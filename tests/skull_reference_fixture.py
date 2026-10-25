"""TEST ONLY: closed stdlib signal-supervisor fixture, never a reconstruction run.

Admission precedes removing loader injection from this generated-only closure.
The reviewed benchmark module is compiled from verified bytes with a non-main
name; its worker/main/NumPy/COLMAP entrypoints are never invoked. No import path
is added, so forged pyc, extension modules and cwd/site shadows are not loaded.
Parent/native instrumentation and sanitizer options are unchanged. This is an
audited functional-test boundary, not a general clean-Python launcher/sandbox.
"""
import hashlib
import os
from pathlib import Path
import sys

from synthetic_child_env import synthetic_child_env

ROOT = Path(__file__).resolve().parents[1]
BENCHMARK_SHA256 = 'b474cc4828e0bef2cb06917fc00a38a218df12c194242fdbe1b28bccaf62b233'
# Includes the exact nested worker and closed replacement argv. Changes require
# explicit source review and an updated literal pin, not a computed self-allowlist.
SUPERVISOR = '''import hashlib,json,pathlib,sys,types
if len(sys.argv) != 3 or not sys.flags.isolated or not sys.flags.no_site:
    raise ValueError('Unreviewed synthetic supervisor argv or interpreter flags')
source,output = map(pathlib.Path, sys.argv[1:])
if any(p.is_symlink() for p in (source,*source.parents)):
    raise ValueError('Synthetic supervisor source symlink refused')
raw = source.read_bytes()
if hashlib.sha256(raw).hexdigest() != 'b474cc4828e0bef2cb06917fc00a38a218df12c194242fdbe1b28bccaf62b233':
    raise ValueError('Synthetic supervisor source identity changed')
ref = types.ModuleType('reviewed_skull_reference_fixture')
ref.__file__ = str(source)
exec(compile(raw,str(source),'exec',dont_inherit=True),ref.__dict__)
worker = "import os,pathlib,signal,sys,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(3)"
original = ref.subprocess.Popen
owned = []
expected = [sys.executable,str(source.resolve()),'--input','unused','--output',str(output),'--worker']
def popen(command, **kwargs):
    if command != expected or owned or set(kwargs) != {'env','stdout','stderr','start_new_session'} or kwargs['start_new_session'] is not True:
        raise ValueError('Unreviewed synthetic worker invocation')
    p = original([sys.executable,'-I','-S','-c',worker,str(output/'worker.pid')],**kwargs)
    owned.append(p)
    return p
ref.subprocess.Popen = popen
code = ref.run_child(pathlib.Path('unused'),output)
(output/'reaped.json').write_text(json.dumps({'returncode':owned[0].returncode}))
sys.exit(code)
'''
SUPERVISOR_SHA256 = 'a4e61b42ce50c9cfa9cd6fd0607cedd873c43cf9e625a6f57f6e0fd238884459'


def signal_supervisor(output, parent=None):
    """Only this fixed generated fixture may receive a copied clean child env."""
    source = ROOT / 'tools/qa/skull_reference.py'
    if any(p.is_symlink() for p in (source, *source.parents)):
        raise ValueError('Benchmark fixture source symlink refused')
    if (not source.is_file() or source.stat().st_size > 65536
            or hashlib.sha256(source.read_bytes()).hexdigest() != BENCHMARK_SHA256):
        raise ValueError('Benchmark fixture source differs from reviewed identity')
    if hashlib.sha256(SUPERVISOR.encode()).hexdigest() != SUPERVISOR_SHA256:
        raise ValueError('Generated supervisor differs from reviewed identity')
    output = Path(os.path.abspath(output))
    if (not output.is_dir() or any(p.is_symlink() for p in (output, *output.parents))
            or output == ROOT or ROOT in output.parents):
        raise ValueError('Synthetic output must be an external nonsymlink test directory')
    argv = [sys.executable, '-I', '-S', '-c', SUPERVISOR, str(source), str(output)]
    return argv, synthetic_child_env(parent)
