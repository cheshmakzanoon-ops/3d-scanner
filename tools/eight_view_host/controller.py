#!/usr/bin/env python3
"""One source-bound eight-view HOST attempt. Deliberately NOT ARMED until reviewed."""
from __future__ import annotations
import hashlib, importlib.util, json, os, platform, re, shutil, sys, time, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.eight_view_host.runtime import Runner,Failure,Blocked,Deadline,Cancelled,sha,atomic_json,inventory,safe_display,frame
from tools.eight_view_host import policy,capability
from tools.device.software_emulator.diagnostic import license_snapshot,verify_license_snapshot,package_metadata
HERE=Path(__file__).resolve().parent
ADAPTER='docs/development/experiments/eight-view-sparse-runtime-successor'
FIXTURE='docs/development/experiments/new-cuboid-eight-view-160x120'
# Final qualified successor package is separately reviewed, never mutate frozen checkpoint2.
ADAPTER_MANIFEST='6d64c30f74751a6438af1c86687c35f8d8e1939d10611317735dbb9cf52fd8a5'
SOURCE_NAMES=('CandidateNativeLoaderTokenHostTest.kt','EightViewSparsePrefixHostTest.kt','EvidenceFailureHandling.kt','QualifiedCapability.kt','RuntimeProviderIdentity.kt','CapabilityIdentity.kt','OpaqueBitmapBridge.kt','SparsePrefixRunner.kt','SandboxNativeLoaderRunner.kt')
# Kotlin file facades/nested classes retain their owner prefix. This additional top-level
# object is declared in RuntimeProviderIdentity.kt, not in a same-named source file.
PROBE_CLASS_PREFIXES=tuple(Path(name).stem for name in SOURCE_NAMES)+('Api35ProviderContract',)
BASE_PACKAGES=('platforms;android-36','build-tools;36.0.0')

def verify_probe_classes_removed(root,run=None):
    for directory in [Path(root)/'app/build/tmp/kotlin-classes/debugUnitTest',Path(root)/'app/build/intermediates/javac/debugUnitTest']:
        if not directory.exists():continue
        _,entries=inventory(directory,max_bytes=2*1024**3,max_files=100000,seconds=phase_budget(run))
        for name in entries:
            phase_budget(run);path=directory/name
            if path.suffix=='.class' and any(path.name.startswith(prefix) for prefix in PROBE_CLASS_PREFIXES):
                raise Failure('Temporary probe class survived restoration: '+path.name)


def phase_budget(run,maximum=10,cleanup=True):
    return maximum if run is None else min(maximum,run.remaining(maximum,cleanup=cleanup))

def bounded_bytes(path,maximum,run=None):
    path=Path(path)
    if path.is_symlink() or not path.is_file():raise Failure('Bounded evidence/input is not a regular file')
    before=path.stat()
    if before.st_size>maximum:raise Failure('Evidence/input per-file byte limit')
    raw=bytearray()
    with path.open('rb') as stream:
        while True:
            phase_budget(run)
            block=stream.read(min(65536,maximum-len(raw)+1))
            if not block:break
            raw.extend(block)
            if len(raw)>maximum:raise Failure('Evidence/input expanded during read')
    after=path.stat()
    if (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns) or len(raw)!=before.st_size:
        raise Failure('Evidence/input changed during bounded read')
    return bytes(raw)

def bounded_hash(path,maximum,run=None,cleanup=True):
    path=Path(path)
    if path.is_symlink() or not path.is_file():raise Failure('Classpath input is not a regular file')
    before=path.stat();total=0;digest=hashlib.sha256()
    if before.st_size>maximum:raise Failure('Classpath per-file byte limit')
    with path.open('rb') as stream:
        while True:
            phase_budget(run,cleanup=cleanup)
            block=stream.read(65536)
            if not block:break
            total+=len(block)
            if total>maximum:raise Failure('Classpath expanded during read')
            digest.update(block)
    after=path.stat()
    if (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns) or total!=before.st_size:
        raise Failure('Classpath changed during bounded hash')
    return digest.hexdigest()

def classpath_check(record,run=None,cleanup=False):
    entries=record['classpath']
    if not isinstance(entries,list) or not 0<len(entries)<=2000:raise Failure('Classpath entry count')
    total=0;count=0;paths=[]
    for entry in entries:
        phase_budget(run,cleanup=cleanup);path=Path(entry['path']);paths.append(str(path))
        if path.is_symlink():raise Failure('Classpath alias changed')
        if entry['directory']:
            size,tree=inventory(path,max_bytes=2*1024**3-total,max_files=100000-count,seconds=phase_budget(run,cleanup=cleanup))
            total+=size;count+=len(tree)
            actual={str((path/name).resolve()):bounded_hash(path/name,512*1024**2,run,cleanup=cleanup) for name in tree if (path/name).is_file()}
            if actual!={p['path']:p['sha256'] for p in entry['files']}:raise Failure('Compiled directory changed')
        else:
            total+=path.stat().st_size;count+=1
            if total>2*1024**3 or count>100000:raise Failure('Classpath cumulative byte/count limit')
            if bounded_hash(path,512*1024**2,run,cleanup=cleanup)!=entry['sha256']:raise Failure('Runtime jar changed')
    if len(set(paths))!=len(paths) or record.get('classpathString',os.pathsep.join(paths))!=os.pathsep.join(paths):raise Failure('Classpath order/identity string differs')

CLASSPATH_FIELDS=('schema','task','classpath','classpathString','owners','opencvAar','opencvClasses','replacedOpenCvProvider','sourceSha')

def runtime_classpath_proof(run,prepared):
    baseline={key:prepared[key] for key in CLASSPATH_FIELDS};proof=[]
    for name in ('runtime-prepare.json','runtime-before-fork.json'):
        raw=bounded_bytes(run.work/name,16*1024**2,run)
        if json.loads(raw)!=baseline:raise Failure('Actual ordered runtime classpath/owners differ from immutable baseline')
        proof.append({'path':name,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
    return proof

def prepare_environment(run):
    if platform.system()!='Linux' or platform.machine()!='x86_64':raise Blocked('Linux x86_64 host required')
    if 'VERSION_ID="24.04"' not in Path('/etc/os-release').read_text():raise Blocked('Reviewed Ubuntu24.04 runner required')
    for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS'):
        if run.env.get(key):raise Blocked('Unreviewed JVM override is retained but cannot qualify: '+key)
    java=Path(run.env.get('JAVA_HOME_17_X64','/missing')).resolve()
    if not (java/'bin/java').is_file() or not re.search(r'^JAVA_VERSION="17\.',(java/'release').read_text(),re.M):raise Blocked('Preinstalled JDK17 missing')
    sdk=Path(run.env.get('ANDROID_HOME',run.env.get('ANDROID_SDK_ROOT','/missing'))).resolve()
    try:licenses=license_snapshot(sdk)
    except Exception as error:raise Blocked('Preaccepted SDK licenses unavailable') from error
    run.sdk_license_state=(sdk,licenses)  # Retained even if setup raises before returning.
    run.env.update(JAVA_HOME=str(java),PATH=str(java/'bin')+os.pathsep+run.env.get('PATH',''),ANDROID_HOME=str(sdk),ANDROID_SDK_ROOT=str(sdk))
    for key,name in [('HOME','home'),('GRADLE_USER_HOME','gradle'),('ANDROID_USER_HOME','home/.android'),('TMPDIR','tmp')]:
        path=run.work/name;path.mkdir(parents=True,exist_ok=True);run.env[key]=str(path)
    for rel in ('app/build','macrobenchmark/build','build','.gradle'):
        path=run.root/rel
        if path.exists():raise Blocked('Build output already exists; fresh checkout required: '+rel)
        run.extra_owned_roots.append(path)
    run.command('java-version',[java/'bin/java','-version'])
    missing=[p for p in BASE_PACKAGES if not sdk.joinpath(*p.split(';'),'package.xml').is_file()]
    if missing:
        manager=sdk/'cmdline-tools/latest/bin/sdkmanager'
        if not manager.is_file():raise Blocked('Existing SDK manager unavailable')
        primary=None
        try:raw,exit_code=run.command('sdk-explicit-existing-licenses',[manager,'--sdk_root='+str(sdk),*missing],seconds=300,check=False)
        except BaseException as error:primary=error;raise
        finally:
            try:verify_license_snapshot(sdk,licenses)
            except BaseException as error:
                if primary is None:raise
                primary.add_note('SDK license postcheck failed: '+str(error));run.evidence_errors.append('SDK license postcheck failure')
        if exit_code or re.search(rb'not accepted|Accept\?\s*\(|licenses?\s+have not been accepted',raw,re.I):raise Blocked('SDK prerequisite blocked; no license acceptance or retry')
    metadata=[package_metadata(sdk,p) for p in BASE_PACKAGES]
    return sdk,licenses,{'java':{'path':str(java),'sha256':sha(java/'bin/java'),'releaseSha256':sha(java/'release')},'sdk':metadata,'preexistingLicenseSha256':licenses}

def gradle_args(run,cfg=None,offline=False):
    args=[run.root/'gradlew','--no-daemon','--no-parallel','--max-workers=1','--no-build-cache','--no-configuration-cache','--dependency-verification','strict',
      '-Pandroid.builder.sdkDownload=false','-Pkotlin.compiler.execution.strategy=in-process','-Pkotlin.daemon.useFallbackStrategy=false',
      '-Dorg.gradle.jvmargs=-Xmx3g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 -Duser.home='+str(run.work/'home')]
    if offline:args.append('--offline')
    if cfg:args.extend(['-I',HERE/'probe.init.gradle','-PeightConfig='+str(cfg)])
    return args

def load_adapter(root):
    package=root/ADAPTER;manifest=package/'SHA256SUMS'
    if not re.fullmatch('[0-9a-f]{64}',ADAPTER_MANIFEST) or sha(manifest)!=ADAPTER_MANIFEST:raise Blocked('Qualified adapter checkpoint is NOT ARMED')
    for line in manifest.read_text().splitlines():
        h,name=line.split('  ')
        if '/' in name or name in ('.','..') or not re.fullmatch('[0-9a-f]{64}',h) or sha(package/name)!=h:raise Failure('Adapter source manifest mismatch')
    spec=importlib.util.spec_from_file_location('eight_observation_inputs',package/'inputs.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return package,module

def prepare_runtime(run,source,package,inputs):
    staging=run.work/'observations';observed=inputs.stage(run.root/FIXTURE,staging)
    atomic_json(run.work/'observation-proof.json',observed)
    sources=run.work/'sources';sources.mkdir()
    for name in SOURCE_NAMES:shutil.copyfile(package/name,sources/name)
    (run.work/'measurement').mkdir()
    cfg={'schema':1,'phase':'prepare','sources':str(sources),'work':str(run.work),'sourceSha':source,'selector':policy.SELECTOR,'observations':str(staging)}
    config=run.work/'gradle-config.json';atomic_json(config,cfg)
    run.command('gradle-task-graph',[*gradle_args(run,config),':app:eightPrepare','--dry-run'],seconds=300)
    graph=json.loads((run.work/'task-graph.json').read_text())
    if graph.get('externalNativeTasks'):raise Blocked('Unexpected Android native tasks in HOST compilation graph; no eager NDK install')
    if graph.get('compileSdk') not in (36,'android-36') or graph.get('buildTools')!='36.0.0':raise Failure('Unexpected actual Android compilation configuration')
    run.command('gradle-prepare',[*gradle_args(run,config),':app:eightPrepare'],seconds=900)
    prepared=json.loads((run.work/'prepared.json').read_text())
    classpath_check(prepared,run)
    pinned=json.loads(capability.POLICY.read_text())
    natives=[entry for entry in prepared['classpath'] if Path(entry['path']).name=='nativeruntime-dist-compat-1.0.16.jar']
    if len(natives)!=1 or natives[0].get('sha256')!=pinned['robolectricNativeRuntimeSha256']:raise Failure('Missing/unpinned Robolectric native graphics/SQLite distribution')
    prepared['robolectricNativeDistribution']={'path':natives[0]['path'],'sha256':natives[0]['sha256']}
    sdk=run.root/'app/build/robolectric-sdk';framework=sdk/'android-all-15-robolectric-12650502.jar'
    if {p.name for p in sdk.iterdir()}!={framework.name}:raise Failure('Unexpected offline framework directory')
    if sha(framework)!=pinned['androidAllSha256']:raise Failure('Unpinned offline framework')
    prepared['offlineFramework']={'path':str(framework),'sha256':sha(framework)}
    # API35 provider resources are in android-all, NOT nativeruntime-dist-compat.
    maximum=pinned['robolectricNativeProviderMaximumBytes']
    with zipfile.ZipFile(framework) as archive:
        entries=[item for item in archive.infolist() if item.filename==pinned['robolectricNativeRuntimeEntry']]
        if len(entries)!=1 or entries[0].file_size!=pinned['robolectricNativeProviderEntryBytes'] or not 0<entries[0].file_size<=maximum:raise Failure('Missing/unexpected actual API35 native provider archive entry')
        with archive.open(entries[0]) as stream:
            digest=hashlib.sha256();total=0
            while block:=stream.read(65536):
                total+=len(block)
                if total>maximum:raise Failure('Native provider entry expansion limit')
                digest.update(block)
        if total!=entries[0].file_size:raise Failure('Incomplete native provider archive entry')
    prepared['runtimeProvider']={**prepared['offlineFramework'],'entry':pinned['robolectricNativeRuntimeEntry'],'entrySha256':digest.hexdigest(),'entryBytes':total,'mappingScope':'JAR_IDENTITY_ONLY_UNTIL_ACTUAL_JVM_MAPPING_RECEIPT'}
    atomic_json(run.work/'prepared.json',prepared)
    if inputs.validate(staging)!=observed:raise Failure('Observation input changed during preparation')
    return config,cfg,prepared,observed

def runtime_provider_proof(run,source,cap,verified):
    """Source-bound own-JVM observed identity receipts, not post-exit live mapping claims."""
    previous=None;proof=[]
    expected_dependencies={}
    for elf in [verified['opencvElf'],*verified['scannerElf'].values()]:
        for provider in elf['providers'].values():
            path=provider['path'];digest=provider['sha256']
            if path in expected_dependencies and expected_dependencies[path]!=digest:raise Failure('Conflicting native provider closure')
            expected_dependencies[path]=digest
    for phase in ('after-bitmap-room','after-room-reopen'):
        path=run.work/'measurement'/('runtime-providers-'+phase+'.json')
        if path.is_symlink() or not path.is_file() or path.stat().st_size>256*1024:raise Failure('Missing/bounded runtime provider receipt')
        value=json.loads(bounded_bytes(path,256*1024,run))
        if (type(value.get('schema')) is not int or value['schema']!=1 or value.get('phase')!=phase
            or value.get('status')!='OBSERVED_PROVIDER_IDENTITIES_MATCH' or value.get('sourceSha')!=source
            or value.get('policySha256')!=sha(capability.POLICY) or value.get('capabilitySha256')!=sha(cap)
            or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',value.get('sqliteVersion',''))
            or value.get('activity')!=['actual Bitmap pixel roundtrip','actual Room SELECT sqlite_version()']):raise Failure('Unqualified/foreign actual runtime provider evidence')
        native=value.get('scannerProviders',[])
        if len(native)!=3 or {x.get('role') for x in native}!={'opencv','shim','engine'}:raise Failure('Incomplete mapped native roles')
        for item in native:
            expected=verified['native'][item['role']]
            if item.get('path')!=expected['path'] or item.get('sha256')!=expected['sha256'] or type(item.get('inode')) is not int or item['inode']<=0:raise Failure('Mapped scanner provider differs')
        dependencies=value.get('scannerElfDependencies',[])
        if len(dependencies)!=len(expected_dependencies) or {x.get('path') for x in dependencies}!=set(expected_dependencies):raise Failure('Incomplete mapped ELF dependency closure')
        for item in dependencies:
            if item.get('sha256')!=expected_dependencies[item['path']] or type(item.get('inode')) is not int or item['inode']<=0:raise Failure('Mapped ELF dependency differs')
        provider=value.get('androidRuntimeProvider',{});archive=verified['runtimeProvider']
        if (provider.get('sha256')!=archive['entrySha256'] or provider.get('bytes')!=archive['entryBytes']
            or provider.get('archivePath')!=archive['path'] or provider.get('archiveSha256')!=archive['sha256']
            or provider.get('archiveEntry')!=archive['entry'] or type(provider.get('inode')) is not int or provider['inode']<=0):raise Failure('Mapped graphics/SQLite provider differs from pinned archive entry')
        observed=Path(provider.get('path','/missing'))
        if not observed.is_absolute() or not observed.is_relative_to(run.work/'runtime-tmp') or observed.name!='libandroid_runtime.so' or '..' in observed.parts:raise Failure('Mapped runtime provider outside owned JVM tmp')
        stable={'native':sorted(native,key=lambda x:x['role']),'dependencies':sorted(dependencies,key=lambda x:x['path']),'provider':provider,'sqliteVersion':value['sqliteVersion']}
        if previous is not None and stable!=previous:raise Failure('Runtime provider changed across actual Room reopen')
        previous=stable;proof.append({'phase':phase,'sha256':sha(path)})
    return {'scope':'SOURCE_BOUND_OWN_JVM_MAPPINGS_AT_CAPTURE_NOT_LIVE_AFTER_EXIT','receipts':proof}

def run_probe(run,source,config,cfg,prepared,cap,inputs,observed):
    verified=policy.verify_capability(cap,source,capability.POLICY,run,prepared)
    aliases=run.work/'aliases';aliases.mkdir()
    for name,key in [('libopencv_java4.so','opencv'),('librealityforge.so','shim')]:
        target=verified['native'][key]['path'];(aliases/name).symlink_to(target);run.aliases[str(aliases/name)]=target
    cfg.update(phase='run',runOwner=run.owner,preparedSha256=sha(run.work/'prepared.json'),capability=str(cap),capabilitySha256=sha(cap),policySha256=sha(capability.POLICY),aliasDir=str(aliases))
    atomic_json(config,cfg)
    classpath_check(prepared,run)
    original=None
    run.pipeline_attempts=1  # Only now is the selected invocation attempted; gates above are not attempts.
    try:
        raw,exit_code=run.command('eight-view-test',[*gradle_args(run,config,offline=True),':app:eightProbe'],seconds=600,check=False)
        if exit_code:raise Failure('Actual eight-view invocation failed; original logs/events retained')
    except BaseException as error:
        original=error;raise
    finally:
        # No subprocess or retry: verify the exact runtime inputs even when the selected
        # invocation fails/cancels. Cleanup budget is available; cancellation stays flagged.
        run.runtime_input_postcheck={'status':'PENDING','phase':'after-selected-invocation'}
        try:
            run.remaining(1,cleanup=True)
            policy.verify_capability(cap,source,capability.POLICY,run,prepared)
            run.remaining(1,cleanup=True);classpath_check(prepared,run,cleanup=True)
            run.runtime_input_postcheck['actualClasspathCaptures']=runtime_classpath_proof(run,prepared)
            run.remaining(1,cleanup=True)
            if inputs.validate(run.work/'observations')!=observed:raise Failure('Observation inputs changed during test')
            run.remaining(1,cleanup=True)
            run.runtime_input_postcheck['status']='PASS'
        except BaseException as postcheck:
            run.runtime_input_postcheck.update(status=getattr(postcheck,'status','FAIL'),errorType=type(postcheck).__name__,error=str(postcheck))
            if original is None:raise
            original.add_note('Runtime input postcheck: '+type(postcheck).__name__+': '+str(postcheck))
            run.evidence_errors.append('Runtime input postcheck incomplete: '+str(postcheck))
    total=0;count=0;trees={}
    for name in ('xml','measurement'):
        size,entries=inventory(run.work/name,max_bytes=31*1024**2-total,max_files=1990-count,seconds=phase_budget(run))
        total+=size;count+=len(entries);trees[name]=entries
    xmls=[run.work/'xml'/name for name in trees['xml'] if '/' not in name and re.fullmatch(r'TEST-.*\.xml',name)]
    if len(xmls)!=1:raise Failure('Missing/duplicate selected JUnit report')
    bounded_bytes(xmls[0],2*1024**2,run)  # Admit XML size/identity before the parser reads it.
    events=[json.loads(x) for x in bounded_bytes(run.work/'test-events.jsonl',256*1024,run).decode().splitlines()]
    proof=policy.junit_proof(xmls[0],events)
    result=json.loads(bounded_bytes(run.work/'measurement/sparse-result.json',4*1024**2,run))
    pairs=json.loads(bounded_bytes(run.work/'measurement/pair-events.json',8*1024**2,run))
    identity=json.loads(bounded_bytes(run.work/'measurement/input-identity.json',64*1024,run))
    return {'junit':proof,'measurement':policy.measurement_proof(result,pairs,identity,observed),'runtimeProviders':runtime_provider_proof(run,source,cap,verified),'capabilitySha256':sha(cap)}

def seal_receipt(destination,receipt,run=None):
    destination=Path(destination)
    inventory(destination,32*1024**2,2000,seconds=phase_budget(run))
    raw=(json.dumps(receipt,sort_keys=True,indent=2)+'\n').encode()
    if len(raw)>256*1024:raise Failure('Terminal receipt framing byte limit')
    if safe_display(raw)[1]=='WITHHELD_SECRET_MARKER':raise Failure('Terminal receipt withheld for secret marker')
    phase_budget(run);atomic_json(destination/'receipt.json',receipt)
    _,entries=inventory(destination,32*1024**2,2000,seconds=phase_budget(run))
    manifest=''.join(f'{bounded_hash(destination/name,32*1024**2,run)}  {name}\n' for name in sorted(entries) if (destination/name).is_file() and name!='SHA256SUMS')
    phase_budget(run);(destination/'SHA256SUMS').write_text(manifest)
    inventory(destination,32*1024**2,2000,seconds=phase_budget(run))

def evidence_package(run,receipt,destination):
    destination=Path(destination)
    if destination.exists():raise Failure('Evidence destination must be fresh')
    phase_budget(run);destination.mkdir();selected=[];selected_bytes=0;selected_entries=0
    def add(path,rel):
        nonlocal selected_bytes,selected_entries
        phase_budget(run)
        if path.is_symlink() or not path.is_file() or path.stat().st_size>16*1024**2:raise Failure('Evidence symlink/per-file size')
        selected_bytes+=path.stat().st_size;selected_entries+=1
        if selected_bytes>31*1024**2 or selected_entries>1990:raise Failure('Evidence package byte/count limit')
        selected.append((path,rel))
    names=['commands.json','prepared.json','task-graph.json','effective-test-config.json','runtime-prepare.json','runtime-before-fork.json','observation-proof.json','test-events.jsonl']
    for name in names:
        if (run.work/name).exists():add(run.work/name,name)
    for directory in ['logs','measurement','xml']:
        root=run.work/directory
        if not root.exists():continue
        _,entries=inventory(root,max_bytes=31*1024**2-selected_bytes,max_files=1990-selected_entries,seconds=phase_budget(run))
        for name in sorted(entries):
            path=root/name
            if path.is_file():add(path,path.relative_to(run.work).as_posix())
            else:
                selected_entries+=1
                if selected_entries>1990:raise Failure('Evidence directory count limit')
    for name in ['capability.json','pending.json','source-files.json','scanner-source.json','opencv-abi.json','scanner-abi.json','consumed-inputs.json','prebuild-inputs.json','consumed-provenance.json']:
        path=run.work/'capability'/name
        if path.exists():add(path,'capability/'+name)
    add(capability.POLICY,'build-policy.json')
    total=0;files=[];withheld=[]
    for path,rel in selected:
        raw=bounded_bytes(path,16*1024**2,run);total+=len(raw)
        if total>31*1024**2:raise Failure('Evidence package expanded past byte limit')
        binary=rel.startswith('measurement/') and (path.suffix in ('.rfac','.rfsm','.rfis') or re.fullmatch(r'artifact-[0-9a-f]{64}\.bin',path.name))
        if binary:
            if raw.startswith((b'\x7fELF',b'PK\x03\x04',b'MZ')):raise Failure('Executable/library archive is not checkpoint evidence')
            if path.suffix=='.bin' and path.stem.removeprefix('artifact-')!=hashlib.sha256(raw).hexdigest():raise Failure('Content-addressed checkpoint mismatch')
        else:
            _,status=safe_display(raw)
            if status=='WITHHELD_SECRET_MARKER':withheld.append({'path':rel,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'reason':status});continue
        target=destination/rel;target.parent.mkdir(parents=True,exist_ok=True);phase_budget(run);target.write_bytes(raw)
        files.append({'path':rel,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
    receipt['evidence']={'files':files,'withheld':withheld,'complete':not withheld,'bytes':sum(x['bytes'] for x in files)}
    if withheld and receipt['status']=='PASS':receipt['status']='FAIL';receipt['evidenceFailure']='Required raw evidence withheld; no complete PASS'
    seal_receipt(destination,receipt,run)
    return destination

def emergency_terminal_downgrade(destination,receipt):
    """Fixed-size final invalidation after deadline; never scan/hash the artifact tree or PASS.

    File IO may still fail (reported by caller). Existing evidence is retained, but its
    terminal receipt cannot remain PASS merely because normal sealing time expired.
    """
    destination=Path(destination)
    if destination.is_symlink() or not destination.is_dir():raise Failure('Emergency evidence destination unavailable')
    if receipt.get('status')=='PASS':raise Failure('Emergency terminal path cannot publish PASS')
    final=dict(receipt);final['evidence']={'complete':False,'terminalSeal':'EMERGENCY_INCOMPLETE_NOT_QUALIFICATION'}
    raw=(json.dumps(final,sort_keys=True,indent=2)+'\n').encode()
    if len(raw)>256*1024 or safe_display(raw)[1]=='WITHHELD_SECRET_MARKER':
        final={key:receipt.get(key) for key in ('schema','status','sourceSha','pipelineAttempts','ownedCleanup')}
        final.update(firstFailureType=receipt.get('firstFailure',{}).get('type'),evidence={'complete':False,'terminalSeal':'EMERGENCY_COMPACT_INCOMPLETE'})
    # Invalidate status first; even a malformed old manifest must not preserve stale PASS.
    atomic_json(destination/'receipt.json',final)
    raw=bounded_bytes(destination/'receipt.json',256*1024)
    old=[];manifest=destination/'SHA256SUMS'
    if manifest.exists():
        data=bounded_bytes(manifest,1024**2).decode()
        for line in data.splitlines():
            if not re.fullmatch(r'[0-9a-f]{64}  [^\r\n]+',line):raise Failure('Emergency old manifest malformed')
            if line.split('  ',1)[1] not in ('receipt.json','TERMINAL_INVALIDATION.json'):old.append(line)
    marker={'status':final['status'],'scope':'BOUNDED_EMERGENCY_INVALIDATION_NOT_COMPLETE_EVIDENCE'}
    atomic_json(destination/'TERMINAL_INVALIDATION.json',marker)
    lines=old+[hashlib.sha256(raw).hexdigest()+'  receipt.json',bounded_hash(destination/'TERMINAL_INVALIDATION.json',4096)+'  TERMINAL_INVALIDATION.json']
    manifest.write_text('\n'.join(sorted(lines))+'\n')
    return raw

def emit_incomplete_essential(run,receipt):
    # Last bounded console attempt, even if artifact IO/deadline prevented normal sealing.
    # This is explicitly incomplete evidence, never an alternate success path.
    raw=(json.dumps(receipt,sort_keys=True)+'\n').encode()
    if len(raw)>256*1024 or safe_display(raw)[1]=='WITHHELD_SECRET_MARKER':
        raw=(json.dumps({'schema':1,'status':receipt['status'],'pipelineAttempts':receipt.get('pipelineAttempts',0),
            'sourceSha':receipt.get('sourceSha'),'firstFailureType':receipt.get('firstFailure',{}).get('type'),
            'evidence':'INCOMPLETE_RECEIPT_WITHHELD_OR_OVERSIZE'})+'\n').encode()
    frame('terminal-incomplete',raw)
    path=run.work/'measurement/failure.json'
    if path.exists():
        try:
            raw=bounded_bytes(path,256*1024)
            if safe_display(raw)[1]=='WITHHELD_SECRET_MARKER':
                frame('original-failure-withheld',json.dumps({'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'scope':'WITHHELD_NOT_RAW_PROOF'}).encode())
            else:frame('original-failure-incomplete-package',raw)
        except BaseException as error:print('RF_EIGHT_ORIGINAL_FAILURE_FRAME_UNAVAILABLE '+type(error).__name__,flush=True)

def main():
    env=dict(os.environ);base=Path(env.get('RUNNER_TEMP','/missing'))
    work=base/'rf-eight-work';evidence=base/'rf-eight-evidence'
    if not base.is_dir() or work.exists() or evidence.exists():raise Blocked('Fresh runner-owned directories required')
    run=Runner(ROOT,work,env=env);run.install_signals();receipt={'schema':1,'classification':'SUPPLEMENTARY_EIGHT_VIEW_HOST_SPARSE_NOT_QUALIFICATION','status':'RUNNING','startedUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'pipelineAttempts':0}
    first=None;sdk=None;licenses=None;restore=False;cap=None
    def secondary(label,error):
        nonlocal first
        receipt.setdefault('secondaryErrors',[]).append({'phase':label,'type':type(error).__name__,'message':str(error)})
        if first is None:first=error
    try:
        event=json.loads(Path(env['GITHUB_EVENT_PATH']).read_text());source=policy.event_guard(env,event)
        receipt.update(policy.source_guard(run,source))
        package,inputs=load_adapter(ROOT)
        sdk,licenses,identity=prepare_environment(run);receipt['tools']=identity
        restore=True
        config,cfg,prepared,observed=prepare_runtime(run,source,package,inputs)
        cap=capability.build(run,source,prepared)
        receipt['result']=run_probe(run,source,config,cfg,prepared,cap,inputs,observed)
        receipt['status']='PENDING_RESTORATION'
    except BaseException as error:
        first=error;receipt['firstFailure']={'type':type(error).__name__,'message':str(error),'status':getattr(error,'status','FAIL')}
    finally:
        if restore and not run.signal:
            try:
                run.command('restore-normal-test-compilation',[*gradle_args(run,offline=True),':app:compileDebugUnitTestKotlin'],seconds=120,cleanup=True)
                verify_probe_classes_removed(ROOT,run)
                receipt['restoration']='PASS'
            except BaseException as error:secondary('restoration',error)
        elif restore:receipt['restoration']='NOT_RUN_CANCELLED'
        if hasattr(run,'sdk_license_state'):
            sdk,licenses=run.sdk_license_state
            receipt['sdkLicenseEvidence']={'sdkPath':str(sdk),'preexistingLicenseSha256':licenses,'postcheck':'PENDING'}
            try:verify_license_snapshot(sdk,licenses);receipt['sdkLicenseEvidence']['postcheck']='PASS'
            except BaseException as error:receipt['sdkLicenseEvidence']['postcheck']='FAIL';secondary('license-postcheck',error)
        try:
            if 'sourceSha' in receipt:
                run.command('source-worktree-postcheck',[sys.executable,'tools/source_manifest.py','verify','--source','worktree'],seconds=60,cleanup=True)
                raw,_=run.command('source-status-postcheck',['git','status','--porcelain=v1','--untracked-files=all'],seconds=15,cleanup=True)
                if raw.strip():raise Failure('Source changed during diagnostic')
                if cap:policy.verify_capability(cap,receipt['sourceSha'],capability.POLICY,run,prepared)
        except BaseException as error:secondary('source-capability-postcheck',error)
        try:run.cleanup();receipt['ownedCleanup']='PASS'
        except BaseException as error:secondary('cleanup',error);receipt['ownedCleanup']='FAIL'
        receipt['status']=getattr(first,'status','FAIL') if first else 'PASS'
        if run.signal:receipt['status']='CANCELLED'
        elif run.clock()>=run.end:receipt['status']='TIMEOUT'
        receipt['pipelineAttempts']=getattr(run,'pipeline_attempts',0)
        if hasattr(run,'runtime_input_postcheck'):receipt['runtimeInputPostcheck']=run.runtime_input_postcheck
        receipt['seconds']=round(run.clock()-run.started,3);receipt['commands']=run.commands
        try:
            output=evidence_package(run,receipt,evidence)
            if run.signal:receipt['status']='CANCELLED';seal_receipt(output,receipt,run)
            elif run.clock()>=run.end:receipt['status']='TIMEOUT';seal_receipt(output,receipt,run)
            # Receipt is essential. Full raw logs/checkpoint evidence are in bounded artifact.
            frame('terminal-receipt',bounded_bytes(output/'receipt.json',256*1024,run))
            for name in ['measurement/failure.json','measurement/sparse-result.json','test-events.jsonl']:
                if (output/name).is_file():frame(name.replace('/','-').replace('.','-'),bounded_bytes(output/name,256*1024,run))
        except BaseException as error:
            secondary('evidence-sealing',error);receipt['status']=getattr(first,'status','FAIL') if first else 'FAIL'
            try:seal_receipt(evidence,receipt,run)
            except BaseException:
                try:emergency_terminal_downgrade(evidence,receipt)
                except BaseException:pass  # Original failure survives unavailable artifact IO; console attempt follows.
            print('RF_EIGHT_EVIDENCE_FAILURE '+type(error).__name__,flush=True)
            try:emit_incomplete_essential(run,receipt)
            except BaseException as framing:print('RF_EIGHT_INCOMPLETE_FRAME_UNAVAILABLE '+type(framing).__name__,flush=True)
        finally:
            if run.signal or run.clock()>=run.end:
                receipt['status']='CANCELLED' if run.signal else 'TIMEOUT'
                try:seal_receipt(evidence,receipt,run);frame('terminal-cancellation',bounded_bytes(evidence/'receipt.json',256*1024,run))
                except BaseException as error:
                    print('RF_EIGHT_FINAL_CANCEL_EVIDENCE_FAILURE '+type(error).__name__,flush=True)
                    try:frame('terminal-emergency-invalidation',emergency_terminal_downgrade(evidence,receipt))
                    except BaseException as emergency:print('RF_EIGHT_EMERGENCY_INVALIDATION_UNAVAILABLE '+type(emergency).__name__,flush=True)
            run.restore_signals()
    return 0 if receipt['status']=='PASS' and not first and not run.signal else 1

if __name__=='__main__':raise SystemExit(main())
