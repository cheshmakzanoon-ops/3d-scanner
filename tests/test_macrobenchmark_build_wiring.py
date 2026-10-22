"""Source regression guards; actual Gradle/APK validation remains a separate gate."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_benchmark_module_and_reviewed_convention_remain_connected():
    assert 'include(":macrobenchmark")' in (ROOT / "settings.gradle.kts").read_text()
    fragment = (ROOT / "gradle/macrobenchmark-validation.gradle.kts").read_text()
    body = "\n".join(line for line in fragment.splitlines() if not line.startswith("import "))
    app = (ROOT / "app/build.gradle.kts").read_text()
    assert body.strip() in app
    assert 'apply(from = "../gradle/macrobenchmark-validation.gradle.kts")' not in app


def test_ordinary_benchmark_configurations_consume_locks():
    app = (ROOT / "app/build.gradle.kts").read_text()
    locked = app.split("val lockedAndroidConfigurations = setOf(", 1)[1].split(")", 1)[0]
    for name in ("benchmarkCompileClasspath", "benchmarkRuntimeClasspath", "kspBenchmarkKotlinProcessorClasspath"):
        assert f'"{name}"' in locked
    assert "if (name in lockedAndroidConfigurations) resolutionStrategy.activateDependencyLocking()" in app
    driver = (ROOT / "macrobenchmark/build.gradle.kts").read_text()
    assert "if (name in lockedBenchmarkConfigurations) resolutionStrategy.activateDependencyLocking()" in driver
    assert 'buildToolsVersion = "36.0.0"' in driver
    assert "kotlin { jvmToolchain(17) }" in driver


def test_benchmark_adapter_is_not_in_the_shipping_manifest():
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
    assert "BenchmarkViewerActivity" not in manifest
    assert "profileable" not in manifest


def test_benchmark_target_keeps_the_reviewed_release_dependency_graph():
    states = {}
    for line in (ROOT / "app/gradle.lockfile").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        module, configurations = line.split("=", 1)
        for configuration in configurations.split(","):
            states.setdefault(configuration, set()).add(module)
    for benchmark, release in (
        ("benchmarkCompileClasspath", "releaseCompileClasspath"),
        ("benchmarkRuntimeClasspath", "releaseRuntimeClasspath"),
        ("kspBenchmarkKotlinProcessorClasspath", "kspReleaseKotlinProcessorClasspath"),
    ):
        assert states.get(benchmark)
        assert states[benchmark] == states[release]


def test_ci_builds_and_audits_real_benchmark_packages():
    workflow = (ROOT / ".github/workflows/verify.yml").read_text()
    assert ":app:assembleBenchmark :macrobenchmark:assembleBenchmark" in workflow
    assert "python tools/device/ui_performance/validate_target.py" in workflow
    assert "needs: [native, android, device, benchmark-build]" in workflow
