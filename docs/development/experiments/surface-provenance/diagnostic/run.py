"""Diagnostic metadata extraction only; never claims original native cache load."""
from pathlib import Path
import argparse,hashlib,json,os,resource,struct,subprocess,time
def require(condition,message):
 if not condition:raise ValueError(message)
def secure_bytes(path,limit=128*1024*1024):
 path=Path(path);require(path.is_absolute() and str(path)==os.path.normpath(str(path)),'canonical absolute path required')
 fd=os.open('/',os.O_RDONLY|os.O_DIRECTORY|os.O_CLOEXEC)
 try:
  for part in path.parts[1:-1]:
   new=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW|os.O_CLOEXEC,dir_fd=fd);os.close(fd);fd=new
  new=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC,dir_fd=fd);os.close(fd);fd=new
  import stat
  before=os.fstat(fd);require(stat.S_ISREG(before.st_mode) and before.st_nlink==1 and 0<=before.st_size<=limit,'file bound/type')
  chunks=[];left=before.st_size
  while left:
   chunk=os.read(fd,min(left,65536));require(bool(chunk),'truncated read');chunks.append(chunk);left-=len(chunk)
  after=os.fstat(fd);require((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns,before.st_ctime_ns)==(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns),'file changed during read')
  return b''.join(chunks)
 finally:os.close(fd)
def h(f):return hashlib.sha256(secure_bytes(f)).hexdigest()
def regular(path,limit):require(bool(secure_bytes(path,limit)),"empty input");return Path(path)
def bind_mesh(out,capture,result):
 require(secure_bytes(out/'serialized.ply')==secure_bytes(capture/'mesh.ply'),'original native PLY serialization differs')
 require(json.loads(secure_bytes(out/'serialized-mesh.json'))==result['meshPreview'],'original native JSON serialization differs')

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True);ap.add_argument('--work',required=True);a=ap.parse_args();cfgpath=regular(Path(a.config),1024*1024);cfg=json.loads(secure_bytes(cfgpath));repo=Path(cfg['repo']);capture=Path(cfg['capture']);engine=regular(Path(cfg['engine']),128*1024*1024);compiler=Path(cfg['compiler']);source=Path(__file__).with_name('decode.cpp');exportedSource=Path(__file__).with_name('exported-analyze.cpp');work=Path(a.work)
 require(h(engine)==cfg['engineSha256']=='4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b','original engine pin');jni=Path('/tmp/typed-ba-increment/librealityforge_jni.so');require(h(jni)=='427f2c8c0e21a5647064edcdbe9307265fe493c256c211e4266f979f9931fcce','original JNI pin');require(h(compiler)==cfg['compilerSha256'],'compiler pin')
 require(h(capture/'capture.json')==cfg['captureManifestSha256'],'capture pin');manifest=json.loads(secure_bytes(capture/'capture.json'));entries=manifest['artifacts'];require({f.name for f in capture.iterdir()}==set(entries)|{'capture.json'},'extra capture files');require(set(entries)=={'result.json','sparse.ply','dense.ply','mesh.ply','surface-v2.rfmesh'},'exact capture inventory')
 files=[]
 for name,item in entries.items():
  f=regular(capture/name,12*1024*1024 if name=='surface-v2.rfmesh' else 32*1024*1024);require(f.stat().st_size==item['bytes'] and h(f)==item['sha256'],'capture hash/size');files.append(f)
 result=json.loads(secure_bytes(capture/'result.json'));identity=result['densePreview']['depthCheckpointIdentity'];require(identity==manifest['depthCheckpointIdentity'],'independent depth identity binding')
 for relative,digest in cfg['nativeSourceSha256'].items():require(h(repo/relative)==digest,'native source pin')
 inputs=[cfgpath,Path(__file__),source,exportedSource,engine,jni,compiler,capture/'capture.json',*files,*sorted((repo/'reconstruction/include').rglob('*.hpp')),*[repo/rel for rel in cfg['nativeSourceSha256']],*[Path(f) for f in cfg['toolchainSha256']]]
 for f,digest in cfg['toolchainSha256'].items():require(h(f)==digest,'toolchain pin')
 pins={str(f):h(f) for f in inputs};require(work.is_absolute() and work.parent.resolve()==work.parent and not work.exists(),'fresh canonical work required');require(work.parent.stat().st_uid in (0,os.geteuid()),'work parent owner');work.mkdir(mode=0o700);(work/'inputs.json').write_text(json.dumps(pins,indent=2)+'\n')
 env={'PATH':'/usr/bin:/bin','LC_ALL':'C','HOME':os.environ['HOME']};commands=[];start=time.monotonic()
 def limit():resource.setrlimit(resource.RLIMIT_AS,(2*1024**3,2*1024**3))
 def run(name,cmd,timeout,expected=0):
  commands.append({'name':name,'argv':cmd,'timeoutSeconds':timeout});q=subprocess.run(cmd,capture_output=True,text=True,env=env,preexec_fn=limit,timeout=min(timeout,max(1,300-(time.monotonic()-start))));(work/(name+'.log')).write_text(q.stdout+q.stderr);require(q.returncode==expected,f'{name} exit {q.returncode}');return q.stdout+q.stderr
 expected=cfg['expectedIntersections'];require(expected==result['meshPreview']['intersections'],'independent replicate counter binding')
 require(cfg['replicateLabel']=='fresh-baseline-surface-capture-20261021' and cfg['depthCheckpointIdentity']==identity,'replicate identity binding')
 exportedExe=work/'exported-analyze'
 run('compile-exported',[str(compiler),'-std=c++20','-O2','-Werror','-I'+str(repo/'reconstruction/include'),str(exportedSource),'-L'+str(engine.parent),'-lrealityforge','-Wl,-z,defs','-Wl,-rpath,'+str(engine.parent),'-o',str(exportedExe)],60)
 exportedDeps=run('linkage-exported',['ldd','-r',str(exportedExe)],15);require(str(engine) in exportedDeps and 'undefined symbol:' not in exportedDeps,'exported native dependency resolution')
 exported=json.loads(run('independent-exported-classification',[str(exportedExe),str(capture/'mesh.ply'),str(capture/'mesh.ply')],60))
 require(exported['baseline']==exported['candidate'] and not exported['baseline']['budgetExceeded'] and exported['baseline']['intersections']==expected,'independent exported counter mismatch')
 exe=work/'decode'
 run('compile',[str(compiler),'-std=c++20','-O2','-Werror','-I'+str(repo/'reconstruction/include'),str(source),'-L'+str(engine.parent),'-lrealityforge','-Wl,-z,defs','-Wl,-rpath,'+str(engine.parent),'-o',str(exe)],60)
 deps=run('linkage',['ldd','-r',str(exe)],15);require(str(engine) in deps and 'undefined symbol:' not in deps,'native dependency resolution')
 stride=str(result['densePreview']['pixelStride']);out=work/'decoded';run('decode',[str(exe),'serialize',str(capture/'surface-v2.rfmesh'),identity,stride,str(out),'-'],60)
 bind_mesh(out,capture,result)
 classified=work/'classified';run('classify',[str(exe),'classify',str(capture/'surface-v2.rfmesh'),identity,stride,str(classified),str(capture/'mesh.ply')],60)
 trace=json.loads((classified/'trace.json').read_text());require(trace['intersections']==expected,'fresh replicate counter differs');require(trace['intersections']==result['meshPreview']['intersections'],'native counter differs');require(trace['intersectionPairs']==exported['baseline']['intersectionPairs'],'independent exported pair sets differ')
 original=secure_bytes(capture/'surface-v2.rfmesh');tests=[]
 def mutation(name,change,error,identityArg=identity):
  b=bytearray(original);change(b)
  if name!='bad-trailer':b[-32:]=hashlib.sha256(b[:-32]).digest()
  f=work/(name+'.rfmesh');f.write_bytes(b);log=run(name,[str(exe),'serialize',str(f),identityArg,stride,str(work/(name+'-out')),'-'],30,1);require(error in log,'unexpected negative failure');tests.append(name)
 mutation('bad-trailer',lambda b:b.__setitem__(-1,b[-1]^1),'checksum mismatch')
 mutation('oversized-vertices',lambda b:struct.pack_into('<I',b,28,40001),'counts')
 mutation('invalid-face-index',lambda b:struct.pack_into('<I',b,trace['triangleOffset'],0xffffffff),'face provenance/index')
 mutation('wrong-algorithm',lambda b:b.__setitem__(140,b[140]^1),'algorithm mismatch')
 mutation('wrong-depth-identity',lambda b:None,'depth identity mismatch',('0' if identity[0]!='0' else '1')+identity[1:])
 # Exercise the actual binding function against altered external copies; originals are immutable.
 badCapture=work/'bad-capture';badCapture.mkdir();(badCapture/'mesh.ply').write_bytes(secure_bytes(capture/'mesh.ply')+b'\n')
 try:bind_mesh(out,badCapture,result)
 except ValueError as error:require('PLY serialization differs' in str(error),'wrong PLY guard')
 else:raise ValueError('altered PLY accepted')
 tests.append('altered-ply-rejected')
 altered=json.loads(json.dumps(result));altered['meshPreview']['triangles'][0][0]=0xffffffff
 try:bind_mesh(out,capture,altered)
 except ValueError as error:require('JSON serialization differs' in str(error),'wrong JSON guard')
 else:raise ValueError('altered JSON accepted')
 tests.append('altered-json-rejected')
 require(pins=={str(f):h(f) for f in inputs},'input changed during run')
 outputs={str(f.relative_to(work)):h(f) for f in work.rglob('*') if f.is_file()}
 receipt={'replicateLabel':cfg['replicateLabel'],'publishedCommit':cfg['publishedCommit'],'depthCheckpointIdentity':identity,'independentExportedClassification':exported['baseline'],'old6176PairLineageRecovered':False,'status':'DIAGNOSTIC_PROVENANCE_PASS_NOT_NATIVE_CACHE_LOAD','elapsedSeconds':time.monotonic()-start,'commands':commands,'inputHashes':pins,'outputHashes':outputs,'negativeGuards':tests,'trace':trace,'limitations':['Missing native input RGBA and original reference depth maps: geometryIdentity/mapIdentity not independently recomputed.','Exact native serialization matched both captured PLY and complete meshPreview JSON before reporting provenance; analysis uses PLY float parsing, not checkpoint doubles.','Stored sample normalSupport histogram is not the recomputed fusion neighborhood-normal evidence; the latter is not stored in this checkpoint.','This fresh baseline replicate is not the old6176 paired-run artifact; counts are not transferred between runs and jitter cause is not established.','No source geometry or thresholds changed.']}
 (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS',h(work/'receipt.json'));print(json.dumps(trace['classes']))
if __name__=='__main__':main()
