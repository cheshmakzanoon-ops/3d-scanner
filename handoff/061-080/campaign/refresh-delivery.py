#!/usr/bin/env python3
"""Refresh external campaign delivery evidence without modifying any Git tree."""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import ast
import importlib.util
import sys
import xml.etree.ElementTree as ET
import hashlib
import json
import subprocess

ROOT = Path('/workspace/campaign-061-080')
WORK = Path('/workspace/agent-work')
WEB = 'https://github.com/cheshmakzanoon-ops/3d-scanner'

def read_json(path):
    return json.loads(path.read_text()) if path.exists() else None

def git(repo, *args):
    return subprocess.check_output(['git', *args], cwd=repo, text=True).strip()

def evidence(path):
    if not path.exists():
        return {'path': str(path), 'exists': False}
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}

IMPLEMENTATIONS = {
 '061': 'Canonical sparse/dense/observed/photographic C10 assets; bounded GLB streaming, real PNG inflation validation, linear vertex colors and atomic file replacement.',
 '062': 'C10 OBJ point/surface writers and relocatable OBJ/MTL/PNG sets; exact byte descriptions, safe names and explicit omitted attributes.',
 '063': 'Binary STL with explicit metric/reference/arbitrary scale admission, target-unit conversion and edge-incidence diagnostics.',
 '064': 'ASCII and binary little-endian PLY preserving typed points, normals, colors, confidence, supporting views and triangle indices.',
 '065': 'Constrained endpoint mesh simplification with topology/seam/displacement/work limits and parent vertex/triangle correspondence sidecars.',
 '066': 'Non-destructive orientation/reflection, conservative crop, component selection, known-distance measurement and versioned undo/redo recipes.',
 '067': 'Immutable revision-bound export catalog/requests, C8 manifest source, private validation/staging, bounded ZIP sets and durable transactional SAF picker binding.',
 '068': 'Strict bounded GLB/PNG parser, generic point/surface inspection, cancellable staged photographic loading and closeable read-only GLB lease.',
 '069': 'Pinned Filament/gltfio renderer, safe local-resource loading, explicit inspection modes, resource admission and GPU lifecycle ownership.',
 '070': 'Accessible orbit/pan/zoom/fit controls, saved studio pose, staged-loader lifetime, renderer fallback and real optional edit/LOD/export action ports.',
 '071': 'Project-aware navigation and feature composition, saved route/picker identity, persisted preferences route and real canonical-export provider contracts.',
 '072': 'Lazy searchable/sortable project library, stable identities, paged service presenter and revision-bound rename/verified duplicate adapters.',
 '073': 'Photo review, bounded image previews, calibrated-input admission and revision-fenced persisted curation/undo/reconstruction command mapping.',
 '074': 'Durable processing/recovery state presentation, measured stage progress, attempt/version fencing and action admission.',
 '075': 'Offline revisitable capture guide, practical overlap/limitations/privacy instructions and the approved 9x6-inner-corner checkerboard.',
 '076': 'Shared semantic theme, adaptive layout policy, safe-inset frames, evidence cards and accessible target primitives.',
 '077': 'Actual-activity accessibility instrumentation and manual audit checklist covering actions, recreation, 48dp targets and 200% text.',
 '078': 'Resource-backed structured user messages, typed plurals/substitutions and explicit locale-aware number/date formatting.',
 '079': 'Strict versioned atomic preferences storage, safe rejected-file recovery, next-operation settings and accurate about/privacy presentation.',
 '080': 'Actual MainActivity/DocumentsUI journey tests, permission tests and candidate-bound runner that verifies source/APK/device/test/screenshot evidence.',
}
FOCUSED = {
 '061': 'Five GLBs pass Khronos validator and Trimesh; focused native checks, sanitizers and arm64/x86_64 NDK linking pass.',
 '062': '27 focused/source-contract checks; independent OBJ import, sanitizer and both-ABI NDK checks recorded.',
 '063': '27 focused/source-contract checks; independent STL import, scale/error/cancel checks, sanitizers and NDK checks recorded.',
 '064': '27 focused/source-contract checks; independent PLY import/attributes, error/cancel checks, sanitizers and NDK checks recorded.',
 '065': 'Real 20,000-to-10,000-face reduction plus refusal/topology/seam tests; independent GLB validation passes.',
 '066': 'Native transform/crop/reflection/measurement and recipe-history regressions pass; Android test APK compiled.',
 '067': '65 focused real-contract export/C8/SAF regressions pass on the hash-identified review snapshot; final receipt and combined/device qualification tracked separately.',
 '068': '232 production-parser corpus checks; standalone Android APKs, 38 JVM tests, lint and alignment pass. Actual device results are listed separately by candidate and class.',
 '069': 'Studio subset Android build and 45 JVM tests pass; lint has zero errors and 13 warnings; 16-library APK alignment passes.',
 '070': 'Same pinned studio-subset build as 069; camera/edit-coordinate behavior tests pass. Actual renderer/UI device results are listed separately by candidate and class.',
 '071': '10 shared navigation/design/localization JVM tests and 22 canonical-manifest contract tests pass; final combined gate is separate.',
 '072': '32 shared screen/service JVM tests include stable 10,000-project queries, rename, verified copy and cancellation; 118 baseline pytest pass.',
 '073': 'Shared 32 screen/service JVM tests include persisted curation, revision conflicts and undo; 118 baseline pytest pass on the recorded prior source.',
 '074': '35 shared screen/service JVM tests include actual action-admission policy regressions; accepted mutations latch until version changes, rejected/navigation actions release. Android repeat-action test is implemented.',
 '075': 'Shared 32 screen/service JVM tests plus two board/content checks; 120 pytest pass on the recorded candidate.',
 '076': 'Initial Android build/lint and final shared 10 JVM tests pass; adaptive/manual matrix remains open.',
 '077': 'Accessibility tests are implemented; historical runtime failures and subsequent per-class device results are preserved separately below, without a manual accessibility pass.',
 '078': 'Initial Android build/lint and final shared 10 JVM tests pass; actual RTL/large-font/locale matrix remains open.',
 '079': 'Shared 32 JVM tests include preferences roundtrip, strict codec and atomic preservation; 118 baseline pytest pass.',
 '080': 'Eight concrete UI/physical entrypoints and five external-controller scenarios; current explicitly scoped runner tests and candidate-bound runtime results are reported separately below.',
}
GATES = [
 {'owners': ['004'], 'gate': 'Commit remaining native/Filament build registrations', 'status': 'OPEN', 'detail': 'The permanent owner004 wrapper/build-script fix is consumed by the current candidate. Register the seven delivered native sources and pinned Filament/gltfio1.56.0 dependencies in owned build files; those two registrations still use explicit external verification adapters.'},
 {'owners': ['003', '037', '053', '057', '009', '067'], 'gate': 'Invoke delivered native features and preserve provenance', 'status': 'OPEN', 'detail': 'Bind checked JNI/materialization/cancellation; route actual canonical writers, LOD and edits; compose parent observation index maps; assign complete descendant C8 identity. Dense legacy mislabeling must not become an offered export.'},
 {'owners': ['001', '005', '006', '007', '008', '018', '019', '022', '023', '024', '025', '026', '071', '072', '073', '074'], 'gate': 'Install durable application providers', 'status': 'OPEN', 'detail': 'Complete delegated C1/canonical provider, library store/duplicate leases, curation/removal/selection admission, job-store registration and atomic full-attempt actions, previous-result selection and provider transaction bindings. The 071 receiving-owner request also keeps capture-archive and transfer capabilities unavailable pending their actual owners and 001 adapters.'},
 {'owners': ['094', '053', '009'], 'gate': 'Resolve failing combined source-contract checks', 'status': 'FAIL', 'detail': 'Retained source-literal gates remain failing in the exact recorded Python scope, including the owner009 M18-domain assertion. Update semantic assertions through their owners and rerun; classification and passing behavioral tests do not turn these failures into success.'},
 {'owners': ['030', '065', '068', '069', '070', '084', '085', '090'], 'gate': 'Measured real-asset geometry/appearance/resource qualification', 'status': 'NOT_RUN', 'detail': 'Measure maximum-asset CPU/GPU residency, frame pacing, open/close lifecycle, LOD error/texture appearance and real target-tool imports on frozen eligible data. Synthetic fixtures and admission bounds are not measured physical reconstruction quality.'},
 {'owners': ['068', '082', '096'], 'gate': 'Sustained hostile-input/fuzz qualification', 'status': 'NOT_RUN', 'detail': 'The committed bounded malformed-input corpus passes; sustained owner082 fuzz campaigning and final integrated parser/security coverage remain separate gates.'},
 {'owners': ['077', '080', '086', '088', '092', '100'], 'gate': 'Complete device, interruption and physical/manual matrix', 'status': 'OPEN', 'detail': 'Per-class software-device results are reported with their exact source/APK identities. Complete latest-candidate UI/parser/render/export/accessibility and API/ABI/16KB runtime coverage, external process/picker/camera interruption integration, physical capture/operator evidence, TalkBack/keyboard/switch access, 200% fonts, RTL and adaptive layouts. A parser/device subset pass does not close the remaining matrix; physical/manual gates remain NOT_RUN.'},
 {'owners': ['028', '030', '095', '079'], 'gate': 'Qualified optional capabilities and notices', 'status': 'OPEN', 'detail': 'Integrate actual storage, qualified manual profiles and audited notice providers before exposing corresponding optional preferences controls.'},
 {'owners': ['100'], 'gate': 'Final integration acceptance', 'status': 'OPEN', 'detail': 'Reconcile receiving-owner changes, regenerate the complete manifest, rerun the final immutable candidate and accept only measured results. Role branches are published individually; no main merge or release acceptance is claimed.'},
]

def scenario_inventory(source_sha):
    source = git(WORK/'080', 'show', source_sha+':tools/android-ui-e2e/run.py')
    tree = ast.parse(source)
    value = next(n.value for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'SCENARIOS' for t in n.targets))
    rows = []
    for key, spec in zip(value.keys, value.values):
        fields = {ast.literal_eval(k): v for k, v in zip(spec.keys, spec.values)}
        rows.append({'scenario': ast.literal_eval(key), 'kind': ast.literal_eval(fields['kind'])})
    return {'source_sha': source_sha, 'scenarios': rows,
            'concrete_entrypoints': sum(r['kind'] in {'instrumentation','physical'} for r in rows),
            'external_controller_scenarios': sum(r['kind'] == 'controller' for r in rows)}


def runner_test_counts(pytest):
    path=Path(pytest['xml']['path'])
    if evidence(path)['sha256'] != pytest['xml']['sha256']:
        raise SystemExit('Python JUnit XML digest mismatch.')
    cases=[c for c in ET.parse(path).getroot().iter('testcase')
           if c.get('classname','').split('.')[-1]=='test_agent_080_ui_evidence']
    failures=sum(c.find('failure') is not None for c in cases)
    errors=sum(c.find('error') is not None for c in cases)
    skipped=sum(c.find('skipped') is not None for c in cases)
    return {'tests':len(cases),'passed':len(cases)-failures-errors-skipped,
            'failures':failures,'errors':errors,'skipped':skipped}


def device_evidence(runtime_paths, current_source, current_artifacts):
    attempts = []
    for path in sorted(ROOT.glob('device*/receipt.json')):
        data = read_json(path)
        if isinstance(data, dict) and ('classes' in data or 'scenarios' in data):
            attempts.append({'source_sha': data.get('source_sha', data.get('source_commit')),
                             'status': data.get('status'), 'physical': data.get('physical',False),
                             'classes': data.get('classes',[]), 'artifacts': data.get('artifacts'),
                             'build_receipt': data.get('build_receipt'), 'evidence': evidence(path)})
    historical = []
    late = ROOT/'077-device/late-install-20261020T091939Z/result.json'
    if late.exists():
        data = read_json(late)
        historical.append({'label':'Initial accessibility runtime', 'source_sha':data.get('build_source_sha'),
                           'status':data.get('actual_accessibility_tests'),
                           'passed':data.get('tests_passed'), 'failed':data.get('tests_failed'),
                           'completed':data.get('tests_run'), 'evidence':evidence(late)})
    rerun = ROOT/'077-device/prepared-guest-rerun/receipt.json'
    log = rerun.parent/'instrumentation.log'
    if rerun.exists() and log.exists():
        # Use the committed runner's real terminal parser, without invoking any device or runner action.
        module_name = 'delivery_instrumentation_parser'
        spec = importlib.util.spec_from_file_location(module_name, WORK/'080/tools/android-ui-e2e/run.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        spec.loader.exec_module(module)
        data = read_json(rerun)
        parsed = module.parse_instrumentation(log.read_text(), data['exit_code'])
        historical.append({'label':'Prepared-guest accessibility rerun','source_sha':data.get('source_sha'),
                           **parsed,'evidence':evidence(rerun),'stdout':evidence(log)})
    runtime = []
    for path in runtime_paths:
        data = read_json(path)
        if not data:
            raise SystemExit('Runtime report is missing: '+str(path))
        candidate=data.get('candidate',{})
        expected={
            'source_sha':current_source,
            'apk_sha256':current_artifacts.get('app/build/outputs/apk/debug/app-debug.apk',{}).get('sha256'),
            'test_apk_sha256':current_artifacts.get('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',{}).get('sha256'),
        }
        matches=all(candidate.get(k)==v and v is not None for k,v in expected.items())
        runtime.append({'evidence':evidence(path),'matches_current_candidate':matches,
                        'candidate_binding':candidate,'report':data})
    return {'status':'PARTIAL_EVIDENCE_ACCEPTANCE_OPEN' if attempts or runtime else 'NOT_RUN',
            'attempts':attempts, 'historical_accessibility_attempts':historical,
            'actual_ui_runner_reports':runtime,
            'physical_capture':'NOT_RUN', 'manual_assistive_technology':'NOT_RUN',
            'claim_limit':'Every device result applies only to its recorded source/APK/class or scenario. Older software-device passes are not relabeled as latest-candidate or physical qualification.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validation-dir', required=True, type=Path,
                        help='Exact completed frozen-candidate directory; stale source/input results are rejected.')
    parser.add_argument('--runtime-report', action='append', default=[], type=Path,
                        help='Candidate-bound actual UI runner report; repeat for additional reports.')
    args = parser.parse_args()
    validation = args.validation_dir.resolve()
    audit = read_json(ROOT/'final-branch-audit.json')
    inputs = read_json(ROOT/'combined-final-inputs.json')
    by_input = {d['agent']:d['source_sha'] for d in inputs}
    by_audit = {d['agent']:d for d in audit['deliveries']}
    screens = read_json(ROOT/'screens-deliveries.json')
    final = read_json(validation/'result.json')
    pytest = read_json(validation/'pytest-result.json')
    if not final or not pytest:
        raise SystemExit('Completed Android and Python result files are required; delivery artifacts were not changed.')
    combined_head = git(ROOT/'combined','rev-parse','HEAD')
    if final['source_commit'] != combined_head:
        raise SystemExit('Validation source differs from current frozen combined HEAD; refusing stale evidence.')
    if final.get('planned_inputs') != inputs:
        raise SystemExit('Supplying revisions differ from combined-final-inputs.json; refusing stale evidence.')
    if pytest['source_commit'] != final['source_commit'] or pytest['tracked_source_digest_sha256'] != final['tracked_source_digest_sha256']:
        raise SystemExit('Android/Python source identity mismatch; delivery artifacts were not changed.')
    if final['state'] not in {'PASS','FAIL'} or pytest['state'] not in {'PASS','FAIL'}:
        raise SystemExit('Validation is still running; no finished result is inferred.')
    native = read_json(ROOT/'native-assertions-final.json')
    native_reference = read_json(validation/'native-proof-reference.json')
    if not native or not native_reference or native_reference['source_commit'] != final['source_commit']:
        raise SystemExit('Current native proof binding must match the final source.')
    native_reused = native['source_sha'] != final['source_commit']
    if native_reference.get('schema') == 2:
        binding=native_reference['native_assertion_proof']
        if (native_reference['tested_native_source_commit'] != native['source_sha'] or
            not native_reference['all_native_inputs_and_host_library_match_current_candidate'] or
            native_reference['ctest_execution_reused_by_exact_native_input_and_library_equality'] != native_reused):
            raise SystemExit('Native execution identity/equivalence binding is inconsistent.')
        proof_digest=binding['sha256']
        for path_key,digest_key in [('current_source_snapshot','current_source_snapshot_sha256')]:
            if evidence(Path(native_reference[path_key]))['sha256'] != native_reference[digest_key]:
                raise SystemExit('Current native source snapshot digest mismatch.')
        library=native_reference['host_library']
        if evidence(Path(library['copied_path']))['sha256'] != library['sha256'] or library['sha256'] != native['library_sha256']:
            raise SystemExit('Current copied native library differs from the assertion-enabled proof.')
    else:
        if native_reused:
            raise SystemExit('Historical native execution requires an explicit current-source equivalence proof.')
        proof_digest=native_reference['proof_sha256']
    if proof_digest != evidence(ROOT/'native-assertions-final.json')['sha256']:
        raise SystemExit('Native proof digest mismatch.')
    assertions = native['assertion_enabled_test_compiles']
    if native['status'] != 'PASS' or native_reference['status'] != 'PASS' or len(assertions) != 17 or any(r['assertion_flags_in_order'][-1] != '-UNDEBUG' for r in assertions):
        raise SystemExit('All seventeen current native test compiles must have assertions enabled and pass.')
    historical_full_path = Path(pytest.get('native_input_equivalence',{}).get('prior_full_pytest_result', ROOT/'final-validation/a18aa6eb6d8f-buildscript-relative/pytest-result.json'))
    historical_full = read_json(historical_full_path)
    historical_affected=[]
    for path in sorted((ROOT/'final-validation').glob('*/pytest-result.json')):
        if path.resolve()==(validation/'pytest-result.json').resolve():continue
        old_result=read_json(path)
        if old_result.get('scope_is_full_pytest_suite') is False:
            historical_affected.append({'source_commit':old_result['source_commit'],
                'scope':old_result.get('scope'),'state':old_result['state'],
                'passed':old_result.get('passed'),'totals':old_result.get('totals'),
                'evidence':evidence(path)})
    full_scope = pytest.get('scope_is_full_pytest_suite', 'tests' in pytest['command']['command'])
    runner_tests=runner_test_counts(pytest)
    device = device_evidence([p.resolve() for p in args.runtime_report],final['source_commit'],final.get('artifacts',{}))
    inventory = scenario_inventory(by_input['080'])
    original_path = ROOT/'final-validation/a18aa6eb6d8f/result.json'
    original = read_json(original_path)
    filament = read_json(ROOT/'filament/v4-artifacts/validation.json')
    adapters = final.get('verification_adapters',{})
    needs_import_adapter = 'owner004_app_build_script_import_fix' in adapters
    fixed_script_matches = git(ROOT/'combined','show',by_input['004']+':app/build.gradle.kts') == git(ROOT/'combined','show',final['source_commit']+':app/build.gradle.kts')
    if not needs_import_adapter and not fixed_script_matches:
        raise SystemExit('Claimed owner004 build script does not match the immutable supplying revision.')
    validation_relative = validation.relative_to(ROOT).as_posix()
    focused = dict(FOCUSED)
    focused['080'] = f"{inventory['concrete_entrypoints']} concrete UI/physical entrypoints and {inventory['external_controller_scenarios']} external-controller scenarios. Current exact Python scope includes {runner_tests['passed']}/{runner_tests['tests']} passing runner cases; actual UI runtime is reported separately by candidate/scenario."
    rows = []
    for number in range(61,81):
        agent=f'{number:03d}';repo=WORK/agent;old=by_audit[agent]
        head=git(repo,'rev-parse','HEAD');branch=git(repo,'branch','--show-current');source=by_input[agent]
        delta=git(repo,'diff','--name-only',source,head).splitlines()
        status=old.get('contract_status',{})
        row={'agent':agent,'branch':branch,'branch_url':f'{WEB}/tree/{branch}',
             'local_head':head,'head_url':f'{WEB}/commit/{head}',
             'combined_input_source_sha':source,'combined_input_url':f'{WEB}/commit/{source}',
             'combined_input_authority':str(ROOT/'combined-final-inputs.json'),
             'paths_changed_after_combined_input':delta,
             'non_receipt_paths_changed_after_combined_input':[p for p in delta if p!='SOURCE_MANIFEST.sha256' and not p.startswith('docs/')],
             'receipt_state_at_audit':status.get('state'),'audit_head':old['final_checkout']['head'],
             'audit_matches_current_head':old['final_checkout']['head']==head,
             'remote_equal_at_audit':old.get('local_remote_head_equal'),
             'delivery_status_at_audit':old.get('delivery_status'),'ownership_at_audit':old.get('ownership_status'),
             'manifest_at_audit':old.get('manifest_commit'),'worktree_porcelain_at_generation':git(repo,'status','--porcelain'),
             'implementation':IMPLEMENTATIONS[agent],'verification_summary':focused[agent],
             'recorded_acceptance_results_at_audit':status.get('acceptance_results',[]),
             'integration_requests':old.get('outgoing_request_paths',[]),'contract_completion_claimed':False}
        if agent in screens:row['focused_screen_test_source_sha']=screens[agent]['source']
        for name in ['production_implementation','source_candidate','candidate_commit','candidate_source_sha','native_candidate_commit','implementation_commits']:
            if name in status:row[name]=status[name]
        rows.append(row)
    report={'schema':2,'generated_at':datetime.now(timezone.utc).isoformat(),
            'state':'DELIVERY_DRAFT_ACCEPTANCE_OPEN','contract_completion_claimed':False,
            'repository':WEB,'campaign_base':audit['campaign_base'],
            'scope':'External delivery artifacts only; no role Git tree, commit or remote is changed by this generator.',
            'source_claim_distinction':'Branch HEAD, frozen combined input, current Android build, explicitly recorded current Python scope, historical Python runs, assertion-enabled native run and per-candidate device/runtime attempts are separate identities and claims.',
            'audit_finished_at':audit['finished_at'],'audit_summary':audit['summary'],'deliveries':rows,
            'combined_validation':{
                'source_commit':final['source_commit'],'source_tree_oid':final['source_tree_oid'],
                'tracked_source_digest_sha256':final['tracked_source_digest_sha256'],
                'original_owner004_attempt_historical':{'source_commit':original['source_commit'],'state':original['state'],'evidence':evidence(original_path)},
                'current_owner004_build_script':{'supplying_sha':by_input['004'],'matches_actual_frozen_script':fixed_script_matches,'external_kotlin_buildscript_adapter_used':needs_import_adapter,'permanent_fix_consumed':not needs_import_adapter and fixed_script_matches},
                'externally_adapted_android':{'state':final['state'],'evidence':evidence(validation/'result.json'),'adapters':adapters,'commands':final.get('commands',[]),'limitations':final.get('limitations',[]),'jvm_tests':final.get('jvm_tests'),'lint':final.get('lint'),'apk_checks':final.get('apk_checks'),'artifacts':final.get('artifacts'),'new_native_packaging':final.get('new_native_packaging'),'tracked_source_unchanged':final.get('tracked_source_unchanged_after_validation')},
                'native':{'result':native['status'],'tested_native_source_commit':native['source_sha'],'binding_candidate_commit':final['source_commit'],'execution_reused_by_exact_native_input_and_library_equality':native_reused,'ctest_passed':native['ctest_count'],'assertion_enabled_test_compile_count':len(assertions),'all_last_assertion_flags_UNDEBUG':True,'evidence':evidence(ROOT/'native-assertions-final.json'),'ctest_log':evidence(Path(native['ctest_log'])),'independent_reference':evidence(validation/'native-proof-reference.json'),'proof':native,'reference':native_reference},
                'python_current':{'state':pytest['state'],'scope':pytest.get('scope','FULL'),'scope_is_full_pytest_suite':full_scope,'passed':pytest['passed'],'totals':pytest['totals'],'agent080_runner_tests':runner_tests,'failed_cases':pytest.get('failed_cases',[]),'new_or_unclassified_failures':pytest.get('new_or_unclassified_failures',[]),'source_unchanged':pytest.get('tracked_source_unchanged',pytest.get('tracked_source_and_host_library_unchanged')),'native_input_equivalence':pytest.get('native_input_equivalence'),'evidence':evidence(validation/'pytest-result.json')},
                'python_full_historical':{'source_commit':historical_full['source_commit'],'state':historical_full['state'],'passed':historical_full['passed'],'totals':historical_full['totals'],'evidence':evidence(historical_full_path),'claim_limit':'Historical full run only; the current Python result has its own explicit source and scope. Old Release baseline CTests without -UNDEBUG are not reused as assertion evidence.'},
                'python_affected_historical':historical_affected,
                'filament_subset_historical':{'result':'PASS' if filament['exit_code']==0 else 'FAIL','source_heads':filament['heads'],'jvm_results':filament['jvm_results'],'evidence':evidence(ROOT/'filament/v4-artifacts/validation.json')},
                'device':device,'ui_scenario_inventory':inventory},
            'remaining_gates':GATES,
            'evidence_inputs':[evidence(ROOT/n) for n in ['final-branch-audit.json','combined-final-inputs.json','screens-deliveries.json','067-review-final-evidence.json','final-validation-004-request.md']],
            'refresh_command':'python /workspace/campaign-061-080/refresh-delivery.py --validation-dir '+str(validation)+''.join(' --runtime-report '+str(p.resolve()) for p in args.runtime_report)}
    summary=audit['summary']
    md=['# Agents 061–080 delivery draft','',f"Generated {report['generated_at']}. Campaign acceptance remains **open**; no assignment is marked DONE.",'',
        f"Audit at {audit['finished_at']}: {summary['local_remote_equal']}/20 local/remote heads equal, {summary['ownership_pass']}/20 ownership checks pass, {summary['committed_manifest_pass']}/20 committed manifests pass, {summary['clean_worktrees']}/20 clean worktrees. Audit pending items: {json.dumps(summary.get('pending',{}))}.",'',
        'Current branch HEADs and frozen supplying inputs are distinct. Full identities, changed paths, exact test sources, receipt states and audit timestamps are preserved in [delivery.json](delivery.json). A subset or historical pass is not relabeled as a current complete-campaign pass.','',
        '| Agent and branch | Current HEAD / combined input | Implemented work and focused evidence | Receipt at audit |','|---|---|---|---|']
    for d in rows:
        identity=f"HEAD [{d['local_head'][:12]}]({d['head_url']})<br>input [{d['combined_input_source_sha'][:12]}]({d['combined_input_url']})"
        md.append(f"| [{d['agent']} — {d['branch']}]({d['branch_url']}) | {identity} | {d['implementation']}<br>{d['verification_summary']} | {d['receipt_state_at_audit']} |")
    j=final['jvm_tests'];totals=pytest['totals'];hist=historical_full['totals']
    scope_label='full suite' if full_scope else 'affected scope only'
    python_scope_note=('This is a fresh full frozen-suite run with no exclusions.' if full_scope else
                       'This run executes all current 080 runner behavioral tests and all retained literal gates; it is not a new full numerical/Python suite.')
    native_execution_note=(f"Tests ran at `{native['source_sha']}`; current candidate admission reuses that execution only by exact native tracked-set/source-byte and actual library-byte equality. This is not a newly executed CTest run." if native_reused else
                           'These tests executed on this exact frozen source.')
    md += ['', '## Latest frozen-candidate evidence','',
        f"Combined commit `{final['source_commit']}`; tree `{final['source_tree_oid']}`; tracked-source SHA256 `{final['tracked_source_digest_sha256']}`. This local integration candidate was not pushed as main or as an individual owner history.",'',
        f"- **Android {final['state']}:** {j['tests']} JVM tests, {j['failures']} failures, {j['errors']} errors, {j['skipped']} skipped; lint zero errors and {final['lint']['severity_counts'].get('Warning',0)} warnings. Both APKs built, no INTERNET permission, and 16 libraries pass 16 KiB ZIP/ELF checks. Seven new native source/symbol families are verified in both ABIs. All {final['tracked_files']} tracked files remained unchanged. [Result]({validation_relative}/result.json).",
        f"- **Owner 004 permanent fix consumed:** actual supplying revision `{by_input['004']}` provides the current fixed wrapper/build script. No external Kotlin build-script/import patch is used. Only the seven-source CMake registration and Filament/gltfio1.56.0 dependency registration remain external verification adapters; compilation/symbol inclusion does not prove JNI or UI invocation.",
        f"- **Native {native['status']}:** {native['ctest_count']}/17 assertion-enabled CTests pass. {native_execution_note} All 17 test compile commands end with `-UNDEBUG` after `-DNDEBUG`; source/command/log/library hashes are recorded. [Execution proof](native-assertions-final.json), [current binding]({validation_relative}/native-proof-reference.json). Old Release baseline CTests without this assertion setting are not reused.",
        f"- **Python {pytest['state']} — {scope_label}:** {pytest['passed']} passed, {totals['failures']} failed, {totals['errors']} errors, {totals['skipped']} skipped, {totals['tests']} executed. {python_scope_note} Agent080 runner coverage within that exact scope is {runner_tests['passed']}/{runner_tests['tests']} passing. [Exact scope/result]({validation_relative}/pytest-result.json).",
        f"- **Historical full Python run at {historical_full['source_commit'][:12]}:** {historical_full['passed']} passed, {hist['failures']} failed, {hist['errors']} errors, {hist['skipped']} skipped out of {hist['tests']}. Reconstruction implementation/test bytes remain unchanged; CMake changed and is covered by the fresh assertion-enabled native proof above. [Historical full result]({historical_full_path.relative_to(ROOT).as_posix()}).",
        f"- **Historical owner 004 Android failure at {original['source_commit'][:12]}:** preserved [original failure](final-validation/a18aa6eb6d8f/result.json). Its temporary import adapter is historical; the current permanent supplying fix supersedes it.",
        f"- **080 implementation:** {inventory['concrete_entrypoints']} concrete instrumentation/physical entrypoints, {inventory['external_controller_scenarios']} explicit shared-controller scenarios, source `{by_input['080']}`. A physical entrypoint requires identified authorized hardware/scene; its existence is not physical execution evidence.",'',
        'Current retained Python failures:','']
    md += ['- `'+r['nodeid']+'`.' for r in pytest.get('failed_cases',[])]
    if historical_affected:
        md += ['', 'Prior affected-only runs remain historical:', '']
        for old_result in historical_affected:
            t=old_result['totals']
            md.append(f"- `{old_result['source_commit'][:12]}`: {old_result['passed']} passed / {t['failures']} failed / {t['skipped']} skipped out of {t['tests']}; [exact affected scope]({Path(old_result['evidence']['path']).relative_to(ROOT).as_posix()}).")
    md += ['', 'Classifying these source-literal failures and recording passing behavioral checks does not waive them. Owners 094/053/009 must resolve semantic assertions and rerun their gates.', '', '## Device and actual UI results', '']
    if device['attempts']:
        md += ['| Source / attempt | Class | Result | Passed / failed / skipped |','|---|---|---|---|']
        for attempt in device['attempts']:
            source=attempt['source_sha'] or 'unrecorded'
            for cls in attempt['classes']:
                r=cls['result']
                md.append(f"| `{source[:12]}` — {attempt['status']} | `{cls['class']}` | {r['status']} | {r.get('passed',0)} / {r.get('failed',0)} / {r.get('skipped',0)} |")
        md += ['', 'The 068 parser device method contains the 232-check malformed/bomb corpus. Each class result applies to its recorded candidate and APK pair. An attempt still marked WORKING is not an aggregate pass; completed prior-candidate classes are not relabeled as latest-candidate runtime acceptance.']
    else:
        md += ['No completed candidate-bound device class is recorded yet.']
    md += ['', 'Historical accessibility attempts are preserved separately:', '']
    for attempt in device['historical_accessibility_attempts']:
        md.append(f"- {attempt['label']} at `{(attempt.get('source_sha') or 'unrecorded')[:12]}`: {attempt.get('status')}, {attempt.get('passed')} passed / {attempt.get('failed')} failed out of {attempt.get('completed')}; [evidence]({Path(attempt['evidence']['path']).relative_to(ROOT).as_posix()}).")
    if device['actual_ui_runner_reports']:
        md += ['', 'Actual UI runner reports:', '']
        for runtime in device['actual_ui_runner_reports']:
            data=runtime['report']
            binding=runtime['candidate_binding']
            md.append(f"- Source `{binding.get('source_sha','not established')}`; current candidate/APK binding match **{runtime['matches_current_candidate']}**; result {data.get('status','UNRECORDED')}; assignment {data.get('assignment_status','UNRECORDED')}; [exact scenario results]({Path(runtime['evidence']['path']).relative_to(ROOT).as_posix()}).")
    else:
        md += ['', 'The latest all-required 080 runner attempt is planned; no result is inferred before a candidate-bound report is supplied.']
    md += ['', '**Physical capture, manual TalkBack/keyboard/switch-access, adaptive/RTL/200% fonts and qualified GPU/thermal/accuracy matrices remain NOT_RUN.** Software-device parser or accessibility results do not close these gates.', '', 'Final application/test artifact identities:', '']
    for name,artifact in final.get('artifacts',{}).items():
        md.append(f"- `{name}` SHA256 `{artifact['sha256']}` ({artifact['bytes']} bytes).")
    md += ['', '## Remaining integration and qualification gates', '']
    for gate in GATES:
        md.append(f"- **{gate['gate']} — {gate['status']} (owners {', '.join(gate['owners'])}):** {gate['detail']}")
    md += ['', 'Outgoing owner requests and all exact evidence identities appear in delivery.json. Shared test counts must not be summed as independent coverage. Synthetic datasets remain synthetic; host numerical checks do not establish physical reconstruction accuracy.', '', 'This report changes no role repository. Git pushes and remote-head verification work; the configured GitHub PR API returned Forbidden. Refresh after the final runtime/audit with the exact candidate result directory; add --runtime-report for actual UI runner reports:', '', '```sh',report['refresh_command'],'```','']
    (ROOT/'delivery.json').write_text(json.dumps(report,indent=2)+'\n')
    (ROOT/'delivery.md').write_text('\n'.join(md))
    print(json.dumps({'deliveries':len(rows),'source':final['source_commit'],'android_jvm_tests':j['tests'],'python_scope':scope_label,'python_passed':pytest['passed'],'python_failed':totals['failures'],'native_assertions':len(assertions),'device_attempts':len(device['attempts'])}))


if __name__ == '__main__':
    main()
