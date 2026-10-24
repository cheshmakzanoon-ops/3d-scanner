#!/usr/bin/env python3
"""One source-bound eight-view HOST attempt. Deliberately NOT ARMED until reviewed."""
from __future__ import annotations
import hashlib, importlib.util, json, os, platform, re, shutil, struct, sys, time, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from tools.eight_view_host.runtime import Runner,Failure,Blocked,Deadline,Cancelled,sha,atomic_json,inventory,safe_display,frame
from tools.eight_view_host import policy,capability,ndk
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

def bounded_bytes(path,maximum,run=None,cleanup=True):
    path=Path(path)
    if path.is_symlink() or not path.is_file():raise Failure('Bounded evidence/input is not a regular file')
    before=path.stat()
    if before.st_size>maximum:raise Failure('Evidence/input per-file byte limit')
    raw=bytearray()
    with path.open('rb') as stream:
        while True:
            phase_budget(run,cleanup=cleanup)
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
        if path.is_symlink() or not path.is_absolute() or str(path.resolve())!=str(path):raise Failure('Classpath alias changed')
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
    normalization_check(record,run,cleanup)

# Ledger digests use printable ASCII names and UTF-8 line records, not JSON bytes.
_ARCHIVE_KEYS={'path','bytes','sha256','entries','classMapSha256','resourceMapSha256'}

def normalization_path(value):
    if not isinstance(value,str):raise Failure('Normalization path type')
    path=Path(value)
    if not path.is_absolute() or str(path.resolve())!=value or path.is_symlink() or not path.is_file():
        raise Failure('Normalization path alias or missing file')
    return path

def normalization_zip_metadata(path,run=None,cleanup=False):
    # Read bounded central headers before ZipFile allocates its entry inventory.
    size=path.stat().st_size
    if not 22<=size<=512*1024**2:raise Failure('Normalization ZIP raw size')
    with path.open('rb') as stream:
        stream.seek(max(0,size-65557));tail=stream.read(65557);offset=tail.rfind(b'PK\x05\x06')
        if offset<0 or offset+22>len(tail):raise Failure('Missing normalization ZIP end record')
        disk,central_disk,count_disk,count,length,position,comment=struct.unpack_from('<4H2IH',tail,offset+4)
        absolute=size-len(tail)+offset
        if (disk or central_disk or count_disk!=count or count>2000 or count==65535 or length==0xffffffff or position==0xffffffff
            or offset+22+comment!=len(tail) or position+length!=absolute):raise Failure('Unsupported normalization ZIP layout')
        stream.seek(position);names=set()
        for _ in range(count):
            phase_budget(run,cleanup=cleanup);header=stream.read(46)
            if len(header)!=46 or header[:4]!=b'PK\x01\x02':raise Failure('Malformed normalization ZIP central header')
            flags,method=struct.unpack_from('<HH',header,8)
            compressed,expanded=struct.unpack_from('<II',header,20)
            namesize,extrasize,commentsize,startdisk=struct.unpack_from('<4H',header,28)
            attrs,local=struct.unpack_from('<II',header,38)
            mode=(attrs>>16)&0o170000
            if (flags&~0x80e or method not in (0,8) or mode not in (0,0o100000,0o040000) or startdisk
                or compressed==0xffffffff or expanded==0xffffffff or local==0xffffffff or not 0<namesize<=4096):
                raise Failure('Unsupported normalization ZIP metadata')
            name=stream.read(namesize);extra=stream.read(extrasize)
            if len(name)!=namesize or len(extra)!=extrasize:raise Failure('Truncated normalization ZIP metadata')
            parts=name[:-1].split(b'/') if name.endswith(b'/') else name.split(b'/')
            if (name in names or any(c<32 or c>126 for c in name) or b'\\' in name or b':' in name
                or any(p in (b'',b'.',b'..') for p in parts)):
                raise Failure('Unsafe or duplicate normalization ZIP name')
            names.add(name)
            if mode==0o040000 and not name.endswith(b'/') or mode==0o100000 and name.endswith(b'/'):
                raise Failure('Inconsistent normalization ZIP file type/name')
            i=0
            while i<len(extra):
                if i+4>len(extra):raise Failure('Malformed normalization ZIP extra field')
                tag,n=struct.unpack_from('<HH',extra,i);i+=4
                if tag==1 or i+n>len(extra):raise Failure('Unsupported normalization ZIP64/extra field')
                i+=n
            stream.seek(commentsize,1)
        if stream.tell()!=position+length:raise Failure('Normalization ZIP central extent')

def normalization_archive(value,run=None,cleanup=False,extra=()):
    if not isinstance(value,dict) or set(value)!=_ARCHIVE_KEYS|set(extra):raise Failure('Normalization archive schema')
    if (type(value['bytes']) is not int or not isinstance(value['entries'],list) or len(value['entries'])>2000
        or any(not isinstance(value[k],str) or not re.fullmatch('[0-9a-f]{64}',value[k]) for k in ('sha256','classMapSha256','resourceMapSha256'))):
        raise Failure('Normalization archive identity types')
    for entry in value['entries']:
        if (not isinstance(entry,dict) or set(entry)!={'name','directory','bytes','sha256'} or not isinstance(entry['name'],str)
            or type(entry['directory']) is not bool or type(entry['bytes']) is not int or not isinstance(entry['sha256'],str)
            or not re.fullmatch('[0-9a-f]{64}',entry['sha256'])):raise Failure('Normalization entry schema')
    path=normalization_path(value['path']);before=path.stat()
    digest=bounded_hash(path,512*1024**2,run,cleanup=cleanup)
    normalization_zip_metadata(path,run,cleanup)
    entries=[];classes={};resources=[];total=0;seen=set()
    try:
        with zipfile.ZipFile(path) as archive:
            infos=archive.infolist()
            if len(infos)>2000:raise Failure('Normalization entry count')
            for info in infos:
                phase_budget(run,cleanup=cleanup)
                name=info.filename;directory=info.is_dir();parts=name.rstrip('/').split('/')
                if (not name or len(name)>4096 or ':' in name or any(ord(c)<32 or ord(c)>126 for c in name) or '\\' in name or name.startswith('/')
                    or any(p in ('','.','..') for p in parts) or name in seen or info.orig_filename!=name
                    or info.flag_bits&1 or info.compress_type not in (zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED)
                    or (info.external_attr>>16)&0o170000 not in (0,0o100000,0o040000)):
                    raise Failure('Unsafe normalization ZIP entry')
                seen.add(name);upper=name.upper()
                if upper.startswith('META-INF/VERSIONS/') or (upper.startswith('META-INF/') and
                    (upper.endswith(('.SF','.RSA','.DSA','.EC')) or upper.rsplit('/',1)[-1].startswith('SIG-'))):
                    raise Failure('Unsupported signed or multi-release archive')
                if info.file_size>16*1024**2 or info.file_size<0:raise Failure('Normalization entry size')
                total+=info.file_size
                if total>512*1024**2:raise Failure('Normalization expanded size')
                h=hashlib.sha256();size=0;manifest=bytearray()
                with archive.open(info) as stream:
                    while True:
                        phase_budget(run,cleanup=cleanup);block=stream.read(65536)
                        if not block:break
                        size+=len(block)
                        if size>16*1024**2:raise Failure('Normalization expanded entry size')
                        h.update(block)
                        if upper=='META-INF/MANIFEST.MF':manifest.extend(block)
                if size!=info.file_size or directory and size:raise Failure('Normalization entry size mismatch')
                if upper=='META-INF/MANIFEST.MF':
                    # Same deliberately narrow manifest domain as the JVM reader.
                    lines=bytes(manifest).replace(b'\r\n',b'\n').replace(b'\r',b'\n').split(b'\n')
                    ended=False;attribute=False
                    for line in lines:
                        if not line:ended=True;continue
                        if ended:raise Failure('Unsupported normalization manifest section')
                        if line.startswith(b' '):
                            if not attribute:raise Failure('Malformed normalization manifest continuation')
                            continue
                        key,separator,_=line.partition(b': ')
                        if not separator or key.lower() not in (b'manifest-version',b'created-by',b'build-jdk',b'built-by'):
                            raise Failure('Unsupported normalization manifest metadata')
                        attribute=True
                entry={'name':name,'directory':directory,'bytes':size,'sha256':h.hexdigest()};entries.append(entry)
                if not directory and name.endswith('.class'):classes[name]=h.hexdigest()
                else:resources.append(entry)
    except Failure:raise
    except (zipfile.BadZipFile,RuntimeError,ValueError,NotImplementedError) as error:
        raise Failure('Unreadable normalization archive') from error
    actual={'path':str(path),'bytes':before.st_size,'sha256':digest,'entries':entries,
        'classMapSha256':hashlib.sha256(''.join(f'{classes[n]}  {n}\n' for n in sorted(classes)).encode('utf-8')).hexdigest(),
        'resourceMapSha256':hashlib.sha256(''.join(f'{"d" if e["directory"] else "f"} {e["sha256"]} {e["bytes"]} {e["name"]}\n' for e in resources).encode('utf-8')).hexdigest()}
    after=path.stat()
    if ((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns)
        or bounded_hash(path,512*1024**2,run,cleanup=cleanup)!=digest):raise Failure('Normalization archive changed during inspection')
    if actual!={k:value[k] for k in _ARCHIVE_KEYS}:raise Failure('Normalization archive identity differs')
    return classes,resources

def effective_owner_count(path,owner,run=None,cleanup=False):
    """Stream unrelated JAR metadata without constructing an unbounded ZipInfo list.

    Unlike normalized providers, unrelated dependencies may legitimately use ZIP64.
    The existing 100000-file inspection cap applies per archive here.
    """
    size=path.stat().st_size;matches=0
    targets={name.encode('ascii') for name in ([owner] if isinstance(owner,str) else owner)}
    target_lengths={len(name) for name in targets}
    if not 22<=size<=512*1024**2:raise Failure('Effective JAR size limit')
    with path.open('rb') as stream:
        stream.seek(max(0,size-65557));tail=stream.read(65557);offset=tail.rfind(b'PK\x05\x06')
        if offset<0 or offset+22>len(tail):raise Failure('Missing effective JAR end record')
        disk,central_disk,count_disk,count,length,position,comment=struct.unpack_from('<4H2IH',tail,offset+4)
        end=size-len(tail)+offset
        if disk or central_disk or offset+22+comment!=len(tail):raise Failure('Unsupported effective JAR layout')
        if count==65535 or count_disk==65535 or length==0xffffffff or position==0xffffffff:
            stream.seek(end-20);locator=stream.read(20)
            if len(locator)!=20 or locator[:4]!=b'PK\x06\x07':raise Failure('Missing effective JAR ZIP64 locator')
            zipdisk,zipoffset,disks=struct.unpack_from('<IQI',locator,4)
            if zipdisk or disks!=1 or zipoffset+56>end-20:raise Failure('Unsupported effective JAR ZIP64 layout')
            stream.seek(zipoffset);header=stream.read(56)
            if len(header)!=56 or header[:4]!=b'PK\x06\x06':raise Failure('Missing effective JAR ZIP64 end')
            recordsize=struct.unpack_from('<Q',header,4)[0]
            disk,central_disk,count_disk,count,length,position=struct.unpack_from('<IIQQQQ',header,16)
            if recordsize<44 or zipoffset+12+recordsize!=end-20 or disk or central_disk:raise Failure('Unsupported effective JAR ZIP64 extent')
            end=zipoffset
        if count_disk!=count or count>100000 or position+length!=end:raise Failure('Effective JAR metadata count/extent')
        stream.seek(position)
        for _ in range(count):
            phase_budget(run,cleanup=cleanup);header=stream.read(46)
            if len(header)!=46 or header[:4]!=b'PK\x01\x02':raise Failure('Malformed effective JAR central header')
            namesize,extrasize,commentsize=struct.unpack_from('<3H',header,28)
            if stream.tell()+namesize+extrasize+commentsize>position+length:raise Failure('Effective JAR metadata extent')
            if namesize in target_lengths:matches+=stream.read(namesize) in targets
            else:stream.seek(namesize,1)
            stream.seek(extrasize+commentsize,1)
        if stream.tell()!=position+length:raise Failure('Effective JAR central extent')
    return matches

def normalization_check(record,run=None,cleanup=False):
    if type(record.get('schema')) is not int or record.get('schema')!=2 or 'replacedOpenCvProvider' in record:raise Failure('Unsupported classpath capture schema')
    ledger=record.get('opencvNormalization')
    if not isinstance(ledger,dict) or set(ledger)!={'schema','mode','sourceAar','sourceClasses','providers','projection'} or type(ledger['schema']) is not int or ledger['schema']!=1:
        raise Failure('Unsupported normalization ledger schema')
    if record.get('task')!=':app:eightPrepare' or not re.fullmatch('[0-9a-f]{40}',record.get('sourceSha','')):raise Failure('Normalization capture task/source binding')
    mode=ledger['mode']
    if mode not in ('single-class-equivalent-provider','agp-two-role-resource-projection'):raise Failure('Unsupported normalization mode')
    aar=ledger['sourceAar'];source=ledger['sourceClasses']
    if not isinstance(aar,dict) or set(aar)!={'path','bytes','sha256'} or type(aar['bytes']) is not int:raise Failure('Normalization source AAR schema')
    path=normalization_path(aar['path']);aar_before=path.stat()
    if aar['path']!=record['opencvAar'] or source['path']!=record['opencvClasses']:raise Failure('Normalization source path binding')
    pinned=json.loads(capability.POLICY.read_text())['opencvAarSha256']
    if aar['sha256']!=pinned or bounded_hash(path,512*1024**2,run,cleanup=cleanup)!=pinned or path.stat().st_size!=aar['bytes']:
        raise Failure('Normalization source AAR identity')
    source_classes,source_resources=normalization_archive(source,run,cleanup)
    if not source_classes:raise Failure('Normalization source classes empty')
    normalization_zip_metadata(path,run,cleanup)
    with zipfile.ZipFile(path) as archive:
        matches=[i for i in archive.infolist() if i.filename=='classes.jar']
        if len(matches)!=1 or matches[0].file_size>16*1024**2:raise Failure('Normalization AAR classes entry')
        with archive.open(matches[0]) as stream:
            raw=stream.read(16*1024**2+1)
        if len(raw)!=source['bytes'] or hashlib.sha256(raw).hexdigest()!=source['sha256']:raise Failure('Normalization extracted classes binding')
    if bounded_hash(path,512*1024**2,run,cleanup=cleanup)!=pinned:raise Failure('Normalization AAR changed')
    aar_after=path.stat()
    if (aar_before.st_dev,aar_before.st_ino,aar_before.st_size,aar_before.st_mtime_ns)!=(aar_after.st_dev,aar_after.st_ino,aar_after.st_size,aar_after.st_mtime_ns):
        raise Failure('Normalization AAR changed during extraction verification')
    providers=ledger['providers'];cp=record['classpath'];projection=ledger['projection']
    expected=1 if mode=='single-class-equivalent-provider' else 2
    if not isinstance(providers,list) or len(providers)!=expected:raise Failure('Normalization provider count')
    identities=[];indices=[];resource_lists=[]
    for provider in providers:
        classes,resources=normalization_archive(provider,run,cleanup,('index','role'))
        if classes!=source_classes:raise Failure('Normalization provider class map')
        index=provider['index']
        if type(index) is not int or not 0<=index<len(cp):raise Failure('Normalization provider index')
        st=Path(provider['path']).stat();identities.append((st.st_dev,st.st_ino));indices.append(index);resource_lists.append(resources)
    if len(set(indices))!=expected or len(set(identities))!=expected:raise Failure('Normalization provider alias')
    def effective(index,archive):
        if cp[index].get('directory') is not False or cp[index].get('path')!=archive['path'] or cp[index].get('sha256')!=archive['sha256']:
            raise Failure('Normalization effective classpath binding')
    if expected==1:
        p=providers[0]
        if projection is not None:raise Failure('Normalization single mode projection')
        disposition='single-source-resources-identical' if resource_lists[0]==source_resources else 'single-class-only-no-resource-completeness'
        if p['role']!=disposition:raise Failure('Normalization single resource disposition')
        if resource_lists[0]!=source_resources and any(not e['directory'] for e in resource_lists[0]):raise Failure('Normalization single provider resources')
        effective(indices[0],p)
    else:
        runtime,original=providers
        if runtime['role']!='runtime-classes' or original['role']!='aar-resources' or indices[0]>=indices[1]:raise Failure('Normalization two-role ordering')
        if any(not e['directory'] for e in resource_lists[0]) or original['sha256']!=source['sha256'] or runtime['sha256']==source['sha256']:raise Failure('Normalization provider role content')
        classes,resources=normalization_archive(projection,run,cleanup,('index','sourcePath'))
        if classes or resources!=source_resources or type(projection['index']) is not int or projection['bytes']>16*1024**2 or projection['index']!=indices[1] or projection['sourcePath']!=original['path']:
            raise Failure('Normalization resource projection binding')
        st=Path(projection['path']).stat()
        if (st.st_dev,st.st_ino) in identities or projection['path'] in (source['path'],aar['path']):raise Failure('Normalization projection alias')
        effective(indices[0],runtime);effective(indices[1],projection)
        if original['path'] in [e['path'] for e in cp]:raise Failure('Original duplicate provider remains effective')
    # No unclassified partial or full provider may overlap ANY canonical class.
    containers=[]
    for index,entry in enumerate(cp):
        phase_budget(run,cleanup=cleanup);path=Path(entry['path'])
        if entry['directory']:
            if any((path/name).exists() for name in source_classes):containers.append(index)
        else:
            if not path.name.endswith('.jar'):raise Failure('Unsupported effective classpath file suffix')
            matches=effective_owner_count(path,source_classes,run,cleanup)
            if index==indices[0] and matches!=len(source_classes):raise Failure('Incomplete or duplicate canonical OpenCV class entries')
            if matches:containers.append(index)
            if bounded_hash(path,512*1024**2,run,cleanup=cleanup)!=entry['sha256']:raise Failure('Effective JAR changed during provider inspection')
    if containers!=[indices[0]]:raise Failure('Undeclared effective OpenCV class overlap/provider')

def prepared_record_check(run,prepared,expected_hash,cleanup=False):
    raw=bounded_bytes(run.work/'prepared.json',16*1024**2,run,cleanup=cleanup)
    if hashlib.sha256(raw).hexdigest()!=expected_hash or json.loads(raw)!=prepared:raise Failure('Immutable prepared record changed')

CLASSPATH_FIELDS=('schema','task','classpath','classpathString','owners','opencvAar','opencvClasses','opencvNormalization','sourceSha')

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
    ndk_identity=ndk.prepare(run,sdk,licenses)
    return sdk,licenses,{'java':{'path':str(java),'sha256':sha(java/'bin/java'),'releaseSha256':sha(java/'release')},'sdk':metadata,'ndkConfigurationPrerequisite':ndk_identity,'preexistingLicenseSha256':licenses}

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
    if graph.get('externalNativeTasks'):raise Blocked('Unexpected Android native tasks in HOST graph; configuration-only NDK does not authorize native execution')
    if graph.get('compileSdk') not in (36,'android-36') or graph.get('buildTools')!='36.0.0' or graph.get('ndkVersion')!=ndk.REVISION:raise Failure('Unexpected actual Android compilation configuration')
    run.command('gradle-prepare',[*gradle_args(run,config),':app:eightPrepare'],seconds=900)
    prepared=json.loads(bounded_bytes(run.work/'prepared.json',16*1024**2,run,cleanup=False))
    if prepared.get('sourceSha')!=source:raise Failure('Prepared source differs from selected source')
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
    prepared_record_check(run,prepared,cfg['preparedSha256'])
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
            prepared_record_check(run,prepared,cfg['preparedSha256'],cleanup=True)
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
    names=['ndk-prerequisite.json','commands.json','prepared.json','task-graph.json','effective-test-config.json','runtime-prepare.json','runtime-before-fork.json','observation-proof.json','test-events.jsonl']
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
        try:ndk.finish(run)
        except BaseException as error:secondary('ndk-postcheck-cleanup',error)
        if hasattr(run,'ndk_state'):receipt['ndkPrerequisite']=run.ndk_state
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
