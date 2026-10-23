"""Standalone QA of emitted face winding under the five declared permutations.
Canonicalizes cyclic rotations only; it never identifies a triangle with its
reversed winding. Does not alter geometry or supersede native topology checks.
"""
from pathlib import Path
import copy,hashlib,json,math,sys,tempfile

def require(condition,message):
    if not condition:raise AssertionError(message)

def oriented_geometry(data):
    vertices=data['vertices'];triangles=data['triangles']
    require(len(vertices)<=40000 and len(triangles)<=80000,'QA output budget exceeded')
    require(all(len(p)==3 and all(type(x) in (int,float) and math.isfinite(x) for x in p) for p in vertices),'Nonfinite or malformed vertex')
    result=[]
    for indices in triangles:
        require(len(indices)==3 and all(type(i) is int and 0<=i<len(vertices) for i in indices),'Malformed face')
        face=tuple(tuple(vertices[i]) for i in indices)
        result.append(min(face,face[1:]+face[:1],face[2:]+face[:2]))
    return sorted(result)

def write_receipt(path,report):
    # Exclusive creation is the final authority; an earlier existence check
    # alone cannot prevent a concurrent creator from losing its evidence.
    with path.open('x') as stream:stream.write(json.dumps(report,indent=2)+'\n')

def main():
    require(len(sys.argv)==3,'Usage: check_oriented_permutation.py REPLAY_KERNEL NEW_RECEIPT_JSON')
    root=Path(sys.argv[1]).resolve();out=Path(sys.argv[2]).resolve()
    require(not out.exists(),'Receipt already exists; nothing overwritten')
    pairs=[('translated_permuted','results/translated-candidate.json','results/permuted-candidate.json')]
    pairs += [(name,f'extended/{name}-forward-candidate.json',f'extended/{name}-reverse-candidate.json') for name in ['complementary','hole','step','curved_identical']]
    report={'scope':'Exact emitted coordinate equality preserving oriented face cycles; no attribute or production claim','checkerSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'pythonOptimized':not __debug__,'pairs':{},'controls':{}}
    for name,left,right in pairs:
        lp,rp=root/left,root/right
        a=json.loads(lp.read_text());b=json.loads(rp.read_text())
        require(oriented_geometry(a)==oriented_geometry(b),name+': oriented triangle-coordinate sets differ')
        report['pairs'][name]={'orientedCyclicTriangleCoordinateSetsExactlyEqual':True,'faces':len(a['triangles']),'leftSha256':hashlib.sha256(lp.read_bytes()).hexdigest(),'rightSha256':hashlib.sha256(rp.read_bytes()).hexdigest()}
    original=json.loads((root/'results/translated-candidate.json').read_text())
    cyclic=copy.deepcopy(original)
    for i,face in enumerate(cyclic['triangles']):cyclic['triangles'][i]=face[1:]+face[:1]
    require(oriented_geometry(original)==oriented_geometry(cyclic),'Cyclic face rotation incorrectly rejected')
    reverse=copy.deepcopy(original);reverse['triangles'][0].reverse()
    require(oriented_geometry(original)!=oriented_geometry(reverse),'Single-face winding reversal incorrectly accepted')
    report['controls']={'allFaceCyclicRotationsPreserveEquality':True,'singleFaceWindingReversalDetected':True}
    for value in (False,True):
        for field in ('index','coordinate'):
            malformed=copy.deepcopy(original)
            if field=='index':malformed['triangles'][0][0]=value
            else:malformed['vertices'][0][0]=value
            expected='Malformed face' if field=='index' else 'Nonfinite or malformed vertex'
            try:oriented_geometry(malformed)
            except AssertionError as error:require(str(error)==expected,'Wrong malformed-boolean rejection')
            else:raise AssertionError('Boolean '+field+' incorrectly accepted')
            report['controls']['boolean_'+field+'_'+str(value).lower()+'_rejected']=True
    with tempfile.TemporaryDirectory(prefix='oriented-receipt-') as directory:
        existing=Path(directory)/'receipt.json';sentinel=b'preserve existing evidence\n';existing.write_bytes(sentinel)
        try:write_receipt(existing,report)
        except FileExistsError:pass
        else:raise AssertionError('Exclusive receipt creation overwrote existing file')
        require(existing.read_bytes()==sentinel,'Existing receipt bytes changed')
    report['controls']['exclusiveCreationPreservesExistingReceipt']=True
    write_receipt(out,report);print(json.dumps(report,indent=2))
if __name__=='__main__':main()
