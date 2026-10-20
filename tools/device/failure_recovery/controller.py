#!/usr/bin/env python3
"""Kill a held instrumented target from outside its process, then verify real recovery."""
from __future__ import annotations
import argparse
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

PACKAGE = 'com.realityforge.scanner'
TEST = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
CLASS = PACKAGE + '.qa.RecoveryStageContractTest#externalProcessBoundary'
STAGES = ('import', 'registration', 'mesh-texture', 'checkpoint', 'publication')


# Use the same raw runner protocol for UI and dedicated recovery fixtures, both
# when executed as a script and when loaded from a source-bound host harness.
_protocol_spec = importlib.util.spec_from_file_location(
    'recovery_instrumentation_protocol', Path(__file__).with_name('stdio_controller.py'))
_protocol = importlib.util.module_from_spec(_protocol_spec)
_protocol_spec.loader.exec_module(_protocol)


def validate_marker(text, nonce, stage):
    marker = json.loads(text)
    if marker.get('nonce') != nonce or marker.get('readyStage') != stage:
        raise ValueError('Readiness is absent or belongs to another run/stage')
    pid = marker.get('preparePid')
    if type(pid) is not int or pid <= 1 or not isinstance(marker.get('revision'), int) or not marker.get('originals'):
        raise ValueError('Invalid PID/revision/original readiness evidence')
    return marker


def _parse_transcript(output):
    try:
        return _protocol.parse_instrumentation(output, 0, CLASS, extra_result_keys=('recoveryReceipt',))
    except _protocol.EvidenceError as error:
        raise ValueError('Recovery requires one complete actually passing dedicated test: ' + str(error)) from error


def validate_transcript(output):
    """One exact dedicated test must really pass; ignored/assumption/partial output fails."""
    _parse_transcript(output)
    return {'tests': 1, 'passed': 1, 'skipped': 0, 'failed': 0}


def validate_result(output, marker, nonce, stage):
    result = _parse_transcript(output)['results'].get('recoveryReceipt')
    if result is None:
        raise ValueError('One exact recovery receipt required')
    if (result.get('nonce') != nonce or result.get('stage') != stage or result.get('status') != 'PASS'
            or result.get('preparePid') != marker['preparePid'] or result.get('verifyPid') == marker['preparePid']
            or type(result.get('verifyPid')) is not int or result['verifyPid'] <= 1
            or result.get('revision') != marker['revision'] or not result.get('artifactKey')):
        raise ValueError('Recovery receipt does not prove a separate matching process')
    return result


class Adb:
    def __init__(self, serial): self.prefix = ['adb', '-s', serial]
    def run(self, *args):
        result = subprocess.run(self.prefix + list(args), text=True, capture_output=True, timeout=600)
        if result.returncode: raise RuntimeError(result.stderr.strip() or result.stdout[-2000:])
        return result.stdout.strip()
    def start(self, args, stream):
        return subprocess.Popen(self.prefix + args, stdout=stream, stderr=subprocess.STDOUT)


def instrument(nonce, stage, phase):
    return ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS,
            '-e', 'recoveryNonce', nonce, '-e', 'recoveryStage', stage,
            '-e', 'recoveryPhase', phase, TEST]


def cycle(adb, nonce, stage, output, timeout=210, now=time.monotonic, pause=time.sleep, upgrade=None):
    output.mkdir(parents=True, exist_ok=True)
    process = None
    with (output / 'prepare.txt').open('w') as stream:
        try:
            process = adb.start(instrument(nonce,stage,'prepare'), stream)
            deadline = now() + timeout
            marker = None
            while now() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Prepare instrumentation exited before readiness')
                try:
                    marker = validate_marker(adb.run('shell','run-as',PACKAGE,'cat',f'files/qa-recovery/{nonce}.json'),nonce,stage)
                except (ValueError, RuntimeError):
                    pause(.2)
                    continue
                break
            if marker is None: raise RuntimeError('No matching stage readiness before timeout')
            pids = adb.run('shell','pidof',PACKAGE).split()
            if str(marker['preparePid']) not in pids:
                raise RuntimeError('Readiness PID is stale; refuse to kill')
            # run-as limits kill authority to the selected debuggable test app's UID.
            adb.run('shell','run-as',PACKAGE,'kill','-9',str(marker['preparePid']))
            process.wait(timeout=20)
            try: remaining = adb.run('shell','pidof',PACKAGE).split()
            except RuntimeError: remaining = []  # pidof exits 1 when the process is absent.
            if str(marker['preparePid']) in remaining: raise RuntimeError('Target process survived kill')
            if upgrade is not None: upgrade(adb)
            result_text = adb.run(*instrument(nonce,stage,'verify'))
            (output / 'verify.txt').write_text(result_text + '\n')
            receipt = validate_result(result_text, marker, nonce, stage)
            cleanup = adb.run(*instrument(nonce,stage,'cleanup'))
            (output / 'cleanup.txt').write_text(cleanup + '\n')
            validate_transcript(cleanup)
            return receipt
        finally:
            if process is not None and process.poll() is None:
                process.terminate()  # Only the adb client, never arbitrary device PIDs.
                process.wait(timeout=20)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--source-sha', required=True, help='Immutable40hex candidate source commit; APK/source mapping supplied by operator')
    parser.add_argument('--stage', choices=STAGES, required=True)
    parser.add_argument('--app-apk', type=Path, required=True, help='Already installed debuggable APK identity')
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--upgrade-app-apk', type=Path)
    parser.add_argument('--upgrade-test-apk', type=Path)
    args = parser.parse_args()
    receipt = {'schema':1, 'status':'FAIL', 'serial':args.serial, 'stage':args.stage, 'synthetic':True,
               'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    args.output.mkdir(parents=True,exist_ok=True)
    try:
        if not re.fullmatch('[0-9a-f]{40}', args.source_sha): raise ValueError('Immutable40hex source SHA required')
        receipt['sourceSha']=args.source_sha
        receipt['sourceBinding']='operator source mapping; installed app/test APK hashes independently verified'
        receipt['appSha256']=hashlib.sha256(args.app_apk.read_bytes()).hexdigest()
        receipt['testSha256']=hashlib.sha256(args.test_apk.read_bytes()).hexdigest()
        adb=Adb(args.serial)
        # Verify actual installed base APKs, not merely filenames supplied by the operator.
        for package,key in [(PACKAGE,'appSha256'),(PACKAGE+'.test','testSha256')]:
            paths=adb.run('shell','pm','path',package).splitlines()
            if len(paths)!=1 or not paths[0].startswith('package:'): raise RuntimeError('Single installed base APK required; split upgrades need full package receipt')
            digest=adb.run('shell','sha256sum',paths[0][8:]).split()[0]
            if digest != receipt[key]: raise RuntimeError('Installed APK hash differs from supplied candidate')
        receipt['fingerprint']=adb.run('shell','getprop','ro.build.fingerprint')
        receipt['emulator']=adb.run('shell','getprop','ro.kernel.qemu')=='1'
        receipt['api']=int(adb.run('shell','getprop','ro.build.version.sdk'))
        receipt['abi']=adb.run('shell','getprop','ro.product.cpu.abi')
        if receipt['api'] < 29 or receipt['abi'] not in ('arm64-v8a','x86_64'): raise ValueError('Unsupported Android API/ABI')
        identity={key:receipt[key] for key in ('serial','fingerprint','emulator','api','abi')}
        receipt['deviceIdentitySha256']=hashlib.sha256(json.dumps(identity,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        upgrade=None
        if bool(args.upgrade_app_apk) != bool(args.upgrade_test_apk):raise ValueError('Upgrade requires both app and matching test APKs')
        if args.upgrade_app_apk:
            receipt['upgradeAppSha256']=hashlib.sha256(args.upgrade_app_apk.read_bytes()).hexdigest()
            receipt['upgradeTestSha256']=hashlib.sha256(args.upgrade_test_apk.read_bytes()).hexdigest()
            def upgrade(selected):
                for package,apk,key in [(PACKAGE,args.upgrade_app_apk,'upgradeAppSha256'),(PACKAGE+'.test',args.upgrade_test_apk,'upgradeTestSha256')]:
                    selected.run('install','-r','-t',str(apk))
                    installed=selected.run('shell','pm','path',package).splitlines()
                    if len(installed)!=1 or not installed[0].startswith('package:') or selected.run('shell','sha256sum',installed[0][8:]).split()[0]!=receipt[key]:
                        raise ValueError('Installed upgrade differs from selected candidate')
        receipt.update(cycle(adb,uuid.uuid4().hex,args.stage,args.output,upgrade=upgrade))
        receipt['counts']={'tests':1,'passed':1,'skipped':0,'failed':0}
    except Exception as error: receipt['error']=str(error)
    receipt['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    (args.output/'recovery.json').write_text(json.dumps(receipt,indent=2)+'\n')
    raise SystemExit(0 if receipt['status']=='PASS' else 1)

if __name__=='__main__': main()
