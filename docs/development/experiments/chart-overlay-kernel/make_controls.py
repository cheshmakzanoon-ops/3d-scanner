import math,json
from pathlib import Path
import numpy as np

def fixture(name):
    shift=.01 if name in ['translated','curved_translated','permuted'] else .5 if name=='complementary' else 0
    rotate=name=='rotated'; partial=name=='partial';curved=name.startswith('curved');hole=name=='hole';step=name=='step';thin=name=='thin_sheets'
    cameras=[];observations=[];triangles=[]
    for cam in range(2):
        R=np.array([[0,-1,0],[1,0,0],[0,0,1.]]) if cam and rotate else np.eye(3)
        t=np.array([-cam*shift,0.,0.]);c={'id':cam,'rotation':R.flatten().tolist(),'translation':t.tolist(),'fx':100.,'fy':100.,'cx':19.,'cy':19.};cameras.append(c);grid={}
        for y in range(3,37,2):
            for x in range(3,37,2):
                if partial and cam and x<19:continue
                ray=R.T@np.array([(x-19)/100,(y-19)/100,1.]);origin=-R.T@t;z=5.
                if curved:
                    A=.004*(ray[0]**2+ray[1]**2);B=.008*(ray[0]*origin[0]+ray[1]*origin[1])-1;C=5+.004*(origin[0]**2+origin[1]**2)
                    z=2*C/(-B+math.sqrt(B*B-4*A*C)) if A else -C/B
                if step:z=5. if ray[0]<0 else 6.
                if thin:z=5.+cam*.02
                p=origin+ray*z
                if hole and abs(p[0])<.21 and abs(p[1])<.21:continue
                record={'id':f'{cam}:{x}:{y}','view':cam,'pixel':[x,y],'xyz':p.tolist(),'confidence':.9,'supportingViews':2,'depth':z}
                grid[x,y]=record;observations.append(record)
        for y in range(3,35,2):
            for x in range(3,35,2):
                keys=[(x,y),(x+2,y),(x,y+2),(x+2,y+2)]
                if any(k not in grid for k in keys):continue
                q=[grid[k] for k in keys]
                # Same source-cell admission as native before cross-view fusion.
                d03=math.dist(q[0]['xyz'],q[3]['xyz']);d12=math.dist(q[1]['xyz'],q[2]['xyz'])
                key03=sorted([q[0]['xyz'],q[3]['xyz']]);key12=sorted([q[1]['xyz'],q[2]['xyz']])
                triples=[(0,1,3),(0,3,2)] if d03<d12 or (d03==d12 and key03<key12) else [(0,1,2),(1,3,2)]
                for triple in triples:
                    qs=[q[i] for i in triple];depths=[v['depth'] for v in qs]
                    if (max(depths)-min(depths))/max(depths)>.08:continue
                    triangles.append({'view':cam,'sources':[v['id'] for v in qs]})
    if name=='permuted':cameras.reverse();observations.reverse();triangles.reverse()
    return {'label':'synthetic direct-depth unit control','cameras':cameras,'observations':observations,'triangles':triangles}

def write_native(data,path):
    cams=sorted(data['cameras'],key=lambda c:c['id']);lines=['2 40 40']
    for c in cams:lines.append(' '.join(map(str,[c['fx'],c['fy'],c['cx'],c['cy'],*c['rotation'],*c['translation']])))
    lines.append(str(len(data['observations'])))
    for o in data['observations']:lines.append(' '.join(map(str,[o['view'],*o['pixel'],*o['xyz'],o['depth']])))
    Path(path).write_text('\n'.join(lines)+'\n')
