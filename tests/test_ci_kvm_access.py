"""CI must inspect existing KVM access, never broaden runner permissions."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class KvmAccessContract(unittest.TestCase):
    def test_device_gate_is_read_only_and_fails_without_access(self):
        workflow = (ROOT / ".github/workflows/verify.yml").read_text()
        start = workflow.index("      - name: Verify existing KVM access")
        end = workflow.index("      - uses:", start)
        gate = workflow[start:end]
        self.assertIn("[ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]", gate)
        self.assertIn("exit 1", gate)
        for command in ("sudo", "chmod", "chown", "udevadm", "tee", "0666"):
            self.assertNotIn(command, gate)
        self.assertNotIn("99-kvm4all.rules", workflow)
        self.assertNotIn("continue-on-error: true", gate)

if __name__ == "__main__":
    unittest.main()
