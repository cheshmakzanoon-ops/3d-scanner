"""Synthetic host Mesa regression; not Android qualification. No repo writes."""
import ctypes as C, os, re, json, hashlib
from pathlib import Path
os.environ['EGL_PLATFORM']='surfaceless'
os.environ['LIBGL_ALWAYS_SOFTWARE']='true'
os.environ['LP_NUM_THREADS']='1'
os.environ['MESA_SHADER_CACHE_DISABLE']='true'
I=C.c_int; U=C.c_uint; P=C.c_void_p; F=C.c_float
IP=C.POINTER(I); UP=C.POINTER(U)
e=C.CDLL('libEGL.so.1')
def ef(name, restype, *args):
 f=getattr(e,name); f.restype=restype; f.argtypes=args; return f
def ints(*v): return (I*len(v))(*v)
getproc=ef('eglGetProcAddress',P,C.c_char_p)
def gl(name,restype,*args):
 address=getproc(name.encode()); assert address,name
 return C.CFUNCTYPE(restype,*args)(address)
d=ef('eglGetDisplay',P,P)(None); major=I(); minor=I()
assert ef('eglInitialize',U,P,IP,IP)(d,C.byref(major),C.byref(minor))
assert ef('eglBindAPI',U,U)(0x30a0)
config=P(); count=I()
assert ef('eglChooseConfig',U,P,IP,C.POINTER(P),I,IP)(d,ints(0x3033,1,0x3040,0x40,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038),C.byref(config),1,C.byref(count)) and count.value
ctx=ef('eglCreateContext',P,P,P,P,IP)(d,config,None,ints(0x3098,3,0x3038)); assert ctx
surface=ef('eglCreatePbufferSurface',P,P,P,IP)(d,config,ints(0x3057,1,0x3056,1,0x3038)); assert surface
assert ef('eglMakeCurrent',U,P,P,P,P)(d,surface,surface,ctx)
gs=gl('glGetString',C.c_char_p,U)
info={'egl':f'{major.value}.{minor.value}','vendor':gs(0x1f00).decode(),'renderer':gs(0x1f01).decode(),'version':gs(0x1f02).decode()}
error=gl('glGetError',U)
def check():
 value=error(); assert value==0,hex(value)
source=Path('/tmp/rf-srgb-audit/baseline-PhotographicRenderer.kt').read_text()
frag=re.search(r'private val FRAGMENT_SHADER = """(.*?)""".trimIndent',source,re.S).group(1)
frag='\n'.join(line.strip() for line in frag.strip().splitlines())
info['baseline_fragment_sha256']=hashlib.sha256(frag.encode()).hexdigest()
# Production shader is verbatim modulo Kotlin trimIndent-compatible whitespace.
vs='''#version 300 es
out vec2 vUv;
void main() {
 vec2 p=vec2((gl_VertexID==1)?3.0:-1.0,(gl_VertexID==2)?3.0:-1.0);
 gl_Position=vec4(p,0.0,1.0); vUv=vec2(0.5);
}'''
encode='''vec3 encodeSrgb(vec3 c) {
 return mix(1.055*pow(c,vec3(1.0/2.4))-0.055,12.92*c,lessThanEqual(c,vec3(0.0031308)));
}
'''
proposal=Path('/workspace/scratch/6b683a119bde/3d-scanner/app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt').read_text()
fixed=re.search(r'private val FRAGMENT_SHADER = """(.*?)""".trimIndent',proposal,re.S).group(1)
fixed='\n'.join(line.strip() for line in fixed.strip().splitlines())
info['applied_production_fragment_sha256']=hashlib.sha256(fixed.encode()).hexdigest()
create=gl('glCreateShader',U,U); shaderSource=gl('glShaderSource',None,U,I,C.POINTER(C.c_char_p),IP)
compileShader=gl('glCompileShader',None,U); shaderiv=gl('glGetShaderiv',None,U,U,IP)
getShaderLog=gl('glGetShaderInfoLog',None,U,I,IP,P)
createProgram=gl('glCreateProgram',U); attach=gl('glAttachShader',None,U,U); link=gl('glLinkProgram',None,U)
programiv=gl('glGetProgramiv',None,U,U,IP)
deleteShader=gl('glDeleteShader',None,U); deleteProgram=gl('glDeleteProgram',None,U)
def shader(kind,src):
 s=create(kind); raw=C.c_char_p(src.encode()); shaderSource(s,1,C.byref(raw),None); compileShader(s)
 ok=I(); shaderiv(s,0x8b81,C.byref(ok))
 if not ok.value:
  log=C.create_string_buffer(4096); getShaderLog(s,4096,None,log); raise AssertionError(log.value)
 return s
def program(fs):
 v=shader(0x8b31,vs); f=shader(0x8b30,fs); p=createProgram(); attach(p,v); attach(p,f); link(p)
 ok=I(); programiv(p,0x8b82,C.byref(ok)); assert ok.value
 deleteShader(v); deleteShader(f); return p
old=program(frag); new=program(fixed)
genTex=gl('glGenTextures',None,I,UP); bindTex=gl('glBindTexture',None,U,U)
texImage=gl('glTexImage2D',None,U,I,I,I,I,I,U,U,P)
texStorage=gl('glTexStorage2D',None,U,I,U,I,I)
texSub=gl('glTexSubImage2D',None,U,I,I,I,I,I,U,U,P)
texParam=gl('glTexParameteri',None,U,U,I); deleteTex=gl('glDeleteTextures',None,I,UP)
genFbo=gl('glGenFramebuffers',None,I,UP); bindFbo=gl('glBindFramebuffer',None,U,U)
attachTex=gl('glFramebufferTexture2D',None,U,U,U,U,I)
frameStatus=gl('glCheckFramebufferStatus',U,U)
attachment=gl('glGetFramebufferAttachmentParameteriv',None,U,U,U,IP)
use=gl('glUseProgram',None,U); location=gl('glGetUniformLocation',I,U,C.c_char_p)
uniform1f=gl('glUniform1f',None,I,F); uniform1i=gl('glUniform1i',None,I,I)
clearColor=gl('glClearColor',None,F,F,F,F); clear=gl('glClear',None,U)
read=gl('glReadPixels',None,I,I,I,I,U,U,P); draw=gl('glDrawArrays',None,U,I,I)
gl('glViewport',None,I,I,I,I)(0,0,1,1)
gl('glDisable',None,U)(0x0bd0) # Dither off for exact byte comparisons.
gl('glDisable',None,U)(0x0be2)
gl('glPixelStorei',None,U,I)(0x0cf5,1)
vao=U(); gl('glGenVertexArrays',None,I,UP)(1,C.byref(vao)); gl('glBindVertexArray',None,U)(vao)
encoding=I(); attachment(0x8d40,0x0405,0x8210,C.byref(encoding)); check()
info['default_framebuffer_encoding']=hex(encoding.value)
assert encoding.value==0x2601 # EGL default LINEAR.
# Explicit FBOs separately test LINEAR and SRGB output conversions.
outputs=[]
for internal in (0x8058,0x8c43):
 t=U(); genTex(1,C.byref(t)); bindTex(0x0de1,t); texStorage(0x0de1,1,internal,1,1)
 f=U(); genFbo(1,C.byref(f)); bindFbo(0x8d40,f); attachTex(0x8d40,0x8ce0,0x0de1,t,0)
 assert frameStatus(0x8d40)==0x8cd5
 attachment(0x8d40,0x8ce0,0x8210,C.byref(encoding)); check()
 outputs.append((f,t,encoding.value))
def sample(p,pixels,srgb,cutoff=.5,output=0,encode_override=None):
 f,t,encoding=outputs[output]; bindFbo(0x8d40,f)
 tex=U(); genTex(1,C.byref(tex)); bindTex(0x0de1,tex)
 raw=(C.c_ubyte*8)(*pixels)
 if srgb:
  texStorage(0x0de1,1,0x8c43,2,1); texSub(0x0de1,0,0,0,2,1,0x1908,0x1401,raw)
 else: texImage(0x0de1,0,0x1908,2,1,0,0x1908,0x1401,raw)
 for param,value in ((0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812f),(0x2803,0x812f)): texParam(0x0de1,param,value)
 use(p); uniform1f(location(p,b'uCutoff'),cutoff); uniform1i(location(p,b'uAtlas'),0)
 uniform1i(location(p,b'uEncodeOutput'), int(encoding==0x2601) if encode_override is None else encode_override)
 clearColor(0,0,0,0); clear(0x4000); draw(4,0,3)
 out=(C.c_ubyte*4)(); read(0,0,1,1,0x1908,0x1401,out); check(); deleteTex(1,C.byref(tex))
 return list(out)
results=[]
def case(name,p,pixels,srgb,expected,cutoff=.5,output=0,encode_override=None):
 actual=sample(p,pixels,srgb,cutoff,output,encode_override)
 assert all(abs(a-b)<=1 for a,b in zip(actual,expected)),(name,actual,expected)
 results.append({'case':name,'actual':actual,'expected':expected})
bw=[0,0,0,255,255,255,255,255]; gray=[128,128,128,255]*2
case('RED original encoded interpolation',old,bw,False,[128,128,128,255])
case('GREEN corrected linear filtering/manual output encoding',new,bw,True,[188,188,188,255])
case('solid gray no double encoding',new,gray,True,[128,128,128,255])
case('negative control encode without sRGB upload',new,gray,False,[188,188,188,255])
case('negative control double encoding on SRGB framebuffer',new,gray,True,[188,188,188,255],output=1,encode_override=1)
case('negative control missing output encoding',old,gray,True,[55,55,55,255])
case('SRGB framebuffer hardware encoding once',new,bw,True,[188,188,188,255],output=1)
case('SRGB framebuffer solid gray once',new,gray,True,[128,128,128,255],output=1)
case('transparent discarded',new,[255,255,255,0]*2,True,[0,0,0,0])
case('alpha 127 below cutoff',new,[128,128,128,127]*2,True,[0,0,0,0])
case('alpha 128 above cutoff straight RGB retained',new,[128,128,128,128]*2,True,[128,128,128,255])
case('alpha linear half interpolation kept at cutoff',new,[128,128,128,0,128,128,128,255],True,[128,128,128,255])
case('alpha linear half interpolation discarded above cutoff',new,[128,128,128,0,128,128,128,255],True,[0,0,0,0],cutoff=.51)
case('opaque mode cutoff zero retains transparent RGB',new,[128,128,128,0]*2,True,[128,128,128,255],cutoff=0)
# Verify the production clear policy independently, with no fragments drawn.
for f,t,encoding in outputs:
 bindFbo(0x8d40,f)
 encoded=[.055,.065,.075]
 decoded=lambda c: c/12.92 if c<=.04045 else ((c+.055)/1.055)**2.4
 values=encoded if encoding==0x2601 else [decoded(c) for c in encoded]
 clearColor(*values,1); clear(0x4000)
 actual=(C.c_ubyte*4)(); read(0,0,1,1,0x1908,0x1401,actual); check()
 expected=[14,17,19,255]
 assert all(abs(a-b)<=1 for a,b in zip(actual,expected)),(encoding,list(actual))
 results.append({'case':'clear backdrop invariance '+hex(encoding),'actual':list(actual),'expected':expected})
for p in (old,new): deleteProgram(p)
for f,t,_ in outputs:
 gl('glDeleteFramebuffers',None,I,UP)(1,C.byref(f)); deleteTex(1,C.byref(t))
gl('glDeleteVertexArrays',None,I,UP)(1,C.byref(vao)); check()
ef('eglMakeCurrent',U,P,P,P,P)(d,None,None,None)
ef('eglDestroySurface',U,P,P)(d,surface); ef('eglDestroyContext',U,P,P)(d,ctx); ef('eglTerminate',U,P)(d)
print(json.dumps({'environment':info,'results':results,'status':'PASS: expected RED reproduced, proposed correction GREEN; desktop Mesa only'},indent=2))

if '--require-production-correct' in __import__('sys').argv:
 assert abs(results[0]['actual'][0]-188)<=1, 'RED: production midpoint is128; linear-light reference is188'
