"""Bounded owned HOST subprocesses; no SDK, licensing or global process operations."""
from __future__ import annotations
import base64, ctypes, hashlib, json, os, re, selectors, shutil, signal, stat, subprocess, time
from pathlib import Path
from contextlib import contextmanager

class Failure(RuntimeError): status = 'FAIL'
class Blocked(Failure): status = 'BLOCKED'
class Deadline(Failure): status = 'TIMEOUT'
class Cancelled(Failure): status = 'CANCELLED'

def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()

def atomic_json(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + '.partial')
    partial.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n')
    partial.replace(path)

def inventory(root, max_bytes=8*1024**3, max_files=100000, aliases=None, seconds=10):
    """Bounded live inventory, not an atomic snapshot; vanished owned descendants may disappear.

    Root/replacement/permission failures remain fatal. Reopen each observed directory chain
    relative to the retained root FD, no-follow, checking identities before enumeration.
    Already encountered entries still count when removed; files already sized remain counted.
    """
    root = Path(root); entries = {}; total = 0; count = 0; started = time.monotonic(); end = started+seconds
    aliases = {} if aliases is None else aliases
    def limit(reason):
        detail={'reason':reason,'visitedEntries':count,'observedBytes':total,
                'maximumEntries':max_files,'budgetSeconds':seconds,'elapsedSeconds':round(max(0,time.monotonic()-started),6)}
        return Failure('Owned directory traversal/count limit; INVENTORY_LIMIT '+json.dumps(detail,sort_keys=True,separators=(',',':')))
    def identity(info): return (info.st_dev,info.st_ino,stat.S_IFMT(info.st_mode))
    root_info=root.lstat();root_identity=identity(root_info)
    if not stat.S_ISDIR(root_info.st_mode):raise Failure('Owned inventory root is not a directory')
    def root_unchanged():
        if identity(root.lstat())!=root_identity:raise Failure('Owned inventory root identity changed')
    flags=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
    root_fd=os.open(root,flags)
    try:
        if identity(os.fstat(root_fd))!=root_identity:raise Failure('Owned inventory root identity changed')
        pending=[((),())]
        while pending:
            if time.monotonic()>=end:raise limit('TIME')
            root_unchanged();parts,expected=pending.pop();fd=os.dup(root_fd);missing=False
            try:
                for part,wanted in zip(parts,expected):
                    if time.monotonic()>=end:raise limit('TIME')
                    try:child=os.open(part,flags,dir_fd=fd)
                    except FileNotFoundError:
                        root_unchanged();missing=True;break  # Previously discovered descendant only.
                    try:
                        if identity(os.fstat(child))!=wanted:raise Failure('Owned directory identity changed')
                    except BaseException:
                        os.close(child);raise
                    os.close(fd);fd=child
                if missing:continue
                with os.scandir(fd) as iterator:
                    for entry in iterator:
                        count+=1
                        if count>max_files:raise limit('COUNT')
                        if time.monotonic()>=end:raise limit('TIME')
                        try:info=entry.stat(follow_symlinks=False)
                        except FileNotFoundError:continue  # Concurrent owned temporary entry disappeared.
                        if stat.S_ISLNK(info.st_mode):
                            p=root.joinpath(*parts,entry.name)
                            if str(p) not in aliases or p.resolve(strict=True)!=Path(aliases[str(p)]).resolve(strict=True):
                                raise Failure('Unexpected symlink in owned tree: '+str(p))
                        elif stat.S_ISDIR(info.st_mode):pending.append((parts+(entry.name,),expected+(identity(info),)))
                        elif stat.S_ISREG(info.st_mode):
                            total+=info.st_size
                            if total>max_bytes:raise Failure('Owned directory size limit')
                        else:raise Failure('Unexpected special file in owned tree')
                        # parts originate only from scandir names below the retained
                        # root FD. Lexical keys need no per-file absolute Path roundtrip;
                        # all no-follow stat/open and ancestry checks above remain intact.
                        entries['/'.join((*parts,entry.name))]=info.st_size if stat.S_ISREG(info.st_mode) else 0
            finally:os.close(fd)
        root_unchanged()
    finally:os.close(root_fd)
    return total,entries

def child_pids(pid, end=None):
    try: return [int(x) for x in Path(f'/proc/{pid}/task/{pid}/children').read_text().split()]
    except ProcessLookupError:
        # The task exited between the ownership/starttime check and this read.
        # Adopted descendants remain discoverable from the controller/subreaper.
        return []
    except FileNotFoundError:
        # Some unprivileged procfs views omit task/children; inspect stat PPID only.
        result=[];count=0;end=min(time.monotonic()+2,end if end is not None else float('inf'))
        with os.scandir('/proc') as entries:
            for entry in entries:
                count+=1
                if count>65536 or time.monotonic()>end:raise Failure('Process ancestry scan limit')
                if entry.name.isdigit():
                    info=proc_info(int(entry.name))
                    if info and info['ppid']==pid:result.append(info['pid'])
        return result

def subreaper(value=None):
    # Linux per-process lifecycle state only: no privileges, global/security settings or persistence.
    libc=ctypes.CDLL(None,use_errno=True); flag=ctypes.c_int()
    if libc.prctl(37,ctypes.byref(flag),0,0,0)!=0: raise Blocked('Cannot query child subreaper capability')
    previous=flag.value
    if value is not None:
        if libc.prctl(36,int(value),0,0,0)!=0: raise Blocked('Cannot activate owned child subreaper')
        if libc.prctl(37,ctypes.byref(flag),0,0,0)!=0 or flag.value!=int(value): raise Failure('Child subreaper state verification failed')
    return previous

def proc_info(pid):
    try:
        data = Path(f'/proc/{pid}/stat').read_text(); tail = data[data.rfind(')') + 2:].split()
        return {'pid': int(pid), 'ppid': int(tail[1]), 'group': int(tail[2]), 'start': int(tail[19]), 'state': tail[0]}
    except (FileNotFoundError, ProcessLookupError): return None

def safe_display(raw):
    text = raw.decode('utf-8', 'replace')
    if re.search(r'-----BEGIN [A-Z ]*PRIVATE KEY-----|(?i:authorization\s*[:=]|\b(?:password|passwd|access[_-]?token|refresh[_-]?token|client[_-]?secret|api[_-]?key)\s*[:=]|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|https?://[^\s/@:]+:[^\s/@]+@)', text):
        return b'', 'WITHHELD_SECRET_MARKER'
    return '\n'.join(''.join(c if c >= ' ' or c == '\t' else '?' for c in l) for l in text.splitlines()).rstrip('\n').encode(), 'SANITIZED_DIAGNOSTIC'

def frame(label, raw, limit=256*1024):
    if not re.fullmatch(r'[a-z0-9-]+', label) or len(raw) > limit: raise Failure('Essential frame limit/label')
    count = (len(raw) + 3071)//3072
    print('RF_EIGHT_FRAME_META ' + json.dumps({'label':label,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'chunks':count}), flush=True)
    for i in range(count): print(f'RF_EIGHT_FRAME {label} {i+1}/{count} ' + base64.b64encode(raw[i*3072:(i+1)*3072]).decode(), flush=True)

class Runner:
    def __init__(self, root, work, env=None, clock=time.monotonic, seconds=4200):
        self.root, self.work = Path(root), Path(work)
        self.env = dict(os.environ if env is None else env); self.clock = clock
        self.started = clock(); self.end = self.started + seconds; self.work_end = self.end - 120
        self.signal = 0; self.commands = []; self.owned = {}; self.last_scan = 0
        self.work.mkdir(parents=True, exist_ok=True); (self.work/'logs').mkdir(exist_ok=True)
        self.owner = os.urandom(16).hex(); self.env['RF_EIGHT_RUN_OWNER'] = self.owner
        self.old_handlers = {}; self.extra_owned_roots = []; self.evidence_errors = []
        self.aliases = {}; self.pidfds = {}; self.subreaper_before = None; self.baseline_children = set()
    def install_signals(self):
        for s in (signal.SIGTERM, signal.SIGINT):
            self.old_handlers[s] = signal.signal(s, lambda signum, _frame: setattr(self, 'signal', signum))
    def remaining(self, cap, cleanup=False):
        if self.signal and not cleanup: raise Cancelled('External signal ' + str(self.signal))
        left = min(cap, (self.end if cleanup else self.work_end) - self.clock())
        if left <= 0: raise Deadline('Overall HOST deadline')
        return left
    def activate_lifecycle(self):
        if self.subreaper_before is None:
            if not hasattr(os,'pidfd_open') or not hasattr(signal,'pidfd_send_signal'): raise Blocked('Linux pidfd required')
            self.baseline_children=set(child_pids(os.getpid()))
            if self.baseline_children:raise Blocked('Dedicated controller must have no preexisting children')
            self.subreaper_before=subreaper()
            try:subreaper(1)
            except BaseException as primary:
                try:subreaper(self.subreaper_before);self.subreaper_before=None
                except BaseException as restore:
                    self.evidence_errors.append('Lifecycle activation rollback failed: '+str(restore));primary.add_note(str(restore))
                raise
    def register(self,pid,allowed_parent):
        before=proc_info(pid)
        if not before or before['ppid']!=allowed_parent: return
        if pid in self.owned:
            if self.owned[pid]!=before['start']: raise Failure('Owned PID identity changed')
            return
        try: fd=os.pidfd_open(pid)
        except ProcessLookupError: return
        after=proc_info(pid)
        if not after or (after['start'],after['ppid'])!=(before['start'],allowed_parent):
            os.close(fd);return
        self.owned[pid]=after['start'];self.pidfds[pid]=fd
    def scan(self, leader=None, cleanup=False):
        if self.subreaper_before is None: return
        left=(self.end if cleanup else min(self.work_end,getattr(self,'phase_end',self.work_end)))-self.clock()
        if left<=0:raise Deadline('Process ownership scan deadline')
        scan_end=time.monotonic()+min(2,left)
        # Only self/direct descendants: no unrelated /proc enumeration or environment reads.
        for pid,fd in list(self.pidfds.items()):
            info=proc_info(pid)
            if not info or info['start']!=self.owned[pid] or info['state']=='Z':
                os.close(fd);del self.pidfds[pid]
        pending=[os.getpid()];seen=set()
        while pending:
            if time.monotonic()>=scan_end:raise Deadline('Process ownership traversal limit')
            parent=pending.pop()
            if parent in seen: continue
            seen.add(parent)
            if len(seen)>4096: raise Failure('Owned process count limit')
            if parent!=os.getpid():
                info=proc_info(parent)
                if not info or info['start']!=self.owned.get(parent): continue
            for pid in child_pids(parent,scan_end):
                if parent==os.getpid() and pid in self.baseline_children: continue
                self.register(pid,parent)
                if pid in self.owned: pending.append(pid)
    def disk_guard(self):
        if shutil.disk_usage(self.work).free < 2*1024**3: raise Failure('Workspace free-space floor')
        if self.clock() - self.last_scan >= 5:
            boundary=self.end if getattr(self,'phase_cleanup',False) else self.work_end
            end=min(boundary,getattr(self,'phase_end',boundary))
            def left():
                value=min(10,end-self.clock())
                if value<=0:raise Deadline('Owned disk inventory deadline')
                return value
            def scan(root,role):
                try:return inventory(root,aliases=self.aliases,seconds=left())
                except Deadline as error:
                    raise Deadline(str(error)+'; OWNED_ROOT_ROLE '+role) from error
                except Failure as error:
                    if str(error).startswith('Owned directory traversal/count limit'):
                        raise Failure(str(error)+'; OWNED_ROOT_ROLE '+role) from error
                    raise
            total, files = scan(self.work,'work')
            count=len(files)
            for index,root in enumerate(self.extra_owned_roots):
                if root.exists():
                    size,entries=scan(root,'extra-owned-'+str(index));total+=size;count+=len(entries)
            if total>8*1024**3 or count>100000:raise Failure('Combined owned build directory size/count limit')
            self.last_scan = self.clock()
    def command(self, label, args, seconds=60, env=None, cwd=None, limit=8*1024**2, check=True, cleanup=False):
        if not re.fullmatch(r'[a-z0-9-]+',label) or any(c['label']==label for c in self.commands): raise Failure('Duplicate/unsafe command label')
        record={'label':label,'complete':False,'argv':[str(x) for x in args],'runOwner':self.owner,'environmentBoundary':'INHERITED_RUNTIME' if env is None else 'EXPLICIT_COMPILATION_TOOL'}; self.commands.append(record)
        raw=bytearray(); proc=None; start=self.clock(); deadline=start+self.remaining(seconds, cleanup)
        previous_phase=(getattr(self,'phase_end',None),getattr(self,'phase_cleanup',None))
        self.phase_end=deadline;self.phase_cleanup=cleanup
        failure=None
        # Fixed-size diagnostic counters only. A separate monotonic clock never
        # participates in admission/deadlines, and no per-process trace is retained.
        timing_started=time.perf_counter()
        operations={name:{'calls':0,'seconds':0.0} for name in (
            'preflight_disk_guard','activate_lifecycle','spawn','initial_scan',
            'loop_disk_guard','loop_scan','selector_wait','read_output','exit_poll','eof_wait',
            'cleanup_scan','cleanup_signal','cleanup_wait','cleanup_close','cleanup_owned')}
        timing={'schema':1,'clock':'PERF_COUNTER_DIAGNOSTIC_ONLY','phaseBudgetSeconds':deadline-start,
            'operations':operations,'lastOperation':None,'firstFailedOperation':None}
        record['timing']=timing
        @contextmanager
        def measured(name):
            began=time.perf_counter();timing['lastOperation']=name;operations[name]['calls']+=1
            try:yield
            except BaseException:
                if timing['firstFailedOperation'] is None:timing['firstFailedOperation']=name
                raise
            finally:operations[name]['seconds']+=max(0.0,time.perf_counter()-began)
        try:
            with measured('preflight_disk_guard'):self.disk_guard()
            with measured('activate_lifecycle'):self.activate_lifecycle()
            with measured('spawn'):
                proc=subprocess.Popen(record['argv'],cwd=self.root if cwd is None else cwd,env=self.env if env is None else env,
                    stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
            record['pid']=proc.pid
            identity=proc_info(proc.pid)
            if not identity:raise Failure('Owned leader identity unavailable')
            record['starttime']=identity['start']
            with measured('initial_scan'):self.scan(proc.pid,cleanup=cleanup)
            os.set_blocking(proc.stdout.fileno(),False)
            with selectors.DefaultSelector() as selector:
                selector.register(proc.stdout, selectors.EVENT_READ); eof=False
                while True:
                    self.remaining(1,cleanup)
                    with measured('loop_disk_guard'):self.disk_guard()
                    with measured('loop_scan'):self.scan(proc.pid,cleanup=cleanup)
                    if self.clock() >= deadline: raise Deadline(label+' timed out')
                    with measured('selector_wait'):
                        ready=selector.select(min(.2,max(0,deadline-self.clock()))) if not eof else []
                    for key,_ in ready:
                        with measured('read_output'):chunk=os.read(key.fd,65536)
                        if not chunk: selector.unregister(proc.stdout);eof=True
                        elif len(raw)+len(chunk)>limit:
                            raw.extend(chunk[:max(0,limit-len(raw))]);raise Failure(label+' output limit')
                        else: raw.extend(chunk)
                    with measured('exit_poll'):
                        exited=os.waitid(os.P_PID,proc.pid,os.WEXITED|os.WNOHANG|os.WNOWAIT)
                    if eof and exited:
                        record.update(complete=True,exit=exited.si_status if exited.si_code==os.CLD_EXITED else 128+exited.si_status)
                        if check and record['exit']: raise Failure(label+' exit '+str(record['exit']))
                        return bytes(raw),record['exit']
                    if eof:
                        with measured('eof_wait'):time.sleep(.05)
        except BaseException as error:
            failure=error;record['error']=type(error).__name__+': '+str(error);raise
        finally:
            cleanup_started=time.perf_counter()
            timing['lastOperationBeforeCleanup']=timing['lastOperation']
            timing['failedOperationBeforeCleanup']=timing['firstFailedOperation']
            timing['phaseElapsedBeforeCleanupSeconds']=max(0.0,cleanup_started-timing_started)
            for name,value in zip(('phase_end','phase_cleanup'),previous_phase):
                if value is None:self.__dict__.pop(name,None)
                else:setattr(self,name,value)
            cleanup_errors=[]
            if proc:
                try:
                    with measured('cleanup_scan'):self.scan(proc.pid,cleanup=True)
                except BaseException as error:cleanup_errors.append(error)
                # Known unreaped session leader retains its process group identity even if discovery fails.
                try:
                    with measured('cleanup_signal'):
                        for sig in (signal.SIGTERM,signal.SIGKILL):
                            try:os.killpg(proc.pid,sig)
                            except ProcessLookupError:break
                            if sig==signal.SIGTERM:time.sleep(.25)
                except BaseException as error:cleanup_errors.append(error)
                try:
                    with measured('cleanup_wait'):proc.wait(timeout=2)
                except BaseException as error:cleanup_errors.append(error)
                try:
                    with measured('cleanup_close'):proc.stdout.close()
                except BaseException as error:cleanup_errors.append(error)
                try:
                    with measured('cleanup_owned'):self.terminate_owned()
                except BaseException as error:cleanup_errors.append(error)
                if cleanup_errors:record['cleanupError']='; '.join(type(e).__name__+': '+str(e) for e in cleanup_errors)
            timing['cleanupSeconds']=max(0.0,time.perf_counter()-cleanup_started)
            for value in operations.values():value['seconds']=round(value['seconds'],6)
            for key in ('phaseBudgetSeconds','phaseElapsedBeforeCleanupSeconds','cleanupSeconds'):
                timing[key]=round(timing[key],6)
            record.update(seconds=round(self.clock()-start,3),bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
            secondary=list(cleanup_errors)
            try:
                (self.work/'logs'/f'{label}.raw.log').write_bytes(raw)
                atomic_json(self.work/'commands.json',self.commands)
            except BaseException as error:secondary.append(error)
            try:print('RF_EIGHT_COMMAND '+json.dumps({k:v for k,v in record.items() if k!='argv'}),flush=True)
            except BaseException as error:secondary.append(error)
            if secondary:
                messages=[type(e).__name__+': '+str(e) for e in secondary]
                record['secondaryErrors']=messages;self.evidence_errors.extend(messages)
                if failure is None:raise Failure('Command cleanup/evidence incomplete: '+'; '.join(messages)) from secondary[0]
                failure.add_note('Secondary cleanup/evidence failure: '+'; '.join(messages))
    def terminate_owned(self):
        if self.subreaper_before is None: return
        discovery_errors=[]
        # Discovery failures never prevent signaling the independently known owned pidfds.
        for attempt in range(6):
            try:self.scan(cleanup=True)
            except BaseException as error:discovery_errors.append(error)
            for pid,fd in list(self.pidfds.items()):
                try:signal.pidfd_send_signal(fd,signal.SIGTERM if attempt==0 else signal.SIGKILL)
                except ProcessLookupError:pass
                except BaseException as error:discovery_errors.append(error)
            time.sleep(.1 if attempt else .2)
            for pid in list(self.owned):
                try:os.waitpid(pid,os.WNOHANG)
                except ChildProcessError:pass
                except BaseException as error:discovery_errors.append(error)
            try:
                self.scan(cleanup=True)
                live=[pid for pid,start in self.owned.items() if (i:=proc_info(pid)) and i['start']==start and i['state']!='Z']
                adopted=[pid for pid in child_pids(os.getpid()) if pid not in self.baseline_children]
                if not live and not adopted:break
            except BaseException as error:discovery_errors.append(error)
        else:discovery_errors.append(Failure('Owned descendants survive/unverified cleanup'))
        if discovery_errors:
            primary=discovery_errors[0]
            for error in discovery_errors[1:]:primary.add_note('Further owned cleanup failure: '+str(error))
            raise primary
    def restore_signals(self):
        for s,handler in self.old_handlers.items():signal.signal(s,handler)
    def cleanup(self):
        primary=None;secondary=[]
        try:self.terminate_owned()
        except BaseException as error:primary=error
        finally:
            if self.subreaper_before is not None:
                try:subreaper(self.subreaper_before);self.subreaper_before=None
                except BaseException as error:secondary.append(error)
            for fd in self.pidfds.values():
                try:os.close(fd)
                except BaseException as error:secondary.append(error)
            self.pidfds.clear()
        if primary:
            for error in secondary:primary.add_note('Lifecycle restoration/FD cleanup: '+str(error))
            raise primary
        if secondary:raise Failure('Lifecycle restoration/FD cleanup failed: '+'; '.join(str(e) for e in secondary)) from secondary[0]
        if self.evidence_errors:raise Failure('Incomplete command evidence/cleanup')
        if self.signal:raise Cancelled('External signal '+str(self.signal))
        if self.clock()>=self.end:raise Deadline('Overall HOST deadline during cleanup')
