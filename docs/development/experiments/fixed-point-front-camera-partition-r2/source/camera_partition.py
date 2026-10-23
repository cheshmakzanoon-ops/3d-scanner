"""Immutable per-camera exhaustive validation partition, no spatial approximation."""
from exact_geometry import Refusal,F

def need(b,m):
 if not b:raise Refusal(m)
def checked_box(box,B):
 need(len(box)==4 and all(type(x) in (int,F) for x in box),'PARTITION_EXACT_BOUNDS_DOMAIN')
 box=tuple(B.number(x) for x in box)
 need(box[0]<=box[1] and box[2]<=box[3],'PARTITION_BOUNDS')
 return box

def build(triangles,bounds,identity,ncam,B):
 need(type(ncam) is int and 1<=ncam<=8,'PARTITION_CAMERA_DOMAIN');need(len(triangles)==len(bounds)==len(identity)<=40000,'PARTITION_SIZE_BINDING');out=[]
 for cam in range(ncam):B.clip();out.append([])
 original=set()
 for i,((cam,corners),box,row) in enumerate(zip(triangles,bounds,identity)):
  B.clip();need(type(cam) is int and 0<=cam<ncam,'PARTITION_CAMERA_DOMAIN');need(len(row)==7 and all(type(v) is int for v in row),'PARTITION_IDENTITY_SCHEMA');need(row[0]==i and row[1]>=0 and row[1] not in original and row[3]==cam and tuple(row[4:])==tuple(corners),'PARTITION_ORIGINAL_IDENTITY');need(row[2]>=0 and len(corners)==3 and all(type(v) is int and v>=0 for v in corners) and len(set(corners))==3,'PARTITION_TRIANGLE_DOMAIN');original.add(row[1]);box=checked_box(box,B);out[cam].append((i,row[1],box))
 return tuple(tuple(rows) for rows in out)

def expected(partitions,cam,box,ncam,B):
 B.clip();need(type(cam) is int and 0<=cam<ncam and len(partitions)==ncam,'PARTITION_QUERY_CAMERA');box=checked_box(box,B)
 result=[]
 for tid,original,b in partitions[cam]:
  B.clip()
  if b[0]<=box[1] and b[1]>=box[0] and b[2]<=box[3] and b[3]>=box[2]:result.append(tid)
 return result
