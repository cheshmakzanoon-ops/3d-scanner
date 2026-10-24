"""Fail-closed source, event and measured-result admission. No dynamic trigger defaults."""
from pathlib import Path
import json, re, xml.etree.ElementTree as ET
from .runtime import Failure, Blocked, sha
REPOSITORY='cheshmakzanoon-ops/3d-scanner'
BASE_SHA='cb26bbad38443d8671dceb303a419581ab32f989'
MESSAGE='fix: bind scanner build-order sentinel'
TRIGGER_PATHS=frozenset(('.github/workflows/eight-view-host-diagnostic.yml','tools/eight_view_host/controller.py'))
ALLOWED_PATHS=frozenset(('.github/workflows/eight-view-host-diagnostic.yml','tools/eight_view_host/policy.py','tools/eight_view_host/native_provenance.py','tests/test_eight_view_native_provenance.py','SOURCE_MANIFEST.sha256'))
SELECTOR='com.realityforge.scanner.hostintegration.EightViewSparsePrefixHostTest.fixedEightPhotosPersistSparseOrRecordUnmodifiedProductionFailure'
CLASS,METHOD=SELECTOR.rsplit('.',1)

def event_guard(env,event,base=BASE_SHA,message=MESSAGE):
    if not re.fullmatch('[0-9a-f]{40}',base) or message=='NOT_ARMED':raise Blocked('One-shot diagnostic is NOT ARMED')
    repo=event.get('repository',{});head=event.get('head_commit',{});actual=env.get('GITHUB_SHA','')
    if not re.fullmatch('[0-9a-f]{40}',actual):raise Failure('Invalid source SHA')
    exact={
        'event':env.get('GITHUB_EVENT_NAME')=='push', 'repo':env.get('GITHUB_REPOSITORY')==REPOSITORY,
        'repoPayload':repo.get('full_name')==REPOSITORY, 'public':repo.get('private') is False,
        'default':repo.get('default_branch')=='main','ref':env.get('GITHUB_REF')=='refs/heads/main',
        'payloadRef':event.get('ref')=='refs/heads/main','attempt':env.get('GITHUB_RUN_ATTEMPT')=='1',
        'before':event.get('before')==base,'after':event.get('after')==actual,'head':head.get('id')==actual,
        'message':head.get('message')==message,'created':event.get('created') is False,
        'deleted':event.get('deleted') is False,'forced':event.get('forced') is False}
    if not all(exact.values()):raise Failure('One-shot event rejected: '+','.join(k for k,v in exact.items() if not v))
    return actual

def source_guard(run,expected,base=BASE_SHA,allowed=ALLOWED_PATHS):
    def git(label,*args):return run.command('git-'+label,['git',*args],seconds=30,limit=256*1024)[0].decode().strip()
    if git('head','rev-parse','HEAD')!=expected:raise Failure('Checkout source mismatch')
    if git('status','status','--porcelain=v1','--untracked-files=all'):raise Failure('Checkout is not clean')
    parents=git('parents','show','--format=%P','--no-patch','HEAD').split()
    if parents!=[base]:raise Failure('Missing history, merge or unexpected parent')
    if git('parent-commit','rev-parse','HEAD^')!=base:raise Failure('Parent object unavailable')
    paths=set(git('paths','diff','--no-renames','--name-only',base,expected).splitlines())
    if not paths or not paths<=allowed or not paths&TRIGGER_PATHS:raise Failure('Changed-path admission mismatch')
    run.command('source-manifest',['python3','tools/source_manifest.py','verify','--source','HEAD'],seconds=60)
    return {'sourceSha':expected,'parentSha':base,'tree':git('tree','rev-parse','HEAD^{tree}'),
            'sourceManifestSha256':sha(run.root/'SOURCE_MANIFEST.sha256'),'changedPaths':sorted(paths)}

def junit_proof(path,events):
    root=ET.parse(path).getroot(); cases=root.findall('.//testcase')
    if root.tag=='testcase':cases=[root]
    if len(cases)!=1 or cases[0].get('classname')!=CLASS or cases[0].get('name')!=METHOD:raise Failure('Wrong/missing/duplicate selected JUnit identity')
    if any(cases[0].find(n) is not None for n in ('failure','error','skipped')):raise Failure('Selected JUnit failed/skipped')
    suites=[root] if root.tag=='testsuite' else root.findall('.//testsuite')
    if len(suites)!=1 or any(suites[0].get(k,'0')!='0' for k in ('errors','failures','skipped')) or suites[0].get('tests')!='1':raise Failure('Inconsistent JUnit totals')
    expected=[{'event':'start','class':CLASS,'method':METHOD},{'event':'finish','class':CLASS,'method':METHOD,'result':'SUCCESS'}]
    if events!=expected:raise Failure('Missing/duplicate/non-success selected test events')
    return {'selector':SELECTOR,'tests':1,'failures':0,'skipped':0,'junitSha256':sha(path)}

def measurement_proof(result,pairs,identity,observations):
    required='ALL8_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY'
    ids=result.get('inputPhotoIds',[]);registered=result.get('registeredPhotoIds',[])
    if (result.get('classification')!=required or len(ids)!=8 or len(set(ids))!=8 or len(registered)!=8
            or set(registered)!=set(ids) or result.get('unregisteredPhotoIds')!=[]
            or result.get('geometry',{}).get('finalSparseOutcome')!='global_typed_ba_admitted12'):
        raise Failure('Actual eight-view reconstruction incomplete/fallback; preserved, not a full sparse success')
    if not isinstance(identity,list) or len(identity)!=8:raise Failure('Missing input-to-photo identity proof')
    expected=observations.get('images',{})
    mapped={}
    for item in identity:
        if not isinstance(item,dict) or set(item)!={'photoId','imageSha256','fixturePath'}:raise Failure('Malformed photo identity')
        photo=item['photoId'];name=item['fixturePath']
        if not isinstance(photo,str) or not photo or photo in mapped or name not in expected or item['imageSha256']!=expected[name]:raise Failure('Foreign/duplicate photo identity')
        mapped[photo]=name
    if len(expected)!=8 or set(mapped)!=set(ids) or set(mapped.values())!=set(expected):raise Failure('Eight photos do not bind to frozen inputs')
    if not isinstance(pairs,list) or not pairs or len(pairs)>100000:raise Failure('Missing/excessive pair events')
    allowed={'pairNumber','pairCount','phase','firstDescriptorRows','secondDescriptorRows','matchCacheHit','geometryEntered'}
    for item in pairs:
        if not isinstance(item,dict) or not set(item)<=allowed or not {'pairNumber','pairCount','phase','geometryEntered'}<=set(item):raise Failure('Malformed pair event fields')
        if type(item['pairNumber']) is not int or not 1<=item['pairNumber']<=28 or type(item['pairCount']) is not int or item['pairCount']!=28:raise Failure('Malformed pair ordinal/count')
        if item['phase'] not in ('LOAD_FIRST','LOAD_SECOND','MATCHING','GEOMETRY','READY') or type(item['geometryEntered']) is not bool:raise Failure('Failed/unknown pair phase/type')
        for key in ('firstDescriptorRows','secondDescriptorRows'):
            if key in item and (type(item[key]) is not int or not 0<=item[key]<=100000):raise Failure('Malformed descriptor row count')
        if 'matchCacheHit' in item and type(item['matchCacheHit']) is not bool:raise Failure('Malformed match cache flag')
    ready=[x for x in pairs if x['phase']=='READY']
    if len(ready)!=28 or {x['pairNumber'] for x in ready}!=set(range(1,29)):
        raise Failure('Incomplete/duplicate actual pair accounting')
    return {'classification':required,'registeredCameraCount':8,'pairCount':28,'geometry':result['geometry']}

def verify_capability(path,source,policy,run=None,prepared=None):
    """Recheck producer records, ABI, exact paths and native closure; flags alone never qualify."""
    from . import capability as c,header_provider as hp,native_provenance as np
    from .controller import bounded_bytes
    import hashlib, shutil, zipfile
    if run is None or prepared is None:raise Failure('Authoritative owned producer/runtime context required')
    out=run.work/'capability';path=Path(path)
    if path!=out/'capability.json' or path.is_symlink():raise Failure('Foreign capability path')
    value=json.loads(path.read_text());requirements=json.loads(Path(policy).read_text())
    if (value.get('schema')!=1 or value.get('classification')!='HOST_SOURCE_BUILT_CAPABILITY'
       or value.get('sourceSha')!=source or value.get('policySha256')!=sha(policy)):
        raise Failure('Foreign/stale capability policy or source')
    if value.get('checks')!={k:True for k in requirements['requiredChecks']}:raise Failure('Incomplete capability verification')
    expected_native={'opencv':out/'build/lib/libopencv_java4120.so','shim':out/'librealityforge_jni.so','engine':out/'scanner/librealityforge.so'}
    if set(value.get('native',{}))!=set(expected_native):raise Failure('Wrong native closure')
    def entry(record,expected):
        if set(record)!={'path','sha256'} or Path(record['path'])!=Path(expected).resolve() or not re.fullmatch('[0-9a-f]{64}',record['sha256']):raise Failure('Unexpected artifact identity/path')
        if sha(expected)!=record['sha256']:raise Failure('Capability input/output changed')
    for role,expected in expected_native.items():entry(value['native'][role],expected)
    proof_path=out/'header-provider.json'
    if proof_path.is_symlink() or proof_path.resolve()!=proof_path:raise Failure('Clang header proof input alias')
    header_proof=json.loads(bounded_bytes(proof_path,32*1024,run))
    if value.get('headerProvider')!=header_proof:raise Failure('Clang header provider capability/input proof differs')
    header_roots=hp.validate(header_proof,source,run)  # Existing authoritative probes only; never re-probe.
    if header_roots!=[role['root'] for role in hp.PROVIDERS]:raise Failure('Unexpected admitted Clang header root')
    java=Path(run.env['JAVA_HOME'])
    expected_inputs={
        **{name:Path(shutil.which(tool,path=run.env['PATH']) or '/missing').resolve() for name,tool in [('compiler','g++'),('cc','gcc'),('cmake','cmake'),('ninja','ninja'),('nm','nm'),('readelf','readelf'),('ldd','ldd')]},
        'java':java/'bin/java','javap':java/'bin/javap','javac':java/'bin/javac','javaRelease':java/'release',
        'header-provider.json':proof_path,**{'headerProviderDriver-'+str(role['major']):role['driver'] for role in hp.PROVIDERS},
        'policy':Path(policy),'smokeSource':c.HERE/'HostOpenCvSmoke.java','archive':out/'opencv.tar.gz',
        'license':out/'source'/('opencv-'+requirements['opencvCommit'])/'LICENSE',
        'aar':Path(prepared['opencvAar']),'classes':Path(prepared['opencvClasses']),
        'nativeOwner':Path(prepared['owners']['com.realityforge.scanner.engine.NativeCore']['file']),
        'robolectricNativeDistribution':Path(prepared['robolectricNativeDistribution']['path']),
        'androidAll':run.root/'app/build/robolectric-sdk/android-all-15-robolectric-12650502.jar',
        'generatedJar':out/'build/bin/opencv-4120.jar','jniSource':run.root/'reconstruction/src/jni.cpp','jniContract':out/'scanner-jni-contract.h',
        **{name:out/name for name in ['source-files.json','scanner-source.json','opencv-abi.json','scanner-abi.json','consumed-inputs.json','prebuild-inputs.json','consumed-provenance.json']}}
    consumed=json.loads((out/'consumed-inputs.json').read_text())
    if not consumed or len(consumed)>24000:raise Failure('Missing/excessive consumed header closure')
    before=json.loads((out/'prebuild-inputs.json').read_text())
    if not before or len(before)>60000:raise Failure('Missing/excessive prebuild input closure')
    native_path=out/np.FILENAME
    if native_path.is_symlink() or native_path.resolve()!=native_path:raise Failure('Native provenance input alias')
    native_raw=bounded_bytes(native_path,16*1024**2,run)
    native_ref={'path':str(native_path),'sha256':hashlib.sha256(native_raw).hexdigest()}
    if value.get('nativeInputProvenance')!=native_ref:raise Failure('Native provenance capability/input reference differs')
    native_proof=json.loads(native_raw)
    native_roles=np.validate(run,native_proof,consumed,before,source)
    system_inputs=set(native_proof['systemLink']['inputs'])
    expected_inputs.update(np.input_paths(native_proof))
    allowed_roots=[out,run.root/'reconstruction',java/'include',Path('/usr/include'),Path('/usr/lib/gcc'),*header_roots]
    for i,(name,digest) in enumerate(sorted(consumed.items())):
        f=Path(name)
        if not f.is_absolute() or str(f)!=name or f.resolve(strict=True)!=f or not (name in system_inputs or any(f.is_relative_to(root.resolve()) for root in allowed_roots)):raise Failure('Consumed compiler input outside source/system/JDK closure')
        if sha(f)!=digest:raise Failure('Consumed compilation input changed')
        expected_inputs['consumed-'+str(i)]=f
    provenance=json.loads((out/'consumed-provenance.json').read_text())
    if provenance!=native_roles:raise Failure('Consumed input prebuild provenance mismatch')
    if str((java/'include/jni.h').resolve()) not in consumed:raise Failure('Consumed JDK JNI header absent')
    if not any(Path(name).is_relative_to('/usr/include') for name in consumed):raise Failure('Consumed system headers absent')
    if set(value.get('inputs',{}))!=set(expected_inputs):raise Failure('Missing/unexpected capability inputs')
    for name,expected in expected_inputs.items():entry(value['inputs'][name],expected)
    if sha(expected_inputs['archive'])!=requirements['opencvArchiveSha256'] or sha(expected_inputs['aar'])!=requirements['opencvAarSha256']:raise Failure('Official dependency pin mismatch')
    with zipfile.ZipFile(expected_inputs['aar']) as z:
        if hashlib.sha256(z.read('classes.jar')).hexdigest()!=sha(expected_inputs['classes']):raise Failure('Original AAR class identity mismatch')
    if value.get('runtimeProvider')!=prepared['runtimeProvider'] or sha(expected_inputs['robolectricNativeDistribution'])!=requirements['robolectricNativeRuntimeSha256']:raise Failure('Runtime dependency/provider archive identity differs')
    provider=value['runtimeProvider']
    if (prepared['offlineFramework']!=value['inputs']['androidAll'] or provider['path']!=str(expected_inputs['androidAll']) or provider['sha256']!=requirements['androidAllSha256'] or sha(expected_inputs['androidAll'])!=requirements['androidAllSha256']
        or requirements['robolectricNativeProviderArchive']!='androidAll' or provider['entry']!=requirements['robolectricNativeRuntimeEntry'] or provider['entryBytes']!=requirements['robolectricNativeProviderEntryBytes'] or not 0<provider['entryBytes']<=requirements['robolectricNativeProviderMaximumBytes']):raise Failure('API35 provider is not the exact pinned framework entry')
    # The authoritative command records are held by this producer, not loaded from the manifest.
    labels=[*np.labels(native_proof),*hp.LABELS,'compiler-version','opencv-configure','opencv-build','opencv-symbols','opencv-consumed-deps','opencv-smoke-compile','opencv-smoke',
            'scanner-configure','scanner-build','scanner-javap','scanner-shim','scanner-consumed-deps','scanner-symbols']
    labels += [f'opencv-{kind}-{i}' for i in range(len(requirements['nativeClasses'])) for kind in ('aar','generated')]
    labels += [prefix+'-'+part for prefix in ('opencv','scanner-engine','scanner-shim') for part in ('elf-header','elf-dynamic','ldd')]
    provenance=value.get('producer',{})
    if set(provenance)!={'runOwner','workDir','sourceSha','commands'} or provenance.get('runOwner')!=run.owner or provenance.get('workDir')!=str(run.work) or provenance.get('sourceSha')!=source:raise Failure('Stale/foreign producer run identity')
    producer=provenance['commands']
    if len(producer)!=len(labels) or {x.get('label') for x in producer}!=set(labels):raise Failure('Incomplete producer transcript')
    actual={record['label']:record for record in run.commands};logs={}
    for record in producer:
        label=record['label'];asserted={k:v for k,v in record.items() if k!='log'}
        if asserted.get('runOwner')!=run.owner or type(asserted.get('starttime')) is not int or asserted['starttime']<=0 or asserted.get('environmentBoundary') not in ('INHERITED_RUNTIME','EXPLICIT_COMPILATION_TOOL') or asserted!=actual.get(label) or asserted.get('complete') is not True or asserted.get('exit')!=0 or any(k in asserted for k in ('error','cleanupError','secondaryErrors')):
            raise Failure('Producer outcome is not complete/authoritative success')
        log=run.work/'logs'/(label+'.raw.log');entry(record['log'],log);raw=log.read_bytes()
        if len(raw)!=asserted.get('bytes') or hashlib.sha256(raw).hexdigest()!=asserted.get('sha256'):raise Failure('Producer raw transcript mismatch')
        logs[label]=raw
    rpath_flags=[arg for arg in actual['opencv-configure']['argv'] if 'CMAKE_SKIP_RPATH' in arg]
    if rpath_flags!=['-DCMAKE_SKIP_RPATH=ON']:raise Failure('OpenCV configure must explicitly omit build/install RPATH')
    cache_flags=[arg for arg in actual['opencv-configure']['argv'] if 'OPENCV_DOWNLOAD_PATH' in arg]
    if cache_flags!=[f'-DOPENCV_DOWNLOAD_PATH={out}/build/download-cache']:raise Failure('OpenCV configure must explicitly bind owned download cache')
    c.verify_download_cache(out,run)
    modules=re.findall(rb'To be built:\s*([^\r\n]+)',logs['opencv-configure'])
    if len(modules)!=1 or modules[0].decode().split()!=requirements['opencvModules'] or not re.search(rb'Java wrappers:\s+YES \(JAVA\)',logs['opencv-configure']):raise Failure('Unexpected actual OpenCV module graph')
    if int(logs['compiler-version'].decode().strip().split('.')[0]) not in requirements['compiler']['allowedMajorVersions']:raise Failure('Unreviewed compiler')
    if len(re.findall(rb'^OPENCV_REAL_SMOKE_PASS version=4\.12\.0 descriptors=[0-9]+ gray=76,150,29,255$',logs['opencv-smoke'],re.M))!=1:raise Failure('Actual OpenCV smoke proof absent')
    cv_exports={x.split()[-1] for x in logs['opencv-symbols'].decode().splitlines() if x.strip()}
    cv_proof={}
    for i,owner in enumerate(requirements['nativeClasses']):
        first=c.native_declarations(logs[f'opencv-aar-{i}'].decode());second=c.native_declarations(logs[f'opencv-generated-{i}'].decode())
        if first!=second or not c.native_exports_match(owner,first,cv_exports):raise Failure('OpenCV original JNI ABI differs')
        cv_proof[owner]={'declarations':[list(pair) for pair in first],'matching':True}
    if json.loads((out/'opencv-abi.json').read_text())!=cv_proof:raise Failure('OpenCV ABI receipt differs')
    declared=logs['scanner-javap'];declarations=c.native_declarations(declared.decode())
    if re.search(rb'\bstatic\s+native\b',declared) or (out/'scanner-jni-contract.h').read_text()!=c.scanner_contract(declarations):raise Failure('Scanner descriptor/receiver contract differs')
    symbols={x.split()[-1] for x in logs['scanner-symbols'].decode().splitlines() if x.strip() and x.split()[-1].startswith('Java_')}
    expected={c.jni_symbol('com.realityforge.scanner.engine.NativeCore',name) for name,_ in declarations}
    if not declarations or symbols!=expected or not {'beginReconstruction','reconstructCalibratedCancellable','bundleAdjustProblemCancellable'}<={x[0] for x in declarations}:raise Failure('Current scanner JNI symbols differ')
    if json.loads((out/'scanner-abi.json').read_text())!={'declarations':[list(pair) for pair in declarations],'symbols':sorted(symbols)}:raise Failure('Scanner ABI receipt differs')
    argv=actual['scanner-shim']['argv'];contract=str(out/'scanner-jni-contract.h')
    if '-include' not in argv or argv[argv.index('-include')+1]!=contract or str(run.root/'reconstruction/src/jni.cpp') not in argv:raise Failure('Scanner compile omitted generated descriptor contract/current source')
    for role,prefix in [('opencv','opencv'),('engine','scanner-engine'),('shim','scanner-shim')]:
        closure=c.parse_elf_closure(expected_native[role],logs[prefix+'-elf-header'],logs[prefix+'-elf-dynamic'],logs[prefix+'-ldd'],out/'scanner' if role=='shim' else None)
        recorded=value['opencvElf'] if role=='opencv' else value['scannerElf'][role]
        if closure!=recorded:raise Failure('Resolved native provider/ELF identity changed')
    for name,root in [('source-files.json',out/'source'/('opencv-'+requirements['opencvCommit'])),('scanner-source.json',run.root/'reconstruction')]:
        original=json.loads((out/name).read_text())
        if not original or not isinstance(original,dict) or len(original)>30000:raise Failure('Missing/bounded source inventory required')
        if any(not (root/rel).is_file() or sha(root/rel)!=digest for rel,digest in original.items()):raise Failure('Source input changed after compilation')
    return value
