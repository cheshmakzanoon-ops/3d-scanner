#!/usr/bin/env python3
"""Add pinned campaign evidence to the two owned receipts; no implementation edits."""
from pathlib import Path
from datetime import datetime, timezone
import copy
import hashlib
import json
import shlex
import subprocess
import yaml

ROOT = Path('/workspace/campaign-061-080')
VALIDATION = ROOT/'final-validation/e290ff563293'
FINAL = json.loads((VALIDATION/'result.json').read_text())
PYTEST = json.loads((VALIDATION/'pytest-result.json').read_text())
NATIVE = json.loads((VALIDATION/'native-proof-reference.json').read_text())
OLD_DEVICE = json.loads((ROOT/'device-3b9f915/receipt.json').read_text())
SOURCE = FINAL['source_commit']
assert SOURCE == 'e290ff56329322b4365298b97bba7df8b16d2470'
assert FINAL['state'] == 'PASS' and PYTEST['state'] == 'FAIL'
assert PYTEST['scope_is_full_pytest_suite'] and PYTEST['totals'] == dict(tests=194, failures=7, errors=0, skipped=0)
assert PYTEST['passed'] == 187 and not PYTEST['new_or_unclassified_failures']
assert NATIVE['status'] == 'PASS' and NATIVE['schema'] == 2
assert len(NATIVE['native_input_sha256']) == 55

def git(repo, *args):
    return subprocess.check_output(['git', *args], cwd=repo, text=True).strip()

def hashed(path):
    path=Path(path)
    return {'path':str(path), 'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}

proof={'schema':1, 'generated_at':datetime.now(timezone.utc).isoformat(),
       'source_commit':SOURCE, 'roles':{}, 'scope':'Read-only Git blob equality; receipts/manifests excluded. No code, tests, APKs or combined checkout changed.'}
inputs={r['agent']:r['source_sha'] for r in FINAL['planned_inputs']}
for role in ['061','068']:
    repo=Path('/workspace/agent-work')/role
    receipt=yaml.safe_load((repo/f'docs/agents/status/{role}.md').read_text())
    if receipt.get('latest_combined_source') == SOURCE:
        raise SystemExit('Receipts already finalized for this candidate; refusing to rewrite their pinned proof.')
    assert not git(repo,'status','--porcelain')
    paths=[p for p in git(repo,'diff','--name-only',receipt['source_base'],'HEAD').splitlines()
           if p != 'SOURCE_MANIFEST.sha256' and not p.startswith('docs/')]
    rows=[]
    for path in paths:
        current=git(repo,'rev-parse','HEAD:'+path)
        combined=git(repo,'rev-parse',SOURCE+':'+path)
        assert current == combined, path
        row={'path':path,'role_blob':current,'combined_blob':combined}
        if role == '068':
            old=git(repo,'rev-parse',OLD_DEVICE['source_sha']+':'+path)
            assert current == old, path
            row['old_3b_device_blob']=old
        rows.append(row)
    proof['roles'][role]={'role_head_before_receipt':git(repo,'rev-parse','HEAD'),
        'frozen_role_input_sha':inputs[role], 'files':rows,
        'all_non_doc_nonmanifest_blobs_match_frozen_combined':True}
    if role == '068':proof['roles'][role]['all_parser_and_corpus_blobs_match_old_device_candidate']=True
proof_path=ROOT/'061-068-final-evidence-proof.json'
proof_path.write_text(json.dumps(proof,indent=2)+'\n')
proof_ref=hashed(proof_path)

environment='Managed Linux x86_64; JDK17.0.16+8, Gradle8.13, Kotlin2.1.21, SDK36, NDK28.2.13676358; both Android29 native ABIs. Build evidence is distinct from device execution.'
dataset='Committed analytical/synthetic regression fixtures and exact packaged artifacts; no physical reconstruction accuracy or performance qualification.'
app=FINAL['artifacts']['app/build/outputs/apk/debug/app-debug.apk']['sha256']
test_app=FINAL['artifacts']['app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']['sha256']
for role in ['061','068']:
    repo=Path('/workspace/agent-work')/role
    path=repo/f'docs/agents/status/{role}.md'
    receipt=yaml.safe_load(path.read_text())
    for row in receipt['acceptance_results']:
        row['historical_evidence']=True
        if row['criterion'].startswith('Combined host native regression'):
            row['limitation']+=' Historical Release baseline test compiles lacked -UNDEBUG; do not reuse this row as assertion-enabled native evidence. The c5 execution/e290 equality row supersedes that claim.'
    receipt['prior_evidence_notes']='Historical implementation/standalone evidence below retains its original candidate/time scope. Earlier NOT_RUN and build-adapter statements describe that earlier attempt; the fresh structured e290 and old-3b device rows below supersede current-status claims.\n\n'+receipt['prior_evidence_notes']
    receipt['production_implementation']=receipt.get('production_implementation',receipt['source_candidate'])
    receipt['latest_combined_source']=SOURCE
    common=dict(source_sha=SOURCE,role_input_sha=inputs[role],environment=environment,dataset_identity=dataset,matched_code_proof=proof_ref)
    android=dict(common,criterion='Fresh C16 combined application/test APK, JVM, lint, offline and 16KiB packaging qualification',result='PASS',
        command='; '.join(shlex.join(c['command']) for c in FINAL['commands']),
        artifact_sha256=app,test_apk_sha256=test_app,evidence=str(VALIDATION/'result.json'),
        result_json_sha256=hashed(VALIDATION/'result.json')['sha256'],
        jvm_tests={k:FINAL['jvm_tests'][k] for k in ['tests','failures','errors','skipped']},
        lint={'errors':0,'warnings':28},packaging={'internet_permission':False,'elf_libraries_16kb_checked':16,'zip_alignment_16kb':True,'new_native_source_symbol_families_both_abis':7},
        verification_adapters=FINAL['verification_adapters'],
        owner004_permanent_fix={'supplying_sha':inputs['004'],'actual_build_script_used_unchanged':True,'external_kotlin_build_script_import_patch_used':False},
        tracked_source_files=579,tracked_source_digest_sha256=FINAL['tracked_source_digest_sha256'],
        limitation='Only seven-source CMake and pinned Filament/gltfio1.56.0 dependency registrations remain external verification adapters. Actual owner004 permanent wrapper/build-script fix is consumed. No handwritten stubs/source substitution; compile and packaged symbols do not establish JNI/application routing, physical/device UI or performance acceptance.')
    python=dict(common,criterion='Fresh full combined Python suite, no exclusions; all failures retained',result='FAIL',
        command=shlex.join(PYTEST['command']['command']),artifact_sha256=PYTEST['xml']['sha256'],
        evidence=str(VALIDATION/'pytest-result.json'),result_json_sha256=hashed(VALIDATION/'pytest-result.json')['sha256'],
        scope_is_full_pytest_suite=True,passed=187,failed=7,errors=0,skipped=0,total=194,
        failed_cases=[r['nodeid'] for r in PYTEST['failed_cases']],new_or_unclassified_failures=[],
        source_unchanged=True,source_digest_sha256=PYTEST['tracked_source_digest_sha256'],
        failure_classification='Seven retained source-literal failures, including owner009 M18 compatibility-domain spelling with independent actual JVM parity coverage. No deselection, xfail, skip, assertion weakening or conversion of failures to success; owners094/053/009 receiving-owner gates remain open.')
    native=dict(common,criterion='Assertion-enabled native execution at c5, admitted to e290 by exact native source and actual library byte equality',result='PASS',
        command='ctest --test-dir /workspace/campaign-061-080/native-harness/build --output-on-failure',
        artifact_sha256=NATIVE['host_library']['sha256'],evidence=str(VALIDATION/'native-proof-reference.json'),
        result_json_sha256=hashed(VALIDATION/'native-proof-reference.json')['sha256'],
        tested_native_source_commit=NATIVE['tested_native_source_commit'],
        ctest_execution_reused_by_exact_native_input_and_library_equality=True,ctest_passed=17,
        assertion_enabled_test_compile_count=17,all_final_assertion_flags_UNDEBUG=True,
        native_tracked_inputs_compared=55,all_native_inputs_and_host_library_match_current_candidate=True,
        native_execution_proof=NATIVE['native_assertion_proof'],ctest_log=NATIVE['ctest_log'],copied_host_library=NATIVE['host_library'],
        limitation='The17 assertion-enabled CTests ran at c5, not freshly at e290. Every native tracked input/set and the actual copied host library match; this row does not claim JNI routing, physical ARM64 runtime, target-tool quality or measured reconstruction accuracy.')
    physical=dict(common,criterion='Physical capture/accuracy, manual accessibility/adaptive/locale and complete latest-candidate UI/runtime acceptance',result='NOT_RUN',
        command='Run qualified owner080/100 physical/operator/manual and complete integrated runtime matrix',artifact_sha256=None,
        evidence='Role-specific manual/hardware gates remain open; current software-device attempts have independent exact source/APK/class identities.',
        limitation='No physical capture, sustained fuzz campaign, maximum-asset CPU/GPU/frame budget, complete interruption matrix or DONE claim follows from shared build/native/corpus results.')
    fresh=[android,python,native]
    if role == '068':
        c=next(c for c in OLD_DEVICE['classes'] if c['class'].endswith('a068.GlbParserCorpusTest'))
        assert c['result']['status']=='PASS' and c['result']['passed']==1
        command=next(c for c in OLD_DEVICE['commands'] if c.get('log')=='class-0.log')
        artifacts=list(OLD_DEVICE['artifacts'].items())
        fresh.append(dict(criterion='Actual Android parser corpus on explicitly older 3b candidate, matching unchanged parser and corpus Git blobs',result='PASS',
            command=shlex.join(command['command']),source_sha=OLD_DEVICE['source_sha'],
            role_input_sha=inputs[role],production_implementation=receipt['production_implementation'],
            artifact_sha256=artifacts[0][1],test_apk_sha256=artifacts[1][1],
            environment='Actual API29 x86_64 software emulator-5556; synthetic test fixtures, no physical hardware capture.',
            dataset_identity='232 bounded malformed/valid GLB/PNG/cancellation/lifecycle corpus assertions inside one AndroidJUnit test; analytical and canonical/legacy synthetic writer fixtures.',
            evidence=str(ROOT/'device-3b9f915/receipt.json'),receipt_sha256=hashed(ROOT/'device-3b9f915/receipt.json')['sha256'],
            instrumentation_stdout=hashed(ROOT/'device-3b9f915/class-0.log'),
            junit_passed=1,junit_failed=0,junit_skipped=0,parser_corpus_assertions=232,
            test_identity=c['result']['test_identities'][0],matching_parser_and_corpus_blobs_proof=proof_ref,
            limitation='Execution occurred on3b APKs, not e290. Exact parser and corpus source/test blobs equal the frozen e290 input, but this is not a new whole-candidate/device/UI/render/physical pass. Original232 host assertions and standalone Android evidence retain their own scope.'))
    fresh.append(physical)
    receipt['acceptance_results']=fresh+receipt['acceptance_results']
    receipt['remaining_work']=[
        'Owner004 must commit the seven-source native CMake registration and pinned Filament/gltfio dependency registration; its permanent wrapper/build-script fix is already consumed without external Kotlin import patch.',
        'Owners094/053/009 must resolve the seven retained full-suite literal gates without weakening protected behavior; current full suite remains FAIL187/7.',
        'Receiving owners must complete actual JNI/materializer/provenance/publication and durable provider integration; packaged native symbols and isolated format-reader checks do not establish application routing.',
        'Physical/manual/resource-budget/sustained-fuzz and complete latest-candidate device/UI matrices remain separate open gates. Older3b parser execution is identified explicitly, not relabeled as e290 acceptance.'
    ]
    receipt['external_blockers']=['Final owner100 receiving-owner integration and acceptance remain open; seven full-suite failures and remaining manual/hardware/runtime gates are preserved.']
    receipt['next_action']='Integrate actual receiving-owner registrations and adapters, resolve preserved full-suite failures and qualify the complete latest candidate plus physical/manual gates. This metadata-only delivery does not mark the assignment DONE.'
    receipt['c16_evidence_scope']='Fresh e290 Android236JVM/lint0errors28warnings/APKs and fullPython187PASS7FAIL/194 are separate from historical runs. Native17 assertion-enabled CTests executed atc5;55native tracked inputs and actual.so bytes match e290. All role source/test blobs match frozen e290, and068 parser/corpus also match old3b. This commit changes only this receipt and the mechanically regenerated staged manifest.'
    path.write_text(yaml.safe_dump(receipt,sort_keys=False,allow_unicode=True,width=110))
print(json.dumps({'proof':proof_ref,'roles':{r:len(proof['roles'][r]['files']) for r in proof['roles']}}))
