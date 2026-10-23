import sys
sys.dont_write_bytecode=True
import pathlib,json,struct,math,hashlib,resource,collections
from exact_geometry import Budget,Refusal
from bridge import convert,validate_index,verify
from ledger_adapter import Ledger
from front import reconstruct
from audit import audit
from witness_audit import audit as witness_audit,need

def publish_mesh(out,ledger,faces,proofs,identity,summary):
 # Export compaction is only an injective storage map; the full original point
 # pool and every unreferenced/unknown point remain in sealed bridge records.
 ids=sorted({a for t in faces for a in t});remap={a:i for i,a in enumerate(ids)};buf=bytearray()
 def put(fmt,*v):buf.extend(struct.pack('<'+fmt,*v))
 put('Q',len(ids))
 for aid in ids:
  r=ledger.attributes[aid][0];put('6d',*(float(r[k]) for k in ('px','py','pz')),0.,0.,0.);put('3B',*(int(r[k]) for k in ('r','g','b')));put('fB',float(r['confidence']),int(r['supportingViews']))
 put('Q',len(faces))
 for t in faces:put('3IB',*(remap[a] for a in t),255)
 put('5Qf',len(ledger.samples),len(ledger.membership_rows),0,0,len(ledger.membership_rows)-len(ledger.points),0.)
 attached=[(aid,sid) for aid,sid,w,r in ledger.membership_rows if aid in remap];unattached=[(aid,sid) for aid,sid,w,r in ledger.membership_rows if aid not in remap];put('Q',len(attached))
 for aid,sid in attached:
  cam,x,y=ledger.samples[sid][:3];put('IBHHQB',remap[aid],cam,x,y,sid,0)
 put('Q',len(unattached))
 for aid,sid in unattached:
  cam,x,y=ledger.samples[sid][:3];put('BHHQB',cam,x,y,sid,0)
 put('6QB',0,0,0,0,0,0,0)
 need(len(buf)<=16777216,'GEOMETRY_OUTPUT_BOUND')
 proofrows=[]
 for i,p in enumerate(proofs):
  proofrows.append({'face':i,'originalPointIds':faces[i],'proposal':p['proposal'],'sourceAttemptIds':[identity[j][1] for j in p['sourceTriangleIds']],'boundaryTests':p['boundaryTests'],'derivation':'fixed original points; original source-triangle constraints; no new observation'})
 data={'candidate.bits':bytes(buf),'face-provenance.json':(json.dumps(proofrows)+'\n').encode(),'export-point-map.json':(json.dumps(ids)+'\n').encode(),'front-summary.json':(json.dumps(summary,indent=2)+'\n').encode()}
 need(sum(map(len,data.values()))<=67108864,'GEOMETRY_PROVENANCE_OUTPUT_BOUND')
 for n,d in data.items():need(not (out/n).exists(),'GEOMETRY_FRESH_OUTPUT');(out/n).write_bytes(d)
 return {'allPointsRetained':len(ledger.points),'exportReferencedPoints':len(ids),'attachedObservations':len(attached),'unattachedObservations':len(unattached),'unacceptedOriginalSampleIds':ledger.unaccepted,'meshBitsSHA256':hashlib.sha256(buf).hexdigest(),'pointXYZAttributesBitBinding':'export values from unchanged original decimal roundtrips; all originals retained','sourceCamera255':'explicit unknown/synthesized sentinel, not native source-camera provenance','normals':'zero pre-face attributes retained, not invented point normals'}

def main():
 package,run,out=map(pathlib.Path,sys.argv[1:]);cfg=json.loads((package/'config.json').read_text());pins=json.loads((package/'input-pins.json').read_text())['files'];need(not out.exists(),'FRESH_ARM');out.mkdir();need(resource.getrlimit(resource.RLIMIT_AS)[0]<=cfg['maxAddressSpaceBytes'],'AS_LIMIT');need(resource.getrlimit(resource.RLIMIT_CPU)[0]<=600,'CPU_LIMIT')
 try:
  verify(run,pins);normal=audit(run/'observer');witness=witness_audit(run/'observer');B=Budget(cfg['maxClippingChecks'],cfg['maxBallChecks'],cfg['maxPartitionFragments'],cfg['maxRationalBits']);bp,rho,facts,identity=convert(run,out/'bridge',pins)
  ledger=Ledger(bp,B,rho,max_views=8,normal_step=2);need(len(ledger.points)==4855 and sum(ledger.unknown)==2363 and len(ledger.samples)==9172,'FULL_NATIVE_LEDGER');need(sum((b,a) in ledger.edges for a,b in ledger.edges)==17920,'STRICT_RECIPROCAL_IDENTITY');index=validate_index(ledger,identity)
  (out/'binding.json').write_text(json.dumps({'nativeOrderedBitAudit':normal['orderedAccumulatorBitParity'],'nativeWitnessAudit':witness['strictIdentityDiagnostics'],'bridge':facts,'index':index,'sourcePins':pins},indent=2)+'\n')
  ledger.reject_only_positive_area=True;faces,proofs,summary=reconstruct(ledger,cfg);ledger.verify();verify(run,pins);account=publish_mesh(out,ledger,faces,proofs,identity,summary);account.update(inputHashesUnchanged=True,frontComplete=True,quality='NOT_YET_EVALUATED',scope='ORACLE_CAMERA_COMPONENT_NOT_IMAGE_DERIVED_REGISTRATION')
  (out/'geometry-receipt.json').write_text(json.dumps(account,indent=2)+'\n');need(sum(f.stat().st_size for f in out.rglob('*') if f.is_file())<=cfg['maxOutputBytes'],'CASE_OUTPUT_BOUND')
 except BaseException as e:
  # No successful geometry publication survives a whole-case failure.
  for name in ('candidate.bits','geometry-receipt.json'):
   if (out/name).exists():(out/name).rename(out/(name+'.REFUSED'))
  (out/'REFUSED.json').write_text(json.dumps({'error':type(e).__name__,'reason':str(e),'geometry':'NOT_PUBLISHED','quality':'NO_GO_INCOMPLETE_ARM','sharedWork':{'clips':B.used_clips,'balls':B.used_balls} if 'B' in locals() else None},indent=2)+'\n');raise
if __name__=='__main__':main()
