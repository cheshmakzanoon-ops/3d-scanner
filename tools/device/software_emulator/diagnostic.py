#!/usr/bin/env python3
"""Manual-only, non-qualifying Android TCG experiment. Never accepts SDK licenses.

All evidence is bounded normal job output; no remote artifact/cache upload. A PASS
means exactly four synthetic x86_64 runtime tests, not the existing device gate.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import selectors
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[3]
IMAGE = "system-images;android-35;google_apis;x86_64"
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
           "build": 1200, "boot": 1800, "instrumentation": 900}
INSTRUMENTATION_LIMIT = 128 * 1024
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


def emit(label, output, limit=16384):
    """Prefix every line so child output cannot issue GitHub workflow commands."""
    raw = output.encode("utf-8", "replace") if isinstance(output, str) else output
    tail = raw[-limit:].decode("utf-8", "replace")
    print("RF_DIAGNOSTIC_LOG " + label + " bytes=" + str(len(raw)), flush=True)
    for line in tail.splitlines():
        # Strip terminal control characters, while preserving printable evidence.
        line = "".join(c if c >= " " or c == "\t" else "?" for c in line)
        print("RF_DIAGNOSTIC_LOG " + label + " | " + line, flush=True)


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
    if len(raw) > INSTRUMENTATION_LIMIT:
        raise Failure("Instrumentation evidence exceeds bounded size")
    encoded = base64.b64encode(raw).decode("ascii")
    chunks = [encoded[index:index + 4096] for index in range(0, len(encoded), 4096)]
    metadata = {"encoding": "base64", "bytes": len(raw), "chunks": len(chunks),
                "sha256": hashlib.sha256(raw).hexdigest()}
    print("RF_INSTRUMENTATION_RAW_META " + json.dumps(metadata, sort_keys=True), flush=True)
    for index, chunk in enumerate(chunks, 1):
        print(f"RF_INSTRUMENTATION_RAW_CHUNK {index}/{len(chunks)} {chunk}", flush=True)


def validate_policy(env, event):
    if (env.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or env.get("RUNNER_ENVIRONMENT") != "github-hosted"
            or env.get("RUNNER_OS") != "Linux" or env.get("RUNNER_ARCH") != "X64"
            or event.get("repository", {}).get("private") is not False):
        raise Blocked("Requires manual dispatch in a public repository on standard Linux x64 GitHub hosting")
    if not re.fullmatch(r"[0-9a-f]{40}", env.get("GITHUB_SHA", "")):
        raise Blocked("Missing exact GitHub source SHA")


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

    def remaining(self, seconds):
        if self.interrupted_signal:
            raise Cancelled("Signal " + str(self.interrupted_signal))
        if self.watchdog_expired:
            raise Deadline("Controller's 72-minute working deadline reached")
        remaining = min(seconds, self.ends - self.clock())
        if remaining <= 0:
            raise Deadline("Controller deadline reached")
        return remaining

    def command(self, label, args, timeout=60, limit=LOG_LIMIT, check=True, stdin=None):
        expires = self.clock() + self.remaining(timeout)
        proc, data = None, bytearray()
        try:
            proc = subprocess.Popen([str(x) for x in args], cwd=self.root, env=self.env,
                                    stdin=subprocess.DEVNULL if stdin is None else subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    start_new_session=True)
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
                    self.drain_background()
                    for key, _ in selector.select(min(0.2, expires - self.clock())) if not eof else []:
                        chunk = os.read(key.fd, 65536)
                        if not chunk:
                            selector.unregister(proc.stdout)
                            eof = True
                        elif len(data) + len(chunk) > limit:
                            raise Failure(label + " exceeded output limit")
                        else:
                            data.extend(chunk)
                    exited = os.waitid(os.P_PID, proc.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                    if eof and exited is not None:
                        code = exited.si_status if exited.si_code == os.CLD_EXITED else 128 + exited.si_status
                        text = data.decode("utf-8", "strict")
                        if check and code:
                            raise Failure(label + " failed with exit " + str(code))
                        return text, code
                    if eof:
                        time.sleep(min(0.1, max(0, expires - self.clock())))
        finally:
            if proc is not None:
                stop_owned(proc)
                proc.stdout.close()
            # Successful polling is quiet; all other bounded diagnostics are retained.
            if label != "boot-poll":
                emit(label, bytes(data))

    def git(self, *args):
        return self.command("source", ["git", *args])[0].strip()

    def source_identity(self):
        actual = self.git("rev-parse", "HEAD")
        if actual != self.env["GITHUB_SHA"] or self.git("status", "--porcelain=v1", "--untracked-files=all"):
            raise Failure("Source is not a clean checkout of the dispatched SHA")
        self.command("source-manifest", [sys.executable, "tools/source_manifest.py", "verify", "--source", "HEAD"])
        self.receipt["sourceSha"] = actual
        self.receipt["sourceManifestSha256"] = sha256(self.root / "SOURCE_MANIFEST.sha256")

    def prerequisites(self):
        event = json.loads(Path(self.env["GITHUB_EVENT_PATH"]).read_text())
        validate_policy(self.env, event)
        os_release = dict(line.split("=", 1) for line in Path("/etc/os-release").read_text().splitlines() if "=" in line)
        if os_release.get("ID", "").strip('"') != "ubuntu" or os_release.get("VERSION_ID", "").strip('"') != "24.04":
            raise Blocked("Requires the standard ubuntu-24.04 runner")
        if platform.machine() != "x86_64":
            raise Blocked("Host architecture is not x86_64")
        self.source_identity()
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
        self.receipt["emulatorVersion"] = self.command("emulator-version", [self.emulator_path, "-version"])[0].strip()[:2048]
        self.receipt["adbVersion"] = self.command("adb-version", [self.adb, "version"])[0].strip()[:2048]
        image = sdk.joinpath(*IMAGE.split(";"))
        props = dict(line.split("=", 1) for line in (image / "source.properties").read_text().splitlines() if "=" in line)
        if props.get("AndroidVersion.ApiLevel") != "35" or props.get("SystemImage.Abi") != "x86_64" or props.get("SystemImage.TagId") != "google_apis":
            raise Blocked("System image properties do not match API35 google_apis x86_64")
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
        self.emulator = subprocess.Popen(args, cwd=self.root, env=self.env, stdin=subprocess.DEVNULL,
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

    def tests(self):
        for apk in (self.app, self.test):
            self.command("install-candidate", self.adb_args("-s", SERIAL, "install", "-t", apk), timeout=120)
        installed = {"appSha256": self.installed_hash(APP), "testSha256": self.installed_hash(APP + ".test")}
        if any(installed[key] != self.receipt[key] for key in installed):
            raise Failure("Installed APK hashes differ from this source's built candidates")
        self.receipt["installedSha256"] = installed
        # Drain between operations; the command loop also drains while native work runs.
        self.drain_background()
        output, _ = self.command("instrumentation", self.adb_args("-s", SERIAL, "shell", "am", "instrument", "-w", "-r",
                       "-e", "class", ",".join(EXPECTED), RUNNER), timeout=BUDGETS["instrumentation"],
                                limit=INSTRUMENTATION_LIMIT)
        emit("instrumentation-full", output, INSTRUMENTATION_LIMIT)
        emit_raw_instrumentation(output)
        proof = parse_instrumentation(output)
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
