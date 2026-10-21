# Binary test fixtures below are two unchanged classfiles from Google's official
# androidx.test:monitor:1.7.2 artifact (Android Open Source Project).
# Source: https://dl.google.com/dl/android/maven2/androidx/test/monitor/1.7.2/monitor-1.7.2.aar
# Copyright (C) 2018 / 2014 The Android Open Source Project.
# Licensed under the Apache License, Version 2.0:
# https://www.apache.org/licenses/LICENSE-2.0
# Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
# Preserve the upstream artifact's license/NOTICE attribution when redistributing.
# Original SHA256 (also checked by TARGETS):
# TestOutputEmitter$1: cadaf2750969b978d060109bc20932b545a91dd54d2a3f7c533ea23d71a0ce45
# Checks$ThreadCheckerSingleton$1: 4730062f582788f36b20ace3cfdf5e9bdaefe2ff4fb0ad0d6d200bb526d96f09
import base64
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import zipfile
import pytest

TOOLS = Path(__file__).resolve().parents[1] / "tools"
if not (TOOLS / "instrumentation_qa_metadata.py").exists(): TOOLS = Path(__file__).parent
sys.path.insert(0, str(TOOLS))
import instrumentation_qa_metadata as m

FIXTURES = {'androidx/test/internal/platform/util/TestOutputEmitter$1.class': 'yv66vgAAADQAJwEAOGFuZHJvaWR4L3Rlc3QvaW50ZXJuYWwvcGxhdGZvcm0vdXRpbC9UZXN0T3V0cHV0RW1pdHRlciQxBwABAQAQamF2YS9sYW5nL09iamVjdAcAAwEANmFuZHJvaWR4L3Rlc3QvaW50ZXJuYWwvcGxhdGZvcm0vdXRpbC9UZXN0T3V0cHV0SGFuZGxlcgcABQEAFlRlc3RPdXRwdXRFbWl0dGVyLmphdmEBADZhbmRyb2lkeC90ZXN0L2ludGVybmFsL3BsYXRmb3JtL3V0aWwvVGVzdE91dHB1dEVtaXR0ZXIHAAgBAA9sYW1iZGEkc3RhdGljJDABADooKUxhbmRyb2lkeC90ZXN0L2ludGVybmFsL3BsYXRmb3JtL3V0aWwvVGVzdE91dHB1dEhhbmRsZXI7DAAKAAsBAAY8aW5pdD4BAAMoKVYMAA0ADgoABAAPAQAEdGhpcwEAOkxhbmRyb2lkeC90ZXN0L2ludGVybmFsL3BsYXRmb3JtL3V0aWwvVGVzdE91dHB1dEVtaXR0ZXIkMTsBABBkdW1wVGhyZWFkU3RhdGVzAQAVKExqYXZhL2xhbmcvU3RyaW5nOylWAQAKb3V0cHV0TmFtZQEAEkxqYXZhL2xhbmcvU3RyaW5nOwEADnRha2VTY3JlZW5zaG90AQAVKExqYXZhL2xhbmcvU3RyaW5nOylaAQAWY2FwdHVyZVdpbmRvd0hpZXJhcmNoeQEAE2FkZE91dHB1dFByb3BlcnRpZXMBABIoTGphdmEvdXRpbC9NYXA7KVoBADwoTGphdmEvdXRpbC9NYXA8TGphdmEvbGFuZy9TdHJpbmc7TGphdmEvaW8vU2VyaWFsaXphYmxlOz47KVoBAApwcm9wZXJ0aWVzAQAPTGphdmEvdXRpbC9NYXA7AQAEQ29kZQEAD0xpbmVOdW1iZXJUYWJsZQEAEkxvY2FsVmFyaWFibGVUYWJsZQEAEE1ldGhvZFBhcmFtZXRlcnMBAAlTaWduYXR1cmUBAAxJbm5lckNsYXNzZXMBAA9FbmNsb3NpbmdNZXRob2QBAApTb3VyY2VGaWxlACAAAgAEAAEABgAAAAUAAAANAA4AAQAfAAAALwABAAEAAAAFKrcAELEAAAACACAAAAAGAAEAAAAkACEAAAAMAAEAAAAFABEAEgAAAAEAEwAUAAIAHwAAADUAAAACAAAAAbEAAAACACAAAAAGAAEAAAAmACEAAAAWAAIAAAABABEAEgAAAAAAAQAVABYAAQAiAAAABQEAFQAAAAEAFwAYAAIAHwAAADYAAQACAAAAAgOsAAAAAgAgAAAABgABAAAAKgAhAAAAFgACAAAAAgARABIAAAAAAAIAFQAWAAEAIgAAAAUBABUAAAABABkAGAACAB8AAAA2AAEAAgAAAAIDrAAAAAIAIAAAAAYAAQAAAC8AIQAAABYAAgAAAAIAEQASAAAAAAACABUAFgABACIAAAAFAQAVAAAAAQAaABsAAwAfAAAANgABAAIAAAACA6wAAAACACAAAAAGAAEAAAA0ACEAAAAWAAIAAAACABEAEgAAAAAAAgAdAB4AAQAjAAAAAgAcACIAAAAFAQAdAAAAAwAkAAAACgABAAIAAAAAAAAAJQAAAAQACQAMACYAAAACAAc=', 'androidx/test/internal/util/Checks$ThreadCheckerSingleton$1.class': 'yv66vgAAADQAPgEAO2FuZHJvaWR4L3Rlc3QvaW50ZXJuYWwvdXRpbC9DaGVja3MkVGhyZWFkQ2hlY2tlclNpbmdsZXRvbiQxBwABAQAQamF2YS9sYW5nL09iamVjdAcAAwEALWFuZHJvaWR4L3Rlc3QvaW50ZXJuYWwvcGxhdGZvcm0vVGhyZWFkQ2hlY2tlcgcABQEAC0NoZWNrcy5qYXZhAQA5YW5kcm9pZHgvdGVzdC9pbnRlcm5hbC91dGlsL0NoZWNrcyRUaHJlYWRDaGVja2VyU2luZ2xldG9uBwAIAQAPbGFtYmRhJHN0YXRpYyQwAQAxKClMYW5kcm9pZHgvdGVzdC9pbnRlcm5hbC9wbGF0Zm9ybS9UaHJlYWRDaGVja2VyOwwACgALAQAiYW5kcm9pZHgvdGVzdC9pbnRlcm5hbC91dGlsL0NoZWNrcwcADQEAFlRocmVhZENoZWNrZXJTaW5nbGV0b24BAAY8aW5pdD4BAAMoKVYMABAAEQoABAASAQAEdGhpcwEAPUxhbmRyb2lkeC90ZXN0L2ludGVybmFsL3V0aWwvQ2hlY2tzJFRocmVhZENoZWNrZXJTaW5nbGV0b24kMTsBAA9jaGVja01haW5UaHJlYWQBABBqYXZhL2xhbmcvVGhyZWFkBwAXAQANY3VycmVudFRocmVhZAEAFCgpTGphdmEvbGFuZy9UaHJlYWQ7DAAZABoKABgAGwEAEWFuZHJvaWQvb3MvTG9vcGVyBwAdAQANZ2V0TWFpbkxvb3BlcgEAFSgpTGFuZHJvaWQvb3MvTG9vcGVyOwwAHwAgCgAeACEBAAlnZXRUaHJlYWQMACMAGgoAHgAkAQAGZXF1YWxzAQAVKExqYXZhL2xhbmcvT2JqZWN0OylaDAAmACcKAAQAKAEAQE1ldGhvZCBjYW5ub3QgYmUgY2FsbGVkIG9mZiB0aGUgbWFpbiBhcHBsaWNhdGlvbiB0aHJlYWQgKG9uOiAlcykIACoBAAdnZXROYW1lAQAUKClMamF2YS9sYW5nL1N0cmluZzsMACwALQoAGAAuAQAKY2hlY2tTdGF0ZQEAKShaTGphdmEvbGFuZy9TdHJpbmc7W0xqYXZhL2xhbmcvT2JqZWN0OylWDAAwADEKAA4AMgEAEmNoZWNrTm90TWFpblRocmVhZAEAP01ldGhvZCBjYW5ub3QgYmUgY2FsbGVkIG9uIHRoZSBtYWluIGFwcGxpY2F0aW9uIHRocmVhZCAob246ICVzKQgANQEABENvZGUBAA9MaW5lTnVtYmVyVGFibGUBABJMb2NhbFZhcmlhYmxlVGFibGUBAA1TdGFja01hcFRhYmxlAQAMSW5uZXJDbGFzc2VzAQAPRW5jbG9zaW5nTWV0aG9kAQAKU291cmNlRmlsZQAgAAIABAABAAYAAAADAAAAEAARAAEANwAAAC8AAQABAAAABSq3ABOxAAAAAgA4AAAABgABAAAAlgA5AAAADAABAAAABQAUABUAAAABABYAEQABADcAAABVAAYAAQAAAB+4ABy4ACK2ACW2ACkSKwS9AARZA7gAHLYAL1O4ADOxAAAAAgA4AAAAEgAEAAAAmgAUAJwAGwCZAB4AnQA5AAAADAABAAAAHwAUABUAAAABADQAEQABADcAAABoAAYAAQAAACe4ABy4ACK2ACW2ACmaAAcEpwAEAxI2BL0ABFkDuAActgAvU7gAM7EAAAADADoAAAAFAAITQAEAOAAAABIABAAAAKIAHACkACMAoQAmAKUAOQAAAAwAAQAAACcAFAAVAAAAAwA7AAAAEgACAAkADgAPAAoAAgAAAAAAAAA8AAAABAAJAAwAPQAAAAIABw=='}

def original(): return {n:base64.b64decode(b) for n,b in FIXTURES.items()}

@pytest.fixture
def setup(tmp_path):
    root = tmp_path / 'root'
    (root / 'tools').mkdir(parents=True)
    shutil.copyfile(m.__file__, root / 'tools/instrumentation_qa_metadata.py')
    (root / 'tools/instrumentation_abi.py').write_text('# bound helper\n')
    source = root / 'input.jar'; source.write_bytes(m.jar_bytes(original()))
    output = root / 'app/build/intermediates/classes/releaseAndroidTest/all/normalizeQaMonitorMetadata/classes.jar'
    receipt = root / 'app/build/generated/instrumentation-qa/metadata.json'
    return root, source, output, receipt


def test_exact_two_byte_only_correction():
    for name, data in original().items():
        after, change = m.patch(name, data)
        off = change['offset']
        assert len(after) == len(data)
        assert after[:off] == data[:off] and after[off+2:] == data[off+2:]
        assert after[off:off+2] == b'\0\0'
        assert change['owner'] == m.TARGETS[name][1]
        with pytest.raises(ValueError): m.patch(name, after)


def test_roundtrip_deterministic(setup):
    root, source, output, receipt = setup
    record = m.normalize([source], output, receipt, root)
    first = output.read_bytes(); first_receipt = receipt.read_bytes()
    assert m.verify(receipt, root) == record
    m.normalize([source], output, receipt, root)
    assert first == output.read_bytes() and first_receipt == receipt.read_bytes()
    with zipfile.ZipFile(output) as z:
        assert set(z.namelist()) == set(original())


@pytest.mark.parametrize('kind', ['input', 'output', 'helper', 'delta', 'extra_class', 'removed_class'])
def test_tamper_rejected(setup, kind):
    root, source, output, receipt = setup
    m.normalize([source], output, receipt, root)
    if kind == 'input': source.write_bytes(source.read_bytes() + b'X')
    elif kind == 'helper': (root / 'tools/instrumentation_abi.py').write_text('changed')
    elif kind == 'delta':
        obj = json.loads(receipt.read_text()); obj['class_changes'][0]['offset'] += 1; receipt.write_text(json.dumps(obj))
    elif kind in ('extra_class', 'removed_class'):
        with zipfile.ZipFile(output) as z: entries = {n:z.read(n) for n in z.namelist()}
        if kind == 'extra_class': entries['Extra.class'] = b'fake'
        else: entries.pop(next(iter(entries)))
        output.write_bytes(m.jar_bytes(entries))
        # Updating claimed output hash must not evade reconstruction.
        obj = json.loads(receipt.read_text()); obj['output_sha256'] = m.digest(output.read_bytes()); receipt.write_text(json.dumps(obj))
    else: output.write_bytes(output.read_bytes() + b'X')
    with pytest.raises(ValueError): m.verify(receipt, root)


def test_bad_location_and_aliases(setup):
    root, source, output, receipt = setup
    with pytest.raises(ValueError): m.normalize([source], root / 'shipping.jar', receipt, root)
    with pytest.raises(ValueError): m.merge([source, source])
    alias = root / 'alias.jar'; alias.symlink_to(source)
    with pytest.raises(ValueError): m.merge([alias])
    folder = root / 'folder'; folder.mkdir(); (folder / 'alias.class').symlink_to(source)
    with pytest.raises(ValueError): m.read_input(folder)


@pytest.mark.parametrize('name', ['../Bad.class', '/Bad.class', 'a//Bad.class', 'a/./Bad.class', 'a\\Bad.class', 'META-INF/versions/9/Bad.class'])
def test_bad_jar_entries(setup, name):
    _, source, _, _ = setup
    with zipfile.ZipFile(source, 'a') as z: z.writestr(name, b'x')
    with pytest.raises(ValueError): m.merge([source])


def test_duplicate_conflicting_and_missing(setup):
    root, source, _, _ = setup
    other = root / 'other.jar'; entries = original(); name = next(iter(entries)); entries[name] += b'X'
    other.write_bytes(m.jar_bytes(entries))
    with pytest.raises(ValueError): m.merge([source, other])
    other.write_bytes(m.jar_bytes({}))
    with pytest.raises(ValueError): m.merge([other])
    with zipfile.ZipFile(source, 'a') as z: z.writestr(name, original()[name])
    with pytest.raises(ValueError): m.merge([source])


def test_directory_inputs(setup):
    root, _, output, receipt = setup
    folder = root / 'classes'
    for name, data in original().items():
        p = folder / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)
    m.normalize([folder], output, receipt, root)
    assert m.verify(receipt, root)['inputs']


def test_parser_bounds_and_invalid_pool():
    for name, data in original().items():
        for cut in range(len(data)):
            with pytest.raises((ValueError, UnicodeError)):
                m.enclosing(data[:cut], name)
        with pytest.raises(ValueError): m.enclosing(data + b'X', name)
        bad = bytearray(data); bad[10] = 99
        with pytest.raises(ValueError): m.enclosing(bytes(bad), name)
        with pytest.raises(ValueError): m.enclosing(data, 'Wrong.class')
        off, *_ = m.enclosing(data, name)
        for value in (0, 65535):
            bad = data[:off] + value.to_bytes(2, 'big') + data[off+2:]
            with pytest.raises(ValueError): m.enclosing(bad, name)


def test_wrong_expected_owner_or_descriptor(monkeypatch):
    name, data = next(iter(original().items()))
    h, owner, descriptor = m.TARGETS[name]
    monkeypatch.setitem(m.TARGETS, name, (h, owner + 'Wrong', descriptor))
    with pytest.raises(ValueError): m.patch(name, data)


def test_failed_normalization_invalidates_receipt(setup):
    root, source, output, receipt = setup
    m.normalize([source], output, receipt, root)
    source.write_bytes(m.jar_bytes({}))
    with pytest.raises(ValueError): m.normalize([source], output, receipt, root)
    assert not receipt.exists()


def test_concurrent_input_mutation_invalidates_receipt(setup, monkeypatch):
    root, source, output, receipt = setup
    original_atomic = m.atomic
    def racing_atomic(path, data):
        original_atomic(path, data)
        if path == output: source.write_bytes(source.read_bytes() + b'X')
    monkeypatch.setattr(m, 'atomic', racing_atomic)
    with pytest.raises(ValueError): m.normalize([source], output, receipt, root)
    assert not receipt.exists()


def test_helper_mutation_during_merge_invalidates_receipt(setup, monkeypatch):
    root, source, output, receipt = setup
    m.normalize([source], output, receipt, root)
    original_merge = m.merge
    def racing_merge(inputs):
        result = original_merge(inputs)
        (root / 'tools/instrumentation_abi.py').write_text('changed during merge')
        return result
    monkeypatch.setattr(m, 'merge', racing_merge)
    with pytest.raises(ValueError): m.normalize([source], output, receipt, root)
    assert not receipt.exists()


def test_exact_module_descriptor_exclusion_receipt(setup):
    root, source, output, receipt = setup
    module = b'java9-module-descriptor'
    with zipfile.ZipFile(source, 'a') as z:
        z.writestr('META-INF/versions/9/module-info.class', module)
    record = m.normalize([source], output, receipt, root)
    assert record['excluded_entries'] == [{'input': str(source), 'entry': 'META-INF/versions/9/module-info.class',
        'sha256': m.digest(module), 'reason': 'Java 9 JPMS descriptor, not Android program bytecode'}]
    assert m.verify(receipt, root) == record
    with zipfile.ZipFile(output) as z: assert 'META-INF/versions/9/module-info.class' not in z.namelist()
    record['excluded_entries'][0]['sha256'] = '0' * 64
    receipt.write_text(json.dumps(record))
    with pytest.raises(ValueError): m.verify(receipt, root)


@pytest.mark.parametrize('name', ['META-INF/versions/10/module-info.class', 'META-INF/versions/9/Other.class', 'module-info.class'])
def test_neighboring_module_classes_rejected(setup, name):
    _, source, _, _ = setup
    with zipfile.ZipFile(source, 'a') as z: z.writestr(name, b'not-approved')
    with pytest.raises(ValueError): m.merge([source])
