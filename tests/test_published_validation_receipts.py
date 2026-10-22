"""Synthetic verifier tests; no scanner execution or historical evidence creation."""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1] / "reports" / "published-validation"


class ReceiptVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="receipt-verifier-")
        cls.root = Path(cls.temporary.name)
        cls.repo = cls.root / "retained"
        cls.git("init", "-b", "main", str(cls.repo))
        fixture = cls.repo / "fixture.txt"
        fixture.write_text("earlier synthetic source\n")
        cls.git("-C", str(cls.repo), "add", "fixture.txt")
        cls.git("-C", str(cls.repo), "commit", "-m", "Earlier synthetic source")
        cls.earlier = cls.source("HEAD")
        fixture.write_text("current synthetic source\n")
        cls.git("-C", str(cls.repo), "commit", "-am", "Published synthetic source")
        cls.published = cls.source("HEAD")
        cls.validated = cls.source(cls.git(
            "-C", str(cls.repo), "commit-tree", cls.published["tree"],
            input=b"Retained validation alias\n").decode().strip())
        cls.build = cls.source(cls.git(
            "-C", str(cls.repo), "commit-tree", cls.published["tree"],
            input=b"Retained artifact build\n").decode().strip())
        cls.public_repo = cls.root / "public.git"
        cls.git("clone", "--bare", "--no-local", "--single-branch", "--branch", "main",
                str(cls.repo), str(cls.public_repo))
        cls.names = ["cloud-dense-incumbent-validation.json", "cloud-ci-diagnostic-validation.json"]
        cls.templates = {}
        for name in cls.names:
            receipt = json.loads((HERE.parent / name).read_text())
            receipt["sources"] = {
                "published": cls.published, "validated": cls.validated,
                "artifactBuild": cls.build,
            }
            receipt["sourceBindings"] = {"fixture.txt": hashlib.sha256(fixture.read_bytes()).hexdigest()}
            cls.templates[name] = receipt

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    @classmethod
    def git(cls, *args, input=None):
        return subprocess.check_output([
            "git", "-c", "user.name=Receipt Test", "-c", "user.email=receipt@example.invalid",
            *args,
        ], input=input, stderr=subprocess.PIPE)

    @classmethod
    def source(cls, revision):
        return {
            "commit": cls.git("-C", str(cls.repo), "rev-parse", revision).decode().strip(),
            "tree": cls.git("-C", str(cls.repo), "rev-parse", revision + "^{tree}").decode().strip(),
        }

    def setUp(self):
        self.case = tempfile.TemporaryDirectory(dir=self.root)
        self.reports = Path(self.case.name) / "reports"
        directory = self.reports / "published-validation"
        directory.mkdir(parents=True)
        for name in ["verify.py", "receipt.schema.json"]:
            shutil.copyfile(HERE / name, directory / name)
        self.receipts = copy.deepcopy(self.templates)

    def tearDown(self):
        self.case.cleanup()

    def run_verifier(self, repo=None, strict=False, *extra, raw_transform=None):
        for name, receipt in self.receipts.items():
            (self.reports / name).write_text(json.dumps(receipt))
        if raw_transform:
            target = self.reports / self.names[0]
            target.write_text(raw_transform(target.read_text()))
        command = [sys.executable, str(self.reports / "published-validation/verify.py"),
                   "--repo", str(repo or self.repo)]
        if strict:
            command.append("--require-retained-source-objects")
        return subprocess.run([*command, *extra], capture_output=True, text=True)

    def test_fresh_public_objectstore_is_explicitly_partial(self):
        result = self.run_verifier(self.public_repo)
        self.assertEqual(result.returncode, 0, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual(output["sourceLinkScope"], "PARTIAL")
        self.assertEqual(output["publishedSourceBindings"], "PASS")
        self.assertEqual(output["sourceRecordsChecked"], 2)
        self.assertEqual(output["sourceBindingsChecked"], 2)
        for roles in output["sourceObjects"].values():
            self.assertEqual(roles["published"], "PASS")
            for role in ["validated", "artifactBuild"]:
                self.assertEqual(roles[role], "NOT_CHECKED_RETAINED_COMMIT_UNAVAILABLE")

    def test_strict_complete_retained_objects(self):
        result = self.run_verifier(strict=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual(output["sourceLinkScope"], "COMPLETE")
        self.assertEqual(output["sourceRecordsChecked"], 6)
        self.assertEqual(output["sourceBindingsChecked"], 5)

    def test_strict_missing_retained_role_fails(self):
        result = self.run_verifier(self.public_repo, strict=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Required source commit unavailable: dense-incumbent.validated", result.stderr)

    def test_invalid_published_commit_fails_in_default_mode(self):
        self.receipts[self.names[0]]["sources"]["published"]["commit"] = "0" * 40
        result = self.run_verifier(self.public_repo)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Required source commit unavailable: dense-incumbent.published", result.stderr)

    def test_available_retained_tree_mismatch_is_not_skipped(self):
        self.receipts[self.names[0]]["sources"]["artifactBuild"]["tree"] = "0" * 40
        result = self.run_verifier()
        self.assertEqual(result.returncode, 1)
        self.assertIn("Commit/tree mismatch: dense-incumbent.artifactBuild", result.stderr)

    def test_available_retained_source_mismatch_is_not_skipped(self):
        self.receipts[self.names[0]]["sources"]["artifactBuild"] = self.earlier
        result = self.run_verifier()
        self.assertEqual(result.returncode, 1)
        self.assertIn("Source binding mismatch: dense-incumbent.artifactBuild", result.stderr)

    def test_noncommit_retained_object_fails(self):
        self.receipts[self.names[0]]["sources"]["artifactBuild"]["commit"] = self.build["tree"]
        result = self.run_verifier()
        self.assertEqual(result.returncode, 1)
        self.assertIn("Source identity is not a commit object", result.stderr)

    def test_git_error_is_not_missing_retained_evidence(self):
        result = self.run_verifier(self.root / "no-repository")
        self.assertEqual(result.returncode, 1)
        self.assertIn("CalledProcessError", result.stderr)

    def test_boolean_schema_version_and_integer_counts_are_rejected(self):
        changes = [
            lambda receipt: receipt.update(schemaVersion=True),
            lambda receipt: receipt["gates"][0]["counts"].update(buildSteps=True),
        ]
        for change in changes:
            self.receipts = copy.deepcopy(self.templates)
            change(self.receipts[self.names[0]])
            result = self.run_verifier()
            self.assertEqual(result.returncode, 1)
            self.assertIn("Receipt schema constraint failed", result.stderr)

    def test_missing_malformed_and_extra_fields_are_rejected(self):
        changes = [
            lambda receipt: receipt.pop("gates"),
            lambda receipt: receipt.update(notes="not-an-array"),
            lambda receipt: receipt.update(unexpected=True),
            lambda receipt: receipt["sources"]["published"].update(unexpected=True),
            lambda receipt: receipt["artifacts"]["hostEngine"].update(sha256="not-a-hash"),
        ]
        for change in changes:
            self.receipts = copy.deepcopy(self.templates)
            change(self.receipts[self.names[0]])
            result = self.run_verifier()
            self.assertEqual(result.returncode, 1)
            self.assertIn("Receipt schema constraint failed", result.stderr)

    def test_duplicate_json_keys_are_rejected(self):
        result = self.run_verifier(raw_transform=lambda text: text.replace(
            '"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1', 1))
        self.assertEqual(result.returncode, 1)
        self.assertIn("Duplicate JSON key", result.stderr)

    def test_invalid_source_paths_fail_schema_before_git(self):
        # These must fail schema validation, not merely fail a later git show.
        for path in ["../", "../escape", "x/../y", "x/..", "..", "/absolute", "x\\y"]:
            self.receipts = copy.deepcopy(self.templates)
            self.receipts[self.names[0]]["sourceBindings"] = {path: "0" * 64}
            result = self.run_verifier()
            self.assertEqual(result.returncode, 1)
            self.assertIn("Receipt schema constraint failed", result.stderr, path)

    def test_unknown_schema_keywords_fail_closed(self):
        path = self.reports / "published-validation/receipt.schema.json"
        schema = json.loads(path.read_text())
        schema["unknownKeyword"] = True
        path.write_text(json.dumps(schema))
        result = self.run_verifier()
        self.assertEqual(result.returncode, 1)
        self.assertIn("Unsupported schema keyword", result.stderr)


if __name__ == "__main__":
    unittest.main()
