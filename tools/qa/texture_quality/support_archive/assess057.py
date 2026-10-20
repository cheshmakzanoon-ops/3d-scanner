import hashlib,importlib.util,json,subprocess,datetime
from pathlib import Path
R=Path('/workspace/agent-workspaces/100');F='86df647bcdba4b110ad36dfb6ce572adb4261462';B='ad3029193f304c70931b94ea7a0df90b69d84b07'; O=Path('/workspace/campaign/evidence/100/057-assessment');O.mkdir(exist_ok=True)
P=Path('/workspace/campaign/frozen100/tools/agents/campaign.py')
spec=importlib.util.spec_from_file_location('campaign057review',P);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
plan=subprocess.check_output(['git','show',F+':PRODUCTION_100_AGENT_EXECUTION_PLAN.md'],cwd=R).decode();rows=m.roster(plan)
def git(*a):return subprocess.check_output(['git',*a],cwd=R).strip()
def sha(p):
 with Path(p).open('rb') as h:return hashlib.file_digest(h,'sha256').hexdigest()
def ref(p,expected=None):
 p=Path(p);d={'path':str(p),'sha256':sha(p),'bytes':p.stat().st_size}
 if expected is not None:assert d['sha256']==expected
 return d
def blob(source,path,needle=None):
 body=git('show',source+':'+path)+b'\n';raw=subprocess.check_output(['git','show',source+':'+path],cwd=R)
 dest=O/(source[:12]+'--'+path.replace('/','__'));dest.write_bytes(raw)
 lines=raw.decode().splitlines();matches=[]
 if needle:
  import re
  matches=[{'line':i+1,'text':line} for i,line in enumerate(lines) if re.search(needle,line)]
 return {'source_sha':source,'path':path,'git_blob':git('rev-parse',source+':'+path).decode(),'sha256':hashlib.sha256(raw).hexdigest(),'snapshot':ref(dest),'matches':matches}
providers={a:{'branch':branch,'observed_fetched_sha':git('rev-parse','origin/'+branch).decode()} for a,branch in [('057','agents/057-photographic-atlas-pipeline'),('003','agents/003-jni-boundary'),('002','agents/002-pipeline-orchestration'),('004','agents/004-build-foundation')]}
scopes=[]
for agent,rev in [('057','f4261f1145d5cba769cee4da2c84b880026af9c1'),('057',providers['057']['observed_fetched_sha']),('003',providers['003']['observed_fetched_sha']),('002',providers['002']['observed_fetched_sha']),('004',providers['004']['observed_fetched_sha'])]:
 errs=m.ownership(R,rows,agent,B,rev)
 scopes.append({'agent':agent,'revision':rev,'comparison_base':B,'result':'REJECTED' if errs else 'PASS','unowned_path_count':len(errs),'unowned_paths':errs,'method':'exact frozen100 campaign.ownership full comparison to mandatory campaign base'})
percommit=[]
for agent,short in [('057','f4261f1'),('057','6cb3c5b'),('057','50b2815'),('003','4efee66'),('002','a330b66'),('004','0f81876'),('004','0019efb')]:
 rev=git('rev-parse',short).decode();parent=git('rev-parse',rev+'^1').decode();paths=git('diff','--no-renames','--name-only',parent,rev).decode().splitlines();row=next(x for x in rows if x['agent']==agent)
 invalid=[p for p in paths if not m.owned(row,p)]
 percommit.append({'agent':agent,'revision':rev,'first_parent':parent,'changed_paths':paths,'unowned_paths':invalid,'direct_diff_result':'REJECTED' if invalid else 'PASS','not_an_import_gate':'A first-parent direct-change check does not replace mandatory full branch scope validation.'})
compactPath=Path('/workspace/campaign/evidence/085/compact057-final/compact-receipt.json');c=json.loads(compactPath.read_text())
freshPath=Path('/workspace/campaign/evidence/100/texture-baseline/actual-texture-receipt.json');f=json.loads(freshPath.read_text())
assert c['protocol_sha256']==f['protocol_sha256']
old='f4261f1145d5cba769cee4da2c84b880026af9c1';latest=providers['057']['observed_fetched_sha'];jni=providers['003']['observed_fetched_sha'];pipe=providers['002']['observed_fetched_sha'];build=providers['004']['observed_fetched_sha']
components=[blob(old,'reconstruction/src/texture.cpp',r'compactCharts|rf_textured_surface(_compact)?_json|texture_png|uv_atlas|texture_blend|progress'),blob(old,'reconstruction/include/realityforge/texture.hpp',r'compactCharts|compact_json'),blob(F,'reconstruction/src/jni.cpp',r'rf_textured_surface_json'),blob(F,'tools/native_api.py',r'rf_textured_surface_json'),blob(F,'CMakeLists.txt',r'add_library|ZLIB|uv_atlas|texture_blend|progress'),blob(latest,'reconstruction/src/texture.cpp',r'rf_textured_surface(_compact)?_json|radiometry|Photometric|Exposure|compactCharts'),blob(latest,'docs/agents/status/057.md'),blob(jni,'reconstruction/src/jni.cpp',r'rf_textured_surface_compact|CompactCheckpointed|RefinedCheckpointed'),blob(pipe,'app/src/main/java/com/realityforge/scanner/engine/pipeline/DensePreviewProfile.kt'),blob(pipe,'app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt',r'executionProfile:|VerifiedRefinedCompact|reconstructTexturedSurface'),blob(pipe,'app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt',r'VerifiedRefinedCompact|reconstructTexturedSurfaceRefinedCheckpointed|true,nativeToken'),blob(pipe,'docs/agents/contracts/002-campaign-031-060.md'),blob(build,'CMakeLists.txt',r'ZLIB|RF_CAMPAIGN_SOURCES|uv_atlas|texture_blend|texture_png|texture_visibility|progress'),blob(F,'benchmarks/quality_protocols/texture.json')]
ancestor_checks=[]
for agent,paths in [('057',['reconstruction/src/texture.cpp']),('003',['reconstruction/src/jni.cpp','app/src/main/java/com/realityforge/scanner/engine/NativeCore.kt']),('004',[]),('002',['app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt','app/src/main/java/com/realityforge/scanner/engine/pipeline/DensePreviewProfile.kt'])]:
    rev=providers[agent]['observed_fetched_sha']
    args=['rev-list','--no-merges',B+'..'+rev]
    if paths:args+=['--',*paths]
    for candidate in git(*args).decode().splitlines():
        if agent == '003' and b'agent-003' not in git('show','-s','--format=%s',candidate):continue
        errors=m.ownership(R,rows,agent,B,candidate)
        required_path={'057':'reconstruction/src/texture.cpp','003':'reconstruction/src/jni.cpp','004':'CMakeLists.txt','002':'app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt'}[agent]
        required_blob=git('show',candidate+':'+required_path)
        capability={'057':b'rf_textured_surface_compact_json' in required_blob,'003':b'rf_textured_surface_compact_json' in required_blob,'004':b'ZLIB::ZLIB' in required_blob and b'texture_visibility.cpp' in required_blob and b'uv_atlas.cpp' in required_blob,'002':b'VerifiedRefinedCompact' in required_blob and b'reconstructTexturedSurfaceRefinedCheckpointed' in required_blob}[agent]
        ancestor_checks.append({'agent':agent,'source_sha':candidate,'subject':git('show','-s','--format=%s',candidate).decode(),'result':'REJECTED' if errors else 'PASS','unowned_path_count':len(errors),'contains_required_compact_capability':capability,'scope_pure_and_supports_required_route':not errors and capability})
pure_providers=[]
refs=git('for-each-ref','--format=%(refname) %(objectname)','refs/remotes/origin/agents/049-*','refs/remotes/origin/agents/051-*','refs/remotes/origin/agents/058-*','refs/remotes/origin/agents/059-*','refs/remotes/origin/agents/060-*').decode().splitlines()
for row in refs:
    name,rev=row.split();agent=name.split('/agents/')[1][:3];errors=m.ownership(R,rows,agent,B,rev)
    pure_providers.append({'agent':agent,'source_sha':rev,'branch':name,'result':'REJECTED' if errors else 'PASS','unowned_path_count':len(errors)})
record={'schema_version':1,'recorded_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'assessment':'AVAILABLE_EXPERIMENTAL_PROVIDER_REQUIRES_SCOPE_CLEAN_COHORT_AND_NEW_CANDIDATE_QUALIFICATION','frozen_candidate_source_sha':F,'frozen_candidate_uv_status':'FAIL','frozen_candidate_uv_overlap_pairs':f['metrics']['uv_overlap_pairs'],'passing_provider_source_sha':old,'passing_provider_uv_status':c['independent_uv_gate'],'passing_provider_overlap_pairs':c['independent_metrics']['uv_overlap_pairs'],'same_threshold_protocol':True,'protocol_sha256':c['protocol_sha256'],'threshold_relaxation':False,'provider_is_unavailable':False,'author057_direct_scope_invalid':False,'providers':providers,'scope_validator':{'source_sha':F,'component':ref(P),'mandatory_comparison_base':B},'full_branch_scope_checks':scopes,'individual_owner_commit_checks':percommit,'artifacts':{'compact_receipt':ref(compactPath),'compact_library':ref('/workspace/campaign/evidence/085/provider057-build/librealityforge.so',c['engine_sha256']),'compact_glb':ref('/workspace/campaign/evidence/085/compact057-final/compact.glb',c['artifact_sha256']['compact.glb']),'compact_png':ref('/workspace/campaign/evidence/085/compact057-final/compact.png',c['artifact_sha256']['compact.png']),'fresh86_receipt':ref(freshPath),'fresh86_library':ref(f['engine_artifact'],f['engine_sha256'])},'source_components':components,'scope_pure_required_route_ancestor_assessment':ancestor_checks,'independently_scoped_cohort_providers':pure_providers,'current_admissible_complete_compact_route_set':False,'scope_pure_early_ancestor_limits':'0574883792 and004e30e61d pass scope but predate compact baking and zlib/campaign registrations, respectively; neither closes the measured UV gate.','conclusions':[
'Existing actual f426 compact output passes the same frozen UV thresholds. This is a valid, limited passing slice and not a rejected algorithm or a changed-threshold acceptance.',
'The f426 compact C ABI passes compactCharts=true. The legacy ABI explicitly passes false; frozen86 JNI and Python NativeEngine both select the legacy symbol. Merging only the provider library cannot route those callers to the passing opt-in implementation.',
'Compatible receiving adapters are published: 003 supplies explicit compact/refined JNI, 004 supplies zlib plus native source/test registration, and 002 supplies a public VerifiedRefinedCompact profile with canonical settings/engine identity. The adapters are not absent. Compatibility remains the default even in the supplied002 branch.',
'The recorded f426 passing library used an external -lz build override and earlier058/059/060/049/051 cohort. Current057 adds exact radiometric visibility, curved charts and per-channel held-out changes; the f426 artifact cannot establish latest057 or a merged100 artifact passing.',
'Mandatory full-branch scope checks reject current057/003/002 delivery snapshots because they carry other owners\' merged histories. The direct057 commits checked here are within057 ownership, so this is a delivery/cohort integration limitation, not evidence of invalid057 source ownership. Current004 is also rejected for two extra contract filenames and an unreserved wrapper checksum path; these require source-owner normalization rather than skipping scope checks.',
'A source-owner scope-clean cohort/accepted integration mechanism, explicit admitted caller choice, canonical artifact identity and fresh combined native/Android/fuzz/security/independent geometry+UV qualification are required before this can replace the exact86 failure. A changed depth/surface/refined002 profile also needs separate full-reference geometry evidence; do not substitute the older unchanged-geometry compact slice.',
'No existing compact partial-stereo result supplies physical full-object coverage, independent poses, held-out capture qualification, Android device memory/thermal qualification or production4K approval. Those remain external.'], 'outgoing_request_draft':ref(O/'100-to-057-draft.md'),'review_actions':'Read-only Git/source/evidence analysis. No source edits, merges, new builds or compact reruns performed.'}
out=O/'assessment.json';out.write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({'assessment':ref(out),'full_branch_scope':[{k:r[k] for k in ['agent','revision','result','unowned_path_count']}for r in scopes],'direct_commit_scopes':[{k:r[k]for k in ['agent','revision','direct_diff_result']}for r in percommit]},indent=2))
