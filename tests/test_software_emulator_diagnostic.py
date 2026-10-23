"""Host checks for the supplemental controller; these are not Android execution."""
import contextlib
import base64
import copy
import hashlib
import importlib.util
import io
import json
import os
import signal
import shutil
import socket
from pathlib import Path
import subprocess
import sys
import tempfile
import tarfile
import time
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

from tools.native_process import native_compile_env

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/device/software_emulator/diagnostic.py"
spec = importlib.util.spec_from_file_location("software_diagnostic", SCRIPT)
diag = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diag)


def event(selector, index, code, total=4):
    owner, method = selector.split("#")
    return (f"INSTRUMENTATION_STATUS: class={owner}\n"
            f"INSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS: current={index}\n"
            f"INSTRUMENTATION_STATUS: numtests={total}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def transcript(selectors=diag.EXPECTED):
    return "".join(event(test, index, 1) + event(test, index, 0)
                   for index, test in enumerate(selectors, 1)) + "\nOK (4 tests)\nINSTRUMENTATION_CODE: -1\n"


def receipt():
    return {**diag.base_receipt(), "sourceSha": "a" * 40, "appSha256": "b" * 64, "testSha256": "c" * 64}


class RawInstrumentationEvidence(unittest.TestCase):
    def capture(self, text):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            diag.emit_raw_instrumentation(text)
        lines = output.getvalue().splitlines()
        metadata = json.loads(lines[0].removeprefix("RF_INSTRUMENTATION_RAW_META "))
        chunks = []
        for index, line in enumerate(lines[1:], 1):
            prefix, position, encoded = line.split(" ", 2)
            self.assertEqual(prefix, "RF_INSTRUMENTATION_RAW_CHUNK")
            self.assertEqual(position, f"{index}/{metadata['chunks']}")
            self.assertLessEqual(len(encoded), 4096)
            self.assertTrue(encoded.isascii())
            chunks.append(encoded)
        self.assertEqual(len(chunks), metadata["chunks"])
        raw = base64.b64decode("".join(chunks), validate=True)
        self.assertEqual(raw, text.encode("utf-8"))
        self.assertEqual(len(raw), metadata["bytes"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), metadata["sha256"])
        return metadata, raw

    def test_exact_bytes_survive_controls_unicode_and_chunk_boundaries(self):
        metadata, _ = self.capture(("\x00\r\nΩ::error::not-a-command\n" * 3000))
        self.assertGreater(metadata["chunks"], 1)

    def test_reconstructed_transcript_hash_binds_the_validated_events(self):
        metadata, raw = self.capture(transcript())
        proof = diag.parse_instrumentation(raw.decode("utf-8"))
        self.assertEqual(proof["rawSha256"], metadata["sha256"])
        self.assertEqual(proof["passed"], 4)

    def test_oversized_transcript_fails_before_emitting_any_evidence(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaises(diag.Failure):
            diag.emit_raw_instrumentation("x" * (diag.INSTRUMENTATION_LIMIT + 1))
        self.assertEqual(output.getvalue(), "")


class InstrumentationProof(unittest.TestCase):
    def test_requires_four_real_start_and_terminal_events(self):
        proof = diag.parse_instrumentation(transcript())
        self.assertEqual((proof["tests"], proof["passed"], proof["skipped"], len(proof["events"])), (4, 4, 0, 8))

    def test_junit_execution_order_may_differ_but_identities_must_match(self):
        self.assertEqual(diag.parse_instrumentation(transcript(tuple(reversed(diag.EXPECTED))))["passed"], 4)

    def test_adb_exit_and_ok_summary_alone_cannot_pass(self):
        with self.assertRaises(diag.Failure):
            diag.parse_instrumentation("OK (4 tests)\nINSTRUMENTATION_CODE: -1\n")

    def test_skip_assumption_failure_error_and_unknown_codes_fail(self):
        for code in (-4, -3, -2, -1, 7):
            with self.subTest(code=code), self.assertRaises(diag.Failure):
                diag.parse_instrumentation(transcript().replace("INSTRUMENTATION_STATUS_CODE: 0", f"INSTRUMENTATION_STATUS_CODE: {code}", 1))

    def test_missing_start_or_terminal_cannot_pass(self):
        for code in (1, 0):
            with self.subTest(code=code), self.assertRaises(diag.Failure):
                diag.parse_instrumentation(transcript().replace(event(diag.EXPECTED[0], 1, code), "", 1))

    def test_duplicate_identity_with_different_index_fails(self):
        duplicate = (diag.EXPECTED[0], diag.EXPECTED[0], *diag.EXPECTED[2:])
        with self.assertRaises(diag.Failure):
            diag.parse_instrumentation(transcript(duplicate))

    def test_unexpected_class_or_method_fails(self):
        with self.assertRaises(diag.Failure):
            diag.parse_instrumentation(transcript().replace(diag.EXPECTED[0].split("#")[1], "unrelatedTest"))

    def test_total_index_summary_and_final_code_must_be_consistent(self):
        replacements = (("numtests=4", "numtests=5"), ("current=2", "current=1"),
                        ("OK (4 tests)", "OK (3 tests)"), ("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"))
        for old, new in replacements:
            with self.subTest(old=old), self.assertRaises(diag.Failure):
                diag.parse_instrumentation(transcript().replace(old, new))

    def test_duplicate_fields_and_final_events_fail(self):
        cases = (transcript().replace("INSTRUMENTATION_STATUS: current=1", "INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: current=1"),
                 transcript() + "INSTRUMENTATION_CODE: -1\n",
                 transcript() + event(diag.EXPECTED[0], 1, 1),
                 transcript() + "INSTRUMENTATION_STATUS: class=truncated\n")
        for value in cases:
            with self.subTest(value=value[-100:]), self.assertRaises(diag.Failure):
                diag.parse_instrumentation(value)

    def test_every_final_code_prefix_must_be_canonical_and_unique(self):
        for suffix in ("INSTRUMENTATION_CODE: invalid\n", "INSTRUMENTATION_CODE: -1 trailing\n",
                       "INSTRUMENTATION_CODE: -1\n", "INSTRUMENTATION_CODE missing-colon\n"):
            with self.subTest(suffix=suffix), self.assertRaises(diag.Failure):
                diag.parse_instrumentation(transcript() + suffix)
        with self.assertRaises(diag.Failure):
            diag.parse_instrumentation(transcript().replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: invalid"))

    def test_crash_markers_override_a_success_summary(self):
        for marker in ("Process crashed", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_RESULT: shortMsg=crash"):
            with self.subTest(marker=marker), self.assertRaises(diag.Failure):
                diag.parse_instrumentation(transcript() + marker)

    def test_proof_overflow_fails_instead_of_parsing_truncated_success(self):
        with self.assertRaises(diag.Failure):
            diag.parse_instrumentation(transcript() + "x" * diag.INSTRUMENTATION_LIMIT)

    def test_junit_is_bound_to_proof_candidate_and_exact_test_names(self):
        proof = diag.parse_instrumentation(transcript())
        xml = ET.fromstring(diag.junit_xml(proof, receipt()))
        self.assertEqual(xml.attrib["tests"], "4")
        self.assertEqual(xml.attrib["failures"], "0")
        self.assertEqual(xml.attrib["skipped"], "0")
        self.assertEqual({node.attrib["classname"] + "#" + node.attrib["name"] for node in xml.findall("testcase")}, set(diag.EXPECTED))
        properties = {node.get("name"): node.get("value") for node in xml.findall("properties/property")}
        self.assertEqual(properties["sourceSha"], "a" * 40)
        self.assertEqual(properties["rawSha256"], proof["rawSha256"])
        self.assertEqual(properties["cpuMode"], "UNSUPPORTED_TCG")


class Policy(unittest.TestCase):
    def setUp(self):
        self.env = {"GITHUB_EVENT_NAME": "workflow_dispatch", "RUNNER_ENVIRONMENT": "github-hosted",
                    "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64", "GITHUB_SHA": "a" * 40}
        self.event = {"repository": {"private": False}}

    def test_public_manual_standard_environment(self):
        diag.validate_policy(self.env, self.event)

    def test_no_push_pr_self_hosted_windows_arm_or_unbound_source(self):
        for key, value in (("GITHUB_EVENT_NAME", "push"), ("GITHUB_EVENT_NAME", "pull_request"),
                           ("RUNNER_ENVIRONMENT", "self-hosted"), ("RUNNER_OS", "Windows"),
                           ("RUNNER_ARCH", "ARM64"), ("GITHUB_SHA", "main")):
            with self.subTest(key=key), self.assertRaises(diag.Blocked):
                diag.validate_policy({**self.env, key: value}, self.event)

    def test_private_or_unknown_repository_visibility_blocked(self):
        for event_data in ({}, {"repository": {}}, {"repository": {"private": True}}, {"repository": {"private": "false"}}):
            with self.subTest(event=event_data), self.assertRaises(diag.Blocked):
                diag.validate_policy(self.env, event_data)

    def test_workflow_is_opt_in_single_standard_runner_no_remote_storage(self):
        workflow = (ROOT / ".github/workflows/software-emulator-diagnostic.yml").read_text()
        self.assertIn("on:\n  workflow_dispatch:", workflow)
        self.assertIn("runs-on: ubuntu-24.04", workflow)
        self.assertIn("timeout-minutes: 75", workflow)
        self.assertIn("toJSON(github.event.repository.private) == 'false'", workflow)
        self.assertIn("  push:\n    branches: [main]\n    paths:\n", workflow)
        for value in (diag.PUSH_BASE_SHA, diag.PUSH_MESSAGE, diag.PUSH_REPOSITORY):
            self.assertIn(value, workflow)
        for guard in ("github.event_name == 'workflow_dispatch'", "github.event_name == 'push'",
                      "github.ref == 'refs/heads/main'", "github.event.ref == 'refs/heads/main'",
                      "github.event.repository.default_branch == 'main'",
                      "github.event.after == github.sha", "github.event.head_commit.id == github.sha",
                      "github.run_attempt == '1'", "ref: ${{ github.sha }}", "fetch-depth: 2",
                      "persist-credentials: false"):
            self.assertIn(guard, workflow)
        for key in ("created", "deleted", "forced"):
            self.assertIn(f"toJSON(github.event.{key}) == 'false'", workflow)
        paths = workflow.split("    paths:\n", 1)[1].split("permissions:", 1)[0]
        self.assertEqual({line.strip().removeprefix("- ") for line in paths.splitlines()}, diag.PUSH_TRIGGER_PATHS)
        self.assertEqual(workflow.count("uses:"), 1)
        for token in ("  pull_request:", "schedule:", "workflow_run:", "upload-artifact", "actions/cache",
                      "setup-android", "setup-gradle", "sudo", "chmod", "--licenses", "continue-on-error"):
            self.assertNotIn(token, workflow)

    def test_controller_scope_does_not_claim_any_original_qualification(self):
        value = diag.base_receipt()
        self.assertEqual(set(value["qualification"].values()), {"NOT_RUN"})
        self.assertEqual(value["status"], "NOT_RUN")
        self.assertEqual(value["cpuMode"], "UNSUPPORTED_TCG")
        self.assertEqual(diag.BUDGETS["boot"], 1800)
        self.assertEqual(diag.BUDGETS["instrumentation"], 900)
        self.assertLess(diag.BUDGETS["controller"], diag.BUDGETS["workflow"])

    def test_selected_tests_remain_exactly_four_source_methods(self):
        for selector in diag.EXPECTED:
            owner, method = selector.split("#")
            source = ROOT / "app/src/androidTest/java" / (owner.replace(".", "/") + ".kt")
            self.assertIn("@Test fun " + method + "(", source.read_text())
        self.assertEqual(len(set(diag.EXPECTED)), 4)


class OneShotPushPolicy(unittest.TestCase):
    def setUp(self):
        self.env = {"GITHUB_EVENT_NAME": "push", "RUNNER_ENVIRONMENT": "github-hosted",
            "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64", "GITHUB_SHA": "a" * 40,
            "GITHUB_REPOSITORY": diag.PUSH_REPOSITORY, "GITHUB_REF": "refs/heads/main",
            "GITHUB_RUN_ATTEMPT": "1"}
        self.event = {"repository": {"private": False, "full_name": diag.PUSH_REPOSITORY,
            "default_branch": "main"}, "ref": "refs/heads/main", "before": diag.PUSH_BASE_SHA,
            "after": self.env["GITHUB_SHA"], "head_commit": {"id": self.env["GITHUB_SHA"],
            "message": diag.PUSH_MESSAGE}, "created": False, "deleted": False, "forced": False}
        self.parents = self.env["GITHUB_SHA"] + " " + diag.PUSH_BASE_SHA
        self.changes = "".join("M\0" + path + "\0" for path in sorted(diag.PUSH_ALLOWED_PATHS))

    def test_exact_one_shot_push_and_tree_are_admitted(self):
        launch = diag.validate_policy(self.env, self.event)
        self.assertEqual(launch["event"], "push")
        self.assertEqual(launch["before"], diag.PUSH_BASE_SHA)
        self.assertEqual(diag.validate_push_tree(self.env["GITHUB_SHA"], self.parents,
            diag.PUSH_MESSAGE, self.changes), sorted(diag.PUSH_ALLOWED_PATHS))

    def test_wrong_environment_identity_ref_attempt_and_event_fail_closed(self):
        for key, value in (("GITHUB_REPOSITORY", "fork/scanner"), ("GITHUB_REF", "refs/tags/main"),
                ("GITHUB_RUN_ATTEMPT", "2"), ("GITHUB_RUN_ATTEMPT", ""),
                ("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_EVENT_NAME", "schedule"),
                ("GITHUB_EVENT_NAME", "repository_dispatch"), ("GITHUB_SHA", "b" * 40)):
            with self.subTest(key=key, value=value), self.assertRaises(diag.Blocked):
                diag.validate_policy({**self.env, key: value}, self.event)

    def test_missing_and_conflicting_head_and_push_fields_fail_closed(self):
        for key, wrong in (("ref", "refs/heads/other"), ("before", "b" * 40),
                ("after", "b" * 40), ("head_commit", None)):
            for value in (wrong, None):
                candidate = copy.deepcopy(self.event)
                if value is None:
                    candidate.pop(key)
                else:
                    candidate[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(diag.Blocked):
                    diag.validate_policy(self.env, candidate)
        for head in ({}, {"id": "b" * 40, "message": diag.PUSH_MESSAGE},
                     {"id": self.env["GITHUB_SHA"], "message": "unrelated"},
                     {"id": self.env["GITHUB_SHA"], "message": diag.PUSH_MESSAGE + " extra"},
                     {"id": self.env["GITHUB_SHA"], "message": diag.PUSH_MESSAGE.upper()}):
            with self.subTest(head=head), self.assertRaises(diag.Blocked):
                diag.validate_policy(self.env, {**self.event, "head_commit": head})

    def test_force_delete_create_require_actual_false_not_missing_or_coercible(self):
        for key in ("forced", "created", "deleted"):
            for value in (True, 0, "false", None):
                candidate = {**self.event, key: value}
                if value is None:
                    candidate.pop(key)
                with self.subTest(key=key, value=value), self.assertRaises(diag.Blocked):
                    diag.validate_policy(self.env, candidate)

    def test_fork_unknown_private_and_wrong_default_branch_are_rejected(self):
        for key, value in (("full_name", "fork/scanner"), ("private", True),
                           ("private", 0), ("default_branch", "other")):
            repository = {**self.event["repository"], key: value}
            with self.subTest(key=key), self.assertRaises(diag.Blocked):
                diag.validate_policy(self.env, {**self.event, "repository": repository})
        for repository in ({}, None, []):
            with self.subTest(repository=repository), self.assertRaises(diag.Blocked):
                diag.validate_policy(self.env, {**self.event, "repository": repository})

    def test_merge_missing_history_wrong_parent_head_and_commit_message_rejected(self):
        for parents in ("", self.env["GITHUB_SHA"], self.parents + " " + "b" * 40,
                        self.env["GITHUB_SHA"] + " " + "b" * 40, "b" * 40 + " " + diag.PUSH_BASE_SHA):
            with self.subTest(parents=parents), self.assertRaises(diag.Blocked):
                diag.validate_push_tree(self.env["GITHUB_SHA"], parents, diag.PUSH_MESSAGE, self.changes)
        with self.assertRaises(diag.Blocked):
            diag.validate_push_tree(self.env["GITHUB_SHA"], self.parents, "wrong", self.changes)

    def test_unreviewed_paths_statuses_duplicates_partial_output_and_empty_diff_rejected(self):
        invalid = ("", self.changes[:-1], self.changes + "M\0app/src/main/foreign.kt\0",
            self.changes + "M\0SOURCE_MANIFEST.sha256\0", self.changes.replace("M\0", "D\0", 1),
            self.changes.replace("M\0", "A\0", 1), "R100\0old\0new\0", "M\0SOURCE_MANIFEST.sha256\0")
        for changes in invalid:
            with self.subTest(changes=changes[-100:]), self.assertRaises(diag.Blocked):
                diag.validate_push_tree(self.env["GITHUB_SHA"], self.parents, diag.PUSH_MESSAGE, changes)

    def test_prerequisites_check_source_and_tree_before_any_sdk_tool_or_license(self):
        ctl = diag.Controller(env={**self.env, "GITHUB_EVENT_PATH": "/event.json"})
        def read(path, *args, **kwargs):
            return json.dumps(self.event) if str(path) == "/event.json" else 'ID=ubuntu\nVERSION_ID="24.04"\n'
        with mock.patch.object(diag.Path, "read_text", read), mock.patch.object(diag.platform, "machine", return_value="x86_64"), \
                mock.patch.object(ctl, "source_identity") as source, \
                mock.patch.object(ctl, "git", side_effect=[self.env["GITHUB_SHA"], diag.PUSH_MESSAGE, self.changes]), \
                mock.patch.object(diag, "require_executable") as executable, \
                mock.patch.object(diag, "license_snapshot") as licenses, self.assertRaises(diag.Blocked):
            ctl.prerequisites()
        source.assert_called_once()
        executable.assert_not_called()
        licenses.assert_not_called()

    def test_source_identity_requires_clean_exact_sha_and_full_manifest(self):
        ctl = diag.Controller(env=self.env)
        for head, status in (("b" * 40, ""), (self.env["GITHUB_SHA"], " M changed")):
            with mock.patch.object(ctl, "git", side_effect=[head, status]), \
                    mock.patch.object(ctl, "command") as command, self.assertRaises(diag.Failure):
                ctl.source_identity()
            command.assert_not_called()
        with mock.patch.object(ctl, "git", side_effect=[self.env["GITHUB_SHA"], ""]), \
                mock.patch.object(ctl, "command") as command, mock.patch.object(diag, "sha256", return_value="manifest"):
            ctl.source_identity()
        command.assert_called_once_with("source-manifest", [sys.executable, "tools/source_manifest.py", "verify", "--source", "HEAD"])

    def test_actual_depth_two_checkout_has_provable_parent_but_depth_one_does_not(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            origin = root / "origin"
            origin.mkdir()
            def git(where, *args):
                return subprocess.check_output(["git", "-C", str(where), *args], text=True, stderr=subprocess.PIPE).strip()
            git(origin, "init", "--quiet")
            for path in sorted(diag.PUSH_ALLOWED_PATHS):
                target = origin / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("before\n")
            git(origin, "add", ".")
            git(origin, "-c", "user.name=Host fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "base")
            parent = git(origin, "rev-parse", "HEAD")
            for path in sorted(diag.PUSH_ALLOWED_PATHS):
                (origin / path).write_text("after\n")
            git(origin, "add", ".")
            git(origin, "-c", "user.name=Host fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", diag.PUSH_MESSAGE)
            head = git(origin, "rev-parse", "HEAD")
            for depth in (1, 2):
                checkout = root / f"depth-{depth}"
                subprocess.run(["git", "clone", "--quiet", "--depth", str(depth), origin.as_uri(), str(checkout)], check=True)
                parents = git(checkout, "rev-list", "--parents", "-n", "1", "HEAD")
                with mock.patch.object(diag, "PUSH_BASE_SHA", parent):
                    if depth == 1:
                        with self.assertRaises(diag.Blocked):
                            diag.validate_push_tree(head, parents, diag.PUSH_MESSAGE, self.changes)
                    else:
                        changes = git(checkout, "diff", "--name-status", "--no-renames", "-z", parent, "HEAD", "--")
                        self.assertEqual(diag.validate_push_tree(head, parents, diag.PUSH_MESSAGE, changes), sorted(diag.PUSH_ALLOWED_PATHS))


class PrivateHostLibraries(unittest.TestCase):
    def metadata(self):
        return "Package: libpulse0\nVersion: 1:16.1-1\nArchitecture: amd64\nSize: 123\nSHA256: " + "a" * 64 + "\nFilename: pool/main/p/pulseaudio/libpulse0_16.1-1_amd64.deb\n"

    def archive(self, entries):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w") as tar:
            for name, kind, data in entries:
                item = tarfile.TarInfo(name)
                if kind == "file":
                    item.size = len(data)
                    tar.addfile(item, io.BytesIO(data))
                elif kind == "link":
                    item.type, item.linkname = tarfile.SYMTYPE, data
                    tar.addfile(item)
                else:
                    item.type = tarfile.LNKTYPE
                    item.linkname = data
                    tar.addfile(item)
        return output.getvalue()

    def test_loader_inventory_requires_complete_real_looking_output(self):
        resolved = {}
        text = "linux-vdso.so.1 (0x00001)\nlibpulse.so.0 => not found\nlibc.so.6 => /lib/libc.so.6 (0x123)\n/lib64/ld-linux-x86-64.so.2 (0x456)\n"
        self.assertEqual(diag.loader_missing(text, resolved), {"libpulse.so.0"})
        self.assertEqual(resolved, {"libc.so.6": "/lib/libc.so.6"})
        for output in ("", "statically linked", "libc.so.6: version `GLIBC_9' not found", text + "truncated"):
            with self.subTest(output=output), self.assertRaises(diag.Blocked):
                diag.loader_missing(output)

    def test_apt_plan_rejects_upgrades_removals_unrelated_core_and_duplicate_packages(self):
        good = "Inst libpulse0 (1:16.1-1 Ubuntu:24.04/noble [amd64])\n"
        self.assertEqual(diag.apt_plan(good)["libpulse0"]["version"], "1:16.1-1")
        for value in ("", good + good, good + "Remv libc6 [2.39]\n",
                      good.replace("libpulse0 (", "libpulse0 [1:old] ("),
                      good + "Inst libc6 (2.39 Ubuntu:24.04/noble [amd64])\n",
                      good + "Inst libssl3t64 (3.0 Ubuntu:24.04/noble [amd64])\n"):
            with self.subTest(value=value), self.assertRaises(diag.Blocked):
                diag.apt_plan(value)

    def test_authenticated_metadata_binds_exact_identity_hash_size_and_main_path(self):
        plan = {"version": "1:16.1-1", "architecture": "amd64"}
        self.assertEqual(diag.checked_package(self.metadata(), "libpulse0", plan)["bytes"], 123)
        for old, new in (("amd64", "arm64"), ("1:16.1-1", "wrong"), ("Size: 123", "Size: 999999999"),
                         ("a" * 64, "invalid"), ("pool/main/", "pool/universe/"),
                         ("pool/main/p/", "pool/main/../../")):
            with self.subTest(old=old), self.assertRaises(diag.Blocked):
                diag.checked_package(self.metadata().replace(old, new), "libpulse0", plan)
        with self.assertRaises(diag.Blocked):
            diag.checked_package(self.metadata() + "Package: duplicate\n", "libpulse0", plan)

    def test_archive_extracts_data_and_relative_symlink_without_scripts_or_permissions(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "package"
            data = self.archive([("./usr/lib/libfixture.so.1.0", "file", b"library"),
                                 ("./usr/lib/libfixture.so.1", "link", "libfixture.so.1.0")])
            diag.extract_library_archive(data, root)
            self.assertEqual((root / "usr/lib/libfixture.so.1").read_bytes(), b"library")
            self.assertTrue((root / "usr/lib/libfixture.so.1").resolve().is_relative_to(root))

    def test_archive_rejects_absolute_traversal_hardlink_duplicates_and_symlink_ancestor_before_writes(self):
        invalid = [ [("/escape", "file", b"bad")], [("../escape", "file", b"bad")],
            [("good", "link", "../../escape")], [("good", "link", "/escape")],
            [("good", "hardlink", "elsewhere")], [("good", "file", b"a"), ("good", "file", b"b")],
            [("dir", "link", "inside"), ("dir/file", "file", b"bad")],
            [("control\nname", "file", b"bad")]]
        with tempfile.TemporaryDirectory() as temp:
            for index, entries in enumerate(invalid):
                root = Path(temp) / str(index)
                with self.subTest(entries=entries), self.assertRaises(diag.Blocked):
                    diag.extract_library_archive(self.archive(entries), root)
                self.assertFalse(root.exists())

    def test_archive_size_and_existing_destination_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(diag.Blocked):
                diag.extract_library_archive(self.archive([]), Path(temp))
            with mock.patch.object(diag, "HOST_EXPANDED_LIMIT", 5), self.assertRaises(diag.Blocked):
                diag.extract_library_archive(b"123456", Path(temp) / "new")

    def test_elf_architecture_and_shared_object_type_are_checked(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "library"
            header = bytearray(20)
            header[:6], header[16:18], header[18:20] = b"\x7fELF\x02\x01", (3).to_bytes(2, "little"), (62).to_bytes(2, "little")
            path.write_bytes(header)
            diag.verify_x86_64_shared_object(path)
            for index, value in ((4, 1), (5, 2), (16, 2), (18, 183)):
                bad = header.copy(); bad[index] = value; path.write_bytes(bad)
                with self.subTest(index=index), self.assertRaises(diag.Blocked):
                    diag.verify_x86_64_shared_object(path)

    def test_private_apt_configuration_has_no_host_snippets_credentials_or_trust_changes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            key, status = root / "keyring", root / "status"
            key.write_bytes(b"existing-public-trust"); status.write_bytes(b"host-status")
            config = diag.private_apt_config(root / "apt", key, status)
            text = config.read_text()
            for key_name in ("Parts", "main", "sourceparts", "preferencesparts", "trustedparts", "netrcparts"):
                self.assertIn("Dir::Etc::" + key_name + ' "' + str(root / "apt"), text)
            self.assertIn('Acquire::AllowInsecureRepositories "false";', text)
            self.assertIn('APT::Get::AllowUnauthenticated "false";', text)
            self.assertNotIn("trusted=yes", (root / "apt/ubuntu.sources").read_text())
            self.assertEqual(key.read_bytes(), b"existing-public-trust")
            self.assertEqual(status.read_bytes(), b"host-status")
            self.assertIn("https://archive.ubuntu.com/ubuntu/", (root / "apt/ubuntu.sources").read_text())
            self.assertIn("https://security.ubuntu.com/ubuntu/", (root / "apt/ubuntu.sources").read_text())

    def test_metadata_storage_rejects_size_count_and_links(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / "index").write_bytes(b"1234")
            diag.check_metadata_storage(root, 4, 1)
            for byte_limit, entry_limit in ((3, 1), (4, 0)):
                with self.subTest(byte_limit=byte_limit), self.assertRaises(diag.Blocked):
                    diag.check_metadata_storage(root, byte_limit, entry_limit)
            (root / "link").symlink_to("index")
            with self.assertRaises(diag.Blocked): diag.check_metadata_storage(root)

    def test_metadata_growth_guard_stops_its_real_owned_child(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); ctl = diag.Controller()
            script = "import pathlib,time; pathlib.Path(" + repr(str(root / "index")) + ").write_bytes(b'x'*1024); time.sleep(30)"
            start = time.monotonic()
            with mock.patch.object(diag, "stop_owned", wraps=diag.stop_owned) as stopped, \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(diag.Blocked):
                ctl.command("metadata-growth-fixture", [sys.executable, "-c", script], timeout=5,
                    guard=lambda: diag.check_metadata_storage(root, 64, 10))
            self.assertLess(time.monotonic() - start, 5)
            stopped.assert_called_once()
            self.assertIsNotNone(stopped.call_args.args[0].returncode)

    def test_binary_subprocess_evidence_is_bounded_hash_only_and_env_is_not_global(self):
        ctl, output = diag.Controller(env={"PATH": os.environ["PATH"]}), io.StringIO()
        with contextlib.redirect_stdout(output):
            value, code = ctl.command("binary-fixture", [sys.executable, "-c", "import os,sys;sys.stdout.buffer.write(os.environ['RF_FIXTURE'].encode()+b'\\x00')"],
                env={**ctl.env, "RF_FIXTURE": "private-value"}, binary=True)
        self.assertEqual((value, code), (b"private-value\x00", 0))
        self.assertNotIn("RF_FIXTURE", ctl.env)
        self.assertNotIn("private-value", output.getvalue())
        self.assertIn("binary bytes=14 sha256=", output.getvalue())

    @unittest.skipUnless(shutil.which("cc") and Path("/usr/bin/ldd").is_file(), "Real host C compiler/loader unavailable; ELF fixture NOT_RUN")
    def test_real_host_loader_resolves_only_missing_fixture_library_from_owned_overlay(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); lib = root / "lib"; overlay = root / "overlay"
            lib.mkdir(); overlay.mkdir()
            (root / "lib.c").write_text("int private_fixture(void) { return 7; }\n")
            (root / "main.c").write_text("extern int private_fixture(void); int main(void) { return private_fixture() == 7 ? 0 : 1; }\n")
            soname = "libprivate_rf_fixture.so.1"
            subprocess.run(["cc", "-shared", "-fPIC", "-Wl,-soname," + soname, str(root / "lib.c"), "-o", str(lib / soname)], check=True, capture_output=True, timeout=30, env=native_compile_env())
            subprocess.run(["cc", str(root / "main.c"), "-L" + str(lib), "-l:" + soname, "-o", str(root / "program")], check=True, capture_output=True, timeout=30, env=native_compile_env())
            before = subprocess.check_output(["/usr/bin/ldd", root / "program"], text=True, timeout=10)
            prior = {}; self.assertEqual(diag.loader_missing(before, prior), {soname})
            (overlay / soname).symlink_to(lib / soname)
            env = {**os.environ, "LD_LIBRARY_PATH": str(overlay)}
            after = subprocess.check_output(["/usr/bin/ldd", root / "program"], text=True, env=env, timeout=10)
            resolved = {}; self.assertEqual(diag.loader_missing(after, resolved), set())
            self.assertEqual(Path(resolved[soname]).resolve(), lib / soname)
            self.assertEqual(resolved["libc.so.6"], prior["libc.so.6"])
            diag.verify_x86_64_shared_object(lib / soname)
            subprocess.run([root / "program"], env=env, check=True, timeout=10)


class ObservedLoaderTrace(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for path in ("lib64", "lib64/vulkan", "lib64/gles_swiftshader", "qemu/linux-x86_64"):
            (self.root / path).mkdir(parents=True, exist_ok=True)
        self.backend = self.root / "qemu/linux-x86_64/qemu-system-x86_64-headless"
        self.launcher = self.root / "emulator"
        self.pid = 5143

    def trace(self, include_ld=True, kind="RPATH"):
        text = (f"5143: search path=/launcher-only/not-inherited (LD_LIBRARY_PATH)\n"
                f"5143: initialize program: {self.launcher}\n5143: transferring control: {self.launcher}\n"
                f"5143: search path={self.root}/lib64 ({kind} from file {self.backend})\n")
        if include_ld:
            text += f"5143: search path={self.root}/lib64/vulkan:{self.root}/lib64/gles_swiftshader (LD_LIBRARY_PATH)\n"
        return text + f"5143: initialize program: {self.backend}\n5143: transferring control: {self.backend}\n"

    def test_owned_exec_pair_and_only_its_ld_path_are_used_not_rpath_or_crashpad(self):
        trace = self.trace() + (f"5147: search path=/foreign/child (LD_LIBRARY_PATH)\n"
            f"5147: initialize program: {self.root}/crashpad_handler\n5147: transferring control: {self.root}/crashpad_handler\n")
        evidence = {}
        backend, dirs = diag.observed_loader_paths(trace, self.root, self.pid, evidence=evidence)
        self.assertEqual(backend, self.backend)
        self.assertEqual(dirs, [self.root / "lib64/vulkan", self.root / "lib64/gles_swiftshader"])
        self.assertEqual(evidence["backendPid"], 5143)
        self.assertEqual(evidence["backendIdentityMode"], "paired-initialize-and-control")
        self.assertEqual(evidence["elfSearchRecords"], [{"kind": "RPATH", "origin": str(self.backend.relative_to(self.root)), "directories": ["lib64"]}])

    def test_native_rpath_or_runpath_is_not_promoted_to_ld_library_path(self):
        for kind in ("RPATH", "RUNPATH"):
            with self.subTest(kind=kind):
                evidence = {}
                _, dirs = diag.observed_loader_paths(self.trace(False, kind), self.root, self.pid, evidence=evidence)
                self.assertEqual(dirs, [])
                self.assertEqual(evidence["elfSearchRecords"][0]["kind"], kind)

    def test_missing_conflicting_foreign_duplicate_or_wrong_pid_identity_is_rejected(self):
        good = self.trace()
        other = self.root / "qemu/linux-x86_64/qemu-system-x86_64"
        bad = [good.replace("5143:", "5147:"),
               good.replace(f"transferring control: {self.backend}", f"transferring control: {other}"),
               good + f"5143: initialize program: {self.backend}\n5143: transferring control: {self.backend}\n",
               good + f"5147: initialize program: {self.backend}\n5147: transferring control: {self.backend}\n",
               good.replace(str(self.backend), "/foreign/qemu-system-x86_64-headless"),
               good.replace(f"5143: transferring control: {self.backend}\n", ""),
               good.replace(f"5143: initialize program: {self.backend}\n", ""),
               good.replace(f"5143: initialize program: {self.backend}\n5143: transferring control: {self.backend}",
                            f"5143: transferring control: {self.backend}\n5143: initialize program: {self.backend}"),
               f'qemu backend: argv[00] = "{self.backend}"\nAndroid emulator version 37.2.12.0\n']
        for value in bad:
            with self.subTest(trace=value), self.assertRaises(diag.Blocked):
                diag.observed_loader_paths(value, self.root, self.pid)
        for pid in (None, 0, True, "5143"):
            with self.subTest(pid=pid), self.assertRaises(diag.Blocked):
                diag.observed_loader_paths(good, self.root, pid)

    def test_foreign_or_reordered_selected_sdk_paths_are_rejected(self):
        good = self.trace()
        extra = f"5143: search path={self.root}/lib64/gles_swiftshader:{self.root}/lib64/vulkan (LD_LIBRARY_PATH)\n"
        bad = [good.replace(str(self.root / "lib64/vulkan"), "/foreign/library"),
               good.replace(f"5143: initialize program: {self.backend}", extra + f"5143: initialize program: {self.backend}"),
               good.replace(f"search path={self.root}/lib64 (RPATH", "search path=/foreign/rpath (RPATH")]
        for value in bad:
            with self.subTest(trace=value), self.assertRaises(diag.Blocked):
                diag.observed_loader_paths(value, self.root, self.pid)

    def test_exact_owned_loader_failure_can_identify_preinitialization_backend_only(self):
        text = self.trace().split(f"5143: initialize program: {self.backend}", 1)[0]
        error = f"{self.backend}: error while loading shared libraries: libpulse.so.0: cannot open shared object file: No such file or directory\n"
        evidence = {}
        backend, _ = diag.observed_loader_paths(text + error, self.root, self.pid, 127, evidence)
        self.assertEqual(backend, self.backend)
        self.assertEqual(evidence["backendIdentityMode"], "owned-loader-failure")
        for value, code in ((text + error, 0), (text + error + error, 127),
                            (text + error + "5147: unrelated child\n", 127),
                            (text + error.replace(str(self.backend), "/foreign/backend"), 127)):
            with self.subTest(trace=value, code=code), self.assertRaises(diag.Blocked):
                diag.observed_loader_paths(value, self.root, self.pid, code)

    def test_retained_hosted_tail_pair_is_real_evidence_but_child_paths_are_insufficient(self):
        # Exact meaningful lines from run39935804154's retained tail; the complete
        # 56,618-byte trace was parsed originally but was not retained in old logs.
        sdk = "/usr/local/lib/android/sdk/emulator"
        excerpt = (
            f"      5143:\tinitialize program: {sdk}/qemu/linux-x86_64/qemu-system-x86_64-headless\n"
            f"      5143:\ttransferring control: {sdk}/qemu/linux-x86_64/qemu-system-x86_64-headless\n"
            f"      5147:\t search path={sdk}/lib64/vulkan:{sdk}/lib64/gles_swiftshader\t\t(LD_LIBRARY_PATH)\n"
            f"      5147:\tinitialize program: {sdk}/crashpad_handler\n"
            f"      5147:\ttransferring control: {sdk}/crashpad_handler\n")
        evidence = {}
        with self.assertRaisesRegex(diag.Blocked, "library search evidence was not observed"):
            diag.observed_loader_paths(excerpt.replace(sdk, str(self.root)), self.root, 5143, evidence=evidence)
        self.assertEqual(evidence["backend"], "qemu/linux-x86_64/qemu-system-x86_64-headless")
        self.assertEqual(evidence["backendPid"], 5143)

    def test_full_raw_trace_round_trips_beyond_old_tail_and_binds_owned_command(self):
        raw = b"BEGIN-important\n" + b"x" * 60000 + b"\nEND\x00\r\n"
        output, identity = io.StringIO(), {}
        with contextlib.redirect_stdout(output):
            result, code = diag.Controller().command("emulator-loader-probe", [sys.executable, "-c", "import sys; sys.stdout.buffer.write(" + repr(raw) + ")"],
                limit=diag.LOADER_TRACE_LIMIT, identity=identity, check=False)
        failure_metadata = {"exit": code, "outputBytes": len(result.encode()), "expectedBytes": len(raw),
            "sanitizerMarkerCounts": {name: result.count(name) for name in
                ("AddressSanitizer", "LeakSanitizer", "UndefinedBehaviorSanitizer", "SUMMARY:")}}
        self.assertTrue(code == 0 and result.encode() == raw, json.dumps(failure_metadata, sort_keys=True))
        lines = output.getvalue().splitlines()
        meta = json.loads(next(line.split(" ", 1)[1] for line in lines if line.startswith("RF_LOADER_TRACE_RAW_META ")))
        chunks = []
        for index, line in enumerate((line for line in lines if line.startswith("RF_LOADER_TRACE_RAW_CHUNK ")), 1):
            _, position, chunk = line.split(" ", 2)
            self.assertEqual(position, f"{index}/{meta['chunks']}")
            chunks.append(chunk)
        self.assertEqual(len(chunks), meta["chunks"])
        self.assertEqual(base64.b64decode("".join(chunks), validate=True), raw)
        self.assertTrue(meta["complete"]); self.assertTrue(identity["complete"])
        self.assertEqual(meta["sha256"], identity["outputSha256"])
        self.assertEqual(meta["bytes"], identity["outputBytes"])
        self.assertIsInstance(identity["pid"], int)
        self.assertIn("| BEGIN-important", output.getvalue())

    def test_partial_timed_out_probe_cannot_claim_complete_trace(self):
        output, identity = io.StringIO(), {}
        with contextlib.redirect_stdout(output), self.assertRaises(diag.Deadline):
            diag.Controller().command("emulator-loader-probe", [sys.executable, "-c",
                "import time; print('partial trace', flush=True); time.sleep(30)"],
                timeout=.3, limit=diag.LOADER_TRACE_LIMIT, identity=identity)
        meta = json.loads(next(line.split(" ", 1)[1] for line in output.getvalue().splitlines()
                               if line.startswith("RF_LOADER_TRACE_RAW_META ")))
        self.assertFalse(meta["complete"]); self.assertFalse(identity["complete"])
        self.assertNotIn("exit", identity)
        self.assertEqual(meta["sha256"], identity["outputSha256"])
        with self.assertRaises(diag.Failure):
            diag.emit_raw_loader_trace(b"x" * (diag.LOADER_TRACE_LIMIT + 1), True)

    def test_overflowing_probe_keeps_only_marked_incomplete_bounded_evidence(self):
        output, identity = io.StringIO(), {}
        with contextlib.redirect_stdout(output), self.assertRaisesRegex(diag.Failure, "output limit"):
            diag.Controller().command("emulator-loader-probe", [sys.executable, "-c",
                "import sys,time; print('prefix',flush=True); time.sleep(.1); sys.stdout.write('x'*10000)"],
                timeout=3, limit=128, identity=identity)
        lines = output.getvalue().splitlines()
        meta = json.loads(next(line.split(" ", 1)[1] for line in lines if line.startswith("RF_LOADER_TRACE_RAW_META ")))
        chunks = [line.split(" ", 2)[2] for line in lines if line.startswith("RF_LOADER_TRACE_RAW_CHUNK ")]
        raw = base64.b64decode("".join(chunks), validate=True)
        self.assertLessEqual(len(raw), 128)
        self.assertFalse(meta["complete"]); self.assertFalse(identity["complete"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), identity["outputSha256"])
        self.assertEqual(meta["sha256"], identity["outputSha256"])

    @unittest.skipUnless(shutil.which("cc") and Path("/usr/bin/ldd").is_file(), "Real compiler/loader unavailable; RPATH fixture NOT_RUN")
    def test_real_rpath_runpath_precedence_is_preserved_not_flattened(self):
        native, overlay = self.root / "lib64/native", self.root / "lib64/overlay"
        native.mkdir(); overlay.mkdir()
        soname = "lib_rf_path_precedence.so.1"
        for directory, value in ((native, 7), (overlay, 9)):
            source = directory / "fixture.c"; source.write_text(f"int fixture(void) {{ return {value}; }}\n")
            subprocess.run(["cc", "-shared", "-fPIC", "-Wl,-soname," + soname, str(source), "-o", str(directory / soname)],
                check=True, capture_output=True, timeout=30, env=native_compile_env())
        main = self.root / "main.c"
        main.write_text('#include <stdio.h>\nextern int fixture(void); int main(void) { printf("VALUE=%d\\n", fixture()); return 0; }\n')
        for tag, expected in (("--disable-new-dtags", 7), ("--enable-new-dtags", 9)):
            subprocess.run(["cc", str(main), "-L" + str(native), "-l:" + soname,
                "-Wl," + tag + ",-rpath,$ORIGIN/../../lib64/native", "-o", str(self.backend)],
                check=True, capture_output=True, timeout=30, env=native_compile_env())
            env = {**os.environ, "LD_DEBUG": "libs", "LD_LIBRARY_PATH": str(overlay)}
            identity = {}
            with contextlib.redirect_stdout(io.StringIO()):
                text, code = diag.Controller().command("real-path-fixture", [self.backend], env=env, identity=identity)
            self.assertEqual(code, 0); self.assertIn(f"VALUE={expected}\n", text)
            _, dirs = diag.observed_loader_paths(text, self.root, identity["pid"])
            self.assertEqual(dirs, [overlay])
            trace = subprocess.check_output(["/usr/bin/ldd", self.backend], env={**os.environ, "LD_LIBRARY_PATH": str(overlay)}, text=True, timeout=10)
            resolved = {}; self.assertEqual(diag.loader_missing(trace, resolved), set())
            self.assertEqual(Path(resolved[soname]).resolve(), (native if expected == 7 else overlay) / soname)


class HostLibraryPreparation(unittest.TestCase):
    """Mock external tools, not controller trust/selection/extraction decisions.

    This exercises orchestration, not Noble APT authentication or Android runtime.
    A separate compiled ELF fixture exercises the actual local dynamic loader.
    """
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sdk = self.root / "sdk/emulator"
        (self.sdk / "qemu/linux-x86_64").mkdir(parents=True)
        (self.sdk / "lib64").mkdir()
        self.header = bytearray(20)
        self.header[:6] = b"\x7fELF\x02\x01"
        self.header[16:18], self.header[18:20] = (3).to_bytes(2, "little"), (62).to_bytes(2, "little")
        for name in ("emulator", "qemu/linux-x86_64/qemu-system-x86_64-headless"):
            (self.sdk / name).write_bytes(self.header)
        self.now, self.calls, self.problem, self.preinstalled = 0, [], None, False
        self.ctl = diag.Controller(env={"PATH": "/usr/bin"}, clock=lambda: self.now)
        self.ctl.temp = self.root / "owned"; self.ctl.temp.mkdir()
        self.ctl.emulator_path = str(self.sdk / "emulator")
        self.key, self.status = self.root / "key", self.root / "status"
        self.key.write_bytes(b"existing public key fixture")
        self.status.write_bytes(b"host package status fixture")
        self.data = b"authenticated package fixture"
        self.real_digest, self.real_config = diag.sha256, diag.private_apt_config
        self.stack = contextlib.ExitStack(); self.addCleanup(self.stack.close)
        self.stack.enter_context(mock.patch.object(diag, "require_executable", side_effect=str))
        self.stack.enter_context(mock.patch.object(diag, "sha256", side_effect=self.digest))
        self.stack.enter_context(mock.patch.object(diag, "private_apt_config", side_effect=lambda root, key, status:
            self.real_config(root, self.key, self.status)))
        self.stack.enter_context(mock.patch.object(self.ctl, "command", side_effect=self.command))

    def digest(self, path, check=None):
        path = Path(path)
        path = {Path("/usr/share/keyrings/ubuntu-archive-keyring.gpg"): self.key,
                Path("/var/lib/dpkg/status"): self.status}.get(path, path)
        if str(path).startswith("/usr/bin/"):
            if check: check()
            return hashlib.sha256(b"fixture tool").hexdigest()
        return self.real_digest(path, check)

    def command(self, label, args, **kwargs):
        self.calls.append((label, list(map(str, args)), copy.deepcopy(kwargs)))
        apt = self.ctl.temp / "private-apt"
        if label == "emulator-loader-probe":
            kwargs["identity"].update(pid=5143, complete=True, exit=0 if self.preinstalled else 127)
            if self.problem == "parser-no-backend":
                return "version output without attributable backend\n", 0
            launcher = f"5143: initialize program: {self.sdk}/emulator\n5143: transferring control: {self.sdk}/emulator\n"
            backend = f"{self.sdk}/qemu/linux-x86_64/qemu-system-x86_64-headless"
            search_kind = f"RPATH from file {backend}" if self.problem == "rpath-only" else "LD_LIBRARY_PATH"
            search = f"5143: search path={self.sdk}/lib64\t({search_kind})\n"
            end = (f"5143: initialize program: {backend}\n5143: transferring control: {backend}\n" if self.preinstalled
                   else f"{backend}: error while loading shared libraries: libpulse.so.0: cannot open shared object file: No such file or directory\n")
            return launcher + search + end, 0 if self.preinstalled else 127
        if label == "emulator-elf-closure":
            selected = self.ctl.temp / "emulator-library-overlay/libpulse.so.0"
            libc = "/lib/libc.so.6"
            if self.problem == "conflicting-provider" and str(args[-1]).endswith("headless"):
                libc = "/different/libc.so.6"
            if self.problem == "changed-provider" and selected.exists():
                libc = "/different/libc.so.6"
            output = f"linux-vdso.so.1 (0x01)\nlibc.so.6 => {libc} (0x02)\n/lib64/ld-linux-x86-64.so.2 (0x03)\n"
            if self.problem == "unknown-initial":
                output += "libunreviewed.so.1 => not found\n"
            elif not self.preinstalled:
                if selected.exists():
                    provider = "/foreign/libpulse.so.0" if self.problem == "wrong-selected-provider" else str(selected)
                    output += f"libpulse.so.0 => {provider} (0x04)\n"
                    if self.problem == "partial-closure":
                        output += "libunavailable.so.1 => not found\n"
                else:
                    output += "libpulse.so.0 => not found\n"
            if self.problem == "late-deadline": self.now = 301
            return output, 0
        if label == "private-apt-signed-metadata":
            for suite in ("noble", "noble-updates", "noble-security"):
                (apt / f"lists/ubuntu_{suite}_InRelease").write_bytes(b"signed fixture " + suite.encode())
            return ("W: incomplete metadata" if self.problem == "metadata-warning" else "complete fixture metadata"), 0
        if label == "ubuntu-release-signature":
            return ("[GNUPG:] VALIDSIG " + "A" * 40 + " 0 0 0\n" if self.problem != "missing-signature" else "no valid signature"), 0
        if label == "private-apt-plan":
            return "Inst libpulse0 (1:16.1-1 Ubuntu:24.04/noble [amd64])\n", 0
        if label == "private-apt-package-metadata":
            return ("Package: libpulse0\nVersion: 1:16.1-1\nArchitecture: amd64\n"
                    f"Size: {len(self.data)}\nSHA256: {hashlib.sha256(self.data).hexdigest()}\n"
                    "Filename: pool/main/p/pulseaudio/libpulse0_16.1-1_amd64.deb\n"), 0
        if label == "private-apt-download-only":
            (apt / "downloads/libpulse0.deb").write_bytes(b"tampered" if self.problem == "archive-hash" else self.data)
            return "download fixture", 0
        if label == "private-deb-identity":
            arch = "arm64" if self.problem == "control-identity" else "amd64"
            return f"Package: libpulse0\nVersion: 1:16.1-1\nArchitecture: {arch}\n", 0
        if label == "private-deb-data-only":
            entries = [("usr/lib/x86_64-linux-gnu/libpulse.so.0", "file", bytes(self.header))]
            if self.problem == "duplicate-provider":
                entries.append(("usr/lib/x86_64-linux-gnu/other/libpulse.so.0", "file", bytes(self.header)))
            return PrivateHostLibraries().archive(entries), 0
        if label == "private-library-soname":
            soname = "libc.so.6" if self.problem == "core-library" else "libpulse.so.0"
            return f" 0x000000000000000e (SONAME) Library soname: [{soname}]\n", 0
        self.fail("Unexpected fixture command: " + label)

    def test_preinstalled_headless_closure_never_initializes_or_downloads_apt(self):
        self.preinstalled = True
        self.ctl.prepare_host_libraries()
        self.assertEqual(self.ctl.receipt["hostLibraries"]["status"], "PREINSTALLED")
        self.assertFalse((self.ctl.temp / "private-apt").exists())
        self.assertEqual(self.ctl.emulator_library_env, {})
        self.assertEqual({call[0] for call in self.calls}, {"emulator-loader-probe", "emulator-elf-closure"})
        self.assertEqual(self.calls[0][1][1:], ["-no-window", "-gpu", "software", "-verbose", "-version"])

    def test_rpath_only_preinstalled_closure_omits_ld_variable_and_preserves_native_tag(self):
        self.problem, self.preinstalled = "rpath-only", True
        self.ctl.prepare_host_libraries()
        details = self.ctl.receipt["hostLibraries"]
        self.assertEqual(details["status"], "PREINSTALLED")
        self.assertEqual(details["observedLdLibraryPath"], [])
        self.assertEqual(details["elfSearchRecords"], [{"kind": "RPATH",
            "origin": "qemu/linux-x86_64/qemu-system-x86_64-headless", "directories": ["lib64"]}])
        for label, _, kwargs in self.calls:
            if label == "emulator-elf-closure":
                self.assertNotIn("LD_LIBRARY_PATH", kwargs["env"])
        self.assertEqual(self.ctl.emulator_library_env, {})
        self.assertFalse((self.ctl.temp / "private-apt").exists())

    def test_rpath_only_repair_adds_only_overlay_without_empty_or_cwd_component(self):
        self.problem = "rpath-only"
        self.ctl.prepare_host_libraries()
        details = self.ctl.receipt["hostLibraries"]
        self.assertEqual(details["status"], "RESOLVED_PRIVATE")
        self.assertEqual(details["observedLdLibraryPath"], [])
        self.assertEqual(details["elfSearchRecords"][0]["kind"], "RPATH")
        self.assertEqual(details["elfSearchRecords"][0]["directories"], ["lib64"])
        overlay = str(self.ctl.temp / "emulator-library-overlay")
        closure_envs = [kwargs["env"] for label, _, kwargs in self.calls if label == "emulator-elf-closure"]
        self.assertTrue(any("LD_LIBRARY_PATH" not in env for env in closure_envs))
        self.assertTrue(any(env.get("LD_LIBRARY_PATH") == overlay for env in closure_envs))
        for env in closure_envs:
            if "LD_LIBRARY_PATH" in env:
                self.assertEqual(env["LD_LIBRARY_PATH"].split(os.pathsep), [overlay])
                self.assertNotIn(str(self.sdk / "lib64"), env["LD_LIBRARY_PATH"])
        self.assertEqual(self.ctl.emulator_library_env, {"LD_LIBRARY_PATH": overlay})
        self.assertNotIn("LD_LIBRARY_PATH", self.ctl.env)

    def test_parser_failure_retains_probe_exit_bytes_hash_and_owned_pid_before_build(self):
        self.problem = "parser-no-backend"
        with self.assertRaises(diag.Blocked): self.ctl.prepare_host_libraries()
        details = self.ctl.receipt["hostLibraries"]
        raw = b"version output without attributable backend\n"
        self.assertEqual(details["status"], "PROBING")
        self.assertEqual(details["launcherProbeExit"], 0)
        self.assertEqual(details["launcherProbeBytes"], len(raw))
        self.assertEqual(details["launcherProbeSha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(details["launcherProbe"]["pid"], 5143)
        self.assertEqual([call[0] for call in self.calls], ["emulator-loader-probe"])

    def test_signed_exact_package_is_extracted_to_missing_only_overlay_and_receipt(self):
        self.ctl.prepare_host_libraries()
        details = self.ctl.receipt["hostLibraries"]
        self.assertEqual(details["status"], "RESOLVED_PRIVATE")
        self.assertEqual(details["initialMissing"], ["libpulse.so.0"])
        self.assertEqual(len(details["signedReleases"]), 3)
        self.assertEqual(details["packages"][0]["sha256"], hashlib.sha256(self.data).hexdigest())
        self.assertEqual(set(details["selectedLibraries"]), {"libpulse.so.0"})
        overlay = self.ctl.temp / "emulator-library-overlay"
        self.assertEqual(list(overlay.iterdir()), [overlay / "libpulse.so.0"])
        self.assertEqual(self.ctl.emulator_library_env, {"LD_LIBRARY_PATH": str(overlay)})
        self.assertNotIn("LD_LIBRARY_PATH", self.ctl.env)
        self.assertNotIn("APT_CONFIG", self.ctl.env)
        self.assertEqual(sum(label == "private-apt-download-only" for label, _, _ in self.calls), 1)
        for label, args, kwargs in self.calls:
            self.assertLessEqual(kwargs["timeout"], 120)
            if label.startswith("private-apt") or label == "ubuntu-release-signature":
                self.assertEqual(kwargs["env"]["APT_CONFIG"], str(self.ctl.temp / "private-apt/apt.conf"))
                self.assertNotIn("LD_LIBRARY_PATH", kwargs["env"])
            if "install" in args:
                self.assertIn("--simulate", args)
            self.assertNotIn("--install", args)

    def test_unrecognized_or_conflicting_initial_closure_blocks_before_apt(self):
        for problem in ("unknown-initial", "conflicting-provider"):
            with self.subTest(problem=problem):
                self.problem = problem
                with self.assertRaises(diag.Blocked): self.ctl.prepare_host_libraries()
                self.assertFalse((self.ctl.temp / "private-apt").exists())
                shutil.rmtree(self.ctl.temp / "emulator-library-overlay")

    def test_authentication_hash_identity_and_provider_failures_cannot_enable_overlay(self):
        for problem in ("metadata-warning", "missing-signature", "archive-hash", "control-identity",
                        "core-library", "duplicate-provider", "partial-closure", "changed-provider", "wrong-selected-provider"):
            with self.subTest(problem=problem):
                self.problem = problem
                with self.assertRaises(diag.Blocked): self.ctl.prepare_host_libraries()
                self.assertEqual(self.ctl.emulator_library_env, {})
                self.assertEqual(self.ctl.receipt["hostLibraries"]["status"], "CHECKING")
                shutil.rmtree(self.ctl.temp); self.ctl.temp.mkdir()

    def test_phase_timeout_after_last_external_command_cannot_report_preinstalled(self):
        self.preinstalled, self.problem = True, "late-deadline"
        with self.assertRaises(diag.Deadline): self.ctl.prepare_host_libraries()
        self.assertEqual(self.ctl.receipt["hostLibraries"]["status"], "CHECKING")

    def test_loader_overrides_are_rejected_without_running_tools(self):
        for key in ("LD_PRELOAD", "LD_AUDIT", "LD_LIBRARY_PATH", "LD_DEBUG_OUTPUT", "LD_ORIGIN_PATH",
                    "GLIBC_TUNABLES", "ANDROID_EMULATOR_USE_SYSTEM_LIBS"):
            with self.subTest(key=key):
                self.ctl.env = {key: "inherited"}
                with self.assertRaises(diag.Blocked): self.ctl.prepare_host_libraries()
                self.assertEqual(self.calls, [])

    def test_setup_failure_ends_run_before_build_adb_boot_or_junit(self):
        self.problem = "archive-hash"
        output = io.StringIO()
        with mock.patch.object(self.ctl, "prerequisites", side_effect=self.ctl.prepare_host_libraries), \
                mock.patch.object(self.ctl, "build") as build, mock.patch.object(self.ctl, "boot") as boot, \
                mock.patch.object(self.ctl, "tests") as instrument, contextlib.redirect_stdout(output):
            self.assertEqual(self.ctl.run(), 1)
        build.assert_not_called(); boot.assert_not_called(); instrument.assert_not_called()
        self.assertEqual(self.ctl.receipt["status"], "BLOCKED")
        self.assertNotIn("RF_DIAGNOSTIC_JUNIT", output.getvalue())


class LicensesAndMetadata(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.sdk = Path(self.temporary.name)

    def license(self):
        directory = self.sdk / "licenses"
        directory.mkdir(exist_ok=True)
        path = directory / "android-sdk-license"
        path.write_text("a" * 40 + "\n")
        return path

    def test_missing_existing_license_is_blocked_not_created(self):
        with self.assertRaises(diag.Blocked):
            diag.license_snapshot(self.sdk)
        self.assertFalse((self.sdk / "licenses").exists())

    def test_preexisting_licenses_cannot_change(self):
        path = self.license()
        before = diag.license_snapshot(self.sdk)
        diag.verify_license_snapshot(self.sdk, before)
        path.write_text("b" * 40 + "\n")
        with self.assertRaises(diag.Blocked):
            diag.verify_license_snapshot(self.sdk, before)

    def test_added_or_symlink_license_is_blocked(self):
        self.license()
        before = diag.license_snapshot(self.sdk)
        extra = self.sdk / "licenses/new-license"
        extra.write_text("b" * 40 + "\n")
        with self.assertRaises(diag.Blocked):
            diag.verify_license_snapshot(self.sdk, before)
        extra.unlink()
        extra.symlink_to(self.sdk / "licenses/android-sdk-license")
        with self.assertRaises(diag.Blocked):
            diag.license_snapshot(self.sdk)

    def test_package_manifest_identity_must_match_requested_package(self):
        directory = self.sdk / "emulator"
        directory.mkdir()
        (directory / "source.properties").write_text("Pkg.Revision=36.6.4\n")
        manifest = directory / "package.xml"
        manifest.write_text('<repository><localPackage path="emulator"><revision><major>36</major><minor>6</minor><micro>4</micro></revision></localPackage></repository>')
        self.assertEqual(diag.package_metadata(self.sdk, "emulator")["revision"], "36.6.4")
        manifest.write_text(manifest.read_text().replace('path="emulator"', 'path="other"'))
        with self.assertRaises(diag.Blocked):
            diag.package_metadata(self.sdk, "emulator")

    def test_apk_manifest_target_and_debug_identity_are_required(self):
        ns = 'xmlns:android="http://schemas.android.com/apk/res/android"'
        app = f'<manifest {ns} package="{diag.APP}"><application android:debuggable="true"/></manifest>'
        test = f'<manifest {ns} package="{diag.APP}.test"><instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="{diag.APP}"/></manifest>'
        diag.validate_apk_manifests(app, test)
        notice = "Picked up JAVA_TOOL_OPTIONS: -Duser.home=/task/home"
        diag.validate_apk_manifests(notice + "\n" + app, notice + "\n" + test, java_notice=notice)
        with self.assertRaises(ET.ParseError):
            diag.validate_apk_manifests("unrecognized-preamble\n" + app, test, java_notice=notice)
        for invalid_app, invalid_test in ((app.replace('debuggable="true"', 'debuggable="false"'), test),
                                         (app, test.replace('targetPackage="' + diag.APP, 'targetPackage="wrong'))):
            with self.assertRaises(diag.Failure):
                diag.validate_apk_manifests(invalid_app, invalid_test)


class SubprocessAndReceipts(unittest.TestCase):
    def test_command_stdin_is_closed(self):
        ctl = diag.Controller()
        with contextlib.redirect_stdout(io.StringIO()):
            value, code = ctl.command("closed-stdin", [sys.executable, "-c", "import sys; print(repr(sys.stdin.read()))"], check=False)
        # Failure metadata only: preserve gate failure without leaking streams, paths or env.
        metadata = {"exit": code, "bytes": len(value.encode()),
            "asanMarkers": value.count("AddressSanitizer"), "lsanMarkers": value.count("LeakSanitizer"),
            "ubsanMarkers": value.count("UndefinedBehaviorSanitizer"), "summaries": value.count("SUMMARY:")}
        self.assertTrue(code == 0 and value.strip() == "''", json.dumps(metadata, sort_keys=True))

    def test_command_deadline_preserves_diagnostics_and_fails(self):
        ctl = diag.Controller()
        output = io.StringIO()
        start = time.monotonic()
        with contextlib.redirect_stdout(output), self.assertRaises(diag.Deadline):
            ctl.command("timeout-test", [sys.executable, "-c", "import time; print('partial', flush=True); time.sleep(60)"], timeout=.15)
        self.assertLess(time.monotonic() - start, 4)
        self.assertIn("partial", output.getvalue())

    def test_command_overflow_fails_not_truncates_evidence_to_pass(self):
        ctl = diag.Controller()
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(diag.Failure):
            ctl.command("overflow", [sys.executable, "-c", "print('x'*10000)"], limit=100)

    def test_command_failure_is_not_adb_success(self):
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(diag.Failure):
            diag.Controller().command("exit", [sys.executable, "-c", "raise SystemExit(9)"])

    def test_outer_deadline_clips_every_stage_budget(self):
        ctl = diag.Controller(clock=lambda: 100)
        ctl.ends = 104
        self.assertEqual(ctl.remaining(900), 4)
        ctl.ends = 99
        with self.assertRaises(diag.Deadline):
            ctl.remaining(900)

    def test_signal_flags_are_observed_without_throwing_inside_process_setup(self):
        ctl = diag.Controller()
        ctl.interrupted_signal = 15
        with self.assertRaises(diag.Cancelled):
            ctl.remaining(60)
        ctl.interrupted_signal = 0
        ctl.watchdog_expired = True
        with self.assertRaises(diag.Deadline):
            ctl.remaining(60)

    def test_file_hash_checks_cancellation_between_bounded_chunks(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "image"
            path.write_bytes(b"x" * (2 * 1024 * 1024))
            check = mock.Mock(side_effect=diag.Cancelled("cancelled"))
            with self.assertRaises(diag.Cancelled):
                diag.sha256(path, check)
            check.assert_called_once()

    def test_output_cannot_execute_workflow_commands(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            diag.emit("fixture", "::error::fake\n\x1b[31mhello\n")
        self.assertTrue(all(line.startswith("RF_DIAGNOSTIC_LOG ") for line in output.getvalue().splitlines()))
        self.assertNotIn("\x1b", output.getvalue())

    def test_missing_tools_emit_blocked_receipt_and_no_junit_success(self):
        ctl = diag.Controller()
        output = io.StringIO()
        with mock.patch.object(ctl, "prerequisites", side_effect=diag.Blocked("existing tool absent")), contextlib.redirect_stdout(output):
            self.assertEqual(ctl.run(), 1)
        row = next(line for line in output.getvalue().splitlines() if line.startswith("RF_DIAGNOSTIC_RECEIPT "))
        value = json.loads(row.split(" ", 1)[1])
        self.assertEqual(value["status"], "BLOCKED")
        self.assertEqual(set(value["qualification"].values()), {"NOT_RUN"})
        self.assertNotIn("RF_DIAGNOSTIC_JUNIT", output.getvalue())

    def test_partial_proof_cannot_emit_success_junit(self):
        for failure in (diag.Failure("partial events"), diag.Deadline("time expired")):
            ctl = diag.Controller()
            output = io.StringIO()
            with mock.patch.object(ctl, "prerequisites", side_effect=failure), contextlib.redirect_stdout(output):
                self.assertEqual(ctl.run(), 1)
            self.assertNotIn("RF_DIAGNOSTIC_JUNIT", output.getvalue())

    def successful_fake(self):
        ctl = diag.Controller()
        ctl.sdk, ctl.licenses = Path("/unused-sdk"), {}
        ctl.receipt.update(receipt())
        def tests():
            proof = diag.parse_instrumentation(transcript())
            ctl.receipt["testProof"] = proof
            ctl.receipt["junitXml"] = diag.junit_xml(proof, ctl.receipt)
        ctl.prerequisites = lambda: None
        ctl.build = lambda: None
        ctl.boot = lambda: None
        ctl.tests = tests
        return ctl

    def test_success_receipt_and_junit_emit_only_after_all_checks_and_cleanup(self):
        ctl, output = self.successful_fake(), io.StringIO()
        with mock.patch.object(diag, "verify_license_snapshot"), contextlib.redirect_stdout(output):
            self.assertEqual(ctl.run(), 0)
        lines = output.getvalue().splitlines()
        xml = next(line for line in lines if line.startswith("RF_DIAGNOSTIC_JUNIT "))
        self.assertEqual(len(ET.fromstring(xml.split(" ", 1)[1]).findall("testcase")), 4)
        final = json.loads(next(line for line in lines if line.startswith("RF_DIAGNOSTIC_RECEIPT ")).split(" ", 1)[1])
        self.assertEqual(final["status"], "PASS")
        self.assertEqual(set(final["qualification"].values()), {"NOT_RUN"})

    def test_cleanup_failure_invalidates_previously_successful_proof(self):
        ctl, output = self.successful_fake(), io.StringIO()
        with mock.patch.object(diag, "verify_license_snapshot"), mock.patch.object(diag, "stop_owned", side_effect=OSError("cleanup failed")), contextlib.redirect_stdout(output):
            self.assertEqual(ctl.run(), 1)
        self.assertNotIn("RF_DIAGNOSTIC_JUNIT", output.getvalue())
        final = json.loads(next(line for line in output.getvalue().splitlines() if line.startswith("RF_DIAGNOSTIC_RECEIPT ")).split(" ", 1)[1])
        self.assertEqual(final["status"], "FAIL")
        self.assertIn("cleanupError", final)

    def test_private_adb_cleanup_is_attempted_even_if_emulator_cleanup_fails(self):
        ctl, output = self.successful_fake(), io.StringIO()
        ctl.emulator, ctl.adb_server = mock.Mock(), mock.Mock()
        def stop(proc):
            if proc is ctl.emulator:
                raise OSError("emulator cleanup failed")
        with mock.patch.object(diag, "verify_license_snapshot"), \
                mock.patch.object(diag, "stop_owned", side_effect=stop) as stopped, \
                mock.patch.object(ctl, "drain_background"), contextlib.redirect_stdout(output):
            self.assertEqual(ctl.run(), 1)
        self.assertEqual(stopped.call_args_list, [mock.call(ctl.emulator), mock.call(ctl.adb_server)])
        ctl.adb_server.stdout.close.assert_called_once()
        self.assertNotIn("RF_DIAGNOSTIC_JUNIT", output.getvalue())

    def test_late_real_sigterm_during_cleanup_cannot_emit_pass_or_junit(self):
        ctl, output = self.successful_fake(), io.StringIO()
        def cancel_during_cleanup(_proc):
            os.kill(os.getpid(), signal.SIGTERM)
        with mock.patch.object(diag, "verify_license_snapshot"), mock.patch.object(diag, "stop_owned", side_effect=cancel_during_cleanup), contextlib.redirect_stdout(output):
            self.assertEqual(ctl.run(), 1)
        self.assertEqual(ctl.receipt["status"], "CANCELLED")
        self.assertNotIn("RF_DIAGNOSTIC_JUNIT", output.getvalue())

    def test_late_watchdog_during_cleanup_cannot_emit_pass_or_junit(self):
        ctl, output = self.successful_fake(), io.StringIO()
        def expire_during_cleanup(_proc):
            os.kill(os.getpid(), signal.SIGALRM)
        with mock.patch.object(diag, "verify_license_snapshot"), mock.patch.object(diag, "stop_owned", side_effect=expire_during_cleanup), contextlib.redirect_stdout(output):
            self.assertEqual(ctl.run(), 1)
        self.assertEqual(ctl.receipt["status"], "TIMEOUT")
        self.assertNotIn("RF_DIAGNOSTIC_JUNIT", output.getvalue())

    def test_installed_hash_rejects_splits_malformed_path_and_digest(self):
        ctl = diag.Controller()
        for listing in ("", "package:/data/app/x/base.apk\npackage:/data/app/x/split.apk", "package:/tmp/foreign.apk", "package:/data/app/../outside/base.apk"):
            with self.subTest(listing=listing), mock.patch.object(ctl, "shell", return_value=listing), self.assertRaises(diag.Failure):
                ctl.installed_hash(diag.APP)
        with mock.patch.object(ctl, "shell", side_effect=["package:/data/app/x/base.apk", "invalid"]), self.assertRaises(diag.Failure):
            ctl.installed_hash(diag.APP)
        with mock.patch.object(ctl, "shell", side_effect=["package:/data/app/x/base.apk", "a" * 64 + "  /data/app/x/base.apk"]):
            self.assertEqual(ctl.installed_hash(diag.APP), "a" * 64)


class CandidateInstallation(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.now, self.calls, self.problem = 0, [], None
        self.ctl = diag.Controller(env={}, clock=lambda: self.now)
        self.ctl.app, self.ctl.test = self.root / "app.apk", self.root / "test.apk"
        self.ctl.app.write_bytes(b"synthetic app bytes")
        self.ctl.test.write_bytes(b"synthetic test bytes")
        self.ctl.receipt.update(appSha256=diag.sha256(self.ctl.app), testSha256=diag.sha256(self.ctl.test))
        self.stack = contextlib.ExitStack(); self.addCleanup(self.stack.close)
        self.stack.enter_context(mock.patch.object(self.ctl, "adb_args", side_effect=lambda *args: ["/owned/adb", *args]))
        self.stack.enter_context(mock.patch.object(self.ctl, "command", side_effect=self.command))
        self.stack.enter_context(contextlib.redirect_stdout(io.StringIO()))

    def command(self, label, args, **kwargs):
        self.calls.append((label, args, kwargs))
        identity = kwargs.get("identity")
        if identity is not None:
            identity.update(pid=123, complete=False, outputBytes=0, outputSha256=hashlib.sha256(b"").hexdigest())
        if self.problem == label:
            self.now += kwargs["timeout"]
            raise diag.Deadline(label + " timed out")
        if self.problem == "cancel-install" and label == "install-app":
            raise diag.Cancelled("Signal 15")
        output = "package:/system/framework/framework-res.apk\n" if label == "package-manager-response" else "Success\n"
        if self.problem == "malformed-probe" and label == "package-manager-response":
            output = "package:/foreign/framework-res.apk\n"
        if identity is not None:
            identity.update(complete=True, exit=0, outputBytes=len(output.encode()), outputSha256=hashlib.sha256(output.encode()).hexdigest())
        return output, 0

    def test_readonly_response_then_exact_unchanged_two_installs_have_per_apk_bound_receipts(self):
        self.ctl.install_candidates()
        self.assertEqual([call[0] for call in self.calls], ["package-manager-response", "install-app", "install-test"])
        self.assertEqual(self.calls[0][1], ["/owned/adb", "-s", diag.SERIAL, "shell", "cmd", "package", "path", "android"])
        self.assertEqual(self.calls[0][2]["timeout"], 60)
        for index, apk in enumerate((self.ctl.app, self.ctl.test), 1):
            label, args, kwargs = self.calls[index]
            self.assertEqual(args, ["/owned/adb", "-s", diag.SERIAL, "install", "-t", apk])
            self.assertEqual(kwargs["timeout"], 600)
            record = self.ctl.receipt["candidateInstallations"][index - 1]
            self.assertEqual(record["apkBytes"], apk.stat().st_size)
            self.assertEqual(record["apkSha256"], diag.sha256(apk))
            self.assertEqual(record["process"]["pid"], 123)
            self.assertEqual(record["status"], "COMMAND_SUCCEEDED")
            self.assertNotIn("installedSha256", record)
        self.assertEqual(self.ctl.receipt["packageManagerProbe"]["status"], "RESPONSIVE")
        self.assertNotIn("testProof", self.ctl.receipt)

    def test_first_and_second_timeouts_never_retry_or_reach_hashes_or_instrumentation(self):
        for problem, count in (("install-app", 1), ("install-test", 2)):
            with self.subTest(problem=problem), mock.patch.object(self.ctl, "installed_hash") as installed:
                self.problem, self.calls, self.now = problem, [], 0
                with self.assertRaises(diag.Deadline): self.ctl.tests()
                installed.assert_not_called()
                self.assertEqual(len(self.ctl.receipt["candidateInstallations"]), count)
                record = self.ctl.receipt["candidateInstallations"][-1]
                self.assertEqual(record["status"], "TIMEOUT")
                self.assertFalse(record["process"]["complete"])
                self.assertEqual(record["process"]["outputBytes"], 0)
                self.assertEqual(record["elapsedSeconds"], 600)
                self.assertEqual(sum(label == problem for label, _, _ in self.calls), 1)
                self.assertNotIn("instrumentation", [label for label, _, _ in self.calls])
                self.assertNotIn("junitXml", self.ctl.receipt)

    def test_probe_timeout_or_malformed_response_prevents_every_install(self):
        for problem in ("package-manager-response", "malformed-probe"):
            with self.subTest(problem=problem):
                self.problem, self.calls, self.now = problem, [], 0
                with self.assertRaises(diag.Failure): self.ctl.install_candidates()
                self.assertEqual([call[0] for call in self.calls], ["package-manager-response"])
                self.assertEqual(self.ctl.receipt["candidateInstallations"], [])
                self.assertNotEqual(self.ctl.receipt["packageManagerProbe"]["status"], "RESPONSIVE")

    def test_effective_install_deadline_is_clipped_to_original_controller_limit(self):
        self.ctl.ends = 75
        def timed(label, args, **kwargs):
            result = self.command(label, args, **kwargs)
            self.now += 5 if label == "package-manager-response" else 30
            return result
        with mock.patch.object(self.ctl, "command", side_effect=timed):
            self.ctl.install_candidates()
        self.assertEqual([call[2]["timeout"] for call in self.calls], [60, 70, 40])
        self.assertEqual([r["effectiveTimeoutSeconds"] for r in self.ctl.receipt["candidateInstallations"]], [70, 40])
        self.assertEqual(diag.BUDGETS["controller"], 4320)
        self.assertEqual(diag.BUDGETS["workflow"], 4500)
        self.assertEqual(diag.BUDGETS["instrumentation"], 900)

    def test_cancelled_install_preserves_partial_identity_and_stops(self):
        self.problem = "cancel-install"
        with self.assertRaises(diag.Cancelled): self.ctl.install_candidates()
        record = self.ctl.receipt["candidateInstallations"][0]
        self.assertEqual(record["status"], "CANCELLED")
        self.assertEqual(record["process"]["pid"], 123)
        self.assertFalse(record["process"]["complete"])
        self.assertEqual([call[0] for call in self.calls], ["package-manager-response", "install-app"])

    def test_changed_source_apk_is_rejected_before_install(self):
        self.ctl.app.write_bytes(b"mutated bytes")
        with self.assertRaises(diag.Failure): self.ctl.install_candidates()
        self.assertEqual([call[0] for call in self.calls], ["package-manager-response"])
        self.assertEqual(self.ctl.receipt["candidateInstallations"], [])

    def test_exit_zero_does_not_bypass_exact_installed_hashes(self):
        with mock.patch.object(self.ctl, "installed_hash", return_value="f" * 64) as installed:
            with self.assertRaisesRegex(diag.Failure, "Installed APK hashes differ"):
                self.ctl.tests()
        self.assertEqual(installed.call_count, 2)
        self.assertNotIn("instrumentation", [label for label, _, _ in self.calls])
        self.assertNotIn("installedSha256", self.ctl.receipt)
        self.assertNotIn("junitXml", self.ctl.receipt)

    def test_real_no_output_timeout_preserves_owned_command_metadata_and_failure(self):
        ctl, record = diag.Controller(), {"package": diag.APP}
        with self.assertRaises(diag.Deadline):
            ctl.candidate_setup_command("install-fixture", [sys.executable, "-c", "import time; time.sleep(30)"], .2, record)
        self.assertEqual(record["status"], "TIMEOUT")
        self.assertIsInstance(record["process"]["pid"], int)
        self.assertFalse(record["process"]["complete"])
        self.assertEqual(record["process"]["outputBytes"], 0)
        self.assertEqual(record["process"]["outputSha256"], hashlib.sha256(b"").hexdigest())

    def test_first_install_timeout_emits_terminal_timeout_receipt_never_junit(self):
        self.problem = "install-app"
        output = io.StringIO()
        with mock.patch.object(self.ctl, "prerequisites"), mock.patch.object(self.ctl, "build"), \
                mock.patch.object(self.ctl, "boot"), contextlib.redirect_stdout(output):
            self.assertEqual(self.ctl.run(), 1)
        self.assertNotIn("RF_DIAGNOSTIC_JUNIT", output.getvalue())
        final = json.loads(next(line.split(" ", 1)[1] for line in output.getvalue().splitlines()
                               if line.startswith("RF_DIAGNOSTIC_RECEIPT ")))
        self.assertEqual(final["status"], "TIMEOUT")
        self.assertEqual(len(final["candidateInstallations"]), 1)
        self.assertNotIn("testProof", final)


class DeviceCrashContext(unittest.TestCase):
    CRASH = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"

    def setUp(self):
        self.now, self.calls = 100.0, []
        self.ctl = diag.Controller(env={}, clock=lambda: self.now)
        self.ctl.receipt.update(receipt(), device={"serial": diag.SERIAL}, candidateInstallations=[])
        self.ctl.receipt["installedSha256"] = {key: self.ctl.receipt[key] for key in ("appSha256", "testSha256")}
        self.ctl.emulator = mock.Mock(pid=123)
        self.ctl.adb_server, self.ctl.adb_port, self.ctl.adb = mock.Mock(pid=234), 34567, "/owned/adb"
        self.stack = contextlib.ExitStack(); self.addCleanup(self.stack.close)
        self.stack.enter_context(mock.patch.object(diag.os, "waitid", return_value=None))
        self.listening = self.stack.enter_context(mock.patch.object(diag, "process_listens_on_loopback", return_value=True))
        self.stack.enter_context(mock.patch.object(self.ctl, "drain_background"))
        self.stack.enter_context(mock.patch.object(self.ctl, "install_candidates"))
        self.stack.enter_context(mock.patch.object(self.ctl, "device_clock_sample", return_value={"status": "UNAVAILABLE"}))
        self.stack.enter_context(mock.patch.object(self.ctl, "source_identity"))
        self.stack.enter_context(mock.patch.object(self.ctl, "installed_hash", side_effect=lambda package:
            self.ctl.receipt["appSha256" if package == diag.APP else "testSha256"]))
        self.output = io.StringIO()
        self.stack.enter_context(contextlib.redirect_stdout(self.output))

    def result(self, kwargs, raw, code=0, complete=True):
        kwargs["identity"].update(pid=345, complete=complete, outputBytes=len(raw),
                                  outputSha256=hashlib.sha256(raw).hexdigest())
        if complete: kwargs["identity"]["exit"] = code
        if "raw_capture" in kwargs: kwargs["raw_capture"][:] = raw
        return (raw if kwargs.get("binary") else raw.decode()), code

    def command(self, label, args, **kwargs):
        self.calls.append((label, args, kwargs))
        if label == "instrumentation": return self.result(kwargs, self.CRASH.encode())
        kwargs["guard"]()
        return self.result(kwargs, b"10-23 11:50:12.000  876  876 E AndroidRuntime: synthetic crash stack\n")

    def test_parser_failure_retains_exact_command_identity_before_one_owned_readonly_capture(self):
        with mock.patch.object(self.ctl, "command", side_effect=self.command):
            with self.assertRaisesRegex(diag.Failure, "Instrumentation crash/failure marker"):
                self.ctl.tests()
        self.assertEqual([call[0] for call in self.calls], ["instrumentation", "device-crash-context"])
        label, args, kwargs = self.calls[-1]
        self.assertEqual(args, ["/owned/adb", "-H", "127.0.0.1", "-P", "34567", "-s", diag.SERIAL,
            "shell", "logcat", "-b", "crash", "-b", "main", "-b", "system", "-d", "-v", "threadtime", "-v", "year", "-v", "UTC", "-t", "1000"])
        self.assertEqual(kwargs["timeout"], 45)
        self.assertEqual(kwargs["limit"], 256 * 1024)
        self.assertTrue(kwargs["binary"]); self.assertFalse(kwargs["check"])
        record, context = self.ctl.receipt["instrumentation"], self.ctl.receipt["crashContext"]
        self.assertEqual(record["status"], "FAIL")
        self.assertEqual(record["process"]["outputSha256"], "cc376fcd260b55a01f82c04af5e0a9ead88516f600131a38bdea1a3e05b10a8d")
        self.assertEqual(record["process"]["outputBytes"], 74)
        self.assertEqual(context["instrumentationProcess"], record["process"])
        self.assertEqual(context["installedSha256"], self.ctl.receipt["installedSha256"])
        self.assertEqual(context["sourceSha"], "a" * 40)
        self.assertEqual(context["status"], "CAPTURED")
        self.assertTrue(context["captureComplete"])
        self.assertNotIn("testProof", self.ctl.receipt); self.assertNotIn("junitXml", self.ctl.receipt)
        self.assertIn("RF_INSTRUMENTATION_RAW_CHUNK 1/1 ", self.output.getvalue())

    def test_success_does_not_capture_and_retains_all_exact_proof_gates(self):
        with mock.patch.object(self.ctl, "command", side_effect=lambda label, args, **kw:
                               self.result(kw, transcript().encode())) as command:
            self.ctl.tests()
        command.assert_called_once()
        self.assertNotIn("crashContext", self.ctl.receipt)
        self.assertEqual(self.ctl.receipt["instrumentation"]["status"], "EVENTS_VERIFIED")
        self.assertEqual(self.ctl.receipt["testProof"]["passed"], 4)
        self.assertIn("junitXml", self.ctl.receipt)

    def test_command_nonzero_timeout_and_signal_failures_keep_original_and_no_junit(self):
        for original in (diag.Failure("instrumentation failed with exit 9"),
                         diag.Deadline("instrumentation timed out"), diag.Cancelled("Signal 15")):
            with self.subTest(original=original):
                self.calls.clear(); self.ctl.receipt.pop("crashContext", None)
                def command(label, args, **kwargs):
                    if label == "instrumentation":
                        self.calls.append((label, args, kwargs))
                        self.result(kwargs, b"partial", complete=False)
                        raise original
                    return self.command(label, args, **kwargs)
                with mock.patch.object(self.ctl, "command", side_effect=command), self.assertRaises(type(original)) as caught:
                    self.ctl.tests()
                self.assertIs(caught.exception, original)
                self.assertFalse(self.ctl.receipt["instrumentation"]["process"]["complete"])
                self.assertEqual(len(self.calls), 1 if isinstance(original, diag.Cancelled) else 2)
                self.assertNotIn("junitXml", self.ctl.receipt)
                if isinstance(original, diag.Cancelled):
                    self.assertEqual(self.ctl.receipt["crashContext"]["status"], "SKIPPED_CANCELLED")

    def test_capture_nonzero_timeout_overflow_and_internal_error_cannot_mask_original(self):
        for problem in ("nonzero", "timeout", "overflow", "renderer"):
            with self.subTest(problem=problem):
                def command(label, args, **kwargs):
                    if label == "instrumentation": return self.command(label, args, **kwargs)
                    self.result(kwargs, b"partial context", code=7, complete=problem == "nonzero")
                    if problem == "timeout": raise diag.Deadline("device-crash-context timed out")
                    if problem == "overflow": raise diag.Failure("device-crash-context exceeded output limit")
                    return b"partial context", 7
                with contextlib.ExitStack() as stack:
                    stack.enter_context(mock.patch.object(self.ctl, "command", side_effect=command))
                    if problem == "renderer": stack.enter_context(mock.patch.object(diag, "crash_context_display", side_effect=ValueError("fixture")))
                    with self.assertRaisesRegex(diag.Failure, "Instrumentation crash/failure marker"):
                        self.ctl.tests()
                self.assertNotIn("testProof", self.ctl.receipt); self.assertNotIn("junitXml", self.ctl.receipt)
                if problem == "renderer":
                    self.assertEqual(self.ctl.receipt["crashContextError"], "ValueError")
                    self.assertEqual(self.ctl.receipt["crashContext"]["capturedSha256"], hashlib.sha256(b"partial context").hexdigest())
                    self.assertEqual(self.ctl.receipt["crashContext"]["displayPolicy"], "NOT_RENDERED")
                else:
                    record = self.ctl.receipt["crashContext"]
                    self.assertEqual(record["capturedBytes"], 15)
                    self.assertEqual(record["capturedSha256"], hashlib.sha256(b"partial context").hexdigest())
                    self.assertEqual(record["captureComplete"], problem == "nonzero")
                    self.assertEqual(record["status"], {"nonzero": "COMMAND_FAILED", "timeout": "TIMEOUT", "overflow": "FAIL"}[problem])

    def test_cancelled_expired_dead_unowned_and_unbound_capture_never_executes(self):
        for problem in ("cancel", "expired", "watchdog", "dead", "unowned", "no-emulator", "no-adb", "binding"):
            with self.subTest(problem=problem), contextlib.ExitStack() as stack:
                if problem == "cancel": stack.enter_context(mock.patch.object(self.ctl, "interrupted_signal", 15))
                if problem == "expired": stack.enter_context(mock.patch.object(self.ctl, "ends", 99))
                if problem == "watchdog": stack.enter_context(mock.patch.object(self.ctl, "watchdog_expired", True))
                if problem == "dead": stack.enter_context(mock.patch.object(diag.os, "waitid", return_value=object()))
                if problem == "unowned": stack.enter_context(mock.patch.object(diag, "process_listens_on_loopback", return_value=False))
                if problem == "no-emulator": stack.enter_context(mock.patch.object(self.ctl, "emulator", None))
                if problem == "no-adb": stack.enter_context(mock.patch.object(self.ctl, "adb_server", None))
                if problem == "binding": stack.enter_context(mock.patch.dict(self.ctl.receipt, installedSha256={}))
                command = stack.enter_context(mock.patch.object(self.ctl, "command"))
                self.ctl.capture_device_crash_context(diag.Failure("original"))
                command.assert_not_called()
                self.assertTrue(self.ctl.receipt["crashContext"]["status"].startswith("SKIPPED_"))
                self.assertFalse(self.ctl.receipt["crashContext"]["captureComplete"])

    def test_global_deadline_clips_capture_and_instrumentation_elapsed_excludes_capture(self):
        self.ctl.ends = 107
        def command(label, args, **kwargs):
            result = self.command(label, args, **kwargs)
            self.now += 2 if label == "instrumentation" else 3
            return result
        with mock.patch.object(self.ctl, "command", side_effect=command), self.assertRaises(diag.Failure):
            self.ctl.tests()
        self.assertEqual(self.calls[-1][2]["timeout"], 5)
        self.assertEqual(self.ctl.receipt["instrumentation"]["elapsedSeconds"], 2)
        self.assertEqual(self.ctl.receipt["crashContext"]["elapsedSeconds"], 3)
        self.assertEqual(diag.BUDGETS["controller"], 4320); self.assertEqual(diag.BUDGETS["workflow"], 4500)

    def test_guard_cancellation_or_lost_ownership_during_capture_preserves_original_failure(self):
        for problem in ("cancel", "deadline", "unowned"):
            with self.subTest(problem=problem):
                self.ctl.interrupted_signal, self.ctl.ends = 0, 1000
                self.listening.return_value = True
                def command(label, args, **kwargs):
                    if label == "instrumentation": return self.command(label, args, **kwargs)
                    self.result(kwargs, b"bounded partial", complete=False)
                    if problem == "cancel": self.ctl.interrupted_signal = 15
                    if problem == "deadline": self.ctl.ends = self.now
                    if problem == "unowned": self.listening.return_value = False
                    kwargs["guard"]()
                    self.fail("Guard must interrupt capture")
                with mock.patch.object(self.ctl, "command", side_effect=command), \
                        self.assertRaisesRegex(diag.Failure, "Instrumentation crash/failure marker"):
                    self.ctl.tests()
                record = self.ctl.receipt["crashContext"]
                self.assertEqual(record["status"], {"cancel": "CANCELLED", "deadline": "TIMEOUT", "unowned": "FAIL"}[problem])
                self.assertFalse(record["captureComplete"])
                self.assertEqual(record["capturedBytes"], 15)
                self.assertNotIn("junitXml", self.ctl.receipt)

    def test_crash_context_remains_diagnostic_and_terminal_original_fail_after_cleanup(self):
        with mock.patch.object(self.ctl, "command", side_effect=self.command), \
                mock.patch.object(self.ctl, "prerequisites"), mock.patch.object(self.ctl, "build"), \
                mock.patch.object(self.ctl, "boot"), mock.patch.object(diag, "stop_owned"):
            self.assertEqual(self.ctl.run(), 1)
        self.assertEqual(self.ctl.receipt["status"], "FAIL")
        self.assertEqual(self.ctl.receipt["error"], "Instrumentation crash/failure marker")
        self.assertEqual(self.ctl.receipt["crashContext"]["scope"], "diagnostic-only-not-test-proof")
        self.assertNotIn("RF_DIAGNOSTIC_JUNIT", self.output.getvalue())
        self.assertNotIn("testProof", self.ctl.receipt)

    def test_display_redaction_distinct_hash_and_safe_prefix_without_raw_crash_emission(self):
        raw = b"::error::not a workflow command\nandroidboot.qemu.adb.pubkey=SYNTHETIC_KEY\n\x1b[31mstack\n"
        with mock.patch.object(self.ctl, "command", side_effect=lambda label, args, **kw: self.result(kw, raw)):
            self.ctl.capture_device_crash_context(diag.Failure("original"))
        context = self.ctl.receipt["crashContext"]
        display, _ = diag.crash_context_display(raw)
        self.assertEqual(context["capturedSha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(context["displaySha256"], hashlib.sha256(display).hexdigest())
        self.assertNotEqual(context["capturedSha256"], context["displaySha256"])
        self.assertNotIn("SYNTHETIC_KEY", self.output.getvalue()); self.assertNotIn("\x1b", self.output.getvalue())
        self.assertNotIn("RAW_CHUNK", self.output.getvalue())
        self.assertTrue(all(line.startswith(("RF_CRASH_CONTEXT_META ", "RF_LAST_ANR_TRACES_META ", "RF_DIAGNOSTIC_LOG "))
                            for line in self.output.getvalue().splitlines()))

    def test_recognizable_unexpected_secrets_withhold_entire_display(self):
        for raw in (b"-----BEGIN PRIVATE KEY-----\nSYNTHETIC_SECRET\n-----END PRIVATE KEY-----",
                    b"Authorization: Bearer SYNTHETIC_SECRET", b"password=SYNTHETIC_SECRET",
                    b"client_secret=SYNTHETIC_SECRET", b"https://user:SYNTHETIC_SECRET@example.invalid",
                    b"ghp_" + b"X" * 25):
            with self.subTest(raw=raw):
                self.assertEqual(diag.crash_context_display(raw), (b"", "WITHHELD_SECRET_MARKER"))
        self.assertEqual(diag.crash_context_display(b"\xff" * diag.CRASH_CONTEXT_LIMIT), (b"", "WITHHELD_DISPLAY_LIMIT"))

    def test_unexpected_secret_never_appears_in_capture_output_or_receipt(self):
        raw = b"stack\n-----BEGIN PRIVATE KEY-----\nSYNTHETIC_PRIVATE_MATERIAL\n-----END PRIVATE KEY-----\n"
        with mock.patch.object(self.ctl, "command", side_effect=lambda label, args, **kw: self.result(kw, raw)):
            self.ctl.capture_device_crash_context(diag.Failure("original"))
        record = self.ctl.receipt["crashContext"]
        self.assertEqual(record["capturedSha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(record["displayPolicy"], "WITHHELD_SECRET_MARKER")
        self.assertEqual(record["displayBytes"], 0)
        self.assertNotIn("SYNTHETIC_PRIVATE_MATERIAL", self.output.getvalue())
        self.assertNotIn("PRIVATE KEY", self.output.getvalue())

    def test_prefixed_display_roundtrips_its_own_hash_not_original_bytes(self):
        for raw in (b"leading\r\n\r\ncontrol\x00\x1b\t\n\n", b"\n\n",
                    "\nΩ\u2028unicode\n".encode() + b"invalid\xff\n",
                    b"::error::fixture\nSending adb public key [SYNTHETIC]\n\n"):
            with self.subTest(raw=raw):
                self.output.seek(0); self.output.truncate(0)
                with mock.patch.object(self.ctl, "command", side_effect=lambda label, args, **kw: self.result(kw, raw)):
                    self.ctl.capture_device_crash_context(diag.Failure("original"))
                prefix = "RF_DIAGNOSTIC_LOG device-crash-context-display | "
                reconstructed = "\n".join(line[len(prefix):] for line in self.output.getvalue().splitlines()
                                          if line.startswith(prefix)).encode("utf-8")
                record = self.ctl.receipt["crashContext"]
                self.assertEqual(hashlib.sha256(reconstructed).hexdigest(), record["displaySha256"])
                self.assertEqual(len(reconstructed), record["displayBytes"])
                self.assertEqual(record["capturedSha256"], hashlib.sha256(raw).hexdigest())
                self.assertNotEqual(record["capturedSha256"], record["displaySha256"])



class AospImageAndFactoryEvidence(unittest.TestCase):
    def test_exact_default_image_revision_and_conflicting_metadata(self):
        props = {"AndroidVersion.ApiLevel": "35", "SystemImage.Abi": "x86_64",
                 "SystemImage.TagId": "default", "Pkg.Revision": "2"}
        metadata = {"package": diag.IMAGE, "revision": "2.0.0"}
        diag.validate_image_metadata(props, metadata)
        self.assertEqual(diag.IMAGE, "system-images;android-35;default;x86_64")
        for key, value in (("AndroidVersion.ApiLevel", "36"), ("SystemImage.Abi", "arm64-v8a"),
                           ("SystemImage.TagId", "aosp_atd"), ("SystemImage.TagId", "google_apis"),
                           ("Pkg.Revision", "1"), ("Pkg.Revision", "3")):
            with self.subTest(key=key, value=value), self.assertRaises(diag.Blocked):
                diag.validate_image_metadata({**props, key: value}, metadata)
        for change in ({"package": "foreign"}, {"revision": "3"}, {"revision": "2.1.0"}):
            with self.subTest(change=change), self.assertRaises(diag.Blocked):
                diag.validate_image_metadata(props, {**metadata, **change})

    def test_factory_values_read_only_unknown_not_equivalence_debuggable_not_suppression(self):
        ctl = diag.Controller(env={})
        def shell(*args, **kwargs):
            if args[:3] == ("settings", "get", "global"): return "null"
            return {"ro.debuggable": "1", "ro.hw_timeout_multiplier": ""}.get(args[-1], "observed")
        with mock.patch.object(ctl, "shell", side_effect=shell) as command:
            ctl.factory_evidence()
        record = ctl.receipt["deviceFactoryEvidence"]
        self.assertEqual(record["properties"]["ro.hw_timeout_multiplier"], "")
        self.assertEqual(record["properties"]["ro.debuggable"], "1")
        self.assertEqual(record["watchdogEquivalenceToPreviousGoogleImage"], "UNVERIFIED")
        self.assertEqual(record["status"], "RECORDED")
        for call in command.call_args_list:
            self.assertIn(call.args[0], ("getprop", "settings"))
            if call.args[0] == "settings": self.assertEqual(call.args[1:3], ("get", "global"))
            self.assertLessEqual(call.kwargs["timeout"], 60)

    def test_selected_debugger_suppression_blocks_without_setting_writes(self):
        for package in (diag.APP, diag.APP + ".test"):
            record = {"globalSettings": {"debug_app": package, "wait_for_debugger": "0"}}
            with self.subTest(package=package), self.assertRaisesRegex(diag.Blocked, "suppresses"):
                diag.validate_factory_evidence(record)
        for value in ("", "null", "0", "1"):
            diag.validate_factory_evidence({"globalSettings": {"debug_app": "null", "wait_for_debugger": value}})

    def test_device_clock_exact_utc_invalid_nonzero_and_deadline(self):
        ctl = diag.Controller(env={}, clock=lambda: 100)
        with mock.patch.object(ctl, "adb_args", side_effect=lambda *args: ["owned-adb", *args]):
            for output, code in (("2026-10-23T12:31:49Z\n", 0), ("2026-02-31T00:00:00Z", 0),
                                 ("garbage", 0), ("2026-10-23T12:31:49Z", 1)):
                def response(*args, **kwargs):
                    kwargs["identity"].update(complete=True, exit=code)
                    return output, code
                with self.subTest(output=output, code=code), mock.patch.object(ctl, "command", side_effect=response) as command:
                    record = ctl.device_clock_sample(deadline=103)
                    self.assertEqual(record["status"], "CAPTURED" if code == 0 and output.endswith("Z\n") else "UNAVAILABLE")
                    self.assertEqual(command.call_args.kwargs["timeout"], 3)
                    self.assertEqual(command.call_args.args[1][-3:], ["date", "-u", "+%Y-%m-%dT%H:%M:%SZ"])
            with mock.patch.object(ctl, "command", side_effect=diag.Deadline("local")):
                self.assertEqual(ctl.device_clock_sample()["status"], "UNAVAILABLE")
            ctl.interrupted_signal = 15
            with self.assertRaises(diag.Cancelled): ctl.device_clock_sample()


class AnrTraceAssociation(unittest.TestCase):
    LOG = ("2026-10-23 12:32:33.442   579  3512 E ActivityManager: ANR in " + diag.APP + "\n"
           "2026-10-23 12:32:33.442   579  3512 E ActivityManager: PID: 3273\n"
           "2026-10-23 12:32:33.442   579  3512 E ActivityManager: Reason: Process ProcessRecord{abc 3273:"
           + diag.APP + "/u0a209} failed to complete startup\n").encode()
    TRACE = ("ACTIVITY MANAGER LAST ANR TRACES (dumpsys activity lastanr-traces)\nFile: anr_fixture\n"
             "----- pid 3273 at 2026-10-23 12:32:31.122123456+0000 -----\nCmd line: " + diag.APP
             + '\n"main" prio=5 tid=1 Runnable\n  at fixture.Class.method(Fixture.kt:1)\n----- end 3273 -----\n').encode()
    BEFORE = {"status": "CAPTURED", "epochSeconds": diag.datetime(2026, 10, 23, 12, 31, 49, tzinfo=diag.timezone.utc).timestamp(), "sampleMonotonic": 100}
    AFTER = {"status": "CAPTURED", "epochSeconds": diag.datetime(2026, 10, 23, 12, 32, 40, tzinfo=diag.timezone.utc).timestamp(), "sampleMonotonic": 151}

    def test_exact_process_pid_utc_window_and_explicit_trace_timezone(self):
        for trace in (self.TRACE, self.TRACE.replace(b"12:32:31.122123456+0000", b"14:32:31.122123456+0200")):
            result = diag.associate_anr_trace(self.LOG, trace, self.BEFORE, self.AFTER)
            self.assertEqual(result["status"], "ASSOCIATED_DIAGNOSTIC_ONLY")
            self.assertEqual(result["pid"], 3273)
            self.assertEqual(result["package"], diag.APP)

    def test_foreign_stale_partial_ambiguous_missing_timezone_never_associated(self):
        mutations = (self.TRACE.replace(b"3273", b"3274"), self.TRACE.replace(diag.APP.encode(), b"foreign.app"),
                     self.TRACE.replace(b"12:32:31", b"11:32:31"), self.TRACE.replace(b"----- end 3273 -----", b""),
                     self.TRACE + self.TRACE, self.TRACE.replace(b"+0000", b""),
                     self.TRACE.replace(b'"main"', b'"worker"'), self.TRACE + b"----- pid 3273 at partial\n",
                     self.TRACE + b"Cmd line: " + diag.APP.encode() + b"\n")
        for trace in mutations:
            with self.subTest(trace=trace[-100:]):
                self.assertEqual(diag.associate_anr_trace(self.LOG, trace, self.BEFORE, self.AFTER)["status"], "UNASSOCIATED")
        for log in (self.LOG + self.LOG, self.LOG.replace(b"PID: 3273", b"PID: 3274"),
                    self.LOG.replace(b"2026-10-23", b"10-23"), self.LOG.replace(b"12:32:33", b"11:32:33"),
                    self.LOG.replace(b"579  3512", b"579  3513", 1), b"\xff"):
            with self.subTest(log=log[:100]):
                self.assertEqual(diag.associate_anr_trace(log, self.TRACE, self.BEFORE, self.AFTER)["status"], "UNASSOCIATED")

    def test_missing_device_clock_or_clock_jump_never_borrows_host_time(self):
        for before, after in (({}, self.AFTER), (self.BEFORE, {}),
                              (self.BEFORE, {**self.AFTER, "sampleMonotonic": 10}),
                              (self.BEFORE, {**self.AFTER, "epochSeconds": self.AFTER["epochSeconds"] + 60})):
            with self.subTest(before=before, after=after):
                self.assertEqual(diag.associate_anr_trace(self.LOG, self.TRACE, before, after)["status"], "UNASSOCIATED")


class DeviceLastAnrTraces(unittest.TestCase):
    setUp = DeviceCrashContext.setUp
    result = DeviceCrashContext.result
    CRASH = DeviceCrashContext.CRASH

    def command(self, label, args, **kwargs):
        self.calls.append((label, args, kwargs))
        if label == "instrumentation":
            self.now += 49
            return self.result(kwargs, self.CRASH.encode())
        kwargs["guard"]()
        if label == "device-crash-context":
            self.now += 2
            return self.result(kwargs, AnrTraceAssociation.LOG)
        self.assertEqual(label, "device-last-anr-traces")
        self.now += 1
        return self.result(kwargs, AnrTraceAssociation.TRACE)

    def execute(self, command=None):
        with mock.patch.object(self.ctl, "device_clock_sample", side_effect=[AnrTraceAssociation.BEFORE, AnrTraceAssociation.AFTER]), \
                mock.patch.object(self.ctl, "command", side_effect=command or self.command), \
                self.assertRaisesRegex(diag.Failure, "Instrumentation crash/failure marker"):
            self.ctl.tests()

    def test_complete_trace_associated_but_original_failure_and_no_junit(self):
        self.execute()
        self.assertEqual([c[0] for c in self.calls], ["instrumentation", "device-crash-context", "device-last-anr-traces"])
        label, args, kwargs = self.calls[-1]
        self.assertEqual(args[-4:], ["shell", "dumpsys", "activity", "lastanr-traces"])
        self.assertEqual(kwargs["timeout"], 43)
        self.assertEqual(kwargs["limit"], diag.CRASH_CONTEXT_LIMIT)
        record = self.ctl.receipt["lastAnrTraces"]
        self.assertEqual(record["status"], "CAPTURED")
        self.assertEqual(record["association"]["status"], "ASSOCIATED_DIAGNOSTIC_ONLY")
        self.assertEqual(record["capturedSha256"], hashlib.sha256(AnrTraceAssociation.TRACE).hexdigest())
        self.assertEqual(record["sourceSha"], self.ctl.receipt["sourceSha"])
        self.assertEqual(record["installedSha256"], self.ctl.receipt["installedSha256"])
        self.assertNotIn("testProof", self.ctl.receipt); self.assertNotIn("junitXml", self.ctl.receipt)
        prefix = "RF_DIAGNOSTIC_LOG device-last-anr-traces-display | "
        display = "\n".join(line[len(prefix):] for line in self.output.getvalue().splitlines() if line.startswith(prefix)).encode()
        self.assertEqual(hashlib.sha256(display).hexdigest(), record["displaySha256"])

    def test_nonzero_timeout_partial_and_permission_denial_do_not_mask_original(self):
        for problem in ("nonzero", "timeout", "overflow", "denied", "secret"):
            with self.subTest(problem=problem):
                self.now = 100; self.calls.clear()
                def command(label, args, **kwargs):
                    if label != "device-last-anr-traces": return self.command(label, args, **kwargs)
                    self.calls.append((label, args, kwargs))
                    raw = b"Permission Denial" if problem == "denied" else b"password=unexpected" if problem == "secret" else AnrTraceAssociation.TRACE
                    result = self.result(kwargs, raw, code=9 if problem == "nonzero" else 0,
                                         complete=problem not in ("timeout", "overflow"))
                    if problem == "timeout": raise diag.Deadline("local dump timeout")
                    if problem == "overflow": raise diag.Failure("dump exceeded output cap")
                    return result
                self.execute(command)
                record = self.ctl.receipt["lastAnrTraces"]
                self.assertEqual(record["association"]["status"], "UNASSOCIATED")
                self.assertNotIn("junitXml", self.ctl.receipt)
                if problem == "secret": self.assertEqual(record["displayPolicy"], "WITHHELD_SECRET_MARKER")

    def test_shared_deadline_cancellation_and_ownership_prevent_dump(self):
        for problem in ("deadline", "cancel", "ownership"):
            with self.subTest(problem=problem):
                self.now, self.ctl.ends, self.ctl.interrupted_signal = 100, 1000, 0
                self.listening.return_value = True
                self.calls.clear()
                def command(label, args, **kwargs):
                    result = self.command(label, args, **kwargs)
                    if label == "device-crash-context":
                        if problem == "deadline": self.now += 44
                        if problem == "cancel": self.ctl.interrupted_signal = 15
                        if problem == "ownership": self.listening.return_value = False
                    return result
                self.execute(command)
                self.assertEqual(len(self.calls), 2)
                self.assertTrue(self.ctl.receipt["lastAnrTraces"]["status"].startswith("SKIPPED_"))
                self.assertNotIn("junitXml", self.ctl.receipt)

    def test_trace_renderer_failure_retains_raw_metadata_and_original_exception(self):
        render = diag.crash_context_display
        def renderer(raw):
            if raw == AnrTraceAssociation.TRACE: raise ValueError("fixture renderer")
            return render(raw)
        with mock.patch.object(diag, "crash_context_display", side_effect=renderer):
            self.execute()
        record = self.ctl.receipt["lastAnrTraces"]
        self.assertEqual(record["capturedSha256"], hashlib.sha256(AnrTraceAssociation.TRACE).hexdigest())
        self.assertTrue(record["captureComplete"])
        self.assertEqual(record["displayPolicy"], "NOT_RENDERED")
        self.assertEqual(self.ctl.receipt["crashContextError"], "ValueError")
        self.assertNotIn("junitXml", self.ctl.receipt)

    def test_foreign_latest_trace_stays_unassociated_without_retry(self):
        def command(label, args, **kwargs):
            if label != "device-last-anr-traces": return self.command(label, args, **kwargs)
            self.calls.append((label, args, kwargs))
            return self.result(kwargs, AnrTraceAssociation.TRACE.replace(diag.APP.encode(), b"foreign.app"))
        self.execute(command)
        self.assertEqual(len(self.calls), 3)
        self.assertEqual(self.ctl.receipt["lastAnrTraces"]["association"]["status"], "UNASSOCIATED")

class CrashCaptureSubprocess(unittest.TestCase):
    def test_real_partial_timeout_and_overflow_hash_only_retained_bytes_and_cleanup(self):
        for kind in ("timeout", "overflow", "signal"):
            with self.subTest(kind=kind):
                ctl, raw, identity, output = diag.Controller(), bytearray(), {}, io.StringIO()
                script = {"timeout": "import time; print('partial',flush=True); time.sleep(60)",
                          "overflow": "import sys,time; sys.stdout.buffer.write(b'x'*10000); sys.stdout.flush(); time.sleep(60)",
                          "signal": "import os,signal; print('partial',flush=True); os.kill(os.getpid(),signal.SIGTERM)"}[kind]
                with contextlib.redirect_stdout(output):
                    if kind == "signal":
                        _, code = ctl.command("context-fixture", [sys.executable, "-c", script], timeout=3,
                            limit=100, binary=True, identity=identity, raw_capture=raw, check=False)
                        self.assertEqual(code, 143)
                    else:
                        with self.assertRaises(diag.Failure):
                            ctl.command("context-fixture", [sys.executable, "-c", script], timeout=.25,
                                limit=100, binary=True, identity=identity, raw_capture=raw)
                self.assertEqual(identity["outputBytes"], len(raw))
                self.assertEqual(identity["outputSha256"], hashlib.sha256(raw).hexdigest())
                self.assertEqual(identity["complete"], kind == "signal")
                self.assertEqual(bytes(raw), b"x" * 100 if kind == "overflow" else b"partial\n")
                self.assertNotIn("partial", output.getvalue()); self.assertNotIn("x" * 20, output.getvalue())
                with self.assertRaises(ChildProcessError): os.waitpid(identity["pid"], os.WNOHANG)


class DiagnosticPublicKeyPrivacy(unittest.TestCase):
    def test_known_emulator_key_forms_are_redacted_before_tail_truncation(self):
        key = "SYNTHETIC_PUBLIC_KEY_BYTES" * 1000
        for line in ("INFO | Sending adb public key [" + key + " runner@fixture]\n",
                     "INFO | androidboot.qemu.adb.pubkey=" + key + " runner@fixture\n"):
            with self.subTest(marker=line[:50]):
                output = io.StringIO()
                with contextlib.redirect_stdout(output): diag.emit("emulator-tail", line + "ordinary line\n", limit=80)
                self.assertNotIn("SYNTHETIC_PUBLIC_KEY_BYTES", output.getvalue())
                self.assertNotIn("runner@fixture", output.getvalue())
                self.assertIn("REDACTED_EPHEMERAL_ADB_PUBLIC_KEY", output.getvalue())
                self.assertIn("displayRedacted=true", output.getvalue())
                self.assertIn("ordinary line", output.getvalue())

    def test_clean_log_bytes_and_unredacted_raw_proofs_keep_existing_semantics(self):
        plain = "normal diagnostic\n"
        self.assertEqual(diag.redact_diagnostic_text(plain), plain)
        output = io.StringIO()
        with contextlib.redirect_stdout(output): diag.emit("plain", plain)
        self.assertNotIn("displayRedacted", output.getvalue())
        self.assertIn("bytes=18", output.getvalue())
        for method, raw in ((diag.emit_raw_instrumentation, "Sending adb public key [SYNTHETIC]\n"),
                            (lambda raw: diag.emit_raw_loader_trace(raw, True), b"androidboot.qemu.adb.pubkey=SYNTHETIC\n")):
            output = io.StringIO()
            with contextlib.redirect_stdout(output), self.assertRaises(diag.Failure): method(raw)
            self.assertEqual(output.getvalue(), "")  # Reject, never hash a silently rewritten proof.


class PrivateAdbOwnership(unittest.TestCase):
    def test_all_user_state_and_keys_are_task_local(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            env = diag.private_environment({"HOME": "/foreign/home", "ADB_VENDOR_KEYS": "/foreign/key",
                "ANDROID_SERIAL": "user-device", "ANDROID_SDK_HOME": "/foreign", "JAVA_TOOL_OPTIONS": "-Duser.home=/foreign"}, temp, 34567)
            for key in ("HOME", "ANDROID_USER_HOME", "ANDROID_SDK_HOME", "ANDROID_EMULATOR_HOME",
                        "ANDROID_AVD_HOME", "GRADLE_USER_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "TMPDIR"):
                self.assertTrue(Path(env[key]).is_relative_to(temp), key)
                self.assertTrue(Path(env[key]).is_dir(), key)
            self.assertNotIn("ADB_VENDOR_KEYS", env)
            self.assertNotIn("ANDROID_SERIAL", env)
            self.assertEqual(env["JAVA_TOOL_OPTIONS"], "-Duser.home=" + str(temp / "home"))
            self.assertEqual(env["ADB_SERVER_SOCKET"], "tcp:127.0.0.1:34567")
            self.assertEqual(env["ANDROID_ADB_SERVER_PORT"], "34567")
            self.assertEqual(env["ADB_MDNS_AUTO_CONNECT"], "0")
            self.assertEqual(env["ADB_LOCAL_TRANSPORT_MAX_PORT"], "0")

    def test_read_only_listener_verification_recognizes_real_owned_loopback(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            self.assertTrue(diag.process_listens_on_loopback(os.getpid(), listener.getsockname()[1]))

    def test_foreign_socket_inode_and_wildcard_listener_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            (proc / "123/fd").mkdir(parents=True)
            (proc / "net").mkdir()
            (proc / "123/fd/4").symlink_to("socket:[777]")
            table = proc / "net/tcp"
            def row(address, inode):
                return f"header\n0: {address}:8707 00000000:0000 0A 0 0 0 0 0 {inode}\n"
            table.write_text(row("0100007F", "888"))
            self.assertFalse(diag.process_listens_on_loopback(123, 34567, proc))
            table.write_text(row("0100007F", "777"))
            self.assertTrue(diag.process_listens_on_loopback(123, 34567, proc))
            table.write_text(row("00000000", "777"))
            with self.assertRaises(diag.Failure):
                diag.process_listens_on_loopback(123, 34567, proc)

    def test_client_commands_require_owned_live_server_and_explicit_non_autostart_endpoint(self):
        ctl = diag.Controller()
        ctl.adb, ctl.adb_port = "/sdk/adb", 34567
        with self.assertRaises(diag.Failure):
            ctl.adb_args("devices")
        ctl.adb_server = mock.Mock(pid=123)
        with mock.patch.object(diag.os, "waitid", return_value=None), mock.patch.object(diag, "process_listens_on_loopback", return_value=True):
            self.assertEqual(ctl.adb_args("-s", diag.SERIAL, "shell", "id"),
                ["/sdk/adb", "-H", "127.0.0.1", "-P", "34567", "-s", diag.SERIAL, "shell", "id"])
        with mock.patch.object(diag.os, "waitid", return_value=object()), self.assertRaises(diag.Failure):
            ctl.adb_args("devices")
        with mock.patch.object(diag.os, "waitid", return_value=None), mock.patch.object(diag, "process_listens_on_loopback", return_value=False), self.assertRaises(diag.Failure):
            ctl.adb_args("devices")

    def test_server_starts_foreground_with_usb_filter_and_owned_process_group(self):
        with tempfile.TemporaryDirectory() as directory:
            ctl = diag.Controller()
            ctl.temp, ctl.adb, ctl.adb_port = Path(directory), "/sdk/adb", 34567
            ctl.env = diag.private_environment({}, ctl.temp, ctl.adb_port)
            fake = mock.Mock(pid=123)
            with mock.patch.object(diag.subprocess, "Popen", return_value=fake) as popen, \
                    mock.patch.object(diag.os, "set_blocking"), mock.patch.object(diag.os, "waitid", return_value=None), \
                    mock.patch.object(diag, "process_listens_on_loopback", return_value=True), mock.patch.object(ctl, "drain_background"):
                ctl.start_private_adb()
            argv = popen.call_args.args[0]
            self.assertEqual(argv[:4], ["/sdk/adb", "-L", "tcp:localhost:34567", "--one-device"])
            self.assertTrue(argv[4].startswith("RF_DIAGNOSTIC_NO_USB_"))
            self.assertEqual(argv[-2:], ["server", "nodaemon"])
            self.assertTrue(popen.call_args.kwargs["start_new_session"])
            self.assertEqual(popen.call_args.kwargs["stdin"], subprocess.DEVNULL)
            self.assertEqual(ctl.receipt["adbServer"]["socketOwnership"], "verified-/proc-inode")
            ctl.adb_server_output.close()


if __name__ == "__main__":
    unittest.main()
