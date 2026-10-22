"""Linux diagnostic-only descendant ownership; no argv/env/host inventory."""
import ctypes
import os
from pathlib import Path
import signal
import time

GRACE = .5


def enable_subreaper():
    # Local process adoption only: no privilege, persistent service or security
    # setting is granted. Needed to reap orphaned/double-forked test children.
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
        raise OSError(ctypes.get_errno(), 'cannot establish child ownership')
    if not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
        raise RuntimeError('identity-safe process signaling unavailable')


def children(pid):
    # Some container kernels omit task/*/children. Read only PID/PPID from stat
    # transiently; retain/publish identity data ONLY for owned descendants.
    result = set()
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            raw = (entry/'stat').read_text()
            fields = raw[raw.rindex(')')+2:].split()
            if int(fields[1]) == pid:
                result.add(int(entry.name))
        except (FileNotFoundError, ProcessLookupError):
            pass
    return result


def identity(pid):
    try:
        raw = Path('/proc', str(pid), 'stat').read_text()
        fields = raw[raw.rindex(')')+2:].split()
        return dict(pid=pid, ppid=int(fields[1]), pgid=int(fields[2]),
                    start_time=int(fields[19]), state=fields[0],
                    executable=raw[raw.index('(')+1:raw.rindex(')')][:80])
    except (FileNotFoundError, ProcessLookupError):
        return None


class Scope:
    def __init__(self):
        enable_subreaper()
        if children(os.getpid()):
            raise RuntimeError('orchestrator already owns unrelated children')
        self.known = {}
        self.escaped = False
        self.leader = None
        self.events = []
        self.errors = []
        self.started = time.monotonic()

    def snapshot(self, label):
        # Only acquire direct children of this persistent subreaper. Nested
        # children become ours after their owned parent exits; never infer
        # ownership through a reusable intermediate PID.
        parent = os.getpid()
        for pid in children(parent):
            value = identity(pid)
            if value and value['ppid'] == parent:
                self.known[(pid, value['start_time'])] = value
                if self.leader is not None and value['pgid'] != self.leader:
                    self.escaped = True
        current = []
        for (pid, birth) in self.known:
            value = identity(pid)
            if value and value['start_time'] == birth and value['ppid'] == parent:
                current.append(value)
        if label:
            self.events.append(dict(event=label, seconds=round(time.monotonic()-self.started,4), members=current))
        return current

    def reap(self, process):
        process.poll()  # Popen remains owner of the direct leader's status.
        for value in self.snapshot(None):
            if value['pid'] == process.pid:
                continue
            try:
                os.waitpid(value['pid'], os.WNOHANG)
            except ChildProcessError:
                pass

    def live(self, process):
        self.reap(process)
        return self.snapshot(None)

    def send(self, value, sig):
        try:
            fd = os.pidfd_open(value['pid'])
        except ProcessLookupError:
            return
        try:
            now = identity(value['pid'])
            if now and now['start_time'] == value['start_time'] and now['ppid'] == os.getpid():
                try:
                    signal.pidfd_send_signal(fd, sig)
                except ProcessLookupError:
                    pass
        finally:
            os.close(fd)

    def finish(self, process, allow_natural):
        def current():
            try:
                return self.live(process)
            except (OSError, ValueError) as error:
                self.errors.append(type(error).__name__)
                # Retain verified identities for best-effort cleanup. send()
                # revalidates birth and current direct parent before signaling.
                return list(self.known.values())

        def event(label):
            try:
                self.snapshot(label)
            except (OSError, ValueError) as error:
                self.errors.append(type(error).__name__)

        event('leader_exit' if allow_natural else 'forced_cleanup_start')
        if allow_natural:
            until = time.monotonic()+GRACE
            while current() and time.monotonic() < until:
                time.sleep(.01)
        residual = current()
        event('natural_drain_end')
        persistent = bool(residual)
        if residual or self.errors:
            for sig, grace in ((signal.SIGTERM, 1.), (signal.SIGKILL, 2.)):
                until = time.monotonic()+grace
                while time.monotonic() < until:
                    remaining = current()
                    if not remaining and process.poll() is not None:
                        break
                    for value in remaining:
                        try:
                            self.send(value, sig)
                        except (OSError, ValueError) as error:
                            self.errors.append(type(error).__name__)
                    # Even if /proc monitoring fails, Popen still owns its
                    # direct child; do not abandon it because another PID raced.
                    if self.errors and process.poll() is None:
                        try:
                            process.send_signal(sig)
                        except ProcessLookupError:
                            pass
                        except OSError as error:
                            self.errors.append(type(error).__name__)
                    time.sleep(.02)
        try:
            process.wait(timeout=1)
        except Exception as error:
            self.errors.append(type(error).__name__)
        remaining = current()
        event('cleanup_end')
        return persistent, not remaining and not self.errors
