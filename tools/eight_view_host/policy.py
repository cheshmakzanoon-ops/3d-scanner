"""Fail-closed source, event and measured-result admission. No dynamic trigger defaults."""
from pathlib import Path
import json, re, xml.etree.ElementTree as ET
from .runtime import Failure, Blocked, sha
from .experiments import LEGACY, COMPLETE, get_profile, artifact_budget
REPOSITORY='cheshmakzanoon-ops/3d-scanner'
BASE_SHA='11dbd41f0311fc338c3a5d6b987366a9c90fd3d7'
MESSAGE='fix: bound complete prototype artifact capacity'
TRIGGER_PATHS=frozenset(('.github/workflows/eight-view-host-diagnostic.yml','tools/eight_view_host/controller.py'))
ALLOWED_PATHS=frozenset(('.github/workflows/eight-view-host-diagnostic.yml', 'SOURCE_MANIFEST.sha256', 'tests/test_eight_view_artifact_budget.py', 'tests/test_eight_view_controller.py', 'tools/eight_view_host/controller.py', 'tools/eight_view_host/experiments.py', 'tools/eight_view_host/policy.py'))
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

def junit_proof(path,events,*,profile=LEGACY):
    selected=get_profile(profile)
    selector=selected.selector
    selected_class,selected_method=selector.rsplit('.',1)
    root=ET.parse(path).getroot(); cases=root.findall('.//testcase')
    if root.tag=='testcase':cases=[root]
    if len(cases)!=1 or cases[0].get('classname')!=selected_class or cases[0].get('name')!=selected_method:raise Failure('Wrong/missing/duplicate selected JUnit identity')
    if any(cases[0].find(n) is not None for n in ('failure','error','skipped')):raise Failure('Selected JUnit failed/skipped')
    suites=[root] if root.tag=='testsuite' else root.findall('.//testsuite')
    if len(suites)!=1 or any(suites[0].get(k,'0')!='0' for k in ('errors','failures','skipped')) or suites[0].get('tests')!='1':raise Failure('Inconsistent JUnit totals')
    expected=[{'event':'start','class':selected_class,'method':selected_method},{'event':'finish','class':selected_class,'method':selected_method,'result':'SUCCESS'}]
    if events!=expected:raise Failure('Missing/duplicate/non-success selected test events')
    return {'selector':selector,'tests':1,'failures':0,'skipped':0,'junitSha256':sha(path)}

def measurement_proof(result,pairs,identity,observations,*,profile=LEGACY):
    selected=get_profile(profile)
    if selected.id == COMPLETE:raise Failure('Complete profile requires raw publication/export measurement proof')
    required=selected.classification
    count=selected.photo_count;pair_count=selected.pair_count
    ids=result.get('inputPhotoIds',[]);registered=result.get('registeredPhotoIds',[])
    if (result.get('classification')!=required or len(ids)!=count or len(set(ids))!=count or len(registered)!=count
            or set(registered)!=set(ids) or result.get('unregisteredPhotoIds')!=[]
            or result.get('geometry',{}).get('finalSparseOutcome')!='global_typed_ba_admitted12'):
        raise Failure('Actual selected-profile reconstruction incomplete/fallback; preserved, not a full sparse success')
    if not isinstance(identity,list) or len(identity)!=count:raise Failure('Missing input-to-photo identity proof')
    expected=observations.get('images',{})
    mapped={}
    for item in identity:
        if not isinstance(item,dict) or set(item)!={'photoId','imageSha256','fixturePath'}:raise Failure('Malformed photo identity')
        photo=item['photoId'];name=item['fixturePath']
        if not isinstance(photo,str) or not photo or photo in mapped or name not in expected or item['imageSha256']!=expected[name]:raise Failure('Foreign/duplicate photo identity')
        mapped[photo]=name
    if len(expected)!=count or set(mapped)!=set(ids) or set(mapped.values())!=set(expected):raise Failure('Selected photos do not bind to frozen inputs')
    if not isinstance(pairs,list) or not pairs or len(pairs)>100000:raise Failure('Missing/excessive pair events')
    allowed={'pairNumber','pairCount','phase','firstDescriptorRows','secondDescriptorRows','matchCacheHit','geometryEntered'}
    for item in pairs:
        if not isinstance(item,dict) or not set(item)<=allowed or not {'pairNumber','pairCount','phase','geometryEntered'}<=set(item):raise Failure('Malformed pair event fields')
        if type(item['pairNumber']) is not int or not 1<=item['pairNumber']<=pair_count or type(item['pairCount']) is not int or item['pairCount']!=pair_count:raise Failure('Malformed pair ordinal/count')
        if item['phase'] not in ('LOAD_FIRST','LOAD_SECOND','MATCHING','GEOMETRY','READY') or type(item['geometryEntered']) is not bool:raise Failure('Failed/unknown pair phase/type')
        for key in ('firstDescriptorRows','secondDescriptorRows'):
            if key in item and (type(item[key]) is not int or not 0<=item[key]<=100000):raise Failure('Malformed descriptor row count')
        if 'matchCacheHit' in item and type(item['matchCacheHit']) is not bool:raise Failure('Malformed match cache flag')
    ready=[x for x in pairs if x['phase']=='READY']
    if len(ready)!=pair_count or {x['pairNumber'] for x in ready}!=set(range(1,pair_count+1)):
        raise Failure('Incomplete/duplicate actual pair accounting')
    return {'classification':required,'registeredCameraCount':count,'pairCount':pair_count,'geometry':result['geometry']}

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
        'exportNativeOwner':Path(prepared['owners']['com.realityforge.scanner.export.NativeAssetExports']['file']),
        'robolectricNativeDistribution':Path(prepared['robolectricNativeDistribution']['path']),
        'androidAll':run.root/'app/build/robolectric-sdk/android-all-15-robolectric-12650502.jar',
        'generatedJar':out/'build/bin/opencv-4120.jar','jniSource':run.root/'reconstruction/src/jni.cpp','jniContract':out/'scanner-jni-contract.h',
        'exportJniSource':run.root/'reconstruction/src/asset_exports_jni.cpp',
        'jniObject':out/'jni.o','exportJniObject':out/'export-jni.o',
        'jniDepfile':out/'jni.d','exportJniDepfile':out/'export-jni.d',
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
            'scanner-configure','scanner-build','scanner-javap','scanner-export-javap','scanner-jni-compile','scanner-export-compile','scanner-shim','scanner-consumed-deps','scanner-symbols']
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
    declared=logs['scanner-javap'];export_declared=logs['scanner-export-javap']
    declarations=c.native_declarations(declared.decode());export_declarations=c.native_declarations(export_declared.decode())
    expected_export=[('writeObserved','(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/Runnable;)V')]
    if export_declarations!=expected_export:raise Failure('Export JNI descriptor differs')
    for label,owner in [('scanner-javap','com.realityforge.scanner.engine.NativeCore'),('scanner-export-javap','com.realityforge.scanner.export.NativeAssetExports')]:
        if actual[label]['argv']!=[str(java/'bin/javap'),'-classpath',prepared['classpathString'],'-private','-s',owner]:raise Failure('JNI declaration owner/classpath command differs')
    if re.search(rb'\bstatic\s+native\b',declared+export_declared) or (out/'scanner-jni-contract.h').read_text()!=c.scanner_contract(declarations,export_declarations):raise Failure('Scanner/export descriptor/receiver contract differs')
    symbols={x.split()[-1] for x in logs['scanner-symbols'].decode().splitlines() if x.strip() and x.split()[-1].startswith('Java_')}
    expected={c.jni_symbol('com.realityforge.scanner.engine.NativeCore',name) for name,_ in declarations}
    expected.update(c.jni_symbol('com.realityforge.scanner.export.NativeAssetExports',name) for name,_ in export_declarations)
    if not declarations or symbols!=expected or not {'beginReconstruction','reconstructCalibratedCancellable','bundleAdjustProblemCancellable'}<={x[0] for x in declarations}:raise Failure('Current scanner/export JNI symbols differ')
    if json.loads((out/'scanner-abi.json').read_text())!={'declarations':[list(pair) for pair in declarations],'exportDeclarations':[list(pair) for pair in export_declarations],'symbols':sorted(symbols)}:raise Failure('Scanner ABI receipt differs')
    c.verify_scanner_outputs(run,out,cleanup=True)
    commands=c.scanner_compile_commands(out,run.root,expected_inputs['compiler'],java,out/'scanner-jni-contract.h')
    for label,argv in commands.items():
        if actual[label]['argv']!=[str(arg) for arg in argv] or actual[label]['environmentBoundary']!='EXPLICIT_COMPILATION_TOOL':raise Failure('Scanner/export compile/link command differs')
    for dep_key,object_key,source_key in [('jniDepfile','jniObject','jniSource'),('exportJniDepfile','exportJniObject','exportJniSource')]:
        dependencies=c.parse_scanner_depfile(expected_inputs[dep_key],expected_inputs[object_key],run,cleanup=True)
        required={str(expected_inputs[source_key]),str(expected_inputs['jniContract'])}
        if not required<=dependencies:raise Failure('JNI dependency closure lacks current source/contract')
        if any(name not in consumed or sha(name)!=consumed[name] for name in dependencies):raise Failure('JNI dependency closure differs from consumed inputs')
    for role,prefix in [('opencv','opencv'),('engine','scanner-engine'),('shim','scanner-shim')]:
        closure=c.parse_elf_closure(expected_native[role],logs[prefix+'-elf-header'],logs[prefix+'-elf-dynamic'],logs[prefix+'-ldd'],out/'scanner' if role=='shim' else None)
        recorded=value['opencvElf'] if role=='opencv' else value['scannerElf'][role]
        if closure!=recorded:raise Failure('Resolved native provider/ELF identity changed')
    for name,root in [('source-files.json',out/'source'/('opencv-'+requirements['opencvCommit'])),('scanner-source.json',run.root/'reconstruction')]:
        original=json.loads((out/name).read_text())
        if not original or not isinstance(original,dict) or len(original)>30000:raise Failure('Missing/bounded source inventory required')
        if any(not (root/rel).is_file() or sha(root/rel)!=digest for rel,digest in original.items()):raise Failure('Source input changed after compilation')
    return value

# Complete-controller evidence is a separate closed experiment, never sparse-prefix PASS.
COMPLETE_DOCUMENTS=tuple(['complete-result.json','job-settings.json','modeled-resources.json']+
    [phase+'-'+kind+'.json' for phase in ('before-reopen','after-reopen')
     for kind in ('preview','publication','artifacts','exports')])
COMPLETE_ARTIFACTS=(
    ('preview','preview.json','diagnostic-report'),
    ('sparse-glb','result.glb','sparse-points'),('sparse-ply','result.ply','sparse-points'),
    ('dense-glb','result.dense.glb','dense-points'),('dense-ply','result.dense.ply','dense-points'),
    ('mesh-glb','result.mesh.glb','observed-open-triangle-mesh'),
    ('mesh-ply','result.mesh.ply','observed-open-triangle-mesh'),
    ('textured-glb','result.textured.glb','photographic-textured-mesh'),
    ('texture-png','result.texture.png','photographic-atlas'))


def complete_measurement_proof(documents,artifacts,identity,observations,*,profile=COMPLETE):
    """Recompute publication/export evidence; synthetic HOST evidence is not device quality."""
    import hashlib,math,struct
    from .complete_formats import validate_exports
    def need(value,message):
        if not value:raise Failure('Complete measurement: '+message)
    def integer(value,minimum=0,maximum=2**63-1):
        return type(value) is int and minimum<=value<=maximum
    def digest(value):return type(value) is str and re.fullmatch('[0-9a-f]{64}',value) is not None
    def token(value):return type(value) is str and re.fullmatch('[A-Za-z0-9_.-]{1,128}',value) is not None
    def sha_bytes(raw):return hashlib.sha256(raw).hexdigest()
    def unique(pairs):
        value={}
        for key,item in pairs:
            need(key not in value,'duplicate JSON key');value[key]=item
        return value
    def reject(_):raise Failure('Complete measurement: nonfinite JSON')
    def finite(text):
        value=float(text);need(math.isfinite(value),'nonfinite JSON');return value
    def decode(raw):
        need(type(raw) is bytes and 0<len(raw)<=16*1024**2,'missing/oversized raw document')
        try:value=json.loads(raw,object_pairs_hook=unique,parse_constant=reject,parse_float=finite)
        except (ValueError,UnicodeError,RecursionError) as error:raise Failure('Complete measurement: malformed JSON') from error
        need(type(value) is dict,'document must be object');return value
    selected=get_profile(profile)
    need(selected.id==COMPLETE,'wrong closed experiment profile')
    need(type(documents) is dict and set(documents)==set(COMPLETE_DOCUMENTS),'exact document inventory required')
    need(type(artifacts) is dict and 1<=len(artifacts)<=9,'exact raw artifact inventory required')
    need(all(type(raw) is bytes and 0<len(raw)<=16*1024**2 for raw in [*documents.values(),*artifacts.values()]),'raw evidence byte bounds')
    need(sum(map(len,documents.values()))+sum(map(len,artifacts.values()))<=artifact_budget(COMPLETE)-1024**2,'aggregate evidence byte bound')
    docs={name:decode(raw) for name,raw in documents.items()}
    result=docs['complete-result.json']
    identity_keys={'projectId','projectRevision','operationId','attempt','inputIdentitySha256','settingsIdentitySha256','engineVersion'}
    need(set(result)=={'schema','classification','synthetic','modeledResources','jobId','originalsPreserved','exportHashes','state','acceptedStage'}|identity_keys,'result fields')
    need(type(result['schema']) is int and result['schema']==1,'result schema')
    need(result['classification']==selected.classification and result['synthetic'] is True and result['modeledResources'] is True and result['originalsPreserved'] is True,'result classification')
    need(result['state']=='COMPLETED' and result['acceptedStage']=='PUBLISHING','persisted job not complete/published')
    need(all(token(result[k]) for k in ('projectId','operationId','jobId','engineVersion')),'job identity token')
    need(result['engineVersion']=='m19-integrated-runner-v2','unreviewed controller engine version')
    need(type(result['projectRevision']) is int and result['projectRevision']==3 and integer(result['attempt'],1),'job revision/attempt')
    need(all(digest(result[k]) for k in ('inputIdentitySha256','settingsIdentitySha256')),'job identity digest')
    need(sha_bytes(documents['job-settings.json'])==result['settingsIdentitySha256'],'job settings bytes differ')
    settings=docs['job-settings.json']
    need(set(settings)=={'schema','focal35','resourceSelection'} and type(settings['schema']) is int and settings['schema']==2
         and type(settings['focal35']) in (int,float) and settings['focal35']==32.88,'job settings semantics')
    selection=settings['resourceSelection']
    need(type(selection) is dict and set(selection)=={'schema','tier','identitySha256'}
         and type(selection['schema']) is int and selection['schema']==1,'resource selection schema')
    tier=selection['tier'];need(type(tier) is str and tier in ('compact','standard'),'resource selection tier')
    params='depth=256|texture=512|views=6|samples=50000' if tier=='compact' else 'depth=384|texture=768|views=8|samples=100000'
    selection_text='resource-selection-v1|'+tier+'|'+params+'|native-threads=1'
    need(selection['identitySha256']==sha_bytes(selection_text.encode('ascii')),'resource selection identity')
    model=docs['modeled-resources.json']
    expected_model={'classification':'MODELED_HOST_RESOURCES','availableSystemBytes':2*1024**3,'thermalStatus':0,
        'batteryPercent':80,'charging':True,'powerSave':False,'statFsBlocks':2000000,'statFsAvailableBlocks':1500000,
        'managedHeap':'actual JVM runtime; not modeled phone heap',
        'storageReservation':'production FileSpaceProbe and StorageBudgetService unchanged'}
    need(model==expected_model and all(type(model[k]) is type(v) for k,v in expected_model.items()),'modeled resource disclosure differs')
    names={row[0] for row in COMPLETE_ARTIFACTS}
    before=docs['before-reopen-artifacts.json'];after=docs['after-reopen-artifacts.json']
    need(set(before)==names and before==after==result['exportHashes'] and all(digest(v) for v in before.values()),'published artifact hashes/reopen differ')
    need(set(artifacts)=={'artifact-'+value+'.bin' for value in before.values()},'raw artifact set differs')
    payloads={name:artifacts['artifact-'+value+'.bin'] for name,value in before.items()}
    need(all(sha_bytes(raw)==before[name] for name,raw in payloads.items()),'raw artifact hash differs')
    need(documents['before-reopen-preview.json']==documents['after-reopen-preview.json']==payloads['preview'],'raw published preview/reopen differs')
    publication=docs['before-reopen-publication.json']
    need(publication==docs['after-reopen-publication.json'],'publication changed after reopen')
    need(set(publication)=={'schema','setId','createdAtEpochMillis','purpose','entries'}|identity_keys,'publication fields')
    need(type(publication['schema']) is int and publication['schema']==1 and publication['purpose']=='RESULT_SET','publication schema/purpose')
    need(all(publication[k]==result[k] and type(publication[k]) is type(result[k]) for k in identity_keys),'publication/job identity differs')
    need(integer(publication['createdAtEpochMillis']) and digest(publication['setId']),'publication timestamp/set identity')
    entries=publication['entries'];need(type(entries) is list and len(entries)==9,'publication entry count')
    def string(value):
        raw=value.encode('utf-8');return struct.pack('>i',len(raw))+raw
    encoded=struct.pack('>ii',1,0)+string(publication['projectId'])+struct.pack('>q',3)+string(publication['operationId'])+struct.pack('>q',publication['attempt'])
    encoded+=string(publication['inputIdentitySha256'])+string(publication['settingsIdentitySha256'])+string(publication['engineVersion'])
    encoded+=struct.pack('>qi',publication['createdAtEpochMillis'],9)
    for entry,(name,path,representation) in zip(entries,COMPLETE_ARTIFACTS):
        need(type(entry) is dict and set(entry)=={'logicalName','relativePath','representation','required','maxBytes','presence','bytes','sha256'},'publication entry fields')
        need((entry['logicalName'],entry['relativePath'],entry['representation'])==(name,path,representation),'publication declaration differs')
        need(entry['required'] is True and entry['presence']=='PRESENT','publication artifact absent/optional')
        need(integer(entry['bytes'],1,16*1024**2) and integer(entry['maxBytes'],1,16*1024**2) and entry['bytes']==entry['maxBytes']==len(payloads[name]),'publication artifact size differs')
        need(entry['sha256']==before[name],'publication artifact digest differs')
        encoded+=string(name)+string(path)+string(representation)+b'\x01'+struct.pack('>qiq',entry['maxBytes'],0,entry['bytes'])+b'\x01'+string(entry['sha256'])
    need(sha_bytes(encoded)==publication['setId'],'publication canonical set identity differs')
    exports=docs['before-reopen-exports.json']
    need(exports==docs['after-reopen-exports.json'] and set(exports)=={'ply','mesh.ply','textured.glb'},'export inventory/reopen differs')
    for extension,name in [('ply','sparse-ply'),('mesh.ply','mesh-ply'),('textured.glb','textured-glb')]:
        entry=exports[extension]
        need(type(entry) is dict and set(entry)=={'path','bytes','sha256','publishedLogicalName'},'export entry fields')
        need(entry['path']=='artifact-'+before[name]+'.bin' and entry['sha256']==before[name] and entry['publishedLogicalName']==name and integer(entry['bytes'],1) and entry['bytes']==len(payloads[name]),'export does not bind published bytes')
    preview=docs['before-reopen-preview.json']
    need(preview.get('revision')==3 and type(preview.get('revision')) is int and token(preview.get('artifactKey')),'preview publication identity')
    need(all(preview.get(k)=='ready' for k in ('denseStatus','meshStatus','textureStatus')),'full stages not ready')
    need(preview.get('finalSparseOutcome')=='global_typed_ba_admitted12' and type(preview.get('bundleAdjustment')) is dict,'typed sparse BA absent')
    cameras=preview.get('cameras');need(type(cameras) is list and len(cameras)==3 and all(type(c) is dict and type(c.get('photoId')) is str for c in cameras),'registered camera identities')
    ids=[c['photoId'] for c in cameras];need(len(set(ids))==3 and type(preview.get('registeredCameras')) is int and preview['registeredCameras']==3,'registered camera count')
    need(type(identity) is list and len(identity)==3 and type(observations) is dict,'input identity proof')
    expected=observations.get('images');need(type(expected) is dict and len(expected)==3 and all(digest(v) for v in expected.values()),'frozen input identities')
    mapped={}
    for item in identity:
        need(type(item) is dict and set(item)=={'photoId','imageSha256','fixturePath'},'input identity fields')
        photo=item['photoId'];path=item['fixturePath']
        need(type(photo) is str and photo in ids and photo not in mapped and type(path) is str and path in expected and item['imageSha256']==expected[path],'input identity mismatch')
        mapped[photo]=path
    need(set(mapped.values())==set(expected),'input mapping not bijective')
    sparse=preview.get('pointCount');dense=preview.get('densePreview');mesh=preview.get('meshPreview');texture=preview.get('texturePreview')
    need(integer(sparse,1) and all(type(v) is dict for v in (dense,mesh,texture)),'stage measurements missing')
    need(integer(dense.get('pointCount'),60) and integer(dense.get('consistentPixels'),60),'dense support below fixed minimum')
    need(integer(mesh.get('vertexCount'),30) and integer(mesh.get('triangleCount'),40),'mesh support below fixed minimum')
    coverage=texture.get('faceCoverage');need(type(coverage) in (int,float) and math.isfinite(coverage) and 0<coverage<=1,'photographic coverage invalid')
    try:counts=validate_exports(payloads['sparse-ply'],payloads['mesh-ply'],payloads['textured-glb'])
    except (ValueError,KeyError,TypeError,OverflowError,struct.error) as error:raise Failure('Complete measurement: invalid raw export format') from error
    expected_counts={'sparsePointCount':sparse,'meshVertexCount':mesh['vertexCount'],'meshTriangleCount':mesh['triangleCount'],
        'texturedVertexCount':texture.get('uvVertexCount'),'texturedTriangleCount':texture.get('triangleCount'),
        'textureWidth':texture.get('atlasWidth'),'textureHeight':texture.get('atlasHeight')}
    need(all(integer(value,1) and counts.get(key)==value for key,value in expected_counts.items()),'binary export counts differ from preview')
    need(counts['textureSha256']==before['texture-png'],'embedded texture differs from published atlas')
    return {'classification':selected.classification,'profile':COMPLETE,'synthetic':True,'modeledResources':True,
        'projectId':result['projectId'],'projectRevision':3,'jobId':result['jobId'],'state':'COMPLETED','acceptedStage':'PUBLISHING',
        'publicationSetId':publication['setId'],'registeredCameraCount':3,'artifactCount':9,'exportCount':3,
        'formatValidationScope':'THREE_EXPORTED_FORMATS; NINE_PUBLISHED_ARTIFACTS_HASH_BOUND',
        'inputIdentityScope':'PHOTO_HASH_MAPPING_CHECKED; SNAPSHOT_HASH_JOB_TO_PUBLICATION_BOUND_NOT_RECOMPUTED',
        'measurements':counts,'faceCoverage':coverage,
        'documents':{name:{'sha256':sha_bytes(raw),'bytes':len(raw)} for name,raw in sorted(documents.items())},
        'artifacts':{name:{'sha256':before[name],'bytes':len(payloads[name])} for name in sorted(names)}}
