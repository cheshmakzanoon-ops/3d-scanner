#!/usr/bin/env python3
"""Deterministic labelled synthetic grids; no captured-object or scan-quality evidence."""
import argparse,hashlib,json,struct,zlib
from pathlib import Path

def png(size):
 def chunk(name,data):return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data))
 rows=[]
 colors=[(255,0,0,255),(0,255,0,255),(0,0,255,255),(255,255,0,255)]
 for y in range(size):rows.append(b'\0'+b''.join(bytes(colors[(y>=size//2)*2+(x>=size//2)]) for x in range(size)))
 return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>2I5B',size,size,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(rows),9))+chunk(b'IEND',b'')
def glb(grid,atlas):
 count=(grid+1)**2;positions=[];normals=[];uv=[];indices=[]
 for y in range(grid+1):
  for x in range(grid+1):positions += [2*x/grid-1,1-2*y/grid,0];normals += [0,0,1];uv += [x/grid,y/grid]
 for y in range(grid):
  for x in range(grid):
   a=y*(grid+1)+x;b=a+1;c=a+grid+1;d=c+1;indices += [a,c,b,b,c,d]
 blocks=[struct.pack('<'+'f'*len(v),*v) for v in (positions,normals,uv)]+[struct.pack('<'+'I'*len(indices),*indices),png(atlas)]
 views=[];binary=b''
 for block in blocks:
  views.append(dict(buffer=0,byteOffset=len(binary),byteLength=len(block)));binary+=block;binary+=b'\0'*((-len(binary))%4)
 doc=dict(asset={'version':'2.0','generator':'Agent097 labelled synthetic grid v1'},scene=0,scenes=[{'nodes':[0]}],nodes=[{'mesh':0}],
  meshes=[{'primitives':[{'attributes':{'POSITION':0,'NORMAL':1,'TEXCOORD_0':2},'indices':3,'material':0,'mode':4}]}],
  buffers=[{'byteLength':len(binary)}],bufferViews=views,
  accessors=[dict(bufferView=i,componentType=5126,count=count,type=t) for i,t in enumerate(['VEC3','VEC3','VEC2'])]+[dict(bufferView=3,componentType=5125,count=len(indices),type='SCALAR')],
  materials=[{'doubleSided':True,'pbrMetallicRoughness':{'baseColorTexture':{'index':0},'metallicFactor':0,'roughnessFactor':1}}],
  textures=[{'source':0}],images=[{'bufferView':4,'mimeType':'image/png'}])
 text=json.dumps(doc,sort_keys=True,separators=(',',':')).encode();text+=b' '*((-len(text))%4)
 return struct.pack('<5I',0x46546c67,2,28+len(text)+len(binary),len(text),0x4e4f534a)+text+struct.pack('<2I',len(binary),0x004e4942)+binary

def generate(output):
 output.mkdir(parents=True,exist_ok=True);records={}
 for name,grid,atlas in [('small',16,64),('large',128,1024)]:
  data=glb(grid,atlas);(output/f'a097-{name}.glb').write_bytes(data)
  records[name]=dict(sha256=hashlib.sha256(data).hexdigest(),bytes=len(data),vertices=(grid+1)**2,triangles=grid*grid*2,atlasEdge=atlas,synthetic=True)
 result=dict(schemaVersion=1,fixtures=records);(output/'a097-fixtures.json').write_text(json.dumps(result,indent=2)+'\n');return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=Path('macrobenchmark/target/assets'));generate(p.parse_args().output)
