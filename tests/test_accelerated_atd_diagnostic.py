"""Host-only controls; no emulator, KVM access mutation or Android execution."""
import contextlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest import mock

from tools.device.accelerated_emulator import diagnostic as diag


def environment():
    return {"RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux",
            "RUNNER_ARCH": "X64", "GITHUB_SHA": "a" * 40,
            "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
            "GITHUB_REPOSITORY": diag.base.PUSH_REPOSITORY}


def event():
    return {"repository": {"private": False, "full_name": diag.base.PUSH_REPOSITORY}}


class AcceleratedControls(unittest.TestCase):
    def test_manual_main_only(self):
        diag.admit_manual(environment(), event())
        for key, value in (("GITHUB_EVENT_NAME", "push"), ("GITHUB_REF", "refs/heads/other"),
                           ("GITHUB_REPOSITORY", "other/repo"), ("RUNNER_ENVIRONMENT", "self-hosted"),
                           ("RUNNER_ARCH", "ARM64"), ("GITHUB_SHA", "bad")):
            with self.subTest(key=key), self.assertRaises(diag.base.Blocked):
                diag.admit_manual({**environment(), key: value}, event())
        for repository in ({"private": True, "full_name": diag.base.PUSH_REPOSITORY},
                           {"private": False, "full_name": "other/repo"}):
            with self.assertRaises(diag.base.Blocked):
                diag.admit_manual(environment(), {"repository": repository})

    def test_commands_remain_separate(self):
        for cls, expected, tcg in ((diag.base.Controller, "off", "yes"),
                                  (diag.Controller, "on", None)):
            obj = cls(env={"ANDROID_I_WANT_MY_TCG": "inherited"})
            obj.emulator_path = "/synthetic/emulator"
            args = obj.emulator_command("test")
            self.assertEqual(args.count("-accel"), 1)
            self.assertEqual(args[args.index("-accel") + 1], expected)
            self.assertEqual(obj.env.get("ANDROID_I_WANT_MY_TCG"), tcg)
            self.assertEqual(args[args.index("-gpu") + 1], "software")
            self.assertEqual(obj.receipt["qualification"], diag.base.base_receipt()["qualification"])
        self.assertEqual(diag.Controller(env={}).receipt["cpuMode"], "KVM_REQUIRED_NO_FALLBACK")

    def test_junit_cpu_identity_matches_receipt(self):
        for cls, name in ((diag.base.Controller, "unsupported-software-emulator-focused"),
                          (diag.Controller, "accelerated-atd-focused")):
            receipt = cls(env={}).receipt
            receipt.update(sourceSha="a" * 40, appSha256="b" * 64, testSha256="c" * 64)
            xml = ET.fromstring(diag.base.junit_xml({"rawSha256": "d" * 64, "tests": len(receipt["expectedSelectors"]),
                "passed": len(receipt["expectedSelectors"])}, receipt))
            self.assertEqual(xml.get("name"), name)
            properties = {p.get("name"): p.get("value") for p in xml.findall("properties/property")}
            self.assertEqual(properties["cpuMode"], receipt["cpuMode"])
        receipt["cpuMode"] = "UNKNOWN"
        with self.assertRaises(diag.base.Failure):
            diag.base.junit_xml({"rawSha256": "d" * 64, "tests": len(receipt["expectedSelectors"]),
                "passed": len(receipt["expectedSelectors"])}, receipt)

    def test_kvm_query_closes_owned_descriptor(self):
        with mock.patch.object(diag.os, "open", return_value=73) as opened, \
             mock.patch.object(diag.os, "fstat", return_value=mock.Mock(st_mode=stat.S_IFCHR)), \
             mock.patch.object(diag.fcntl, "ioctl", return_value=12) as ioctl, \
             mock.patch.object(diag.os, "close") as close:
            self.assertFalse(diag.check_kvm()["permissionChanges"])
            opened.assert_called_once_with("/dev/kvm", os.O_RDWR | os.O_CLOEXEC | os.O_NOFOLLOW)
            ioctl.assert_called_once_with(73, 0xAE00, 0)
            close.assert_called_once_with(73)

    def test_missing_kvm_refuses_without_setup(self):
        with mock.patch.object(diag.os, "open", side_effect=PermissionError), self.assertRaises(diag.base.Blocked):
            diag.check_kvm()

    def test_wrong_device_or_api_closes_fd(self):
        for mode, version in ((stat.S_IFREG, 12), (stat.S_IFCHR, 11)):
            with mock.patch.object(diag.os, "open", return_value=74), \
                 mock.patch.object(diag.os, "fstat", return_value=mock.Mock(st_mode=mode)), \
                 mock.patch.object(diag.fcntl, "ioctl", return_value=version), \
                 mock.patch.object(diag.os, "close") as close, self.assertRaises(diag.base.Blocked):
                try:
                    diag.check_kvm()
                finally:
                    close.assert_called_once_with(74)

    def test_admission_before_sdk_and_build(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "event.json"
            path.write_text(json.dumps(event()))
            obj = diag.Controller(env={**environment(), "GITHUB_EVENT_PATH": str(path)})
            with mock.patch.object(diag, "check_kvm", side_effect=diag.base.Blocked("no existing KVM")), \
                 mock.patch.object(diag.base.Controller, "prerequisites") as setup, \
                 mock.patch.object(obj, "build") as build, contextlib.redirect_stdout(io.StringIO()) as stream:
                self.assertEqual(obj.run(), 1)
                setup.assert_not_called()
                build.assert_not_called()
            self.assertEqual(obj.receipt["status"], "BLOCKED")
            self.assertNotIn("RF_DIAGNOSTIC_JUNIT", stream.getvalue())

    def test_inherited_failure_cleanup_and_no_success(self):
        obj = diag.Controller(env={})
        temp = mock.Mock()
        def setup():
            obj.temporary = temp
        with mock.patch.object(obj, "prerequisites", side_effect=setup), \
             mock.patch.object(obj, "build", side_effect=diag.base.Failure("synthetic build failure")), \
             mock.patch.object(obj, "boot") as boot, \
             mock.patch.object(diag.base, "stop_owned") as stop, \
             contextlib.redirect_stdout(io.StringIO()) as stream:
            self.assertEqual(obj.run(), 1)
            temp.cleanup.assert_called_once()
            boot.assert_not_called()
            self.assertEqual(stop.call_count, 2)
            self.assertNotIn("RF_DIAGNOSTIC_JUNIT", stream.getvalue())

    def test_accelerated_scope_remains_four_without_new_software_pnp_selector(self):
        obj = diag.Controller(env={})
        self.assertEqual(obj.receipt["expectedSelectors"], list(diag.base.ORIGINAL_EXPECTED))
        self.assertEqual(len(obj.receipt["expectedSelectors"]), 4)
        self.assertNotIn("CenteredTranslation", " ".join(obj.receipt["expectedSelectors"]))
        self.assertEqual(diag.base.Controller(env={}).receipt["expectedSelectors"], list(diag.base.EXPECTED))
        self.assertEqual(len(diag.base.EXPECTED), 5)

    def test_workflow_manual_without_mutators_or_acceptance(self):
        text = (diag.ROOT / ".github/workflows/accelerated-atd-diagnostic.yml").read_text()
        self.assertIn("workflow_dispatch:", text)
        self.assertNotIn("  push:", text)
        self.assertIn("persist-credentials: false", text)
        for forbidden in ("sudo ", "chmod", "setfacl", "setup-android", "android-emulator-runner", "upload-artifact", "actions/cache"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
