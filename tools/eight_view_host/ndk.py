"""Exact official NDK configuration prerequisite; no SDK terms or native execution.

Direct official archive layout is admitted by AGP's source.properties resolver. Only
fresh task-owned paths are created/deleted. Existing SDK packages are never replaced.
"""
from __future__ import annotations
import hashlib, json, os, posixpath, re, shutil, stat, struct, time, urllib.request, xml.etree.ElementTree as ET, zipfile
from pathlib import Path, PurePosixPath
from .runtime import Failure, Blocked, Deadline, atomic_json, inventory, sha
from tools.device.software_emulator.diagnostic import verify_license_snapshot

REVISION='28.2.13676358'
PACKAGE='ndk;'+REVISION
INDEX_URL='https://dl.google.com/android/repository/repository2-3.xml'
ARCHIVE_URL='https://dl.google.com/android/repository/android-ndk-r28c-linux.zip'
ARCHIVE_BYTES=722261334
ARCHIVE_SHA1='a7b54a5de87fecd125a17d54f73c446199e72a64'
LICENSE_ID='android-sdk-license'
LICENSE_SHA1='24333f8a63b6825ea9c5514f83c2829b004d1fee'
PREFIX='android-ndk-r28c'
ENTRY_COUNT=8996
EXPANDED_BYTES=2294132951
SYMLINK_COUNT=35
CENTRAL_BYTES=1540425
CENTRAL_SHA256='6ff39a6d45004d7e20fc45cffbd1c628b42be6c04fd4569ea8b56197521d3df8'
FLOOR=2*1024**3

class Budget:
    def __init__(self,run,seconds=600):self.run=run;self.end=run.clock()+run.remaining(seconds)
    def check(self):
        left=min(self.end-self.run.clock(),self.run.remaining(600))
        if left<=0:raise Deadline('NDK prerequisite 600-second bound')
        return left

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):raise Failure('NDK official download redirect refused')

def fetch(run,url,path,maximum,budget,record,exact=None,sha1=None):
    """One HTTPS request, closed stdin/no helper process; exact retained partial metadata."""
    if url not in (INDEX_URL,ARCHIVE_URL):raise Failure('Unreviewed NDK download URL')
    path=Path(path);record.update(url=url,complete=False,bytes=0)
    h=hashlib.sha256();h1=hashlib.sha1()
    try:
        request=urllib.request.Request(url,headers={'User-Agent':'RealityForge-bounded-NDK-prerequisite','Accept-Encoding':'identity'})
        with urllib.request.build_opener(NoRedirect).open(request,timeout=min(15,budget.check())) as response,path.open('xb') as output:
            if response.status!=200 or response.geturl()!=url:raise Failure('Unexpected NDK download response')
            size=response.headers.get('Content-Length')
            if size is not None and (not size.isdigit() or int(size)>maximum or exact is not None and int(size)!=exact):raise Failure('NDK download declared size')
            while True:
                budget.check();run.disk_guard()
                block=response.read1(min(65536,maximum-record['bytes']+1))
                if not block:break
                if record['bytes']+len(block)>maximum:raise Failure('NDK download byte bound')
                output.write(block);h.update(block);h1.update(block);record['bytes']+=len(block)
        if exact is not None and record['bytes']!=exact:raise Failure('Incomplete NDK archive')
        if sha1 is not None and h1.hexdigest()!=sha1:raise Failure('Official NDK archive SHA1 mismatch')
        record['complete']=True
    finally:
        record.update(sha256=h.hexdigest(),sha1=h1.hexdigest())

def metadata(raw):
    if len(raw)>1024**2 or b'<!DOCTYPE' in raw or b'<!ENTITY' in raw:raise Failure('NDK metadata size/entity bound')
    root=ET.fromstring(raw)
    packages=[x for x in root if x.tag.endswith('remotePackage') and x.get('path')==PACKAGE]
    if len(packages)!=1:raise Failure('Exact NDK metadata missing/duplicate')
    package=packages[0]
    def children(node,name):return [x for x in node if x.tag.rsplit('}',1)[-1]==name]
    def single(node,name):
        values=children(node,name)
        if len(values)!=1:raise Failure('NDK metadata field '+name)
        return values[0]
    revision=single(package,'revision')
    if '.'.join(single(revision,x).text or '' for x in ('major','minor','micro'))!=REVISION or children(revision,'preview'):raise Failure('NDK metadata revision')
    if single(package,'uses-license').get('ref')!=LICENSE_ID or children(package,'dependencies'):raise Failure('Unexpected NDK license/dependencies')
    archives=single(package,'archives');linux=[a for a in children(archives,'archive') if single(a,'host-os').text=='linux']
    if len(linux)!=1:raise Failure('NDK Linux archive missing/duplicate')
    complete=single(linux[0],'complete');checksum=single(complete,'checksum')
    if (single(complete,'size').text!=str(ARCHIVE_BYTES) or checksum.get('type')!='sha1' or checksum.text!=ARCHIVE_SHA1
        or single(complete,'url').text!='android-ndk-r28c-linux.zip'):raise Failure('Official NDK package identity changed')
    licenses=[x for x in root if x.tag.rsplit('}',1)[-1]=='license' and x.get('id')==LICENSE_ID]
    if len(licenses)!=1 or list(licenses[0]):raise Failure('NDK license metadata ambiguity')
    # Exact repository 31.11.1 TrimStringAdapter semantics (Java default ASCII \\s and trim).
    value=re.sub(r'(?<=\s)[ \t]*','',licenses[0].text or '',flags=re.ASCII)
    value=re.sub(r'(?<!\n)\n(?!\n)',' ',value);value=re.sub(' +',' ',value).strip(''.join(map(chr,range(33))))
    if hashlib.sha1(value.encode()).hexdigest()!=LICENSE_SHA1:raise Blocked('Official NDK license changed; no new terms accepted')
    return {'package':PACKAGE,'revision':REVISION,'licenseId':LICENSE_ID,'licenseSha1':LICENSE_SHA1,'archiveUrl':ARCHIVE_URL,'archiveBytes':ARCHIVE_BYTES,'archiveSha1':ARCHIVE_SHA1}

def accepted(sdk,licenses):
    verify_license_snapshot(sdk,licenses)
    path=sdk/'licenses'/LICENSE_ID
    if LICENSE_SHA1 not in path.read_text().splitlines():raise Blocked('Exact NDK license is not preaccepted; no terms accepted')

def central_admission(path,budget):
    """Bound central-directory allocation BEFORE ZipFile parses it; no ZIP64/multidisk."""
    with path.open('rb') as stream:
        stream.seek(-22,2);end=stream.read(22)
        if len(end)!=22:raise Failure('NDK ZIP missing EOCD')
        sig,disk,cd_disk,n_disk,n,size,offset,comment=struct.unpack('<4s4H2IH',end)
        if sig!=b'PK\x05\x06' or disk or cd_disk or n_disk!=n or n!=ENTRY_COUNT or size!=CENTRAL_BYTES or comment or offset+size+22!=path.stat().st_size:raise Failure('NDK ZIP central-directory bounds')
        budget.check();stream.seek(offset);raw=stream.read(size+1)
        if len(raw)!=size+1 or hashlib.sha256(raw[:size]).hexdigest()!=CENTRAL_SHA256:raise Failure('NDK ZIP central-directory identity')
    return offset

def archive_plan(archive,central,budget,expected_count=ENTRY_COUNT,expanded=EXPANDED_BYTES,links_count=SYMLINK_COUNT):
    entries=archive.infolist();seen={};total=0;links={};intervals=[]
    if len(entries)!=expected_count:raise Failure('NDK ZIP entry count')
    for entry in entries:
        budget.check();name=entry.filename;p=PurePosixPath(name);mode=entry.external_attr>>16;kind=stat.S_IFMT(mode)
        if (not p.parts or p.parts[0]!=PREFIX or p.is_absolute() or '..' in p.parts or '\\' in name or ':' in name or '\x00' in name
            or name.rstrip('/')!=p.as_posix() or p.as_posix() in seen or len(name)>256 or entry.flag_bits & ~2
            or entry.compress_type not in (0,8) or kind not in (stat.S_IFREG,stat.S_IFDIR,stat.S_IFLNK)
            or mode & 0o7000 or mode & 0o777 not in ({0o644,0o755} if kind==stat.S_IFREG else {0o755} if kind==stat.S_IFDIR else {0o777}) or entry.file_size<0 or entry.file_size>256*1024**2):raise Failure('Unsafe NDK ZIP entry')
        rel='/'.join(p.parts[1:]);seen[p.as_posix()]=(entry,rel,kind);total+=entry.file_size
        if total>expanded:raise Failure('NDK ZIP expansion bound')
        if kind==stat.S_IFDIR and (not name.endswith('/') or entry.file_size):raise Failure('NDK ZIP directory shape')
        if kind==stat.S_IFLNK:
            if entry.file_size>4096:raise Failure('NDK symlink payload bound')
            target=archive.read(entry).decode('utf-8')
            if not target or target.startswith('/') or '\\' in target or ':' in target or '\x00' in target:raise Failure('Unsafe NDK symlink')
            resolved=posixpath.normpath(posixpath.join(posixpath.dirname(p.as_posix()),target))
            if not resolved.startswith(PREFIX+'/'):raise Failure('NDK symlink escapes package')
            links[rel]=(target,resolved)
        # Validate actual local header ranges, not merely central claims. No data-descriptor flag admitted.
        archive.fp.seek(entry.header_offset);header=archive.fp.read(30)
        if len(header)!=30:raise Failure('NDK local header truncated')
        values=struct.unpack('<4s5H3I2H',header);start=entry.header_offset;end=start+30+values[-2]+values[-1]+entry.compress_size
        if values[0]!=b'PK\x03\x04' or start<0 or end>central or values[2]!=entry.flag_bits or values[3]!=entry.compress_type:raise Failure('NDK local header bounds')
        intervals.append((start,end))
    if total!=expanded or len(links)!=links_count:raise Failure('NDK ZIP aggregate identity')
    intervals.sort()
    if any(a[1]>b[0] for a,b in zip(intervals,intervals[1:])):raise Failure('NDK ZIP overlapping entries')
    for name,(entry,rel,kind) in seen.items():
        for parent in PurePosixPath(name).parents:
            if str(parent)=='.':break
            if parent.as_posix() not in seen or seen[parent.as_posix()][2]!=stat.S_IFDIR:raise Failure('NDK entry parent is absent/non-directory')
        if kind==stat.S_IFLNK:
            resolved=links[rel][1];visited={name}
            while True:
                if resolved in visited or resolved not in seen:raise Failure('NDK symlink missing/cyclic target')
                visited.add(resolved);other,other_rel,other_kind=seen[resolved]
                if other_kind!=stat.S_IFLNK:break
                resolved=links[other_rel][1]
    return list(seen.values()),links

def directory_identity(path):
    info=path.lstat()
    if not stat.S_ISDIR(info.st_mode):raise Failure('NDK owned directory is not a directory')
    return [info.st_dev,info.st_ino]

def package_identity(target,budget):
    records={}
    for name in ('source.properties','meta/abis.json','meta/platforms.json','build/cmake/android.toolchain.cmake'):
        budget.check();path=target/name
        for parent in (path,*path.parents):
            if parent.is_symlink():raise Failure('NDK configuration path is an alias')
            if parent==target:break
        if path.is_symlink() or not path.is_file() or path.stat().st_size>1024**2:raise Failure('NDK configuration file missing/unsafe')
        raw=path.read_bytes();records[name]={'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
        if name=='source.properties':
            rows=re.findall(r'^Pkg.Revision\s*=\s*(\S+)\s*$',raw.decode(),re.M)
            if rows!=[REVISION]:raise Failure('NDK source.properties revision mismatch')
    return records

def extract(run,path,target,budget,record):
    central=central_admission(path,budget)
    with zipfile.ZipFile(path) as archive:
        entries,links=archive_plan(archive,central,budget)
        target.mkdir()  # Fails if ANY prior target exists; no replacement/merge.
        record['ownedIdentity']=directory_identity(target);record['owned']=True;target.chmod(0o755)
        run.extra_owned_roots.append(target)
        # Explicit exact aliases only; strict inventory never gains a generic symlink exception.
        for rel,(_,resolved) in links.items():run.aliases[str(target/rel)]=str(target/PurePosixPath(resolved).relative_to(PREFIX))
        hashes={};total=0
        for entry,rel,kind in sorted(entries,key=lambda item:(item[2]!=stat.S_IFDIR,len(PurePosixPath(item[1]).parts),item[1])):
            budget.check()
            if directory_identity(target)!=record['ownedIdentity']:raise Failure('NDK extraction root replaced')
            if not rel:continue
            dest=target/rel
            if kind==stat.S_IFDIR:dest.mkdir();dest.chmod(0o755);continue
            if kind==stat.S_IFLNK:continue  # Links are created last, never traversed while writing.
            h=hashlib.sha256();count=0
            with archive.open(entry) as source,dest.open('xb') as output:
                while True:
                    budget.check()
                    if shutil.disk_usage(target.parent).free<FLOOR:raise Failure('SDK free-space floor during extraction')
                    block=source.read(min(65536,entry.file_size-count+1))
                    if not block:break
                    count+=len(block);total+=len(block)
                    if count>entry.file_size or total>EXPANDED_BYTES:raise Failure('NDK extraction expansion bound')
                    output.write(block);h.update(block)
            if count!=entry.file_size:raise Failure('NDK extraction incomplete entry')
            # Official ordinary executable semantics only, never setuid/setgid/sticky.
            dest.chmod(entry.external_attr>>16 & 0o777)
            hashes[rel]=h.hexdigest()
            if len(hashes)%100==0:run.disk_guard()
        for rel,(value,_) in links.items():
            budget.check();(target/rel).symlink_to(value)
        for rel in links:
            resolved=(target/rel).resolve(strict=True)
            if not resolved.is_relative_to(target.resolve()):raise Failure('NDK final symlink escaped')
        record.update(expandedBytes=sum(x.file_size for x,_,_ in entries),entries=len(entries),symlinkCount=len(links),treeSha256=hashlib.sha256(json.dumps(hashes,sort_keys=True).encode()).hexdigest())
        run.disk_guard()

def prepare(run,sdk,licenses):
    budget=Budget(run);target=sdk/'ndk'/REVISION
    record={'package':PACKAGE,'revision':REVISION,'status':'PENDING','owned':False,'path':str(target),'scope':'CONFIGURATION_PREREQUISITE_NO_ANDROID_NATIVE_TASKS'}
    run.ndk_state=record;primary=None
    try:
        accepted(sdk,licenses)
        index=run.work/'ndk-repository.xml';record['metadataDownload']={}
        fetch(run,INDEX_URL,index,1024**2,budget,record['metadataDownload'])
        record['officialMetadata']=metadata(index.read_bytes());accepted(sdk,licenses)
        if target.parent.is_symlink():raise Blocked('SDK NDK parent is an alias')
        if target.exists() or target.is_symlink():
            if target.is_symlink():raise Blocked('Preexisting NDK path is an alias')
            record['configurationFiles']=package_identity(target,budget);record['status']='PREINSTALLED_METADATA_VERIFIED_NOT_ARCHIVE_PROOF'
            return record
        parent=target.parent
        if parent.is_symlink() or not parent.is_dir():raise Blocked('Existing SDK NDK parent required, never redirect SDK paths')
        record['parentIdentity']=directory_identity(parent)
        free=shutil.disk_usage(parent).free;work_free=shutil.disk_usage(run.work).free
        same=parent.stat().st_dev==run.work.stat().st_dev
        needed=FLOOR+EXPANDED_BYTES+(ARCHIVE_BYTES if same else 0)+256*1024**2
        record['spaceAdmission']={'sdkFreeBytes':free,'workFreeBytes':work_free,'sameFilesystem':same,'sdkRequiredBytes':needed,'workRequiredBytes':FLOOR+ARCHIVE_BYTES}
        if free<needed or work_free<FLOOR+ARCHIVE_BYTES:raise Blocked('Insufficient free space for archive plus expanded NDK and 2GiB floor')
        archive=run.work/'ndk-r28c.zip';record['archiveDownload']={}
        fetch(run,ARCHIVE_URL,archive,ARCHIVE_BYTES,budget,record['archiveDownload'],exact=ARCHIVE_BYTES,sha1=ARCHIVE_SHA1)
        if directory_identity(parent)!=record['parentIdentity']:raise Failure('SDK NDK parent changed')
        extract(run,archive,target,budget,record)
        record['configurationFiles']=package_identity(target,budget)
        if shutil.disk_usage(parent).free<FLOOR:raise Failure('SDK free-space floor after NDK extraction')
        archive.unlink();record['archiveRemovedAfterVerification']=True
        record['status']='INSTALLED_OFFICIAL_ARCHIVE_VERIFIED'
        return record
    except BaseException as error:
        primary=error;record['status']=getattr(error,'status','FAIL');record['failure']={'type':type(error).__name__,'message':str(error)}
        raise
    finally:
        try:verify_license_snapshot(sdk,licenses);record['licensePostcheck']='PASS'
        except BaseException as error:
            record['licensePostcheck']='FAIL'
            if primary is None:raise
            primary.add_note('NDK license postcheck: '+str(error));run.evidence_errors.append('NDK license postcheck failed')
        try:atomic_json(run.work/'ndk-prerequisite.json',record)
        except BaseException as error:
            if primary is None:raise
            primary.add_note('NDK receipt write: '+str(error));run.evidence_errors.append('NDK prerequisite evidence incomplete')

def remove_owned(run,target,expected):
    """Bounded no-follow deletion of exactly the inode created by this run, never another SDK."""
    end=run.clock()+run.remaining(60,cleanup=True);count=0;total=0
    def check():
        if run.clock()>=end:raise Deadline('NDK owned cleanup deadline')
        run.remaining(60,cleanup=True)
    flags=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
    def walk(fd,depth):
        nonlocal count,total
        if depth>64:raise Failure('NDK cleanup depth bound')
        with os.scandir(fd) as entries:
            for entry in entries:
                check();count+=1
                if count>12000:raise Failure('NDK cleanup entry bound')
                info=entry.stat(follow_symlinks=False)
                if stat.S_ISREG(info.st_mode):
                    total+=info.st_size
                    if total>3*1024**3:raise Failure('NDK cleanup byte bound')
                if stat.S_ISDIR(info.st_mode):
                    child=os.open(entry.name,flags,dir_fd=fd)
                    try:
                        actual=os.fstat(child)
                        if (actual.st_dev,actual.st_ino)!=(info.st_dev,info.st_ino):raise Failure('NDK cleanup directory replaced')
                        walk(child,depth+1)
                    finally:os.close(child)
                    os.rmdir(entry.name,dir_fd=fd)
                elif stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode):os.unlink(entry.name,dir_fd=fd)
                else:raise Failure('NDK cleanup unexpected special file')
    check();fd=os.open(target,flags)
    try:
        actual=os.fstat(fd)
        if [actual.st_dev,actual.st_ino]!=expected or directory_identity(target)!=expected:raise Failure('NDK cleanup root identity changed')
        walk(fd,0);check()
        if directory_identity(target)!=expected:raise Failure('NDK cleanup root replaced')
        target.rmdir()
    finally:os.close(fd)

def finish(run):
    """Read-only configuration postcheck, then remove only this run's newly created package."""
    record=getattr(run,'ndk_state',None)
    if record is None:return
    target=Path(record['path']);primary=None
    try:
        if 'configurationFiles' in record:
            class CleanupBudget:
                def check(self):return run.remaining(60,cleanup=True)
            if package_identity(target,CleanupBudget())!=record['configurationFiles']:raise Failure('NDK configuration inputs changed')
            record['configurationPostcheck']='PASS'
    except BaseException as error:primary=error;record['configurationPostcheck']='FAIL'
    try:
        if record.get('owned'):
            if 'parentIdentity' in record and directory_identity(target.parent)!=record['parentIdentity']:raise Failure('SDK NDK parent replaced before cleanup')
            if directory_identity(target)!=record['ownedIdentity']:raise Failure('Refusing cleanup of replaced NDK root')
            # Partial link creation may leave dangling owned aliases; unlink, never resolve/follow.
            remove_owned(run,target,record['ownedIdentity']);record['ownedCleanup']='REMOVED_OWN_PARTIAL_OR_INSTALLED_PACKAGE'
            run.extra_owned_roots.remove(target)
            for path in list(run.aliases):
                if Path(path).is_relative_to(target):del run.aliases[path]
        else:record['ownedCleanup']='NOT_OWNED_NOT_REMOVED'
    except BaseException as error:
        record['ownedCleanup']='FAIL'
        if primary:primary.add_note('NDK owned cleanup: '+str(error))
        else:primary=error
    try:atomic_json(run.work/'ndk-prerequisite.json',record)
    except BaseException as error:
        if primary:primary.add_note('NDK final receipt: '+str(error))
        else:primary=error
    if primary:raise primary
