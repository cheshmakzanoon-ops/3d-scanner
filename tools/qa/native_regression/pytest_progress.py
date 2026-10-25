"""Best-effort, bounded phase timing in pytest.log; never acceptance evidence.

Each record is flushed before test work resumes so SIGKILL leaves the last
started phase visible. Parameter values, captured output and absolute paths are
never recorded. A missing end is incomplete work, not a pass. This is deliberately
independent of the required accounting receipt and JUnit XML.
"""
import json
import re
import sys
import time

import pytest

MAX_BYTES = 4 * 1024 * 1024
MAX_RECORDS = 100000
MAX_NODEID = 512
PREFIX = 'RF_PYTEST_PROGRESS '
TRUNCATED = PREFIX + '{"event":"limit","incomplete":true}\n'


def safe_nodeid(nodeid):
    # Strip *all* parameter data, including pytest's escaped private paths.
    if not isinstance(nodeid, str) or len(nodeid) > 8192:
        return '<redacted>'
    base = nodeid.split('[', 1)[0]
    parts = base.split('::')
    if (len(base) > MAX_NODEID or len(parts) < 2
            or not re.fullmatch(r'tests/(?:[A-Za-z0-9_]+/)*test_[A-Za-z0-9_]+\.py', parts[0])
            or any(not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', part) for part in parts[1:])):
        return '<redacted>'
    return base


class Progress:
    def __init__(self):
        self.bytes = 0
        self.records = 0
        self.phases = 0
        self.stopped = False
        self.stream = sys.__stdout__
        try:
            self.started = time.monotonic()
        except Exception:
            self.stopped = True

    def emit(self, record):
        if self.stopped:
            return
        # No exception from optional diagnostics may change the test outcome.
        try:
            line = PREFIX + json.dumps(record, ensure_ascii=True, allow_nan=False,
                                       separators=(',', ':')) + '\n'
            size = len(line.encode('ascii'))
            if (self.records >= MAX_RECORDS
                    or self.bytes + size + len(TRUNCATED) > MAX_BYTES):
                line = TRUNCATED
                self.stopped = True
            self.stream.write(line)
            self.stream.flush()
            self.bytes += len(line.encode('ascii'))
            self.records += 1
        except Exception:
            self.stopped = True

    def begin(self, nodeid, phase):
        if self.stopped:
            return None, None
        try:
            self.phases += 1
            started = time.monotonic()
            identity = dict(phase_id=self.phases, nodeid=safe_nodeid(nodeid), phase=phase)
            self.emit(dict(identity, event='start', elapsed_s=round(started - self.started, 6)))
            return identity, started
        except Exception:
            self.stopped = True
            return None, None

    def end(self, identity, started):
        if self.stopped or identity is None:
            return
        try:
            now = time.monotonic()
            self.emit(dict(identity, event='end', elapsed_s=round(now - self.started, 6),
                           duration_s=round(now - started, 6)))
        except Exception:
            self.stopped = True


_progress = None


def pytest_configure(config):
    global _progress
    _progress = Progress()


@pytest.hookimpl(hookwrapper=True, tryfirst=True)
def pytest_runtest_setup(item):
    identity, started = _progress.begin(item.nodeid, 'setup')
    try:
        yield
    finally:
        _progress.end(identity, started)


@pytest.hookimpl(hookwrapper=True, tryfirst=True)
def pytest_runtest_call(item):
    identity, started = _progress.begin(item.nodeid, 'call')
    try:
        yield
    finally:
        _progress.end(identity, started)


@pytest.hookimpl(hookwrapper=True, tryfirst=True)
def pytest_runtest_teardown(item):
    identity, started = _progress.begin(item.nodeid, 'teardown')
    try:
        yield
    finally:
        _progress.end(identity, started)
