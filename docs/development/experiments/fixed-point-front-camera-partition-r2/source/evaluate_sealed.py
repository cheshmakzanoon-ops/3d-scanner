"""Separate truth-only evaluator; reads sealed solver output, never called by solver."""
import sys
sys.dont_write_bytecode=True
import json,pathlib,struct,hashlib
import numpy as np
from cuboid_evaluate import evaluate

def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def main():
    fixture,meshfile,sealfile,out=map(pathlib.Path,sys.argv[1:]);seal=json.loads(sealfile.read_text());data=meshfile.read_bytes()
    if sha(meshfile)!=seal['meshBitsSHA256']:raise RuntimeError('sealed mesh changed')
    n=struct.unpack_from('<Q',data)[0];pos=8;vertices=[]
    for _ in range(n):vertices.append(struct.unpack_from('<3d',data,pos));pos+=56
    count=struct.unpack_from('<Q',data,pos)[0];pos+=8;triangles=[]
    for _ in range(count):triangles.append(struct.unpack_from('<3I',data,pos));pos+=13
    result=evaluate(json.loads(fixture.read_text()),np.asarray(vertices,dtype=float).reshape(-1,3),np.asarray(triangles,dtype=int).reshape(-1,3))
    if sha(meshfile)!=seal['meshBitsSHA256']:raise RuntimeError('mesh changed during evaluation')
    result.update(scope='ORACLE_CAMERA_COMPONENT_ONLY_NOT_IMAGE_DERIVED_REGISTRATION',oracleCameraExtrinsicsInput=True,geometryPassedToSolver=False,meshBitsSHA256=sha(meshfile))
    output=(json.dumps(result,indent=2,allow_nan=False)+'\n').encode()
    if len(output)>16777216 or out.exists():raise RuntimeError('evaluation output bound/freshness')
    out.write_bytes(output)
if __name__=='__main__':main()
