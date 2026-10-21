#!/usr/bin/env python3
"""Read-only API30 readiness gate. No boot/install/settings writes. 90s/600s fixed."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

STABLE_SECONDS = 90
TOTAL_SECONDS = 600
CRITICAL = re.compile(
    r'FATAL EXCEPTION IN SYSTEM PROCESS|Lost network stack|'
    r'ANR in com\.android\.networkstack(?:\.|\b)|'
    r'Timeout executing service:.*(?:com\.android\.networkstack|TetheringService)|'
    r'Reason: executing service .*TetheringService|'
    r'Killing \d+:com\.android\.networkstack(?:\.|/|\b)')

class LogCursor:
    """Byte cursor, not timestamps/cumulative grep counts. First read skips history.

    Each pass validates prior EOF prefix to detect copytruncate+regrowth as well
    as inode rotation. Any discontinuity or unavailable log resets readiness.
    Partial lines are retained; oversized line/backlog fails closed.
    """
    def __init__(self, path):
        self.path = Path(path)
        self.identity = None
        self.offset = 0
        self.anchor = b''
        self.partial = b''
        self.last_growth = None

    def poll(self):
        try:
            with self.path.open('rb') as f:
                st = os.fstat(f.fileno())
                now = time.monotonic()
                identity = (st.st_dev, st.st_ino)
                changed = self.identity != identity or st.st_size < self.offset
                if not changed and self.offset:
                    f.seek(self.offset - len(self.anchor))
                    changed = f.read(len(self.anchor)) != self.anchor
                if changed:
                    # Existing bytes are historical/untrusted after discontinuity;
                    # start a new full stability window, never count them as quiet.
                    self.identity = identity
                    self.last_growth = now
                    self.offset = st.st_size
                    self.partial = b''
                    f.seek(max(0, self.offset - 128))
                    self.anchor = f.read(128)
                    return False, []
                f.seek(self.offset)
                data = f.read(8 * 1024 * 1024 + 1)
                if len(data) > 8 * 1024 * 1024:
                    self.identity = None
                    return False, ['LOG_BACKLOG_LIMIT']
                if data:
                    self.last_growth = now
                self.offset += len(data)
                f.seek(max(0, self.offset - 128))
                self.anchor = f.read(min(128, self.offset))
                parts = (self.partial + data).split(b'\n')
                self.partial = parts.pop()
                if len(self.partial) > 65536:
                    self.identity = None
                    return False, ['LOG_LINE_LIMIT']
                events = [line.decode('utf-8', 'replace') for line in parts
                          if CRITICAL.search(line.decode('utf-8', 'replace'))]
                if self.partial and CRITICAL.search(self.partial.decode('utf-8', 'replace')):
                    events.append(self.partial.decode('utf-8', 'replace'))
                if self.last_growth is None or now - self.last_growth > 30:
                    return False, events + ['LOG_NOT_ADVANCING_30S']
                return True, events
        except OSError:
            self.identity = None
            return False, ['LOG_UNAVAILABLE']

class Window:
    def __init__(self):
        self.identity = None
        self.since = None
        self.previous = None

    def update(self, now, identity, healthy):
        gap = self.previous is not None and now - self.previous > 30
        self.previous = now
        if gap:
            self.identity = None
            self.since = None
        if not healthy or not identity:
            self.identity = None
            self.since = None
            return False
        if identity != self.identity:
            self.identity, self.since = identity, now
        return now - self.since >= STABLE_SECONDS


def single_pid(value):
    value = value.strip()
    return value if re.fullmatch(r'[1-9][0-9]*', value) else None


def current_user_unlocked(dump, user):
    """Parse API30 UserManagerService.dump user-local State, not another user."""
    if not user or not user.isdecimal():
        return False
    sections = re.findall(r'^  UserInfo\{([0-9]+):[^\n]*\n((?:(?:(?!  UserInfo\{)[ \t]+[^\n]*|)\n)*)',
                          dump or '', re.MULTILINE)
    matches = [body for uid, body in sections if uid == user]
    return len(matches) == 1 and re.findall(r'^    State: (\S+)\s*$', matches[0],
                                          re.MULTILINE) == ['RUNNING_UNLOCKED']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--log', required=True, help='Continuously appended current-boot threadtime logcat')
    p.add_argument('--output', required=True, help='New JSONL evidence file; refuses overwrite')
    args = p.parse_args()
    start = time.monotonic()
    deadline = start + TOTAL_SECONDS
    cursor, window = LogCursor(args.log), Window()
    def command(*argv):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        try:
            result = subprocess.run([args.adb, '-s', args.serial, 'shell', *argv],
                capture_output=True, text=True, timeout=min(8, remaining), check=False)
            return result.stdout.strip() if result.returncode == 0 else None
        except (OSError, subprocess.TimeoutExpired):
            return None
    with open(args.output, 'x', encoding='utf-8') as out:
        def record(value):
            out.write(json.dumps(value, sort_keys=True) + '\n')
            out.flush()
        record({'status': 'START', 'stable_seconds': STABLE_SECONDS,
                'total_seconds': TOTAL_SECONDS, 'serial': args.serial})
        while time.monotonic() < deadline:
            probe_started = time.monotonic()
            boot = command('getprop', 'sys.boot_completed')
            sys_pid = single_pid(command('pidof', 'system_server') or '')
            net_pid = single_pid(command('pidof', 'com.android.networkstack.process') or '')
            package = command('pm', 'path', 'android')
            settings = command('settings', 'get', 'global', 'airplane_mode_on')
            user = command('am', 'get-current-user')
            unlocked = command('dumpsys', 'user')
            # Recheck process identities after sequential service probes.
            same = (sys_pid and net_pid and command('am', 'get-current-user') == user and
                    single_pid(command('pidof', 'system_server') or '') == sys_pid and
                    single_pid(command('pidof', 'com.android.networkstack.process') or '') == net_pid)
            log_ok, events = cursor.poll()
            healthy = bool(boot == '1' and same and package and package.startswith('package:') and
                           settings in ('0', '1') and user and user.isdecimal() and
                           current_user_unlocked(unlocked, user) and log_ok and not events)
            now = time.monotonic()
            probe_gap = now - window.previous if window.previous is not None else None
            ready = window.update(now, (sys_pid, net_pid, user) if same else None, healthy)
            record({'elapsed_seconds': round(now-start, 3), 'system_server': sys_pid,
                    'networkstack': net_pid, 'healthy': healthy, 'boot_completed': boot,
                    'current_user': user, 'current_user_unlocked': current_user_unlocked(unlocked, user),
                    'probe_duration_seconds': round(now-probe_started, 3),
                    'probe_gap_seconds': round(probe_gap, 3) if probe_gap is not None else None,
                    'probe_gap_reset': probe_gap is not None and probe_gap > 30,
                    'log_ok': log_ok, 'critical_events': events,
                    'stable_seconds': round(now-window.since, 3) if window.since is not None else 0})
            if ready and now < deadline:
                record({'status': 'READY', 'elapsed_seconds': round(now-start, 3)})
                return 0
            time.sleep(max(0, min(5, deadline-time.monotonic())))
        record({'status': 'NOT_STABLE', 'exit_code': 124})
        return 124

if __name__ == '__main__':
    raise SystemExit(main())
