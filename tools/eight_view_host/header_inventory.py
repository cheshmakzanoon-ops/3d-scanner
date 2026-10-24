"""Bounded metadata-only enumeration; never admits or opens escaped header content."""
from pathlib import Path
import hashlib,json,os,stat,time
from .runtime import Failure,Deadline,Cancelled
MAX_ENTRIES=60000
MAX_ROWS=128
MAX_ERRORS=32
MAX_BYTES=20*1024
MAX_ROOTS=16
SECONDS=90


def scan(run,roots):
    result={'schema':1,'classification':'HEADER_ESCAPE_METADATA_ONLY_NOT_CONTENT_SNAPSHOT',
        'complete':False,'status':'INCOMPLETE','entriesSeen':0,'symlinksSeen':0,'escapedCount':0,
        'escapes':[],'errors':[],'escapedRowsOmitted':0,'errorRowsOmitted':0,'textsTruncated':0,
        'roots':[],'rootsOmitted':max(0,len(roots)-MAX_ROOTS),'limitsHit':[],
        'limits':{'seconds':SECONDS,'entries':MAX_ENTRIES,'escapeRows':MAX_ROWS,'errorRows':MAX_ERRORS,'jsonBytes':MAX_BYTES}}
    def text(value):
        value=str(value)
        if len(value)<=256:return value
        result['textsTruncated']+=1;raw=value.encode('utf-8',errors='surrogatepass')
        return {'preview':value[:256],'utf8Bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'truncated':True}
    def error(path,exc):
        if len(result['errors'])<MAX_ERRORS:
            result['errors'].append({'path':text(path),'type':type(exc).__name__,'errno':getattr(exc,'errno',None)})
        else:result['errorRowsOmitted']+=1
    def identity(info):return (info.st_dev,info.st_ino,stat.S_IFMT(info.st_mode),stat.S_IMODE(info.st_mode))
    def same(path,fd,wanted):
        if identity(path.lstat())!=wanted or identity(os.fstat(fd))!=wanted:
            raise Failure('Header metadata directory identity changed')
    end=time.monotonic()+min(SECONDS,run.remaining(SECONDS));flags=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
    class StopInventory(Exception):pass
    def budget():
        if time.monotonic()>=end:
            result['limitsHit'].append('TIME');raise StopInventory()
        try:run.remaining(SECONDS)
        except (Deadline,Cancelled) as exc:
            result['limitsHit'].append(type(exc).__name__.upper());error('inventory',exc);raise StopInventory()
    def version(info):return (info.st_mtime_ns,info.st_ctime_ns)
    observed=[]
    def open_root(path):
        # Anchor every absolute component, not only the final directory; an
        # ancestor replacement must not redirect enumeration outside the root.
        fd=os.open('/',flags)
        try:
            for component in path.parts[1:]:
                budget()
                child=os.open(component,flags,dir_fd=fd);os.close(fd);fd=child
            return fd
        except BaseException:os.close(fd);raise
    canonical=[];opened=[];seen=set();stopped=False
    if not roots:error('roots',Failure('Header metadata roots missing'))
    try:
        for root in roots[:MAX_ROOTS]:
            budget()
            try:
                root=Path(root).resolve(strict=True)
                if root not in canonical:canonical.append(root)
            except (OSError,RuntimeError) as exc:error(root,exc)
        for root in canonical:
            result['roots'].append(text(root))
            try:
                before=root.lstat();wanted=identity(before)
                if not stat.S_ISDIR(before.st_mode):raise Failure('Header metadata root is not directory')
                fd=open_root(root)
                try:same(root,fd,wanted)
                except BaseException:os.close(fd);raise
                opened.append((root,fd,wanted))
            except (OSError,Failure) as exc:error(root,exc)
        for root,root_fd,root_identity in opened:
            if stopped:break
            pending=[((),())]
            while pending and not stopped:
                parts,expected=pending.pop();path=root.joinpath(*parts);fd=None
                try:
                    budget()
                    same(root,root_fd,root_identity);fd=os.dup(root_fd)
                    for name,wanted in zip(parts,expected):
                        budget()
                        child=os.open(name,flags,dir_fd=fd)
                        try:
                            if identity(os.fstat(child))!=wanted:raise Failure('Header metadata directory identity changed')
                        except BaseException:os.close(child);raise
                        os.close(fd);fd=child
                    current=identity(os.fstat(fd));same(path,fd,current)
                    if current[:2] in seen:continue
                    seen.add(current[:2]);directory_version=version(os.fstat(fd))
                    observed.append((path,current,directory_version))
                    with os.scandir(fd) as entries:
                        for entry in entries:
                            if result['entriesSeen']>=MAX_ENTRIES:
                                result['limitsHit'].append('ENTRIES');stopped=True;break
                            budget()
                            result['entriesSeen']+=1;p=path/entry.name
                            try:
                                info=entry.stat(follow_symlinks=False)
                                if stat.S_ISDIR(info.st_mode):
                                    pending.append((parts+(entry.name,),expected+(identity(info),)))
                                elif stat.S_ISLNK(info.st_mode):
                                    result['symlinksSeen']+=1
                                    raw=os.readlink(entry.name,dir_fd=fd)
                                    # Resolution reads link metadata only. No target directory
                                    # enumeration or regular-file open occurs, even inside roots.
                                    target=p.resolve(strict=True)
                                    after=os.stat(entry.name,dir_fd=fd,follow_symlinks=False)
                                    if (identity(after)!=identity(info) or after.st_mtime_ns!=info.st_mtime_ns or
                                        after.st_ctime_ns!=info.st_ctime_ns or os.readlink(entry.name,dir_fd=fd)!=raw):
                                        raise Failure('Header metadata symlink changed')
                                    if not any(target.is_relative_to(admitted) for admitted in canonical):
                                        result['escapedCount']+=1
                                        if len(result['escapes'])<MAX_ROWS:
                                            result['escapes'].append({'path':text(p),'rawTarget':text(raw),'resolvedTarget':text(target)})
                                        else:result['escapedRowsOmitted']+=1
                                elif not stat.S_ISREG(info.st_mode):raise Failure('Header metadata special entry refused')
                            except (OSError,RuntimeError,Failure) as exc:error(p,exc)
                    same(path,fd,current)
                    if version(os.fstat(fd))!=directory_version:raise Failure('Header metadata directory changed during enumeration')
                except (OSError,RuntimeError,Failure) as exc:error(path,exc)
                finally:
                    if fd is not None:os.close(fd)
        for path,wanted,original_version in observed:
            budget()
            try:
                info=path.lstat()
                if identity(info)!=wanted or version(info)!=original_version:raise Failure('Header metadata enumerated directory changed')
            except (OSError,Failure) as exc:error(path,exc)
        for root,fd,wanted in opened:
            budget()
            try:same(root,fd,wanted)
            except (OSError,Failure) as exc:error(root,exc)
    except StopInventory:pass
    except Failure as exc:
        error('inventory',exc)
    finally:
        for _,fd,_ in opened:os.close(fd)
    def update():
        result['complete']=not (result['rootsOmitted'] or result['errors'] or result['errorRowsOmitted'] or
            result['escapedRowsOmitted'] or result['textsTruncated'] or result['limitsHit'])
        result['status']='COMPLETE' if result['complete'] else 'INCOMPLETE'
    update()
    # Loss is always explicit. This report is not an admission decision, even when complete.
    while len(json.dumps(result,sort_keys=True,ensure_ascii=True,separators=(',',':')).encode('ascii'))>MAX_BYTES:
        if 'OUTPUT' not in result['limitsHit']:result['limitsHit'].append('OUTPUT')
        if result['escapes']:result['escapes'].pop();result['escapedRowsOmitted']+=1
        elif result['errors']:result['errors'].pop();result['errorRowsOmitted']+=1
        elif result['roots']:result['roots'].pop();result['rootsOmitted']+=1
        else:raise Failure('Header metadata report minimum exceeds bound')
        update()
    return result
