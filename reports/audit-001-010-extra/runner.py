from pathlib import Path
import json, subprocess, hashlib, re, os, time

repo = Path('/workspace/scratch/16a420565b0d/3d-scanner')
toolchain = Path('/dev/shm/scanner-jvm-checks')
output = Path(__file__).resolve().parent
(output/'tmp').mkdir(exist_ok=True)
os.environ['TMPDIR'] = str(output/'tmp')
base = repo/'app/src/main/java/com/realityforge/scanner'
testbase = repo/'app/src/test/java/com/realityforge/scanner'
relative = [
    'application/OperationCoordinator.kt', 'domain/OperationContext.kt',
    'data/jobs/JobContracts.kt', 'data/jobs/JobStore.kt', 'data/jobs/JobStateMachine.kt',
    'engine/pipeline/PipelineContract.kt', 'engine/pipeline/PipelineGraph.kt',
    'engine/LensGeometry.kt', 'engine/MultiViewTracks.kt', 'engine/IncrementalRegistration.kt',
    'engine/DenseWorkload.kt', 'engine/ScanQualityEvidence.kt',
    'engine/diagnostics/DiagnosticTypes.kt', 'engine/diagnostics/ScanEvidence.kt',
    'data/storagebudget/StorageBudget.kt', 'processing/resources/ResourcePolicy.kt',
    'processing/resources/ResourceWorkloadSelection.kt', 'processing/resources/DenseResourcePlan.kt',
]
sources = [base/x for x in relative] + sorted((base/'engine/artifacts').glob('*.kt'))
testnames = [
    'agents/a001/OperationCoordinatorTest.kt', 'agents/a002/PipelineGraphTest.kt',
    'agents/a007/DurableJobContractsTest.kt', 'agents/a009/AlignmentCheckpointContractTest.kt',
    'agents/a009/ArtifactIdentityPolicyTest.kt', 'agents/a009/StageManifestCodecTest.kt',
    'engine/LensGeometryTest.kt', 'engine/IncrementalRegistrationTest.kt',
    'engine/MultiViewTracksTest.kt', 'engine/DenseWorkloadTest.kt', 'engine/ScanQualityEvidenceTest.kt',
]
tests = [testbase/x for x in testnames]
fixtures = [testbase/'agents/a009/ArtifactProvenanceFixtures.kt']
files = sources + fixtures + tests
assert all(p.is_file() for p in files)
prior = json.loads((repo/'reports/audit-001-030/portable-jvm-provenance.json').read_text())
test_classes = ['.'.join(str(p.relative_to(repo/'app/src/test/java')).removesuffix('.kt').split('/')) for p in tests]
assert not set(test_classes).intersection(prior['test_classes']), 'Do not rerun the prior 44 tests.'
for p in sources:
    assert not re.search(r'^import (android|androidx|org\.opencv)\.', p.read_text(), re.M), p

deps = json.loads((toolchain/'dependencies.json').read_text())
for dep in deps:
    p = toolchain/dep['file']
    assert hashlib.sha256(p.read_bytes()).hexdigest() == dep['sha256'], p
compiler_cp = ':'.join(str(p) for p in sorted(toolchain.glob('*.jar')) if p.name != 'kotlinx-coroutines-core-jvm-1.10.2.jar')
runtime_cp = ':'.join(str(toolchain/n) for n in [
    'kotlin-stdlib-2.1.21.jar', 'kotlinx-coroutines-core-jvm-1.10.2.jar', 'json-20250517.jar',
    'junit-4.13.2.jar', 'hamcrest-core-1.3.jar', 'annotations-13.0.jar'])
classes = output/'classes'
command = ['java', '-Xmx512m', '-Djava.io.tmpdir='+str(output/'tmp'), '-cp', compiler_cp,
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-classpath', runtime_cp, '-d', str(classes)] + [str(p) for p in files]
junit_command = ['java', '-Xmx512m', '-Djava.io.tmpdir='+str(output/'tmp'), '-cp', str(classes)+':'+runtime_cp,
    'org.junit.runner.JUnitCore'] + test_classes
provenance = {
    'scope': 'Supplementary real pure-JVM tests; no Android/Room substitutions, no Gradle/device result.',
    'repository_head': subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),
    'toolchain': deps,
    'files': [{'path': str(p.relative_to(repo)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in files],
    'compiler_command': command,
    'junit_command': junit_command,
    'test_classes': test_classes,
    'selected_test_method_counts': {str(p.relative_to(testbase)): len(re.findall(r'@Test\b', p.read_text())) for p in tests},
    'prior_test_classes_explicitly_excluded': prior['test_classes'],
    'other_exclusions': {
        'agents/a006/ProjectLeaseCoordinatorTest.kt': 'Real ProjectPathPolicy references ScanPhoto in the Room database entity file.',
        'agents/a006/ProjectPathPolicyTest.kt': 'Direct ScanPhoto dependency on the Room database entity file.',
        'agents/a009/ProductionCheckpointFilesTest.kt': 'Real PipelineJobCheckpointValidator references ProjectRepository and Android LensCalibration.',
        'CalibrationTest.kt': 'Real ImageLoader and LensCalibration require Android/OpenCV.'
    },
}
(output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
print('Repository HEAD:',provenance['repository_head'],flush=True)
print('Selected classes:',len(tests),'existing test methods:',sum(provenance['selected_test_method_counts'].values()),flush=True)
started = time.monotonic()
with (output/'compile.log').open('w') as log:
    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
provenance['compile_exit'] = result.returncode
provenance['compile_seconds'] = round(time.monotonic()-started,3)
print('Kotlin compile exit:',result.returncode,'elapsed:',provenance['compile_seconds'],flush=True)
print((output/'compile.log').read_text()[-14000:],flush=True)
(output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
if result.returncode:
    raise SystemExit(result.returncode)
started = time.monotonic()
with (output/'junit.log').open('w') as log:
    result = subprocess.run(junit_command, stdout=log, stderr=subprocess.STDOUT)
provenance['junit_exit'] = result.returncode
provenance['junit_seconds'] = round(time.monotonic()-started,3)
provenance['source_files_unchanged_during_run'] = all(hashlib.sha256((repo/f['path']).read_bytes()).hexdigest()==f['sha256'] for f in provenance['files'])
(output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
print((output/'junit.log').read_text(),flush=True)
print('Source files unchanged:',provenance['source_files_unchanged_during_run'],flush=True)
print('Evidence:',output,flush=True)
raise SystemExit(result.returncode)
