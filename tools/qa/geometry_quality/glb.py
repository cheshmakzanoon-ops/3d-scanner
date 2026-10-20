"""Independent strict decoder of current self-contained C10 GLB output subset."""
import json
import struct
from pathlib import Path
import numpy as np


def read(path: Path):
    body=path.read_bytes()
    if len(body)<28 or len(body)>256*1024*1024:raise ValueError('invalid GLB size')
    magic,version,length=struct.unpack_from('<III',body)
    if magic!=0x46546c67 or version!=2 or length!=len(body):raise ValueError('invalid GLB header')
    pos=12;chunks=[]
    while pos<len(body):
        if pos+8>len(body):raise ValueError('truncated GLB chunk')
        size,kind=struct.unpack_from('<II',body,pos);pos+=8
        if size%4 or pos+size>len(body):raise ValueError('invalid GLB chunk bounds')
        chunks.append((kind,body[pos:pos+size]));pos+=size
    if len(chunks)!=2 or [c[0] for c in chunks]!=[0x4e4f534a,0x004e4942]:raise ValueError('unsupported GLB chunk layout')
    data=json.loads(chunks[0][1]);binary=chunks[1][1]
    buffers=data.get('buffers',[])
    if len(buffers)!=1 or 'uri' in buffers[0] or not 0<=len(binary)-buffers[0]['byteLength']<=3:raise ValueError('invalid embedded buffer')
    if data.get('extensionsRequired') or data.get('skins') or data.get('animations'):raise ValueError('unsupported required extension/animation')
    if len(data.get('nodes',[]))!=1 or len(data.get('meshes',[]))!=1:raise ValueError('current subset requires one untransformed mesh/node')
    node=data['nodes'][0]
    if node.get('mesh')!=0 or any(k in node for k in ('matrix','translation','rotation','scale','children')):raise ValueError('unsupported node transform')
    if len(data['meshes'][0]['primitives'])!=1:raise ValueError('current subset requires one primitive')
    return data,binary


def accessor(data,binary,index):
    try:
        if type(index)!=int or index<0:raise ValueError('invalid accessor index')
        acc=data['accessors'][index]
        if acc.get('sparse') or acc.get('normalized'):raise ValueError('unsupported sparse/normalized accessor')
        count=acc['count'];width={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}[acc['type']]
        dtype=np.dtype({5121:'u1',5123:'<u2',5125:'<u4',5126:'<f4'}[acc['componentType']])
        view=data['bufferViews'][acc['bufferView']]
        if view.get('buffer',0)!=0:raise ValueError('external buffer')
        begin=view.get('byteOffset',0);size=view['byteLength'];offset=acc.get('byteOffset',0)
        stride=view.get('byteStride',width*dtype.itemsize)
        if type(count)!=int or not 0<count<=10000000 or min(begin,size,offset)<0 or stride<width*dtype.itemsize or stride%dtype.itemsize:raise ValueError('invalid accessor layout')
        if begin+size>data['buffers'][0]['byteLength'] or offset+(count-1)*stride+width*dtype.itemsize>size:raise ValueError('accessor exceeds buffer view')
        result=np.ndarray((count,width),dtype=dtype,buffer=binary,offset=begin+offset,strides=(stride,dtype.itemsize)).copy()
        if not np.isfinite(result).all():raise ValueError('nonfinite accessor')
        return result
    except (KeyError,IndexError,TypeError,OverflowError) as exc:raise ValueError('invalid GLB accessor') from exc


def geometry(path):
    data,binary=read(path);prim=data['meshes'][0]['primitives'][0]
    vertices=accessor(data,binary,prim['attributes']['POSITION'])
    if vertices.shape[1]!=3:raise ValueError('POSITION must be VEC3')
    if prim.get('mode',4)==0:return vertices,np.empty((0,3),int)
    if prim.get('mode',4)!=4:raise ValueError('only points and triangles supported')
    indices=accessor(data,binary,prim['indices'])
    if indices.shape[1]!=1 or not np.issubdtype(indices.dtype,np.integer) or indices.size%3:raise ValueError('invalid GLB indices')
    return vertices,indices.reshape(-1,3).astype(int)
