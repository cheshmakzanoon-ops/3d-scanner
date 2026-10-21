"""One bounded classification child; receipt trust/admission owned by run.py."""
from pathlib import Path
import argparse,hashlib,json,os,stat,sys,importlib.util
sys.dont_write_bytecode=True
from secure import secure_bytes,need,digest,safe
spec=importlib.util.spec_from_file_location('rf_edge_reader',Path(__file__).with_name('inspect.py'));reader=importlib.util.module_from_spec(spec);spec.loader.exec_module(reader)
bind_inputs,analyze,strict_json,SAMPLE_KEY=reader.bind_inputs,reader.analyze,reader.strict_json,reader.SAMPLE_KEY

def main():
 p=argparse.ArgumentParser()
 for key in ['receipt','receipt-sha','bits','mesh-json','bits-key','json-key','counts','proof','out']:p.add_argument('--'+key,required=True)
 a=p.parse_args();bits,mesh=bind_inputs(a.receipt,a.receipt_sha,a.bits,a.mesh_json,a.bits_key,a.json_key)
 proof=strict_json(secure_bytes(a.proof,12*1024*1024));need(proof['schema']==1 and proof['scope']=='EXACT_NATIVE_FACE_ELIGIBILITY_AND_EDGE_TOTALS_ONLY' and proof['crossingsNotComputed'] is True and proof['maxIntersectionCandidates']==0 and proof['meshBitsSha256']==hashlib.sha256(bits).hexdigest(),'exact native face eligibility proof')
 excluded=proof['excludedNativeDegenerateFaces'];need(isinstance(excluded,list) and all(type(i) is int for i in excluded) and len(set(excluded))==len(excluded),'unique native exclusions')
 counts=strict_json(secure_bytes(a.counts,12*1024*1024));result=analyze(bits,mesh,counts[SAMPLE_KEY],set(excluded))
 for key in ['vertexCount','triangleCount','boundaryEdges','nonmanifoldEdges']:need(result['aggregates'][key]==proof[key],'native/indexed proof parity '+key)
 result.update(nativeEligibilityProofSha256=digest(a.proof),meshJsonSha256=digest(a.mesh_json),admittedSampleRowsSha256=digest(a.counts))
 data=(json.dumps(result,separators=(',',':'),allow_nan=False)+'\n').encode();need(len(data)<=12*1024*1024,'result cap');out=Path(a.out);need(out.is_absolute() and out.parent.resolve()==out.parent,'canonical output parent');st=out.parent.stat();need(st.st_uid==os.getuid() and stat.S_IMODE(st.st_mode)==0o700,'owned0700 output parent')
 fd=os.open(out,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW|os.O_CLOEXEC,0o600)
 try:
  at=0
  while at<len(data):
   n=os.write(fd,data[at:]);need(n>0,'output write');at+=n
 finally:os.close(fd)
 need(secure_bytes(out,12*1024*1024)==data,'output verification');print('STRUCTURAL_EDGE_CLASSIFICATION_READY_NOT_CAUSAL_PROOF')
if __name__=='__main__':main()
