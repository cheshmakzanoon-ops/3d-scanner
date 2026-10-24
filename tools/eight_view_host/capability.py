"""Only source-built capability creation; never admits a caller-supplied native binary."""
from pathlib import Path, PurePosixPath
import hashlib, json, os, re, shlex, shutil, tarfile, time, urllib.request, urllib.parse, zipfile
from tools.native_process import native_compile_env
from .runtime import Failure, Blocked, sha, atomic_json, inventory
from . import header_provider
HERE=Path(__file__).resolve().parent
POLICY=HERE/'build-policy.json'

def path_record(p):return {'path':str(Path(p).resolve()),'sha256':sha(p)}

def source_inventory(root):
    _,entries=inventory(root,max_bytes=2*1024**3,max_files=30000)
    return {name:sha(Path(root)/name) for name in sorted(entries) if (Path(root)/name).is_file()}

def download(run,url,target,expected,maximum):
    if not re.fullmatch('[0-9a-f]{64}',expected):raise Failure('Missing download checksum')
    target=Path(target)
    if target.exists():raise Failure('Download target already exists')
    end=run.clock()+run.remaining(300);h=hashlib.sha256();count=0
    req=urllib.request.Request(url,headers={'User-Agent':'RealityForge-source-bound-host-diagnostic'})
    with urllib.request.urlopen(req,timeout=15) as source,target.with_suffix(target.suffix+'.partial').open('xb') as dest:
        final=urllib.parse.urlsplit(source.geturl())
        if final.scheme!='https' or final.hostname not in ('github.com','codeload.github.com','repo.maven.apache.org'):raise Failure('Foreign official-download redirect')
        size=source.headers.get('Content-Length')
        if size is not None and (not size.isdigit() or int(size)>maximum):raise Failure('Download declared limit')
        while True:
            run.remaining(1);run.disk_guard()
            if run.clock()>=end:raise Failure('Download time limit')
            block=source.read(65536)
            if not block:break
            count+=len(block)
            if count>maximum:raise Failure('Download byte limit')
            dest.write(block);h.update(block)
    if h.hexdigest()!=expected:raise Failure('Official download hash mismatch')
    target.with_suffix(target.suffix+'.partial').replace(target)
    return {'url':url,'bytes':count,'sha256':h.hexdigest()}

def extract_source(archive,out,expected_prefix,max_members=12000,max_bytes=1024**3):
    out.mkdir();seen=set();total=0;count=0;end=time.monotonic()+90
    # Stream headers and payloads; never allocate an unbounded getmembers() list.
    with tarfile.open(archive,'r|gz') as tf:
        for m in tf:
            count+=1
            if count>max_members or time.monotonic()>end:raise Failure('Source archive traversal/member limit')
            p=PurePosixPath(m.name)
            if (not p.parts or p.parts[0]!=expected_prefix or p.is_absolute() or '..' in p.parts or '\\' in m.name
                or str(p) in seen or not (m.isdir() or m.isfile()) or m.size<0):raise Failure('Unsafe source archive entry')
            seen.add(str(p));total+=m.size
            if total>max_bytes:raise Failure('Source archive expansion limit')
            target=out.joinpath(*p.parts)
            if m.isdir():target.mkdir(parents=True,exist_ok=True)
            else:
                target.parent.mkdir(parents=True,exist_ok=True)
                with tf.extractfile(m) as source,target.open('xb') as dest:
                    remaining=m.size
                    while remaining:
                        if time.monotonic()>end:raise Failure('Source archive time limit')
                        block=source.read(min(65536,remaining))
                        if not block:raise Failure('Truncated source archive')
                        dest.write(block);remaining-=len(block)
    root=out/expected_prefix
    if not (root/'LICENSE').is_file():raise Failure('Official source license missing')
    return root

def native_declarations(output):
    result=[];lines=output.splitlines()
    for i,line in enumerate(lines):
        if ' native ' not in line:continue
        if i+1>=len(lines) or not lines[i+1].strip().startswith('descriptor:'):raise Failure('Malformed javap native descriptor')
        name=re.search(r'(\w+)\(',line)
        if not name:raise Failure('Malformed native declaration')
        result.append((name.group(1),lines[i+1].strip().removeprefix('descriptor:').strip()))
    return sorted(result)

def jni_encode(text):
    result=[]
    for char in text:
        if char.isascii() and char.isalnum():result.append(char)
        elif char in ('/','.'):result.append('_')
        elif char=='_':result.append('_1')
        elif char==';':result.append('_2')
        elif char=='[':result.append('_3')
        else:
            for i in range(0,len(char.encode('utf-16-be')),2):result.append('_0'+char.encode('utf-16-be')[i:i+2].hex())
    return ''.join(result)

def jni_symbol(owner,name,descriptor=None):
    result='Java_'+jni_encode(owner)+'_'+jni_encode(name)
    if descriptor is not None:
        descriptor_types(descriptor)
        result+='__'+jni_encode(descriptor[1:descriptor.index(')')])
    return result

def native_exports_match(owner,declarations,exports):
    counts={name:sum(other==name for other,_ in declarations) for name,_ in declarations}
    for name,descriptor in declarations:
        short=jni_symbol(owner,name);long=jni_symbol(owner,name,descriptor)
        if counts[name]>1:
            if long not in exports or short in exports:return False
        elif short not in exports and long not in exports:return False
    return True

def verify_abi(run,javap,aar_classes,generated,lib,classes,prefix):
    raw,_=run.command(prefix+'-symbols',['nm','-D','--defined-only',lib]);exports={x.split()[-1] for x in raw.decode().splitlines() if x.strip()}
    proof={}
    for i,owner in enumerate(classes):
        a,_=run.command(f'{prefix}-aar-{i}',[javap,'-classpath',aar_classes,'-private','-s',owner])
        b,_=run.command(f'{prefix}-generated-{i}',[javap,'-classpath',generated,'-private','-s',owner])
        first,second=native_declarations(a.decode()),native_declarations(b.decode())
        if first!=second:raise Failure('Native descriptor mismatch: '+owner)
        if not native_exports_match(owner,first,exports):raise Failure('Missing JNI export: '+owner)
        proof[owner]={'declarations':first,'matching':True}
    return proof

SYSTEM_SONAMES=frozenset(('libstdc++.so.6','libm.so.6','libgcc_s.so.1','libc.so.6','libz.so.1','libpthread.so.0','libdl.so.2','librt.so.1','ld-linux-x86-64.so.2'))

def parse_elf_closure(path,header,dynamic,closure,engine_dir=None):
    path=Path(path)
    with path.open('rb') as stream:ident=stream.read(20)
    if len(ident)!=20 or ident[:7]!=b'\x7fELF\x02\x01\x01' or int.from_bytes(ident[18:20],'little')!=62:
        raise Failure('Wrong native ELF identity')
    if b'ELF64' not in header or b'Advanced Micro Devices X86-64' not in header:raise Failure('Wrong host ELF architecture')
    needed=re.findall(rb'\(NEEDED\).*?Shared library: \[([^]\r\n]+)\]',dynamic)
    needed=[n.decode() for n in needed]
    allowed=SYSTEM_SONAMES|({'librealityforge.so'} if engine_dir else set())
    if not needed or len(set(needed))!=len(needed) or not set(needed)<=allowed:raise Failure('Unreviewed ELF DT_NEEDED closure')
    rpath=re.findall(rb'\(RPATH\).*?\[([^]]*)\]',dynamic)
    runpath=re.findall(rb'\(RUNPATH\).*?\[([^]]*)\]',dynamic)
    expected=[str(engine_dir).encode()] if engine_dir else []
    if rpath or runpath!=expected:raise Failure('Unexpected ELF RPATH/RUNPATH')
    if engine_dir and 'librealityforge.so' not in needed:raise Failure('Shim engine dependency missing')
    providers={}
    for line in closure.decode().splitlines():
        line=line.strip()
        if not line:continue
        if re.fullmatch(r'linux-vdso\.so\.1 \(0x[0-9a-f]+\)',line):continue
        match=re.fullmatch(r'(\S+) => (/\S+) \(0x[0-9a-f]+\)',line)
        if match:name,target=match.groups()
        else:
            match=re.fullmatch(r'(/\S+) \(0x[0-9a-f]+\)',line)
            if not match:raise Failure('Unresolved/unknown ELF loader output')
            target=match[1];name=Path(target).name
        if name in providers or name not in allowed:raise Failure('Unexpected/duplicate resolved provider')
        resolved=Path(target).resolve(strict=True)
        if name=='librealityforge.so':
            if not engine_dir or resolved!=(Path(engine_dir)/name).resolve(strict=True):raise Failure('Foreign scanner engine provider')
        elif not any(resolved.is_relative_to(Path(d).resolve()) for d in ('/lib/x86_64-linux-gnu','/usr/lib/x86_64-linux-gnu','/lib64')):
            raise Failure('Non-system host library provider')
        providers[name]=path_record(resolved)
    if not set(needed)<=set(providers):raise Failure('Incomplete resolved ELF closure')
    return {'path':str(path),'sha256':sha(path),'needed':sorted(needed),'runpath':[x.decode() for x in runpath],
            'providers':providers,'dynamicSha256':hashlib.sha256(dynamic).hexdigest(),'closureSha256':hashlib.sha256(closure).hexdigest()}

def elf(run,label,path,engine_dir=None):
    # Inspection/runtime closure retains caller environment; compiler sanitization is NOT used here.
    header,_=run.command(label+'-elf-header',['readelf','-h',path])
    dynamic,_=run.command(label+'-elf-dynamic',['readelf','-d',path])
    closure,_=run.command(label+'-ldd',['ldd','-r',path])
    return parse_elf_closure(path,header,dynamic,closure,engine_dir)

def descriptor_types(descriptor):
    primitives={'V':'void','Z':'jboolean','B':'jbyte','C':'jchar','S':'jshort','I':'jint','J':'jlong','F':'jfloat','D':'jdouble'}
    def one(index,returns=False):
        if index>=len(descriptor):raise Failure('Incomplete JNI descriptor')
        c=descriptor[index]
        if c in primitives:
            if c=='V' and not returns:raise Failure('Void JNI parameter')
            return primitives[c],index+1
        if c=='L':
            end=descriptor.find(';',index)
            if end<0:raise Failure('Incomplete JNI object descriptor')
            return ('jstring' if descriptor[index:end+1]=='Ljava/lang/String;' else 'jclass' if descriptor[index:end+1]=='Ljava/lang/Class;' else 'jobject'),end+1
        if c=='[':
            child,end=one(index+1)
            return (child+'Array' if child in primitives.values() else 'jobjectArray'),end
        raise Failure('Unknown JNI descriptor type')
    if not descriptor.startswith('('):raise Failure('Malformed JNI descriptor')
    args=[];i=1
    while i<len(descriptor) and descriptor[i]!=')':t,i=one(i);args.append(t)
    if i>=len(descriptor):raise Failure('Unterminated JNI argument descriptor')
    result,i=one(i+1,True)
    if i!=len(descriptor):raise Failure('Trailing JNI descriptor')
    return result,args

def scanner_contract(declarations):
    if not declarations or len({name for name,_ in declarations})!=len(declarations):raise Failure('Missing/overloaded scanner native declaration')
    lines=['// Generated ONLY from the actual current compiled NativeCore javap descriptors.','#include <jni.h>']
    for name,descriptor in declarations:
        result,args=descriptor_types(descriptor)
        lines.append('extern "C" JNIEXPORT '+result+' JNICALL '+jni_symbol('com.realityforge.scanner.engine.NativeCore',name)+'('+', '.join(['JNIEnv*','jobject',*args])+');')
    return '\n'.join(lines)+'\n'

def system_header_roots(java):
    """The existing build header domain, shared verbatim with the focused diagnostic."""
    roots=[Path('/usr/include'),Path(java).parent.parent/'include']
    roots.extend(path for path in Path('/usr/lib/gcc').glob('*/*/include*') if path.is_dir())
    return roots

def header_escape_diagnostic(candidate, resolved, roots):
    """Bounded path metadata only; never opens the escaped target or changes admission."""
    def text_record(value):
        value=str(value);raw=value.encode('utf-8',errors='surrogatepass')
        return {'text':value[:256],'utf8Bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),
                'truncated':len(value)>256}
    record={'schema':1,'lexicalCandidate':text_record(candidate),'resolvedTarget':text_record(resolved),
            'admittedRoots':[text_record(root) for root in roots[:8]],'rootsTotal':len(roots),
            'rootsOmitted':max(0,len(roots)-8),'linkTarget':None,'linkMetadataError':None}
    try:
        # readlink inspects only the encountered directory entry, not target contents.
        record['linkTarget']=text_record(os.readlink(candidate))
    except OSError as error:
        record['linkMetadataError']={'type':type(error).__name__,'errno':error.errno}
    while True:
        encoded=json.dumps(record,sort_keys=True,ensure_ascii=True,separators=(',',':'))
        if len(encoded.encode('ascii'))<=16*1024:return 'EIGHT_VIEW_HEADER_ESCAPE '+encoded
        if not record['admittedRoots']:raise Failure('Header diagnostic bound invariant')
        record['admittedRoots'].pop();record['rootsOmitted']+=1

def prebuild_snapshot(run,roots):
    """Pre-consumption immutable headers/source, bounded and canonical; no filesystem-wide scan."""
    roots=[Path(root).resolve(strict=True) for root in roots];pending=list(roots);seen=set();result={};total=0;count=0
    end=time.monotonic()+min(90,run.remaining(90))
    while pending:
        path=pending.pop();count+=1
        if count>60000 or time.monotonic()>=end:raise Failure('Prebuild header traversal limit')
        candidate=path;path=path.resolve(strict=True)
        if not any(path.is_relative_to(root) for root in roots):
            raise Failure('Prebuild header symlink escapes admitted roots; '+header_escape_diagnostic(candidate,path,roots))
        if path in seen:continue
        seen.add(path)
        if path.is_dir():
            with os.scandir(path) as entries:
                for entry in entries:
                    if len(pending)+count>60000:raise Failure('Prebuild header entry limit')
                    pending.append(Path(entry.path))
        elif path.is_file():
            total+=path.stat().st_size
            if total>1024**3:raise Failure('Prebuild input byte limit')
            result[str(path)]=sha(path)
        else:raise Failure('Unexpected prebuild input special file')
    return result

def bind_consumed(consumed,before,generated_roots):
    result={}
    for path,digest in consumed.items():
        if path in before:
            if before[path]!=digest:raise Failure('Compiler input changed between prebuild snapshot and consumed dependency')
            result[path]={'sha256':digest,'scope':'PREBUILD_AND_POSTBUILD_IDENTICAL'}
        elif Path(path) in {generated_roots[0]/'modules/java_bindings_generator/gen/cpp'/name for name in ('core.inl.hpp','imgproc.inl.hpp','features2d.inl.hpp','opencv_jni.hpp')}:
            result[path]={'sha256':digest,'scope':'POST_GENERATION_ONLY_NOT_PRECONSUMPTION'}
        else:raise Failure('Consumed immutable compiler input lacks prebuild identity')
    return result

def consumed_inputs(run,ninja,builddir,label,extra_depfile=None):
    raw,_=run.command(label+'-consumed-deps',[ninja,'-C',builddir,'-t','deps'],seconds=60,env=native_compile_env(run.env))
    paths={line.strip() for line in raw.decode().splitlines() if line.startswith('    ') and line.strip()}
    if extra_depfile:
        text=Path(extra_depfile).read_text().replace('\\\n',' ')
        if ':' not in text:raise Failure('Malformed compiler dependency file')
        paths.update(shlex.split(text.split(':',1)[1]))
    if not paths or len(paths)>12000:raise Failure('Missing/excessive consumed compilation dependencies')
    result={}
    for name in sorted(paths):
        run.remaining(1)
        path=Path(name);path=(path if path.is_absolute() else builddir/path).resolve(strict=True)
        if not path.is_file():raise Failure('Consumed dependency not a regular file')
        result[str(path)]=sha(path)
    return result

def build(run,source_sha,prepared):
    policy=json.loads(POLICY.read_text());out=run.work/'capability'
    if out.exists():raise Failure('Capability directory must be fresh')
    out.mkdir();checks={k:False for k in policy['requiredChecks']};receipt={'schema':1,'classification':'HOST_SOURCE_BUILT_CAPABILITY','sourceSha':source_sha,'policySha256':sha(POLICY),'checks':checks,'native':{},'inputs':{}}
    atomic_json(out/'pending.json',receipt)
    compiler=Path(shutil.which('g++',path=run.env['PATH']) or '/missing').resolve();cc=Path(shutil.which('gcc',path=run.env['PATH']) or '/missing').resolve()
    version,_=run.command('compiler-version',[compiler,'-dumpfullversion'],env=native_compile_env(run.env))
    if int(version.decode().strip().split('.')[0]) not in policy['compiler']['allowedMajorVersions']:raise Blocked('Unreviewed GNU compiler major')
    cmake=Path(shutil.which('cmake',path=run.env['PATH']) or '/missing').resolve();ninja=Path(shutil.which('ninja',path=run.env['PATH']) or '/missing').resolve()
    java=Path(run.env['JAVA_HOME'])/'bin/java';javap=java.with_name('javap');javac=java.with_name('javac')
    for name,p in [('compiler',compiler),('cc',cc),('cmake',cmake),('ninja',ninja),('java',java),('javap',javap),('javac',javac),('nm',Path(shutil.which('nm')).resolve()),('readelf',Path(shutil.which('readelf')).resolve()),('ldd',Path(shutil.which('ldd')).resolve()),('javaRelease',java.parent.parent/'release'),('policy',POLICY),('smokeSource',HERE/'HostOpenCvSmoke.java')]:receipt['inputs'][name]=path_record(p)
    provider=header_provider.acquire(run,source_sha)
    receipt['headerProvider']=provider
    atomic_json(out/'header-provider.json',provider)
    receipt['inputs']['header-provider.json']=path_record(out/'header-provider.json')
    for role in header_provider.PROVIDERS:
        receipt['inputs']['headerProviderDriver-'+str(role['major'])]=path_record(role['driver'])
    atomic_json(out/'pending.json',receipt)  # Retain admission even if the next unknown escape refuses.
    system_roots=[*system_header_roots(java),*header_provider.validate(provider,source_sha,run)]
    original=None
    try:immutable_before=prebuild_snapshot(run,system_roots)
    except BaseException as error:original=error;raise
    finally:
        try:header_provider.validate(provider,source_sha,run)
        except BaseException as changed:
            if original is None:raise
            original.add_note('Header provider post-snapshot recheck failed: '+str(changed))
    archive=out/'opencv.tar.gz'
    receipt['download']=download(run,'https://github.com/opencv/opencv/archive/'+policy['opencvCommit']+'.tar.gz',archive,policy['opencvArchiveSha256'],136*1024**2)
    source=extract_source(archive,out/'source','opencv-'+policy['opencvCommit']);before=source_inventory(source)
    receipt['inputs']['archive']=path_record(archive);receipt['inputs']['license']=path_record(source/'LICENSE')
    atomic_json(out/'source-files.json',before)
    aar=Path(prepared['opencvAar']);classes=Path(prepared['opencvClasses'])
    if sha(aar)!=policy['opencvAarSha256']:raise Failure('Current app AAR differs from approved OpenCV')
    if hashlib.sha256(zipfile.ZipFile(aar).read('classes.jar')).hexdigest()!=sha(classes):raise Failure('AAR classes extraction differs')
    receipt['inputs']['aar']=path_record(aar);receipt['inputs']['classes']=path_record(classes)
    hooks=out/'hooks';hooks.mkdir();(hooks/'OPENCV_DOWNLOAD_PRE.cmake').write_text('message(FATAL_ERROR "Implicit dependency download forbidden: ${DL_ID} ${DL_FILENAME}")\n')
    builddir=out/'build';env=native_compile_env(run.env);env['PYTHONDONTWRITEBYTECODE']='1'
    args=[cmake,'-S',source,'-B',builddir,'-G','Ninja',f'-DCMAKE_MAKE_PROGRAM={ninja}',f'-DCMAKE_C_COMPILER={cc}',f'-DCMAKE_CXX_COMPILER={compiler}',
          '-DCMAKE_BUILD_TYPE=Release','-DCMAKE_SKIP_RPATH=ON',f'-DCMAKE_INSTALL_PREFIX={out}/not-installed','-DBUILD_LIST=core,imgproc,features2d,java','-DBUILD_SHARED_LIBS=OFF','-DBUILD_FAT_JAVA_LIB=ON',
          '-DBUILD_JAVA=ON','-DOPENCV_JAVA_IGNORE_ANT=ON','-DOPENCV_JAVA_SDK_BUILD_TYPE=JAVA',f'-DOPENCV_CMAKE_HOOKS_DIR={hooks}','-DOPENCV_DOWNLOAD_TRIES_LIST=',
          '-DFETCHCONTENT_FULLY_DISCONNECTED=ON','-DCPU_BASELINE=SSE3','-DCPU_DISPATCH=','-DENABLE_LTO=OFF',f'-DPYTHON_DEFAULT_EXECUTABLE={shutil.which("python3")}']
    for key in ['BUILD_TESTS','BUILD_PERF_TESTS','BUILD_EXAMPLES','BUILD_DOCS','BUILD_opencv_apps','BUILD_opencv_world','BUILD_opencv_gapi','BUILD_opencv_python2','BUILD_opencv_python3',
                'WITH_ADE','WITH_IPP','WITH_ITT','WITH_OPENCL','WITH_CUDA','WITH_LAPACK','WITH_EIGEN','WITH_TBB','WITH_OPENMP','WITH_V4L','WITH_FFMPEG','WITH_GSTREAMER','WITH_GTK','WITH_QT',
                'WITH_OPENGL','WITH_PROTOBUF','WITH_JPEG','WITH_PNG','WITH_TIFF','WITH_WEBP','WITH_OPENJPEG','WITH_OPENEXR','WITH_AVIF','WITH_VTK','WITH_VA']:args.append('-D'+key+'=OFF')
    configure,_=run.command('opencv-configure',args,seconds=180,env=env)
    modules=re.findall(rb'To be built:\s*([^\r\n]+)',configure)
    if len(modules)!=1 or modules[0].decode().split()!=policy['opencvModules'] or not re.search(rb'Java wrappers:\s+YES \(JAVA\)',configure):
        raise Failure('Unreviewed OpenCV module/Java build graph')
    checks['opencvModules']=True
    immutable_before.update(prebuild_snapshot(run,[source,builddir]))
    if shutil.disk_usage(out).free<4*1024**3:raise Blocked('Four GiB free required before native build')
    run.command('opencv-build',[cmake,'--build',builddir,'--target','opencv_java','--parallel','2'],seconds=1620,env=env)
    if any(not (source/p).is_file() or sha(source/p)!=h for p,h in before.items()):raise Failure('Official source changed during build')
    checks['sourceIntegrity']=True
    lib=builddir/'lib/libopencv_java4120.so';generated=builddir/'bin/opencv-4120.jar'
    atomic_json(out/'opencv-abi.json',verify_abi(run,javap,classes,generated,lib,policy['nativeClasses'],'opencv'))
    checks['opencvAbi']=True;opencv_consumed=consumed_inputs(run,ninja,builddir,'opencv');receipt['opencvElf']=elf(run,'opencv',lib);checks['opencvElf']=True
    smoke=out/'smoke';smoke.mkdir()
    run.command('opencv-smoke-compile',[javac,'-cp',classes,'-d',smoke,HERE/'HostOpenCvSmoke.java'],seconds=60,env=env)
    text,_=run.command('opencv-smoke',[java,'-Xmx256m','-XX:ActiveProcessorCount=2','-cp',os.pathsep.join(map(str,[smoke,classes])),'HostOpenCvSmoke',lib],seconds=90)
    if len(re.findall(rb'^OPENCV_REAL_SMOKE_PASS version=4\.12\.0 descriptors=[0-9]+ gray=76,150,29,255$',text,re.M))!=1:raise Failure('Missing actual OpenCV smoke completion')
    checks['opencvSmoke']=True
    engine_build=out/'scanner';scanner_before=source_inventory(run.root/'reconstruction')
    run.command('scanner-configure',[cmake,'-S',run.root,'-B',engine_build,'-G','Ninja','-DCMAKE_BUILD_TYPE=Release','-DRF_REQUIRE_CAMPAIGN_031_060=ON',f'-DCMAKE_CXX_COMPILER={compiler}'],seconds=60,env=env)
    immutable_before.update(prebuild_snapshot(run,[run.root/'reconstruction',engine_build]))
    run.command('scanner-build',[cmake,'--build',engine_build,'--target','realityforge','--parallel','2'],seconds=240,env=env)
    engine=engine_build/'librealityforge.so';shim=out/'librealityforge_jni.so';deps=out/'jni.d'
    declared,_=run.command('scanner-javap',[javap,'-classpath',prepared['classpathString'],'-private','-s','com.realityforge.scanner.engine.NativeCore'])
    if re.search(rb'\bstatic\s+native\b',declared):raise Failure('Unexpected static NativeCore JNI receiver')
    declarations=native_declarations(declared.decode());contract=out/'scanner-jni-contract.h';contract.write_text(scanner_contract(declarations))
    immutable_before[str(contract)]=sha(contract)
    run.command('scanner-shim',[compiler,'-std=c++20','-O0','-fPIC','-shared',f'-I{run.root}/reconstruction/include',f'-I{java.parent.parent}/include',f'-I{java.parent.parent}/include/linux',
        '-MD','-MF',deps,'-include',contract,run.root/'reconstruction/src/jni.cpp',f'-L{engine_build}','-lrealityforge','-lz','-Wl,-z,defs',f'-Wl,-rpath,{engine_build}','-o',shim],seconds=60,env=env)
    scanner_consumed=consumed_inputs(run,ninja,engine_build,'scanner',deps)
    if scanner_before!=source_inventory(run.root/'reconstruction'):raise Failure('Scanner source changed during native build')
    atomic_json(out/'scanner-source.json',scanner_before);checks['scannerSource']=True
    owners=prepared['owners'];entry=owners['com.realityforge.scanner.engine.NativeCore']
    receipt['inputs']['nativeOwner']=path_record(entry['file'])
    receipt['runtimeProvider']=prepared['runtimeProvider']
    receipt['inputs']['robolectricNativeDistribution']=path_record(prepared['robolectricNativeDistribution']['path'])
    receipt['inputs']['androidAll']=path_record(prepared['offlineFramework']['path'])
    symbols,_=run.command('scanner-symbols',['nm','-D','--defined-only',shim])
    actual={x.split()[-1] for x in symbols.decode().splitlines() if x.strip() and x.split()[-1].startswith('Java_')}
    declarations=native_declarations(declared.decode());expected={jni_symbol('com.realityforge.scanner.engine.NativeCore',name) for name,_ in declarations}
    if not declarations or actual!=expected or not {'beginReconstruction','reconstructCalibratedCancellable','bundleAdjustProblemCancellable'}<={x[0] for x in declarations}:raise Failure('Current NativeCore/shim ABI mismatch')
    atomic_json(out/'scanner-abi.json',{'declarations':declarations,'symbols':sorted(actual)});checks['scannerAbi']=True
    receipt['scannerElf']={'engine':elf(run,'scanner-engine',engine),'shim':elf(run,'scanner-shim',shim,engine_build)};checks['scannerElf']=True
    receipt['native']={k:path_record(p) for k,p in [('opencv',lib),('shim',shim),('engine',engine)]}
    receipt['inputs']['generatedJar']=path_record(generated)
    receipt['inputs']['jniSource']=path_record(run.root/'reconstruction/src/jni.cpp')
    receipt['inputs']['jniContract']=path_record(contract)
    combined={**opencv_consumed,**scanner_consumed}
    for i,(path,digest) in enumerate(sorted(combined.items())):
        if sha(path)!=digest:raise Failure('Consumed compiler input changed after build')
        receipt['inputs']['consumed-'+str(i)]={'path':path,'sha256':digest}
    atomic_json(out/'consumed-inputs.json',combined)
    atomic_json(out/'prebuild-inputs.json',immutable_before)
    atomic_json(out/'consumed-provenance.json',bind_consumed(combined,immutable_before,[builddir,engine_build]))
    for name in ['source-files.json','scanner-source.json','opencv-abi.json','scanner-abi.json','consumed-inputs.json','prebuild-inputs.json','consumed-provenance.json']:
        receipt['inputs'][name]=path_record(out/name)
    header_provider.validate(provider,source_sha,run)
    receipt['producer']={'runOwner':run.owner,'workDir':str(run.work),'sourceSha':source_sha,'commands':[{**record,'log':path_record(run.work/'logs'/(record['label']+'.raw.log'))} for record in run.commands if record['label'].startswith(('compiler-','opencv-','scanner-','header-provider-'))]}
    atomic_json(out/'capability.json',receipt)
    return out/'capability.json'
