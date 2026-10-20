from pathlib import Path
import sys,hashlib,json,struct,subprocess
import cv2,numpy as np
root=Path('/workspace/agents/046');sys.path.insert(0,str(root/'tools'))
from benchmark_middlebury import load_middlebury,FOCAL_PIXELS,CX_LEFT,CX_RIGHT,CY
left,right,disparity=load_middlebury();h,w=left.shape[:2];fw=228;fh=round(h*fw/w)
rgba=np.ascontiguousarray([np.concatenate([cv2.resize(i,(fw,fh),interpolation=cv2.INTER_AREA),np.full((fh,fw,1),255,np.uint8)],axis=-1) for i in (left,right)])
cameras=np.ascontiguousarray([[1,0,0,0,1,0,0,0,1,-float(i),0,0,FOCAL_PIXELS*fw/w,FOCAL_PIXELS*fh/h,cx*fw/w,CY*fh/h] for i,cx in enumerate((CX_LEFT,CX_RIGHT))],dtype=np.float64)
cw=fw//2;ch=fh//2;coarse=np.ascontiguousarray([cv2.resize(i,(cw,ch),interpolation=cv2.INTER_AREA) for i in rgba]);coarse[...,3]=255
cc=cameras.copy();sx=cw/fw;sy=ch/fh;cc[:,12]*=sx;cc[:,13]*=sy;cc[:,14]=(cc[:,14]+.5)*sx-.5;cc[:,15]=(cc[:,15]+.5)*sy-.5
fixture=Path('/tmp/rf046-real-fixture.bin');fixture.write_bytes(struct.pack('<5I',fw,fh,2,cw,ch)+cameras.tobytes()+rgba.tobytes()+cc.tobytes()+coarse.tobytes())
old=json.loads((root/'build/agents/046-refined-middlebury.json').read_text())['baseline']
def evaluate(result):
 p=np.asarray(result['points'],float).reshape(-1,8);x=np.rint(p[:,0]*FOCAL_PIXELS/p[:,2]+CX_LEFT).astype(int);y=np.rint(p[:,1]*FOCAL_PIXELS/p[:,2]+CY).astype(int)
 inside=(x>=0)&(x<w)&(y>=0)&(y<h);truth=disparity[np.clip(y,0,h-1),np.clip(x,0,w-1)];valid=inside&np.isfinite(truth)&(truth>0)&(truth+CX_RIGHT-CX_LEFT>0)
 error=np.abs(FOCAL_PIXELS/p[valid,2]-(CX_RIGHT-CX_LEFT)-truth[valid]);d=FOCAL_PIXELS/(truth[valid]+CX_RIGHT-CX_LEFT);rel=np.abs(p[valid,2]-d)/d
 return dict(points=len(p),truth_supported=int(valid.sum()),disparity_p50=float(np.median(error)),disparity_p90=float(np.percentile(error,90)),relative_depth_p50=float(np.median(rel)),relative_depth_p90=float(np.percentile(rel,90)))
report={'dataset':'Middlebury2014 Motorcycle','truth_used_by_solver':False,'physical_device':False,'baseline':old,'declared_gates':'truth support >=max(1000,0.60*baseline); points>=1500; disparity p50<=1.10*baseline+0.05,p90<=0.95*baseline+0.10; absolute p50<2,p90<10; depth median<0.08,p90<0.30'}
for mode in ('strict','strict-refined'):
 result=json.loads(subprocess.check_output(['/tmp/rf046-real-profile',str(fixture),mode,hashlib.sha256(fixture.read_bytes()).hexdigest()]));new=evaluate(result)
 gates={'retention':new['truth_supported']>=max(1000,.6*old['truth_supported']),'points':new['points']>=1500,'p50':new['disparity_p50']<=1.10*old['disparity_p50']+.05,'p90':new['disparity_p90']<=.95*old['disparity_p90']+.1,'absolute':new['disparity_p50']<2 and new['disparity_p90']<10 and new['relative_depth_p50']<.08 and new['relative_depth_p90']<.30}
 report[mode]={'metrics':new,'gates':gates,'qualified':all(gates.values()),'coarseDepthMaps':result['coarseDepthMaps'],'propagatedHypotheses':result['propagatedHypotheses']}
out=root/'build/agents/046-strict-middlebury.json';out.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
