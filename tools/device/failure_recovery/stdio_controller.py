#!/usr/bin/env python3
"""Agent080's candidate-bound stdio adapter for actual, source-owned UI fixtures.

The baseline RecoveryStageContractTest is deliberately not used as a UI fixture.
An absent fixture returns NOT_RUN; an installed broken/stale fixture returns FAIL.
See docs/agents/contracts/088.md for the source-owner Android protocol.
"""
from __future__ import annotations

import hashlib
import json
import re
import shlex
import subprocess
import sys
import tempfile
import time
import uuid
from typing import Any

PACKAGE = "com.realityforge.scanner"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
PROTOCOL = "rf088-ui-controller-v1"
DESCRIBE = PACKAGE + ".uiflow.ControllerFixtureContractTest#describeFixture"
SCENARIOS = {
    "process_kill_relaunch": ("processKillRelaunch", "native_depth_running"),
    "picker_return_after_process_death": ("pickerReturnAfterProcessDeath", "picker_open"),
    "late_camera_callback": ("lateCameraCallback", "camera_callback_pending"),
}
OTHER_SCENARIOS = {
    "permission_denial_regrant": "088/014 must supply an outside-process camera permission action/restore contract before instrumentation; revoking inside instrumentation can kill its own process",
    "provider_short_write_recovery": "026/080 must supply the actual transfer/UI fault recovery fixture and agree its provider configuration/action contract with088",
    "changed_inputs_and_repeated_actions": "018/001/080 must supply actual UI changed-input/repeated-action barriers and assertions",
    "background_lock_reconnect": "008/080 must supply actual service/background/lock/reconnect action barriers and assertions",
}
REQUIRED = {
    "original_hashes_before", "original_hashes_after", "previous_result_sha256_before",
    "previous_result_sha256_after", "instrumentation_stdout", "instrumentation_returncode",
    "test_identity", "dataset_identity_sha256", "synthetic",
}
SHA = re.compile(r"[0-9a-f]{64}\Z")
SOURCE = re.compile(r"[0-9a-f]{40}\Z")
RUN_ID = re.compile(r"[0-9a-f]{32}\Z")
SERIAL = re.compile(r"[A-Za-z0-9_.:\[\]-]{1,256}\Z")
MAX_INPUT = 64 * 1024
MAX_OUTPUT = 4 * 1024 * 1024
TIMEOUT = 240
PROPERTIES = ("ro.build.fingerprint", "ro.build.version.sdk", "ro.product.cpu.abi",
              "ro.product.manufacturer", "ro.product.model", "ro.kernel.qemu", "ro.boot.qemu")


class EvidenceError(Exception):
    pass


class Unavailable(EvidenceError):
    def __init__(self, reason: str, status: str = "NOT_RUN"):
        super().__init__(reason)
        self.status = status


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def read_json(text: str) -> dict:
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise EvidenceError("Duplicate JSON field: " + key)
            result[key] = value
        return result
    try:
        value = json.loads(text, object_pairs_hook=pairs,
                           parse_constant=lambda value: (_ for _ in ()).throw(EvidenceError("Non-finite JSON number")))
    except (ValueError, TypeError) as error:
        raise EvidenceError("Malformed JSON") from error
    if not isinstance(value, dict):
        raise EvidenceError("JSON root must be an object")
    return value


def validate_request(request: dict) -> None:
    expected = {"schema_version", "scenario", "run_id", "candidate", "device_identity_sha256",
                "adb", "serial", "required_evidence", "request_sha256"}
    if set(request) != expected or type(request.get("schema_version")) is not int or request["schema_version"] != 1:
        raise EvidenceError("Unsupported request schema/fields")
    if request["scenario"] not in SCENARIOS.keys() | OTHER_SCENARIOS.keys():
        raise EvidenceError("Unknown controller scenario")
    if not isinstance(request["run_id"], str) or not RUN_ID.fullmatch(request["run_id"]):
        raise EvidenceError("Invalid unique run_id")
    candidate = request["candidate"]
    if (not isinstance(candidate, dict) or set(candidate) != {"source_sha", "apk_sha256", "test_apk_sha256"}
            or not isinstance(candidate["source_sha"], str) or not SOURCE.fullmatch(candidate["source_sha"])
            or any(not isinstance(candidate[key], str) or not SHA.fullmatch(candidate[key])
                   for key in ("apk_sha256", "test_apk_sha256"))):
        raise EvidenceError("Invalid source/APK/test-APK candidate identity")
    for key in ("device_identity_sha256", "request_sha256"):
        if not isinstance(request[key], str) or not SHA.fullmatch(request[key]):
            raise EvidenceError("Invalid " + key)
    if not isinstance(request["serial"], str) or not SERIAL.fullmatch(request["serial"]):
        raise EvidenceError("Invalid explicit adb serial")
    if (not isinstance(request["adb"], str) or not request["adb"] or len(request["adb"]) > 4096
            or any(ord(char) < 32 for char in request["adb"])):
        raise EvidenceError("Invalid adb executable/wrapper")
    evidence = request["required_evidence"]
    if (not isinstance(evidence, list) or not all(isinstance(item, str) for item in evidence)
            or len(evidence) != len(set(evidence)) or set(evidence) != REQUIRED):
        raise EvidenceError("Unsupported/missing required evidence fields")
    if digest({key: value for key, value in request.items() if key != "request_sha256"}) != request["request_sha256"]:
        raise EvidenceError("Request hash mismatch")


class Running:
    """An adb client with private bounded-output files, never inherited stdout."""
    def __init__(self, argv: list[str]):
        self.stdout = tempfile.TemporaryFile()
        self.stderr = tempfile.TemporaryFile()
        try:
            self.process = subprocess.Popen(argv, stdout=self.stdout, stderr=self.stderr)
        except OSError:
            self.stdout.close()
            self.stderr.close()
            raise

    def poll(self):
        if self.stdout.tell() > MAX_OUTPUT or self.stderr.tell() > MAX_OUTPUT:
            self.stop()
            raise EvidenceError("Instrumentation output exceeds budget")
        return self.process.poll()

    def finish(self, timeout: float = 20):
        try:
            self.process.wait(timeout=timeout)
        except subprocess.TimeoutExpired as error:
            self.stop()
            raise EvidenceError("Instrumentation client did not terminate") from error
        self.poll()
        self.stdout.seek(0)
        self.stderr.seek(0)
        return subprocess.CompletedProcess(self.process.args, self.process.returncode,
                                           self.stdout.read(MAX_OUTPUT + 1).decode("utf-8", "replace"),
                                           self.stderr.read(MAX_OUTPUT + 1).decode("utf-8", "replace"))

    def stop(self):
        if self.process.poll() is None:
            self.process.terminate()  # adb client only; no unverified device PID.
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)

    def close(self):
        try:
            self.stop()
        finally:
            self.stdout.close()
            self.stderr.close()


class Adb:
    def __init__(self, executable: str, serial: str):
        self.prefix = [executable, "-s", serial]
        self.serial = serial
        self.commands: list[dict] = []

    def run(self, *args: str, input_text: str | None = None, timeout: int = 30):
        started = time.time()
        try:
            result = subprocess.run(self.prefix + list(args), input=input_text, text=True,
                                    capture_output=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise Unavailable("Selected adb/device command is unavailable: " + str(error), "BLOCKED_EXTERNAL") from error
        self.commands.append({"argv": self.prefix + list(args), "started_at_unix_ms": int(started * 1000),
                              "finished_at_unix_ms": int(time.time() * 1000), "returncode": result.returncode})
        if len(result.stdout.encode()) > MAX_OUTPUT or len(result.stderr.encode()) > MAX_OUTPUT:
            raise EvidenceError("adb output exceeds budget")
        return result

    def shell(self, *args: str, **kwargs):
        return self.run("shell", "-T", shlex.join(args), **kwargs)

    def start(self, args: list[str]):
        argv = self.prefix + ["shell", "-T", shlex.join(args)]
        self.commands.append({"argv": argv, "started_at_unix_ms": int(time.time() * 1000), "asynchronous": True})
        return Running(argv)


def checked(adb, *args: str, **kwargs) -> str:
    result = adb.shell(*args, **kwargs)
    if result.returncode:
        raise EvidenceError("Device command failed: " + shlex.join(args) + ": " + result.stderr[-2000:])
    return result.stdout.strip()


def identify_device(adb) -> dict:
    state = adb.run("get-state")
    if state.returncode or state.stdout.strip() != "device":
        raise Unavailable("Selected Android device is unavailable/offline/unauthorized", "BLOCKED_EXTERNAL")
    properties = {key: checked(adb, "getprop", key) for key in PROPERTIES}
    if not properties[PROPERTIES[0]] or not properties[PROPERTIES[2]]:
        raise EvidenceError("Device fingerprint/ABI unavailable")
    try:
        api = int(properties["ro.build.version.sdk"])
    except ValueError as error:
        raise EvidenceError("Invalid device API") from error
    if api < 29:
        raise Unavailable("Selected Android device is below app API29 minimum", "BLOCKED_EXTERNAL")
    emulator = any(properties[key] == "1" for key in ("ro.kernel.qemu", "ro.boot.qemu")) or bool(
        re.search(r"emulator|sdk_gphone|goldfish|ranchu|generic", properties["ro.build.fingerprint"] + " " + properties["ro.product.model"], re.I))
    identity = {"serial": adb.serial, "properties": properties, "api": api, "emulator": emulator}
    identity["identity_sha256"] = digest(identity)  # Same identity contract as pinned080.
    return identity


def verify_installed(adb, candidate: dict) -> None:
    for package, key in ((PACKAGE, "apk_sha256"), (PACKAGE + ".test", "test_apk_sha256")):
        paths = checked(adb, "pm", "path", package).splitlines()
        if len(paths) != 1 or not re.fullmatch(r"package:/data/app/[A-Za-z0-9_./+=~-]+\.apk", paths[0]):
            raise EvidenceError("A single installed base APK is required for " + package)
        value = checked(adb, "sha256sum", paths[0][8:]).split()
        if not value or value[0] != candidate[key]:
            raise EvidenceError("Installed candidate APK hash mismatch for " + package)


def bindings(request: dict, nonce: str) -> dict:
    return {key: request[key] for key in ("schema_version", "scenario", "run_id", "candidate",
                                         "device_identity_sha256", "request_sha256")} | {
        "protocol": PROTOCOL, "controller_nonce": nonce, "target_package": PACKAGE}


def validate_bound(value: dict, request: dict, nonce: str) -> None:
    if (not isinstance(value, dict) or type(value.get("schema_version")) is not int
            or any(value.get(key) != item for key, item in bindings(request, nonce).items())):
        raise EvidenceError("Stale/mismatched fixture request, nonce, candidate, device or scenario binding")


def selector(scenario: str) -> str:
    return PACKAGE + ".uiflow.ControlledUiJourneyTest#" + SCENARIOS[scenario][0]


def instrument(request: dict, nonce: str, phase: str, test: str) -> list[str]:
    return ["am", "instrument", "-w", "-r", "-e", "class", test,
            "-e", "rfEvidenceRunId", request["run_id"], "-e", "rf088ControllerNonce", nonce,
            "-e", "rf088ControllerPhase", phase, "-e", "rf088ControllerRequest", canonical(request), RUNNER]


def parse_instrumentation(stdout: str, returncode: int, expected: str,
                          extra_result_keys: tuple[str, ...] = ()) -> dict:
    """Require the complete raw AndroidJUnitRunner protocol for one selected test.

    Only stream fields are multiline. Unknown protocol lines, error result keys,
    orphan text and anything following completion fail instead of being ignored.
    """
    if returncode or not isinstance(stdout, str) or len(stdout.encode()) > MAX_OUTPUT:
        raise EvidenceError("UI instrumentation failed or exceeded output budget")
    identity = expected.split("#")
    if len(identity) != 2 or not all(identity):
        raise EvidenceError("One exact class#method selector is required")
    class_name, method = identity
    fields, events, results = {}, [], {}
    active_stream = None
    result_phase = False
    complete = False
    status_keys = {"class", "test", "current", "numtests", "id", "stream"}
    for line in stdout.splitlines():
        if complete:
            if line:
                raise EvidenceError("Trailing output after UI instrumentation completion")
            continue
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if result_phase or len(events) >= 2:
                raise EvidenceError("UI status after terminal event/results")
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if (not sep or key in fields
                    or (key not in status_keys and not re.fullmatch(r"rf088[A-Za-z0-9_]*", key))):
                raise EvidenceError("Malformed/unknown/duplicate UI instrumentation status field")
            fields[key] = value
            active_stream = fields if key == "stream" else None
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code_text = line[len("INSTRUMENTATION_STATUS_CODE:"):]
            if result_phase or len(events) >= 2 or code_text != (" 1" if not events else " 0"):
                raise EvidenceError("UI instrumentation requires one start and one success; no skips/failures")
            required = {"class": class_name, "test": method, "current": "1", "numtests": "1",
                        "id": "AndroidJUnitRunner", "stream": "\n" + class_name + ":" if not events else "."}
            if any(fields.get(key) != value for key, value in required.items()):
                raise EvidenceError("UI test identity/count/runner/stream mismatch")
            events.append(fields)
            fields = {}
            active_stream = None
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            if fields or len(events) != 2:
                raise EvidenceError("UI results before complete successful test events")
            result_phase = True
            key, sep, value = line[len("INSTRUMENTATION_RESULT: "):].partition("=")
            if (not sep or key in results or (key != "stream" and key not in extra_result_keys
                    and not re.fullmatch(r"rf088[A-Za-z0-9_]*", key))):
                raise EvidenceError("Malformed/unknown/duplicate UI instrumentation result")
            results[key] = value if key == "stream" else read_json(value)
            active_stream = results if key == "stream" else None
        elif line.startswith("INSTRUMENTATION_CODE:"):
            if fields or len(events) != 2 or not result_phase or line != "INSTRUMENTATION_CODE: -1":
                raise EvidenceError("UI instrumentation has no exact successful completion")
            complete = True
            active_stream = None
        elif line.startswith("INSTRUMENTATION_"):
            raise EvidenceError("Unknown UI instrumentation protocol line")
        elif active_stream is not None:
            active_stream["stream"] += "\n" + line
        elif line:
            raise EvidenceError("Orphan text outside UI instrumentation stream")
    if (not complete or fields or len(events) != 2 or "stream" not in results
            or not re.fullmatch(r"\n+Time: [0-9]+(?:\.[0-9]+)?\n+OK \(1 tests?\)\n*", results["stream"])):
        raise EvidenceError("UI instrumentation requires the exact JUnit Time/OK result stream and final code")
    return {"test_identity": expected, "started_count": 1, "completed_count": 1,
            "passed_count": 1, "skipped_count": 0,
            "results": {key: value for key, value in results.items() if key != "stream"}}


def fixture_is_missing(stdout: str) -> bool:
    """Only explicit missing reserved class, never a failed installed descriptor."""
    class_name, method = DESCRIBE.split("#")
    fields = {}
    for line in stdout.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            if fields.get("class") == class_name and fields.get("test") == method:
                return False  # Any event from the actual descriptor is an installed gate.
            fields = {}
    missing = (r"^(?:INSTRUMENTATION_STATUS: stack=)?\s*(?:Caused by:\s*)?"
               r"(?:java\.lang\.)?ClassNotFoundException:\s*(?:Didn't find class \")?"
               + re.escape(class_name) + r"(?:[\"\s]|$)")
    return bool(re.search(missing, stdout, re.M))


def hash_map(value: Any) -> dict:
    if (not isinstance(value, dict) or not 1 <= len(value) <= 250
            or any(not isinstance(key, str) or not key or len(key) > 512
                   or any(ord(char) < 32 for char in key)
                   or not isinstance(item, str) or not SHA.fullmatch(item) for key, item in value.items())):
        raise EvidenceError("Missing/invalid original hash map")
    return value


def validate_marker(value: dict, request: dict, nonce: str, state: str = "READY") -> dict:
    validate_bound(value, request, nonce)
    if (value.get("state") != state or value.get("barrier") != SCENARIOS[request["scenario"]][1]
            or value.get("test_identity") != selector(request["scenario"]) or value.get("action") != "external_kill"
            or type(value.get("prepare_pid")) is not int or value["prepare_pid"] <= 1
            or type(value.get("revision")) is not int or value["revision"] < 0
            or not isinstance(value.get("project_id"), str) or not 1 <= len(value["project_id"]) <= 256
            or any(ord(char) < 32 for char in value["project_id"])
            or value.get("synthetic") is not True or type(value.get("event_sequence")) is not int
            or value.get("event_sequence") != (1 if state == "READY" else 2)):
        raise EvidenceError("Invalid/stale actual UI readiness state, barrier, selector, action or PID")
    hash_map(value.get("original_hashes_before"))
    for key in ("previous_result_sha256_before", "dataset_identity_sha256"):
        if not isinstance(value.get(key), str) or not SHA.fullmatch(value[key]):
            raise EvidenceError("Missing/invalid fixture " + key)
    artifact = value.get("expected_artifact_key")
    if not isinstance(artifact, str) or not 1 <= len(artifact) <= 512 or any(ord(char) < 32 for char in artifact):
        raise EvidenceError("Missing/invalid expected artifact key")
    return value


def assert_live_pid(adb, pid: int) -> None:
    pids = checked(adb, "pidof", PACKAGE).split()
    if str(pid) not in pids:
        raise EvidenceError("Readiness PID is stale; refuse device kill")


def marker_path(request: dict) -> str:
    return "files/qa-ui-controller/" + request["run_id"] + "/marker.json"


def wait_marker(adb, running, request: dict, nonce: str, state: str, *, now=time.monotonic, pause=time.sleep) -> dict:
    deadline = now() + TIMEOUT
    while now() < deadline:
        if running.poll() is not None:
            raise EvidenceError("UI preparation exited before " + state + " barrier")
        result = adb.shell("run-as", PACKAGE, "cat", marker_path(request))
        if result.returncode:
            pause(.1)
            continue
        marker = read_json(result.stdout)
        if state == "ACKNOWLEDGED" and marker.get("state") == "READY":
            validate_marker(marker, request, nonce)
            pause(.1)
            continue
        return validate_marker(marker, request, nonce, state)
    raise EvidenceError("No fresh actual UI " + state + " marker before timeout")


def validate_receipt(receipt: dict, marker: dict, request: dict, nonce: str) -> dict:
    validate_bound(receipt, request, nonce)
    if (receipt.get("status") != "PASS" or receipt.get("test_identity") != selector(request["scenario"])
            or receipt.get("project_id") != marker["project_id"] or receipt.get("revision") != marker["revision"]
            or receipt.get("prepare_pid") != marker["prepare_pid"] or type(receipt.get("verify_pid")) is not int
            or receipt["verify_pid"] <= 1 or receipt["verify_pid"] == marker["prepare_pid"]
            or receipt.get("synthetic") is not True or receipt.get("dataset_identity_sha256") != marker["dataset_identity_sha256"]):
        raise EvidenceError("Actual UI receipt did not prove matching separate-process recovery")
    before = hash_map(receipt.get("original_hashes_before"))
    after = hash_map(receipt.get("original_hashes_after"))
    if before != marker["original_hashes_before"] or after != before:
        raise EvidenceError("Original photograph hashes changed across interruption")
    previous = marker["previous_result_sha256_before"]
    if receipt.get("previous_result_sha256_before") != previous or receipt.get("previous_result_sha256_after") != previous:
        raise EvidenceError("Previous completed result was not preserved")
    expected = marker["expected_artifact_key"]
    if receipt.get("expected_artifact_key") != expected or receipt.get("observed_artifact_key") != expected:
        raise EvidenceError("Expected/observed artifact key mismatch")
    if receipt.get("no_duplicate_publication") is not True or receipt.get("checkpoint_identity_verified") is not True:
        raise EvidenceError("UI fixture omitted checkpoint/publication safety assertions")
    return receipt


def require_screenshot(adb, request: dict) -> list[str]:
    prefix = "/data/local/tmp/rf-ui-" + request["run_id"] + "-"
    listing = checked(adb, "find", "/data/local/tmp", "-maxdepth", "1", "-type", "f", "-name", "rf-ui-" + request["run_id"] + "-*.png")
    paths = listing.splitlines()
    if (not 1 <= len(paths) <= 32 or len(set(paths)) != len(paths)
            or any(not re.fullmatch(re.escape(prefix) + r"[a-z0-9-]+\.png", path) for path in paths)):
        raise EvidenceError("No valid current-run UI screenshot;080 independently pulls/validates each PNG")
    return paths


def run_request(request: dict, *, adb_factory=Adb, nonce_factory=lambda: uuid.uuid4().hex,
                now=time.monotonic, pause=time.sleep) -> dict:
    response = {key: request.get(key) for key in ("schema_version", "scenario", "candidate", "device_identity_sha256", "request_sha256")}
    response.update({"status": "FAIL", "events": [], "release_qualification": "NOT_RUN"})
    adb = None
    running = None
    def event(name):
        response["events"].append({"event": name, "host_unix_ms": int(time.time() * 1000)})
    try:
        validate_request(request)
        nonce = nonce_factory()
        if not RUN_ID.fullmatch(nonce):
            raise EvidenceError("Invalid controller nonce")
        response["controller_nonce"] = nonce
        adb = adb_factory(request["adb"], request["serial"])
        device = identify_device(adb)
        response["device"] = device
        if device["identity_sha256"] != request["device_identity_sha256"]:
            raise EvidenceError("Selected device identity hash mismatch")
        verify_installed(adb, request["candidate"])
        event("installed_candidate_and_device_verified")
        scenario = request["scenario"]
        if scenario in OTHER_SCENARIOS:
            raise Unavailable(OTHER_SCENARIOS[scenario] + "; no acceptance claim")
        probe = adb.shell(*instrument(request, nonce, "describe", DESCRIBE), timeout=60)
        response["fixture_probe_stdout"] = probe.stdout
        # Only an explicit missing reserved class/method is unavailable. A skipped,
        # malformed or failing installed descriptor is a failed gate.
        if fixture_is_missing(probe.stdout):
            raise Unavailable("080 actual UI fixture descriptor " + DESCRIBE + " is not installed in the bound test APK;080/001/014/026 must supply actual UI barriers;088baseline recovery is not substituted")
        descriptor = parse_instrumentation(probe.stdout, probe.returncode, DESCRIBE)["results"].get("rf088Fixture")
        validate_bound(descriptor, request, nonce)
        if descriptor.get("supported_scenarios") != [scenario]:
            raise Unavailable("Bound actual UI fixture does not advertise this exact scenario")
        if descriptor.get("test_identity") != selector(scenario) or descriptor.get("barrier") != SCENARIOS[scenario][1]:
            raise EvidenceError("Descriptor selector/barrier mismatch")
        # Refuse stale paths before launching any process-controlled scenario.
        exists = adb.shell("run-as", PACKAGE, "test", "-e", "files/qa-ui-controller/" + request["run_id"])
        if exists.returncode == 0:
            raise EvidenceError("Run directory already exists; stale marker/ACK must not be reused")
        if exists.returncode != 1:
            raise Unavailable("Candidate lacks authorized debuggable run-as access", "BLOCKED_EXTERNAL")
        running = adb.start(instrument(request, nonce, "prepare", selector(scenario)))
        marker = wait_marker(adb, running, request, nonce, "READY", now=now, pause=pause)
        response["ready_marker"] = marker
        assert_live_pid(adb, marker["prepare_pid"])
        event("actual_ui_ready_pid_verified")
        ack = bindings(request, nonce) | {"state": "ACK", "barrier": marker["barrier"], "prepare_pid": marker["prepare_pid"]}
        ack["ack_sha256"] = digest(ack)
        ack_path = "files/qa-ui-controller/" + request["run_id"] + "/ack.json"
        # noclobber makes stale/duplicate acknowledgements fatal; request run_id is
        # validated hex and the only variable path component in this shell script.
        checked(adb, "run-as", PACKAGE, "sh", "-c", "set -C; cat > " + ack_path, input_text=canonical(ack))
        acknowledged = wait_marker(adb, running, request, nonce, "ACKNOWLEDGED", now=now, pause=pause)
        if acknowledged != marker | {"state": "ACKNOWLEDGED", "event_sequence": 2, "ack_sha256": ack["ack_sha256"]}:
            raise EvidenceError("ACK marker changed immutable fixture/PID/original state")
        assert_live_pid(adb, marker["prepare_pid"])
        event("fresh_ack_and_live_target_reverified")
        checked(adb, "run-as", PACKAGE, "kill", "-9", str(marker["prepare_pid"]))
        preparation = running.finish()
        response["preparation_stdout"] = preparation.stdout
        response["preparation_returncode"] = preparation.returncode
        remaining = adb.shell("pidof", PACKAGE)
        if remaining.returncode not in (0, 1) or str(marker["prepare_pid"]) in remaining.stdout.split():
            raise EvidenceError("Target process did not disappear after external kill")
        event("target_process_death_observed")
        verify = adb.shell(*instrument(request, nonce, "verify", selector(scenario)), timeout=600)
        response["instrumentation_stdout"] = verify.stdout
        response["instrumentation_returncode"] = verify.returncode
        parsed = parse_instrumentation(verify.stdout, verify.returncode, selector(scenario))
        receipt = validate_receipt(parsed["results"].get("rf088Receipt"), marker, request, nonce)
        response["screenshots_on_device"] = require_screenshot(adb, request)
        # Recheck identities after recovery, closing a mid-run device/APK switch.
        if identify_device(adb)["identity_sha256"] != request["device_identity_sha256"]:
            raise EvidenceError("Device identity changed during scenario")
        verify_installed(adb, request["candidate"])
        event("verified_actual_ui_recovery_and_candidate_rechecked")
        # Fixture metadata cannot replace the host-validated transcript, counts,
        # command log, status, screenshots or qualification scope.
        for key in ("original_hashes_before", "original_hashes_after", "previous_result_sha256_before",
                    "previous_result_sha256_after", "dataset_identity_sha256", "synthetic", "test_identity",
                    "project_id", "revision", "prepare_pid", "verify_pid", "expected_artifact_key",
                    "observed_artifact_key", "no_duplicate_publication", "checkpoint_identity_verified"):
            response[key] = receipt[key]
        response.update({"status": "PASS", "test_counts": {key: value for key, value in parsed.items() if key.endswith("_count")}})
    except Unavailable as error:
        response.update({"status": error.status, "reason": str(error)})
    except (EvidenceError, OSError, ValueError, TypeError, KeyError) as error:
        response.update({"status": "FAIL", "reason": str(error)})
    finally:
        if running is not None:
            try:
                running.close()
            except Exception as error:
                # Preserve a single machine-readable verdict even when local adb
                # cleanup fails. Cleanup never upgrades incomplete evidence.
                response.update({"status": "FAIL", "reason": "Selected adb client cleanup failed: " + str(error)})
        if adb is not None:
            response["commands"] = adb.commands
    return response


def main(stdin=None, stdout=None, stderr=None) -> int:
    stdin, stdout, stderr = stdin or sys.stdin, stdout or sys.stdout, stderr or sys.stderr
    try:
        raw = stdin.read(MAX_INPUT + 1)
        if len(raw.encode()) > MAX_INPUT:
            raise EvidenceError("Controller request exceeds input budget")
        response = run_request(read_json(raw))
    except (EvidenceError, OSError, ValueError, TypeError) as error:
        response = {"schema_version": 1, "status": "FAIL", "reason": str(error), "release_qualification": "NOT_RUN"}
    if response["status"] != "PASS":
        print(response["status"] + ": " + response.get("reason", "Controller gate failed"), file=stderr)
    print(json.dumps(response, allow_nan=False), file=stdout)
    #080 independently interprets response.status; nonzero would hide the concrete
    #NOT_RUN/BLOCKED_EXTERNAL response behind its generic executable-failure gate.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
