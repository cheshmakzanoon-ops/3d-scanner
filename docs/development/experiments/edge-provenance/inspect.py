"""Preparation-only bounded structural edge incidence reader; no geometry edits.
Public API analyze(bits: bytes, mesh_json: dict, sampleRows: list, excluded_faces: set[int]) -> dict.
Caller must admit receipt/source hashes before passing inputs; bind_inputs provides
receipt output bindings and immutable anchored reads, not receipt trust policy.
"""
import collections
import hashlib
import json
import math
import struct
from secure import secure_bytes, need
MAX_BYTES=12*1024*1024
SAMPLE_KEY='sampleRows_sourceId_camera_x_y_normalValidity_normalSupported_visits_passed_rejectMask_outcome'

def strict_json(data):
 need(len(data)<=MAX_BYTES,'JSON bound')
 def pairs(items):
  d={}
  for k,v in items:
   need(k not in d,'duplicate JSON key');d[k]=v
  return d
 return json.loads(data,object_pairs_hook=pairs,parse_constant=lambda s: (_ for _ in ()).throw(ValueError('nonfinite JSON')))

def bind_inputs(receipt_path, expected_receipt_sha, bits_path, json_path, bits_key, json_key):
 """Caller supplies exact admitted receipt digest and relative receipt output keys.
 Keys must match receipt-relative filenames, not arbitrary alias matches.
 """
 from pathlib import Path
 receipt_data=secure_bytes(receipt_path,MAX_BYTES)
 need(hashlib.sha256(receipt_data).hexdigest()==expected_receipt_sha,'receipt SHA')
 receipt=strict_json(receipt_data);base=Path(receipt_path).parent
 result=[]
 for path,key in ((bits_path,bits_key),(json_path,json_key)):
  need(str(base/key)==str(path) and not Path(key).is_absolute() and '..' not in Path(key).parts,'receipt filename binding')
  data=secure_bytes(path,MAX_BYTES)
  need(hashlib.sha256(data).hexdigest()==receipt['outputSha256'][key],'receipt artifact SHA')
  result.append(data)
 need(secure_bytes(receipt_path,MAX_BYTES)==receipt_data,'receipt changed')
 return result[0],strict_json(result[1])

class Reader:
 def __init__(self,data):
  need(isinstance(data,bytes) and len(data)<=MAX_BYTES,'mesh byte bound');self.data=data;self.at=0
 def take(self,fmt):
  fmt='<'+fmt;n=struct.calcsize(fmt);need(self.at+n<=len(self.data),'truncated mesh')
  value=struct.unpack_from(fmt,self.data,self.at);self.at+=n;return value

def histogram(counter):
 return [{'key':json.loads(k),'count':v} for k,v in sorted(counter.items())]
def key(value):return json.dumps(value,separators=(',',':'))
def edge_incidence(triangles,vertex_cameras,excluded_faces):
 """Pure indexed incidence; caller validates source camera/indices beforehand."""
 edges=collections.defaultdict(list);face_cameras=collections.Counter()
 for fid,t in enumerate(triangles):
  a,b,c,cam=t;face_cameras[cam]+=1
  if fid in excluded_faces:continue
  for u,v in ((a,b),(b,c),(c,a)):edges[tuple(sorted((u,v)))].append(fid)
 uses=collections.Counter();multisets=collections.Counter();endpoints=collections.Counter();rows=[]
 boundary=nonmanifold=same=mixed=0
 for (a,b),faces in sorted(edges.items()):
  n=len(faces);uses[n]+=1;cams=sorted(triangles[f][3] for f in faces)
  kind='boundary' if n==1 else 'nonmanifold' if n>2 else 'two_incident'
  boundary+=n==1;nonmanifold+=n>2
  if n>2:
   same+=len(set(cams))==1;mixed+=len(set(cams))>1
   multisets[key(cams)]+=1;endpoints[key(sorted([sorted(vertex_cameras[a]),sorted(vertex_cameras[b])]))]+=1
  rows.append([a,b,faces,cams,kind,sorted(vertex_cameras[a]),sorted(vertex_cameras[b])])
 return {'edgeCount':len(edges),'boundaryEdges':boundary,'nonmanifoldEdges':nonmanifold,
  'sameCameraNonmanifoldEdges':same,'mixedCameraNonmanifoldEdges':mixed,
  'edgeIncidenceHistogram':dict(sorted(uses.items())),
  'nonmanifoldSourceCameraMultisetHistogram':histogram(multisets),
  'nonmanifoldEndpointObservationCameraSetHistogram':histogram(endpoints),
  'faceCountPerNativeCamera':dict(sorted(face_cameras.items()))},rows

def analyze(bits,mesh_json,sampleRows,excluded_faces):
 r=Reader(bits);need(r.take('8s')[0]==b'RFMESH01','mesh magic')
 nv,nt,no=r.take('III');need(nv<=40000 and nt<=80000 and no<=6867,'mesh counts')
 need(isinstance(excluded_faces,(set,frozenset)) and all(type(i) is int and 0<=i<nt for i in excluded_faces),'explicit native degenerate face exclusion set')
 names=['inputSamples','acceptedSamples','rejectedCells','rejectedTriangles','fusedObservations']
 summary=dict(zip(names,r.take('5Q')));median=r.take('f')[0]
 need(math.isfinite(median) and 0<=median<=1,'median confidence')
 need(summary['inputSamples']<=6867 and summary['acceptedSamples']<=summary['inputSamples'] and summary['fusedObservations']<=summary['acceptedSamples'],'sample summary bounds')
 vertices=[]
 for _ in range(nv):
  v=r.take('6d3BfB');need(all(math.isfinite(x) for x in v[:6]) and math.isfinite(v[9]) and 0<=v[9]<=1 and v[10]>0,'vertex finite/support');vertices.append(v)
 triangles=[]
 for _ in range(nt):
  t=r.take('IIIB');need(all(i<nv for i in t[:3]) ,'triangle indices');triangles.append(t)
 for fid,t in enumerate(triangles):need(len(set(t[:3]))==3 or fid in excluded_faces,'repeated-index face must be natively excluded')
 need(isinstance(sampleRows,list) and len(sampleRows)<=6867,'trace sample count')
 samples={};cameras=set()
 for row in sampleRows:
  need(isinstance(row,list) and len(row)==10 and all(type(x) is int for x in row),'trace row schema')
  sid,cam,x,y=row[:4];need(sid>=0 and 0<=cam<256 and 0<=x<=65535 and 0<=y<=65535 and sid not in samples,'trace sample identity')
  samples[sid]=(cam,x,y);cameras.add(cam)
 need(len(samples)==summary['inputSamples'],'trace input sample equality')
 vertex_cameras=[set() for _ in range(nv)];sample_seen=set();pixel_seen=set()
 for _ in range(no):
  vertex,cam,x,y,sid=r.take('IBHHQ')
  need(vertex<nv and sid in samples and samples[sid]==(cam,x,y),'observation trace join')
  need(sid not in sample_seen and (cam,x,y) not in pixel_seen and cam not in vertex_cameras[vertex],'unique observations')
  sample_seen.add(sid);pixel_seen.add((cam,x,y));vertex_cameras[vertex].add(cam)
 for i,v in enumerate(vertices):need(len(vertex_cameras[i])==v[10],'supportingViews equality')
 for a,b,c,cam in triangles:need(cam in cameras and all(cam in vertex_cameras[i] for i in (a,b,c)),'triangle source camera evidence')
 topo_names=['boundaryEdges','components','nonmanifoldEdges','intersections','ambiguousPairs','removedExactDefects']
 topology=dict(zip(topo_names,r.take('6Q')));budget=r.take('B')[0]
 need(budget in (0,1) and r.at==len(bits),'mesh trailing bytes/budget')
 need(mesh_json['schema']==8 and mesh_json['representation']=='observed_open_triangle_mesh','mesh JSON schema')
 need(type(mesh_json['topologyIncomplete']) is bool and mesh_json['topologyIncomplete']==bool(budget),'topology budget equality')
 for k,v in {**summary,'vertexCount':nv,'triangleCount':nt,**{k:topology[k] for k in ['boundaryEdges','nonmanifoldEdges','intersections']}}.items():
  need(type(mesh_json[k]) is int and mesh_json[k]==v,'JSON scalar '+k)
 need(math.isfinite(mesh_json['medianConfidence']) and struct.pack('<f',mesh_json['medianConfidence'])==struct.pack('<f',median),'JSON median serialization')
 need(mesh_json['triangles']==[list(t[:3]) for t in triangles],'JSON exact ordered triangle indices')
 need(len(mesh_json['vertices'])==nv,'JSON vertex count')
 for v,j in zip(vertices,mesh_json['vertices']):
  need(len(j)==11 and all(isinstance(x,(int,float)) and not isinstance(x,bool) and math.isfinite(x) for x in j),'JSON finite vertex')
  need(j[3:6]==list(v[6:9]) and j[10]==v[10],'JSON discrete vertex fields')
 aggregate,rows=edge_incidence(triangles,vertex_cameras,excluded_faces)
 for name in ('boundaryEdges','nonmanifoldEdges'):need(aggregate[name]==topology[name],'indexed/native topology reconciliation '+name)
 aggregate.update({'vertexCount':nv,'triangleCount':nt,'observationCount':no,'excludedNativeDegenerateFaces':len(excluded_faces),'includedIncidentFaces':nt-len(excluded_faces),'nativeCameraRefs':sorted(cameras)})
 result={'schema':1,'scope':'Indexed structural incidence with explicit exact-native degenerate face exclusions supplied by caller; no Python geometric predicate or crossing replay. Native boundary/nonmanifold totals reconciled exactly. Camera refs are not photo IDs.',
  'meshBitsSha256':hashlib.sha256(bits).hexdigest(),'aggregates':aggregate,
  'edgeRowColumns':['vertexA','vertexB','incidentFaceIds','sortedSourceCameraMultiset','incidenceClass','vertexACameraRefs','vertexBCameraRefs'],'edgeRows':rows}
 need(len(json.dumps(result,separators=(',',':'),allow_nan=False).encode())<=MAX_BYTES,'edge result bound')
 return result
