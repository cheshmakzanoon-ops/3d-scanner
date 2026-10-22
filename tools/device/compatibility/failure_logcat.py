"""Opt-in disposable-emulator diagnostics; never instrumentation pass/fail evidence."""
from __future__ import annotations
from datetime import datetime, timezone
import codecs
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import time

MAX_BYTES = 2 * 1024 * 1024
RECORD_LIMIT = 4000
TIMEOUT_SECONDS = 20
BUFFERS = ('main', 'system', 'crash')


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def capture(serial, emulator, destination: Path, *, timeout=TIMEOUT_SECONDS,
            max_bytes=MAX_BYTES, popen=subprocess.Popen):
    """Bound memory, disk and client lifetime; stdout/stderr are one diagnostic tail.

    Caller must require explicit opt-in and a failed run. The double emulator
    identity check also lives here so a helper invocation cannot collect USB logs.
    This POSIX collector is used by the Linux CI emulator jobs; no adb root,
    buffer clearing, package permission change, or unbounded communicate().
    """
    if not emulator or not re.fullmatch(r'emulator-[0-9]+', serial or ''):
        return {'status': 'NOT_CAPTURED', 'reason': 'verified emulator serial required'}
    if not 0 < timeout <= TIMEOUT_SECONDS or not 0 < max_bytes <= MAX_BYTES:
        raise ValueError('Diagnostic bounds must not exceed the fixed limits')
    argv = ['adb', '-s', serial, 'logcat', '-d', '-t', str(RECORD_LIMIT),
            '-v', 'threadtime', *[item for name in BUFFERS for item in ('-b', name)]]
    result = {'status': 'ERROR', 'startedAtUtc': utc_now(), 'serial': serial,
              'maxBytes': max_bytes, 'recordLimit': RECORD_LIMIT,
              'timeoutSeconds': timeout, 'buffersRequested': list(BUFFERS),
              'bufferAvailability': 'NOT_VERIFIED', 'command': argv,
              'encoding': 'UTF-8; partial or invalid sequences omitted'}
    tail, observed, process = bytearray(), 0, None
    deadline = time.monotonic() + timeout
    timed_out = False
    try:
        process = popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT, start_new_session=True)
        os.set_blocking(process.stdout.fileno(), False)
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    timed_out = True
                    break
                for key, _ in selector.select(remaining):
                    try:
                        chunk = os.read(key.fd, 65536)
                    except BlockingIOError:
                        continue
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    observed += len(chunk)
                    if len(chunk) >= max_bytes:
                        tail[:] = chunk[-max_bytes:]
                    else:
                        excess = len(tail) + len(chunk) - max_bytes
                        if excess > 0:
                            del tail[:excess]
                        tail.extend(chunk)
        if not timed_out:
            try:
                process.wait(timeout=max(0.001, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                timed_out = True
        result['status'] = ('TIMED_OUT' if timed_out else
                            'FAILED' if process.returncode else
                            'CAPTURED' if observed else 'EMPTY')
    except Exception as error:
        result['error'] = str(error)[:512]
    finally:
        if process is not None:
            # A leader may exit while a descendant closes its inherited pipe
            # and keeps running. Always drain this newly-created client group;
            # a missing group is normal after a clean client exit. This cannot
            # signal a different-session adb server or any device-side process.
            try:
                os.killpg(process.pid, signal.SIGKILL)
                result['residualGroupTerminated'] = True
            except ProcessLookupError:
                pass
            except OSError as error:
                result['terminationError'] = str(error)[:512]
                try:
                    process.kill()
                except OSError:
                    pass
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                result['terminationError'] = 'client did not reap within 2 seconds'
            result['exitCode'] = process.returncode
            if process.stdout is not None:
                process.stdout.close()
        result.update(timedOut=timed_out, observedBytes=observed,
                      retainedBytes=0, truncated=observed > max_bytes,
                      finishedAtUtc=utc_now())
        try:
            # Convert/write in fixed chunks: never duplicate the full tail into
            # a Unicode string and another encoded byte buffer. Tail length is
            # <= max_bytes; decoder input chunks are at most 64 KiB (not a whole-process RSS cap).
            if process is not None:
                decoder = codecs.getincrementaldecoder('utf-8')(errors='ignore')
                with destination.open('wb') as stream:
                    for offset in range(0, len(tail), 65536):
                        text = decoder.decode(memoryview(tail)[offset:offset + 65536])
                        result['retainedBytes'] += stream.write(text.encode('utf-8'))
                    result['retainedBytes'] += stream.write(decoder.decode(b'', final=True).encode('utf-8'))
                result['omittedInvalidUtf8Bytes'] = len(tail) - result['retainedBytes']
                result['artifact'] = destination.name
                result['buffersObserved'] = sorted({match.group(1).decode() for match in re.finditer(
                    rb'^--------- beginning of (main|system|crash)\r?$', tail,
                    re.MULTILINE)})
                if result['status'] == 'CAPTURED' and result['retainedBytes'] == 0:
                    result['status'] = 'EMPTY'
        except OSError as error:
            result['artifactError'] = str(error)[:512]
            result['status'] = 'ERROR'
    return result
