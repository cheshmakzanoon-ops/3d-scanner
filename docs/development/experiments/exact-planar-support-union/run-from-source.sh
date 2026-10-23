#!/bin/sh
# New-evidence replay from locally available pinned source; no network operations.
set -eu
unset LD_LIBRARY_PATH LD_PRELOAD LD_AUDIT
[ "$#" -eq 2 ] || { echo "Usage: PYTHON=/path/python CMAKE=/path/cmake $0 REPOSITORY EXISTING_EMPTY_OUTPUT" >&2; exit 2; }
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO=$(cd "$1" && pwd -P)
[ -d "$2" ] || { echo "Output must already exist and be empty" >&2; exit 2; }
OUT=$(cd "$2" && pwd -P)
case "$OUT/" in "$HERE/"*|"$REPO/"*) echo "Output must be outside input directories" >&2; exit 2;; esac
[ -z "$(find "$OUT" -mindepth 1 -maxdepth 1 -print -quit)" ] || { echo "Output must be empty; nothing removed" >&2; exit 2; }
PY=${PYTHON:-python3}
CMAKE=${CMAKE:-cmake}
COMPILER=$(command -v c++)
BASELINE=c30e123df1d64414417a84d19807918a06cf6390
# Fail before build when prerequisites are absent. No pip/git fetch/download.
"$PY" - <<'PY'
import numpy,shapely
if numpy.__version__!='2.2.6' or shapely.__version__!='2.1.2':raise RuntimeError('Require QA NumPy 2.2.6 and Shapely 2.1.2')
PY
"$CMAKE" --version > "$OUT/cmake-version.txt"
"$COMPILER" --version > "$OUT/compiler-version.txt"
git -C "$REPO" cat-file -e "$BASELINE^{commit}"
mkdir "$OUT/source" "$OUT/native" "$OUT/replay-package" "$OUT/replay"
# Separate archive file avoids a pipeline masking git archive errors.
git -C "$REPO" archive --format=tar -o "$OUT/baseline-source.tar" "$BASELINE"
tar -xf "$OUT/baseline-source.tar" -C "$OUT/source"
"$CMAKE" -S "$OUT/source" -B "$OUT/build" \
 -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER="$COMPILER" \
 -DRF_REQUIRE_CAMPAIGN_031_060=ON -DRF_EXPERIMENTAL_VULKAN=OFF -DRF_SANITIZE=OFF \
 > "$OUT/configure.log" 2>&1
"$CMAKE" --build "$OUT/build" --target realityforge -j2 > "$OUT/build.log" 2>&1
cp "$OUT/build/librealityforge.so" "$OUT/native/librealityforge.so"
mkdir -p "$OUT/native/reconstruction"
cp -R "$OUT/source/reconstruction/include" "$OUT/native/reconstruction/include"
# Reuse unchanged acceptance machinery, with a NEW identity receipt explicitly
# generated from this source build. Never overwrite the historical package.
for FILE in "$HERE"/*.py "$HERE"/native_baseline.cpp "$HERE"/native_topology.cpp "$HERE"/run-final.sh; do cp "$FILE" "$OUT/replay-package/"; done
cp -R "$HERE/regressions" "$OUT/replay-package/regressions"
"$PY" - "$HERE" "$OUT" "$BASELINE" "$COMPILER" <<'PY'
import hashlib,json,pathlib,subprocess,sys
here,out=map(pathlib.Path,sys.argv[1:3]);baseline,compiler=sys.argv[3:]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
historical=json.loads((here/'identities.json').read_text());library=out/'native/librealityforge.so'
headers={}
for name,item in historical['archivedHeaders'].items():
 p=out/'native'/name;digest=sha(p)
 if digest!=item['sha256']:raise RuntimeError('Pinned-source header mismatch: '+name)
 headers[name]={'sha256':digest,'matchesGitBaseline':True}
identity={'evidenceMode':'new_local_source_build','baselineCommit':baseline,'historicalLibraryIdentityClaimed':False,'libraryPath':str(library),'librarySha256':sha(library),'archivedHeaders':headers,'compilerPath':compiler,'compilerExecutableSha256':sha(pathlib.Path(compiler).resolve()),'compilerVersion':(out/'compiler-version.txt').read_text(),'cmakeVersion':(out/'cmake-version.txt').read_text(),'baselineArchiveSha256':sha(out/'baseline-source.tar'),'cmakeCacheSha256':sha(out/'build/CMakeCache.txt'),'cmakeCache':(out/'build/CMakeCache.txt').read_text(),'configureLogSha256':sha(out/'configure.log'),'buildLogSha256':sha(out/'build.log'),'nativeLibraryLdd':subprocess.check_output(['ldd',str(library)],text=True),'qaSourceHashes':{p.name:sha(p) for p in sorted((out/'replay-package').glob('*.py'))},'sourceBuildRunnerSha256':sha(here/'run-from-source.sh'),'acceptanceRunnerSha256':sha(here/'run-final.sh')}
(out/'new-build-identities.json').write_text(json.dumps(identity,indent=2)+'\n')
(out/'replay-package/identities.json').write_text(json.dumps(identity,indent=2)+'\n')
PY
# Both output-local staging identities and actual loader paths are verified by
# the unchanged acceptance runner. Results constitute NEW evidence, not byte
# reproduction of any historical build, even if a hash happens to match.
PYTHON="$PY" sh "$OUT/replay-package/run-final.sh" "$REPO" "$OUT/native" "$OUT/replay" > "$OUT/replay-console.log" 2>&1
"$PY" - "$OUT" <<'PY'
import json,pathlib,sys
out=pathlib.Path(sys.argv[1]);(out/'source-build-result.json').write_text(json.dumps({'mode':'new_local_source_build','historicalLibraryIdentityClaimed':False,'nativeBuildExitCode':0,'qaReplayExitCode':0,'fullNativeCTest':'NOT_RUN','fullRepositoryPytest':'NOT_RUN','androidQualification':'NOT_RUN'},indent=2)+'\n')
PY
printf 'New-source QA replay passed. New evidence: %s\n' "$OUT"
