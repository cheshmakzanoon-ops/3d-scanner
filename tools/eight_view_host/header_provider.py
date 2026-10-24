"""Closed installed Clang resource-header roles, never build compiler selection."""
from pathlib import Path
import hashlib,json,os,re,stat
from .runtime import Failure,safe_display
from tools.native_process import native_compile_env
# Only the provider domain established by complete hosted metadata is admitted.
PROVIDERS=tuple({'major':major,'entry':Path(f'/usr/bin/clang-{major}'),
    'driver':Path(f'/usr/lib/llvm-{major}/bin/clang'),
    'root':Path(f'/usr/lib/llvm-{major}/lib/clang/{major}/include'),
    'aliases':tuple(Path(f'/usr/include/clang/{version}/include') for version in versions),
    'rawTarget':f'../../../lib/llvm-{major}/lib/clang/{major}/include'}
    for major,versions in ((16,('16','16.0.6')),(17,('17','17.0.6')),(18,('18.1.3',))))
LABELS=tuple(f'header-provider-{major}-{kind}' for major in (16,17,18) for kind in ('version','resource-dir'))
STATUS='ADMITTED_ROOTS_NOT_CONTENT_SNAPSHOT'


def _node(path,kind):
    info=path.lstat()
    actual='directory' if stat.S_ISDIR(info.st_mode) else 'file' if stat.S_ISREG(info.st_mode) else 'symlink' if stat.S_ISLNK(info.st_mode) else 'special'
    if actual!=kind:raise Failure('Clang header provider unexpected filesystem type')
    value={'path':str(path),'kind':actual,'device':str(info.st_dev),'inode':str(info.st_ino),
           'mode':info.st_mode,'size':info.st_size,'mtimeNs':info.st_mtime_ns,'ctimeNs':info.st_ctime_ns}
    if kind=='symlink':value['target']=os.readlink(path)
    return value


def filesystem():
    records={};parents=set()
    for role in PROVIDERS:
        for path in (role['entry'],role['driver'],role['root'],*role['aliases']):parents.update(path.parents)
    for path in sorted(parents,key=str):records[str(path)]=_node(path,'directory')
    for role in PROVIDERS:
        entry,driver,root=role['entry'],role['driver'],role['root']
        for path,kind in ((driver,'file'),(root,'directory'),(entry,'symlink' if entry.is_symlink() else 'file')):
            records[str(path)]=_node(path,kind)
        if entry.resolve(strict=True)!=driver or driver.resolve(strict=True)!=driver or not os.access(driver,os.X_OK):
            raise Failure('Clang header driver path/executable mismatch')
        if root.resolve(strict=True)!=root:raise Failure('Clang header root is not canonical')
        for alias in role['aliases']:
            records[str(alias)]=_node(alias,'symlink')
            if os.readlink(alias)!=role['rawTarget'] or alias.resolve(strict=True)!=root:
                raise Failure('Clang resource header alias mismatch')
    return [records[key] for key in sorted(records)]


def driver_hash(run,driver):
    maximum=512*1024**2;total=0;digest=hashlib.sha256()
    with driver.open('rb') as stream:
        while True:
            run.remaining(1);chunk=stream.read(1024**2)
            if not chunk:break
            total+=len(chunk)
            if total>maximum:raise Failure('Clang header driver byte limit')
            digest.update(chunk)
    if total==0:raise Failure('Clang header driver is empty')
    return digest.hexdigest()


def _labels(role):return tuple(f"header-provider-{role['major']}-{kind}" for kind in ('version','resource-dir'))


def _probe_records(run,role):
    outputs=[];labels=_labels(role)
    for label,arg in zip(labels,('--version','-print-resource-dir')):
        matching=[x for x in run.commands if x.get('label')==label]
        if len(matching)!=1:raise Failure('Clang provider probe command missing/duplicate')
        record=matching[0]
        if (record.get('argv')!=[str(role['driver']),arg] or record.get('runOwner')!=run.owner or
            record.get('complete') is not True or record.get('exit')!=0 or
            record.get('environmentBoundary')!='EXPLICIT_COMPILATION_TOOL' or
            any(k in record for k in ('error','cleanupError','secondaryErrors'))):
            raise Failure('Clang provider probe not authoritative successful command')
        path=run.work/'logs'/(label+'.raw.log')
        if path.is_symlink() or not path.is_file() or path.stat().st_size>4096:raise Failure('Clang provider probe log limit/type')
        raw=path.read_bytes()
        if len(raw)!=record.get('bytes') or hashlib.sha256(raw).hexdigest()!=record.get('sha256'):
            raise Failure('Clang provider probe log identity mismatch')
        outputs.append(raw)
    def refusal(message):
        rows=[]
        for label,raw in zip(labels,outputs):
            sensitive=safe_display(raw)[1]=='WITHHELD_SECRET_MARKER'
            rows.append({'label':label,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),
                'hexPreview':'' if sensitive else raw[:48].hex(),
                'previewStatus':'WITHHELD_SECRET_MARKER' if sensitive else 'BOUNDED_HEX',
                'omittedBytes':len(raw) if sensitive else max(0,len(raw)-48)})
        detail={'major':role['major'],'driver':str(role['driver']),'outputs':rows}
        return Failure(message+'; CLANG_PROVIDER_PROBE '+json.dumps(detail,sort_keys=True,separators=(',',':')))
    try:version=outputs[0].decode('utf-8');resource=outputs[1].decode('ascii')
    except UnicodeError as error:raise refusal('Clang probe output encoding') from error
    if not re.match(r'^(?:Ubuntu |Debian )?clang version '+str(role['major'])+r'\.[0-9]+(?:\.[0-9]+)?(?:[ \r\n]|$)',version):
        raise refusal('Clang header provider major/version mismatch')
    if resource not in (str(role['root'].parent),str(role['root'].parent)+'\n'):
        raise refusal('Clang resource directory probe mismatch')
    return [{'label':label,'outputText':raw.decode('utf-8'),'outputSha256':hashlib.sha256(raw).hexdigest()} for label,raw in zip(labels,outputs)]


def _provider_record(run,role,digest):
    return {'major':role['major'],'driver':{'path':str(role['driver']),'sha256':digest},
        'aliases':[{'path':str(alias),'rawTarget':role['rawTarget'],'root':str(role['root'])} for alias in role['aliases']],
        'includeRoot':str(role['root']),'probes':_probe_records(run,role)}


def acquire(run,source_sha):
    if not re.fullmatch('[0-9a-f]{40}',source_sha):raise Failure('Clang provider source identity required')
    before=filesystem();hashes=[driver_hash(run,role['driver']) for role in PROVIDERS]
    for role in PROVIDERS:
        for label,arg in zip(_labels(role),('--version','-print-resource-dir')):
            run.command(label,[role['driver'],arg],seconds=10,limit=4096,env=native_compile_env(run.env))
    proof={'schema':2,'sourceSha':source_sha,'runOwner':run.owner,'workDir':str(run.work),'status':STATUS,
        'filesystem':before,'providers':[_provider_record(run,role,digest) for role,digest in zip(PROVIDERS,hashes)]}
    validate(proof,source_sha,run)
    return proof


def validate(proof,source_sha,run):
    expected={'schema','sourceSha','runOwner','workDir','status','filesystem','providers'}
    if (not isinstance(proof,dict) or set(proof)!=expected or type(proof['schema']) is not int or proof['schema']!=2 or
        proof['sourceSha']!=source_sha or not re.fullmatch('[0-9a-f]{40}',source_sha) or
        proof['runOwner']!=run.owner or proof['workDir']!=str(run.work) or proof['status']!=STATUS):
        raise Failure('Clang provider proof identity/schema mismatch')
    before=filesystem();hashes=[driver_hash(run,role['driver']) for role in PROVIDERS];after=filesystem()
    if before!=after or before!=proof['filesystem']:raise Failure('Clang provider filesystem identity changed')
    actual=[_provider_record(run,role,digest) for role,digest in zip(PROVIDERS,hashes)]
    if proof['providers']!=actual:raise Failure('Clang provider closed-set identities/probes differ')
    return [role['root'] for role in PROVIDERS]
