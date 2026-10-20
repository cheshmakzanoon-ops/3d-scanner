from pathlib import Path
import datetime,json,subprocess

heads_path=Path('/workspace/agents/031-035-receipt-heads.json')
heads=json.loads(heads_path.read_text())
runtime=Path('/workspace/cloud-setup/emulator-api29-attempt/final-runtime/FINAL_OPTIMIZED_NATIVE_DEBUG-2f18a4f-20261020T102600Z')
summary=json.loads((runtime/'summary.json').read_text())
platform=json.loads((runtime/'platform.json').read_text())
bridge=json.loads((runtime/'bridge_geometry.json').read_text())
interrupted=json.loads((runtime/'user-interruption.json').read_text())
checkpoint_dir=Path('/workspace/agents/final-artifacts/2723c9940069b8fd58e0cd045466a05de225ff01-optimized-native-20261020T103313Z')
checkpoint=json.loads((checkpoint_dir/'validation-receipt.json').read_text())
results=[]
for number in ('031','032','033','034','035'):
    root=Path('/workspace/agents')/('031-delivery' if number=='031' else number)
    def git(*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
    expected=heads[number]['head'];branch=heads[number]['branch']
    assert git('rev-parse','HEAD')==expected,(number,'local head changed')
    assert git('ls-remote','--heads','origin',branch).split()[0]==expected,(number,'remote head changed')
    assert not git('status','--porcelain','--untracked-files=no'),(number,'unexpected worktree changes')
    path=root/f'docs/agents/status/{number}.md'
    current=json.loads(path.read_text())
    draft=json.loads(Path(f'/workspace/agents/receipt-drafts/final-status/{number}.draft.json').read_text())
    status=dict(current)
    for key,value in draft.items():
        if key!='acceptance_results':status[key]=value
    # Keep every previous scoped result and append draft evidence without silently dropping history.
    acceptance=list(current.get('acceptance_results',[]))
    identities={(r.get('criterion'),r.get('source_sha'),r.get('result')) for r in acceptance}
    for row in draft.get('acceptance_results',[]):
        key=(row.get('criterion'),row.get('source_sha'),row.get('result'))
        if key not in identities:acceptance.append(row);identities.add(key)
    for row in acceptance:
        if row.get('criterion')=='Actual final-source focused/integrated Android runtime':
            row['result']='NOT_COMPLETED_USER_REQUEST'
            row['details']='Source2f18 platform9/10 (012 metadata failure), shared bridge11/11 PASS; pipeline interrupted by explicit user stop after833.44s, with no pass/failure inferred. Resource032/035 batch NOT_RUN_USER_REQUEST. Corrected-source272 was built but not installed. No further tests/builds after stop.'
    acceptance.append({'criterion':'Intermediate272 decoder-presence APK build checkpoint before further WIP review','result':'PASS','qualification_scope':'INTERMEDIATE_BUILD_CHECKPOINT_ONLY','source_sha':checkpoint['source_sha'],'source_tree_sha':checkpoint['source_tree_sha'],'validation_checkout_sha':checkpoint['validation_checkout_sha'],'command':checkpoint['command'],'jvm_tests':checkpoint['jvm_tests'],'jvm_task_outcome':'EXECUTED','lint_issue_counts':checkpoint['lint_issue_counts'],'artifacts':checkpoint['artifacts'],'evidence':str(checkpoint_dir/'validation-receipt.json'),'details':'Official-wrapper build PASS6m13s;124 JVM tests actually executed,0fail/error/skip,lint0errors8warnings; privacy/noINTERNET and12 ELF/ZIP16KB checks passed. All12 native APK payloads and Ninja object/link execution logs identical to prior2f18 optimized build. This checkpoint was not installed; its range-only decoder fix still has the independently identified malformed-type gap. Unfinished conservative followup is saved only on012.'})
    if number in ('031','033'):
        wanted=f'com.realityforge.scanner.agents.a{number}.'
        record={};tests=[]
        for line in (runtime/'platform.log').read_text().splitlines():
            if line.startswith('INSTRUMENTATION_STATUS: '):
                k,_,v=line[len('INSTRUMENTATION_STATUS: '):].partition('=');record[k]=v
            elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
                code=int(line.split(': ',1)[1])
                if record.get('class','').startswith(wanted) and code!=1:tests.append({'class':record.get('class'),'test':record.get('test'),'status_code':code})
                record={}
        assert tests and all(r['status_code']==0 for r in tests)
        acceptance.append({'criterion':'Actual Android role-specific platform fixtures on exact2f18 APK','result':'PASS','source_sha':'2f18a4f5a87139c38ee388f6c70af33e0fb247f7','tests':tests,'evidence':platform['log'],'log_sha256':platform['log_sha256'],'environment':'Actual API29x86_64 software-CPU emulator5556, optimized-native DEBUG; no physical-phone timing claim','details':'Only the listed role-specific tests passed. Wider platform batch9/10 retains the unrelated012 metadata assertion failure; shared pipeline/resource qualification did not complete before user stop.'})
    status['acceptance_results']=acceptance
    status['state_before_user_stop']=current.get('state')
    status['state']='IN_PROGRESS'
    status['user_requested_stop']={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'work_complete':False,'action':'Save existing authored work and receipts only; no implementation, tests or builds after stop.','owned_production_changed_by_stop_receipt':False,'warm_validation_history_pushed':False}
    status['runtime_stop_snapshot']={'source_sha':'2f18a4f5a87139c38ee388f6c70af33e0fb247f7','app_sha256':'0f2564d6b7845a0d930c232b200f0812e3323bcc4bdce9fe849f4c3c6f29760e','test_apk_sha256':'a31df39fdbf0a0b8ccb9e335413b2a35590db9a89d436a3fa3258a34dcb0d7e8','environment_evidence':str(runtime/'device-environment.json'),'platform':platform,'shared_bridge_geometry':bridge,'pipeline':{'classification':interrupted['classification'],'seconds':interrupted['seconds'],'log':interrupted['log'],'log_sha256':interrupted['log_sha256']},'resources':'NOT_RUN_USER_REQUEST','summary':str(runtime/'summary.json'),'qualification_scope':'Actual observed source/batch outcomes only; no overall app or campaign success claim.'}
    status['known_unfinished_dependency']={'012_published_range_fix':'73da7ac2c11f1a4e5fbde8277efdaeb7d5f17897','012_conservative_wip_source':'eaafabfb2ad6e301836cec09e3fa4577a60c38f3','details':'Physical malformed TIFF types can be hidden by AndroidX defaults in the published range-only provider. Conservative bounded scanner followup is saved as unfinished012 WIP; it was not received into032/035 or the built272 candidate before user stop. Actual wrong-type JPEG injection test and corrected runtime remain unfinished.'}
    status['next_action']='Work stopped by explicit user instruction. Resume unfinished implementation/qualification only after a new instruction; saved source/build/runtime evidence does not imply completion.'
    if number in ('032','034','035'):
        status['available_but_unrun_real_evidence']={'dataset':'Published Middlebury Motorcycle real stereo via skimage.data.stereo_motorcycle, already used by repository benchmarks','result':'NOT_RUN_USER_REQUEST','scope':'Existing032 optional photo path and035 optional pair manifest can run bounded real extraction/exact-BF comparison. This pair does not qualify30/80/250 real360 collections or physical ARM64 resource limits.035 currently supports focal-only centered estimated intrinsics, so calibrated native/GT geometry must state that limitation; full published intrinsics and per-match pixel-row improvements were not implemented before stop.'}
        status['external_blockers']=['Expanded real360-degree collections with independent correspondence truth and identified physical ARM64 devices remain unavailable. The bounded published Motorcycle stereo pair is available but was not run before user stop; it is not deferred as unavailable.']
    if number in ('032','035'):
        status['remaining_work']=['Actual resource/reference/cancellation Android batch was not run before explicit user stop.','Corrected decoder dependency, actual malformed-type JPEG fixture, final-source build/runtime and real-pair optional evidence remain unfinished.','Expanded real-collection and physical-device profile/admission gates remain unqualified; current defaults were not raised.']
    path.write_text(json.dumps(status,indent=2)+'\n')
    subprocess.run(['git','-C',str(root),'add',str(path.relative_to(root))],check=True)
    subprocess.run(['python','/workspace/agents/regen_manifest.py'],cwd=root,check=True)
    subprocess.run(['git','-C',str(root),'add','SOURCE_MANIFEST.sha256'],check=True)
    subprocess.run(['git','-C',str(root),'diff','--cached','--check'],check=True)
    subprocess.run(['git','-C',str(root),'commit','-m',f'docs(agent{number}): save user-stopped qualification receipts'],check=True)
    subprocess.run(['git','-C',str(root),'push','origin',branch],check=True)
    head=git('rev-parse','HEAD')
    assert git('ls-remote','--heads','origin',branch).split()[0]==head
    assert not git('status','--porcelain','--untracked-files=no')
    heads[number]['head']=head
    result={'agent':number,'branch':branch,'head':head,'changed_paths':['SOURCE_MANIFEST.sha256',f'docs/agents/status/{number}.md'],'remote_verified':True,'production_unchanged':True}
    results.append(result);print(json.dumps(result),flush=True)
heads_path.write_text(json.dumps(heads,indent=2)+'\n')
Path('/workspace/agents/031-035-stop-pushed-receipts.json').write_text(json.dumps(results,indent=2)+'\n')
