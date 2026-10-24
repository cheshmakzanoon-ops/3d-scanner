"""Bounded observer receipts for pinned pytest; never alters test outcomes."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat

import pytest
from _pytest.junitxml import bin_xml_escape, mangle_test_address
from _pytest.reports import TestReport
from _pytest.subtests import SubtestReport

VERSION = '9.0.3'
SCHEMA = 'realityforge.native-pytest-accounting.v1'
PROFILES = {'gcc-release', 'clang-release', 'gcc-sanitize', 'clang-sanitize'}
MAX_EVENTS = 100000
MAX_BYTES = 32 * 1024 * 1024
MAX_XML_BYTES = 64 * 1024 * 1024
MAX_NAME_BYTES = 8192


def pytest_addoption(parser):
    parser.addoption('--rf-pytest-accounting', metavar='PATH', help='Write exclusive bounded native pytest accounting receipt')


def pytest_configure(config):
    output = config.getoption('--rf-pytest-accounting')
    if output:
        config.pluginmanager.register(Observer(config, output), 'rf-pytest-accounting-observer')


def encoded(value):
    return json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(',', ':')).encode('utf-8')


class Observer:
    def __init__(self, config, output):
        if pytest.__version__ != VERSION:
            raise pytest.UsageError('Native accounting requires exact pytest ' + VERSION)
        self.output = Path(output)
        if os.path.lexists(self.output):
            raise pytest.UsageError('Native accounting output already exists')
        self.xmlpath = config.option.xmlpath
        if not self.xmlpath or hasattr(config, 'workerinput') or config.pluginmanager.hasplugin('xdist'):
            raise pytest.UsageError('Native accounting requires single-process JUnit XML')
        source = os.environ.get('RF_PYTEST_SOURCE_SHA', '')
        profile = os.environ.get('RF_NATIVE_CONFIG', '')
        repository = os.environ.get('RF_PYTEST_REPOSITORY')
        run_id = os.environ.get('RF_PYTEST_RUN_ID')
        if not re.fullmatch('[0-9a-f]{40}', source) or profile not in PROFILES:
            raise pytest.UsageError('Invalid native accounting source/profile identity')
        if (repository is None) != (run_id is None):
            raise pytest.UsageError('Incomplete hosted accounting identity')
        if repository is not None and (not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository)
                                       or not re.fullmatch(r'[1-9][0-9]*:[1-9][0-9]*', run_id)):
            raise pytest.UsageError('Invalid hosted accounting identity')
        self.prefix = config.option.junitprefix
        family = config.getini('junit_family')
        if family != 'xunit2':
            raise pytest.UsageError('Native accounting requires xunit2 JUnit family')
        if self.prefix is not None and (not isinstance(self.prefix, str) or len(self.prefix.encode('utf-8')) > MAX_NAME_BYTES):
            raise pytest.UsageError('Invalid JUnit prefix')
        self.identity = dict(schema=SCHEMA, source_sha=source, profile=profile,
                             repository=repository, run_id=run_id, pytest_version=VERSION,
                             junit_prefix=self.prefix, junit_family=family)
        for path in (self.output, Path(self.xmlpath)):
            if any(parent.is_symlink() for parent in (path.absolute(), *path.absolute().parents)):
                raise pytest.UsageError('Symlink accounting/XML paths are not allowed')
        self.events = []
        self.ordinals = {}
        self.byte_count = len(encoded(self.identity))
        self.failed = False

    def pytest_runtest_logreport(self, report):
        try:
            if type(report) not in (TestReport, SubtestReport):
                raise ValueError('Unknown pytest report type')
            if report.when not in ('setup', 'call', 'teardown') or report.outcome not in ('passed', 'failed', 'skipped'):
                raise ValueError('Unknown pytest report phase/outcome')
            if any(hasattr(report, name) for name in ('wasxfail', 'worker_id', 'item_index')):
                raise ValueError('Unsupported xfail/distributed pytest report shape')
            subtest = type(report) is SubtestReport
            names = mangle_test_address(report.nodeid)
            classname = '.'.join(([self.prefix] if self.prefix else []) + names[:-1])
            name = bin_xml_escape(names[-1])
            for value in (report.nodeid, classname, name):
                if not isinstance(value, str) or len(value.encode('utf-8')) > MAX_NAME_BYTES:
                    raise ValueError('Native accounting name exceeds budget')
            ordinal = self.ordinals.get(report.nodeid, 0) if subtest else None
            event = dict(sequence=len(self.events), nodeid=report.nodeid, classname=classname,
                         name=name, when=report.when, outcome=report.outcome,
                         subtest=subtest, ordinal=ordinal)
            size = len(encoded(event)) + 1
            if len(self.events) >= MAX_EVENTS or self.byte_count + size > MAX_BYTES:
                raise ValueError('Native accounting event budget exceeded')
            if subtest:
                self.ordinals[report.nodeid] = ordinal + 1
            self.events.append(event)
            self.byte_count += size
        except Exception:
            self.failed = True
            raise

    @pytest.hookimpl(hookwrapper=True, tryfirst=True)
    def pytest_sessionfinish(self, session, exitstatus):
        outcome = yield
        if outcome.excinfo is not None or self.failed:
            session.exitstatus = pytest.ExitCode.INTERNAL_ERROR
            return
        try:
            for path in (self.output, Path(self.xmlpath)):
                if any(parent.is_symlink() for parent in (path.absolute(), *path.absolute().parents)):
                    raise ValueError('Symlink accounting/XML paths are not allowed')
            flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
            fd = os.open(self.xmlpath, flags)
            with os.fdopen(fd, 'rb') as stream:
                metadata = os.fstat(stream.fileno())
                if not stat.S_ISREG(metadata.st_mode) or not 0 < metadata.st_size <= MAX_XML_BYTES:
                    raise ValueError('Invalid raw JUnit XML file/budget')
                raw = stream.read(MAX_XML_BYTES + 1)
                if len(raw) != metadata.st_size or len(raw) > MAX_XML_BYTES:
                    raise ValueError('Raw JUnit XML changed or exceeds budget')
            data = dict(self.identity, raw_xml_sha256=hashlib.sha256(raw).hexdigest(),
                        raw_xml_size=len(raw), session_exitstatus=int(session.exitstatus),
                        completed=True, events=self.events)
            payload = encoded(data) + b'\n'
            if len(payload) > MAX_BYTES:
                raise ValueError('Native accounting receipt exceeds budget')
            self.output.parent.mkdir(parents=True, exist_ok=True)
            fd = os.open(self.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(payload)
        except Exception:
            session.exitstatus = pytest.ExitCode.INTERNAL_ERROR
            raise
