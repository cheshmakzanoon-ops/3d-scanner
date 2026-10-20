from pathlib import Path
import json, subprocess, hashlib

repo = Path('/workspace/scratch/16a420565b0d/3d-scanner')
toolchain = Path('/dev/shm/scanner-jvm-checks')
output = Path('/tmp/scanner-jvm-core')
output.mkdir(exist_ok=True)
base = repo/'app/src/main/java/com/realityforge/scanner'
relative = ['engine/ScanQualityReport.kt', 'engine/ScanQualityEvidence.kt',
    'engine/nativebridge/NativeBoundaryFailure.kt', 'engine/nativebridge/NativeResultJson.kt',
    'engine/pipeline/PipelineContract.kt', 'engine/pipeline/ProductionEngineCompatibility.kt',
    'engine/DepthCheckpointIdentity.kt', 'data/operations/ProjectOperation.kt',
    'data/storagebudget/StorageBudget.kt', 'processing/resources/ResourcePolicy.kt',
    'processing/resources/ResourceCheckpointPause.kt', 'data/reopening/PreviewSchemaValidator.kt',
    'data/reopening/PreparedPreviewGeometry.kt']
sources = [base/x for x in relative] + sorted((base/'engine/diagnostics').glob('*.kt'))
tests = sorted((repo/'app/src/test/java/com/realityforge/scanner/agents/a010').glob('*.kt'))
tests += [repo/'app/src/test/java/com/realityforge/scanner'/x for x in [
    'engine/DepthCheckpointIdentityTest.kt', 'agents/a024/PreviewSchemaValidatorTest.kt',
    'agents/a024/PreparedPreviewGeometryTest.kt']]
summary = base/'data/reopening/PreviewUiSummary.kt'
if summary.exists():
    sources.append(summary)
    tests.append(repo/'app/src/test/java/com/realityforge/scanner/agents/a024/PreviewUiSummaryTest.kt')
files = sources + tests
compiler_cp = ':'.join(str(p) for p in sorted(toolchain.glob('*.jar'))
    if p.name != 'kotlinx-coroutines-core-jvm-1.10.2.jar')
runtime_cp = ':'.join(str(toolchain/n) for n in [
    'kotlin-stdlib-2.1.21.jar', 'kotlinx-coroutines-core-jvm-1.10.2.jar', 'json-20250517.jar',
    'junit-4.13.2.jar', 'hamcrest-core-1.3.jar', 'annotations-13.0.jar'])
classes = output/'classes'
command = ['java', '-Xmx1024m', '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', runtime_cp, '-d', str(classes)]
command += [str(p) for p in files]
provenance = {
    'toolchain': json.loads((toolchain/'dependencies.json').read_text()),
    'files': [{'path': str(p.relative_to(repo)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in files],
    'compiler_command': command,
    'runtime_classpath': runtime_cp,
    'test_classes': ['.'.join(str(p.relative_to(repo/'app/src/test/java')).removesuffix('.kt').split('/')) for p in tests],
}
(output/'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
with (output/'compile.log').open('w') as log:
    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
print('Kotlin compile exit:', result.returncode, flush=True)
print((output/'compile.log').read_text()[-14000:], flush=True)
if result.returncode:
    raise SystemExit(result.returncode)
with (output/'junit.log').open('w') as log:
    result = subprocess.run(['java', '-Xmx768m', '-cp', str(classes)+':'+runtime_cp,
        'org.junit.runner.JUnitCore']+provenance['test_classes'], stdout=log, stderr=subprocess.STDOUT)
print((output/'junit.log').read_text(), flush=True)
raise SystemExit(result.returncode)
