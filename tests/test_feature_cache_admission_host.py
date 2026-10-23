"""Real Kotlin/JVM cache admission at 48 MiB; no Android/OpenCV or downloads.

Set RF_KOTLIN_HOME (official Kotlin distribution), RF_JUNIT_JAR (4.13.2),
RF_HAMCREST_JAR (1.3), optionally RF_JAVA. Otherwise use PATH/Gradle cache.
Missing capability is explicitly NOT_RUN, never a synthetic runtime pass.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[1]


def test_feature_cache_admission_with_bounded_real_jvm(tmp_path):
    java = os.environ.get("RF_JAVA") or shutil.which("java")
    home = os.environ.get("RF_KOTLIN_HOME") or os.environ.get("KOTLIN_HOME")
    kotlinc = str(Path(home) / "bin/kotlinc") if home else shutil.which("kotlinc")
    if not java or not kotlinc:
        pytest.skip("JDK/Kotlin compiler unavailable; bounded cache JVM regression NOT_RUN")
    # Explicitly supplied paths are configuration errors, not missing-capability skips.
    assert Path(java).is_file(), f"Invalid Java path: {java}"
    assert Path(kotlinc).is_file(), f"Invalid Kotlin compiler path: {kotlinc}"
    stdlib = Path(kotlinc).resolve().parents[1] / "lib/kotlin-stdlib.jar"
    assert stdlib.is_file(), f"Kotlin distribution stdlib missing: {stdlib}"
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"

    def jar(variable, group, name, version):
        override = os.environ.get(variable)
        if override:
            path = Path(override)
            assert path.is_file(), f"Invalid {variable}: {path}"
            return path
        candidates = list((cache / group / name / version).glob("*/*.jar"))
        if len(candidates) != 1:
            pytest.skip(f"Pinned {name} {version} unavailable; bounded cache JVM regression NOT_RUN")
        return candidates[0]

    runtime = [stdlib, jar("RF_JUNIT_JAR", "junit", "junit", "4.13.2"),
               jar("RF_HAMCREST_JAR", "org.hamcrest", "hamcrest-core", "1.3")]
    sources = [ROOT / f"app/src/{kind}/java/com/realityforge/scanner/engine/features/{name}.kt"
               for kind, name in [("main", "FeatureCacheFormat"), ("main", "FeatureCache"),
                                  ("test", "FeatureCacheAdmissionTest"), ("test", "FeatureCacheTest")]]
    tracked = [*sources, *runtime]
    before = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in tracked}
    env = os.environ.copy()
    for variable in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "KOTLIN_OPTS"):
        env.pop(variable, None)
    classes = tmp_path / "classes"
    cp = os.pathsep.join(map(str, runtime))
    compile_result = subprocess.run(
        [kotlinc, "-J-Xmx384m", "-J-XX:ActiveProcessorCount=2", "-jvm-target", "17",
         "-classpath", cp, "-d", str(classes), *map(str, sources)],
        env=env, text=True, capture_output=True, timeout=120)
    (tmp_path / "compile.log").write_text(compile_result.stdout + compile_result.stderr)
    assert compile_result.returncode == 0, compile_result.stdout + compile_result.stderr
    result = subprocess.run(
        [java, "-Xmx48m", "-XX:ActiveProcessorCount=2", "-cp", str(classes) + os.pathsep + cp,
         "org.junit.runner.JUnitCore",
         "com.realityforge.scanner.engine.features.FeatureCacheAdmissionTest",
         "com.realityforge.scanner.engine.features.FeatureCacheTest"],
        env=env, text=True, capture_output=True, timeout=90)
    (tmp_path / "junit.log").write_text(result.stdout + result.stderr)
    assert {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in tracked} == before
    assert result.returncode == 0, result.stdout + result.stderr
    assert "OK (15 tests)" in result.stdout, result.stdout + result.stderr
