"""Independent format semantics; small synthetic current-writer layouts, not quality QA."""
import copy
import hashlib
import json
import struct
import sys
import zlib
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from tools.eight_view_host import complete_formats as f


def ply(surface=False):
    lines=['ply','format ascii 1.0','comment RealityForge '+('observed open surface' if surface else 'sparse reconstruction'),'element vertex 3']
    lines += ['property float '+p for p in (['x','y','z','nx','ny','nz'] if surface else ['x','y','z'])]
    lines += ['property uchar '+p for p in ['red','green','blue']]
    if surface:lines += ['element face 1','property list uchar int vertex_indices']
    lines += ['end_header']
    for p in ('0 0 0','1 0 0','0 1 0'):lines += [p+(' 0 0 1' if surface else '')+' 255 128 0']
    if surface:lines+=['3 0 1 2']
    return ('\n'.join(lines)+'\n').encode()


def chunk(kind,body):return struct.pack('>I',len(body))+kind+body+struct.pack('>I',zlib.crc32(body,zlib.crc32(kind))&0xffffffff)


def png(scanline=b'\x00\xff\x80\x00\xff',width=1,height=1):
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(scanline))+chunk(b'IEND',b'')


def fixture(image=None):
    image=png() if image is None else image
    arrays=[struct.pack('<9f',0,0,0,1,0,0,0,1,0),struct.pack('<9f',*(0,0,1)*3),struct.pack('<6f',0,0,1,0,0,1),struct.pack('<3I',0,1,2),image]
    views=[];binary=b''
    for a in arrays:
        views.append({'buffer':0,'byteOffset':len(binary),'byteLength':len(a)})
        binary+=a
    binary+=b'\0'*((-len(binary))%4)
    doc={'asset':{'version':'2.0','generator':'synthetic M10 layout'},'scene':0,'scenes':[{'nodes':[0]}],'nodes':[{'mesh':0}],
        'meshes':[{'primitives':[{'attributes':{'POSITION':0,'NORMAL':1,'TEXCOORD_0':2},'indices':3,'mode':4,'material':0}]}],
        'materials':[{'doubleSided':True,'alphaMode':'MASK','alphaCutoff':.5,'pbrMetallicRoughness':{'baseColorTexture':{'index':0},'metallicFactor':0,'roughnessFactor':1}}],
        'textures':[{'sampler':0,'source':0}],'samplers':[{'magFilter':9729,'minFilter':9729,'wrapS':33071,'wrapT':33071}],
        'images':[{'bufferView':4,'mimeType':'image/png'}],'buffers':[{'byteLength':len(binary)}],'bufferViews':views,
        'accessors':[{'bufferView':i,'componentType':5125 if i==3 else 5126,'count':3,'type':('SCALAR' if i==3 else 'VEC2' if i==2 else 'VEC3')} for i in range(4)]}
    return doc,binary


def glb(doc,binary):
    text=json.dumps(doc,separators=(',',':')).encode();text+=b' '*((-len(text))%4)
    return struct.pack('<4sII',b'glTF',2,28+len(text)+len(binary))+struct.pack('<II',len(text),0x4e4f534a)+text+struct.pack('<II',len(binary),0x004e4942)+binary


def test_three_current_layouts():
    doc,binary=fixture();result=f.validate_exports(ply(),ply(True),glb(doc,binary))
    assert result=={'sparsePointCount':3,'meshVertexCount':3,'meshTriangleCount':1,'texturedVertexCount':3,'texturedTriangleCount':1,'textureSha256':hashlib.sha256(png()).hexdigest(),'textureWidth':1,'textureHeight':1}


@pytest.mark.parametrize('mode',['truncated','trailing','huge-count','nan','float32-overflow','color','index','repeated-index','wrong-property','binary','wrong-role'],ids=str)
def test_ply_refusals(mode):
    raw=ply(True)
    if mode=='truncated':raw=raw[:-1]
    elif mode=='trailing':raw+=b'junk\n'
    elif mode=='huge-count':raw=raw.replace(b'element vertex 3',b'element vertex 99999999999999')
    elif mode=='nan':raw=raw.replace(b'0 0 0 0 0 1',b'nan 0 0 0 0 1')
    elif mode=='float32-overflow':raw=raw.replace(b'0 0 0 0 0 1',b'1e100 0 0 0 0 1')
    elif mode=='color':raw=raw.replace(b'255 128 0',b'256 128 0')
    elif mode=='index':raw=raw.replace(b'3 0 1 2',b'3 0 1 3')
    elif mode=='repeated-index':raw=raw.replace(b'3 0 1 2',b'3 0 1 1')
    elif mode=='wrong-property':raw=raw.replace(b'property float nx',b'property float other')
    elif mode=='binary':raw=raw.replace(b'format ascii 1.0',b'format binary_little_endian 1.0')
    else:raw=ply()
    with pytest.raises(ValueError):f.validate_export('mesh-ply',raw)


@pytest.mark.parametrize('mode',['crc','signature','short-stream','extra-stream','filter','oversize-image','nonfinite','indices','uv','count','negative','bool-count','overlap','out-of-buffer','stride','sparse','external','wrong-material','node-transform','scene','accessor-type','json-depth','duplicate-json','json-size','trailing','wrong-total'],ids=str)
def test_glb_refusals(mode):
    doc,binary=fixture()
    if mode in ('crc','signature','short-stream','extra-stream','filter','oversize-image'):
        image=png()
        if mode=='crc':image=image[:-1]+bytes([image[-1]^1])
        elif mode=='signature':image=b'x'+image[1:]
        elif mode=='short-stream':image=png(b'\0\xff')
        elif mode=='extra-stream':image=png(b'\0\xff\0\0\xffextra')
        elif mode=='filter':image=png(b'\x05\xff\0\0\xff')
        else:image=png(width=4097)
        doc,binary=fixture(image)
    elif mode=='nonfinite':binary=struct.pack('<f',float('inf'))+binary[4:]
    elif mode=='indices':at=doc['bufferViews'][3]['byteOffset'];binary=binary[:at]+struct.pack('<3I',0,1,3)+binary[at+12:]
    elif mode=='uv':at=doc['bufferViews'][2]['byteOffset'];binary=binary[:at]+struct.pack('<f',1.5)+binary[at+4:]
    elif mode=='count':doc['accessors'][0]['count']=10**12
    elif mode=='negative':doc['accessors'][0]['count']=-1
    elif mode=='bool-count':doc['accessors'][0]['count']=True
    elif mode=='overlap':doc['bufferViews'][1]['byteOffset']=0
    elif mode=='out-of-buffer':doc['bufferViews'][4]['byteLength']=len(binary)
    elif mode=='stride':doc['bufferViews'][0]['byteStride']=20
    elif mode=='sparse':doc['accessors'][0]['sparse']={}
    elif mode=='external':doc['images'][0]['uri']='file:///etc/passwd'
    elif mode=='wrong-material':doc['materials'][0]['pbrMetallicRoughness']['baseColorTexture']['index']=1
    elif mode=='node-transform':doc['nodes'][0]['scale']=[1,1,1]
    elif mode=='scene':doc['scenes'][0]['nodes']=[1]
    elif mode=='accessor-type':doc['accessors'][0]['componentType']=5125
    elif mode=='json-depth':
        doc['extras']={};v=doc['extras']
        for _ in range(40):
            v['nested']={};v=v['nested']
    elif mode=='json-size':doc['extras']={'text':'a'*65536}
    raw=glb(doc,binary)
    if mode=='duplicate-json':
        text=json.dumps(doc).replace('"scene": 0','"scene": 0, "scene": 0').encode();text+=b' '*((-len(text))%4)
        raw=struct.pack('<4sII',b'glTF',2,28+len(text)+len(binary))+struct.pack('<II',len(text),0x4e4f534a)+text+struct.pack('<II',len(binary),0x004e4942)+binary
    if mode=='trailing':raw+=b'xxxx'
    if mode=='wrong-total':raw=raw[:8]+struct.pack('<I',0)+raw[12:]
    with pytest.raises(ValueError):f.validate_export('textured-glb',raw)


def test_payload_bounds_and_unknown():
    for raw in (b'',b'x'*(f.MAX_BYTES+1),bytearray(b'x')):
        with pytest.raises(ValueError):f.validate_export('sparse-ply',raw)
    with pytest.raises(ValueError):f.validate_export('other',b'x')


def test_png_multichunk_and_large_stream_is_incremental():
    # 4MiB decoded bytes; parser only holds <=64KiB decompressed slices.
    width,height=1024,1024;rows=(b'\0'+b'\xff\0\x80\xff'*width)*height
    packed=zlib.compress(rows);middle=len(packed)//2
    image=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,6,0,0,0))+chunk(b'IDAT',packed[:middle])+chunk(b'IDAT',packed[middle:])+chunk(b'IEND',b'')
    assert f.png(image)==(width,height)


def test_existing_sparse_writer_fixture():
    # Existing repository artifact, not generated by this independent reader.
    raw=(ROOT/'benchmarks/output/sparse.ply').read_bytes()
    assert f.validate_export('sparse-ply',raw)['pointCount']>0

@pytest.mark.parametrize('mode',['json-overflow','extrema-nonfinite','extrema-wrong','empty-index','repeat-index','truncated-chunk','too-many-chunks','image-mime'],ids=str)
def test_additional_glb_semantics(mode):
    doc,binary=fixture()
    if mode=='json-overflow':doc['extras']={'value':float('inf')}
    elif mode=='extrema-nonfinite':doc['accessors'][0]['min']=[0,float('nan'),0]
    elif mode=='extrema-wrong':doc['accessors'][0]['max']=[100,100,100]
    elif mode=='empty-index':doc['accessors'][3]['count']=0
    elif mode=='repeat-index':
        at=doc['bufferViews'][3]['byteOffset'];binary=binary[:at]+struct.pack('<3I',0,1,1)+binary[at+12:]
    elif mode=='image-mime':doc['images'][0]['mimeType']='image/jpeg'
    raw=glb(doc,binary)
    if mode=='truncated-chunk':raw=raw[:-4];raw=raw[:8]+struct.pack('<I',len(raw))+raw[12:]
    if mode=='too-many-chunks':raw+=struct.pack('<II',0,0x004e4942);raw=raw[:8]+struct.pack('<I',len(raw))+raw[12:]
    with pytest.raises(ValueError):f.validate_export('textured-glb',raw)

@pytest.mark.parametrize('mode',['trailing-zlib','truncated-zlib','critical','idat-order','inflate-bomb','bad-row-length','duplicate-ihdr'],ids=str)
def test_png_stream_and_chunk_refusals(mode):
    ihdr=chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,6,0,0,0))
    compressed=zlib.compress(b'\0\xff\0\0\xff')
    middle=b''
    if mode=='trailing-zlib':compressed+=zlib.compress(b'second')
    elif mode=='truncated-zlib':compressed=compressed[:-2]
    elif mode=='inflate-bomb':compressed=zlib.compress(b'\0'*1_000_000)
    elif mode=='bad-row-length':compressed=zlib.compress(b'\0'*6)
    elif mode=='critical':middle=chunk(b'ABCD',b'')
    elif mode=='duplicate-ihdr':middle=ihdr
    data=chunk(b'IDAT',compressed)
    if mode=='idat-order':data=chunk(b'IDAT',compressed[:2])+chunk(b'tEXt',b'comment')+chunk(b'IDAT',compressed[2:])
    image=b'\x89PNG\r\n\x1a\n'+ihdr+middle+data+chunk(b'IEND',b'')
    with pytest.raises(ValueError):f.png(image)


def test_png_rgb_and_all_filters():
    image=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,5,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(bytes([filter_,1,2,3]) for filter_ in range(5))))+chunk(b'IEND',b'')
    assert f.png(image)==(1,5)


def test_real_production_writers(tmp_path):
    """Actual unmodified C++ serializers on synthetic input, NOT reconstruction quality."""
    import os, shutil, subprocess
    from tools.native_process import native_compile_env
    compiler=shutil.which('g++')
    if not compiler:
        if os.environ.get('RF_REQUIRE_COMPLETE_FORMAT_TOOLS')=='1':pytest.fail('g++ required')
        pytest.skip('Production writer fixture requires installed C++ compiler')
    source=tmp_path/'fixture.cpp'
    source.write_text(WRITER)
    executable=tmp_path/'writer'
    inputs=[ROOT/'reconstruction/src'/name for name in ('texture.cpp','surface.cpp','cancellation.cpp','core.cpp')]
    command=[compiler,'-std=c++20','-O0','-ffunction-sections','-fdata-sections','-I',str(ROOT/'reconstruction/include'),str(source),*map(str,inputs),'-Wl,--gc-sections','-lz','-o',str(executable)]
    result=subprocess.run(command,cwd=tmp_path,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=native_compile_env(),timeout=90)
    assert result.returncode==0,result.stdout.decode(errors='replace')[-4000:]
    # Candidate native code inherits the parent runtime environment; no sanitizer bypass.
    result=subprocess.run([str(executable),str(tmp_path)],cwd=tmp_path,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
    assert result.returncode==0,result.stdout.decode(errors='replace')[-4000:]
    assert f.validate_export('sparse-ply',(tmp_path/'sparse.ply').read_bytes())=={'pointCount':3}
    mesh=(tmp_path/'mesh.ply').read_bytes();texture=(tmp_path/'textured.glb').read_bytes()
    assert f.validate_export('mesh-ply',mesh)=={'vertexCount':3,'triangleCount':1}
    actual=f.validate_export('textured-glb',texture)
    assert actual['vertexCount']==3 and actual['triangleCount']==1 and actual['textureWidth']==actual['textureHeight']==1
    destination=os.environ.get('RF_COMPLETE_FORMAT_EVIDENCE_DIR')
    if destination:
        out=Path(destination);out.mkdir(parents=True,exist_ok=True)
        (out/'receipt.json').write_text(json.dumps({'scope':'ACTUAL_PRODUCTION_WRITERS_SYNTHETIC_INPUT_NOT_RECONSTRUCTION_QUALITY','sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},'outputs':{name:hashlib.sha256((tmp_path/name).read_bytes()).hexdigest() for name in ('sparse.ply','mesh.ply','textured.glb')},'result':actual},sort_keys=True,indent=2)+'\n')

WRITER = '#include "realityforge/texture.hpp"\nint main(int argc,char**argv){if(argc!=2)return 2;rf::SurfaceMesh m;\n for(auto p:{rf::Vec3{0,0,0},rf::Vec3{1,0,0},rf::Vec3{0,1,0}}){rf::SurfaceVertex v;v.xyz=p;v.normal={0,0,1};v.color={255,128,0};m.vertices.push_back(v);}m.triangles.push_back({0,1,2,0});\n rf::TexturedSurface t;t.atlasWidth=1;t.atlasHeight=1;t.atlasRgba={255,128,0,255};t.vertices={{0,0,0},{1,1,0},{2,0,1}};t.indices={0,1,2};\n rf::Result r;r.representation=rf::PointRepresentation::SparsePoints;for(const auto& v:m.vertices){rf::Point p;p.xyz=v.xyz;p.color=v.color;r.points.push_back(p);}rf::writePly(r,std::string(argv[1])+"/sparse.ply");rf::writeSurfacePly(m,std::string(argv[1])+"/mesh.ply");rf::writeTexturedGlb(m,t,std::string(argv[1])+"/textured.glb");}\n'
