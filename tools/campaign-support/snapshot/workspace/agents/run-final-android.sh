#!/usr/bin/env bash
set -euo pipefail
source /workspace/cloud-setup/activate.sh
cd /workspace/agents/031
candidate_sha=${1:?Supply the reviewed immutable combined source SHA}
[[ "$candidate_sha" =~ ^[0-9a-f]{40}$ ]]
test "$(git rev-parse "$candidate_sha^{tree}")" = "$(git rev-parse 'HEAD^{tree}')"
git diff --quiet
git diff --cached --quiet
test -f gradle/wrapper/gradle-wrapper.jar
git ls-files --error-unmatch gradle/wrapper/gradle-wrapper.jar > /dev/null
validation_stamp=$(date -u +%Y%m%dT%H%M%SZ)
validation_dir="/workspace/agents/final-artifacts/$candidate_sha-$validation_stamp"
mkdir -p "$validation_dir"
export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:ActiveProcessorCount=2"
export CMAKE_BUILD_PARALLEL_LEVEL=2
printf '%s\n' "$candidate_sha" > "$validation_dir/reviewed-source.sha"
git rev-parse HEAD > "$validation_dir/validation-checkout.sha"
git rev-parse 'HEAD^{tree}' > "$validation_dir/source-tree.sha"
build_command=(./gradlew --no-daemon --max-workers=2 --init-script /workspace/agents/android-bounded.init.gradle
    '-Dorg.gradle.jvmargs=-Xmx3g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
    -Pkotlin.compiler.execution.strategy=in-process
    :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug)
printf '%q ' "${build_command[@]}" > "$validation_dir/command.txt"
printf '\n' >> "$validation_dir/command.txt"
printf '%s\n' "$validation_dir" > /workspace/agents/final-android-current-run.txt
set +e
"${build_command[@]}" > "$validation_dir/gradle.log" 2>&1
gradle_status=$?
set -e
if ((gradle_status!=0)); then
    tail -100 "$validation_dir/gradle.log"
    exit "$gradle_status"
fi
test "$(git rev-parse 'HEAD^{tree}')" = "$(git rev-parse "$candidate_sha^{tree}")"
git diff --quiet
git diff --cached --quiet
python tools/check_apk.py app/build/outputs/apk/debug/app-debug.apk --sdk "$ANDROID_HOME" --report "$validation_dir/apk-validation.json" > "$validation_dir/apk-validation.log" 2>&1
cp app/build/outputs/apk/debug/app-debug.apk "$validation_dir/app-debug.apk"
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$validation_dir/app-debug-androidTest.apk"
cp app/build/reports/lint-results-debug.xml "$validation_dir/lint-results-debug.xml"
cp app/build/reports/lint-results-debug.html "$validation_dir/lint-results-debug.html"
cp -a app/build/test-results/testDebugUnitTest "$validation_dir/junit-results"
python - "$validation_dir" <<'PY'
import hashlib,json,sys,xml.etree.ElementTree as E
from pathlib import Path
folder=Path(sys.argv[1]);root=Path.cwd()
results=[E.parse(p).getroot() for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
counts={key:sum(int(r.get(key,0)) for r in results) for key in ('tests','failures','errors','skipped')}
assert counts['tests']-counts['skipped']>0 and counts['failures']==counts['errors']==0,counts
artifacts={p.name:{'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size} for p in folder.glob('*.apk')}
receipt={'source_sha':(folder/'reviewed-source.sha').read_text().strip(),
 'validation_checkout_sha':(folder/'validation-checkout.sha').read_text().strip(),
 'source_tree_sha':(folder/'source-tree.sha').read_text().strip(),
 'command':(folder/'command.txt').read_text().strip(),
 'jvm_tests':counts,'artifacts':artifacts,
 'cmake_pool_init_sha256':hashlib.sha256(Path('/workspace/agents/android-bounded.init.gradle').read_bytes()).hexdigest(),
 'device_execution':'NOT_RUN by this build; coordinated separately on immutable APK hashes'}
(folder/'validation-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
PY
