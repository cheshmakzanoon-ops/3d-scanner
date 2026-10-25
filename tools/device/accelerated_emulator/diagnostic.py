#!/usr/bin/env python3
"""Manual, fail-closed KVM variant of four existing non-UI ATD checks.

No permission mutation or license acceptance. CPU acceleration does not make
ATD's disabled rendering or four synthetic methods full-device qualification.
"""
import fcntl
import json
import os
from pathlib import Path
import stat
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from tools.device.software_emulator import diagnostic as base


def admit_manual(env, event):
    base.validate_policy(env, event)
    if (env.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or env.get("GITHUB_REPOSITORY") != base.PUSH_REPOSITORY
            or event.get("repository", {}).get("full_name") != base.PUSH_REPOSITORY
            or env.get("GITHUB_REF") != "refs/heads/main"):
        raise base.Blocked("Accelerated diagnostic requires explicit main-branch manual dispatch")


def check_kvm():
    """Only open the existing character device and query API; never change access."""
    try:
        fd = os.open("/dev/kvm", os.O_RDWR | os.O_CLOEXEC | os.O_NOFOLLOW)
        try:
            info = os.fstat(fd)
            if not stat.S_ISCHR(info.st_mode):
                raise base.Blocked("Existing /dev/kvm is not a character device")
            version = fcntl.ioctl(fd, 0xAE00, 0)  # KVM_GET_API_VERSION, read-only query
            if version != 12:
                raise base.Blocked("Unsupported KVM API version")
            return {"apiVersion": version, "access": "existing-user-read-write",
                    "permissionChanges": False}
        finally:
            os.close(fd)
    except OSError as error:
        raise base.Blocked("Existing KVM access unavailable; no access changes attempted") from error


class Controller(base.Controller):
    CPU_MODE = "KVM_REQUIRED_NO_FALLBACK"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.env.pop("ANDROID_I_WANT_MY_TCG", None)
        self.receipt.update(schema="realityforge.accelerated-atd-diagnostic.v1",
                            cpuMode="KVM_REQUIRED_NO_FALLBACK")

    def prerequisites(self):
        event = json.loads(Path(self.env["GITHUB_EVENT_PATH"]).read_text())
        admit_manual(self.env, event)
        self.receipt["kvm"] = check_kvm()
        # Inherits exact-source checks, preaccepted-license snapshots, closed-stdin
        # downloads, bounded setup and original tool/package/provenance checks.
        super().prerequisites()

    def emulator_command(self, name):
        # Reuse the unchanged launch options; never launch the software command.
        args = super().emulator_command(name)
        self.env.pop("ANDROID_I_WANT_MY_TCG", None)
        index = args.index("-accel")
        if args[index + 1] != "off" or args.count("-accel") != 1:
            raise base.Blocked("Unexpected shared emulator command shape")
        args[index + 1] = "on"
        return args


if __name__ == "__main__":
    raise SystemExit(Controller().run())
