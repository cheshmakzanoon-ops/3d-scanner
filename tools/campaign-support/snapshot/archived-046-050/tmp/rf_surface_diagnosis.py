import os,sys,json
from pathlib import Path
sys.dont_write_bytecode=True
sys.path.insert(0,'/workspace/campaign/final-candidate/tools')
from native_api import NativeEngine
from benchmark_surface_middlebury import run
original=NativeEngine.surface
raw={}
def traced(self,*args,**kwargs):
 value=original(self,*args,**kwargs)
 raw['dense']={k:v for k,v in value['dense'].items() if k!='points'}
 raw['mesh']={k:v for k,v in value.get('mesh',{}).items() if k not in ('vertices','triangles')}
 return value
NativeEngine.surface=traced
metrics=run();print(json.dumps({'library':os.environ['RF_NATIVE_LIBRARY'],'metrics':metrics,'raw':raw},indent=2))
