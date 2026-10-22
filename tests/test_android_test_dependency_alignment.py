"""Pinned white-box test ABI alignment; not an Android runtime-pass claim."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_espresso_capture_api_and_annotation_pin_are_test_only():
    build = (ROOT / 'app/build.gradle.kts').read_text()
    assert 'androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")' in build
    assert 'add("androidTestImplementation", "com.google.errorprone:error_prone_annotations:2.26.1")' in build
    convention = (ROOT / "gradle/release-validation.gradle.kts").read_text()
    assert 'add("androidTestImplementation", "com.google.errorprone:error_prone_annotations:2.26.1")' in convention
    locks = {}
    for line in (ROOT / 'app/gradle.lockfile').read_text().splitlines():
        if line and not line.startswith('#'):
            key, configs = line.split('=', 1)
            locks[key] = set(configs.split(','))
    android_tests = {
        'debugAndroidTestCompileClasspath', 'debugAndroidTestRuntimeClasspath',
        'releaseAndroidTestCompileClasspath', 'releaseAndroidTestRuntimeClasspath',
    }
    unit_runtime = {'debugUnitTestRuntimeClasspath', 'releaseUnitTestRuntimeClasspath'}
    for artifact in ('espresso-core', 'espresso-idling-resource'):
        key = f'androidx.test.espresso:{artifact}:3.6.1'
        assert key in locks
        assert f'androidx.test.espresso:{artifact}:3.5.0' not in locks
        # Robolectric4.14.1 declares this exact idling AAR in its runtime variant.
        # Do not broaden espresso-core, unit compile, APK or instrumentation scopes.
        allowed = android_tests | (unit_runtime if artifact == 'espresso-idling-resource' else set())
        assert locks[key] and locks[key] <= allowed
        assert {'debugAndroidTestRuntimeClasspath', 'releaseAndroidTestRuntimeClasspath'} <= locks[key]
    annotations = locks['com.google.errorprone:error_prone_annotations:2.26.1']
    assert {'debugAndroidTestRuntimeClasspath', 'releaseAndroidTestRuntimeClasspath'} <= annotations
    assert annotations <= android_tests | {
        'kspDebugKotlinProcessorClasspath', 'kspReleaseKotlinProcessorClasspath',
        'kspBenchmarkKotlinProcessorClasspath',
    }
    host_annotations = locks['com.google.errorprone:error_prone_annotations:2.28.0']
    assert host_annotations == unit_runtime | {'debugUnitTestCompileClasspath', 'releaseUnitTestCompileClasspath'}
