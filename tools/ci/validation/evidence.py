#!/usr/bin/env python3
"""Seal current-run test/artifact evidence and fail closed on candidate validation."""
from __future__ import annotations
import argparse
import glob
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SCHEMA = "realityforge.ci-evidence.v1"
GATES = {"native": "host-synthetic-and-real-stereo", "android": "host-jvm", "device": "emulator-synthetic-session"}
SUITE_PATH = "tools/device/compatibility/suites.json"
DEBUG_SPECIALIST_GATES = {"physical_capture", "external_recovery", "physical_resource_profile", "offline_radios_journey", "minified_release"}
NATIVE_REPORTS = {"numerical.json", "benchmark_multiview_coverage.json", "benchmark_middlebury.json", "benchmark_dense_middlebury.json", "benchmark_surface_middlebury.json", "benchmark_texture_middlebury.json"}
NATIVE_CONFIGS = {"gcc-release", "clang-release", "gcc-sanitize", "clang-sanitize"}
COMMANDS = {"configure", "build", "ctest", "numerical", "pytest", "bundle-three-view", "benchmark_multiview_coverage", "benchmark_middlebury", "benchmark_dense_middlebury", "benchmark_surface_middlebury", "benchmark_texture_middlebury"}


class EvidenceError(ValueError):
    pass


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source(root, expected):
    actual = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", expected) or actual != expected:
        raise EvidenceError("Candidate source SHA does not equal checked-out HEAD")
    subprocess.run(["git", "-C", str(root), "diff", "--quiet", "HEAD", "--"], check=True)
    return actual


def load(path):
    if Path(path).stat().st_size > 32 * 1024 * 1024:
        raise EvidenceError("Evidence JSON exceeds size budget")
    return json.loads(Path(path).read_text())


def junit(path):
    if Path(path).stat().st_size > 64 * 1024 * 1024:
        raise EvidenceError("JUnit exceeds size budget")
    root = ET.parse(path).getroot()
    if root.tag not in ("testsuite", "testsuites"):
        raise EvidenceError("Not JUnit XML")
    cases = list(root.iter("testcase"))
    failed = sum(case.find("failure") is not None or case.find("error") is not None for case in cases)
    skipped = sum(case.find("skipped") is not None for case in cases)
    # Declared failures must never be hidden by an omitted testcase element.
    declared_failed = sum(int(suite.attrib.get("failures", "0")) + int(suite.attrib.get("errors", "0")) for suite in root.iter("testsuite"))
    declared_tests = sum(int(suite.attrib.get("tests", "0")) for suite in root.iter("testsuite") if not suite.findall("testsuite"))
    if not cases or failed or declared_failed or skipped == len(cases) or declared_tests != len(cases):
        raise EvidenceError(f"Incomplete/failed JUnit: {path}")
    return {"tests": len(cases), "passed": len(cases) - skipped, "skipped": skipped, "failed": failed}


def fresh(path, started):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
        raise EvidenceError(f"Missing/empty/symlink evidence: {path}")
    if path.stat().st_mtime_ns < started:
        raise EvidenceError(f"Stale evidence predates this run: {path}")
    return path


def expanded(patterns):
    result = []
    for pattern in patterns:
        matches = sorted(glob.glob(pattern, recursive=True))
        if not matches:
            raise EvidenceError(f"Missing expected report/artifact: {pattern}")
        result.extend(Path(item) for item in matches)
    return list(dict.fromkeys(result))


def begin(root, gate, expected, run_id, output):
    source(root, expected)
    if not run_id.strip() or output.exists():
        raise EvidenceError("Run ID required and start record must be new")
    output.parent.mkdir(parents=True, exist_ok=True)
    data = dict(schema=SCHEMA, gate=gate, source_sha=expected, run_id=run_id,
                started_ns=time.time_ns(), root=str(root.resolve()), kind=GATES[gate])
    output.write_text(json.dumps(data, indent=2) + "\n")
    return data


def native_record(path, expected, mapped=None):
    def resolve(value):
        if mapped is not None and str(value) not in mapped:
            raise EvidenceError("Native referenced artifact missing from sealed bundle")
        return mapped[str(value)] if mapped is not None else Path(value)
    data = load(path)
    if data.get("schema") != "realityforge.native-regression.v1" or data.get("source_sha") != expected or data.get("dirty_tree") is not False or data.get("result") != "PASS":
        raise EvidenceError("Native runner source/status mismatch")
    configs = data.get("configurations", [])
    if len(configs) != 4 or {item.get("name") for item in configs} != NATIVE_CONFIGS:
        raise EvidenceError("All four native compiler/sanitizer configurations are required")
    files, xml = [Path(path)], []
    for config in configs:
        if config.get("result") != "PASS":
            raise EvidenceError("Native configuration failed")
        commands = config.get("commands", [])
        labels = {Path(item.get("log", "")).stem for item in commands}
        if len(commands) != len(COMMANDS) or labels != COMMANDS:
            raise EvidenceError("Native command evidence is incomplete")
        for item in commands:
            log = Path(item["log"])
            if item.get("result") != "PASS" or item.get("exit_code") != 0 or item.get("reason") is not None or sha(resolve(log)) != item.get("log_sha256"):
                raise EvidenceError("Native command/log failed integrity validation")
            if log.stem == "ctest":
                match = re.search(r"100% tests passed, 0 tests failed out of (\d+)", resolve(log).read_text())
                if not match or int(match[1]) < 1:
                    raise EvidenceError("Native CTest completion evidence missing")
            if log.stem == "pytest":
                xml.append(resolve(log.with_name("pytest.xml")))
            files.append(resolve(log))
        for item in config.get("binaries", []) + config.get("artifacts", []):
            artifact = resolve(item["path"])
            if sha(artifact) != item.get("sha256"):
                raise EvidenceError("Native artifact hash mismatch")
            files.append(artifact)
        if not config.get("binaries") or {Path(item["path"]).name for item in config.get("artifacts", [])} != NATIVE_REPORTS:
            raise EvidenceError("Native binaries/numerical/benchmark reports missing")
    comparisons = data.get("comparisons", [])
    if len(comparisons) != 3 or any(item.get("result") != "PASS" for item in comparisons) or {item.get("config") for item in comparisons} != NATIVE_CONFIGS - {configs[0]["name"]}:
        raise EvidenceError("Native cross-compiler parity evidence incomplete")
    return files, xml


def instrumentation_counts(text, outcomes=None):
    """Independently verify AndroidJUnitRunner raw completion and skip events."""
    summaries = re.findall(r"^OK \((\d+) tests?\)\s*$", text, re.MULTILINE)
    completed = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", text, re.MULTILINE)
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if len(summaries) != 1 or completed != ["-1"] or not lines or lines[-1] != "INSTRUMENTATION_CODE: -1" or any(value in text for value in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "INSTRUMENTATION_ABORTED")):
        raise EvidenceError("Device instrumentation failed/incomplete")
    fields, starts, ends, totals = {}, set(), {}, set()
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator and key in ("class", "test", "current", "numtests"):
                if key in fields:
                    raise EvidenceError("Duplicate instrumentation event field")
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                code = int(line.split(": ", 1)[1])
                current, total = int(fields["current"]), int(fields["numtests"])
                key = (fields["class"], fields["test"], current)
            except (KeyError, ValueError) as error:
                raise EvidenceError("Incomplete instrumentation event identity") from error
            if not key[0] or not key[1] or not 1 <= current <= total:
                raise EvidenceError("Invalid instrumentation identity/count")
            totals.add(total)
            if code == 1:
                if key in starts:
                    raise EvidenceError("Duplicate instrumentation start")
                starts.add(key)
            elif code in (0, -1, -2, -3, -4):
                if key not in starts or key in ends:
                    raise EvidenceError("Missing instrumentation start or duplicate completion")
                ends[key] = code
            else:
                raise EvidenceError("Unknown instrumentation status")
            fields = {}
    if fields or not ends or starts != set(ends) or totals != {len(ends)} or {key[2] for key in ends} != set(range(1, len(ends) + 1)):
        raise EvidenceError("Incomplete instrumentation test discovery/completion")
    result = dict(tests=len(ends), passed=sum(code == 0 for code in ends.values()),
                  skipped=sum(code in (-3, -4) for code in ends.values()),
                  failed=sum(code in (-1, -2) for code in ends.values()))
    # JUnit's summary excludes @Ignore (-3), but includes assumptions (-4).
    if result["failed"] or result["passed"] < 1 or int(summaries[0]) != result["tests"] - sum(code == -3 for code in ends.values()):
        raise EvidenceError("Instrumentation summary/pass/skip counts disagree")
    if outcomes is not None:
        for key, code in ends.items():
            outcomes.setdefault(f"{key[0]}#{key[1]}", []).append(code)
    return result


def device_record(record, transcript, apks, suite_manifest):
    data = load(record)
    text = Path(transcript).read_text()
    outcomes = {}
    counts = instrumentation_counts(text, outcomes)
    if data.get("schema") != 1 or data.get("status") != "PASS" or data.get("counts") != counts:
        raise EvidenceError("Device receipt differs from actual instrumentation outcomes")
    device = data.get("device", {})
    if device.get("emulator") is not True or device.get("serial") != "emulator-5554" or not device.get("fingerprint") or device.get("api") != 35 or device.get("pageSize") not in (4096, 16384) or device.get("abi") != "x86_64":
        raise EvidenceError("Expected actual x86_64 emulator identity")
    if data.get("tests") != counts["tests"] or {data.get("appSha256"), data.get("testSha256")} != {sha(path) for path in apks} or len(apks) != 2:
        raise EvidenceError("Device test count/candidate APK identity mismatch")
    manifest = load(suite_manifest)
    if manifest.get("schema") != "realityforge.android-suite-scopes.v1":
        raise EvidenceError("Unknown Android suite manifest schema")
    scope = manifest.get("scopes", {}).get("debug", {})
    excluded = scope.get("excludedGates", [])
    required_selectors = scope.get("requiredPassedSelectors", [])
    gates = [row.get("gate") for row in excluded]
    valid_rows = all(set(row) == {"gate", "selector", "reason"} and all(isinstance(row[key], str) and row[key].strip() for key in row) for row in excluded)
    if not valid_rows or len(set(gates)) != len(gates) or not DEBUG_SPECIALIST_GATES <= set(gates) or not required_selectors:
        raise EvidenceError("Debug scope must retain baseline specialists and unique source-declared exclusions")
    expected_suite = dict(schema=1, scope="debug", manifestSha256=sha(suite_manifest),
                          notClass=[row["selector"] for row in excluded],
                          requiredPassedSelectors=required_selectors,
                          excludedGates=[{**row, "status": "NOT_RUN"} for row in excluded],
                          fullQualification="NOT_RUN")
    if data.get("suite") != expected_suite:
        raise EvidenceError("Compatibility scope/excluded required gates differ from source manifest")
    if any(not outcomes.get(selector) or any(code != 0 for code in outcomes[selector]) for selector in required_selectors):
        raise EvidenceError("Required compatibility/session selectors did not actually pass")
    for row in excluded:
        selector = row["selector"]
        if any(name == selector or ("#" not in selector and name.split("#", 1)[0] == selector) for name in outcomes):
            raise EvidenceError("Excluded specialist selector executed inside generic compatibility scope")
    return counts, expected_suite


def collect(start_path, output, xml_patterns=(), artifact_patterns=(), native=None, screenshot=None, apk_report=None, device=None):
    if output.exists():
        raise EvidenceError("Use a new sealed output directory; existing evidence is immutable")
    start = load(start_path)
    if start.get("schema") != SCHEMA or start.get("gate") not in GATES:
        raise EvidenceError("Invalid start record")
    source(Path(start["root"]), start["source_sha"])
    files, xml = expanded(artifact_patterns), expanded(xml_patterns)
    gate = start["gate"]
    suite_manifest = None
    if gate == "native":
        if native is None:
            raise EvidenceError("Native gate requires the 081 runner record")
        native_files, native_xml = native_record(native, start["source_sha"])
        files += native_files
        xml += native_xml
    if gate == "android":
        if apk_report is None or len([path for path in files if path.suffix == ".apk"]) != 2:
            raise EvidenceError("Android gate requires built APKs and APK validation report")
        report = load(fresh(apk_report, start["started_ns"]))
        permissions = report.get("permissions")
        if (not isinstance(permissions, list)
                or any(not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", name) for name in permissions)
                or {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}.intersection(permissions)):
            raise EvidenceError("APK permission evidence missing, malformed or unsafe")
        if report.get("internetPermission") is not False or report.get("zipAlignment16k") is not True or not report.get("elfLibrariesChecked"):
            raise EvidenceError("APK privacy/alignment validation failed")
        files.append(Path(apk_report))
    if gate == "device":
        if screenshot is None or not any(path.suffix == ".apk" for path in files):
            raise EvidenceError("Device gate requires tested APK and actual screenshot")
        sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
        from check_screenshot import validate
        validate(fresh(screenshot, start["started_ns"]))
        if device is None:
            raise EvidenceError("Device compatibility record required")
        transcript = Path(device).with_name("instrumentation.txt")
        suite_manifest = Path(start["root"]) / SUITE_PATH
        committed_manifest = subprocess.check_output(["git", "-C", start["root"], "show", f"{start['source_sha']}:{SUITE_PATH}"])
        if suite_manifest.read_bytes() != committed_manifest:
            raise EvidenceError("Suite manifest differs from exact candidate Git source")
        device_counts, suite = device_record(fresh(device, start["started_ns"]), fresh(transcript, start["started_ns"]), [path for path in files if path.suffix == ".apk"], suite_manifest)
        files += [Path(device), transcript, Path(screenshot), suite_manifest]
    if not xml and gate != "device":
        raise EvidenceError("No JUnit test results")
    counts = dict(tests=0, passed=0, failed=0, skipped=0)
    for path in dict.fromkeys(xml):
        values = junit(fresh(path, start["started_ns"]))
        for key in counts:
            counts[key] += values[key]
    if gate == "device":
        counts = device_counts
    files += xml
    output.mkdir(parents=True, exist_ok=True)
    bundle = output / "files"
    bundle.mkdir(exist_ok=True)
    artifacts = []
    for path in dict.fromkeys(files):
        # Device APKs originate in the Android job. Their exact same-run hashes
        # are compared across both gates; reports/screenshots must be fresh here.
        path = fresh(path, 0 if gate == "device" and (path.suffix == ".apk" or path == suite_manifest) else start["started_ns"])
        digest = sha(path)
        relative = "files/" + digest + "-" + path.name
        target = output / relative
        shutil.copyfile(path, target)
        role = "junit" if path in xml else "screenshot" if screenshot and path == Path(screenshot) else "apk-report" if apk_report and path == Path(apk_report) else "native-record" if native and path == Path(native) else "device-record" if device and path == Path(device) else "instrumentation" if device and path == Path(device).with_name("instrumentation.txt") else "scope-manifest" if suite_manifest and path == suite_manifest else "artifact"
        artifacts.append(dict(path=relative, original_path=str(path), role=role, sha256=digest, size_bytes=target.stat().st_size))
    evidence = {**start, "status": "PASS", "completed_ns": time.time_ns(), "tests": counts, "artifacts": artifacts}
    if gate == "device":
        evidence["suite"] = suite
    (output / "evidence.json").write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n")
    return evidence


def verify_bundle(directory, expected, run_id, required=("native", "android", "device")):
    records = {}
    for path in directory.glob("*/evidence.json"):
        item = load(path)
        gate = item.get("gate")
        if gate in records or gate not in GATES or item.get("schema") != SCHEMA or item.get("source_sha") != expected or item.get("run_id") != run_id or item.get("status") != "PASS" or item.get("kind") != GATES[gate]:
            raise EvidenceError("Duplicate, stale or wrong-source CI evidence")
        counts = item.get("tests", {})
        if counts.get("passed", 0) < 1 or counts.get("failed") != 0 or counts.get("tests") != counts.get("passed", 0) + counts.get("skipped", 0):
            raise EvidenceError("Incomplete/failed test evidence")
        if item.get("completed_ns", 0) < item.get("started_ns", 1) or not item.get("artifacts"):
            raise EvidenceError("Missing artifact/completion evidence")
        mapped, roles = {}, {}
        for artifact in item["artifacts"]:
            relative = artifact["path"]
            if not relative.startswith("files/") or str(PurePosixPath(relative)) != relative or ".." in PurePosixPath(relative).parts or "\\" in relative:
                raise EvidenceError("Unsafe artifact path")
            candidate = path.parent / relative
            if candidate.is_symlink() or candidate.parent.is_symlink() or not candidate.is_file() or candidate.stat().st_size != artifact["size_bytes"] or sha(candidate) != artifact["sha256"]:
                raise EvidenceError("Missing/tampered CI artifact")
            if artifact["original_path"] in mapped:
                raise EvidenceError("Duplicate artifact identity")
            mapped[artifact["original_path"]] = candidate
            roles.setdefault(artifact["role"], []).append(candidate)
        totals = dict(tests=0, passed=0, failed=0, skipped=0)
        if not roles.get("junit") and gate != "device":
            raise EvidenceError("JUnit artifacts missing")
        for report in roles.get("junit", []):
            for key, value in junit(report).items():
                totals[key] += value
        if gate != "device" and totals != counts:
            raise EvidenceError("Summary test counts differ from actual JUnit")
        if gate == "native":
            if len(roles.get("native-record", [])) != 1:
                raise EvidenceError("Native runner record missing")
            native_record(roles["native-record"][0], expected, mapped)
        if gate in ("android", "device") and not any(path.suffix == ".apk" for path in mapped.values()):
            raise EvidenceError("Built/tested APK missing")
        if gate == "android":
            if len(roles.get("apk-report", [])) != 1:
                raise EvidenceError("APK validation report missing")
            apk = load(roles["apk-report"][0])
            if apk.get("internetPermission") is not False or apk.get("zipAlignment16k") is not True or not apk.get("elfLibrariesChecked"):
                raise EvidenceError("APK validation report failed")
        if gate == "device":
            if len(roles.get("screenshot", [])) != 1:
                raise EvidenceError("Device screenshot missing")
            sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
            from check_screenshot import validate
            validate(roles["screenshot"][0])
            if len(roles.get("device-record", [])) != 1 or len(roles.get("instrumentation", [])) != 1:
                raise EvidenceError("Device execution record/transcript missing")
            if len(roles.get("scope-manifest", [])) != 1:
                raise EvidenceError("Versioned compatibility scope manifest missing")
            actual_counts, actual_suite = device_record(roles["device-record"][0], roles["instrumentation"][0], [path for path in mapped.values() if path.suffix == ".apk"], roles["scope-manifest"][0])
            if actual_counts != counts or item.get("suite") != actual_suite:
                raise EvidenceError("Device summary does not match actual instrumentation/scope")
        records[gate] = item
    if set(records) != set(required):
        raise EvidenceError(f"Required evidence missing: {sorted(set(required) - set(records))}")
    if {"android", "device"} <= records.keys():
        hashes = lambda gate: {row["sha256"] for row in records[gate]["artifacts"] if row["path"].endswith(".apk")}
        if hashes("android") != hashes("device") or len(hashes("android")) != 2:
            raise EvidenceError("Installed APKs differ from the Android candidate build")
    return dict(schema=SCHEMA, status="PASS", source_sha=expected, run_id=run_id,
                gates={key: {"status": value["status"], "kind": value["kind"], "tests": value["tests"], **({"suite": value["suite"]} if key == "device" else {})} for key, value in records.items()},
                required_specialist_gates=records.get("device", {}).get("suite", {}).get("excludedGates", []),
                release_qualification="NOT_RUN", physical_device="NOT_RUN")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    start = sub.add_parser("begin")
    start.add_argument("--gate", choices=GATES, required=True)
    start.add_argument("--source-sha", required=True)
    start.add_argument("--run-id", required=True)
    start.add_argument("--root", type=Path, default=Path.cwd())
    start.add_argument("--output", type=Path, required=True)
    seal = sub.add_parser("collect")
    seal.add_argument("--start", type=Path, required=True)
    seal.add_argument("--output", type=Path, required=True)
    seal.add_argument("--junit", action="append", default=[])
    seal.add_argument("--artifact", action="append", default=[])
    seal.add_argument("--native-record", type=Path)
    seal.add_argument("--screenshot", type=Path)
    seal.add_argument("--apk-report", type=Path)
    seal.add_argument("--device-record", type=Path)
    check = sub.add_parser("verify")
    check.add_argument("--directory", type=Path, required=True)
    check.add_argument("--source-sha", required=True)
    check.add_argument("--run-id", required=True)
    check.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "begin":
            result = begin(args.root, args.gate, args.source_sha, args.run_id, args.output)
        elif args.command == "collect":
            result = collect(args.start, args.output, args.junit, args.artifact, args.native_record, args.screenshot, args.apk_report, args.device_record)
        else:
            result = verify_bundle(args.directory, args.source_sha, args.run_id)
            if args.output:
                args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({key: value for key, value in result.items() if key != "artifacts"}, sort_keys=True))
        return 0
    except (EvidenceError, OSError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.CalledProcessError) as exc:
        print(f"CI evidence rejected: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
