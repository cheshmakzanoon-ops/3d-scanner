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
    for artifact in ('espresso-core', 'espresso-idling-resource'):
        key = f'androidx.test.espresso:{artifact}:3.6.1'
        assert key in locks
        assert f'androidx.test.espresso:{artifact}:3.5.0' not in locks
        assert locks[key] and all('AndroidTest' in name for name in locks[key])
        assert {'debugAndroidTestRuntimeClasspath', 'releaseAndroidTestRuntimeClasspath'} <= locks[key]
    annotations = locks['com.google.errorprone:error_prone_annotations:2.26.1']
    assert {'debugAndroidTestRuntimeClasspath', 'releaseAndroidTestRuntimeClasspath'} <= annotations
