import json, sys, numpy as np
from pathlib import Path
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path.cwd()

def cross(a,b):return a[0]*b[1]-a[1]*b[0]

def area(p):
 return abs(sum(cross(p[(i+1)%len(p)]-p[0],p[i]-p[0]) for i in range(1,len(p)-1)))/2 if len(p)>2 else 0.
def intersection(subject,clip):
 output=list(subject);ori=cross(clip[1]-clip[0],clip[2]-clip[0]);sign=1 if ori>0 else -1
 for a,b in zip(clip,np.roll(clip,-1,axis=0)):
  source=output;output=[]
  if not source:break
  previous=source[-1];dp=sign*cross(b-a,previous-a)
  for point in source:
   dc=sign*cross(b-a,point-a)
   if (dc>=0)!=(dp>=0):output.append(previous+(point-previous)*dp/(dp-dc))
   if dc>=0:output.append(point)
   previous=point;dp=dc
 return area(output)
def triangles(path):
 j=json.loads(path.read_text());v=np.array(j['vertices'])[:,:3];return v[np.array(j['triangles'])]
report={}
for n in ['rotated','reverse','partial','step']:
 old=triangles(root/'baseline-controls'/f'{n}.json');new=triangles(root/'canonical-controls'/f'{n}.json');loss=0;maximum=0
 for t in old:
  boxes=(new[:,:,0].max(1)>=t[:,0].min())&(new[:,:,0].min(1)<=t[:,0].max())&(new[:,:,1].max(1)>=t[:,1].min())&(new[:,:,1].min(1)<=t[:,1].max())&(abs(new[:,:,2].mean(1)-t[:,2].mean())<1e-12)
  covered=sum(intersection(t[:,:2],u[:,:2]) for u in new[boxes]);difference=max(0,area(t[:,:2])-covered);loss+=difference;maximum=max(maximum,difference)
 report[n]={'baselineSummedArea':sum(area(t[:,:2]) for t in old),'candidateUnionAreaNonoverlapping':sum(area(t[:,:2]) for t in new),'summedUncoveredBaselineTriangleArea':loss,'largestUncoveredTriangleArea':maximum}
print(json.dumps(report,indent=2));(root/'coverage.json').write_text(json.dumps(report,indent=2)+'\n')
