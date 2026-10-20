from pathlib import Path
import json,yaml,hashlib,subprocess,copy
reviewbase=Path('/workspace/campaign-061-080/filament/lifecycle-review-3b')
review=json.loads((reviewbase/'review.json').read_text())
source=Path('/workspace/campaign-061-080/final-validation/3b9f9157753b/source')
for rel in ['app/src/main/java/com/realityforge/scanner/ui/PointCloudViewer.kt','app/src/main/java/com/realityforge/scanner/ui/studio/StudioControls.kt','app/src/main/java/com/realityforge/scanner/ui/studio/StudioPose.kt','app/src/main/res/values/agent_070.xml']:
 raw=(source/rel).read_bytes();commit='5c683605423d2c4c743bac2420192364ee430b44'
 assert raw==subprocess.check_output(['git','show',f'{commit}:{rel}'],cwd='/workspace/agent-work/070')
 review['source_correlation'][rel]={'role':'070','branch_source_commit':commit,'sha256':hashlib.sha256(raw).hexdigest()}
p=Path('/workspace/campaign-061-080/device-3b9f915-egl/class-2.log');raw=p.read_bytes();assert b'OK (1 test)' in raw
(reviewbase/'egl-class-2.log').write_bytes(raw)
review['evidence_logs']['egl-class-2.log']=hashlib.sha256(raw).hexdigest()
review['runtime_results']['filamentRestoresCameraPausesAndRejectsOversizedResize']='PASS1/1,83.907 seconds,successful standalone terminal summary; actual native renderer/frame/matrix/pause/resume/resize-refusal disposal'
(reviewbase/'review.json').write_text(json.dumps(review,indent=2,sort_keys=True))
egl=Path('/workspace/campaign-061-080/device-3b9f915-egl')
remaining=Path('/workspace/campaign-061-080/device-3b9f915-remaining')
commands={}
for base in [egl,remaining]:
 receipt=json.loads((base/'receipt.json').read_text())
 for item in receipt['commands']:
  if 'instrument' in item['command']:commands[str(base/item['log'])]=' '.join(item['command'])
apphash='0e60bc83d4d7e3838d98c4d7d969afef4ae8901a75ea18795fe7d4df65fe0585'
testhash='2e93c6656d30d2832626ac2302136f1ac50395037ba3a1d82cdd28d3440489f5'
integration='3b9f9157753b934350e05287a6ff33bbe46c665a'
environment='Android29 x86_64 software emulator-5556; Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys; physical=false'
def entry(role,criterion,result,path,dataset,detail):
 return {'criterion':criterion,'result':result,'command':commands[str(path)],'source_sha':integration,'role_input_sha':{'069':'c137e6996477f35735f169b10c8b92112c551007','070':'5c683605423d2c4c743bac2420192364ee430b44'}[role],'artifact_sha256':apphash,'test_apk_sha256':testhash,'environment':environment,'dataset_identity':dataset,'evidence':str(path),'evidence_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'detail':detail}
def lifecycle(role):
 return entry(role,'Actual photographic dialog activity stop/resume transition','FAIL',remaining/'class-4.log','Synthetic embedded8x8 quadrant photographic fixture','Initial real GLES frame/no-renderer-failure checks succeeded. ActivityScenario.moveToState(CREATED) then failed with last lifecycle RESUMED before resume/assertions. Full9-method command timed out240s after3 parser PASS and1 lifecycle FAIL; Filament started without completion in that invocation. Separate later method passes do not erase this failure. Read-only review found no concrete app-level circular wait; no main/GL stacks were captured to establish cause.')
for role in ['069','070']:
 path=Path('/workspace/agent-work')/role/'docs/agents/status'/f'{role}.md'
 data=yaml.safe_load(path.read_text());original=copy.deepcopy(data)
 assert str(data['agent'])==role
 entries=data['acceptance_results']
 if role=='069':
  index=next(i for i,v in enumerate(entries) if v['criterion']=='Actual Filament restored camera, pause/resume and failed-resize disposal')
  entries[index:index+1]=[
   entry(role,'Actual Filament restored camera, pause/resume and failed-resize disposal','PASS',egl/'class-2.log','Labelled analytical embedded GLB color/UV fixture','Standalone native Filament method completed1/1 in83.907s with successful terminal JUnit summary. Covers pre-Surface pose replay, actual camera/root matrices, frames, pause/resume and one oversized-resize failure callback with idempotent disposal. No photographic pixel-parity or physical-resource inference.'),
   entry(role,'Actual GLES photographic quadrant pixels and UV orientation','PASS',egl/'class-0.log','Synthetic8x8 red/green/blue/yellow atlas and independent quadrant expectations','Standalone method completed1/1 in3.319s; actual EGL/GLES draw/readback, orbit/reset and expected colors passed.'),
   entry(role,'Actual GLES masked pixels and context-loss atlas reupload','PASS',egl/'class-1.log','Synthetic alpha-mask photographic atlas; two actual EGL contexts','Standalone method completed1/1 in2.653s; masked background and colors passed after repeated context creation/reupload.'),
   lifecycle(role)]
 else:
  index=next(i for i,v in enumerate(entries) if v['criterion']=='Real accessibility camera buttons and asynchronous native-view fallback replacement')
  entries[index:index+1]=[
   entry(role,'Actual accessible zoom/rotate/fit buttons','PASS',remaining/'class-5.log','Synthetic dense point viewer; actual Android accessibility actions and semantic camera state','ViewerControlsTest completed1/1 in81.823s with successful terminal JUnit summary. Actual Zoom, Rotate and Fit clicks changed the saved displayed state; no pinch/drag was used.'),
   entry(role,'Actual asynchronous backend failure mounts and draws GLES fallback','PASS',remaining/'class-6.log','Test-only asynchronously failed alternative surface; actual photographic GLES replacement','RendererFallbackTest completed1/1 in95.698s with successful terminal JUnit summary. Replacement PhotoGlView actually drew, old surface closed and original GLB bytes were preserved.'),
   lifecycle(role)]
 data['remaining_work']=[
  'Diagnose the preserved actual dialog CREATED-transition failure with main/GL stacks and lifecycle events on a supported device; rerun the affected criterion after identifying its cause'
  if s.startswith('Complete serialized') else
  'Required physical/manual qualification and unexecuted integrated runtime criteria remain NOT_RUN; listed software-emulator passes do not close physical qualification and dialog lifecycle remains FAIL'
  if s.startswith('Required physical/manual/runtime gates') else s for s in data['remaining_work']]
 data['runtime_evidence_scope']={'integration_source_sha':integration,'role_source_sha':{'069':'c137e6996477f35735f169b10c8b92112c551007','070':'5c683605423d2c4c743bac2420192364ee430b44'}[role],'source_correlation':str(reviewbase/'review.json'),'scope':'All12 inspected production/resource/test blobs match the frozen role implementations. Actual installed3b APK identities differ from the earlier v4/a18 build artifacts; no runtime result is retroactively assigned to those APK hashes. Only individual completed methods/classes above are PASS. PhotographicViewerTest full9-method invocation remains failed/incomplete. Hardware/performance/Filament photographic-pixel qualification stays open. No source or device changes were made for this read-only review.'}
 for key in original:
  if key not in ['acceptance_results','remaining_work']:assert original[key]==data[key],key
 path.write_text(yaml.safe_dump(data,sort_keys=False,allow_unicode=True,width=110))
 print(role,'receipt updated; prior combined-build entries retained; app/test source unchanged')
