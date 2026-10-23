"""Decode fixed PNGs and extract explicitly authorized oracle-camera input only."""
import sys,pathlib,json,hashlib,struct,math,importlib.machinery
sys.dont_write_bytecode=True
import cv2,numpy as np

def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def need(b,m):
    if not b:raise RuntimeError(m)
def prepare(fixture,out):
    fixture=pathlib.Path(fixture).resolve();out=pathlib.Path(out).resolve();need(not out.exists(),'fresh input output');out.mkdir()
    need(sha(fixture/'SHA256SUMS')=='c1107dd09510ffbba286b1c7bdae2e3d099726df8704322f20ed92e1010176b6','FIXTURE_MANIFEST')
    for line in (fixture/'SHA256SUMS').read_text().splitlines():
        h,n=line.split('  ',1);p=(fixture/n).resolve();need(p.is_relative_to(fixture) and p.is_file() and sha(p)==h,'FIXTURE_IDENTITY:'+n)
    observation=json.loads((fixture/'observation-inputs.json').read_text());render=json.loads((fixture/'evaluation-only/fixture.json').read_text());rows=render['cameras'];need(len(rows)==8 and observation['width']==160 and observation['height']==120,'FIXED_VIEWSET')
    need(render['settings']=={'near':3.0,'far':7.0,'planes':96,'stride':2},'FIXED_TEST_BOUNDS')
    prefix=pathlib.Path(sys.prefix).resolve();interpreterEntry=pathlib.Path(sys.executable).absolute()
    need(interpreterEntry.parent.parent.resolve()==prefix,'INTERPRETER_PREFIX_OWNERSHIP')
    modules={}
    for name,module in list(sys.modules.items()):
        file=getattr(module,'__file__',None)
        if file and (name=='cv2' or name.startswith('cv2.') or name=='numpy' or name.startswith('numpy.')):
            path=pathlib.Path(file).resolve();need(path.is_relative_to(prefix) and path.is_file(),'MODULE_OWNERSHIP:'+name);modules[name]={'path':str(path),'sha256':sha(path)}
    loaded={}
    for line in pathlib.Path('/proc/self/maps').read_text().splitlines():
        fields=line.split(None,5)
        if len(fields)==6 and 'site-packages/' in fields[5] and '.so' in fields[5]:
            path=pathlib.Path(fields[5]).resolve();need(path.is_relative_to(prefix) and path.is_file(),'EXTENSION_OWNERSHIP');loaded[str(path)]=sha(path)
    need(any('/cv2/' in p for p in loaded) and any('/numpy/' in p for p in loaded),'DECODER_EXTENSIONS_NOT_MAPPED')
    payload=bytearray(struct.pack('<Q',8));images=[]
    for i,entry in enumerate(observation['images']):
        need(entry['id']==f'view-{i:02}' and i<8,'VIEW_ORDER');p=fixture/entry['path'];need(sha(p)==entry['sha256'],'PNG_IDENTITY');image=cv2.imread(str(p),cv2.IMREAD_UNCHANGED);need(image is not None and image.shape==(120,160,4) and image.dtype==np.uint8,'RGBA_SCHEMA');rgba=cv2.cvtColor(image,cv2.COLOR_BGRA2RGBA).tobytes();need(len(rgba)==76800,'RGBA_SIZE')
        row=rows[i];need(len(row)==16 and all(math.isfinite(v) for v in row),'CAMERA_ROWS');need(row[12:]==[200.0,200.0,79.5,59.5],'CAMERA_K');payload.extend(struct.pack('<QQ16dQ',160,120,*row,len(rgba)));payload.extend(rgba);images.append({'id':entry['id'],'pngSHA256':sha(p),'rgbaSHA256':hashlib.sha256(rgba).hexdigest()})
    need(len(images)==8,'EXACT_EIGHT');(out/'solver-input.bits').write_bytes(payload)
    receipt={'oracleCameraExtrinsicsInput':True,'imageDerivedRegistration':False,'geometryMasksRayWitnessInput':False,'scale':'supplied oracle-camera gauge','cameraRows':rows,'settings':render['settings'],'images':images,'inputSHA256':sha(out/'solver-input.bits'),'decoder':{'opencv':cv2.__version__,'numpy':np.__version__,'interpreterEntryPoint':str(interpreterEntry),'interpreterRealPath':str(interpreterEntry.resolve()),'interpreterSHA256':sha(interpreterEntry.resolve()),'sysPrefix':str(prefix),'sysBasePrefix':sys.base_prefix,'modules':modules,'loadedExtensionBinaries':loaded},'fixtureManifest':sha(fixture/'SHA256SUMS')}
    (out/'input-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
if __name__=='__main__':prepare(*sys.argv[1:])
