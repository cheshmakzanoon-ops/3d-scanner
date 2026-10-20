"""Artifact-bound per-scene photographic evaluation with independent held-out masks."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import numpy as np
from PIL import Image
from .assets import load
from .metrics import uv_metrics,alpha_metrics,render,held_out_metrics,geometry_retention
from tools.qa.geometry_quality.__main__ import load as load_geometry


def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def image(path):
    with Image.open(path) as decoded:
        if decoded.width*decoded.height>16000000:raise ValueError('comparison image pixel budget')
        return np.asarray(decoded.convert('RGBA'),float)/255


def mask(path):
    with Image.open(path) as decoded:return np.asarray(decoded.convert('L'))>0


def review(path,predicted,reference,visible):
    """Scientific error visualization, with unknowns magenta; no image alignment."""
    delta=np.mean(np.abs(predicted[:,:,:3]-reference[:,:,:3]),axis=2)
    heat=np.zeros_like(reference);heat[:,:,0]=np.clip(delta*4,0,1);heat[:,:,3]=1
    heat[visible&(predicted[:,:,3]<.99)]=[1,0,1,1]
    panels=[]
    for pixels in (reference,predicted,heat):
        tile=Image.fromarray(np.rint(np.clip(pixels,0,1)*255).astype('uint8'),'RGBA');tile.thumbnail((512,512));panels.append(tile)
    board=Image.new('RGBA',(sum(p.width for p in panels),max(p.height for p in panels)),(32,32,32,255));x=0
    for p in panels:board.paste(p,(x,0));x+=p.width
    board.save(path)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('descriptor',type=Path);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();root=args.descriptor.parent;descriptor=json.loads(args.descriptor.read_text())
    protocol=Path(__file__).resolve().parents[3]/'benchmarks/quality_protocols/texture.json';config=json.loads(protocol.read_text())
    receipts=[];physical_objects=set()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    for index,case in enumerate(descriptor['scenes']):
        try:
            glb,original,settings,engine=[root/case[k] for k in ('candidate','original_mesh','settings','engine_artifact')]
            source=case['candidate_source_sha']
            if not isinstance(source,str) or len(source)!=40 or any(c not in '0123456789abcdef' for c in source):raise ValueError('invalid candidate source SHA')
            if case['kind'] not in ('real','synthetic'):raise ValueError('unknown data kind')
            expected=root/case['expected_png'] if 'expected_png' in case else None
            v,f,uv,tex,asset=load(glb,expected)
            original_vertices,original_faces=load_geometry(original)
            result=uv_metrics(v,f,uv,tex,original_face_count=len(original_faces),overlap_budget=config['overlap_face_budget'])
            result['geometry_retention']=geometry_retention(v,f,original_vertices,original_faces)
            failures=[];held=[];manifest=None;manifest_identity=None
            if 'manifest' in case:
                from tools.qa.corpus.manifest import validate
                manifest_path=root/case['manifest'];manifest=json.loads(manifest_path.read_text());validated=validate(manifest,manifest_path.parent)
                manifest_identity=validated['manifest_sha256']
                if manifest['kind']!=case['kind']:raise ValueError('corpus kind mismatch')
            elif case['kind']=='real':raise ValueError('real evaluation needs validated corpus v1 manifest')
            if 'independent_atlas_support_mask' in case:
                mask_path=root/case['independent_atlas_support_mask']
                result['alpha']=alpha_metrics(tex,mask(mask_path));result['alpha_mask_sha256']=digest(mask_path)
                if result['alpha']['opaque_unknown_pixels']>config['opaque_unknown_pixels_max']:failures.append('opaque_unknown')
            for hidx,view in enumerate(case.get('held_out',[])):
                photo=root/view['photo'];visibility=root/view['independent_visible_mask'];truth=image(photo)
                if manifest is not None:
                    matches=[p for p in manifest['photos'] if p['id']==view['photo_id']]
                    if len(matches)!=1 or matches[0]['role']!='held_out' or digest(photo)!=matches[0]['sha256']:raise ValueError('held-out photograph not isolated from reconstruction inputs')
                elif case['kind']=='real':raise ValueError('held-out real photograph lacks corpus provenance')
                predicted,_=render(v,f,uv,tex,truth.shape[1],truth.shape[0],**view['camera'])
                visible=mask(visibility);measured=held_out_metrics(predicted,truth,visible)
                visual=args.output.parent/f'review-{index}-{hidx}.png';review(visual,predicted,truth,visible)
                held.append({'metrics':measured,'photo_sha256':digest(photo),'visibility_sha256':digest(visibility),'camera_sha256':hashlib.sha256(json.dumps(view['camera'],sort_keys=True).encode()).hexdigest(),'visual_review':str(visual),'visual_sha256':digest(visual)})
                if measured['held_out_support_fraction']<config['held_out_support_fraction_min'] or measured['linear_rgb_rmse'] is None or measured['linear_rgb_rmse']>config['held_out_linear_rgb_rmse_max']:failures.append('held_out_appearance')
            for key in ('uv_out_of_bounds_vertices','degenerate_uv_faces','uv_overlap_pairs'):
                if result[key] is None or result[key]>config[key+'_max']:failures.append(key)
            if result['supported_face_fraction_original']<config['supported_face_fraction_original_min']:failures.append('face_support')
            if result['distortion_anisotropy_p95'] is None or result['distortion_anisotropy_p95']>config['distortion_anisotropy_p95_max']:failures.append('UV_distortion')
            if result['seam_linear_rgb_abs_p95'] is not None and result['seam_linear_rgb_abs_p95']>config['seam_linear_rgb_abs_p95_max']:failures.append('seam_discontinuity')
            if asset['opaque_material_with_transparent_texels']:failures.append('opaque_material_hides_unknown_alpha')
            if manifest and manifest['kind']=='real' and manifest['scene']=='matte_textured' and manifest['capture'].get('coverage_degrees')==360 and held:
                physical_objects.add(manifest['dataset_id'])
            receipts.append({'id':case['id'],'status':'FAIL' if failures else 'PASS','failures':failures,'metrics':result,'asset':asset,'held_out':held,
                             'held_out_status':'CHECKED' if held else 'NOT_RUN','candidate_sha256':digest(glb),'original_mesh_sha256':digest(original),
                             'settings_sha256':digest(settings),'engine_sha256':digest(engine),'manifest_sha256':manifest_identity,'candidate_source_sha':source})
        except (ValueError,KeyError,OSError,ImportError) as exc:receipts.append({'id':case.get('id'),'status':'FAIL','error':str(exc)})
    status='FAIL' if not receipts or any(r['status']=='FAIL' for r in receipts) else 'PASS'
    report={'schema_version':1,'source_sha':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'protocol_sha256':digest(protocol),'descriptor_sha256':digest(args.descriptor),
            'status':status,'scenes':receipts,'physical_gate':status if len(physical_objects)>=2 else 'BLOCKED_EXTERNAL',
            'physical_blocker':'Two authorized multi-side matte textured physical objects with independent held-out photographs remain required; synthetic checker and two-view stereo cannot substitute'}
    args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n');print(json.dumps(report,indent=2));return int(status!='PASS')


if __name__=='__main__':raise SystemExit(main())
