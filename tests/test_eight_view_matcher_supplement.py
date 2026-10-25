"""Adapter admission/orchestration tests; these do not claim actual JNI execution."""
import json
from pathlib import Path
from types import SimpleNamespace
import pytest
from tools.eight_view_host import matcher_supplement as m, controller as c
from tools.eight_view_host.runtime import Failure, sha


def fixture(tmp_path, monkeypatch):
    root=tmp_path/'repo';work=tmp_path/'work';root.mkdir();work.mkdir()
    run=SimpleNamespace(root=root,work=work,remaining=lambda seconds,cleanup=False:seconds)
    jars=work/'gradle';jars.mkdir()
    file=jars/'kotlin-compiler-embeddable-2.1.21.jar';file.write_bytes(b'compiler')
    (root/'gradle').mkdir()
    (root/'gradle/verification-metadata.xml').write_text(f'<verification-metadata xmlns="https://schema.gradle.org/dependency-verification"><components><component><artifact name="{file.name}"><sha256 value="{sha(file)}"/></artifact></component></components></verification-metadata>')
    record={'schema':1,'sourceSha':'a'*40,'configuration':'kotlinCompilerClasspath','entries':[{'path':str(file),'sha256':sha(file)}]}
    path=work/'matcher-compiler.json';path.write_text(json.dumps(record))
    return run,record,file,path


def test_namespaced_pinned_compiler_ledger(tmp_path,monkeypatch):
    run,record,file,path=fixture(tmp_path,monkeypatch)
    assert m.compiler_inputs(run,'a'*40)==([file],record)


@pytest.mark.parametrize('kind',['source','schema','configuration','hash','duplicate','unapproved','alias','escape'])
def test_untrusted_compiler_inputs_fail_closed(tmp_path,monkeypatch,kind):
    run,record,file,path=fixture(tmp_path,monkeypatch)
    if kind=='source':record['sourceSha']='b'*40
    elif kind=='schema':record['schema']=True
    elif kind=='configuration':record['configuration']='other'
    elif kind=='hash':file.write_bytes(b'modified')
    elif kind=='duplicate':record['entries']*=2
    elif kind=='unapproved':record['entries'][0]['sha256']='0'*64
    elif kind=='alias':
        alias=file.with_name('alias.jar');alias.symlink_to(file);record['entries'][0]['path']=str(alias)
    else:
        other=run.root/file.name;other.write_bytes(file.read_bytes());record['entries'][0]['path']=str(other)
    path.write_text(json.dumps(record))
    with pytest.raises(Failure):m.compiler_inputs(run,'a'*40)


def test_runtime_requires_exact_prepared_jar_owners():
    entries=[{'kind':'jar','path':'/cache/'+name,'sha256':'a'*64} for name in m.RUNTIME_JARS]
    assert m.runtime_inputs({'classpath':entries})==entries
    for bad in (entries[:-1],entries+[entries[0]]):
        with pytest.raises(Failure):m.runtime_inputs({'classpath':bad})


def test_no_legacy_receipt_or_download_and_strict_result_remains_separate():
    source=Path(m.__file__).read_text();controller=Path(c.__file__).read_text()
    assert 'validation.json' not in source and 'aar-input.json' not in source
    assert 'gradle_args(run, config, offline=True)' in source
    assert source.count('policy.verify_capability(cap, source, capability.POLICY, run, prepared)')==2
    assert source.count('prepared_record_check(run, prepared, prepared_hash)')>=2
    assert 'except ' not in source  # integrity/deadline/cancellation errors cannot be swallowed
    assert controller.index('supplement=matcher_supplement.execute(')<controller.index("receipt['result']=run_probe(")
    assert controller.index("receipt['result']=run_probe(")<controller.index("if supplement is not None and supplement['status']!='PASS':")
    assert 'if experiments.ACTIVE==experiments.REAL_THREE:' in controller
    assert 'check=False' in source and "'overallDeadlineExtended': False" in source


def execute_fixture(tmp_path,monkeypatch,compile_code=0,test_code=0,mutate=None):
    run,ledger,compiler,ledger_path=fixture(tmp_path,monkeypatch)
    run.env={};run.calls=[]
    entries=[]
    for name in m.RUNTIME_JARS:
        path=run.work/'gradle'/name;path.write_bytes(name.encode())
        entries.append({'kind':'jar','path':str(path),'sha256':sha(path)})
    classes=run.work/'aar.jar';classes.write_bytes(b'aar-classes')
    java=run.work/'java';java.write_bytes(b'java')
    native=run.work/'opencv.so';native.write_bytes(b'native')
    prepared={'classpath':entries,'opencvClasses':str(classes)}
    (run.work/'prepared.json').write_text(json.dumps(prepared))
    cap=run.work/'capability.json';cap.write_bytes(b'capability')
    verified={'inputs':{'java':{'path':str(java),'sha256':sha(java)},'classes':{'path':str(classes),'sha256':sha(classes)}},
              'native':{'opencv':{'path':str(native),'sha256':sha(native)}}}
    for rel in m.SOURCES:
        file=run.root/'app/src/main/java/com/realityforge/scanner/engine'/rel;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(b'source')
    for name in ('ProductionMatcherHostTest.kt','HostRunner.kt'):
        file=run.root/'tools/feature_matcher_validation'/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(b'test')
    monkeypatch.setattr(m.policy,'verify_capability',lambda *a:verified)
    monkeypatch.setattr(c,'classpath_check',lambda *a,**k:None)
    monkeypatch.setattr(c,'gradle_args',lambda *a,**k:['gradle','--offline','--dependency-verification','strict'])
    def command(label,args,**kwargs):
        run.calls.append((label,list(map(str,args)),kwargs))
        if label=='matcher-compiler-capture':
            (run.work/'matcher-task-graph.json').write_text(json.dumps({'tasks':[':app:eightMatcherCompiler'],'externalNativeTasks':[]}))
            return b'',0
        if label=='matcher-supplement-compile':
            if mutate=='cancel':raise c.Cancelled('cancelled')
            if mutate=='deadline':raise c.Deadline('deadline')
            if compile_code==0:
                output=run.work/'matcher-supplement/classes'
                for name in ('HostRunnerKt.class','FeatureMatcher.class','TentativeMatches.class','ProductionMatcherHostTest.class'):
                    path=output/name if name=='HostRunnerKt.class' else output/'com/realityforge/scanner/engine/matching'/name
                    path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(name.encode())
            if mutate=='runtime':Path(entries[0]['path']).write_bytes(b'mutated')
            return b'',compile_code
        assert label=='matcher-supplement-test'
        if mutate=='output':(run.work/'matcher-supplement/classes/HostRunnerKt.class').write_bytes(b'mutated')
        return b'',test_code
    run.command=command
    return run,prepared,cap


@pytest.mark.parametrize('compile_code,test_code,status,commands',[(0,0,'PASS',3),(1,0,'FAIL',2),(0,10,'FAIL',3)])
def test_actual_command_orchestration_separates_ordinary_failure(tmp_path,monkeypatch,compile_code,test_code,status,commands):
    run,prepared,cap=execute_fixture(tmp_path,monkeypatch,compile_code,test_code)
    result=m.execute(run,'a'*40,run.work/'config.json',prepared,cap)
    assert result['status']==status and len(run.calls)==commands
    assert json.loads((run.work/'matcher-supplement.json').read_text())==result
    compile=run.calls[1]
    assert compile[2]['seconds']==120 and compile[2]['check'] is False
    assert '-no-stdlib' in compile[1] and '-no-reflect' in compile[1]
    assert '384m' in ' '.join(compile[1]) and str(run.root/'app/build') not in ' '.join(compile[1])
    if commands==3:
        assert run.calls[2][2]['seconds']==90
        assert run.calls[2][1][-2]=='HostRunnerKt'


@pytest.mark.parametrize('mutation,error',[('runtime',Failure),('output',Failure),('cancel',c.Cancelled),('deadline',c.Deadline)])
def test_integrity_cancellation_and_deadline_propagate_without_success(tmp_path,monkeypatch,mutation,error):
    run,prepared,cap=execute_fixture(tmp_path,monkeypatch,mutate=mutation)
    with pytest.raises(error):m.execute(run,'a'*40,run.work/'config.json',prepared,cap)
    assert not (run.work/'matcher-supplement.json').exists()
    if mutation!='output':assert len(run.calls)==2  # never enter test after invalid compile boundary


@pytest.mark.parametrize('compile_code,test_code',[(-9,0),(0,-11)])
def test_signaled_processes_are_not_ordinary_test_failures(tmp_path,monkeypatch,compile_code,test_code):
    run,prepared,cap=execute_fixture(tmp_path,monkeypatch,compile_code,test_code)
    with pytest.raises(Failure,match='terminated by signal'):
        m.execute(run,'a'*40,run.work/'config.json',prepared,cap)
    assert not (run.work/'matcher-supplement.json').exists()


@pytest.mark.parametrize('code',[1,2,137])
def test_runner_integrity_failure_is_not_a_junit_failure(tmp_path,monkeypatch,code):
    run,prepared,cap=execute_fixture(tmp_path,monkeypatch,test_code=code)
    with pytest.raises(Failure,match='runner integrity/launch failure'):
        m.execute(run,'a'*40,run.work/'config.json',prepared,cap)
    assert not (run.work/'matcher-supplement.json').exists()


def test_reserved_failure_exit_follows_count_and_post_native_mapping_check():
    runner=(Path(m.__file__).parents[1]/'feature_matcher_validation/HostRunner.kt').read_text()
    assert runner.index('maps();check(result.runCount==9)') < runner.index('kotlin.system.exitProcess(10)')
    assert 'if(!result.wasSuccessful())kotlin.system.exitProcess(10)' in runner
    assertion_guard='check(result.failures.all { it.exception is AssertionError })'
    assert runner.index('maps();check(result.runCount==9)') < runner.index(assertion_guard) < runner.index('kotlin.system.exitProcess(10)')


def test_compiler_internal_exit_does_not_continue_to_test(tmp_path,monkeypatch):
    run,prepared,cap=execute_fixture(tmp_path,monkeypatch,compile_code=2)
    with pytest.raises(Failure,match='compiler internal/launch failure'):
        m.execute(run,'a'*40,run.work/'config.json',prepared,cap)
    assert len(run.calls)==2 and not (run.work/'matcher-supplement.json').exists()
