"""Independent bounded readers for the three current HOST export encodings.

Structural/content validity only; these checks do not establish surface quality.
No file I/O, external readers, image allocations, or candidate-code execution.
"""
import hashlib
import io
import json
import math
import struct
import zlib

MAX_BYTES = 16 * 1024 * 1024


def need(ok, reason):
    if not ok:
        raise ValueError('Export format: ' + reason)


def integer(v, maximum, minimum=0):
    need(type(v) is int and minimum <= v <= maximum, 'integer bound')
    return v


def ply(raw, surface):
    stream = io.BytesIO(raw)
    def line():
        v = stream.readline(1026)
        need(v.endswith(b'\n') and len(v) <= 1025, 'PLY line bound')
        v = v[:-1].removesuffix(b'\r')
        need(all(32 <= c <= 126 for c in v), 'PLY character')
        return v.decode('ascii')
    need(line() == 'ply' and line() == 'format ascii 1.0', 'PLY encoding')
    comment = line()
    need(comment.startswith('comment RealityForge '), 'PLY provenance')
    if not surface and comment == 'comment RealityForge sparse_points; arbitrary scale; x-right y-down z-forward':
        need(line() == 'comment representation sparse_points', 'PLY representation')
    else:
        need(('observed open surface' if surface else 'sparse reconstruction') in comment, 'PLY representation')
    def count(name):
        v = line().split(' ')
        need(len(v) == 3 and v[:2] == ['element', name] and v[2].isascii() and v[2].isdigit(), 'PLY element')
        return integer(int(v[2]), min(2_000_000 if name == 'vertex' else 4_000_000, len(raw)//6), 1)
    vertices = count('vertex')
    floats = ['x','y','z'] + (['nx','ny','nz'] if surface else [])
    for p in floats:
        need(line() == 'property float ' + p, 'PLY float property')
    for p in ['red','green','blue']:
        need(line() == 'property uchar ' + p, 'PLY color property')
    faces = count('face') if surface else 0
    if surface:
        need(line() == 'property list uchar int vertex_indices', 'PLY face property')
    need(line() == 'end_header', 'PLY header')
    for _ in range(vertices):
        values = line().split()
        need(len(values) == len(floats)+3, 'PLY vertex fields')
        for value in values[:len(floats)]:
            f = float(value)
            need(math.isfinite(f) and abs(f) <= 3.4028234663852886e38, 'PLY nonfinite float32')
        for value in values[len(floats):]:
            need(value.isdigit() and 0 <= int(value) <= 255, 'PLY color range')
    for _ in range(faces):
        values = line().split()
        need(len(values) == 4 and values[0] == '3' and all(v.isdigit() for v in values[1:]), 'PLY triangle')
        indices = [int(v) for v in values[1:]]
        need(len(set(indices)) == 3 and all(i < vertices for i in indices), 'PLY index bounds')
    need(not stream.read(1), 'PLY trailing data')
    return {'vertexCount': vertices, 'triangleCount': faces} if surface else {'pointCount':vertices}


def png(raw):
    need(raw[:8] == b'\x89PNG\r\n\x1a\n', 'PNG signature')
    at=8; chunks=0; ranges=[]; ended=False; after=False; width=height=channels=0
    while at < len(raw):
        chunks += 1
        need(chunks <= 10000 and at+12 <= len(raw), 'PNG chunk bound')
        n, = struct.unpack_from('>I',raw,at); kind=raw[at+4:at+8]
        need(n <= len(raw)-at-12 and all(65<=c<=90 or 97<=c<=122 for c in kind) and not(kind[2]&32), 'PNG chunk type/size')
        body=memoryview(raw)[at+8:at+8+n]
        crc,=struct.unpack_from('>I',raw,at+8+n)
        need(zlib.crc32(body,zlib.crc32(kind)) & 0xffffffff == crc, 'PNG CRC')
        if chunks == 1:
            need(kind == b'IHDR' and n == 13, 'PNG IHDR')
            width,height,depth,color,compression,filtering,interlace=struct.unpack('>IIBBBBB',body)
            need(1<=width<=4096 and 1<=height<=4096 and width*height<=16_777_216 and depth==8 and color in (2,6) and (compression,filtering,interlace)==(0,0,0), 'PNG image domain')
            channels=3 if color==2 else 4
        elif kind == b'IDAT':
            need(n>0 and not after, 'PNG IDAT order'); ranges.append((at+8,n))
        elif kind == b'IEND':
            need(n==0 and ranges and at+12==len(raw), 'PNG end'); ended=True
        else:
            need(kind != b'IHDR' and kind[0]&32, 'PNG unsupported critical chunk')
            if ranges: after=True
        at += n+12
    need(ended, 'PNG missing end')
    decoder=zlib.decompressobj(); total=0; row=1+width*channels; expected=row*height
    for start,n in ranges:
        pending=memoryview(raw)[start:start+n]
        while pending:
            out=decoder.decompress(pending,65536)
            pending=decoder.unconsumed_tail
            need(total+len(out)<=expected and not decoder.unused_data, 'PNG inflated bound/trailing stream')
            for i in range((-total)%row,len(out),row):
                need(out[i]<=4, 'PNG scanline filter')
            total+=len(out)
    need(decoder.eof and total==expected and not decoder.unused_data, 'PNG incomplete scanlines')
    return width,height


def object_pairs(pairs):
    result={}
    for k,v in pairs:
        need(k not in result, 'duplicate JSON key'); result[k]=v
    return result


def glb(raw):
    need(len(raw)>=28 and struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw)), 'GLB header')
    at=12; chunks=[]
    while at<len(raw):
        need(len(chunks)<2 and at+8<=len(raw), 'GLB chunks')
        n,kind=struct.unpack_from('<II',raw,at); at+=8
        need(n%4==0 and n<=len(raw)-at, 'GLB chunk bounds')
        chunks.append((kind,memoryview(raw)[at:at+n]));at+=n
    need([x[0] for x in chunks]==[0x4e4f534a,0x004e4942], 'GLB chunk order')
    need(len(chunks[0][1])<=65536,'GLB JSON bound')
    text=bytes(chunks[0][1]).decode('utf8'); depth=0; quoted=False; escape=False
    for c in text:
        if quoted:
            if escape: escape=False
            elif c=='\\': escape=True
            elif c=='"': quoted=False
        elif c=='"': quoted=True
        elif c in '[{': depth+=1; need(depth<=32,'JSON depth')
        elif c in ']}': depth-=1; need(depth>=0,'JSON depth')
    need(depth==0 and not quoted,'JSON termination')
    doc=json.loads(text,object_pairs_hook=object_pairs,parse_constant=lambda _:need(False,'nonfinite JSON'))
    def numbers(value):
        if isinstance(value,dict):
            for v in value.values():numbers(v)
        elif isinstance(value,list):
            for v in value:numbers(v)
        elif isinstance(value,float):need(math.isfinite(value),'nonfinite JSON number')
    numbers(doc)
    binary=chunks[1][1]
    need(doc['asset']['version']=='2.0' and not any(k in doc for k in ('extensionsRequired','skins','animations')), 'GLB asset/features')
    need(doc['scene']==0 and doc['scenes']==[{'nodes':[0]}] and len(doc['nodes'])==1 and doc['nodes'][0]['mesh']==0 and set(doc['nodes'][0])<= {'mesh','name'}, 'GLB scene')
    need(len(doc['buffers'])==1 and set(doc['buffers'][0])=={'byteLength'},'GLB buffer')
    size=integer(doc['buffers'][0]['byteLength'],len(binary),1)
    need(0<=len(binary)-size<=3 and not any(binary[size:]),'GLB padding')
    views=doc['bufferViews']; accessors=doc['accessors']
    need(len(views)==5 and len(accessors)==4, 'GLB current layout')
    intervals=[]
    for v in views:
        need(set(v)<= {'buffer','byteOffset','byteLength','target'} and v['buffer']==0,'GLB view features')
        start=integer(v.get('byteOffset',0),size); n=integer(v['byteLength'],size,1)
        need(start%4==0 and start+n<=size,'GLB view bounds');intervals.append((start,start+n))
    need(all(intervals[i][1]<=intervals[i+1][0] for i in range(4)), 'GLB overlapping views')
    counts=[]
    for i,(width,typ,component) in enumerate(((3,'VEC3',5126),(3,'VEC3',5126),(2,'VEC2',5126),(1,'SCALAR',5125))):
        a=accessors[i]
        need(set(a)<= {'bufferView','componentType','count','type','min','max'} and a['bufferView']==i and a['type']==typ and a['componentType']==component,'GLB accessor layout')
        count=integer(a['count'],len(binary)//(width*4),1);counts.append(count)
        start,end=intervals[i];need(end-start==count*width*4,'GLB accessor span')
        if i<3:
            lo=[math.inf]*width;hi=[-math.inf]*width
            for values in struct.iter_unpack('<'+'f'*width,binary[start:end]):
                need(all(math.isfinite(x) for x in values),'GLB nonfinite geometry')
                for axis,x in enumerate(values):lo[axis]=min(lo[axis],x);hi[axis]=max(hi[axis],x)
                if i==2:need(all(0<=x<=1 for x in values),'GLB UV bounds')
            for key,actual in (('min',lo),('max',hi)):
                if key in a:
                    stated=a[key]
                    need(isinstance(stated,list) and len(stated)==width and all(type(x) in (int,float) and math.isfinite(x) for x in stated),'GLB accessor extrema')
                    need(all(math.isclose(x,y,rel_tol=1e-6,abs_tol=1e-7) for x,y in zip(stated,actual)),'GLB accessor extrema mismatch')
    need(counts[0]==counts[1]==counts[2] and counts[3]%3==0,'GLB geometry counts')
    start,end=intervals[3]
    for tri in struct.iter_unpack('<III',binary[start:end]):
        need(len(set(tri))==3 and max(tri)<counts[0],'GLB triangle indices')
    need(len(doc['meshes'])==1 and len(doc['meshes'][0]['primitives'])==1,'GLB mesh')
    prim=doc['meshes'][0]['primitives'][0]
    need(prim=={'attributes':{'POSITION':0,'NORMAL':1,'TEXCOORD_0':2},'indices':3,'mode':4,'material':0},'GLB primitive')
    need(len(doc['materials'])==1,'GLB material')
    mat=doc['materials'][0];pbr=mat['pbrMetallicRoughness']
    need(mat.get('doubleSided') is True and mat.get('alphaMode','OPAQUE') in ('MASK','OPAQUE') and set(mat)<= {'doubleSided','alphaMode','alphaCutoff','pbrMetallicRoughness'},'GLB material features')
    cutoff=mat.get('alphaCutoff',.5);need(type(cutoff) in (int,float) and math.isfinite(cutoff) and 0<=cutoff<=1,'GLB alpha')
    need(pbr=={'baseColorTexture':{'index':0},'metallicFactor':0,'roughnessFactor':1},'GLB texture material')
    need(doc['textures']==[{'sampler':0,'source':0}] and doc['samplers']==[{'magFilter':9729,'minFilter':9729,'wrapS':33071,'wrapT':33071}] and doc['images']==[{'bufferView':4,'mimeType':'image/png'}],'GLB embedded texture')
    start,end=intervals[4]; image=bytes(binary[start:end]);w,h=png(image)
    return {'vertexCount':counts[0],'triangleCount':counts[3]//3,'textureSha256':hashlib.sha256(image).hexdigest(),'textureWidth':w,'textureHeight':h}


def validate_export(kind, raw):
    need(type(raw) is bytes and 0<len(raw)<=MAX_BYTES,'payload bound')
    try:
        if kind=='sparse-ply':return ply(raw,False)
        if kind=='mesh-ply':return ply(raw,True)
        if kind=='textured-glb':return glb(raw)
        raise ValueError('Export format: unknown kind')
    except (KeyError,IndexError,TypeError,OverflowError,UnicodeError,struct.error,zlib.error) as exc:
        raise ValueError('Export format: malformed structure') from exc


def validate_exports(sparse_ply,mesh_ply,textured_glb):
    s=validate_export('sparse-ply',sparse_ply);m=validate_export('mesh-ply',mesh_ply);t=validate_export('textured-glb',textured_glb)
    return {'sparsePointCount':s['pointCount'],'meshVertexCount':m['vertexCount'],'meshTriangleCount':m['triangleCount'],'texturedVertexCount':t['vertexCount'],'texturedTriangleCount':t['triangleCount'],**{k:t[k] for k in ('textureSha256','textureWidth','textureHeight')}}
