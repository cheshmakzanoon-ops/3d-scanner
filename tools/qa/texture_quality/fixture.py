"""Tiny asymmetric checker/color photographic-evaluation fixtures, SYNTHETIC only."""
import json
from pathlib import Path
import struct
import numpy as np
from PIL import Image


def generate(root: Path):
    root.mkdir(parents=True,exist_ok=True)
    y,x=np.mgrid[:16,:16];checker=((x//2+y//2)%2)*.4
    tex=np.stack([.1+checker+.4*x/15,.05+.6*y/15,.8-.6*x/15,np.ones((16,16))],-1)
    encoded=np.rint(tex*255).astype('uint8');Image.fromarray(encoded,'RGBA').save(root/'checker.png')
    vertices=np.array([[0,0,1],[15,0,1],[15,15,1],[0,15,1]],dtype='<f4')
    uv=np.array([[.5,.5],[15.5,.5],[15.5,15.5],[.5,15.5]],dtype='<f4')/16
    indices=np.array([0,1,2,0,2,3],dtype='<u4');png=(root/'checker.png').read_bytes()
    binary=vertices.tobytes()+uv.tobytes()+indices.tobytes()+png
    # Stored file coordinates match the declared analytic camera, not an internal-engine transform.
    data={'asset':{'version':'2.0','generator':'SYNTHETIC texture QA fixture'},'scene':0,'scenes':[{'nodes':[0]}],'nodes':[{'mesh':0}],
          'meshes':[{'primitives':[{'attributes':{'POSITION':0,'TEXCOORD_0':1},'indices':2,'material':0,'mode':4}]}],
          'buffers':[{'byteLength':len(binary)}],
          'bufferViews':[{'buffer':0,'byteOffset':0,'byteLength':48},{'buffer':0,'byteOffset':48,'byteLength':32},{'buffer':0,'byteOffset':80,'byteLength':24},{'buffer':0,'byteOffset':104,'byteLength':len(png)}],
          'accessors':[{'bufferView':0,'componentType':5126,'count':4,'type':'VEC3'},{'bufferView':1,'componentType':5126,'count':4,'type':'VEC2'},{'bufferView':2,'componentType':5125,'count':6,'type':'SCALAR'}],
          'images':[{'bufferView':3,'mimeType':'image/png'}],'textures':[{'source':0}],
          'materials':[{'pbrMetallicRoughness':{'baseColorTexture':{'index':0}},'alphaMode':'BLEND'}]}
    js=json.dumps(data,separators=(',',':')).encode();js+=b' '*((-len(js))%4);binary+=b'\0'*((-len(binary))%4)
    body=struct.pack('<III',0x46546c67,2,28+len(js)+len(binary))+struct.pack('<II',len(js),0x4e4f534a)+js+struct.pack('<II',len(binary),0x004e4942)+binary
    (root/'checker.glb').write_bytes(body)
    Image.fromarray(np.full((16,16),255,'uint8'),'L').save(root/'visible.png')
    return root/'checker.glb'


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path)
    print(generate(parser.parse_args().output))
