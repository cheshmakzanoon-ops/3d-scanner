#!/usr/bin/env python3
"""Bounded sdkmanager install; only the observed corrupt ZIP read is retryable.

Original evidence: GitHub Actions job 120008503035, 2026-10-22. This helper
never accepts licenses, removes caches/packages, or retries build/device tests.
Linux/POSIX CI only: every child belongs to a new, owned process group.
"""
import io
import os
import selectors
import signal
import subprocess
import sys
import time
from dataclasses import dataclass

PACKAGES = ('platforms;android-36', 'build-tools;36.0.0',
            'ndk;28.2.13676358', 'cmake;3.22.1')
ZIP_READ_ERROR = b'Error reading Zip content from a SeekableByteChannel.'
ATTEMPT_TIMEOUT_SECONDS = 300
BACKOFF_SECONDS = (5, 10)
POLL_SECONDS = .1
CLEANUP_GRACE_SECONDS = .25


@dataclass
class Cancellation:
    signum: int = 0

    def record(self, signum, _frame):
        # Do not raise between Popen spawning and assignment of cleanup ownership.
        self.signum = signum

    @property
    def status(self):
        return 128 + self.signum if self.signum else 0


def _signal_group(pid, signum):
    try:
        os.killpg(pid, signum)
    except ProcessLookupError:
        pass


def _cleanup(proc):
    # The leader may have exited while a child retains our stdout pipe.
    _signal_group(proc.pid, signal.SIGTERM)
    time.sleep(CLEANUP_GRACE_SECONDS)
    _signal_group(proc.pid, signal.SIGKILL)
    proc.wait()


class AttemptStopped(Exception):
    pass


class OutputForwarder:
    """Independent Linux open-file description: no inherited O_NONBLOCK mutation.

    At most one 64 KiB chunk waits for downstream capacity. File redirections are
    append-only logs; in-memory output is supported for deterministic unit tests.
    """
    def __init__(self, output):
        self.output = output
        self.fd = None
        try:
            original = output.fileno()
        except (AttributeError, io.UnsupportedOperation):
            return
        self.fd = os.open(f'/proc/self/fd/{original}',
                          os.O_WRONLY | os.O_NONBLOCK | os.O_APPEND)

    def write(self, data, cancel, deadline):
        if self.fd is None:
            self.output.write(data)
            self.output.flush()
            return
        pending = memoryview(data)
        with selectors.DefaultSelector() as writable:
            registered = False
            while pending:
                if cancel.status or time.monotonic() >= deadline:
                    raise AttemptStopped
                try:
                    count = os.write(self.fd, pending)
                    if count == 0:
                        raise OSError('stdout made no write progress')
                    pending = pending[count:]
                except BlockingIOError:
                    if not registered:
                        writable.register(self.fd, selectors.EVENT_WRITE)
                        registered = True
                    writable.select(min(POLL_SECONDS, max(0, deadline-time.monotonic())))

    def close(self):
        if self.fd is not None:
            os.close(self.fd)


def run_attempt(command, cancel, *, timeout=ATTEMPT_TIMEOUT_SECONDS, output=None):
    """Return (exit status, observed exact ZIP diagnostic), retaining no full log."""
    if output is None:
        output = sys.stdout.buffer
    if cancel.status:
        return cancel.status, False
    proc = None
    forwarder = OutputForwarder(output)
    tail = b''
    matched = False
    deadline = time.monotonic() + timeout
    try:
        proc = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                start_new_session=True)
        os.set_blocking(proc.stdout.fileno(), False)
        with selectors.DefaultSelector() as selector:
            selector.register(proc.stdout, selectors.EVENT_READ)
            eof = False
            while True:
                if cancel.status:
                    return cancel.status, False
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return 124, False
                events = selector.select(min(POLL_SECONDS, remaining)) if not eof else []
                for key, _ in events:
                    chunk = os.read(key.fd, 65536)
                    if not chunk:
                        selector.unregister(proc.stdout)
                        eof = True
                        break
                    forwarder.write(chunk, cancel, deadline)
                    window = tail + chunk
                    matched = matched or ZIP_READ_ERROR in window
                    tail = window[-(len(ZIP_READ_ERROR) - 1):]
                # Observe without reaping: the leader's reserved PID prevents
                # process-group reuse until our cleanup has finished signaling.
                exited = os.waitid(os.P_PID, proc.pid,
                                   os.WEXITED | os.WNOHANG | os.WNOWAIT)
                if exited is not None and eof:
                    code = exited.si_status if exited.si_code == os.CLD_EXITED else 128 + exited.si_status
                    return code, matched
                if eof:
                    time.sleep(min(POLL_SECONDS, remaining))
                # If descendants retain the pipe, the same attempt deadline
                # bounds their lifetime, even after the leader has exited.
    except AttemptStopped:
        return cancel.status or 124, False
    finally:
        try:
            if proc is not None:
                _cleanup(proc)
                proc.stdout.close()
        finally:
            forwarder.close()


def _backoff(seconds, cancel):
    deadline = time.monotonic() + seconds
    while not cancel.status:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        time.sleep(min(POLL_SECONDS, remaining))


def install(sdkmanager='sdkmanager'):
    if sys.platform != 'linux':
        raise RuntimeError('SDK retry helper requires Linux process groups and /proc')
    cancel = Cancellation()
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGTERM, signal.SIGINT)}
    try:
        for sig in previous:
            signal.signal(sig, cancel.record)
        command = [sdkmanager, *PACKAGES]
        for attempt in range(3):
            if cancel.status:
                return cancel.status
            status, zip_error = run_attempt(command, cancel)
            if cancel.status:
                return cancel.status
            if status != 1 or not zip_error or attempt == 2:
                return status
            _backoff(BACKOFF_SECONDS[attempt], cancel)
        raise RuntimeError('Unreachable attempt state')
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def main():
    if len(sys.argv) != 1:
        print('This helper takes no arguments; SDK package pins are fixed.', file=sys.stderr)
        return 2
    try:
        return install()
    except OSError as error:
        print(f'Cannot execute sdkmanager: {error}', file=sys.stderr)
        return 127


if __name__ == '__main__':
    sys.exit(main())
