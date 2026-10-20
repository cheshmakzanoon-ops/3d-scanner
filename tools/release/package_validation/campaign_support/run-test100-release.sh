#!/usr/bin/env bash
set -euo pipefail
source /workspace/.setup/3d-scanner/env.sh
cd /workspace/campaign/test100-release
release_evidence="${RF_RELEASE_EVIDENCE:-/workspace/campaign/evidence/100/release-final}"
export RF_RELEASE_EVIDENCE="$release_evidence"
python - <<'CHECK'
import json,os,subprocess
from pathlib import Path
root=Path(os.environ["RF_RELEASE_EVIDENCE"])
source=json.loads((root/"tested-source.json").read_text())
if source["testedOverlayCommit"]!=subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip():raise SystemExit("Source/evidence identity mismatch")
if (root/"release-integration-summary.json").exists():raise SystemExit("Refusing to overwrite an immutable completed report")
CHECK
export CMAKE_BUILD_PARALLEL_LEVEL=1
export RF_DEPENDENCY_EXPORT="$release_evidence/resolved.json"
python - <<'COMMAND'
import json,os
from pathlib import Path
args=['--dependency-verification','strict','-I','tools/dependencies/export.gradle','-PrealityForgeTestBuildType=release','-PcandidateSigning=test',':app:assembleRelease',':app:bundleRelease',':app:assembleReleaseAndroidTest',':app:testReleaseUnitTest',':app:lintRelease',':app:exportDependencyInventory',':app:assembleBenchmark',':macrobenchmark:assembleBenchmark']
(Path(os.environ['RF_RELEASE_EVIDENCE'])/'gradle-command.json').write_text(json.dumps({'wrapper':'/workspace/campaign/gradle.py','arguments':args,'lock':'/workspace/campaign/android.lock'},indent=2)+'\n')
COMMAND
flock /workspace/campaign/android.lock python /workspace/campaign/gradle.py \
  --dependency-verification strict -I tools/dependencies/export.gradle -PrealityForgeTestBuildType=release -PcandidateSigning=test \
  :app:assembleRelease :app:bundleRelease :app:assembleReleaseAndroidTest \
  :app:testReleaseUnitTest :app:lintRelease :app:exportDependencyInventory \
  :app:assembleBenchmark :macrobenchmark:assembleBenchmark \
  > "$release_evidence/gradle.log" 2>&1
python /workspace/campaign/verify-test100-release-signers.py > "$release_evidence/signer-agreement.log" 2>&1
python tools/device/ui_performance/validate_target.py \
  --target-apk app/build/outputs/apk/benchmark/app-benchmark.apk \
  --benchmark-apk macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk \
  --mapping app/build/outputs/mapping/benchmark/mapping.txt \
  --output "$release_evidence/benchmark-target-validation.json" \
  > "$release_evidence/benchmark-target-validation.log" 2>&1
python - <<'ISOLATION'
import json,os,subprocess,zipfile
from pathlib import Path
root=Path(os.environ['RF_RELEASE_EVIDENCE'])
apk=Path('app/build/outputs/apk/release/app-release.apk')
analyzer=Path(os.environ['ANDROID_HOME'])/'cmdline-tools/latest/bin/apkanalyzer'
manifest=subprocess.check_output([str(analyzer),'manifest','print',str(apk)],text=True,stderr=subprocess.PIPE)
if 'com.realityforge.scanner.bench97.BenchmarkViewerActivity' in manifest:raise ValueError('Benchmark activity leaked into production release')
for path in [apk,Path('app/build/outputs/bundle/release/app-release.aab')]:
 with zipfile.ZipFile(path) as package:
  if any(name.startswith(('assets/a097-','base/assets/a097-')) for name in package.namelist()):raise ValueError('Synthetic benchmark assets leaked into production release')
(root/'benchmark-production-isolation.json').write_text(json.dumps({'status':'PASS','releaseManifestExcludesBenchmarkActivity':True,'releaseApkAndBundleExcludeSyntheticBenchmarkAssets':True,'performanceQualification':'NOT_RUN'},indent=2)+'\n')
ISOLATION
python tools/dependencies/inventory.py verify "$release_evidence/resolved.json" \
  --inventory tools/dependencies/evidence/inventory.json \
  > "$release_evidence/resolved-verification.log" 2>&1
python tools/release/package_validation/candidate.py --mode test \
  --bundletool /workspace/campaign/evidence/091/bundletool-all-1.18.2.jar \
  --output "$release_evidence/package-validation" \
  > "$release_evidence/package-validation.log" 2>&1
release_test_source=$(git rev-parse HEAD)
python tools/device/offline_privacy/audit.py \
  --apk app/build/outputs/apk/release/app-release.apk --sdk "$ANDROID_HOME" \
  --source-sha "$release_test_source" --output "$release_evidence/privacy-policy.json" \
  > "$release_evidence/privacy-policy.log" 2>&1
python tools/device/offline_privacy/audit.py \
  --apk "$release_evidence/package-validation/splits/universal/universal.apk" --sdk "$ANDROID_HOME" \
  --source-sha "$release_test_source" --output "$release_evidence/privacy-bundle-universal.json" \
  > "$release_evidence/privacy-bundle-universal.log" 2>&1
for release_package in app/build/outputs/apk/release/app-release.apk app/build/outputs/bundle/release/app-release.aab; do
  package_kind=${release_package##*.}
  python tools/dependencies/inventory.py audit "$release_package" \
    --inventory tools/dependencies/evidence/inventory.json \
    --notices app/src/main/assets/third-party-notices \
    --report "$release_evidence/attribution-$package_kind.json" \
    > "$release_evidence/attribution-$package_kind.log" 2>&1
done
python /workspace/campaign/summarize-test100-release.py
