"""Replay only with the separately retained, hash-matched synthetic photographs.
Args: repository, fixture-inputs directory, native library, output JSON.
No reference geometry or evaluation alignment enters the reconstruction.
"""
import hashlib,json,os,sys
from pathlib import Path
import numpy as np,cv2
repo,inputs,library,output=map(lambda value:Path(value).resolve(),sys.argv[1:])
sys.path.insert(0,str(repo/'tools'));os.environ['RF_NATIVE_LIBRARY']=str(library)
from native_api import NativeEngine
here=Path(__file__).resolve().parent
for name,expected in json.loads((here/'frozen-input-hashes.json').read_text()).items():
    actual=hashlib.sha256((inputs/name).read_bytes()).hexdigest()
    if actual!=expected:raise ValueError(f'Frozen input hash mismatch: {name}')
inp=json.loads((here/'frozen-mesh-input.json').read_text())
cal=json.loads((inputs/'calibration.json').read_text())['cameras'][:3]
rgba=np.array([cv2.cvtColor(cv2.imread(str(inputs/k['image']),cv2.IMREAD_UNCHANGED),cv2.COLOR_BGRA2RGBA) for k in cal])
result=NativeEngine().surface(rgba,np.array(inp['cameras']),inp['near'],inp['far'],depth_planes=96,pixel_stride=2)
output.write_text(json.dumps(result))
print({k:v for k,v in result['mesh'].items() if k not in ['vertices','triangles']})
