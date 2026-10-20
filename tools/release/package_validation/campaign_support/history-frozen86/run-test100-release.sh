#!/usr/bin/env bash
set -euo pipefail
source /workspace/.setup/3d-scanner/env.sh
cd /workspace/campaign/test100-release
release_evidence=/workspace/campaign/evidence/100/release-final
export CMAKE_BUILD_PARALLEL_LEVEL=1
export RF_DEPENDENCY_EXPORT="$release_evidence/resolved.json"
flock /workspace/campaign/android.lock python /workspace/campaign/gradle.py \
  --dependency-verification strict -I tools/dependencies/export.gradle \
  :app:assembleRelease :app:bundleRelease :app:assembleReleaseAndroidTest \
  :app:testReleaseUnitTest :app:lintRelease :app:exportDependencyInventory \
  > "$release_evidence/gradle.log" 2>&1
python /workspace/campaign/verify-test100-release-signers.py > "$release_evidence/signer-agreement.log" 2>&1
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
