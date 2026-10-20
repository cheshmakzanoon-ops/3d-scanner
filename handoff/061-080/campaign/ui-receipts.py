from pathlib import Path
import subprocess,hashlib,yaml
base='ad3029193f304c70931b94ea7a0df90b69d84b07'
log=Path('/workspace/campaign-061-080/ui-foundation-jvm.log')
for role,title,branch,initial,count in [
('076','Shared design system and adaptive layout primitives','agents/076-adaptive-design-system','30e11e2b7c4b3e5d987cc31e0dce4be22d21f291',38),
('078','Localization resources and structured user messages','agents/078-localization-resources','06ef94d9dcfb728b9dd3664c3f52870ce8000a32',39)]:
 root=Path('/workspace/agent-work')/role
 head=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
 paths=subprocess.check_output(['git','-C',str(root),'diff','--name-only',base,'HEAD'],text=True).splitlines()
 commits=subprocess.check_output(['git','-C',str(root),'rev-list','--reverse',base+'..HEAD'],text=True).splitlines()
 requests=[str(p.relative_to(root)) for p in (root/'docs/agents/requests').glob(role+'-to-*.md')]
 evidence=[]
 def add(criterion,result,command,sha,artifact,evidencepath,dataset=None):
  evidence.append(dict(criterion=criterion,result=result,command=command,source_sha=sha,artifact_sha256=artifact,environment='Linux x86_64; JDK17; Kotlin2.1.21; Android SDK36; Gradle8.13',dataset_identity=dataset,evidence=evidencepath))
 add('Production Android compilation, JVM regressions and debug lint at original implementation','PASS','python /workspace/toolchains/gradle-cloud.py :app:assembleDebug :app:testDebugUnitTest :app:lintDebug',initial,None,f'/workspace/campaign-061-080/{role}-android.log')
 add('Final owned pure JVM behavior; 10 total across071/076/078, zero failures or skips','PASS','python /workspace/campaign-061-080/ui-foundation-jvm.py',head,hashlib.sha256(log.read_bytes()).hexdigest(),str(log),'Synthetic behavioral unit inputs, explicitly locale-controlled')
 add('Final integrated production, instrumentation APK and lint','NOT_RUN','Parent final pinned combined Android candidate',head,None,'Parent campaign evidence index pending')
 add('Actual activity accessibility semantics and resource behavior','NOT_RUN',f':app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.realityforge.scanner.agents.a{role}.'+('DesignSystemDeviceTest' if role=='076' else 'LocalizedResourcesDeviceTest'),head,None,'Implemented Android instrumentation; pending integrated device run')
 add('Adaptive phone/landscape/tablet,200% font,screen reader and locale flow matrix','NOT_RUN','Run integrated manual/device qualification matrix',head,None,'No physical-device or screenshot qualification claimed')
 receipt=dict(agent=role,role=title,branch=branch,campaign_base=base,implementation_commits=commits,state='READY_FOR_INTEGRATION',owned_paths_changed=paths,interfaces_published=[f'docs/agents/contracts/{role}.md'],integration_requests=requests,acceptance_results=evidence,remaining_work=['Final immutable combined Android build/tests/lint','Required physical/adaptive/accessibility/locale matrix'],external_blockers=['Agent100 combined integration and supported-device qualification']+(['Owner001 native system-bar/night integration'] if role=='076' else ['Owner004 pseudo-locales configuration','Remaining camera/viewer source-owner localization']),next_action='Integrate exact source into parent combined candidate, execute remaining gates and append immutable evidence; no DONE claim')
 (root/f'docs/agents/status/{role}.md').write_text(yaml.safe_dump(receipt,sort_keys=False,width=110))
