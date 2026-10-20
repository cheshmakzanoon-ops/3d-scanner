"""Runner behavior using explicit adb/controller test doubles, never device proof."""
from pathlib import Path
import hashlib
import importlib.util
import json
import struct
import subprocess
import sys
import zlib

import pytest

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools/android-ui-e2e/run.py"
spec = importlib.util.spec_from_file_location("agent080_evidence", RUNNER)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def instrumentation(test="com.example.UiTest#journey", code=0, count=1, terminal=-1):
    classname, method = test.split("#")
    status = ""
    for state in (1, code):
        status += f"INSTRUMENTATION_STATUS: class={classname}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: numtests={count}\nINSTRUMENTATION_STATUS_CODE: {state}\n"
    return status + f"INSTRUMENTATION_RESULT: stream=\nOK ({count} test{'s' if count != 1 else ''})\nINSTRUMENTATION_CODE: {terminal}\n"


def png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\0\xff\0\0\xff")) + chunk(b"IEND", b"")


def executable(path, source):
    path.write_text(f"#!{sys.executable}\n" + source)
    path.chmod(0o755)
    return path


def fake_adb(tmp_path, mode="pass", physical=False):
    image = tmp_path / "test-double.png"
    image.write_bytes(png())
    trace = tmp_path / "fake-adb-commands.jsonl"
    config = {"mode": mode, "image": str(image), "trace": str(trace), "physical": physical}
    return executable(tmp_path / "fake-adb", """
import json,pathlib,shlex,sys
C=json.loads(CONFIG)
args=sys.argv[1:]
with open(C['trace'],'a') as f:f.write(json.dumps(args)+'\\n')
assert args[:2]==['-s','fixture-device']
args=args[2:]
if args==['get-state']:
 print('offline' if C['mode']=='offline' else 'device');sys.exit(0)
if args[0]=='install':
 print('Failure [INSTALL_FAILED_TEST]' if C['mode']=='install-failed' else 'Success');sys.exit(0)
if args[0]=='pull':
 if C['mode']=='pull-failed':sys.exit(1)
 pathlib.Path(args[2]).write_bytes(pathlib.Path(C['image']).read_bytes());sys.exit(0)
assert args[0]=='shell'
cmd=shlex.split(args[1])
if cmd[0]=='getprop':
 props={'ro.build.fingerprint':'vendor/real-device/release' if C['physical'] else 'generic/sdk_gphone/emulator','ro.build.version.sdk':'35','ro.product.cpu.abi':'x86_64','ro.product.manufacturer':'Synthetic Test Double','ro.product.model':'Physical Test Double' if C['physical'] else 'Emulator Test Double','ro.kernel.qemu':'0' if C['physical'] else '1','ro.boot.qemu':''}
 print(props[cmd[1]]);sys.exit(0)
if cmd[0]=='find':
 if C['mode']=='no-screenshot':sys.exit(0)
 pattern=cmd[-1]
 if C['mode']=='stale-screenshot':pattern='rf-ui-'+'0'*32+'-*.png'
 print('/data/local/tmp/'+pattern.replace('*','frame'));sys.exit(0)
if cmd[0]=='stat':
 print(pathlib.Path(C['image']).stat().st_size);sys.exit(0)
assert cmd[:2]==['am','instrument']
test=cmd[cmd.index('class')+1]
if C['mode']=='wrong-test':test='com.example.WrongTest#other'
classname,method=test.split('#')
if C['mode']=='zero':
 print('INSTRUMENTATION_RESULT: stream=\\nOK (0 tests)\\nINSTRUMENTATION_CODE: -1');sys.exit(0)
code={'skip':-3,'failure':-2}.get(C['mode'],0)
for state in (1,code):
 print('INSTRUMENTATION_STATUS: class='+classname)
 print('INSTRUMENTATION_STATUS: test='+method)
 print('INSTRUMENTATION_STATUS: numtests=1')
 print('INSTRUMENTATION_STATUS_CODE: '+str(state))
print('INSTRUMENTATION_RESULT: stream=\\nOK (1 test)')
if C['mode']!='missing-terminal':print('INSTRUMENTATION_CODE: -1')
sys.exit(1 if C['mode']=='adb-failed' else 0)
""".replace("CONFIG", repr(json.dumps(config))))


@pytest.fixture
def candidate(tmp_path):
    repo = tmp_path / "candidate"
    repo.mkdir()
    (repo / "README.md").write_text("Explicit synthetic runner fixture, not Android production source.\n")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "add", "README.md"], cwd=repo, check=True)
    subprocess.run(["git", "-c", "user.name=Runner Test", "-c", "user.email=runner-test@example.invalid", "commit", "-qm", "Synthetic runner candidate"], cwd=repo, check=True)
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    apk = tmp_path / "app.apk"
    test_apk = tmp_path / "test.apk"
    apk.write_bytes(b"Explicit fake APK input consumed only by test-double adb")
    test_apk.write_bytes(b"Explicit fake test APK input")
    receipt = tmp_path / "build.json"
    receipt.write_text(json.dumps({"source_sha": sha, "apk_sha256": module.digest(apk), "test_apk_sha256": module.digest(test_apk)}))
    return {"repo": repo, "sha": sha, "apk": apk, "test_apk": test_apk, "receipt": receipt}


def run_cli(tmp_path, candidate, mode="pass", extra=(), physical=False):
    adb = fake_adb(tmp_path, mode, physical)
    output = tmp_path / "evidence"
    args = [sys.executable, str(RUNNER), "--repo", str(candidate["repo"]), "--source-sha", candidate["sha"], "--apk", str(candidate["apk"]), "--test-apk", str(candidate["test_apk"]), "--build-receipt", str(candidate["receipt"]), "--output", str(output), "--adb", str(adb), "--serial", "fixture-device", *extra]
    completed = subprocess.run(args, capture_output=True, text=True)
    assert completed.stdout, completed.stderr
    return completed, json.loads(completed.stdout), output


def test_success_collects_exact_identity_real_png_and_keeps_other_gates_open(tmp_path, candidate):
    completed, report, output = run_cli(tmp_path, candidate)
    assert completed.returncode == 0
    assert report["status"] == "PASS" and report["assignment_status"] == "NOT_RUN"
    assert report["candidate"]["source_sha"] == candidate["sha"]
    assert report["candidate"]["apk_sha256"] == module.digest(candidate["apk"])
    assert report["device"]["emulator"]
    assert len(report["device"]["identity_sha256"]) == 64
    ran = [row for row in report["scenarios"] if row["status"] == "PASS"]
    assert len(ran) == 2
    for row in ran:
        assert row["passed"] == 1 and row["skipped"] == 0
        assert len(row["screenshots"]) == 1
        image = row["screenshots"][0]
        assert image["width"] == image["height"] == 1
        assert image["sha256"] == hashlib.sha256(png()).hexdigest()
        assert row["run_id"] in Path(image["path"]).name
    assert json.loads((output / "evidence.json").read_text()) == report


@pytest.mark.parametrize("mode", ["zero", "skip", "failure", "wrong-test", "missing-terminal", "adb-failed", "no-screenshot", "stale-screenshot", "pull-failed", "install-failed"])
def test_command_success_alone_never_qualifies_bad_runner_or_evidence(tmp_path, candidate, mode):
    completed, report, _ = run_cli(tmp_path, candidate, mode)
    assert completed.returncode == 1 and report["status"] == "FAIL"
    assert len(report["scenarios"]) == len(module.SCENARIOS)


def test_unavailable_device_is_blocked_and_installs_nothing(tmp_path, candidate):
    completed, report, _ = run_cli(tmp_path, candidate, "offline")
    assert completed.returncode == 2 and report["status"] == "BLOCKED_EXTERNAL"
    assert all(command["argv"][-1] == "get-state" for command in report["commands"])


@pytest.mark.parametrize("problem", ["stale-head", "changed-apk", "dirty-source"])
def test_stale_candidate_fails_before_any_device_operation(tmp_path, candidate, problem):
    if problem == "stale-head":
        candidate["sha"] = "0" * 40
    elif problem == "changed-apk":
        candidate["apk"].write_bytes(b"Different APK than build receipt")
    else:
        (candidate["repo"] / "Uncommitted.kt").write_text("changed source")
    completed, report, _ = run_cli(tmp_path, candidate)
    assert completed.returncode == 1 and report["status"] == "FAIL"
    assert report["commands"] == []


def test_old_evidence_directory_is_not_reused(tmp_path, candidate):
    output = tmp_path / "evidence"
    output.mkdir()
    sentinel = output / "sentinel"
    sentinel.write_text("preserve previous evidence")
    completed, report, _ = run_cli(tmp_path, candidate)
    assert completed.returncode == 1 and report["status"] == "FAIL"
    assert sentinel.read_text() == "preserve previous evidence"


def test_emulator_cannot_satisfy_physical_entrypoint(tmp_path, candidate):
    completed, report, _ = run_cli(tmp_path, candidate, extra=["--scenario", "physical_capture", "--physical-class", "com.example.PhysicalTest#capture", "--hardware-evidence-id", "physical-protocol-42"])
    assert completed.returncode == 2
    physical = next(row for row in report["scenarios"] if row["scenario"] == "physical_capture")
    assert physical["status"] == "BLOCKED_EXTERNAL"
    assert not any("instrument" in str(command["argv"]) for command in report["commands"])


def test_physical_entrypoint_requires_identified_hardware_and_capture_test(tmp_path, candidate):
    completed, report, _ = run_cli(tmp_path, candidate, physical=True, extra=["--scenario", "physical_capture"])
    assert completed.returncode == 2 and report["status"] == "BLOCKED_EXTERNAL"


def test_physical_adapter_accepts_explicit_operator_record_only_after_ui_execution(tmp_path, candidate):
    completed, report, _ = run_cli(tmp_path, candidate, physical=True, extra=["--scenario", "physical_capture", "--physical-class", "com.example.PhysicalTest#capture", "--hardware-evidence-id", "synthetic-adapter-test-only"])
    assert completed.returncode == 0 and report["status"] == "PASS"
    physical = next(row for row in report["scenarios"] if row["scenario"] == "physical_capture")
    assert physical["hardware_evidence_id"] == "synthetic-adapter-test-only"
    assert physical["physical_device_operator_identified"]
    assert not physical["synthetic"]


def controller(tmp_path, mode):
    valid_output = instrumentation()
    config = {"mode": mode, "instrumentation": valid_output}
    return executable(tmp_path / "owner088-controller-double", """
import json,sys
C=json.loads(CONFIG);request=json.load(sys.stdin)
response={'schema_version':1,'scenario':request['scenario'],'request_sha256':request['request_sha256'],'candidate':request['candidate'],'device_identity_sha256':request['device_identity_sha256'],'status':'PASS','original_hashes_before':{'photo':'a'*64},'original_hashes_after':{'photo':'a'*64},'previous_result_sha256_before':'b'*64,'previous_result_sha256_after':'b'*64,'test_identity':'com.example.UiTest#journey','instrumentation_stdout':C['instrumentation'],'instrumentation_returncode':0,'synthetic':True,'dataset_identity_sha256':'d'*64}
if C['mode']=='stale':response['request_sha256']='0'*64
if C['mode']=='changed-original':response['original_hashes_after']['photo']='c'*64
if C['mode']=='lost-previous':response.pop('previous_result_sha256_before')
if C['mode']=='no-tests':response['instrumentation_stdout']='INSTRUMENTATION_CODE: -1'
if C['mode']=='invalid-types':response['instrumentation_stdout']=8
print(json.dumps(response))
""".replace("CONFIG", repr(json.dumps(config))))


@pytest.mark.parametrize("mode", ["pass", "stale", "changed-original", "lost-previous", "no-tests", "invalid-types"])
def test_shared_controller_adapter_validates_identity_and_data_preservation(tmp_path, candidate, mode):
    external = controller(tmp_path, mode)
    completed, report, _ = run_cli(tmp_path, candidate, extra=["--scenario", "process_kill_relaunch", "--process-controller", str(external)])
    assert report["status"] == ("PASS" if mode == "pass" else "FAIL")
    assert completed.returncode == (0 if mode == "pass" else 1)
    if mode == "pass":
        row = next(row for row in report["scenarios"] if row["scenario"] == "process_kill_relaunch")
        assert row["synthetic"] is True
        assert row["dataset_identity_sha256"] == "d" * 64


def test_missing_owner088_dependency_is_explicit_unrun(tmp_path, candidate):
    completed, report, _ = run_cli(tmp_path, candidate, extra=["--scenario", "process_kill_relaunch"])
    assert completed.returncode == 3 and report["status"] == "NOT_RUN"
    assert "Agent 088" in next(row for row in report["scenarios"] if row["scenario"] == "process_kill_relaunch")["reason"]


def test_png_crc_corruption_and_dimension_bomb_rejected(tmp_path):
    image = tmp_path / "invalid.png"
    data = bytearray(png())
    data[-5] ^= 1
    image.write_bytes(data)
    with pytest.raises(module.EvidenceError, match="CRC"):
        module.validate_png(image)
    data = bytearray(png())
    struct.pack_into(">II", data, 16, 2**30, 2**30)
    struct.pack_into(">I", data, 29, zlib.crc32(data[12:29]) & 0xFFFFFFFF)
    image.write_bytes(data)
    with pytest.raises(module.EvidenceError, match="pixel budget"):
        module.validate_png(image)


def test_completion_without_start_is_not_execution_evidence():
    output = instrumentation()
    completion_only = output.split("INSTRUMENTATION_STATUS_CODE: 1\n", 1)[1]
    result = module.parse_instrumentation(completion_only, 0, "com.example.UiTest#journey")
    assert result["status"] == "FAIL"
    assert "Started and completed test identities disagree" in result["errors"]
