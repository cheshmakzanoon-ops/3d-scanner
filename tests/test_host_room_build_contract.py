"""Static wiring contracts only; these do not execute or qualify Android/Room."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "app/build.gradle.kts"
SDK_VERSION = "15-robolectric-12650502"
SDK_SHA256 = "6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b"
HOST_PACKAGE = "com.realityforge.scanner.hostintegration"


def test_sdk_is_a_separate_pinned_locked_non_transitive_configuration():
    build = BUILD.read_text()
    config = build.split('configurations.create("robolectricSdk") {', 1)[1].split("\n}", 1)[0]
    for setting in ("isCanBeConsumed = false", "isCanBeResolved = true",
                    "isTransitive = false", "resolutionStrategy.activateDependencyLocking()"):
        assert setting in config
    assert f'add(robolectricSdk.name, "org.robolectric:android-all:{SDK_VERSION}")' in build
    assert f'org.robolectric:android-all:{SDK_VERSION}=robolectricSdk' in (ROOT / "app/gradle.lockfile").read_text().splitlines()
    assert not re.search(r'(?:implementation|api|runtimeOnly)\("org\.robolectric:', build)
    metadata = ET.parse(ROOT / "gradle/verification-metadata.xml")
    ns = {"v": "https://schema.gradle.org/dependency-verification"}
    sdk = metadata.find(f'.//v:component[@group="org.robolectric"][@name="android-all"][@version="{SDK_VERSION}"]', ns)
    assert sdk is not None
    artifact = sdk.find(f'v:artifact[@name="android-all-{SDK_VERSION}.jar"]', ns)
    assert artifact is not None
    assert artifact.find(f'v:sha256[@value="{SDK_SHA256}"]', ns) is not None


def test_offline_sdk_resolver_and_both_byte_identity_checks_are_wired():
    build = BUILD.read_text()
    assert 'systemProperty("robolectric.offline", "true")' in build
    assert 'systemProperty("robolectric.usePreinstrumentedJars", "false")' in build
    assert 'systemProperty("robolectric.dependency.dir", hostSdkDirectory.get().asFile.absolutePath)' in build
    assert '!systemProperties.containsKey("robolectric-deps.properties")' in build
    guard = build.split('tasks.register("verifyHostIntegrationSdk") {', 1)[1].split('val verifyHostIntegrationClassification', 1)[0]
    assert SDK_SHA256 in guard
    assert "digest(source) == expected" in guard
    assert "digest(staged) == expected" in guard
    assert "== listOf(canonical)" in guard
    assert "outputs." not in guard  # guard must run even if SDK staging is up-to-date
    assert "dependsOn(verifyHostIntegrationSdk)" in build
    assert "/tmp/" not in build


def test_robolectric_sources_and_discovery_stay_in_explicit_host_gate():
    build = BUILD.read_text()
    assert 'if (name != "hostIntegrationTest") exclude("**/hostintegration/**")' in build
    assert f'includeTestsMatching("{HOST_PACKAGE}.*")' in build
    assert "isFailOnNoMatchingTests = true" in build
    assert "dependsOn(verifyHostIntegrationClassification)" in build
    assert "maxParallelForks = 1" in build
    assert 'maxHeapSize = "1g"' in build
    assert "timeout.set(Duration.ofMinutes(5))" in build
    host = build.split('tasks.register<Test>("hostIntegrationTest") {', 1)[1]
    assert "ordinary.map" not in host
    assert "dependsOn(ordinary.taskDependencies.getDependencies(ordinary))" in host
    sources = [p for p in (ROOT / "app/src/test").rglob("*")
               if p.suffix in (".kt", ".java") and "org.robolectric" in p.read_text()]
    assert sources
    for source in sources:
        assert "/com/realityforge/scanner/hostintegration/" in source.as_posix()
        assert re.search(rf"^package\s+{re.escape(HOST_PACKAGE)}(?:[.;\s]|$)", source.read_text(), re.M)


def test_ci_runs_and_collects_both_distinct_host_and_jvm_gates():
    workflow = (ROOT / ".github/workflows/verify.yml").read_text()
    assert ":app:testDebugUnitTest" in workflow
    assert "--max-workers=1 --no-parallel :app:hostIntegrationTest" in workflow
    for gate in ("testDebugUnitTest", "hostIntegrationTest"):
        assert f"--junit 'app/build/test-results/{gate}/TEST-*.xml'" in workflow
        assert f"app/build/test-results/{gate}/" in workflow
    assert "not device qualification" in workflow
