"""EXPERIMENTAL diagnostic snapshot, NOT a readiness gate or install permission.

Reproduces a failed one-core API30 observation: only boot completed before the
host25s deadline. Requires owner-verified stock toybox timeout. Guest bounds
request termination, but scheduling/signal delivery can be delayed under TCG;
the host deadline is independently enforced. This does not prove guest cleanup.
No readiness retry, emulator launch, install, security or watchdog changes.
"""
import argparse
import json
import re
import secrets
import shlex
import subprocess
import time

COMMANDS = (
    ('boot', 'getprop sys.boot_completed'),
    ('system_before', 'pidof system_server'),
    ('network_before', 'pidof com.android.networkstack.process'),
    ('package', 'pm path android'),
    ('settings', 'settings get global airplane_mode_on'),
    ('user_before', 'am get-current-user'),
    ('users', 'dumpsys user'),
    ('user_after', 'am get-current-user'),
    ('system_after', 'pidof system_server'),
    ('network_after', 'pidof com.android.networkstack.process'),
)


def shell_script(nonce):
    if not re.fullmatch('[a-f0-9]{32}', nonce):
        raise ValueError('Invalid nonce')
    lines = [f"printf '%s\\n' '{nonce} SNAPSHOT_BEGIN'"]
    for name, command in COMMANDS:
        lines += ["read up rest < /proc/uptime",
                  f"printf '%s %s\\n' '{nonce} BEGIN {name}' \"$up\"",
                  '/system/bin/toybox timeout 8 sh -c ' + shlex.quote(command),
                  'rc=$?', 'read up rest < /proc/uptime',
                  f"printf '\\n%s %s %s\\n' '{nonce} END {name}' \"$rc\" \"$up\""]
    lines += [f"printf '%s\\n' '{nonce} SNAPSHOT_END'"]
    # Both outer and inner are guest-side bounds, independent of host adb lifetime.
    return 'exec /system/bin/toybox timeout 25 sh -c ' + shlex.quote('\n'.join(lines)) + '\n'


def parse(text, nonce):
    """Exact ordered nonce framing; output cannot invent another successful field."""
    if not re.fullmatch('[a-f0-9]{32}', nonce):
        raise ValueError('Invalid nonce')
    lines = text.replace('\r\n', '\n').splitlines()
    if not lines or lines[0] != nonce + ' SNAPSHOT_BEGIN':
        raise ValueError('Missing initial frame')
    pos, fields = 1, {}
    for name, _ in COMMANDS:
        if pos >= len(lines):
            return {'complete': False, 'fields': fields, 'pending': name}
        match = re.fullmatch(re.escape(nonce + ' BEGIN ' + name) + r' ([0-9]+(?:\.[0-9]+)?)', lines[pos])
        if not match:
            raise ValueError('Malformed/duplicate/out-of-order begin frame: ' + name)
        started = float(match.group(1)); pos += 1; body = []
        while pos < len(lines) and not lines[pos].startswith(nonce):
            body.append(lines[pos]); pos += 1
        if pos >= len(lines):
            return {'complete': False, 'fields': fields, 'pending': name}
        end = re.fullmatch(re.escape(nonce + ' END ' + name) + r' ([0-9]{1,3}) ([0-9]+(?:\.[0-9]+)?)', lines[pos])
        if not end:
            raise ValueError('Malformed/duplicate/out-of-order end frame: ' + name)
        code, finished = int(end.group(1)), float(end.group(2)); pos += 1
        if code > 255 or finished < started:
            raise ValueError('Invalid status/time')
        output = '\n'.join(body).strip()
        status = 'ok' if code == 0 else ('timeout_or_exit124' if code == 124 else 'shell_nonzero')
        if name.startswith(('system_', 'network_')) and code == 1 and not output:
            status = 'pid_absent'
        fields[name] = {'status': status, 'exit_code': code, 'duration_seconds': finished-started,
                        'output': output, 'value': output if code == 0 else None}
    if pos >= len(lines):
        return {'complete': False, 'fields': fields, 'pending': 'snapshot_end'}
    if lines[pos:] != [nonce + ' SNAPSHOT_END']:
        raise ValueError('Malformed/trailing snapshot frame')
    return {'complete': True, 'fields': fields, 'pending': None}


def snapshot(adb, serial, remaining=25):
    nonce = secrets.token_hex(16)
    started = time.monotonic()
    # Host bound no larger than guest25s or overall remaining budget.
    try:
        result = subprocess.run([adb, '-s', serial, 'shell', 'sh'], input=shell_script(nonce),
                                capture_output=True, text=True, timeout=min(25, remaining))
        stdout, stderr, code = result.stdout, result.stderr, result.returncode
        transport = 'ok' if code == 0 else ('snapshot_timeout_or_exit124' if code == 124 else 'adb_or_shell_error')
    except subprocess.TimeoutExpired as e:
        stdout = e.stdout or b''; stderr = e.stderr or b''; code = None
        transport = 'host_snapshot_timeout'
    except OSError as e:
        return {'complete': False, 'fields': {}, 'status': 'transport_error', 'error': str(e),
                'duration_seconds': time.monotonic()-started}
    if isinstance(stdout, bytes): stdout = stdout.decode('utf-8', 'replace')
    if isinstance(stderr, bytes): stderr = stderr.decode('utf-8', 'replace')
    try:
        parsed = parse(stdout, nonce)
    except ValueError as e:
        parsed = {'complete': False, 'fields': {}, 'parse_error': str(e)}
    parsed.update(status=transport, adb_exit=code, duration_seconds=time.monotonic()-started,
                  stderr=stderr, raw_stdout=stdout)
    parsed['complete'] = parsed['complete'] and transport == 'ok'
    return parsed

if __name__ == '__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--adb', required=True); p.add_argument('--serial', required=True)
    p.add_argument('--output', required=True)
    args=p.parse_args()
    with open(args.output, 'x') as f:
        result=snapshot(args.adb,args.serial)
        json.dump(result,f,indent=2);f.write('\n')
    raise SystemExit(0 if result['complete'] else 1)
