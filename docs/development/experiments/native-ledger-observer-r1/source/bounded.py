"""Bounded POSIX process groups; signal-masked spawn closes handle-assignment race."""
import os,signal,subprocess,time,pathlib

def run(argv,log,seconds=60,env=None,cwd=None,max_bytes=16777216):
    log=pathlib.Path(log);proc=None;start=time.monotonic();signals=(signal.SIGINT,signal.SIGTERM)
    old={s:signal.getsignal(s) for s in signals}
    def interrupted(s,f):raise RuntimeError('INTERRUPTED:'+str(s))
    for s in signals:signal.signal(s,interrupted)
    try:
        with log.open('xb') as stream:
            mask=signal.pthread_sigmask(signal.SIG_BLOCK,signals)
            try:proc=subprocess.Popen(argv,stdout=stream,stderr=subprocess.STDOUT,env=env,cwd=cwd,start_new_session=True,preexec_fn=lambda: signal.pthread_sigmask(signal.SIG_SETMASK,mask))
            finally:signal.pthread_sigmask(signal.SIG_SETMASK,mask)
            while proc.poll() is None:
                if time.monotonic()-start>seconds:raise RuntimeError('WALL_BUDGET')
                if log.stat().st_size>max_bytes:raise RuntimeError('LOG_BUDGET')
                time.sleep(.05)
            if time.monotonic()-start>seconds or log.stat().st_size>max_bytes:raise RuntimeError('FINAL_TIME_OR_LOG_BUDGET')
            if proc.returncode:raise RuntimeError('CHILD_EXIT:'+str(proc.returncode)+':'+str(log))
    finally:
        for s in signals:signal.signal(s,signal.SIG_IGN)
        try:
            if proc is not None:
                try:os.killpg(proc.pid,signal.SIGTERM)
                except ProcessLookupError:pass
                deadline=time.monotonic()+1
                while time.monotonic()<deadline:
                    try:os.killpg(proc.pid,0)
                    except ProcessLookupError:break
                    time.sleep(.02)
                try:os.killpg(proc.pid,signal.SIGKILL)
                except ProcessLookupError:pass
                proc.wait(timeout=2)
        finally:
            for s in signals:signal.signal(s,old[s])
    return {'argv':argv,'seconds':time.monotonic()-start,'logBytes':log.stat().st_size}
