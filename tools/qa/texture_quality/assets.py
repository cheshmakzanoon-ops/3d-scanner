"""C10 self-contained photographic GLB/PNG adapter; no external URI resolution."""
import hashlib
import io
import numpy as np
from PIL import Image
from tools.qa.geometry_quality.glb import read,accessor,geometry


def load(path, expected_png=None):
    data,binary=read(path);vertices,faces=geometry(path);prim=data['meshes'][0]['primitives'][0]
    try:
        uv=accessor(data,binary,prim['attributes']['TEXCOORD_0'])
        material=data['materials'][prim['material']]
        color_texture=material['pbrMetallicRoughness']['baseColorTexture']
        if color_texture.get('texCoord',0)!=0 or color_texture.get('extensions') or 'COLOR_0' in prim['attributes']:raise ValueError('unsupported texture transform/color modulation')
        texture_index=color_texture['index']
        texture=data['textures'][texture_index];image=data['images'][texture['source']]
        if 'sampler' in texture:
            sampler=data['samplers'][texture['sampler']]
            if sampler.get('wrapS',10497)!=33071 or sampler.get('wrapT',10497)!=33071 or sampler.get('magFilter',9729)!=9729 or sampler.get('minFilter',9729)!=9729:raise ValueError('unsupported sampler; linear clamp required')
        if image.get('mimeType')!='image/png' or 'uri' in image:raise ValueError('embedded photographic PNG required')
        if material.get('pbrMetallicRoughness',{}).get('baseColorFactor',[1,1,1,1])!=[1,1,1,1]:raise ValueError('unsupported material tint')
        if material.get('alphaMode','OPAQUE') not in ('OPAQUE','BLEND','MASK'):raise ValueError('invalid alpha mode')
        view=data['bufferViews'][image['bufferView']];start=view.get('byteOffset',0);size=view['byteLength']
        if view.get('buffer',0)!=0 or min(start,size)<0 or start+size>data['buffers'][0]['byteLength']:raise ValueError('image view bounds')
        encoded=binary[start:start+size]
        if expected_png is not None and encoded!=expected_png.read_bytes():raise ValueError('texture substitution: embedded bytes differ from expected PNG')
        with Image.open(io.BytesIO(encoded)) as decoded:
            if decoded.width*decoded.height>16000000:raise ValueError('image pixel budget')
            rgba=np.asarray(decoded.convert('RGBA'),float)/255
        return vertices,faces,uv,rgba,{'embedded_png_sha256':hashlib.sha256(encoded).hexdigest(),'alpha_mode':material.get('alphaMode','OPAQUE'),
                                     'opaque_material_with_transparent_texels':material.get('alphaMode','OPAQUE')=='OPAQUE' and bool(np.any(rgba[:,:,3]<1))}
    except (KeyError,IndexError,TypeError,OSError) as exc:raise ValueError('unsupported photographic GLB material/image') from exc
