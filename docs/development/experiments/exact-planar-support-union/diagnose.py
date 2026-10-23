import json,subprocess
import numpy as np
from pathlib import Path
from make_controls import fixture
from original_overlay import overlay
from shapely import unary_union
root=Path(__file__).resolve().parent
out=overlay(fixture('complementary'));uv=np.array(out['_chartVertices']);xyz=np.array(out['vertices']);f=np.array(out['triangles']);cross=np.linalg.norm(np.cross(xyz[f[:,1]]-xyz[f[:,0]],xyz[f[:,2]]-xyz[f[:,0]]),axis=1)
small=np.where(cross<1e-12)[0]
report={'vertices':len(xyz),'faces':len(f),'smallCrossCount':len(small),'crossRange':[float(cross.min()),float(cross.max())], 'examples':[{'faceIndex':int(i),'cross':float(cross[i]),'chart':uv[f[i]].tolist(),'xyz':xyz[f[i]].tolist(),'contributors':out['faceSourceTriangles'][i]} for i in small[:10]], 'union':unary_union(out['_chartPolygons']).wkt}
mesh=root/'original-complementary.mesh';mesh.write_text(f'{len(xyz)} {len(f)}\n'+'\n'.join(' '.join(map(str,p)) for p in xyz)+'\n'+'\n'.join(' '.join(map(str,t)) for t in f)+'\n');report['native']=json.loads(subprocess.check_output([root/'native_topology',mesh]));(root/'diagnosis.json').write_text(json.dumps(report,indent=2));print(json.dumps({k:v for k,v in report.items() if k not in ['examples','union']},indent=2))
