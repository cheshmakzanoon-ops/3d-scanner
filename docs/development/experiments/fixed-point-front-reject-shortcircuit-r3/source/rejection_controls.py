"""Original full path versus reject-only path on fixed analytic controls."""
import hashlib,json
from front_controls import Analytic,patch,cameras
from front import reconstruct
from overlay_controls import record
from closed_certificate import *

def need(ok,why):
 if not ok:raise RuntimeError(why)
class Tracked(Analytic):
 def candidate_certificate(self,ids):
  result=super().candidate_certificate(ids)
  self.trace.append((tuple(ids),result))
  return result

def run(cfg):
 out={};c=[(F(0),F(0),F(1)),(F(1),F(0),F(1)),(F(0),F(1),F(1))];B=Budget();a=record(0,0,SIMPLEX,F(1),c,B);b=record(1,1,SIMPLEX,F(1),c,B);edges={(i,i+3) for i in range(3)}|{(i+3,i) for i in range(3)};fr=record(2,2,SIMPLEX,F(2),c,B);touch=record(2,2,[(F(-1),F(0)),(F(0),F(-1)),(F(0),F(0))],F(2),c,B)
 for name,records,links,early in [('valid',[a,b],edges,False),('insufficient',[a],set(),True),('interiorFront',[a,b,fr],edges,True),('boundaryFront',[a,b,touch],edges,False),('boundaryWitness',[a,b],{(0,3),(3,0)},False)]:
  oldB=Budget();newB=Budget();old=closed_certificate(records,links,oldB);new=closed_certificate(records,links,newB,reject_only=True)
  need(old['accepted_support_hypothesis']==new['accepted_support_hypothesis'],'DECISION:'+name)
  need((new.get('rejectionReasonsComplete',True)==False)==early,'EARLY_MARKER:'+name)
  if not early:need(old==new,'FULL_PATH_EXACT:'+name)
  else:need(set(new['refusals'])<=set(old['refusals']) and new['boundaryValidation']=='NOT_EVALUATED_ALREADY_REJECTED','PARTIAL_REASONS:'+name)
  out[name]={'decisionIdentical':True,'fullResultIdentical':old==new,'earlyRefusal':early,'oldClips':oldB.used_clips,'newClips':newB.used_clips}
 try:closed_certificate([a,b],edges,Budget(clips=0),reject_only=True)
 except Refusal as e:need(str(e)=='SUPPORT_WORK_BUDGET','BUDGET_REASON')
 else:raise RuntimeError('BUDGET_NOT_REFUSED')
 for kind in ('plane','curved','hole','disconnected','fold','aboveBandLayers','belowResolutionLayers'):
  p,t=patch(n=5 if kind=='hole' else 3,hole=(1,1) if kind=='hole' else None,z=(lambda x,y:F(5)+(x*x+y*y)/16) if kind=='curved' else (lambda x,y:F(5)+abs(x-F(1,2))/2) if kind=='fold' else (lambda x,y:F(5)));groups=[(list(range(len(p))),cameras())]
  if kind=='disconnected':
   q,f=patch(offset=F(4));n=len(p);p+=q;t+=[tuple(i+n for i in tri) for tri in f];groups=[(list(range(len(p))),cameras())]
  if kind in ('aboveBandLayers','belowResolutionLayers'):
   z=F(7) if kind=='aboveBandLayers' else F(101,20);q,f=patch(z=lambda x,y:z);n=len(p);p+=q;t+=[tuple(i+n for i in tri) for tri in f];groups=[(list(range(n)),cameras()),(list(range(n,len(p))),cameras(True,z+5))]
  answers=[];traces=[];costs=[];earlyCount=0
  for enabled in (False,True):
   ledger=Tracked(p,t,groups,Budget());ledger.trace=[];ledger.reject_only_positive_area=enabled
   immutable=repr((ledger.points,ledger.samples,ledger.members,ledger.triangles,ledger.edges,ledger.unknown))
   faces,proofs,summary=reconstruct(ledger,cfg)
   need(repr((ledger.points,ledger.samples,ledger.members,ledger.triangles,ledger.edges,ledger.unknown))==immutable,'LEDGER_MUTATED')
   answers.append((faces,proofs));traces.append(ledger.trace);costs.append(summary['sharedExactWork'])
   if enabled:earlyCount=sum(not cert.get('rejectionReasonsComplete',True) for ids,cert in ledger.trace)
  need(answers[0]==answers[1],'ORIENTED_SEQUENCE_AND_PROVENANCE:'+kind);need(len(traces[0])==len(traces[1]),'ATTEMPT_SEQUENCE:'+kind)
  for (ids,a),(ids2,b) in zip(*traces):
   need(ids==ids2 and a['accepted_support_hypothesis']==b['accepted_support_hypothesis'],'ALL_CERTIFICATE_DECISIONS:'+kind)
   if a['accepted_support_hypothesis']:need(a==b,'ACCEPTED_CERTIFICATE_EXACT:'+kind)
   elif not b.get('rejectionReasonsComplete',True):need(set(b['refusals'])<=set(a['refusals']),'REJECTION_SUBSET:'+kind)
   else:need(a==b,'FULL_REJECTED_CERTIFICATE_EXACT:'+kind)
  blob=json.dumps(answers[0],sort_keys=True).encode();out[kind]={'faces':len(answers[0][0]),'certificateAttempts':len(traces[0]),'earlyRefusals':earlyCount,'orientedFaceSequenceAndProofsSHA256':hashlib.sha256(blob).hexdigest(),'oldWork':costs[0],'newWork':costs[1],'allDecisionsIdentical':True,'acceptedCertificatesExact':True,'ledgerUnchanged':True}
 out['scope']='Analytic controls only; no photographic frequency estimate or quality claim; original per-stage caps unchanged.'
 return out
