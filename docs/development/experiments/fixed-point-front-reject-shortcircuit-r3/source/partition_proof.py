"""Separate read-only equivalence proof. No front, budget resets or geometry output."""
import pathlib,json,sys,resource,random
from exact_geometry import Budget,Refusal,F
from camera_partition import build,expected
from bridge import convert,verify
from ledger_adapter import Ledger
from witness_audit import need

def controls():
 triangles=[(i%4,(3*i,3*i+1,3*i+2)) for i in range(24)];boxes=[(F(i%3),F(i%3+1),F(i//3),F(i//3+1)) for i in range(24)];identity=[(i,2*i,0,c,*ids) for i,(c,ids) in enumerate(triangles)];B=Budget();parts=build(triangles,boxes,identity,4,B);random.seed(7341);count=0
 for cam in range(4):
  for _ in range(100):
   x,y=F(random.randrange(-3,10),2),F(random.randrange(-3,20),2);box=(x,x+1,y,y+1);got=expected(parts,cam,box,4,B);old=[i for i,((c,_),b) in enumerate(zip(triangles,boxes)) if c==cam and b[0]<=box[1] and b[1]>=box[0] and b[2]<=box[3] and b[3]>=box[2]];need(got==old,'CONTROL_PARTITION_EQUIVALENCE');count+=1
 negatives={}
 def refuses(name,fn):
  try:fn()
  except Refusal as e:negatives[name]=str(e)
  else:raise RuntimeError('partition invalid input accepted:'+name)
 refuses('missingCameraPartition',lambda:expected(parts[:-1],3,(0,1,0,1),4,B));dup=list(identity);dup[1]=(1,identity[0][1],*identity[1][2:]);refuses('duplicateOriginalId',lambda:build(triangles,boxes,dup,4,B));missing=list(identity[:-1]);refuses('missingIdentity',lambda:build(triangles,boxes,missing,4,B));bad=list(triangles);bad[0]=(4,bad[0][1]);refuses('cameraDomain',lambda:build(bad,boxes,identity,4,B));refuses('queryCameraDomain',lambda:expected(parts,4,(0,1,0,1),4,B));refuses('invertedBounds',lambda:expected(parts,0,(1,0,0,1),4,B));refuses('nonfiniteBounds',lambda:expected(parts,0,(0,float('inf'),0,1),4,B));refuses('floatBounds',lambda:expected(parts,0,(0.,1.,0.,1.),4,B));badcorners=list(triangles);badcorners[0]=(0,(0,0,2));badids=list(identity);badids[0]=(0,0,0,0,0,0,2);refuses('triangleDomain',lambda:build(badcorners,boxes,badids,4,B));refuses('workBudget',lambda:build(triangles,boxes,identity,4,Budget(clips=0)))
 # A present but empty camera partition is legal and exhaustively empty;
 # silently omitting that partition is not the same contract.
 empty=build([(0,(0,1,2))],[(0,1,0,1)],[(0,0,0,0,0,1,2)],2,B);need(expected(empty,1,(0,1,0,1),2,B)==[],'EMPTY_CAMERA_PARTITION')
 return {'queries':count,'allEquivalent':True,'negatives':negatives,'presentEmptyCameraRetained':True}

def main():
 package,run,out=map(pathlib.Path,sys.argv[1:]);need(not out.exists(),'PROOF_FRESH');out.mkdir();need(resource.getrlimit(resource.RLIMIT_AS)[0]<=1073741824 and resource.getrlimit(resource.RLIMIT_CPU)[0]<=600,'PROOF_CAPS');pins=json.loads((package/'input-pins.json').read_text())['files'];cfg=json.loads((package/'config.json').read_text());B=Budget(cfg['maxClippingChecks'],cfg['maxBallChecks'],cfg['maxPartitionFragments'],cfg['maxRationalBits'])
 try:
  bp,rho,facts,identity=convert(run,out/'bridge',pins);ledger=Ledger(bp,B,rho,max_views=8,normal_step=2);prefix=B.used_clips;parts=build(ledger.triangles,ledger.bounds,identity,ledger.ncam,B);construction=B.used_clips-prefix;results=[];bruteVisits=optimizedVisits=0
  for cam in range(8):
   ids=[i for i,r in enumerate(identity) if r[3]==cam]
   for i in (ids[0],ids[len(ids)//2],ids[-1]):
    _,corners=ledger.triangles[i];points=[ledger.samples[j][3] for j in corners]
    for view in range(8):
     indexed,q=ledger.overlapping(view,points);xy=[ledger.pixel(view,v) for v in q];box=(min(v[0] for v in xy),max(v[0] for v in xy),min(v[1] for v in xy),max(v[1] for v in xy));before=B.used_clips;new=expected(parts,view,box,8,B);optimizedVisits+=B.used_clips-before;old=[]
     for k,((c,_),b) in enumerate(zip(ledger.triangles,ledger.bounds)):
      B.clip();bruteVisits+=1
      if c==view and b[0]<=box[1] and b[1]>=box[0] and b[2]<=box[3] and b[3]>=box[2]:old.append(k)
     need(old==new==indexed,'FULL_FIXED_QUERY_EQUIVALENCE');results.append({'sourceCompactId':i,'sourceOriginalAttemptId':identity[i][1],'camera':view,'matchingCompactIds':old,'matchingOriginalAttemptIds':[identity[j][1] for j in old]})
  verify(run,pins);ledger.verify();need(len(results)==192,'ALL_FIXED_QUERIES');result={'status':'EXACT_OLD_NEW_INDEX_SETS_EQUAL','allViews':8,'queries':192,'sourceTriangles':len(ledger.triangles),'partitionCounts':[len(p) for p in parts],'partitionConstructionVisits':construction,'optimizedValidationVisitsIncludingQueryChecks':optimizedVisits,'originalValidationVisits':bruteVisits,'savedVisits':bruteVisits-construction-optimizedVisits,'prefixLedgerVisits':prefix,'totalProofVisits':B.used_clips,'inputHashesUnchanged':True,'scope':'separate equivalence proof only; no front or mesh; whole successor still has one unchanged 5M shared cap','matches':results}
  data=(json.dumps(result,indent=2)+'\n').encode();need(len(data)<=16777216,'PROOF_OUTPUT_BOUND');(out/'proof.json').write_bytes(data);print(json.dumps({k:v for k,v in result.items() if k!='matches'}))
 except BaseException as e:(out/'REFUSED.json').write_text(json.dumps({'error':type(e).__name__,'reason':str(e),'clips':B.used_clips,'geometry':'NOT_RUN'},indent=2)+'\n');raise
if __name__=='__main__':main()
