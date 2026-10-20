"""Read-only, source-only validation of published, unmerged owner interfaces.
Not an Android build, integration candidate, or instrumentation acceptance.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys

REPO=Path('/workspace/agent-workspaces/094')
OUT=Path('/workspace/campaign/evidence/094/incoming-fixture')
if OUT.exists():
    shutil.rmtree(OUT)
OUT.mkdir()
APP='app/src/main/java/com/realityforge/scanner/'
inputs={}

def overlay(commit, prefixes):
    listing=subprocess.check_output(['git','ls-tree','-r','--name-only',commit,'--',*prefixes],cwd=REPO,text=True).splitlines()
    assert listing, (commit,prefixes)
    for name in listing:
        blob=subprocess.check_output(['git','show',f'{commit}:{name}'],cwd=REPO)
        target=OUT/name
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(blob)
        inputs[name]={'source_sha':commit,'sha256':hashlib.sha256(blob).hexdigest()}

# No owner histories or worktrees are modified. These are exact committed blobs.
overlay('eee224368ed47ee940c3f95ee9b157e705d86706',[APP,'app/src/main/res/values','reconstruction/src','app/src/androidTest'])
overlay('e4a762baea07fd1dfa6606e7c340f49aed1be425',[APP+'engine/NativeCore.kt','reconstruction/src/jni.cpp'])
overlay('9af9f4a3d43a1f90768b46afc4a9e65dc78c0be1',[APP+'data'])
overlay('0e85e76bdc38d0df80c627933a96a5d868c867fe',[APP+'viewer/PhotographicGlb.kt',APP+'viewer/loading'])
overlay('c137e6996477f35735f169b10c8b92112c551007',[APP+'viewer/RenderBackend.kt'])
overlay('5c683605423d2c4c743bac2420192364ee430b44',[APP+'ui/PhotographicModelViewer.kt'])
overlay('f837a8ef7f7e08f2c5e021d8697d7370691742e4',[APP+'ui/ScannerApp.kt',APP+'ui/navigation','app/src/main/res/values/agent_071.xml'])
overlay('3eaacd8b8466c8018f5169b613ee46de9e7f088c',[APP+'export'])
spec=importlib.util.spec_from_file_location('incoming_contracts',REPO/'tests/test_project_contracts.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.ROOT=OUT
names=['test_jni_names_match_declared_methods','test_sparse_output_disclosed_in_ui','test_capture_package_requires_explicit_destination_and_preserves_hashes','test_m09_camera_projected_uvs_are_embedded_and_exportable','test_photographic_viewer_uses_bounded_local_assets_and_on_demand_gl','test_session_reopen_and_export_have_revision_and_operation_guards','test_m19_persisted_scan_evidence_and_local_export']
results=[]
for name in names:
    getattr(module,name)()
    results.append({'check':name,'result':'PASS','kind':'published-source-contract'})

# Corrupt a protected source condition in the projected fixture, then require
# rejection. Every original byte is restored before the next independent case.
def reject(label,name,path,old,new):
    target=OUT/path
    original=target.read_text()
    assert old in original,(label,old)
    try:
        target.write_text(original.replace(old,new,1))
        try:
            getattr(module,name)()
        except (AssertionError,ValueError,FileNotFoundError):
            results.append({'check':label,'result':'PASS','kind':'deliberate-source-corruption-rejected'})
        else:
            raise AssertionError(f'accepted corruption: {label}')
    finally:
        target.write_text(original)

reject('JNI Kotlin/export mismatch',names[0],APP+'engine/NativeCore.kt','external fun pollNativeProgress(', 'external fun unexpectedProgress(')
reject('lease owner/active validation removed',names[5],APP+'data/ProjectRepository.kt','session.requireActive(sessionOwnerToken)','Unit')
reject('lease session expiration removed',names[5],APP+'data/operations/ProjectOperation.kt','active.set(false)','active.set(true)')
reject('capture digest update removed',names[2],APP+'data/operations/CaptureArchiveServiceImpl.kt','digest.update(buffer, 0, read)','Unit')
reject('capture completion provider bypassed',names[2],APP+'data/operations/CaptureArchiveServiceImpl.kt','ProjectRepository.captureManifestEntry()','ZipEntry("session.json")')
reject('preview revision recheck removed',names[5],APP+'data/operations/PreviewReopeningServiceImpl.kt','after.revision != before.revision','false')
reject('CPU model lease release removed',names[4],APP+'ui/PhotographicModelViewer.kt','owned?.close()','Unit')
reject('loader cancellation swallowed',names[4],APP+'ui/PhotographicModelViewer.kt','catch (cancelled: CancellationException) { throw cancelled }','catch (cancelled: CancellationException) { failure="cancelled" }')
reject('bounded loader growth check removed',names[4],APP+'viewer/loading/GlbStructure.kt','require(input.read() == -1) { "Model changed while loading." }','Unit')
reject('external URI rejection removed',names[4],APP+'viewer/loading/GlbStructure.kt','require(!buffer.has("uri"))','require(true)')
reject('sparse/dense disclosure conflated',names[1],'app/src/main/res/values/agent_071.xml','Sparse points','Finished mesh')
reject('export checksum schema bypassed',names[2],APP+'export/PendingExportStore.kt','input.readInt() == 1','input.readInt() == 2')
reject('capture original privacy disclosure removed',names[2],'app/src/main/res/values/agent_071.xml','Original photographs remain private unless you choose the capture archive','All photographs are exported automatically')
reject('scan completeness limit removed',names[6],'app/src/main/res/values/agent_071.xml','not whole-object completeness, physical accuracy or a quality score','proof of physical accuracy')
reject('calibration action disconnected',names[6],APP+'ui/ScannerApp.kt','ReviewCommand.CALIBRATE -> beginCalibration()','ReviewCommand.CALIBRATE -> Unit')
report={'schema':1,'contract_source_sha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),'scope':'source-only projected immutable owner blobs; modules unmerged; no product/runtime acceptance','contract_file_sha256':hashlib.sha256((REPO/'tests/test_project_contracts.py').read_bytes()).hexdigest(),'inputs':inputs,'checks':results,'status':'PASS'}
(REPO.parent.parent/'campaign/evidence/094/incoming-source-checks.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
print(f'{len(names)} published-source checks and {len(results)-len(names)} deliberate-corruption rejections PASS; Android/device NOT_RUN')
