"""Independent analytic/pixel-array fixture; no real data or native measurements."""
from pathlib import Path
import json
import cv2
import numpy as np
source=(1001,777);target=(317,241)
sx=target[0]/source[0];sy=target[1]/source[1]
fx,fy,cx,cy=713.7,817.4,441.25,319.75
scaled=(fx*sx,fy*sy,(cx+.5)*sx-.5,(cy+.5)*sy-.5)
errors=[];bad_errors=[]
for u in [0.,17.125,158.,316.]:
 for v in [0.,41.75,120.,240.]:
  original=((u+.5)/sx-.5,(v+.5)/sy-.5)
  ray=((original[0]-cx)/fx,(original[1]-cy)/fy)
  transformed=((u-scaled[2])/scaled[0],(v-scaled[3])/scaled[1])
  wrong=((u-cx*sx)/(fx*sx),(v-cy*sy)/(fy*sy))
  errors.append(max(abs(ray[i]-transformed[i]) for i in [0,1]))
  bad_errors.append(max(abs(ray[i]-wrong[i]) for i in [0,1]))
assert max(errors)<1e-14
assert min(bad_errors)>1e-4
# Actual INTER_AREA integrates 4x3 source pixel blocks. A linear source array's
# exact block average equals its value at the mapped block's geometric centre.
y,x=np.mgrid[:39,:84]
image=(2*x+3*y+7).astype(np.float32)
actual=cv2.resize(image,(21,13),interpolation=cv2.INTER_AREA)
v,u=np.mgrid[:13,:21]
expected=2*((u+.5)/.25-.5)+3*((v+.5)/(1/3)-.5)+7
wrong=2*(u/.25)+3*(v/(1/3))+7
area_error=float(np.max(np.abs(actual-expected)))
wrong_error=float(np.min(np.abs(actual-wrong)))
assert area_error<1e-5 and wrong_error>1
output={'synthetic':True,'sourceDimensions':source,'targetDimensions':target,
 'scales':[sx,sy],'sourceIntrinsics':[fx,fy,cx,cy],
 'correctResizedIntrinsics':scaled,'maximumNormalizedRayError':max(errors),
 'oldDirectScaleNormalizedRayError':bad_errors[0],
 'oldTruthSamplingBiasOriginalPixels':[.5/sx-.5,.5/sy-.5],
 'actualOpenCvAreaFixture':{'sourceDimensions':[84,39],'targetDimensions':[21,13],
  'maximumCorrectPixelError':area_error,'minimumOldOriginPixelError':wrong_error}}
Path('/workspace/agents/diagnostics/resize-ray-centres.json').write_text(json.dumps(output,indent=2)+'\n')
print(json.dumps(output,indent=2))
