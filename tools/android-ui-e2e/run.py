#!/usr/bin/env python3
"""Run actual Android UI scenarios and retain candidate-bound, fail-closed evidence.

This runner does not implement process killing or a fault document provider.
Those capabilities are supplied by Agent 088 through the JSON controller adapter.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import struct
import subprocess
import sys
import time
from typing import Any
import uuid
import zlib


SHA = re.compile(r"[0-9a-f]{64}\Z")
SOURCE_SHA = re.compile(r"[0-9a-f]{40}\Z")
CLASS = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+(?:#[A-Za-z_][A-Za-z0-9_]*)?\Z")
RUNNER = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z_][A-Za-z0-9_.]*\Z")
PNG_NAME = re.compile(r"rf-ui-([0-9a-f]{32})-[a-z0-9-]+\.png\Z")
BASE_CLASS = "com.realityforge.scanner.uiflow.ActualUiJourneyTest"
SCENARIOS = {
    "create_rotate_reopen_cancel": {
        "test": BASE_CLASS + "#createRotateReopenAndCancelDestructiveActionThroughVisibleControls",
        "kind": "instrumentation", "synthetic": True,
    },
    "synthetic_import_reconstruct_export_reopen": {
        "test": BASE_CLASS + "#syntheticPhotographsImportReconstructInspectExportAndReopenThroughUi",
        "kind": "instrumentation", "synthetic": True,
    },
    "shared_provider_import_fault": {
        "test": BASE_CLASS + "#sharedFaultProviderImportUsesTheActualPickerAndPreservesOriginals",
        "kind": "instrumentation", "synthetic": True,
    },
    "permission_denial_regrant": {"kind": "controller", "owner": "088/014"},
    "late_camera_callback": {"kind": "controller", "owner": "088/014/001"},
    "changed_inputs_and_repeated_actions": {"kind": "controller", "owner": "088/018/001"},
    "background_lock_reconnect": {"kind": "controller", "owner": "088/008"},
    "process_kill_relaunch": {"kind": "controller", "owner": "088"},
    "picker_return_after_process_death": {"kind": "controller", "owner": "088/026/001"},
    "provider_short_write_recovery": {"kind": "controller", "owner": "088/026"},
    "physical_capture": {"kind": "physical", "synthetic": False},
}
DEFAULT_SCENARIOS = list(SCENARIOS)[:2]


class EvidenceError(Exception):
    pass


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def canonical_hash(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def aggregate(statuses: list[str]) -> str:
    for status in ("FAIL", "BLOCKED_EXTERNAL", "NOT_RUN"):
        if status in statuses:
            return status
    return "PASS" if statuses else "NOT_RUN"


def parse_instrumentation(stdout: str, returncode: int, expected_test: str | None = None) -> dict[str, Any]:
    """AndroidJUnitRunner's terminal code is -1 on success; adb's code is insufficient."""
    current: dict[str, str] = {}
    events = []
    declared_counts = set()
    terminal = []
    errors = []
    for line in stdout.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                current[key] = value
                if key == "numtests":
                    try:
                        declared_counts.add(int(value))
                    except ValueError:
                        errors.append("Invalid declared test count")
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                code = int(line.partition(":")[2].strip())
            except ValueError:
                errors.append("Invalid instrumentation status code")
                continue
            events.append({"code": code, "class": current.get("class"), "test": current.get("test")})
            current = {}
        elif line.startswith("INSTRUMENTATION_CODE:"):
            try:
                terminal.append(int(line.partition(":")[2].strip()))
            except ValueError:
                errors.append("Invalid terminal instrumentation code")
    completed = [event for event in events if event["code"] in (0, -1, -2, -3, -4)]
    passed = sum(event["code"] == 0 for event in completed)
    failed = sum(event["code"] in (-1, -2) for event in completed)
    skipped = sum(event["code"] in (-3, -4) for event in completed)
    identities = [str(event["class"]) + "#" + str(event["test"]) for event in completed]
    started_identities = [str(event["class"]) + "#" + str(event["test"]) for event in events if event["code"] == 1]
    if returncode != 0:
        errors.append(f"adb/instrument command exited {returncode}")
    if terminal != [-1]:
        errors.append("Missing or unsuccessful terminal instrumentation code")
    if not completed or not passed:
        errors.append("No successfully executed tests")
    if failed:
        errors.append("Test failures/errors reported")
    if skipped:
        errors.append("Skipped/assumption-failed tests do not qualify")
    if len(set(identities)) != len(identities):
        errors.append("Duplicate test completion records")
    if sorted(started_identities) != sorted(identities):
        errors.append("Started and completed test identities disagree")
    if len(declared_counts) != 1 or next(iter(declared_counts), -1) != len(completed):
        errors.append("Declared and completed test counts disagree")
    if any(event["code"] not in (1, 0, -1, -2, -3, -4) for event in events):
        errors.append("Unsupported instrumentation status code")
    if any(event["class"] is None or event["test"] is None for event in completed):
        errors.append("Completion lacks class/test identity")
    if expected_test and identities != [expected_test]:
        errors.append("Executed test identity differs from the selected scenario")
    if re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|FAILURES!!!|shortMsg=|Process crashed", stdout):
        errors.append("Instrumentation reported a runner/process failure")
    summary = re.findall(r"^OK \((\d+) tests?\)\s*$", stdout, re.MULTILINE)
    if summary != [str(passed)] or failed or skipped:
        errors.append("Missing or inconsistent successful JUnit summary")
    return {
        "status": "FAIL" if errors else "PASS", "passed": passed, "failed": failed,
        "skipped": skipped, "completed": len(completed), "test_identities": identities,
        "terminal_codes": terminal, "errors": errors,
    }


def validate_png(path: Path) -> dict[str, int]:
    data = path.read_bytes()
    if len(data) < 57 or len(data) > 32 * 1024 * 1024 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise EvidenceError("Screenshot is not a bounded complete PNG")
    at, width, height, saw_data, ended = 8, 0, 0, False, False
    while at < len(data):
        if len(data) - at < 12:
            raise EvidenceError("Truncated screenshot PNG chunk")
        length, = struct.unpack_from(">I", data, at)
        if length > len(data) - at - 12:
            raise EvidenceError("Screenshot PNG chunk exceeds file bounds")
        kind = data[at + 4:at + 8]
        payload = data[at + 8:at + 8 + length]
        crc, = struct.unpack_from(">I", data, at + 8 + length)
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != crc:
            raise EvidenceError("Screenshot PNG CRC mismatch")
        if at == 8:
            if kind != b"IHDR" or length != 13:
                raise EvidenceError("Screenshot PNG lacks first IHDR")
            width, height = struct.unpack_from(">II", payload)
            if not width or not height or width * height > 64 * 1024 * 1024:
                raise EvidenceError("Screenshot dimensions exceed pixel budget")
        elif kind == b"IHDR":
            raise EvidenceError("Duplicate screenshot PNG IHDR")
        if kind == b"IDAT":
            saw_data = True
        if kind == b"IEND":
            if length or not saw_data or at + 12 != len(data):
                raise EvidenceError("Invalid screenshot PNG end")
            ended = True
        at += length + 12
    if not ended:
        raise EvidenceError("Screenshot PNG is incomplete")
    return {"width": width, "height": height, "bytes": len(data)}


class Commands:
    def __init__(self, adb: str, serial: str, timeout: int):
        self.adb, self.serial, self.timeout = adb, serial, timeout
        self.records: list[dict[str, Any]] = []

    def run(self, args: list[str], timeout: int | None = None, input_text: str | None = None) -> subprocess.CompletedProcess[str]:
        started = time.monotonic()
        try:
            result = subprocess.run(args, capture_output=True, text=True, errors="replace", timeout=timeout or self.timeout, input=input_text)
        except subprocess.TimeoutExpired as exc:
            def decode(value):
                return value.decode("utf-8", "replace") if isinstance(value, bytes) else value or ""
            result = subprocess.CompletedProcess(args, 124, decode(exc.stdout), decode(exc.stderr) + "\nCommand timed out")
        except OSError as exc:
            result = subprocess.CompletedProcess(args, 127, "", str(exc))
        self.records.append({"argv": args, "returncode": result.returncode, "seconds": time.monotonic() - started})
        return result

    def adb_run(self, *args: str, timeout: int | None = None):
        return self.run([self.adb, "-s", self.serial, *args], timeout=timeout)

    def shell(self, *args: str, timeout: int | None = None):
        return self.adb_run("shell", shlex.join(args), timeout=timeout)


def source_binding(repo: Path, source_sha: str, apk: Path, test_apk: Path, receipt_path: Path) -> dict[str, str]:
    if not SOURCE_SHA.fullmatch(source_sha):
        raise EvidenceError("--source-sha must be a full lowercase Git commit")
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, capture_output=True, text=True, check=True).stdout.strip()
    if head != source_sha:
        raise EvidenceError("Requested candidate is stale: checkout HEAD differs")
    dirty = subprocess.run(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=repo, capture_output=True, text=True, check=True).stdout
    if dirty:
        raise EvidenceError("Candidate checkout has uncommitted files; freeze source before qualification")
    hashes = {"source_sha": source_sha, "apk_sha256": digest(apk), "test_apk_sha256": digest(test_apk)}
    receipt = json.loads(receipt_path.read_text())
    if not isinstance(receipt, dict) or any(receipt.get(key) != value for key, value in hashes.items()):
        raise EvidenceError("Build receipt source/APK/test-APK binding is missing or stale")
    return hashes


def identify_device(commands: Commands) -> dict[str, Any]:
    state = commands.adb_run("get-state")
    if state.returncode or state.stdout.strip() != "device":
        raise EvidenceError("Selected Android device is unavailable/offline/unauthorized")
    properties = {}
    for key in ("ro.build.fingerprint", "ro.build.version.sdk", "ro.product.cpu.abi", "ro.product.manufacturer", "ro.product.model", "ro.kernel.qemu", "ro.boot.qemu"):
        result = commands.shell("getprop", key)
        if result.returncode:
            raise EvidenceError(f"Cannot read device identity property {key}")
        properties[key] = result.stdout.strip()
    if not properties["ro.build.fingerprint"] or not properties["ro.product.cpu.abi"]:
        raise EvidenceError("Device fingerprint/ABI identity is unavailable")
    try:
        api = int(properties["ro.build.version.sdk"])
    except ValueError as exc:
        raise EvidenceError("Invalid device API identity") from exc
    if api < 29:
        raise EvidenceError("Selected device is below the app's API 29 minimum")
    emulator = any(properties[key] == "1" for key in ("ro.kernel.qemu", "ro.boot.qemu")) or bool(
        re.search(r"emulator|sdk_gphone|goldfish|ranchu|generic", properties["ro.build.fingerprint"] + " " + properties["ro.product.model"], re.I)
    )
    identity = {"serial": commands.serial, "properties": properties, "api": api, "emulator": emulator}
    identity["identity_sha256"] = canonical_hash(identity)
    return identity


def collect_screenshots(commands: Commands, directory: Path, run_id: str) -> list[dict[str, Any]]:
    listing = commands.shell("find", "/data/local/tmp", "-maxdepth", "1", "-type", "f", "-name", f"rf-ui-{run_id}-*.png")
    if listing.returncode:
        raise EvidenceError("Cannot enumerate current-run screenshots")
    paths = listing.stdout.splitlines()
    if len(paths) > 32 or len(paths) != len(set(paths)):
        raise EvidenceError("Screenshot count exceeds budget or paths repeat")
    result, total = [], 0
    directory.mkdir()
    for raw in sorted(paths):
        path = PurePosixPath(raw)
        match = PNG_NAME.fullmatch(path.name)
        if path.parent != PurePosixPath("/data/local/tmp") or not match or match[1] != run_id:
            raise EvidenceError("Screenshot path is unsafe or belongs to a stale run")
        size = commands.shell("stat", "-c", "%s", str(path))
        if size.returncode or not re.fullmatch(r"[0-9]+\s*", size.stdout):
            raise EvidenceError("Cannot establish screenshot byte count")
        count = int(size.stdout)
        if not 0 < count <= 32 * 1024 * 1024 or total + count > 64 * 1024 * 1024:
            raise EvidenceError("Screenshot byte budget exceeded")
        target = directory / path.name
        pull = commands.adb_run("pull", str(path), str(target))
        if pull.returncode or not target.is_file() or target.stat().st_size != count:
            raise EvidenceError("Screenshot pull failed or byte count changed")
        dimensions = validate_png(target)
        result.append({"path": str(target), "sha256": digest(target), **dimensions})
        total += count
    return result


def run_instrumented(commands: Commands, scenario: str, test: str, runner: str, directory: Path) -> dict[str, Any]:
    if not CLASS.fullmatch(test) or "#" not in test:
        raise EvidenceError("Scenario needs a specific safe test class#method")
    run_id = uuid.uuid4().hex
    directory.mkdir()
    result = commands.shell("am", "instrument", "-w", "-r", "-e", "class", test, "-e", "rfEvidenceRunId", run_id, runner)
    stdout = directory / "instrumentation.stdout.txt"
    stderr = directory / "instrumentation.stderr.txt"
    stdout.write_text(result.stdout)
    stderr.write_text(result.stderr)
    parsed = parse_instrumentation(result.stdout, result.returncode, test)
    evidence = {"scenario": scenario, "run_id": run_id, **parsed, "stdout": str(stdout), "stdout_sha256": digest(stdout), "stderr": str(stderr), "screenshots": []}
    try:
        evidence["screenshots"] = collect_screenshots(commands, directory / "screenshots", run_id)
        if not evidence["screenshots"]:
            raise EvidenceError("Successful UI qualification requires a genuine current-run screenshot")
    except EvidenceError as exc:
        evidence["status"] = "FAIL"
        evidence["errors"].append(str(exc))
    return evidence


def validate_hash_map(value: Any, label: str) -> dict[str, str]:
    if not isinstance(value, dict) or not value or len(value) > 250:
        raise EvidenceError(f"Controller {label} is missing or exceeds scope")
    if not all(isinstance(key, str) and len(key) <= 512 and isinstance(item, str) and SHA.fullmatch(item) for key, item in value.items()):
        raise EvidenceError(f"Controller {label} has invalid artifact identities")
    return value


def run_controller(commands: Commands, controller: Path, scenario: str, binding: dict, device: dict, directory: Path) -> dict[str, Any]:
    directory.mkdir()
    request = {
        "schema_version": 1, "scenario": scenario, "run_id": uuid.uuid4().hex,
        "candidate": binding, "device_identity_sha256": device["identity_sha256"],
        "adb": commands.adb, "serial": commands.serial,
        "required_evidence": ["original_hashes_before", "original_hashes_after", "previous_result_sha256_before", "previous_result_sha256_after", "instrumentation_stdout", "instrumentation_returncode", "test_identity", "dataset_identity_sha256", "synthetic"],
    }
    request["request_sha256"] = canonical_hash(request)
    (directory / "request.json").write_text(json.dumps(request, indent=2) + "\n")
    result = commands.run([str(controller)], input_text=json.dumps(request), timeout=commands.timeout)
    (directory / "controller.stdout.txt").write_text(result.stdout)
    (directory / "controller.stderr.txt").write_text(result.stderr)
    evidence = {"scenario": scenario, "status": "FAIL", "errors": [], "screenshots": []}
    try:
        response = json.loads(result.stdout)
        if result.returncode or not isinstance(response, dict):
            raise EvidenceError("External process controller failed")
        if response.get("schema_version") != 1 or response.get("scenario") != scenario or response.get("request_sha256") != request["request_sha256"]:
            raise EvidenceError("External controller response is stale or for another scenario")
        if response.get("candidate") != binding or response.get("device_identity_sha256") != device["identity_sha256"]:
            raise EvidenceError("External controller candidate/device identity mismatch")
        if response.get("status") in ("NOT_RUN", "BLOCKED_EXTERNAL"):
            if not isinstance(response.get("reason"), str) or not response["reason"].strip():
                raise EvidenceError("Controller did not explain its unavailable gate")
            return {**evidence, "status": response["status"], "reason": response["reason"]}
        if response.get("status") != "PASS":
            raise EvidenceError("External controller scenario failed")
        dataset = response.get("dataset_identity_sha256")
        if not isinstance(dataset, str) or not SHA.fullmatch(dataset) or response.get("synthetic") is not True:
            raise EvidenceError("Process scenario lacks an identified explicitly synthetic fixture")
        before = validate_hash_map(response.get("original_hashes_before"), "original hashes before interruption")
        after = validate_hash_map(response.get("original_hashes_after"), "original hashes after interruption")
        if before != after:
            raise EvidenceError("Original photograph identities changed across interruption")
        previous = response.get("previous_result_sha256_before")
        if not isinstance(previous, str) or not SHA.fullmatch(previous) or response.get("previous_result_sha256_after") != previous:
            raise EvidenceError("Previous completed result was not proven preserved")
        test = response.get("test_identity")
        if not isinstance(test, str) or not CLASS.fullmatch(test) or "#" not in test:
            raise EvidenceError("Controller lacks executed UI test identity")
        runner_output = response.get("instrumentation_stdout")
        runner_code = response.get("instrumentation_returncode")
        if not isinstance(runner_output, str) or len(runner_output) > 4 * 1024 * 1024 or type(runner_code) is not int:
            raise EvidenceError("Controller instrumentation evidence has invalid types or exceeds budget")
        parsed = parse_instrumentation(runner_output, runner_code, test)
        if parsed["status"] != "PASS":
            raise EvidenceError("Controller did not supply passing executed UI instrumentation: " + "; ".join(parsed["errors"]))
        screenshots = collect_screenshots(commands, directory / "screenshots", request["run_id"])
        if not screenshots:
            raise EvidenceError("Controller supplied no current-run UI screenshot")
        return {**evidence, "status": "PASS", "runner": parsed, "original_hashes": before, "previous_result_sha256": previous, "dataset_identity_sha256": dataset, "synthetic": True, "screenshots": screenshots}
    except (EvidenceError, ValueError, TypeError) as exc:
        evidence["errors"].append(str(exc))
        return evidence


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    result.add_argument("--source-sha", required=True)
    result.add_argument("--apk", type=Path, required=True)
    result.add_argument("--test-apk", type=Path, required=True)
    result.add_argument("--build-receipt", type=Path, required=True)
    result.add_argument("--output", type=Path, required=True, help="New, nonexistent evidence directory")
    result.add_argument("--adb", default="adb", help="Executable or session/container adb wrapper")
    result.add_argument("--serial", required=True)
    result.add_argument("--runner", default="com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner")
    result.add_argument("--scenario", action="append", choices=list(SCENARIOS))
    result.add_argument("--all-required", action="store_true")
    result.add_argument("--process-controller", type=Path)
    result.add_argument("--physical-class", help="Real UI capture class#method, not a ViewModel-only test")
    result.add_argument("--hardware-evidence-id", help="Operator's identified physical hardware/capture protocol receipt")
    result.add_argument("--timeout", type=int, default=600)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    if args.output.exists():
        print(json.dumps({"status": "FAIL", "errors": ["Evidence directory already exists; stale results cannot be reused"]}))
        return 1
    args.output.mkdir(parents=True)
    report: dict[str, Any] = {"schema_version": 1, "run_id": uuid.uuid4().hex, "status": "FAIL", "assignment_status": "NOT_RUN", "errors": [], "scenarios": [], "commands": []}
    commands = Commands(args.adb, args.serial, args.timeout)
    selected = list(SCENARIOS) if args.all_required else args.scenario or DEFAULT_SCENARIOS
    try:
        if not RUNNER.fullmatch(args.runner) or args.timeout < 1 or args.timeout > 3600 or len(selected) != len(set(selected)):
            raise EvidenceError("Invalid runner, timeout or duplicate scenario selection")
        report["candidate"] = source_binding(args.repo, args.source_sha, args.apk, args.test_apk, args.build_receipt)
        report["build_receipt_sha256"] = digest(args.build_receipt)
        try:
            report["device"] = identify_device(commands)
        except EvidenceError as exc:
            report["status"] = "BLOCKED_EXTERNAL"
            raise EvidenceError(str(exc)) from exc
        for apk in (args.apk, args.test_apk):
            installed = commands.adb_run("install", "-r", "-t", str(apk.resolve()), timeout=args.timeout)
            if installed.returncode or not re.search(r"^Success\s*$", installed.stdout, re.MULTILINE):
                raise EvidenceError("APK installation did not succeed: " + installed.stdout + installed.stderr)
        for scenario, spec in SCENARIOS.items():
            if scenario not in selected:
                report["scenarios"].append({"scenario": scenario, "status": "NOT_RUN", "reason": "Not selected; no acceptance claim"})
                continue
            directory = args.output / scenario
            if spec["kind"] == "instrumentation":
                evidence = run_instrumented(commands, scenario, spec["test"], args.runner, directory)
            elif spec["kind"] == "controller":
                if args.process_controller:
                    evidence = run_controller(commands, args.process_controller.resolve(), scenario, report["candidate"], report["device"], directory)
                else:
                    evidence = {"scenario": scenario, "status": "NOT_RUN", "reason": "Agent 088 shared external process/provider controller is not integrated", "owner": spec["owner"]}
            elif report["device"]["emulator"]:
                evidence = {"scenario": scenario, "status": "BLOCKED_EXTERNAL", "reason": "An emulator cannot qualify physical camera capture"}
            elif not args.physical_class or not args.hardware_evidence_id or not args.hardware_evidence_id.strip():
                evidence = {"scenario": scenario, "status": "BLOCKED_EXTERNAL", "reason": "Physical capture requires a real UI test and identified hardware protocol evidence"}
            else:
                if len(args.hardware_evidence_id) > 1024:
                    raise EvidenceError("Hardware protocol evidence identity exceeds budget")
                evidence = run_instrumented(commands, scenario, args.physical_class, args.runner, directory)
                evidence["hardware_evidence_id"] = args.hardware_evidence_id
                evidence["physical_device_operator_identified"] = True
            if "synthetic" in spec:
                evidence["synthetic"] = spec["synthetic"]
            report["scenarios"].append(evidence)
        report["status"] = aggregate([row["status"] for row in report["scenarios"] if row["scenario"] in selected])
        report["assignment_status"] = aggregate([row["status"] for row in report["scenarios"]])
    except (EvidenceError, OSError, ValueError, subprocess.CalledProcessError) as exc:
        report["errors"].append(str(exc))
        if report["status"] != "BLOCKED_EXTERNAL":
            report["status"] = "FAIL"
    completed_scenarios = {row["scenario"] for row in report["scenarios"]}
    for scenario in SCENARIOS:
        if scenario not in completed_scenarios:
            report["scenarios"].append({"scenario": scenario, "status": report["status"] if scenario in selected else "NOT_RUN", "reason": "Preflight/setup did not reach this scenario; see report errors"})
    report["assignment_status"] = aggregate([row["status"] for row in report["scenarios"]])
    report["selected_scenarios"] = selected
    report["commands"] = commands.records
    (args.output / "evidence.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return {"PASS": 0, "FAIL": 1, "BLOCKED_EXTERNAL": 2, "NOT_RUN": 3}[report["status"]]


if __name__ == "__main__":
    sys.exit(main())
