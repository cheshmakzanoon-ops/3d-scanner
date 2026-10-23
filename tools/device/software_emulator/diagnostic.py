#!/usr/bin/env python3
"""Explicitly opted-in, non-qualifying Android TCG experiment. Never accepts licenses.

All evidence is bounded normal job output; no remote artifact/cache upload. A PASS
means exactly four synthetic x86_64 runtime tests, not the existing device gate.
"""
from __future__ import annotations

import base64
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import posixpath
import re
import selectors
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import tarfile
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[3]
PUSH_REPOSITORY = "cheshmakzanoon-ops/3d-scanner"
PUSH_BASE_SHA = "9628a5b28501183b0d2fd78daf2cb77ba53da02f"
PUSH_MESSAGE = "test: isolate focused Android runtime on stock AOSP"
PUSH_TRIGGER_PATHS = frozenset((".github/workflows/software-emulator-diagnostic.yml",
                                "tools/device/software_emulator/diagnostic.py"))
PUSH_ALLOWED_PATHS = PUSH_TRIGGER_PATHS | frozenset(("tests/test_software_emulator_diagnostic.py",
    "docs/development/SOFTWARE_EMULATOR_DIAGNOSTIC.md", "SOURCE_MANIFEST.sha256"))
IMAGE = "system-images;android-35;default;x86_64"
IMAGE_REVISION = "2"
PACKAGES = ("platform-tools", "emulator", "platforms;android-36", "build-tools;36.0.0",
            "ndk;28.2.13676358", "cmake;3.22.1", IMAGE)
PREFIX = "com.realityforge.scanner."
EXPECTED = (
    PREFIX + "agents.a037.CalibratedInitializerNativeTest#calibratedPlaneReportsModelAndSupportWithoutChangingLegacyContract",
    PREFIX + "agents.a037.CalibratedInitializerNativeTest#calibratedJniRejectsMalformedDomainShapeAndMisalignedPixels",
    PREFIX + "agents.a037.CalibratedInitializerNativeTest#cancelledCalibratedCallHonorsScopedOperationLease",
    PREFIX + "agents.a033.FeatureCacheDeviceTest#privateAndroidCacheHitsCorruptionRecomputesAndEvictionPreservesUnownedFiles",
)
APP = "com.realityforge.scanner"
RUNNER = APP + ".test/androidx.test.runner.AndroidJUnitRunner"
SERIAL = "emulator-5580"
BUDGETS = {"workflow": 4500, "controller": 4320, "sdkInstall": 300,
           "hostLibraries": 300, "build": 1200, "boot": 1800,
           "packageManagerProbe": 60, "apkInstall": 600, "instrumentation": 900,
           "crashContext": 45, "deviceFactoryEvidence": 60, "deviceClockSample": 10}
HOST_LIBRARY_PACKAGES = frozenset(("libpulse0", "libasyncns0", "libsndfile1", "libflac12t64",
    "libogg0", "libopus0", "libvorbis0a", "libvorbisenc2", "libmpg123-0t64", "libmp3lame0",
    "libdbus-1-3", "libx11-6", "libx11-xcb1", "libxcb1", "libxau6", "libxdmcp6", "libbsd0", "libmd0"))
HOST_ARCHIVE_LIMIT = 32 * 1024 * 1024
HOST_EXPANDED_LIMIT = 64 * 1024 * 1024
HOST_METADATA_LIMIT = 256 * 1024 * 1024
SONAME = re.compile(r"[A-Za-z0-9_+.-]+\.so(?:\.[A-Za-z0-9_.-]+)*")
LOADER_TRACE_LIMIT = 256 * 1024
INSTRUMENTATION_LIMIT = 128 * 1024
CRASH_CONTEXT_LIMIT = 256 * 1024
LOG_LIMIT = 4 * 1024 * 1024
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class Failure(RuntimeError):
    status = "FAIL"


class Blocked(Failure):
    status = "BLOCKED"


class Deadline(Failure):
    status = "TIMEOUT"


class Cancelled(Failure):
    status = "CANCELLED"


def sha256(path, check=None):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            if check is not None:
                check()
            digest.update(chunk)
    return digest.hexdigest()


def redact_diagnostic_text(text):
    """Suppress known emulator public-key lines before tail truncation, not raw proofs."""
    return re.sub(r"(?i)(Sending adb public key|androidboot\.qemu\.adb\.pubkey=)[^\r\n]*",
                  r"\1[REDACTED_EPHEMERAL_ADB_PUBLIC_KEY]", text)


def emit(label, output, limit=16384):
    """Prefix every line so child output cannot issue GitHub workflow commands."""
    raw = output.encode("utf-8", "replace") if isinstance(output, str) else output
    decoded = raw.decode("utf-8", "replace")
    safe = redact_diagnostic_text(decoded)
    tail = safe.encode("utf-8")[-limit:].decode("utf-8", "replace")
    print("RF_DIAGNOSTIC_LOG " + label + " bytes=" + str(len(raw))
          + (" displayRedacted=true" if safe != decoded else ""), flush=True)
    for line in tail.splitlines():
        # Strip terminal control characters, while preserving printable evidence.
        line = "".join(c if c >= " " or c == "\t" else "?" for c in line)
        print("RF_DIAGNOSTIC_LOG " + label + " | " + line, flush=True)


def crash_context_display(raw):
    """Diagnostic-only rendering; never publish raw crash bytes as execution proof."""
    decoded = raw.decode("utf-8", "replace")
    safe = redact_diagnostic_text(decoded)
    # This disposable synthetic device has no user accounts or signing secrets.
    # Fail closed on recognizable unexpected credentials, including multiline keys.
    secret = re.search(r"-----BEGIN [A-Z ]*PRIVATE KEY-----|(?i:"
        r"\b(?:authorization|proxy-authorization)\s*[:=]|"
        r"\b(?:password|passwd|access[_-]?token|refresh[_-]?token|client[_-]?secret|api[_-]?key)\s*[:=]|"
        r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})|"
        r"https?://[^\s/@:]+:[^\s/@]+@)", safe)
    if secret:
        return b"", "WITHHELD_SECRET_MARKER"
    # Canonical no-terminal-newline form is recoverable by joining prefixed lines.
    display = "\n".join("".join(c if c >= " " or c == "\t" else "?" for c in line)
                        for line in safe.splitlines()).rstrip("\n").encode("utf-8")
    if len(display) > CRASH_CONTEXT_LIMIT:
        return b"", "WITHHELD_DISPLAY_LIMIT"
    return display, "SANITIZED_DIAGNOSTIC_ONLY"


def validate_image_metadata(properties, metadata):
    if (properties.get("AndroidVersion.ApiLevel") != "35"
            or properties.get("SystemImage.Abi") != "x86_64"
            or properties.get("SystemImage.TagId") != "default"
            or properties.get("Pkg.Revision") != IMAGE_REVISION
            or metadata.get("package") != IMAGE
            or metadata.get("revision") not in (IMAGE_REVISION, IMAGE_REVISION + ".0.0")):
        raise Blocked("System image must be ordinary AOSP API35 default x86_64 revision2")


def validate_factory_evidence(record):
    """Observe factory settings, never set them or infer absent values/equivalence."""
    settings = record["globalSettings"]
    if settings.get("debug_app") in (APP, APP + ".test"):
        raise Blocked("Selected app debugger mode suppresses Android ANR handling")
    if settings.get("wait_for_debugger") not in ("", "null", "0", "1"):
        raise Blocked("Unrecognized wait_for_debugger setting")
    record["watchdogEquivalenceToPreviousGoogleImage"] = "UNVERIFIED"
    record["scope"] = "observed-factory-values-no-setting-writes"


def associate_anr_trace(logcat, trace, before, after):
    """Associate only complete same-device UTC event/trace evidence, not test proof."""
    unavailable = {"status": "UNASSOCIATED", "reason": "missing-or-ambiguous-process-pid-time-evidence"}
    try:
        if before.get("status") != "CAPTURED" or after.get("status") != "CAPTURED":
            return unavailable
        low, high = before["epochSeconds"], after["epochSeconds"] + 1
        elapsed = after["sampleMonotonic"] - before["sampleMonotonic"]
        if elapsed < 0 or high < low or abs((high - low - 1) - elapsed) > 5:
            return {**unavailable, "reason": "device-clock-discontinuity"}
        events = []
        lines = logcat.decode("utf-8", "strict").splitlines()
        prefix = r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+) E ActivityManager: "
        for index, line in enumerate(lines):
            match = re.fullmatch(prefix + "ANR in " + re.escape(APP), line)
            if match is None or index + 2 >= len(lines):
                continue
            stamp = datetime.strptime(match[1], "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=timezone.utc).timestamp()
            if not low <= stamp <= high:
                continue
            pid = re.fullmatch(prefix + r"PID: ([1-9][0-9]*)", lines[index + 1])
            reason = re.fullmatch(prefix + r"Reason: Process ProcessRecord\{[^{}\s]+ ([1-9][0-9]*):"
                + re.escape(APP) + r"/[^{}\s]+\} failed to complete startup", lines[index + 2])
            if (pid is None or reason is None or pid.groups()[:3] != match.groups()
                    or reason.groups()[:3] != match.groups() or pid[4] != reason[4]):
                return unavailable
            events.append((int(pid[4]), stamp, match[1]))
        if len(events) != 1:
            return unavailable
        pid, event_time, event_text = events[0]
        blocks = re.findall(r"(?ms)^----- pid ([1-9][0-9]*) at ([^\n]+) -----\n(.*?)^----- end ([1-9][0-9]*) -----$",
                            trace.decode("utf-8", "strict"))
        selected = [block for block in blocks if block[0] == str(pid)
                    or re.search(r"(?m)^Cmd line: " + re.escape(APP) + "$", block[2])]
        if len(selected) != 1:
            return unavailable
        block_pid, timestamp, body, end_pid = selected[0]
        # ART GetIsoDate prints an explicit numeric timezone; never borrow the host timezone.
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{9}[+-]\d{4}", timestamp):
            return unavailable
        canonical = timestamp[:26] + timestamp[-5:]
        trace_time = datetime.strptime(canonical, "%Y-%m-%d %H:%M:%S.%f%z").timestamp()
        if (len(re.findall(r"(?m)^----- pid " + str(pid) + r" at ", trace.decode())) != 1
                or len(re.findall(r"(?m)^Cmd line: " + re.escape(APP) + "$", trace.decode())) != 1
                or block_pid != str(pid) or end_pid != str(pid)
                or re.findall(r"(?m)^Cmd line: (.*)$", body) != [APP]
                or not re.search(r'(?m)^"main" ', body)
                or not low <= trace_time <= high or abs(trace_time - event_time) > 30):
            return unavailable
        return {"status": "ASSOCIATED_DIAGNOSTIC_ONLY", "package": APP, "pid": pid,
                "eventUtc": event_text + "Z", "traceTimestamp": timestamp,
                "timeBasis": "device-date-UTC-and-logcat-year-UTC-with-explicit-trace-zone"}
    except (ValueError, KeyError, TypeError, UnicodeDecodeError, OverflowError):
        return unavailable


def base_receipt():
    return {"schema": "realityforge.software-emulator-diagnostic.v1", "status": "NOT_RUN",
            "scope": "four synthetic Android x86_64 ART/Bionic/JNI/filesystem tests",
            "cpuMode": "UNSUPPORTED_TCG", "graphicsMode": "software",
            "qualification": {name: "NOT_RUN" for name in (
                "originalDeviceGate", "fullInstrumentation", "physicalCapture", "arm64",
                "performance", "graphicsQualification", "runtime16kb")},
            "budgetsSeconds": BUDGETS.copy(), "expectedSelectors": list(EXPECTED),
            "startedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}


def emit_raw_instrumentation(output):
    """Keep exact bounded transcript bytes recoverable without artifact uploads.

    Human-readable logs normalize line endings/control characters. These small
    ASCII-only chunks preserve the original UTF-8 bytes and cannot issue workflow
    commands. A consumer must require every unique ordered chunk and verify SHA.
    """
    raw = output.encode("utf-8")
    if redact_diagnostic_text(output) != output:
        raise Failure("Raw instrumentation contains a public-key diagnostic; refusing unredacted proof emission")
    if len(raw) > INSTRUMENTATION_LIMIT:
        raise Failure("Instrumentation evidence exceeds bounded size")
    encoded = base64.b64encode(raw).decode("ascii")
    chunks = [encoded[index:index + 4096] for index in range(0, len(encoded), 4096)]
    metadata = {"encoding": "base64", "bytes": len(raw), "chunks": len(chunks),
                "sha256": hashlib.sha256(raw).hexdigest()}
    print("RF_INSTRUMENTATION_RAW_META " + json.dumps(metadata, sort_keys=True), flush=True)
    for index, chunk in enumerate(chunks, 1):
        print(f"RF_INSTRUMENTATION_RAW_CHUNK {index}/{len(chunks)} {chunk}", flush=True)


def emit_raw_loader_trace(raw, complete):
    """Preserve the complete bounded capture even when subsequent parsing fails."""
    if len(raw) > LOADER_TRACE_LIMIT:
        raise Failure("Loader trace exceeds bounded size")
    decoded = raw.decode("utf-8", "replace")
    if redact_diagnostic_text(decoded) != decoded:
        raise Failure("Raw loader trace contains a public-key diagnostic; refusing unredacted evidence emission")
    encoded = base64.b64encode(raw).decode("ascii")
    chunks = [encoded[index:index + 4096] for index in range(0, len(encoded), 4096)]
    metadata = {"encoding": "base64", "bytes": len(raw), "chunks": len(chunks),
                "sha256": hashlib.sha256(raw).hexdigest(), "complete": complete is True}
    print("RF_LOADER_TRACE_RAW_META " + json.dumps(metadata, sort_keys=True), flush=True)
    for index, chunk in enumerate(chunks, 1):
        print(f"RF_LOADER_TRACE_RAW_CHUNK {index}/{len(chunks)} {chunk}", flush=True)


def validate_policy(env, event):
    repository = event.get("repository", {}) if isinstance(event, dict) else {}
    if (not isinstance(repository, dict) or env.get("RUNNER_ENVIRONMENT") != "github-hosted"
            or env.get("RUNNER_OS") != "Linux" or env.get("RUNNER_ARCH") != "X64"
            or repository.get("private") is not False):
        raise Blocked("Requires a public repository on standard Linux x64 GitHub hosting")
    if not re.fullmatch(r"[0-9a-f]{40}", env.get("GITHUB_SHA", "")):
        raise Blocked("Missing exact GitHub source SHA")
    if env.get("GITHUB_EVENT_NAME") == "workflow_dispatch":
        return {"event": "workflow_dispatch"}
    head = event.get("head_commit")
    if (env.get("GITHUB_EVENT_NAME") != "push" or not isinstance(head, dict)
            or env.get("GITHUB_REPOSITORY") != PUSH_REPOSITORY
            or repository.get("full_name") != PUSH_REPOSITORY
            or repository.get("default_branch") != "main"
            or env.get("GITHUB_REF") != "refs/heads/main" or event.get("ref") != "refs/heads/main"
            or event.get("before") != PUSH_BASE_SHA
            or event.get("after") != env["GITHUB_SHA"] or head.get("id") != env["GITHUB_SHA"]
            or head.get("message") != PUSH_MESSAGE or env.get("GITHUB_RUN_ATTEMPT") != "1"
            or any(event.get(key) is not False for key in ("created", "deleted", "forced"))):
        raise Blocked("Not the exact one-shot diagnostic push; no automatic retry or recurring push trigger")
    return {"event": "push", "repository": PUSH_REPOSITORY, "ref": "refs/heads/main",
            "before": PUSH_BASE_SHA, "after": env["GITHUB_SHA"], "attempt": 1,
            "commitMessage": PUSH_MESSAGE}


def validate_push_tree(source_sha, parents, message, changes):
    """Do not trust path filters or missing per-file data in the Actions payload."""
    if parents.split() != [source_sha, PUSH_BASE_SHA] or message != PUSH_MESSAGE:
        raise Blocked("One-shot source must be one non-merge commit with the exact reviewed parent/message")
    if not changes.endswith("\0"):
        raise Blocked("Missing or malformed one-shot changed-file evidence")
    fields = changes[:-1].split("\0")
    if len(fields) % 2:
        raise Blocked("Malformed one-shot changed-file evidence")
    paths = []
    for status, path in zip(fields[::2], fields[1::2]):
        if status != "M" or path not in PUSH_ALLOWED_PATHS or path in paths:
            raise Blocked("One-shot push changed an unreviewed path or file status")
        paths.append(path)
    if not PUSH_TRIGGER_PATHS.issubset(paths):
        raise Blocked("One-shot push must change both the workflow and controller")
    return sorted(paths)


def loader_missing(output, resolved=None):
    """Parse complete ldd output; version/format errors never look like resolution."""
    missing, records = set(), 0
    for line in output.splitlines():
        line = line.strip()
        absent = re.fullmatch(r"([^ /]+) => not found", line)
        if absent and SONAME.fullmatch(absent[1]):
            missing.add(absent[1])
        elif not (re.fullmatch(r"[^ /]+ => /[^\n]+ \(0x[0-9a-f]+\)", line)
                  or re.fullmatch(r"/[^\n]+ \(0x[0-9a-f]+\)", line)
                  or re.fullmatch(r"linux-vdso\.so\.1 \(0x[0-9a-f]+\)", line)):
            raise Blocked("Unrecognized ELF loader output or unresolved version requirement")
        else:
            match = re.fullmatch(r"([^ /]+) => (/[^\n]+) \(0x[0-9a-f]+\)", line)
            if resolved is not None and match:
                if match[1] in resolved and Path(resolved[match[1]]).resolve() != Path(match[2]).resolve():
                    raise Blocked("Conflicting ELF providers for one selected SONAME")
                resolved[match[1]] = match[2]
        records += 1
    if not records:
        raise Blocked("Missing complete ELF loader evidence")
    return missing


def apt_plan(output):
    """Only a new, narrow audio-library closure; never upgrade host/security libraries."""
    result = {}
    for line in output.splitlines():
        if line.startswith(("Remv ", "Purg ", "Broken ")):
            raise Blocked("Private dependency plan attempts removal or has broken dependencies")
        if not line.startswith("Inst "):
            continue
        match = re.fullmatch(r"Inst ([a-z0-9+.-]+) \(([A-Za-z0-9.+:~_-]+) .+ \[(amd64|all)\]\)", line)
        if not match or match[1] not in HOST_LIBRARY_PACKAGES or match[1] in result:
            raise Blocked("Dependency plan contains an upgrade, unrelated package or malformed identity")
        result[match[1]] = {"version": match[2], "architecture": match[3]}
    if "libpulse0" not in result or len(result) > 16:
        raise Blocked("Missing or oversized private PulseAudio dependency closure")
    return result


def deb_fields(text):
    fields = {}
    for line in text.strip().splitlines():
        if line.startswith((" ", "\t")):
            continue
        key, separator, value = line.partition(": ")
        if not separator or key in fields:
            raise Blocked("Ambiguous package metadata")
        fields[key] = value
    return fields


def checked_package(text, name, planned):
    fields = deb_fields(text)
    if (fields.get("Package") != name or fields.get("Version") != planned["version"]
            or fields.get("Architecture") != planned["architecture"]
            or not re.fullmatch(r"[0-9a-f]{64}", fields.get("SHA256", ""))
            or not fields.get("Size", "").isdigit()
            or not 0 < int(fields["Size"]) <= HOST_ARCHIVE_LIMIT
            or not re.fullmatch(r"pool/main/[A-Za-z0-9+._~/-]+\.deb", fields.get("Filename", ""))
            or ".." in PurePosixPath(fields["Filename"]).parts):
        raise Blocked("Package metadata is not the exact signed Ubuntu main dependency")
    return {"package": name, "version": planned["version"], "architecture": planned["architecture"],
            "sha256": fields["SHA256"], "bytes": int(fields["Size"]), "filename": fields["Filename"],
            "metadataSha256": hashlib.sha256(text.encode()).hexdigest()}


def extract_library_archive(payload, destination, check=None):
    """Extract data only after validating every path/type/link; never run package scripts."""
    if len(payload) > HOST_EXPANDED_LIMIT or destination.exists():
        raise Blocked("Package expansion exceeds bounds or destination is not fresh")
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
        members, names, links, total = [], set(), set(), 0
        for member in archive:
            if check is not None:
                check()
            name = member.name.removeprefix("./").rstrip("/")
            if name in ("", ".") and member.isdir():
                continue
            if (not name or name.startswith("/") or "\\" in name
                    or any(part in ("", ".", "..") for part in name.split("/"))
                    or any(ord(c) < 32 or ord(c) == 127 for c in name) or name in names
                    or not (member.isdir() or member.isfile() or member.issym())):
                raise Blocked("Unsafe or duplicate archive member")
            if member.issym():
                target = member.linkname
                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), target))
                if (not target or target.startswith("/") or "\\" in target
                        or any(ord(c) < 32 or ord(c) == 127 for c in target)
                        or resolved == ".." or resolved.startswith("../")):
                    raise Blocked("Archive symlink escapes owned package directory")
                links.add(name)
            total += member.size
            if total > HOST_EXPANDED_LIMIT or len(members) >= 10000:
                raise Blocked("Package expanded size/member count exceeds bounds")
            names.add(name)
            members.append((member, name))
        for _, name in members:
            if any(str(parent) in links for parent in PurePosixPath(name).parents):
                raise Blocked("Archive writes through a symlink ancestor")
        destination.mkdir()
        for member, name in members:
            if check is not None:
                check()
            path = destination / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if member.isdir():
                path.mkdir(exist_ok=True)
            elif member.isfile():
                with archive.extractfile(member) as source, path.open("xb") as target:
                    shutil.copyfileobj(source, target, 1024 * 1024)
            else:
                path.symlink_to(member.linkname)
        for name in links:
            try:
                if not (destination / name).resolve().is_relative_to(destination.resolve()):
                    raise Blocked("Archive symlink chain escapes owned package directory")
            except RuntimeError as error:
                raise Blocked("Cyclic archive symlink") from error


def verify_x86_64_shared_object(path, executable=False):
    with path.open("rb") as stream:
        header = stream.read(20)
    if (len(header) != 20 or header[:6] != b"\x7fELF\x02\x01"
            or int.from_bytes(header[16:18], "little") not in ((2, 3) if executable else (3,))
            or int.from_bytes(header[18:20], "little") != 62):
        raise Blocked("Dependency is not an x86_64 ELF shared object")


def private_apt_config(root, keyring, status):
    """No host APT snippets/hooks/sources or writes: only existing Ubuntu archive trust."""
    if root.exists() or not keyring.is_file() or not status.is_file():
        raise Blocked("Fresh private APT state and existing archive keyring/status are required")
    for path in (root, keyring, status):
        if any(c in str(path) for c in ('"', "\\", "\n", "\r")):
            raise Blocked("Unsafe APT configuration path")
    root.mkdir()
    for name in ("empty", "lists/partial", "cache/archives/partial", "log", "downloads", "gnupg"):
        (root / name).mkdir(parents=True)
    shutil.copyfile(keyring, root / "ubuntu-archive-keyring.gpg")
    shutil.copyfile(status, root / "status")
    for name in ("preferences", "extended_states", "main.conf", "auth.conf"):
        (root / name).write_text("")
    sources = []
    for uri, suites in (("https://archive.ubuntu.com/ubuntu/", "noble noble-updates"),
                        ("https://security.ubuntu.com/ubuntu/", "noble-security")):
        sources.append(f"Types: deb\nURIs: {uri}\nSuites: {suites}\nComponents: main\n"
                       f"Architectures: amd64\nSigned-By: {root}/ubuntu-archive-keyring.gpg\n")
    (root / "ubuntu.sources").write_text("\n".join(sources))
    config = {
        "Dir::Etc::Parts": root / "empty", "Dir::Etc::main": root / "main.conf",
        "Dir::Etc::sourcelist": root / "ubuntu.sources", "Dir::Etc::sourceparts": root / "empty",
        "Dir::Etc::preferences": root / "preferences", "Dir::Etc::preferencesparts": root / "empty",
        "Dir::Etc::netrc": root / "auth.conf", "Dir::Etc::netrcparts": root / "empty",
        "Dir::Etc::trusted": root / "ubuntu-archive-keyring.gpg", "Dir::Etc::trustedparts": root / "empty",
        "Dir::State": root, "Dir::State::lists": root / "lists", "Dir::State::status": root / "status",
        "Dir::State::extended_states": root / "extended_states", "Dir::Cache": root / "cache",
        "Dir::Cache::pkgcache": root / "cache/pkgcache.bin", "Dir::Cache::srcpkgcache": root / "cache/srcpkgcache.bin",
        "Dir::Log": root / "log", "APT::Architecture": "amd64", "Acquire::Languages": "none",
        "Acquire::Retries": "0", "Acquire::https::Timeout": "30", "Acquire::Check-Valid-Until": "true",
        "Acquire::AllowInsecureRepositories": "false", "Acquire::AllowWeakRepositories": "false",
        "Acquire::AllowDowngradeToInsecureRepositories": "false", "APT::Get::AllowUnauthenticated": "false",
        "APT::Update::Error-Mode": "any", "APT::Install-Recommends": "false", "APT::Install-Suggests": "false"}
    (root / "apt.conf").write_text("".join(f'{key} "{value}";\n' for key, value in config.items())
        + 'APT::Architectures { "amd64"; };\n')
    return root / "apt.conf"


def check_metadata_storage(root, byte_limit=HOST_METADATA_LIMIT, entry_limit=128):
    """Sample owned APT index growth; this is a polling guard, not a filesystem quota."""
    if not root.is_dir() or root.is_symlink():
        raise Blocked("Private APT metadata directory is missing or not owned")
    total, entries = 0, 0
    def unreadable(error):
        raise Blocked("Cannot inspect private APT metadata storage") from error
    for directory, dirs, files in os.walk(root, followlinks=False, onerror=unreadable):
        for name in dirs + files:
            entries += 1
            if entries > entry_limit:
                raise Blocked("Private APT metadata exceeded entry-count limit")
            try:
                value = (Path(directory) / name).lstat()
            except FileNotFoundError:
                continue  # APT atomically renames partial indexes while running.
            if stat.S_ISREG(value.st_mode):
                total += value.st_size
            elif not stat.S_ISDIR(value.st_mode):
                raise Blocked("Unexpected private APT metadata file type")
            if total > byte_limit:
                raise Blocked("Private APT metadata exceeded sampled byte limit")


def observed_loader_paths(output, emulator_root, probe_pid, exit_code=0, evidence=None):
    """Bind backend startup and search records to the actual owned probe PID/exec.

    RPATH/RUNPATH remain native ELF attributes; only observed LD_LIBRARY_PATH
    entries become the corresponding environment variable for closure checks.
    """
    emulator_root = emulator_root.resolve()
    launcher = emulator_root / "emulator"
    allowed = {emulator_root / "qemu/linux-x86_64" / name for name in
               ("qemu-system-x86_64", "qemu-system-x86_64-headless")}
    if type(probe_pid) is not int or probe_pid <= 0:
        raise Blocked("Missing owned launcher probe PID")
    pending, initialized, completed, selected, seen_pids = [], None, [], None, set()
    for line in output.splitlines():
        record = re.fullmatch(r"\s*([0-9]+):\s*(.*)", line)
        if not record:
            continue
        pid, body = int(record[1]), record[2].strip()
        seen_pids.add(pid)
        startup = re.fullmatch(r"(initialize program|transferring control): (/.+)", body)
        if pid != probe_pid:
            if startup and Path(startup[2]).resolve() in allowed:
                raise Blocked("A different process also claims selected QEMU startup")
            continue  # Crashpad and other children cannot supply backend/path evidence.
        if startup:
            path = Path(startup[2]).resolve()
            if path not in allowed | {launcher}:
                raise Blocked("Owned probe initialized a foreign program")
            if startup[1] == "initialize program":
                if initialized is not None or path in completed:
                    raise Blocked("Duplicate or incomplete owned program initialization")
                initialized = (path, list(pending))
            else:
                if initialized is None or initialized[0] != path:
                    raise Blocked("Program control transfer lacks its matching initialization")
                if path in allowed:
                    if selected is not None:
                        raise Blocked("Conflicting selected QEMU backend")
                    selected = (path, initialized[1], "paired-initialize-and-control")
                completed.append(path)
                pending, initialized = [], None
        elif body.startswith(("initialize program:", "transferring control:")):
            raise Blocked("Malformed owned program startup evidence")
        elif body.startswith("search path="):
            pending.append(body)
    if initialized is not None:
        raise Blocked("Program initialization has no matching control transfer")
    if selected is None:
        # A loader failure occurs before initialization. Accept only one exact
        # backend error after the owned launcher, no child PID/foreign evidence.
        failures = re.findall(r"(?m)^(/[^\n:]+): error while loading shared libraries: "
            r"([^ /:]+): cannot open shared object file: No such file or directory$", output)
        if (exit_code != 127 or len(failures) != 1 or Path(failures[0][0]).resolve() not in allowed
                or not SONAME.fullmatch(failures[0][1]) or seen_pids != {probe_pid}
                or completed != [launcher]):
            raise Blocked("Cannot identify an owned selected QEMU startup or exact loader failure")
        selected = (Path(failures[0][0]).resolve(), pending, "owned-loader-failure")
    backend, records, mode = selected
    if mode == "paired-initialize-and-control" and completed not in ([backend], [launcher, backend]):
        raise Blocked("Unexpected owned launcher/backend execution sequence")
    if evidence is not None:
        evidence.update(backendPid=probe_pid, backend=str(backend.relative_to(emulator_root)), backendIdentityMode=mode)
    directories, elf_records, ld_order = [], [], None
    for body in records:
        search = re.fullmatch(r"search path=(.*?)\s+\((LD_LIBRARY_PATH|(?:RPATH|RUNPATH) from file .+)\)", body)
        if not search:
            if body.endswith("(system search path)"):
                continue
            raise Blocked("Unrecognized selected-process library search evidence")
        kind = search[2].split(" from file ", 1)[0]
        origin = None if kind == "LD_LIBRARY_PATH" else Path(search[2].split(" from file ", 1)[1]).resolve()
        if origin is not None and not origin.is_relative_to(emulator_root):
            continue  # Host ELF metadata is handled by the real loader, never promoted.
        observed = []
        for entry in search[1].strip().split(":"):
            path = Path(entry)
            if (not entry or not path.is_absolute() or any(ord(c) < 32 for c in entry)
                    or not path.resolve().is_relative_to(emulator_root)):
                raise Blocked("Selected-process SDK library path escaped the official SDK")
            if path.is_dir() and path.resolve() not in observed:
                observed.append(path.resolve())
        if kind == "LD_LIBRARY_PATH":
            if ld_order is None:
                ld_order = observed
                directories = observed
            elif [p for p in ld_order if p in observed] != observed:
                raise Blocked("Conflicting selected-process LD_LIBRARY_PATH order")
        else:
            item = {"kind": kind, "origin": str(origin.relative_to(emulator_root)),
                    "directories": [str(p.relative_to(emulator_root)) for p in observed]}
            if item not in elf_records:
                elf_records.append(item)
        if len(elf_records) > 64:
            raise Blocked("Oversized selected-process ELF search evidence")
    if ld_order is None and not elf_records:
        raise Blocked("Selected QEMU library search evidence was not observed")
    if evidence is not None:
        evidence.update(backendPid=probe_pid, backend=str(backend.relative_to(emulator_root)),
                        backendIdentityMode=mode, elfSearchRecords=elf_records,
                        observedLdLibraryPath=[str(p.relative_to(emulator_root)) for p in directories])
    return backend, directories


def license_snapshot(sdk):
    directory = sdk / "licenses"
    if not directory.is_dir() or directory.is_symlink():
        raise Blocked("Pre-existing SDK license directory unavailable; no license acceptance allowed")
    result = {}
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 65536:
            raise Blocked("Unexpected SDK license entry")
        hashes = path.read_text().split()
        if not hashes or any(not re.fullmatch(r"[0-9a-fA-F]{40}", x) for x in hashes):
            raise Blocked("Malformed pre-existing SDK license record")
        result[path.name] = sha256(path)
    if "android-sdk-license" not in result:
        raise Blocked("Pre-existing android-sdk-license missing; no license acceptance allowed")
    return result


def verify_license_snapshot(sdk, expected):
    if license_snapshot(sdk) != expected:
        raise Blocked("SDK license records changed; no new terms may be accepted")


def parse_instrumentation(output):
    """Require real, complete JUnit start/finish events for exactly four identities."""
    if len(output.encode()) > INSTRUMENTATION_LIMIT:
        raise Failure("Instrumentation evidence exceeds bounded size")
    if any(x in output for x in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed",
                                "INSTRUMENTATION_ABORTED", "INSTRUMENTATION_RESULT: shortMsg=")):
        raise Failure("Instrumentation crash/failure marker")
    if re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.M) != ["4"]:
        raise Failure("Missing or inconsistent four-test JUnit summary")
    fields, started, completed, active, events = {}, set(), set(), None, []
    final_seen = False
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_CODE"):
            if final_seen or not re.fullmatch(r"INSTRUMENTATION_CODE: -1", line):
                raise Failure("Duplicate or malformed final instrumentation code")
            final_seen = True
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if final_seen:
                raise Failure("Test event after final instrumentation code")
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if sep and key in ("class", "test", "current", "numtests"):
                if key in fields:
                    raise Failure("Duplicate event field")
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            if final_seen or set(fields) != {"class", "test", "current", "numtests"}:
                raise Failure("Incomplete or misplaced instrumentation event")
            identity = fields["class"] + "#" + fields["test"]
            try:
                code = int(line.partition(":")[2])
                index, total = int(fields["current"]), int(fields["numtests"])
            except ValueError as error:
                raise Failure("Noninteger instrumentation event") from error
            if identity not in EXPECTED or total != 4 or not 1 <= index <= 4:
                raise Failure("Unexpected test identity/count")
            if code == 1:
                if active or identity in started or index != len(started) + 1:
                    raise Failure("Duplicate, overlapping or nonsequential test start")
                started.add(identity)
                active = (identity, index)
            elif code == 0:
                if active != (identity, index) or identity in completed:
                    raise Failure("Terminal event has no unique matching start")
                completed.add(identity)
                active = None
            else:
                raise Failure("A selected test failed, skipped or had an assumption failure")
            events.append({"selector": identity, "current": index, "total": total, "code": code})
            fields = {}
    if not final_seen or fields or active or started != set(EXPECTED) or completed != set(EXPECTED) or len(events) != 8:
        raise Failure("Missing, partial or duplicate selected-test proof")
    return {"tests": 4, "passed": 4, "failed": 0, "skipped": 0, "events": events,
            "rawSha256": hashlib.sha256(output.encode()).hexdigest()}


def junit_xml(proof, receipt):
    suite = ET.Element("testsuite", name="unsupported-software-emulator-focused", tests="4",
                       failures="0", errors="0", skipped="0")
    properties = ET.SubElement(suite, "properties")
    for name, value in (("sourceSha", receipt["sourceSha"]), ("appSha256", receipt["appSha256"]),
                        ("testSha256", receipt["testSha256"]), ("rawSha256", proof["rawSha256"]),
                        ("scope", receipt["scope"]), ("cpuMode", "UNSUPPORTED_TCG")):
        ET.SubElement(properties, "property", name=name, value=value)
    for selector in EXPECTED:
        owner, method = selector.split("#")
        ET.SubElement(suite, "testcase", classname=owner, name=method)
    return ET.tostring(suite, encoding="unicode")


def require_executable(path):
    if not path.is_file() or not os.access(path, os.X_OK):
        raise Blocked("Required existing executable unavailable: " + str(path))
    return str(path)


def package_metadata(sdk, package):
    directory = sdk.joinpath(*package.split(";"))
    manifest = directory / "package.xml"
    if not manifest.is_file() or not (directory / "source.properties").is_file():
        raise Blocked("SDK package missing: " + package)
    try:
        tree = ET.fromstring(manifest.read_bytes())
        local = [node for node in tree.iter() if node.tag.split("}")[-1] == "localPackage"]
        if len(local) != 1 or local[0].get("path") != package:
            raise ValueError("package identity")
        revision = next(node for node in local[0] if node.tag.split("}")[-1] == "revision")
        version = ".".join((node.text or "") for node in revision)
        if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", version):
            raise ValueError("package revision")
    except (ET.ParseError, StopIteration, ValueError) as error:
        raise Blocked("Inconsistent SDK package metadata: " + package) from error
    return {"package": package, "revision": version, "packageXmlSha256": sha256(manifest),
            "sourcePropertiesSha256": sha256(directory / "source.properties")}


def validate_apk_manifests(app_xml, test_xml, java_notice=None):
    # The JVM writes this exact configured notice to stderr, which our bounded
    # command transcript merges with stdout. No other preamble is permitted.
    if java_notice is not None:
        app_xml = app_xml.removeprefix(java_notice + "\n")
        test_xml = test_xml.removeprefix(java_notice + "\n")
    app, test = ET.fromstring(app_xml), ET.fromstring(test_xml)
    application = app.find("application")
    if (app.get("package") != APP or application is None
            or application.get(ANDROID_NS + "debuggable") != "true"):
        raise Failure("Expected the exact debug app manifest")
    entries = test.findall("instrumentation")
    if (test.get("package") != APP + ".test" or len(entries) != 1
            or entries[0].get(ANDROID_NS + "targetPackage") != APP
            or entries[0].get(ANDROID_NS + "name") != "androidx.test.runner.AndroidJUnitRunner"):
        raise Failure("Test APK does not target the selected app/runner")


def stop_owned(proc):
    """Signal only this Popen-owned process group, retaining its PID until reaped."""
    if proc is None:
        return
    # Do not poll/wait first: an unreaped leader reserves its process-group ID.
    for sig, grace in ((signal.SIGTERM, 1.0), (signal.SIGKILL, 0.0)):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            break
        if grace:
            time.sleep(grace)
    proc.wait()


def process_listens_on_loopback(pid, port, proc_root=Path("/proc")):
    """Read-only socket-inode proof: never connect to an unowned server to probe it."""
    try:
        inodes = set()
        for path in (proc_root / str(pid) / "fd").iterdir():
            try:
                target = os.readlink(path)
            except FileNotFoundError:
                # A transient unrelated client FD can close during inspection.
                continue
            if target.startswith("socket:["):
                inodes.add(target[8:-1])
        owned = []
        for filename in ("tcp", "tcp6"):
            table = proc_root / "net" / filename
            if not table.exists():
                continue
            for line in table.read_text().splitlines()[1:]:
                fields = line.split()
                if len(fields) < 10 or fields[3] != "0A" or fields[9] not in inodes:
                    continue
                address, raw_port = fields[1].split(":")
                if int(raw_port, 16) == port:
                    owned.append(address)
        if any(address not in ("0100007F", "00000000000000000000000001000000") for address in owned):
            raise Failure("Owned diagnostic server unexpectedly listens beyond loopback")
        return bool(owned)
    except (OSError, ValueError) as error:
        raise Blocked("Cannot verify owned process listener via /proc: " + str(error)) from error


def private_environment(env, temp, adb_port):
    """Keep Android preferences, debug/ADB keys and all Java user state task-local."""
    result = dict(env)
    for key in ("ADB_VENDOR_KEYS", "ANDROID_SERIAL", "ADBHOST", "ADB_TRACE", "ANDROID_VERBOSE",
                "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        result.pop(key, None)
    home = temp / "home"
    paths = {"HOME": home, "ANDROID_USER_HOME": home / ".android", "ANDROID_SDK_HOME": home,
             "ANDROID_EMULATOR_HOME": home / ".android", "ANDROID_AVD_HOME": temp / "avd",
             "GRADLE_USER_HOME": temp / "gradle", "XDG_CONFIG_HOME": temp / "config",
             "XDG_CACHE_HOME": temp / "cache", "XDG_DATA_HOME": temp / "data", "TMPDIR": temp / "tmp"}
    for key, path in paths.items():
        path.mkdir(parents=True, exist_ok=True)
        result[key] = str(path)
    result.update(JAVA_TOOL_OPTIONS="-Duser.home=" + str(home),
                  ADB_SERVER_SOCKET="tcp:127.0.0.1:" + str(adb_port),
                  ANDROID_ADB_SERVER_ADDRESS="127.0.0.1", ANDROID_ADB_SERVER_PORT=str(adb_port),
                  ADB_MDNS_AUTO_CONNECT="0", ADB_LOCAL_TRANSPORT_MAX_PORT="0")
    return result


class Controller:
    def __init__(self, root=ROOT, env=None, clock=time.monotonic):
        self.root, self.env, self.clock = root, dict(os.environ if env is None else env), clock
        self.started = clock()
        self.ends = self.started + BUDGETS["controller"]
        self.receipt = base_receipt()
        self.emulator = None
        self.emulator_output = None
        self.adb_server = None
        self.adb_server_output = None
        self.temporary = None
        self.interrupted_signal = 0
        self.watchdog_expired = False
        self.emulator_library_env = {}

    def remaining(self, seconds):
        if self.interrupted_signal:
            raise Cancelled("Signal " + str(self.interrupted_signal))
        if self.watchdog_expired:
            raise Deadline("Controller's 72-minute working deadline reached")
        remaining = min(seconds, self.ends - self.clock())
        if remaining <= 0:
            raise Deadline("Controller deadline reached")
        return remaining

    def command(self, label, args, timeout=60, limit=LOG_LIMIT, check=True, stdin=None,
                env=None, cwd=None, binary=False, guard=None, identity=None, raw_capture=None):
        expires = self.clock() + self.remaining(timeout)
        proc, data = None, bytearray()
        try:
            if guard is not None:
                guard()
            proc = subprocess.Popen([str(x) for x in args], cwd=self.root if cwd is None else cwd,
                                    env=self.env if env is None else env,
                                    stdin=subprocess.DEVNULL if stdin is None else subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    start_new_session=True)
            if identity is not None:
                identity.update(pid=proc.pid, complete=False)
            if stdin is not None:
                proc.stdin.write(stdin)
                proc.stdin.close()
            os.set_blocking(proc.stdout.fileno(), False)
            with selectors.DefaultSelector() as selector:
                selector.register(proc.stdout, selectors.EVENT_READ)
                eof = False
                while True:
                    if self.clock() >= expires:
                        raise Deadline(label + " timed out")
                    self.remaining(1)
                    if guard is not None:
                        guard()
                    self.drain_background()
                    for key, _ in selector.select(min(0.2, expires - self.clock())) if not eof else []:
                        chunk = os.read(key.fd, 65536)
                        if not chunk:
                            selector.unregister(proc.stdout)
                            eof = True
                        elif len(data) + len(chunk) > limit:
                            if raw_capture is not None:
                                data.extend(chunk[:max(0, limit - len(data))])
                            raise Failure(label + " exceeded output limit")
                        else:
                            data.extend(chunk)
                    exited = os.waitid(os.P_PID, proc.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                    if eof and exited is not None:
                        if guard is not None:
                            guard()
                        code = exited.si_status if exited.si_code == os.CLD_EXITED else 128 + exited.si_status
                        if identity is not None:
                            identity.update(exit=code, complete=True)
                        text = bytes(data) if binary else data.decode("utf-8", "strict")
                        if check and code:
                            raise Failure(label + " failed with exit " + str(code))
                        return text, code
                    if eof:
                        time.sleep(min(0.1, max(0, expires - self.clock())))
        finally:
            if raw_capture is not None:
                raw_capture[:] = data
            if identity is not None:
                identity.update(outputBytes=len(data), outputSha256=hashlib.sha256(data).hexdigest())
            if proc is not None:
                stop_owned(proc)
                proc.stdout.close()
            # Successful polling is quiet; all other bounded diagnostics are retained.
            if label != "boot-poll":
                if label == "emulator-loader-probe":
                    emit_raw_loader_trace(bytes(data), identity is not None and identity.get("complete") is True)
                emit(label, (f"binary bytes={len(data)} sha256={hashlib.sha256(data).hexdigest()}"
                             if binary else bytes(data)),
                     limit=LOADER_TRACE_LIMIT if label == "emulator-loader-probe" else 16384)

    def git(self, *args):
        return self.command("source", ["git", *args])[0].strip()

    def source_identity(self):
        actual = self.git("rev-parse", "HEAD")
        if actual != self.env["GITHUB_SHA"] or self.git("status", "--porcelain=v1", "--untracked-files=all"):
            raise Failure("Source is not a clean checkout of the dispatched SHA")
        self.command("source-manifest", [sys.executable, "tools/source_manifest.py", "verify", "--source", "HEAD"])
        self.receipt["sourceSha"] = actual
        self.receipt["sourceManifestSha256"] = sha256(self.root / "SOURCE_MANIFEST.sha256")

    def prepare_host_libraries(self):
        deadline = self.clock() + self.remaining(BUDGETS["hostLibraries"])
        def check_phase():
            self.remaining(1)
            left = deadline - self.clock()
            if left <= 0:
                raise Deadline("Private host-library preparation exceeded five minutes")
            return left
        def digest(path):
            return sha256(path, check_phase)
        def run(label, args, **kwargs):
            left = check_phase()
            return self.command(label, args, timeout=min(120, left), **kwargs)
        if any(value for key, value in self.env.items() if key.startswith("LD_") or key in
               ("GLIBC_TUNABLES", "ANDROID_EMULATOR_USE_SYSTEM_LIBS")):
            raise Blocked("Inherited loader overrides are not permitted for this diagnostic")
        ldd, readelf = require_executable(Path("/usr/bin/ldd")), require_executable(Path("/usr/bin/readelf"))
        sdk_root = Path(self.emulator_path).parent.resolve()
        details = {"status": "PROBING", "launcherProbe": {}, "packages": [], "selectedLibraries": {}}
        self.receipt["hostLibraries"] = details
        probe, code = run("emulator-loader-probe", [self.emulator_path, "-no-window", "-gpu", "software",
                         "-verbose", "-version"], env={**self.env, "LD_DEBUG": "libs"}, check=False,
                         limit=LOADER_TRACE_LIMIT, identity=details["launcherProbe"])
        details.update(launcherProbeSha256=hashlib.sha256(probe.encode()).hexdigest(),
                       launcherProbeBytes=len(probe.encode()), launcherProbeExit=code)
        if code not in (0, 127):
            raise Blocked("Version-only launcher probe failed for a non-loader reason")
        backend, vendor_dirs = observed_loader_paths(probe, sdk_root, details["launcherProbe"].get("pid"), code, details)
        targets = {Path(self.emulator_path).resolve(), backend}
        for directory in (sdk_root / "lib64/gles_swiftshader", sdk_root / "lib64/vulkan"):
            if directory.is_dir():
                for candidate in directory.glob("*.so*"):
                    if candidate.is_file():
                        if not candidate.resolve().is_relative_to(sdk_root):
                            raise Blocked("SDK renderer library escapes SDK directory")
                        targets.add(candidate.resolve())
        if len(targets) > 16:
            raise Blocked("Unexpectedly large emulator/renderer ELF target set")
        for target in targets:
            verify_x86_64_shared_object(target, executable=target in (backend, Path(self.emulator_path).resolve()))
        details.update(status="CHECKING", vendorLibraryDirs=[str(p.relative_to(sdk_root)) for p in vendor_dirs],
            targets={str(p.relative_to(sdk_root)): digest(p) for p in sorted(targets)},
            loaderToolSha256={Path(tool).name: digest(tool) for tool in (ldd, readelf)})
        overlay = self.temp / "emulator-library-overlay"
        overlay.mkdir()
        trace_env = {**self.env, "LC_ALL": "C"}
        if vendor_dirs:
            trace_env["LD_LIBRARY_PATH"] = os.pathsep.join(map(str, vendor_dirs))
        def scan():
            missing, resolved, transcripts = set(), {}, []
            for target in sorted(targets):
                text, _ = run("emulator-elf-closure", [ldd, target], env=trace_env)
                missing.update(loader_missing(text, resolved))
                transcripts.append(text)
            if missing.intersection(resolved):
                raise Blocked("A missing SONAME already has a provider for another selected target")
            return missing, resolved, hashlib.sha256("\n".join(transcripts).encode()).hexdigest()
        missing, original_resolved, before_hash = scan()
        details.update(initialMissing=sorted(missing), beforeClosureSha256=before_hash)
        if not missing:
            if code != 0:
                raise Blocked("Launcher failed despite complete traced ELF closure")
            check_phase()
            details.update(status="PREINSTALLED", afterClosureSha256=before_hash)
            return
        if missing != {"libpulse.so.0"}:
            raise Blocked("Unreviewed host dependencies: " + ", ".join(sorted(missing)))
        apt_get = require_executable(Path("/usr/bin/apt-get"))
        apt_cache = require_executable(Path("/usr/bin/apt-cache"))
        gpgv = require_executable(Path("/usr/bin/gpgv"))
        dpkg_deb = require_executable(Path("/usr/bin/dpkg-deb"))
        keyring, status = Path("/usr/share/keyrings/ubuntu-archive-keyring.gpg"), Path("/var/lib/dpkg/status")
        apt = self.temp / "private-apt"
        config = private_apt_config(apt, keyring, status)
        status_hash = digest(status)
        if digest(apt / "status") != status_hash or digest(apt / "ubuntu-archive-keyring.gpg") != digest(keyring):
            raise Blocked("Host package status or existing archive keyring changed during private snapshot")
        stable = {path: digest(path) for path in (config, apt / "ubuntu.sources", apt / "status", apt / "ubuntu-archive-keyring.gpg")}
        apt_env = {**self.env, "LC_ALL": "C", "APT_CONFIG": str(config), "GNUPGHOME": str(apt / "gnupg")}
        details.update(archiveKeyringSha256=digest(keyring), aptConfigurationSha256=digest(config),
                       aptSourcesSha256=digest(apt / "ubuntu.sources"), hostPackageStatusSha256=status_hash)
        details["toolSha256"] = {Path(tool).name: digest(tool) for tool in (apt_get, apt_cache, gpgv, dpkg_deb, ldd, readelf)}
        metadata_guard = lambda: check_metadata_storage(apt / "lists")
        update, _ = run("private-apt-signed-metadata", [apt_get, "update"], env=apt_env, guard=metadata_guard)
        metadata_guard()
        details["metadataStorageGuard"] = {"sampledByteLimit": HOST_METADATA_LIMIT, "entryLimit": 128}
        if re.search(r"(?mi)^(W:|E:)|NO_PUBKEY|BADSIG|EXPKEYSIG|Failed to fetch|not authenticated|not signed", update):
            raise Blocked("APT metadata update warned or did not authenticate every index")
        releases = sorted((apt / "lists").glob("*_InRelease"))
        if len(releases) != 3:
            raise Blocked("Expected exactly Noble, Noble updates and Noble security signed releases")
        details["signedReleases"] = []
        for release in releases:
            verified, _ = run("ubuntu-release-signature", [gpgv, "--homedir", apt / "gnupg", "--keyring",
                apt / "ubuntu-archive-keyring.gpg", "--status-fd", "1", release], env=apt_env)
            fingerprints = re.findall(r"(?m)^\[GNUPG:\] VALIDSIG ([0-9A-F]{40}) ", verified)
            if not fingerprints:
                raise Blocked("Missing independently verified Ubuntu archive signature")
            details["signedReleases"].append({"file": release.name, "sha256": digest(release), "signers": fingerprints})
        simulated, _ = run("private-apt-plan", [apt_get, "--simulate", "--no-install-recommends", "--no-upgrade",
                             "install", "libpulse0:amd64"], env=apt_env)
        plan = apt_plan(simulated)
        for name, expected in sorted(plan.items()):
            metadata, _ = run("private-apt-package-metadata", [apt_cache, "show", name + "=" + expected["version"]], env=apt_env)
            details["packages"].append(checked_package(metadata, name, expected))
        if sum(package["bytes"] for package in details["packages"]) > HOST_ARCHIVE_LIMIT:
            raise Blocked("Private dependency download exceeds 32 MiB")
        run("private-apt-download-only", [apt_get, "download", *[name + "=" + record["version"]
             for name, record in sorted(plan.items())]], env=apt_env, cwd=apt / "downloads")
        archives = list((apt / "downloads").iterdir())
        if len(archives) != len(plan) or any(not p.is_file() or p.is_symlink() or p.suffix != ".deb" for p in archives):
            raise Blocked("Unexpected dependency download contents")
        candidates, expanded = {}, 0
        for package in details["packages"]:
            matches = [path for path in archives if path.stat().st_size == package["bytes"] and digest(path) == package["sha256"]]
            if len(matches) != 1:
                raise Blocked("Downloaded dependency does not match authenticated size/SHA256")
            archive = matches[0]
            fields, _ = run("private-deb-identity", [dpkg_deb, "--field", archive, "Package", "Version", "Architecture"])
            if deb_fields(fields) != {"Package": package["package"], "Version": package["version"], "Architecture": package["architecture"]}:
                raise Blocked("Downloaded package control identity conflicts with signed metadata")
            payload, _ = run("private-deb-data-only", [dpkg_deb, "--fsys-tarfile", archive], binary=True, limit=HOST_EXPANDED_LIMIT)
            expanded += len(payload)
            if expanded > HOST_EXPANDED_LIMIT:
                raise Blocked("Dependency closure expansion exceeds 64 MiB")
            destination = apt / ("package-" + package["package"])
            extract_library_archive(payload, destination, check_phase)
            for subdir in ("usr/lib/x86_64-linux-gnu", "lib/x86_64-linux-gnu"):
                for candidate in (destination / subdir).rglob("*.so*"):
                    if not candidate.is_file():
                        continue
                    actual = candidate.resolve()
                    if not actual.is_relative_to(destination):
                        raise Blocked("Dependency library escapes its owned extraction")
                    verify_x86_64_shared_object(actual)
                    dynamic, _ = run("private-library-soname", [readelf, "-d", actual])
                    sonames = re.findall(r"\(SONAME\).*\[([^\]]+)\]", dynamic)
                    if len(sonames) != 1 or not SONAME.fullmatch(sonames[0]):
                        raise Blocked("Dependency library lacks one canonical SONAME")
                    name = sonames[0]
                    if re.match(r"^(ld-|lib(?:c\.|m\.|dl\.|rt\.|pthread\.|resolv\.|gcc|stdc\+\+|ssl|crypto|nss|pam|selinux|seccomp|cap|audit|systemd))", name):
                        raise Blocked("Core or security-critical library is outside this scoped dependency repair")
                    value = (actual, package["package"])
                    if name in candidates and candidates[name] != value:
                        raise Blocked("Ambiguous dependency SONAME provider")
                    candidates[name] = value
        trace_env["LD_LIBRARY_PATH"] = os.pathsep.join(map(str, [*vendor_dirs, overlay]))
        for _ in range(16):
            if not missing:
                break
            for name in sorted(missing):
                if name not in candidates or name in details["selectedLibraries"] or name in original_resolved:
                    raise Blocked("Unresolved dependency or attempted existing-provider override: " + name)
                path, package_name = candidates[name]
                (overlay / name).symlink_to(path)
                details["selectedLibraries"][name] = {"package": package_name, "sha256": digest(path)}
            missing, resolved, after_hash = scan()
            if any(name not in resolved or Path(resolved[name]).resolve() != Path(path).resolve()
                   for name, path in original_resolved.items()):
                raise Blocked("Private overlay changed an existing host or SDK SONAME provider")
            for name in details["selectedLibraries"]:
                if name not in resolved or Path(resolved[name]).resolve() != candidates[name][0]:
                    raise Blocked("Selected dependency did not resolve to its exact owned provider")
        else:
            raise Blocked("Dependency closure exceeds bounded resolution depth")
        if missing or digest(status) != status_hash or any(digest(path) != value for path, value in stable.items()):
            raise Blocked("Incomplete dependency closure or changed host package database")
        check_phase()
        details.update(status="RESOLVED_PRIVATE", afterClosureSha256=after_hash)
        # Only emulator/version receives the overlay. Its launcher adds observed vendor dirs itself.
        self.emulator_library_env = {"LD_LIBRARY_PATH": str(overlay)}

    def prerequisites(self):
        event = json.loads(Path(self.env["GITHUB_EVENT_PATH"]).read_text())
        launch = validate_policy(self.env, event)
        os_release = dict(line.split("=", 1) for line in Path("/etc/os-release").read_text().splitlines() if "=" in line)
        if os_release.get("ID", "").strip('"') != "ubuntu" or os_release.get("VERSION_ID", "").strip('"') != "24.04":
            raise Blocked("Requires the standard ubuntu-24.04 runner")
        if platform.machine() != "x86_64":
            raise Blocked("Host architecture is not x86_64")
        self.source_identity()
        if launch["event"] == "push":
            launch["changedPaths"] = validate_push_tree(self.env["GITHUB_SHA"],
                self.git("rev-list", "--parents", "-n", "1", "HEAD"),
                self.git("log", "-1", "--format=%B"),
                self.git("diff", "--name-status", "--no-renames", "-z", PUSH_BASE_SHA, "HEAD", "--"))
        self.receipt["launchPolicy"] = launch
        self.receipt["runnerImage"] = {key: self.env.get(key, "unknown") for key in ("ImageOS", "ImageVersion")}
        self.receipt["runIdentity"] = {key: self.env.get(key, "") for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")}
        sdk_value = self.env.get("ANDROID_HOME")
        if not sdk_value or (self.env.get("ANDROID_SDK_ROOT") not in (None, "", sdk_value)):
            raise Blocked("Preinstalled ANDROID_HOME missing or conflicts with ANDROID_SDK_ROOT")
        sdk = Path(sdk_value)
        self.sdk = sdk
        self.sdkmanager = require_executable(sdk / "cmdline-tools/latest/bin/sdkmanager")
        self.avdmanager = require_executable(sdk / "cmdline-tools/latest/bin/avdmanager")
        self.apkanalyzer = require_executable(sdk / "cmdline-tools/latest/bin/apkanalyzer")
        command_line_properties = sdk / "cmdline-tools/latest/source.properties"
        if not command_line_properties.is_file():
            raise Blocked("Preinstalled command-line tools metadata missing")
        self.receipt["commandLineTools"] = {
            "sourcePropertiesSha256": sha256(command_line_properties),
            "properties": command_line_properties.read_text()[:2048]}
        java = Path(self.env.get("JAVA_HOME_17_X64", "/missing-java-17"))
        require_executable(java / "bin/java")
        self.env["JAVA_HOME"] = str(java)
        self.env["PATH"] = str(java / "bin") + os.pathsep + self.env.get("PATH", "")
        self.env["ANDROID_SDK_ROOT"] = str(sdk)
        self.licenses = license_snapshot(sdk)
        self.receipt["preexistingLicenseSha256"] = self.licenses
        self.command("java-version", [java / "bin/java", "-version"])
        self.temporary = tempfile.TemporaryDirectory(prefix="rf-software-", dir=self.env["RUNNER_TEMP"])
        self.temp = Path(self.temporary.name)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
            reservation.bind(("127.0.0.1", 0))
            self.adb_port = reservation.getsockname()[1]
        if self.adb_port == 5037:
            raise Blocked("OS unexpectedly selected the default/shared ADB port")
        self.env = private_environment(self.env, self.temp, self.adb_port)
        # No SDK setup action, sudo, license acceptance, global process cleanup or caches.
        missing = [p for p in PACKAGES if not sdk.joinpath(*p.split(";"), "package.xml").is_file()]
        if missing:
            try:
                output, code = self.command("sdk-existing-licenses-only", [self.sdkmanager, "--sdk_root=" + str(sdk),
                                                                          "--channel=0", *missing],
                                            timeout=BUDGETS["sdkInstall"], check=False)
            finally:
                verify_license_snapshot(sdk, self.licenses)
            if code or re.search(r"not accepted|Accept\?\s*\(|licenses?\s+have not been accepted", output, re.I):
                raise Blocked("SDK download failed or requested unaccepted terms; stdin was closed")
        self.receipt["sdkPackages"] = [package_metadata(sdk, p) for p in PACKAGES]
        self.adb = require_executable(sdk / "platform-tools/adb")
        self.emulator_path = require_executable(sdk / "emulator/emulator")
        self.receipt["toolSha256"] = {"adb": sha256(self.adb), "emulator": sha256(self.emulator_path)}
        self.prepare_host_libraries()
        self.receipt["emulatorVersion"] = self.command("emulator-version", [self.emulator_path, "-no-window",
            "-gpu", "software", "-version"], env={**self.env, **self.emulator_library_env})[0].strip()[:2048]
        self.receipt["adbVersion"] = self.command("adb-version", [self.adb, "version"])[0].strip()[:2048]
        image = sdk.joinpath(*IMAGE.split(";"))
        props = dict(line.split("=", 1) for line in (image / "source.properties").read_text().splitlines() if "=" in line)
        validate_image_metadata(props, next(p for p in self.receipt["sdkPackages"] if p["package"] == IMAGE))
        self.receipt["systemImageSha256"] = {
            name: sha256(image / name, lambda: self.remaining(1))
            for name in ("system.img", "kernel-ranchu", "ramdisk.img")}
        if (image / "vendor.img").is_file():
            self.receipt["systemImageSha256"]["vendor.img"] = sha256(image / "vendor.img", lambda: self.remaining(1))
        if shutil.disk_usage(self.temp).free < 5 * 1024**3:
            raise Blocked("Less than 5 GiB free for the bounded build/AVD experiment")

    def build(self):
        self.app = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        self.test = self.root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        if self.app.exists() or self.test.exists():
            raise Failure("Pre-existing APK output cannot be used as fresh candidate evidence")
        try:
            self.command("gradle-build", [self.root / "gradlew", "--no-daemon", "--no-build-cache",
                        "--max-workers=2", "--no-parallel", "--dependency-verification", "strict",
                        "-Pandroid.builder.sdkDownload=false", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
                         timeout=BUDGETS["build"])
        finally:
            verify_license_snapshot(self.sdk, self.licenses)
        self.source_identity()
        validate_apk_manifests(
            self.command("app-manifest", [self.apkanalyzer, "manifest", "print", self.app])[0],
            self.command("test-manifest", [self.apkanalyzer, "manifest", "print", self.test])[0],
            java_notice="Picked up JAVA_TOOL_OPTIONS: " + self.env["JAVA_TOOL_OPTIONS"])
        with zipfile.ZipFile(self.app) as archive:
            native = archive.read("lib/x86_64/librealityforge.so")
        self.receipt.update(appSha256=sha256(self.app), testSha256=sha256(self.test),
                            nativeX8664Sha256=hashlib.sha256(native).hexdigest())

    def shell(self, *args, **kwargs):
        return self.command(kwargs.pop("label", "adb-shell"), self.adb_args("-s", SERIAL, "shell", *args), **kwargs)[0].strip()

    def adb_args(self, *args):
        if (self.adb_server is None
                or os.waitid(os.P_PID, self.adb_server.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None
                or not process_listens_on_loopback(self.adb_server.pid, self.adb_port)):
            raise Failure("Task-owned foreground ADB server is not alive and listening")
        # Explicit numeric loopback host is the ADB client's non-autostart path.
        # Do not use localhost/default socket: those can auto-daemonize on failure.
        return [self.adb, "-H", "127.0.0.1", "-P", str(self.adb_port), *args]

    def start_private_adb(self):
        self.adb_server_output = tempfile.TemporaryFile(dir=self.temp)
        no_usb = "RF_DIAGNOSTIC_NO_USB_" + uuid.uuid4().hex
        args = [self.adb, "-L", "tcp:localhost:" + str(self.adb_port),
                "--one-device", no_usb, "server", "nodaemon"]
        self.adb_server = subprocess.Popen(args, cwd=self.root, env=self.env, stdin=subprocess.DEVNULL,
                                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                           start_new_session=True)
        os.set_blocking(self.adb_server.stdout.fileno(), False)
        deadline = self.clock() + self.remaining(30)
        while self.clock() < deadline:
            self.remaining(1)
            self.drain_background()
            if os.waitid(os.P_PID, self.adb_server.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None:
                raise Blocked("Task-owned foreground ADB server could not start")
            if process_listens_on_loopback(self.adb_server.pid, self.adb_port):
                self.receipt["adbServer"] = {"pid": self.adb_server.pid, "port": self.adb_port,
                    "mode": "owned-foreground-loopback", "socketOwnership": "verified-/proc-inode",
                    "mdnsAutoConnect": False, "emulatorPortScanning": False, "usbSelector": no_usb,
                    "statePersistence": "disposable-runner-temp-only"}
                return
            time.sleep(.1)
        raise Deadline("Private ADB server did not establish an owned listener in 30 seconds")

    def boot(self):
        if shutil.disk_usage(self.temp).free < 3 * 1024**3:
            raise Blocked("Less than 3 GiB remains for disposable AVD storage")
        name = "rf_software_" + self.env["GITHUB_RUN_ID"] + "_" + self.env["GITHUB_RUN_ATTEMPT"]
        if not re.fullmatch(r"rf_software_[0-9]+_[0-9]+", name):
            raise Blocked("Missing valid run identity for a fresh AVD")
        self.command("create-disposable-avd", [self.avdmanager, "create", "avd", "-n", name, "-k", IMAGE,
                                              "-p", self.temp / "device.avd"], stdin=b"no\n")
        self.start_private_adb()
        listing = self.command("adb-devices-before", self.adb_args("devices"))[0]
        if listing.strip() != "List of devices attached":
            raise Blocked("Fresh private ADB server unexpectedly discovered a device")
        # Detect an existing service without contacting it; never commandeer its ports.
        for port in (5580, 5581):
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
                try:
                    reservation.bind(("127.0.0.1", port))
                except OSError as error:
                    raise Blocked("Disposable emulator port is unavailable: " + str(port)) from error
        self.env["ANDROID_I_WANT_MY_TCG"] = "yes"
        args = [self.emulator_path, "-avd", name, "-accel", "off", "-gpu", "software",
                "-no-window", "-no-snapshot", "-no-boot-anim", "-no-audio", "-memory", "2048",
                "-cores", "2", "-port", "5580", "-camera-back", "none", "-camera-front", "none"]
        self.receipt["emulatorOptions"] = args[1:]
        self.emulator_output = tempfile.TemporaryFile(dir=self.temp)
        self.emulator = subprocess.Popen(args, cwd=self.root, env={**self.env, **self.emulator_library_env}, stdin=subprocess.DEVNULL,
                                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        os.set_blocking(self.emulator.stdout.fileno(), False)
        self.boot_end = self.clock() + self.remaining(BUDGETS["boot"])
        while self.clock() < self.boot_end:
            self.drain_background()
            exited = os.waitid(os.P_PID, self.emulator.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            if exited is not None:
                raise Failure("Owned software emulator exited before boot completed")
            # No device-directed command until the owned emulator owns its ADB port.
            if not process_listens_on_loopback(self.emulator.pid, 5581):
                time.sleep(min(1, max(0, self.boot_end-self.clock())))
                continue
            text, code = self.command("boot-poll", self.adb_args("-s", SERIAL, "shell", "getprop", "sys.boot_completed"),
                                      timeout=min(10, max(.1, self.boot_end-self.clock())), check=False)
            if code == 0 and text.strip() == "1":
                break
            time.sleep(min(5, max(0, self.boot_end-self.clock())))
        else:
            raise Deadline("Software emulator did not boot within 30 minutes")
        # Verify Android identity; neither a physical device nor translated ARM is accepted.
        identity = {"serial": SERIAL, "api": self.shell("getprop", "ro.build.version.sdk"),
                    "abi": self.shell("getprop", "ro.product.cpu.abi"),
                    "emulator": self.shell("getprop", "ro.kernel.qemu"),
                    "pageSize": self.shell("getconf", "PAGESIZE"),
                    "fingerprint": self.shell("getprop", "ro.build.fingerprint")}
        if (identity["api"] != "35" or identity["abi"] != "x86_64" or identity["emulator"] != "1"
                or identity["pageSize"] != "4096" or not identity["fingerprint"]):
            raise Failure("Measured device differs from focused API35 x86_64 4KiB emulator")
        self.receipt["device"] = identity
        self.factory_evidence()

    def drain_background(self):
        for proc, output, label in ((self.emulator, self.emulator_output, "Emulator"),
                                    (self.adb_server, self.adb_server_output, "Private ADB server")):
            if proc is None or proc.stdout.closed:
                continue
            while True:
                try:
                    chunk = os.read(proc.stdout.fileno(), 65536)
                except BlockingIOError:
                    break
                if not chunk:
                    break
                if output.tell() + len(chunk) > LOG_LIMIT:
                    raise Failure(label + " diagnostic output exceeded 4 MiB")
                output.write(chunk)

    def installed_hash(self, package):
        path = self.shell("pm", "path", package).splitlines()
        if (len(path) != 1 or not re.fullmatch(r"package:/data/app/[A-Za-z0-9_~+=/.-]+/base\.apk", path[0])
                or any(part in (".", "..") for part in path[0][8:].split("/"))):
            raise Failure("Installed package is not one verifiable base APK")
        line = self.shell("sha256sum", path[0][8:])
        if not re.fullmatch(r"[0-9a-f]{64}\s+" + re.escape(path[0][8:]), line):
            raise Failure("Malformed installed APK hash")
        return line[:64]

    def candidate_setup_command(self, label, args, budget, record):
        """One observed command, no retry; partial metadata survives timeout/cancellation."""
        started = self.clock()
        record.update(stage=label, status="RUNNING", process={},
                      startedAtUtc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                      elapsedAtStartSeconds=round(started - self.started, 3), budgetSeconds=budget)
        try:
            record["effectiveTimeoutSeconds"] = self.remaining(budget)
            print("RF_CANDIDATE_SETUP_START " + json.dumps(record, sort_keys=True), flush=True)
            result = self.command(label, args, timeout=record["effectiveTimeoutSeconds"], identity=record["process"])
            record["status"] = "COMMAND_SUCCEEDED"
            return result
        except Exception as error:
            record.update(status=getattr(error, "status", "FAIL"), error=str(error)[:1024])
            raise
        finally:
            record.update(elapsedSeconds=round(self.clock() - started, 3),
                          finishedAtUtc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
            print("RF_CANDIDATE_SETUP_FINISH " + json.dumps(record, sort_keys=True), flush=True)

    def install_candidates(self):
        self.receipt["candidateInstallations"] = []
        probe = {"package": "android", "expectedPath": "/system/framework/framework-res.apk"}
        self.receipt["packageManagerProbe"] = probe
        output, _ = self.candidate_setup_command("package-manager-response",
            self.adb_args("-s", SERIAL, "shell", "cmd", "package", "path", "android"),
            BUDGETS["packageManagerProbe"], probe)
        if output.strip() != "package:" + probe["expectedPath"]:
            probe["status"] = "UNEXPECTED_RESPONSE"
            raise Failure("PackageManager did not return the exact system framework package path")
        probe["status"] = "RESPONSIVE"  # Not proof of idle state or future install readiness.
        for label, apk, package, hash_key in (("install-app", self.app, APP, "appSha256"),
                                             ("install-test", self.test, APP + ".test", "testSha256")):
            if (not apk.is_file() or apk.is_symlink()
                    or sha256(apk, lambda: self.remaining(1)) != self.receipt[hash_key]):
                raise Failure("Built APK identity changed before candidate installation")
            record = {"package": package, "apkBytes": apk.stat().st_size, "apkSha256": self.receipt[hash_key]}
            self.receipt["candidateInstallations"].append(record)
            self.candidate_setup_command(label, self.adb_args("-s", SERIAL, "install", "-t", apk),
                                         BUDGETS["apkInstall"], record)

    def factory_evidence(self):
        deadline = self.clock() + self.remaining(BUDGETS["deviceFactoryEvidence"])
        record = {"properties": {}, "globalSettings": {}, "status": "RUNNING"}
        self.receipt["deviceFactoryEvidence"] = record
        for key in ("ro.hw_timeout_multiplier", "ro.debuggable", "ro.build.type", "ro.build.tags",
                    "ro.test_harness", "debug.hwui.drawing_enabled"):
            record["properties"][key] = self.shell("getprop", key,
                timeout=self.remaining(deadline - self.clock()), limit=2048)
        for key in ("debug_app", "wait_for_debugger", "watchdog_timeout_millis"):
            record["globalSettings"][key] = self.shell("settings", "get", "global", key,
                timeout=self.remaining(deadline - self.clock()), limit=2048)
        validate_factory_evidence(record)
        record["status"] = "RECORDED"

    def device_clock_sample(self, deadline=None, guard=None):
        """Read-only device UTC anchor; unavailable metadata never invents a time."""
        record = {"status": "UNAVAILABLE", "process": {}}
        try:
            timeout = self.remaining(BUDGETS["deviceClockSample"])
            if deadline is not None:
                timeout = min(timeout, self.remaining(deadline - self.clock()))
            if guard is not None:
                guard()
            output, code = self.command("device-clock", self.adb_args("-s", SERIAL, "shell", "date",
                "-u", "+%Y-%m-%dT%H:%M:%SZ"), timeout=timeout, limit=256, check=False,
                identity=record["process"], guard=guard)
            value = output.strip()
            if (code == 0 and record["process"].get("complete") is True
                    and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", value)):
                stamp = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()
                record.update(status="CAPTURED", utc=value, epochSeconds=stamp, sampleMonotonic=self.clock())
        except (Cancelled, Deadline):
            self.remaining(1)  # Propagate external cancellation/global expiry, not a local probe timeout.
        except Exception as error:
            record["errorType"] = type(error).__name__
        return record

    def capture_last_anr_traces(self, original_error, logcat, deadline):
        """Read the service's latest trace once, sharing the existing45s failure budget."""
        started, raw = self.clock(), bytearray()
        context = self.receipt["crashContext"]
        record = {"status": "PENDING", "scope": "diagnostic-only-not-test-proof", "process": {},
                  "limitBytes": CRASH_CONTEXT_LIMIT, "budgetPolicy": "shared-crashContext-deadline",
                  "sourceSha": context["sourceSha"], "serial": SERIAL,
                  "installedSha256": context["installedSha256"],
                  "instrumentationProcess": context["instrumentationProcess"],
                  "association": {"status": "UNASSOCIATED"}}
        self.receipt["lastAnrTraces"] = record
        def owned_device():
            self.remaining(deadline - self.clock())
            if (self.emulator is None
                    or os.waitid(os.P_PID, self.emulator.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None
                    or not process_listens_on_loopback(self.emulator.pid, 5581)):
                raise Failure("Task-owned emulator is not alive and listening")
            self.adb_args()
        try:
            if isinstance(original_error, Cancelled):
                raise Cancelled("Original instrumentation was cancelled")
            owned_device()
            if (context["status"] != "CAPTURED" or not context["captureComplete"]
                    or not re.search(rb"(?m) E ActivityManager: ANR in " + APP.encode() + rb"$", logcat)):
                record["status"] = "SKIPPED_NO_COMPLETE_SCANNER_ANR_LOG"
                return
            record["deviceClockAfterFailure"] = self.device_clock_sample(deadline, owned_device)
            record["effectiveTimeoutSeconds"] = self.remaining(deadline - self.clock())
            record["status"] = "RUNNING"
            _, code = self.command("device-last-anr-traces", self.adb_args("-s", SERIAL, "shell", "dumpsys",
                "activity", "lastanr-traces"), timeout=record["effectiveTimeoutSeconds"],
                limit=CRASH_CONTEXT_LIMIT, binary=True, check=False, guard=owned_device,
                identity=record["process"], raw_capture=raw)
            record["status"] = "CAPTURED" if code == 0 else "COMMAND_FAILED"
            if code == 0 and record["process"].get("complete") is True:
                record["association"] = associate_anr_trace(logcat, bytes(raw),
                    self.receipt.get("instrumentationDeviceClock", {}), record["deviceClockAfterFailure"])
        except Exception as error:
            record.update(status=("SKIPPED_" if record["status"] == "PENDING" else "")
                          + getattr(error, "status", "FAIL"), error=str(error)[:1024])
        finally:
            record.update(capturedBytes=len(raw), capturedSha256=hashlib.sha256(raw).hexdigest(),
                          captureComplete=record["process"].get("complete") is True,
                          displayPolicy="NOT_RENDERED", elapsedSeconds=round(self.clock() - started, 3))
            display, policy = crash_context_display(bytes(raw))
            record.update(displayPolicy=policy, displayBytes=len(display),
                          displaySha256=hashlib.sha256(display).hexdigest(), displayChanged=display != bytes(raw))
            print("RF_LAST_ANR_TRACES_META " + json.dumps(record, sort_keys=True), flush=True)
            emit("device-last-anr-traces-display", display, CRASH_CONTEXT_LIMIT)

    def capture_device_crash_context(self, original_error):
        """One read-only best-effort capture; its outcome cannot replace the test failure."""
        started, raw = self.clock(), bytearray()
        deadline = started + min(BUDGETS["crashContext"], max(0, self.ends - started))
        record = {"status": "PENDING", "scope": "diagnostic-only-not-test-proof", "process": {},
                  "budgetSeconds": BUDGETS["crashContext"], "limitBytes": CRASH_CONTEXT_LIMIT,
                  "buffers": ["crash", "main", "system"], "lastRecords": 1000,
                  "sourceSha": self.receipt.get("sourceSha"), "serial": SERIAL,
                  "installedSha256": dict(self.receipt.get("installedSha256", {})),
                  "instrumentationProcess": dict(self.receipt.get("instrumentation", {}).get("process", {})),
                  "startedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        self.receipt["crashContext"] = record
        def owned_device():
            self.remaining(1)
            if (self.emulator is None
                    or os.waitid(os.P_PID, self.emulator.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None
                    or not process_listens_on_loopback(self.emulator.pid, 5581)):
                raise Failure("Task-owned emulator is not alive and listening")
            self.adb_args()  # Requires the live owned foreground server and private socket.
        try:
            if isinstance(original_error, Cancelled):
                raise Cancelled("Original instrumentation was cancelled")
            record["effectiveTimeoutSeconds"] = self.remaining(BUDGETS["crashContext"])
            deadline = started + record["effectiveTimeoutSeconds"]
            if (self.receipt.get("device", {}).get("serial") != SERIAL
                    or not re.fullmatch(r"[0-9a-f]{40}", record["sourceSha"] or "")
                    or any(not re.fullmatch(r"[0-9a-f]{64}", self.receipt.get(key, ""))
                           or record["installedSha256"].get(key) != self.receipt[key]
                           for key in ("appSha256", "testSha256"))):
                raise Failure("Crash context lacks exact source/device/installed-APK binding")
            owned_device()
            record["effectiveTimeoutSeconds"] = self.remaining(deadline - self.clock())
            record["status"] = "RUNNING"
            _, code = self.command("device-crash-context", self.adb_args("-s", SERIAL, "shell", "logcat",
                "-b", "crash", "-b", "main", "-b", "system", "-d", "-v", "threadtime", "-v", "year", "-v", "UTC", "-t", "1000"),
                timeout=record["effectiveTimeoutSeconds"], limit=CRASH_CONTEXT_LIMIT, binary=True,
                check=False, guard=owned_device, identity=record["process"], raw_capture=raw)
            record["status"] = "CAPTURED" if code == 0 else "COMMAND_FAILED"
        except Exception as error:
            record.update(status=("SKIPPED_" if record["status"] == "PENDING" else "")
                          + getattr(error, "status", "FAIL"), error=str(error)[:1024])
        finally:
            # Hash the original bounded bytes independently of the safe display.
            record.update(capturedBytes=len(raw), capturedSha256=hashlib.sha256(raw).hexdigest(),
                          captureComplete=record["process"].get("complete") is True,
                          displayPolicy="NOT_RENDERED",
                          elapsedSeconds=round(self.clock() - started, 3),
                          finishedAtUtc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
            display, policy = crash_context_display(bytes(raw))
            record.update(displayPolicy=policy, displayBytes=len(display),
                          displaySha256=hashlib.sha256(display).hexdigest(), displayChanged=display != bytes(raw))
            print("RF_CRASH_CONTEXT_META " + json.dumps(record, sort_keys=True), flush=True)
            emit("device-crash-context-display", display, CRASH_CONTEXT_LIMIT)
            self.capture_last_anr_traces(original_error, bytes(raw), deadline)

    def tests(self):
        self.install_candidates()
        installed = {"appSha256": self.installed_hash(APP), "testSha256": self.installed_hash(APP + ".test")}
        if any(installed[key] != self.receipt[key] for key in installed):
            raise Failure("Installed APK hashes differ from this source's built candidates")
        self.receipt["installedSha256"] = installed
        for record in self.receipt["candidateInstallations"]:
            record.update(status="INSTALLED_HASH_VERIFIED", installedSha256=record["apkSha256"])
        # Drain between operations; the command loop also drains while native work runs.
        self.drain_background()
        self.receipt["instrumentationDeviceClock"] = self.device_clock_sample()
        started = self.clock()
        record = {"status": "RUNNING", "process": {}, "budgetSeconds": BUDGETS["instrumentation"],
                  "startedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        self.receipt["instrumentation"] = record
        try:
            record["effectiveTimeoutSeconds"] = self.remaining(BUDGETS["instrumentation"])
            output, _ = self.command("instrumentation", self.adb_args("-s", SERIAL, "shell", "am", "instrument", "-w", "-r",
                           "-e", "class", ",".join(EXPECTED), RUNNER), timeout=record["effectiveTimeoutSeconds"],
                                    limit=INSTRUMENTATION_LIMIT, identity=record["process"])
            emit("instrumentation-full", output, INSTRUMENTATION_LIMIT)
            emit_raw_instrumentation(output)
            proof = parse_instrumentation(output)
            record["status"] = "EVENTS_VERIFIED"
        except Exception as error:
            record.update(status=getattr(error, "status", "FAIL"), error=str(error)[:1024],
                          elapsedSeconds=round(self.clock() - started, 3),
                          finishedAtUtc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
            try:
                self.capture_device_crash_context(error)
            except Exception as capture_error:
                # Even an unexpected capture/renderer failure must not mask the original.
                self.receipt["crashContextError"] = type(capture_error).__name__
            raise
        finally:
            record.setdefault("elapsedSeconds", round(self.clock() - started, 3))
            record.setdefault("finishedAtUtc", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
        self.receipt["testProof"] = proof
        self.source_identity()
        if self.installed_hash(APP) != self.receipt["appSha256"] or self.installed_hash(APP + ".test") != self.receipt["testSha256"]:
            raise Failure("Installed candidate identity changed during tests")
        self.drain_background()
        if os.waitid(os.P_PID, self.emulator.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None:
            raise Failure("Software emulator exited during instrumentation")
        self.receipt["junitXml"] = junit_xml(proof, self.receipt)

    def run(self):
        previous = {}
        def interrupted(signum, _frame):
            # Do not throw between Popen creation and cleanup-ownership assignment.
            self.interrupted_signal = signum
        def expired(_signum, _frame):
            self.watchdog_expired = True
        try:
            print("RF_DIAGNOSTIC_START " + json.dumps(self.receipt, sort_keys=True), flush=True)
            for sig in (signal.SIGTERM, signal.SIGINT):
                previous[sig] = signal.signal(sig, interrupted)
            previous[signal.SIGALRM] = signal.signal(signal.SIGALRM, expired)
            signal.setitimer(signal.ITIMER_REAL, self.remaining(BUDGETS["controller"]))
            for name, phase in (("prerequisites", self.prerequisites), ("build", self.build),
                                ("boot", self.boot), ("tests", self.tests)):
                self.receipt["phase"] = name
                phase()
            verify_license_snapshot(self.sdk, self.licenses)
            self.remaining(1)
            self.receipt["status"] = "PASS"
        except Exception as error:
            self.receipt["status"] = getattr(error, "status", "FAIL")
            self.receipt["error"] = str(error)[:2048]
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            # Flag-only handlers stay active through cleanup and final evidence emission.
            for proc, output, label in ((self.emulator, self.emulator_output, "emulator-tail"),
                                        (self.adb_server, self.adb_server_output, "private-adb-tail")):
                try:
                    stop_owned(proc)
                    if proc is not None:
                        self.drain_background()
                        proc.stdout.close()
                    if output is not None:
                        output.seek(0)
                        emit(label, output.read(LOG_LIMIT))
                        output.close()
                except Exception as error:
                    self.receipt["status"] = "FAIL"
                    self.receipt["cleanupError"] = str(error)[:1024]
            if self.temporary is not None:
                try:
                    self.temporary.cleanup()
                except Exception as error:
                    self.receipt["status"] = "FAIL"
                    self.receipt["cleanupError"] = str(error)[:1024]
            self.receipt["elapsedSeconds"] = round(self.clock() - self.started, 3)
            self.receipt["finishedAtUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            if self.receipt["status"] == "PASS":
                try:
                    self.remaining(1)
                except (Cancelled, Deadline) as error:
                    self.receipt["status"] = error.status
                    self.receipt["error"] = str(error)
            if self.receipt["status"] == "PASS":
                print("RF_DIAGNOSTIC_JUNIT " + self.receipt.pop("junitXml"), flush=True)
            else:
                self.receipt.pop("junitXml", None)
            print("RF_DIAGNOSTIC_RECEIPT " + json.dumps(self.receipt, sort_keys=True), flush=True)
            for sig, handler in previous.items():
                signal.signal(sig, handler)
        return 0 if self.receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(Controller().run())
