"""Configuration regression only; actual minified APK/device checks remain required."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def test_separate_instrumentation_trace_entry_points_survive_app_r8():
    # The shared dependency is omitted from androidTest's APK. Its separate DEX
    # still calls these entry points even when the app can inline every caller.
    rules = (ROOT / "app/proguard-rules.pro").read_text()
    block = re.search(
        r"-keep,allowoptimization\s+class\s+androidx\.tracing\.Trace\s*\{([^}]+)\}",
        rules,
    )
    assert block, "Keep only the runner's shared Trace ABI, with optimization enabled"
    methods = {line.strip() for line in block.group(1).splitlines() if line.strip()}
    assert methods == {
        "public static void beginSection(java.lang.String);",
        "public static void endSection();",
        "public static void forceEnableAppTracing();",
    }
    assert "-dontoptimize" not in rules
    assert "-dontshrink" not in rules
    assert "-dontobfuscate" not in rules
