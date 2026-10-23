"""Lossless sealed native-to-R3 schema bridge, not normal/witness estimation."""
import pathlib,json,struct,hashlib,math,statistics
from fractions import Fraction as F
from witness_audit import Reader,need
from ledger_adapter import Ledger
PH='sourceId camera x y px py pz r g b confidence supportingViews verifiedNx verifiedNy verifiedNz verifiedNormalConfidence verifiedNormalViews validity evidenceNx evidenceNy evidenceNz preparedSupported preparedNx preparedNy preparedNz frontRadius bound planeResidual conditioning estimatedPositionSigma maximumParallaxRadians triangulationSigmaAvailable triangulationSigma calibrated effectiveSupport supportIdCount supportIds...'
AH='accumulatorId supportedNormal weightedPx weightedPy weightedPz weightedNx weightedNy weightedNz weightedR weightedG weightedB weight maxConfidence viewMask fusionRadius radiusLevel cellX cellY cellZ px py pz r g b confidence supportingViews preFaceNx preFaceNy preFaceNz sourceCount sourceIds...'
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def verify(run,pins):
 for name,h in pins.items():need((run/name).is_file() and sha(run/name)==h,'SEALED_INPUT:'+name)
def convert(run,out,pins):
 verify(run,pins);need(not out.exists(),'BRIDGE_FRESH_OUTPUT');out.mkdir();p=run/'observer'
 prep=[r.split() for r in (p/'prepared.tsv').read_text().splitlines()];acc=[r.split() for r in (p/'accumulators.tsv').read_text().splitlines()];need(len(prep)==9172 and len(acc)==4855,'FROZEN_COUNTS')
 names={k:out/(k+'.txt') for k in ('samples','cameras','dimensions','triangles','witnesses','prepared','accumulators','memberships')}
 names['dimensions'].write_text('8 160 120\n');names['samples'].write_text(''.join(' '.join(r[1:7]+r[10:12])+'\n' for r in prep))
 inp=Reader(p/'inputs.bits');need(inp.integer()==8,'CAMERAS');cams=[]
 for _ in range(8):need(inp.take('QQ')==(160,120),'DIMS');cams.append(inp.take('16d'));need(inp.integer()==76800,'RGBA');inp.skip(76800)
 need(inp.pos==len(inp.data),'INPUT_EOF');names['cameras'].write_text(''.join(' '.join(map(repr,c))+'\n' for c in cams))
 names['prepared'].write_text(PH+'\n'+(p/'prepared.tsv').read_text());names['accumulators'].write_text(AH+'\n'+(p/'accumulators.tsv').read_text());names['memberships'].write_text('sequence accumulatorId sourceId actualWeight\n'+(p/'memberships.tsv').read_text())
 triangles=[];identity=[];edges=set();records=(p/'derived-support.tsv').read_text().splitlines();need(records[0]=='attemptId cellId camera source0 source1 source2 status derivedFlip','DERIVED_SCHEMA')
 for line in records[1:]:
  r=line.split();need(len(r)==8,'DERIVED_ROW')
  if r[6]!='DERIVED_SUPPORT':continue
  attempt,cell,cam,a,b,c=map(int,r[:6]);flip=int(r[7]);need(flip in (0,1),'DERIVED_FLIP')
  if flip:b,c=c,b
  identity.append((len(triangles),attempt,cell,cam,a,b,c));triangles.append((cam,a,b,c))
  for x,y in ((a,b),(b,c),(c,a)):
   ca,px,py=map(int,prep[x][1:4]);cb,qx,qy=map(int,prep[y][1:4]);need(ca==cb==cam,'CAMERA_BINDING')
   if (abs(px-qx)==2 and py==qy) or (abs(py-qy)==2 and px==qx):edges.add((cam,min(x,y),max(x,y),0 if py==qy else 1))
 need(len(triangles)==15726 and edges,'TRIANGLE_EDGE_BINDING');names['triangles'].write_text(''.join(' '.join(map(str,r))+'\n' for r in triangles))
 (out/'triangle-identities.tsv').write_text('compactId originalAttempt cell camera source0 source1 source2\n'+''.join(' '.join(map(str,r))+'\n' for r in identity))
 lengths=[];rows=[]
 for cam,a,b,axis in sorted(edges):
  x=list(map(float,prep[a][4:7]));y=list(map(float,prep[b][4:7]));d=math.sqrt(sum((x[i]-y[i])**2 for i in range(3)));need(math.isfinite(d) and d>0,'SAMPLING_EDGE');lengths.append(d);rows.append((cam,a,b,axis,d))
 h=statistics.median(lengths);need(math.isfinite(h) and h>0,'SAMPLING_H');(out/'sampling-edges.tsv').write_text('camera source0 source1 axis length\n'+''.join(' '.join(map(str,r))+'\n' for r in rows))
 # R3 needs only observed source pixel identity and native status. The other
 # legacy columns are zero unavailable placeholders, never fabricated costs.
 data=(p/'witness.bin').read_bytes();need(len(data)%192==0,'WITNESS_WIDTH');wr=list(struct.iter_unpack('<24Q',data));names['witnesses'].write_text(''.join(' '.join(map(str,(r[1],r[3],r[4],r[2],r[6],r[7],r[5],0,0,0,0,0,0)))+'\n' for r in wr))
 # All rich actual evidence remains immutable under input pins, not lost in projection.
 facts={'allNativeWitnessRecords':len(wr),'directedConfirmations':sum(r[5]==0 for r in wr),'strictReverseDirected':17920,'allObservations':len(prep),'allPoints':len(acc),'sourceTriangles':len(triangles),'samplingEdges':len(edges),'h':h,'rho':h,'hHex':h.hex(),'axisCameraStats':{f'{cam}:{axis}':{'count':len(v),'min':min(v),'median':statistics.median(v),'max':max(v)} for cam in range(8) for axis in range(2) if (v:=[r[4] for r in rows if r[0]==cam and r[3]==axis])},'inputHashesUnchanged':True,'unknownPointNormals':'RETAINED_NOT_ESTIMATED','triangleMapping':'explicit original attempt IDs; native winding not assumed','witnessLegacyExtraFields':'UNAVAILABLE_ZERO_PLACEHOLDERS_NOT_COSTS'}
 (out/'bridge-summary.json').write_text(json.dumps(facts,indent=2)+'\n');verify(run,pins)
 return {k:{'path':str(v),'sha256':sha(v),'bytes':v.stat().st_size} for k,v in names.items()},F(h),facts,identity

def validate_index(ledger,identity):
 from camera_partition import build,expected as partition_expected
 partitions=build(ledger.triangles,ledger.bounds,identity,ledger.ncam,ledger.B)
 selected=[]
 for cam in range(8):
  ids=[i for i,r in enumerate(identity) if r[3]==cam]
  for i in (ids[0],ids[len(ids)//2],ids[-1]):
   _,corners=ledger.triangles[i];points=[ledger.samples[j][3] for j in corners]
   for view in range(8):
    got,q=ledger.overlapping(view,points);xy=[ledger.pixel(view,v) for v in q];box=(min(v[0] for v in xy),max(v[0] for v in xy),min(v[1] for v in xy),max(v[1] for v in xy))
    expected=partition_expected(partitions,view,box,ledger.ncam,ledger.B)
    need(got==expected,'CALIBRATED_INDEX_COMPLETENESS')
    selected.append((i,view,len(got)))
 return {'queries':len(selected),'checks':selected,'selection':'first/middle/last original admitted attempt per camera, against all 8 views; no truth'}
