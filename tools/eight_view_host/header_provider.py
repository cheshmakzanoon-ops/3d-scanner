"""Exact installed Clang-18 resource-header admission, not a build compiler choice."""
from pathlib import Path
import hashlib,json,os,re,stat
from .runtime import Failure
from tools.native_process import native_compile_env
DRIVER_ENTRY=Path('/usr/bin/clang-18')
DRIVER=Path('/usr/lib/llvm-18/bin/clang')
ALIAS=Path('/usr/include/clang/18.1.3/include')
ALIAS_TARGET='../../../lib/llvm-18/lib/clang/18/include'
INCLUDE_ROOT=Path('/usr/lib/llvm-18/lib/clang/18/include')
LABELS=('header-provider-version','header-provider-resource-dir')
STATUS='ADMITTED_ROOT_NOT_CONTENT_SNAPSHOT'


def _node(path,kind):
    info=path.lstat()
    actual='directory' if stat.S_ISDIR(info.st_mode) else 'file' if stat.S_ISREG(info.st_mode) else 'symlink' if stat.S_ISLNK(info.st_mode) else 'special'
    if actual!=kind:raise Failure('Clang header provider unexpected filesystem type')
    value={'path':str(path),'kind':actual,'device':str(info.st_dev),'inode':str(info.st_ino),
           'mode':info.st_mode,'size':info.st_size,'mtimeNs':info.st_mtime_ns,'ctimeNs':info.st_ctime_ns}
    if kind=='symlink':value['target']=os.readlink(path)
    return value


def filesystem():
    # No symlink ancestors are silently followed. Only the two explicit entries
    # may be links, and both must resolve to fixed independently checked paths.
    parents=set()
    for path in (DRIVER_ENTRY,DRIVER,ALIAS,INCLUDE_ROOT):parents.update(path.parents)
    records=[_node(path,'directory') for path in sorted(parents,key=str)]
    records.append(_node(DRIVER,'file'));records.append(_node(INCLUDE_ROOT,'directory'))
    driver_kind='symlink' if DRIVER_ENTRY.is_symlink() else 'file'
    records.append(_node(DRIVER_ENTRY,driver_kind));records.append(_node(ALIAS,'symlink'))
    if DRIVER_ENTRY.resolve(strict=True)!=DRIVER or DRIVER.resolve(strict=True)!=DRIVER:
        raise Failure('Clang header driver path mismatch')
    if not os.access(DRIVER,os.X_OK):raise Failure('Clang header driver is not executable')
    if os.readlink(ALIAS)!=ALIAS_TARGET or ALIAS.resolve(strict=True)!=INCLUDE_ROOT or INCLUDE_ROOT.resolve(strict=True)!=INCLUDE_ROOT:
        raise Failure('Clang resource header alias mismatch')
    return records


def driver_hash(run):
    maximum=512*1024**2;total=0;digest=hashlib.sha256()
    with DRIVER.open('rb') as stream:
        while True:
            run.remaining(1);chunk=stream.read(1024**2)
            if not chunk:break
            total+=len(chunk)
            if total>maximum:raise Failure('Clang header driver byte limit')
            digest.update(chunk)
    if total==0:raise Failure('Clang header driver is empty')
    return digest.hexdigest()


def _outputs(outputs):
    version,resource=outputs
    if len(version)>4096 or len(resource)>4096:raise Failure('Clang probe output limit')
    try:v=version.decode('utf-8');r=resource.decode('ascii')
    except UnicodeError as error:raise Failure('Clang probe output encoding') from error
    if not re.match(r'^(?:Ubuntu |Debian )?clang version 18\.[0-9]+(?:\.[0-9]+)?(?:[ \r\n]|$)',v):
        raise Failure('Clang header provider major/version mismatch')
    if r not in (str(INCLUDE_ROOT.parent),str(INCLUDE_ROOT.parent)+'\n'):
        raise Failure('Clang resource directory probe mismatch')
    return [{'label':label,'outputText':raw.decode('utf-8'),'outputSha256':hashlib.sha256(raw).hexdigest()} for label,raw in zip(LABELS,outputs)]


def _probe_records(run):
    outputs=[]
    for label,arg in zip(LABELS,('--version','-print-resource-dir')):
        matching=[x for x in run.commands if x.get('label')==label]
        if len(matching)!=1:raise Failure('Clang provider probe command missing/duplicate')
        record=matching[0]
        if (record.get('argv')!=[str(DRIVER),arg] or record.get('runOwner')!=run.owner or
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
    return _outputs(outputs)


def acquire(run,source_sha):
    if not re.fullmatch('[0-9a-f]{40}',source_sha):raise Failure('Clang provider source identity required')
    before=filesystem();digest=driver_hash(run)
    for label,arg in zip(LABELS,('--version','-print-resource-dir')):
        run.command(label,[DRIVER,arg],seconds=10,limit=4096,env=native_compile_env(run.env))
    proof={'schema':1,'sourceSha':source_sha,'runOwner':run.owner,'workDir':str(run.work),'status':STATUS,
           'driver':{'path':str(DRIVER),'sha256':digest},'filesystem':before,'probes':_probe_records(run)}
    validate(proof,source_sha,run)
    return proof


def validate(proof,source_sha,run):
    expected={'schema','sourceSha','runOwner','workDir','status','driver','filesystem','probes'}
    if (not isinstance(proof,dict) or set(proof)!=expected or type(proof['schema']) is not int or proof['schema']!=1 or
        proof['sourceSha']!=source_sha or not re.fullmatch('[0-9a-f]{40}',source_sha) or
        proof['runOwner']!=run.owner or proof['workDir']!=str(run.work) or proof['status']!=STATUS):
        raise Failure('Clang provider proof identity/schema mismatch')
    before=filesystem();digest=driver_hash(run);after=filesystem()
    if before!=after or before!=proof['filesystem']:raise Failure('Clang provider filesystem identity changed')
    if proof['driver']!={'path':str(DRIVER),'sha256':digest}:raise Failure('Clang provider executable identity changed')
    if proof['probes']!=_probe_records(run):raise Failure('Clang provider recorded probe differs')
    return INCLUDE_ROOT
