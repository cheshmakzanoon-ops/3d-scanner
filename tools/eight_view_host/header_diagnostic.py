#!/usr/bin/env python3
"""One source-bound header-admission observation; never native/runtime qualification."""
from __future__ import annotations
import hashlib,json,os,platform,re,shutil,sys,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.eight_view_host import capability as c,policy,header_provider as hp,header_inventory as hi
from tools.eight_view_host.runtime import Runner,Failure,Blocked,sha,safe_display
from tools.eight_view_host.controller import bounded_bytes,bounded_hash
from tools.native_process import native_compile_env
BASE='25a0176d7cd93ba451932cf65b016331ca13038b'
MESSAGE='test: inventory host header escapes once'
ALLOWED_PATHS=frozenset((
 '.github/workflows/host-header-diagnostic.yml','tools/eight_view_host/header_inventory.py',
 'tools/eight_view_host/header_diagnostic.py','tests/test_eight_view_header_inventory.py',
 'tests/test_eight_view_header_diagnostic.py',
 'docs/development/EIGHT_VIEW_HOST_GRADLE_LIFECYCLE.md','SOURCE_MANIFEST.sha256'))
CLASSIFICATION='HOST_HEADER_ADMISSION_DIAGNOSTIC_NOT_QUALIFICATION'
EVIDENCE_LIMIT=32*1024


def source_guard(run,expected):
    # The full pipeline's frozen TRIGGER_PATHS deliberately do not admit this
    # separate diagnostic. Keep its single-parent exact-seven-file domain local.
    def git(label,*args):return run.command('header-git-'+label,['git',*args],seconds=30,limit=32*1024)[0].decode().strip()
    if git('head','rev-parse','HEAD')!=expected:raise Failure('Diagnostic checkout source mismatch')
    if git('status','status','--porcelain=v1','--untracked-files=all'):raise Failure('Diagnostic checkout is not clean')
    if git('parents','show','--format=%P','--no-patch','HEAD').split()!=[BASE]:raise Failure('Diagnostic missing history/merge/unexpected parent')
    if git('parent','rev-parse','HEAD^')!=BASE:raise Failure('Diagnostic parent object unavailable')
    if set(git('paths','diff','--no-renames','--name-only',BASE,expected).splitlines())!=ALLOWED_PATHS:
        raise Failure('Diagnostic exact changed-path admission mismatch')
    run.command('header-source-manifest',['python3','tools/source_manifest.py','verify','--source','HEAD'],seconds=60,limit=32*1024)
    return {'sourceSha':expected,'parentSha':BASE,'tree':git('tree','rev-parse','HEAD^{tree}'),
            'sourceManifestSha256':sha(run.root/'SOURCE_MANIFEST.sha256'),'changedPaths':sorted(ALLOWED_PATHS)}


def source_postcheck(run,source):
    for label,args,wanted in (
        ('head',['rev-parse','HEAD'],source['sourceSha']),
        ('tree',['rev-parse','HEAD^{tree}'],source['tree']),
        ('status',['status','--porcelain=v1','--untracked-files=all'],'')):
        raw,_=run.command('header-post-'+label,['git',*args],seconds=30,limit=32*1024,cleanup=True)
        if raw.decode().strip()!=wanted:raise Failure('Diagnostic source changed during header inspection')
    if sha(run.root/'SOURCE_MANIFEST.sha256')!=source['sourceManifestSha256']:raise Failure('Diagnostic source manifest changed')


def file_identity(path,run,maximum=512*1024**2):
    path=Path(path).resolve(strict=True)
    return {'path':str(path),'sha256':bounded_hash(path,maximum,run)}


def observe(run,receipt):
    if platform.system()!='Linux' or platform.machine()!='x86_64':raise Blocked('Linux x86_64 host required')
    os_release=bounded_bytes(Path('/etc/os-release').resolve(strict=True),16*1024,run)
    if 'VERSION_ID="24.04"' not in os_release.decode():raise Blocked('Reviewed Ubuntu24.04 runner required')
    for name in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS'):
        if run.env.get(name):raise Blocked('Unreviewed JVM override retained; header diagnostic refused: '+name)
    java_home=Path(run.env.get('JAVA_HOME_17_X64','/missing')).resolve()
    release=bounded_bytes(java_home/'release',16*1024,run)
    if not (java_home/'bin/java').is_file() or not re.search(rb'^JAVA_VERSION="17\.',release,re.M):raise Blocked('Preinstalled JDK17 missing')
    run.env.update(JAVA_HOME=str(java_home),PATH=str(java_home/'bin')+os.pathsep+run.env.get('PATH',''))
    java=java_home/'bin/java'
    receipt['image']={'system':platform.system(),'architecture':platform.machine(),
        'osReleaseSha256':hashlib.sha256(os_release).hexdigest(),'imageOS':run.env.get('ImageOS','')[:256],
        'imageVersion':run.env.get('ImageVersion','')[:256]}
    receipt['jdk']={'java':file_identity(java,run),'release':file_identity(java_home/'release',run,16*1024)}
    version,_=run.command('header-java-version',[java,'-version'],seconds=15,limit=2048)
    receipt['jdk']['version']=version.decode(errors='replace')
    compiler=Path(shutil.which('g++',path=run.env['PATH']) or '/missing').resolve()
    cc=Path(shutil.which('gcc',path=run.env['PATH']) or '/missing').resolve()
    receipt['compiler']={'cxx':file_identity(compiler,run),'c':file_identity(cc,run)}
    version,_=run.command('header-compiler-version',[compiler,'-dumpfullversion'],seconds=15,limit=2048,env=native_compile_env(run.env))
    text=version.decode().strip();receipt['compiler']['version']=text
    requirements=json.loads(bounded_bytes(c.POLICY,64*1024,run))
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*',text) or int(text.split('.')[0]) not in requirements['compiler']['allowedMajorVersions']:
        raise Blocked('Unreviewed GNU compiler major')
    receipt['policySha256']=sha(c.POLICY)
    header_proof=hp.acquire(run,receipt['source']['sourceSha'])
    receipt['headerProvider']=header_proof
    header_root=hp.validate(header_proof,receipt['source']['sourceSha'],run)
    if header_root!=hp.INCLUDE_ROOT:raise Failure('Unexpected admitted Clang header root')
    roots=[*c.system_header_roots(java),header_root]  # Only the exact proved provider extends unchanged roots.
    encoded=json.dumps([str(root) for root in roots],ensure_ascii=True,separators=(',',':')).encode()
    receipt['headerRoots']={'count':len(roots),'sha256':hashlib.sha256(encoded).hexdigest(),
        'paths':[str(root)[:512] for root in roots[:8]],'omitted':max(0,len(roots)-8),
        'pathTextTruncated':any(len(str(root))>512 for root in roots[:8])}
    receipt['snapshot']={'attempted':False,'status':'NOT_RUN_METADATA_ONLY'}
    inventory=hi.scan(run,roots)
    receipt['headerInventory']=inventory
    if not inventory.get('complete') or inventory.get('status')!='COMPLETE':
        raise Failure('Header escape metadata inventory incomplete; admission refused')
    if inventory['escapedCount']:
        raise Failure('Header escape metadata inventory found escapes; admission refused')



def record_failure(receipt,error):
    receipt['status']='FAIL'
    text=str(error);prefix,marker,payload=text.partition('EIGHT_VIEW_HEADER_ESCAPE ')
    failure={'type':type(error).__name__,'message':prefix[:1024]}
    if marker:
        try:
            raw=payload.encode('ascii')
            if len(raw)<=16*1024:failure['headerEscape']=json.loads(raw)
            else:failure['diagnosticMetadata']='OVERSIZE_WITHHELD'
        except (UnicodeError,ValueError,TypeError):failure['diagnosticMetadata']='MALFORMED_WITHHELD'
    if 'failure' not in receipt:receipt['failure']=failure
    else:receipt.setdefault('secondaryFailures',[]).append(failure)
    if receipt.get('snapshot',{}).get('status')=='PENDING':receipt['snapshot']['status']='FAIL'


def write_receipt(destination,receipt):
    raw=(json.dumps(receipt,sort_keys=True,ensure_ascii=True,separators=(',',':'))+'\n').encode('ascii')
    if len(raw)>EVIDENCE_LIMIT or safe_display(raw)[1]=='WITHHELD_SECRET_MARKER':
        receipt={'schema':1,'classification':CLASSIFICATION,'status':'FAIL','qualified':False,'pipelineAttempts':0,
            'failure':{'type':'EvidenceLimit','message':'Original diagnostic evidence withheld: size/secret-marker guard'},
            'evidenceComplete':False,'originalBytes':len(raw),'originalSha256':hashlib.sha256(raw).hexdigest()}
        raw=(json.dumps(receipt,sort_keys=True,separators=(',',':'))+'\n').encode('ascii')
    destination=Path(destination)
    if destination.is_symlink() or destination.exists():raise Failure('Diagnostic evidence destination must be fresh')
    destination.mkdir()
    # Exclusive, bounded single-file artifact; no raw logs/header contents uploaded.
    with (destination/'receipt.json').open('xb') as stream:stream.write(raw)
    return receipt


def main():
    receipt={'schema':1,'classification':CLASSIFICATION,'status':'FAIL','qualified':False,'pipelineAttempts':0,
             'snapshot':{'attempted':False,'status':'NOT_RUN_METADATA_ONLY'}}
    run=None;source=None;work=None;destination=None
    try:
        temporary=Path(os.environ.get('RUNNER_TEMP','/missing')).resolve(strict=True)
        if not temporary.is_dir():raise Failure('Runner temporary directory unavailable')
        destination=temporary/'rf-header-evidence'
        if len(sys.argv)!=1:raise Failure('No command-line/root overrides accepted')
        work=Path(tempfile.mkdtemp(prefix='rf-header-work-',dir=temporary))
        run=Runner(ROOT,work,seconds=270);run.install_signals()
        event=json.loads(bounded_bytes(run.env.get('GITHUB_EVENT_PATH','/missing'),1024**2,run))
        selected=policy.event_guard(run.env,event,base=BASE,message=MESSAGE)
        source=source_guard(run,selected);receipt['source']=source
        observe(run,receipt)
        receipt['status']='INVENTORY_COMPLETE'
    except BaseException as error:record_failure(receipt,error)
    finally:
        if run is not None:
            if 'headerProvider' in receipt:
                try:
                    hp.validate(receipt['headerProvider'],source['sourceSha'],run);receipt['headerProviderPostcheck']='PASS'
                except BaseException as error:
                    receipt['headerProviderPostcheck']='FAIL';record_failure(receipt,error)
            if source is not None:
                try:source_postcheck(run,source);receipt['sourcePostcheck']='PASS'
                except BaseException as error:receipt['sourcePostcheck']='FAIL';record_failure(receipt,error)
            try:run.cleanup();receipt['ownedCleanup']='PASS'
            except BaseException as error:receipt['ownedCleanup']='FAIL';record_failure(receipt,error)
            finally:
                try:run.restore_signals()
                except BaseException as error:record_failure(receipt,error)
        if work is not None:
            try:shutil.rmtree(work)
            except BaseException as error:record_failure(receipt,error)
    try:
        if destination is None:raise Failure('No admitted diagnostic evidence destination')
        receipt=write_receipt(destination,receipt)
        print(json.dumps({'classification':CLASSIFICATION,'status':receipt['status'],'evidence':'rf-header-evidence/receipt.json'},sort_keys=True))
    except BaseException as error:
        print(json.dumps({'classification':CLASSIFICATION,'status':'FAIL','evidenceErrorType':type(error).__name__},sort_keys=True));return 1
    return 0 if receipt['status']=='INVENTORY_COMPLETE' else 1

if __name__=='__main__':raise SystemExit(main())
