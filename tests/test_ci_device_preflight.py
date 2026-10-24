"""Early readonly KVM guard contracts; these do not execute an Android emulator."""
from pathlib import Path
import subprocess
import textwrap

ROOT = Path(__file__).resolve().parents[1]
NAME = "Verify existing KVM access for accelerated Android emulation"
SCRIPT = '''if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
  echo "::error::Runner lacks existing read/write access to /dev/kvm; configure authorized runner access before executing device tests."
  exit 1
fi
'''


def device_job():
    workflow = (ROOT / ".github/workflows/verify.yml").read_text()
    return workflow.split("  device:\n", 1)[1].split("  candidate-evidence:\n", 1)[0]


def preflight_script():
    job = device_job()
    section = job.split("      - name: " + NAME + "\n", 1)[1].split("      - uses:", 1)[0]
    assert section.startswith("        run: |\n")
    return textwrap.dedent(section[len("        run: |\n"):])


def test_kvm_guard_is_first_step_before_tools_sdk_or_candidate_download():
    job = device_job()
    assert job.count(NAME) == 1
    assert job.split("    steps:\n", 1)[1].startswith("      - name: " + NAME + "\n")
    guard = job.index(NAME)
    for action in ("actions/checkout@", "actions/setup-python@", "actions/setup-java@",
                   "android-actions/setup-android@", "actions/download-artifact@",
                   "reactivecircus/android-emulator-runner@"):
        assert guard < job.index(action)
    assert "    needs: android\n" in job
    assert "    runs-on: ubuntu-24.04\n" in job


def test_guard_keeps_exact_readonly_fail_closed_policy():
    assert preflight_script() == SCRIPT
    assert "continue-on-error" not in device_job()


def test_actual_guard_shell_blocks_missing_path_and_accepts_readwrite_fixture(tmp_path):
    # Only substitute the path for shell semantics. This is not KVM admission proof.
    path = tmp_path / "synthetic-device"
    script = preflight_script().replace("/dev/kvm", str(path))
    missing = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=5)
    assert missing.returncode == 1
    assert "::error::Runner lacks existing read/write access" in missing.stdout
    path.write_bytes(b"host-fixture-only")
    ready = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=5)
    assert ready.returncode == 0
    assert not ready.stdout and not ready.stderr
    assert path.read_bytes() == b"host-fixture-only"
