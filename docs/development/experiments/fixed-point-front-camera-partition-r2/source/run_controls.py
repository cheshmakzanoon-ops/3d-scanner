import sys
sys.dont_write_bytecode=True
import pathlib,json,resource
from front_controls import run
from cancellation_controls import run as cancellation
from partition_proof import controls as partition_controls
p,out=map(pathlib.Path,sys.argv[1:]);cfg=json.loads((p/'config.json').read_text())
if out.exists():raise RuntimeError('fresh control output required')
out.mkdir()
if resource.getrlimit(resource.RLIMIT_AS)[0]>1073741824 or resource.getrlimit(resource.RLIMIT_CPU)[0]>600:raise RuntimeError('control process caps required')
try:
 result=run(cfg,out);result['cameraPartition']=partition_controls();result['externalCancellation']=cancellation(out/'cancellation');data=json.dumps(result,indent=2).encode()
 if len(data)>16777216:raise RuntimeError('control result byte cap')
 (out/'results.json').write_bytes(data);print('CONTROLS_PASS')
except BaseException as e:
 (out/'REFUSED.json').write_text(json.dumps({'error':type(e).__name__,'reason':str(e),'wholeDataset':'NOT_RUN'},indent=2)+'\n');raise
