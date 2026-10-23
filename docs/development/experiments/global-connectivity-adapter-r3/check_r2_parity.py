"""Re-execute committed R2 controls against R3 dependencies; no photographs."""
import sys
sys.dont_write_bytecode=True
import pathlib,json,hashlib,importlib.util,resource
ROOT=pathlib.Path(__file__).resolve().parent
if len(sys.argv)!=2:raise RuntimeError('usage: check_r2_parity.py REVIEWED_R2_PACKAGE')
r2=pathlib.Path(sys.argv[1]).resolve()
if hashlib.sha256((r2/'SHA256SUMS').read_bytes()).hexdigest()!='459b287bebb3a90020fb3fec435ef04751174b679ca40200b5254ea7257e9872':raise RuntimeError('R2 identity')
for line in (r2/'SHA256SUMS').read_text().splitlines():
 h,n=line.split('  ',1)
 if hashlib.sha256((r2/n).read_bytes()).hexdigest()!=h:raise RuntimeError('R2 file identity')
resource.setrlimit(resource.RLIMIT_AS,(268435456,268435456));resource.setrlimit(resource.RLIMIT_CPU,(120,120))
sys.path.insert(0,str(ROOT/'source'))
from exact_geometry import Budget
import support_overlay,closed_certificate,orientation
sys.path.append(str(r2/'source'))
from controls import run
from overlay_controls import run_overlay
B=Budget();actual=run(B);actual['overlay']=run_overlay(B)
expected=json.loads((r2/'review/independent-normal.json').read_text())
for k,v in actual.items():
 if expected[k]!=v:raise RuntimeError('R2 semantic mismatch '+k)
if expected['work']!={'support':B.used_clips,'balls':B.used_balls}:raise RuntimeError('R2 work mismatch')
print(json.dumps({'status':'PASS_R2_DEFAULT_CONTROLS_WITH_R3_MODULES','sourceModules':{m.__name__:hashlib.sha256(pathlib.Path(m.__file__).read_bytes()).hexdigest() for m in (support_overlay,closed_certificate,orientation)},'work':expected['work']}))
