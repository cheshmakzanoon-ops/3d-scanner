"""Closed synthetic missing-records CLI admission; never release qualification."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest
import stdlib_cli_boundary as boundary


@pytest.fixture
def fixture(tmp_path):
    root = tmp_path/'fixture'
    root.mkdir()
    (root/'records').mkdir()
    (root/'unit-fixture.apk').write_bytes(b'SYNTHETIC UNIT ARTIFACT')
    source = root/'source'
    source.mkdir()
    subprocess.run(['/usr/bin/git','init','-q','-b','fixture-source',str(source)],check=True)
    (source/'source.txt').write_bytes(b'SYNTHETIC UNIT SOURCE\n')
    subprocess.run(['/usr/bin/git','-C',str(source),'add','source.txt'],check=True)
    subprocess.run(['/usr/bin/git','-C',str(source),'-c','user.name=Unit Fixture',
                    '-c','user.email=unit@example.invalid','commit','-qm','Synthetic fixture'],check=True)
    sha = subprocess.check_output(['/usr/bin/git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
    data = {'schema':1,'source_sha':sha,
            'artifact_sha256':hashlib.sha256(b'SYNTHETIC UNIT ARTIFACT').hexdigest(),
            'run_id':'SYNTHETIC-UNIT-RECEIPTS','created_at':'2026-01-01T00:00:00Z',
            'policy_sha256':boundary.PINS['release-policy'],
            'protocols':{key:'6'*64 for key in ('geometry','texture','resources')},
            'scenes':[],'devices':[]}
    (root/'candidate.json').write_text(json.dumps(data))
    args = [sys.executable,'-m','tools.release.qualification.aggregate',
            '--candidate',str(root/'candidate.json'),'--records',str(root/'records'),
            '--evidence-root',str(root),'--candidate-apk',str(root/'unit-fixture.apk'),
            '--source-root',str(source),'--output',str(root/'report.json')]
    return root, args


def test_exact_fixture_gets_only_local_environment_copy(fixture):
    root, args = fixture
    injected = {'LD_PRELOAD':'unexecuted-asan','LD_AUDIT':'unexecuted-audit',
                'LD_LIBRARY_PATH':'unexecuted-libs','PYTHONPATH':'unexecuted-python',
                'RF_NATIVE_LIBRARY':'unexecuted-native','RF_CABI_LIBRARY':'unexecuted-cabi',
                'GIT_CONFIG_GLOBAL':'unexecuted-config'}
    parent = dict(os.environ, **injected, PATH='/usr/bin:/bin',
                  ASAN_OPTIONS='detect_leaks=1:halt_on_error=1', LSAN_OPTIONS='exitcode=23',
                  UBSAN_OPTIONS='halt_on_error=1')
    before = dict(parent)
    command, env = boundary.missing_release_records_cli(root,args,parent)
    assert command == [sys.executable,'-I','-S',
                       str(boundary.ROOT/'tools/release/qualification/aggregate.py'),*args[3:]]
    assert parent == before and env is not parent
    assert not set(injected).difference({'GIT_CONFIG_GLOBAL'}).intersection(env)
    assert env['GIT_CONFIG_GLOBAL'] == os.devnull
    for key in ('ASAN_OPTIONS','LSAN_OPTIONS','UBSAN_OPTIONS'):
        assert env[key] == parent[key]


@pytest.mark.parametrize('change', ['record','apk','candidate','duplicate-field','source',
                                  'output','extra','symlink','foreign-root','git-config','git-alternate'])
def test_foreign_or_altered_data_never_admitted(fixture, tmp_path, change):
    root, args = fixture
    if change == 'record': (root/'records/native.json').write_text('{}')
    elif change == 'apk': (root/'unit-fixture.apk').write_bytes(b'PK real-or-foreign-artifact')
    elif change == 'candidate':
        p=root/'candidate.json'; data=json.loads(p.read_text());data['scenes']=[{}];p.write_text(json.dumps(data))
    elif change == 'duplicate-field':
        p=root/'candidate.json';p.write_text(p.read_text()[:-1]+', "devices": []}')
    elif change == 'source': (root/'source/source.txt').write_text('foreign source')
    elif change == 'output': (root/'report.json').write_text('{}')
    elif change == 'extra': (root/'unreviewed.py').write_text('import ctypes')
    elif change == 'symlink':
        p=root/'unit-fixture.apk';p.unlink();p.symlink_to(tmp_path/'outside.apk')
    elif change == 'foreign-root': root=boundary.ROOT
    elif change == 'git-config':
        p=root/'source/.git/config';p.write_text(p.read_text()+'\n[include]\npath = /unreviewed/config\n')
    else: (root/'source/.git/objects/info/alternates').write_text('/unreviewed/objects\n')
    with pytest.raises(ValueError): boundary.missing_release_records_cli(root,args)


@pytest.mark.parametrize('change', ['module','argument','extra','interpreter','foreign-path','path-alias'])
def test_other_invocations_are_not_a_generic_qualification_boundary(fixture, change):
    root, args = fixture
    if change == 'module': args[2]='native_api'
    elif change == 'argument': args[3]='--help'
    elif change == 'extra': args += ['--extra']
    elif change == 'interpreter': args[0]='/usr/bin/false'
    elif change == 'foreign-path': args[10]='/unreviewed/real.apk'
    else: args[4]=str(root/'source/../candidate.json')
    with pytest.raises(ValueError): boundary.missing_release_records_cli(root,args)


def copy_closure(tmp_path, monkeypatch):
    repo=tmp_path/'reviewed-code'
    for name in ('aggregate.py','policy-v1.json'):
        source=boundary.ROOT/'tools/release/qualification'/name
        target=repo/'tools/release/qualification'/name
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source,target)
    monkeypatch.setattr(boundary,'ROOT',repo)
    return repo/'tools/release/qualification'


@pytest.mark.parametrize('target', ['aggregate.py','policy-v1.json'])
def test_changed_source_or_policy_is_not_executed(fixture,tmp_path,monkeypatch,target):
    root,args=fixture; directory=copy_closure(tmp_path,monkeypatch)
    path=directory/target
    path.write_bytes(path.read_bytes()+b'\nimport ctypes; ctypes.CDLL("unexecuted-native.so")\n')
    with pytest.raises(ValueError,match='identity'):
        boundary.missing_release_records_cli(root,args)


def test_real_entrypoint_ignores_startup_and_import_shadows(fixture,tmp_path,monkeypatch):
    root,args=fixture; directory=copy_closure(tmp_path,monkeypatch)
    marker=tmp_path/'poison-ran'
    poison='from pathlib import Path\nPath('+repr(str(marker))+').touch()\nraise RuntimeError("shadow")\n'
    for name in ('sitecustomize.py','usercustomize.py','subprocess.py','argparse.py'):
        (directory/name).write_text(poison)
    parent=dict(os.environ,PYTHONPATH=str(directory),PYTHONSTARTUP=str(directory/'sitecustomize.py'))
    command,env=boundary.missing_release_records_cli(root,args,parent)
    result=subprocess.run(command,env=env,cwd=directory,capture_output=True,text=True,timeout=10)
    assert result.returncode == 2 and result.stderr == ''
    report=json.loads((root/'report.json').read_text())
    assert json.loads(result.stdout)['status'] == report['status'] == 'BLOCKED_EXTERNAL'
    assert len(report['blockers']) == 2 and report['rejected_records'] == []
    assert not marker.exists()


def test_external_common_directory_filter_never_executes(fixture,tmp_path,monkeypatch):
    root,args=fixture
    metadata=root/'source/.git'
    external=tmp_path/'external-git'
    shutil.copytree(metadata,external)
    marker=tmp_path/'must-not-execute'
    config=external/'config'
    config.write_text(config.read_text()+'\n[filter "probe"]\n\tclean = touch "'+str(marker)+'"; cat\n\trequired = true\n')
    (external/'info/attributes').write_text('source.txt filter=probe\n')
    (metadata/'commondir').write_text(str(external)+'\n')
    calls=[]
    def forbidden(*a,**kw):
        calls.append(a)
        raise AssertionError('Git executed before metadata admission')
    monkeypatch.setattr(boundary.subprocess,'check_output',forbidden)
    with pytest.raises(ValueError,match='Git metadata'):
        boundary.missing_release_records_cli(root,args)
    assert calls == [] and not marker.exists()


@pytest.mark.parametrize('name', ['commondir','gitdir','config.worktree','info/attributes',
    'objects/info/alternates','objects/info/http-alternates','refs/replace/foreign',
    'objects/pack/foreign.pack','hooks/pre-commit'])
def test_git_indirections_are_rejected_before_any_git_process(fixture,monkeypatch,name):
    root,args=fixture
    path=root/'source/.git'/name
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text('/unreviewed/external\n')
    def forbidden(*a,**kw):
        raise AssertionError('Git executed before metadata admission')
    monkeypatch.setattr(boundary.subprocess,'check_output',forbidden)
    with pytest.raises(ValueError,match='Git metadata'):
        boundary.missing_release_records_cli(root,args)


def test_git_environment_cannot_redirect_admission_or_child(fixture,monkeypatch):
    root,args=fixture
    parent=dict(os.environ, GIT_COMMON_DIR='/unreviewed/common', GIT_DIR='/unreviewed/git',
                GIT_WORK_TREE='/unreviewed/work', GIT_OBJECT_DIRECTORY='/unreviewed/objects',
                GIT_ALTERNATE_OBJECT_DIRECTORIES='/unreviewed/alternates',
                GIT_CONFIG_PARAMETERS="'filter.probe.clean=unreviewed'", GIT_ATTR_NOSYSTEM='0')
    original=dict(parent)
    real=boundary.subprocess.check_output
    observations=[]
    def checked(command,**kwargs):
        env=kwargs['env'];observations.append(command)
        assert env['GIT_DIR'] == env['GIT_COMMON_DIR'] == str(root/'source/.git')
        assert env['GIT_WORK_TREE'] == str(root/'source')
        assert env['GIT_OBJECT_DIRECTORY'] == str(root/'source/.git/objects')
        assert env['GIT_ALTERNATE_OBJECT_DIRECTORIES'] == ''
        assert env['GIT_ATTR_NOSYSTEM'] == '1'
        assert 'GIT_CONFIG_PARAMETERS' not in env
        return real(command,**kwargs)
    monkeypatch.setattr(boundary.subprocess,'check_output',checked)
    command,env=boundary.missing_release_records_cli(root,args,parent)
    assert len(observations) == 4 and parent == original
    assert env['GIT_COMMON_DIR'] == str(root/'source/.git')
    result=subprocess.run(command,env=env,capture_output=True,text=True,timeout=10)
    assert result.returncode == 2 and result.stderr == ''
    assert json.loads(result.stdout)['status'] == 'BLOCKED_EXTERNAL'
