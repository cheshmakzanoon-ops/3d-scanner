"""Source-only structural guard fixtures; no geometry-quality claim."""
import importlib.util
from pathlib import Path
import struct
import json
spec=importlib.util.spec_from_file_location('edge_reader',Path(__file__).with_name('inspect.py'))
reader=importlib.util.module_from_spec(spec);spec.loader.exec_module(reader)

def check(value):
 if not value:raise AssertionError('structural fixture mismatch')
def fixture(tris):
 nv=max(max(t[:3]) for t in tris)+1
 cameras=[set() for _ in range(nv)]
 for a,b,c,cam in tris:
  for i in (a,b,c):cameras[i].add(cam)
 rows=[];obs=[]
 for v,refs in enumerate(cameras):
  for cam in sorted(refs):
   sid=len(rows);rows.append([sid,cam,v,0,0,0,0,0,0,0]);obs.append((v,cam,v,0,sid))
 agg,_=reader.edge_incidence(tris,cameras,set())
 data=bytearray(b'RFMESH01')+struct.pack('<III5Qf',nv,len(tris),len(obs),len(obs),len(obs),0,0,0,1.)
 vertices=[]
 for v,refs in enumerate(cameras):
  xyz=[float(v),float(v*v),0.];normal=[0.,0.,1.]
  data+=struct.pack('<6d3BfB',*(xyz+normal+[1,2,3,1.,len(refs)]))
  vertices.append(xyz+[1,2,3]+normal+[1.,len(refs)])
 for tri in tris:data+=struct.pack('<IIIB',*tri)
 for ob in obs:data+=struct.pack('<IBHHQ',*ob)
 data+=struct.pack('<6QB',agg['boundaryEdges'],1,agg['nonmanifoldEdges'],0,0,0,0)
 j={'schema':8,'representation':'observed_open_triangle_mesh','vertexCount':nv,'triangleCount':len(tris),'inputSamples':len(obs),'acceptedSamples':len(obs),'rejectedCells':0,'rejectedTriangles':0,'fusedObservations':0,'medianConfidence':1.,'boundaryEdges':agg['boundaryEdges'],'nonmanifoldEdges':agg['nonmanifoldEdges'],'intersections':0,'topologyIncomplete':False,'vertices':vertices,'triangles':[list(t[:3]) for t in tris]}
 return bytes(data),j,rows

def main():
 cases=[('one_boundary',[(0,1,2,0)],3,0,0,0),
 ('two_face_manifold',[(0,1,2,0),(1,0,3,0)],4,0,0,0),
 ('same_camera_nonmanifold',[(0,1,2,0),(1,0,3,0),(0,1,4,0)],6,1,1,0),
 ('mixed_camera_nonmanifold',[(0,1,2,0),(1,0,3,1),(0,1,4,2)],6,1,0,1)]
 passed=[]
 for name,tris,boundary,nonmanifold,same,mixed in cases:
  b,j,s=fixture(tris);r=reader.analyze(b,j,s,set());a=r['aggregates']
  check((a['boundaryEdges'],a['nonmanifoldEdges'],a['sameCameraNonmanifoldEdges'],a['mixedCameraNonmanifoldEdges'])==(boundary,nonmanifold,same,mixed));passed.append(name)
 b,j,s=fixture([(0,1,2,0)])
 for name,bb,jj,ss in [('trailing',b+b'x',j,s),('truncated',b[:-1],j,s),('wrong_order',b,{**j,'triangles':[[1,0,2]]},s),('wrong_topology',b,{**j,'boundaryEdges':4},s),('wrong_join',b,j,[[row[0],row[1],row[2]+1,*row[3:]] for row in s])]:
  try:reader.analyze(bb,jj,ss,set())
  except ValueError:passed.append(name)
  else:raise AssertionError('accepted malformed '+name)
 # Set API is explicit: duplicate-bearing lists cannot silently collapse.
 for name,excluded in [('exclusion_negative',{-1}),('exclusion_out_of_range',{1}),('exclusion_duplicate_list',[0,0]),('exclusion_bool',{True}),('exclusion_missing',None)]:
  try:reader.analyze(b,j,s,excluded)
  except ValueError:passed.append(name)
  else:raise AssertionError('accepted invalid exclusion '+name)
 tris=[(0,1,2,0),(1,0,3,0),(0,1,4,0)]
 a,edge_rows=reader.edge_incidence(tris,[{0} for _ in range(5)],{1})
 check(a['nonmanifoldEdges']==0 and a['boundaryEdges']==4)
 check(next(row for row in edge_rows if row[:2]==[0,1])[2]==[0,2])
 passed.append('explicit_exclusion_preserves_original_face_ids')
 print(json.dumps({'status':'PASS_STRUCTURAL_GUARD_FIXTURES_ONLY','tests':passed,'count':len(passed)}))
if __name__=='__main__':main()
