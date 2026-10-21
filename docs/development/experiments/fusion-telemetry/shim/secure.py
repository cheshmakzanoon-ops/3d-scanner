"""External telemetry comparison. DRAFT: no run until root approves frozen inputs/recipe."""
from pathlib import Path
import argparse,hashlib,json,os,resource,subprocess,time

def need(x,why):
 if not x:raise ValueError(why)
def secure_bytes(path,limit=128*1024*1024):
 path=Path(path);need(path.is_absolute() and str(path)==os.path.normpath(str(path)),'canonical absolute path required')
 fd=os.open('/',os.O_RDONLY|os.O_DIRECTORY|os.O_CLOEXEC)
 try:
  for part in path.parts[1:-1]:
   new=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW|os.O_CLOEXEC,dir_fd=fd);os.close(fd);fd=new
  new=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC,dir_fd=fd);os.close(fd);fd=new
  import stat
  before=os.fstat(fd);need(stat.S_ISREG(before.st_mode) and before.st_nlink==1 and 0<=before.st_size<=limit,'file bound/type')
  chunks=[];left=before.st_size
  while left:
   chunk=os.read(fd,min(left,65536));need(bool(chunk),'truncated read');chunks.append(chunk);left-=len(chunk)
  after=os.fstat(fd);need((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns,before.st_ctime_ns)==(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns),'file changed during read')
  return b''.join(chunks)
 finally:os.close(fd)
def h(f):return hashlib.sha256(secure_bytes(f)).hexdigest()
def regular(path,limit):need(bool(secure_bytes(path,limit)),"empty input");return Path(path)
def digest(p):return hashlib.sha256(secure_bytes(p)).hexdigest()
def safe(p):
 p=Path(p);need(p.is_absolute() and p.resolve()==p,'canonical no-symlink input');need(bool(secure_bytes(p)),'nonempty input');return p

