"""Synthetic parser/index binding controls, NOT a photographic pipeline result."""
import pathlib,json,shutil
from ledger_adapter import *

def make_case(path,ncam):
    path.mkdir();names={k:path/(k+'.txt') for k in ('samples','cameras','dimensions','triangles','witnesses','prepared','accumulators','memberships')}
    names['dimensions'].write_text(f'{ncam} 32 32\n')
    cameras=[];samples=[];tri=[];prepared=[];witness=[]
    ph='sourceId camera x y px py pz r g b confidence supportingViews verifiedNx verifiedNy verifiedNz verifiedNormalConfidence verifiedNormalViews validity evidenceNx evidenceNy evidenceNz preparedSupported preparedNx preparedNy preparedNz frontRadius bound planeResidual conditioning estimatedPositionSigma maximumParallaxRadians triangulationSigmaAvailable triangulationSigma calibrated effectiveSupport supportIdCount supportIds...'.split()
    ah='accumulatorId supportedNormal weightedPx weightedPy weightedPz weightedNx weightedNy weightedNz weightedR weightedG weightedB weight maxConfidence viewMask fusionRadius radiusLevel cellX cellY cellZ px py pz r g b confidence supportingViews preFaceNx preFaceNy preFaceNz sourceCount sourceIds...'.split()
    # Binary-exact increments remove irrelevant rounding ambiguity in this parser control.
    points=[(1.25,1.25,1),(1.5,1.25,1),(1.25,1.5,1),(1.5,1.5,1)]
    for cam in range(ncam):
        # fx8, baseline1/8 -> exact integer source pixels for every camera.
        cameras.append([1,0,0,0,1,0,0,0,1,-cam/8,0,0,8,8,0,0])
        for j,p in enumerate(points):
            sid=4*cam+j;x=int(p[0]*8)-cam;y=int(p[1]*8);samples.append([cam,x,y,*p,1,ncam])
            d={k:'0' for k in ph[:36]};d.update(sourceId=str(sid),camera=str(cam),x=str(x),y=str(y),px=str(p[0]),py=str(p[1]),pz=str(p[2]),r='40',g='50',b='60',confidence='1',supportingViews=str(ncam),validity='2',preparedSupported='0',supportIdCount='0')
            if j!=3:
                d.update(preparedSupported='1',preparedNz='-1',evidenceNz='-1',validity='0',effectiveSupport='9',supportIdCount='9')
                support=[(y+dy)*32+x+dx for dy in (-2,0,2) for dx in (-2,0,2)]
            else:support=[]
            prepared.append(' '.join([*(d[k] for k in ph[:36]),*map(str,support)]))
        tri.extend([[cam,4*cam,4*cam+1,4*cam+3],[cam,4*cam,4*cam+3,4*cam+2]])
    for cam in range(ncam-1):
        for j in range(4):
            a=samples[4*cam+j][:3];b=samples[4*(cam+1)+j][:3]
            witness.extend([a+b+[0,0,0,1,0,0,0],b+a+[0,0,0,1,0,0,0]])
    memberships=[];acc=[]
    # Match native XYZ/camera source-order: three supported fused accumulators,
    # then one unsupported singleton per camera. Unknown points are never merged.
    groups=[(j,[4*c+j for c in range(ncam)],True) for j in (0,2,1)]+[(3,[4*c+3],False) for c in range(ncam)]
    for aid,(j,ids,supported) in enumerate(groups):
        p=points[j];weight=len(ids)*ncam;mask=sum(1<<(sid//4) for sid in ids);d={k:'0' for k in ah[:31]}
        d.update(accumulatorId=str(aid),supportedNormal=str(int(supported)),px=str(p[0]),py=str(p[1]),pz=str(p[2]),r='40',g='50',b='60',confidence='1',maxConfidence='1',supportingViews=str(len(ids)),sourceCount=str(len(ids)),weight=str(weight),weightedPx=str(p[0]*weight),weightedPy=str(p[1]*weight),weightedPz=str(p[2]*weight),weightedNz=str(-weight if supported else 0),weightedR=str(40*weight),weightedG=str(50*weight),weightedB=str(60*weight),viewMask=str(mask))
        acc.append(' '.join([*(d[k] for k in ah[:31]),*map(str,ids)]))
        for sid in ids:memberships.append([len(memberships),aid,sid,ncam])
    for key,rr in [('cameras',cameras),('samples',samples),('triangles',tri),('witnesses',witness)]:names[key].write_text(''.join(' '.join(map(str,r))+'\n' for r in rr))
    names['prepared'].write_text(' '.join(ph)+'\n'+'\n'.join(prepared)+'\n');names['accumulators'].write_text(' '.join(ah)+'\n'+'\n'.join(acc)+'\n');names['memberships'].write_text('sequence accumulatorId sourceId actualWeight\n'+''.join(' '.join(map(str,r))+'\n' for r in memberships))
    return {k:{'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p)} for k,p in names.items()}

def run(path,B):
    outcomes={}
    for ncam in (2,4,8):
        pins=make_case(path/f'views-{ncam}',ncam);ledger=Ledger(pins,B,F(1,4),max_views=8)
        need(len(ledger.points)==3+ncam and sum(ledger.unknown)==ncam and len(ledger.samples)==4*ncam and len(ledger.membership_rows)==4*ncam,'ALL_LEDGER_RETENTION')
        need(sum(map(len,ledger.point_index.values()))==3+ncam and ledger.component_counts=={0:2*ncam},'INDEX_COMPONENT_ACCOUNTING')
        need(all(d['r']=='40' and d['g']=='50' and d['b']=='60' and d['confidence']=='1' for d,raw in ledger.attributes),'ATTRIBUTE_RETENTION')
        # Verify exact bbox index enumeration independently against all triangles.
        for cam in range(ncam):
            got,q=ledger.overlapping(cam,ledger.points[:3]);xy=[ledger.pixel(cam,p) for p in q];box=(min(p[0] for p in xy),max(p[0] for p in xy),min(p[1] for p in xy),max(p[1] for p in xy))
            expected=[i for i,((c,ids),b) in enumerate(zip(ledger.triangles,ledger.bounds)) if c==cam and b[0]<=box[1] and b[1]>=box[0] and b[2]<=box[3] and b[3]>=box[2]]
            need(got==expected,'INDEX_COMPLETENESS')
        cert=ledger.candidate_certificate((0,1,2));need(cert['accepted_support_hypothesis'],'ANALYTIC_CERTIFICATE')
        need(len(cert['contributingSourceIds'])>=ncam,'ALL_VIEWS_CONTRIBUTING')
        ledger.verify();outcomes[str(ncam)]={'observations':len(ledger.samples),'accumulators':len(ledger.points),'unknownNormalsRetained':sum(ledger.unknown),'memberships':len(ledger.membership_rows),'sourceTriangles':len(ledger.triangles),'components':ledger.component_counts,'candidateSupportHypothesis':True,'inputHashesUnchanged':True}
    negative={}
    for name,field,value in [('attribute','r','41'),('postFaceNormal','preFaceNx','1')]:
        pp=make_case(path/('negative-'+name),2);target=pathlib.Path(pp['accumulators']['path']);lines=target.read_text().splitlines();header=lines[0].split();row=lines[1].split();row[header.index(field)]=value;lines[1]=' '.join(row);target.write_text('\n'.join(lines)+'\n');pp['accumulators'].update(bytes=target.stat().st_size,sha256=sha(target))
        try:Ledger(pp,B,F(1,4))
        except Refusal as e:negative[name]=str(e)
        else:raise RuntimeError('invalid ledger accepted '+name)
    pp=make_case(path/'negative-hash',2);target=pathlib.Path(pp['samples']['path']);target.write_text(target.read_text()+'\n')
    try:Ledger(pp,B,F(1,4))
    except Refusal as e:negative['hash']=str(e)
    else:raise RuntimeError('input mutation accepted')
    pp=make_case(path/'negative-work',2)
    try:Ledger(pp,Budget(clips=0),F(1,4))
    except Refusal as e:negative['work']=str(e)
    else:raise RuntimeError('work overflow accepted')
    pp=make_case(path/'unsupported-nonzero-vector',2);target=pathlib.Path(pp['prepared']['path']);lines=target.read_text().splitlines();header=lines[0].split()
    for sid in (3,7):
        row=lines[sid+1].split();row[header.index('preparedNz')]='-1';lines[sid+1]=' '.join(row)
    target.write_text('\n'.join(lines)+'\n');pp['prepared'].update(bytes=target.stat().st_size,sha256=sha(target));gated=Ledger(pp,B,F(1,4));need(sum(gated.unknown)==2,'unsupported-vector gating')
    pp=make_case(path/'negative-unknown-merge',2)
    for key in ('prepared','accumulators'):
        target=pathlib.Path(pp[key]['path']);lines=target.read_text().splitlines();header=lines[0].split()
        indices=(1,5) if key=='prepared' else (1,)
        for index in indices:
            row=lines[index].split()
            if key=='prepared':row[header.index('preparedSupported')]='0'
            else:row[header.index('supportedNormal')]='0';row[header.index('weightedNz')]='0'
            lines[index]=' '.join(row)
        target.write_text('\n'.join(lines)+'\n');pp[key].update(bytes=target.stat().st_size,sha256=sha(target))
    try:Ledger(pp,B,F(1,4))
    except Refusal as e:negative['unknownMerge']=str(e);need(str(e)=='UNSUPPORTED_ACCUMULATOR_NOT_SINGLETON','wrong unknown merge refusal')
    else:raise RuntimeError('unknown-camera observations merged')
    return {'scope':'synthetic calibrated ledger/parser/index controls only; no production or photographic result','cases':outcomes,'negativeControls':negative,'unsupportedNonzeroPreparedVectorIgnoredInAccumulator':True}
