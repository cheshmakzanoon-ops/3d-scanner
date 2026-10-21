"""Read-only RFAC wire-v1 camera decoder, not replacement checkpoint acceptance."""
import struct,hashlib,math
class Reader:
 def __init__(self,b):self.b=b;self.i=0
 def take(self,n):
  assert 0<=n<=len(self.b)-self.i
  v=self.b[self.i:self.i+n];self.i+=n;return v
 def integer(self):return struct.unpack('>i',self.take(4))[0]
 def count(self,max=1000000):
  n=self.integer();assert 0<=n<=max;return n
 def string(self,max=65536):return self.take(self.count(max)).decode('utf8')
 def double(self):
  x=struct.unpack('>d',self.take(8))[0];assert math.isfinite(x);return x

def cameras(raw):
 r=Reader(raw);assert r.take(4)==b'RFAC' and r.integer()==1
 n=r.count(16*1024*1024);h=r.take(32);payload=r.take(n);assert r.i==len(raw) and hashlib.sha256(payload).digest()==h
 r=Reader(payload);assert r.integer()==1
 r.string(128);r.take(8);r.string(128);r.take(8);r.take(32) # binding/snapshot
 r.take(32);assert r.string(32)=='alignment';r.string(96);r.string(96);r.take(32)
 for _ in range(r.count()):r.string();r.string()
 for _ in range(r.count(32)):r.string(32);r.take(32)
 for _ in range(r.count(32)):r.string(32);r.take(32)
 acceptance=[r.string(32),r.string(96),r.string(96),r.take(32).hex()];assert r.take(1)==b'\x01'
 assert r.count(3)==3
 cams=[]
 for _ in range(3):
  c=dict(photoId=r.string(160),width=r.integer(),height=r.integer())
  for k in ['fx','fy','cx','cy']:c[k]=r.double()
  c['rotation']=[r.double() for _ in range(9)];c['translation']=[r.double() for _ in range(3)];cams.append(c)
 for _ in range(r.count(1000000)):r.take(16);r.double()
 tracks=r.count(200000);observations=0
 for _ in range(tracks):
  r.string(160)
  for _ in range(3):r.double()
  r.take(12);r.double();n=r.count(3);observations+=n
  for _ in range(n):r.take(8);r.double();r.double()
 assert r.i==len(payload)
 return cams,dict(acceptance=acceptance,tracks=tracks,observations=observations,limitation='Wire/hash/finite parsing only; production checkpoint semantic validation is established by terminal harness receipt, not reimplemented here')
