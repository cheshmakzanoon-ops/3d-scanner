#!/bin/sh
# QA-only replay. Inputs stay read-only; all generated work goes to EMPTY_OUTPUT.
set -eu
unset LD_LIBRARY_PATH LD_PRELOAD LD_AUDIT
[ "$#" -eq 3 ] || { echo "Usage: PYTHON=/path/python $0 REPOSITORY FROZEN_NATIVE_DIR EXISTING_EMPTY_OUTPUT" >&2; exit 2; }
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO=$(cd "$1" && pwd -P)
FROZEN=$(cd "$2" && pwd -P)
[ -d "$3" ] || { echo "Output must already exist and be empty" >&2; exit 2; }
OUT=$(cd "$3" && pwd -P)
case "$OUT/" in "$HERE/"*|"$REPO/"*|"$FROZEN/"*) echo "Output must be outside all input directories" >&2; exit 2;; esac
[ -z "$(find "$OUT" -mindepth 1 -maxdepth 1 -print -quit)" ] || { echo "Output must be empty; nothing removed" >&2; exit 2; }
PY=${PYTHON:-python3}
# Dependency/library/header verification precedes builds or candidate evaluation.
"$PY" - "$REPO" "$FROZEN" "$HERE/identities.json" "$OUT" <<'PY'
import hashlib,json,pathlib,subprocess,sys
import numpy,shapely
repo,frozen,receipt,out=map(pathlib.Path,sys.argv[1:]);expected=json.loads(receipt.read_text());commit=expected['baselineCommit']
if numpy.__version__!='2.2.6' or shapely.__version__!='2.1.2':raise RuntimeError('Require existing QA NumPy 2.2.6 and Shapely 2.1.2; see requirements-qa.txt')
lib=frozen/'librealityforge.so';digest=hashlib.sha256(lib.read_bytes()).hexdigest()
if digest!=expected['librarySha256']:raise RuntimeError('Frozen library identity mismatch; do not substitute a mutable or differently built library')
headers={}
for name,item in expected['archivedHeaders'].items():
 p=frozen/name;data=p.read_bytes();git=subprocess.check_output(['git','-C',str(repo),'show',commit+':'+name])
 if data!=git or hashlib.sha256(data).hexdigest()!=item['sha256']:raise RuntimeError('Frozen header mismatch: '+name)
 headers[name]=item['sha256']
(out/'verified-inputs.json').write_text(json.dumps({'baselineCommit':commit,'libraryPath':str(lib),'librarySha256':digest,'headers':headers,'python':sys.executable,'numpy':numpy.__version__,'shapely':shapely.__version__},indent=2)+'\n')
PY
mkdir "$OUT/kernel"
for FILE in "$HERE"/*.py; do cp "$FILE" "$OUT/kernel/"; done
cp -R "$HERE/regressions" "$OUT/kernel/regressions"
for NAME in native_baseline native_topology; do
 c++ -std=c++20 -O2 -I"$FROZEN/reconstruction/include" "$HERE/$NAME.cpp" -L"$FROZEN" -lrealityforge -Wl,-rpath,"$FROZEN" -o "$OUT/kernel/$NAME"
done
"$PY" - "$OUT/kernel/native_topology" "$FROZEN/librealityforge.so" <<'PYCODE'
import pathlib,subprocess,sys
resolved=subprocess.check_output(['ldd',sys.argv[1]],text=True)
line=next((line for line in resolved.splitlines() if 'librealityforge.so =>' in line),'')
if not line or pathlib.Path(line.split('=>',1)[1].strip().split()[0]).resolve()!=pathlib.Path(sys.argv[2]).resolve():raise RuntimeError('Native adapter does not resolve verified frozen library')
PYCODE
# Preserve complete verification output and propagate failures without pipelines.
(
 echo "Verified baseline c30e123df1d64414417a84d19807918a06cf6390"
 sha256sum "$FROZEN/librealityforge.so" "$OUT/kernel/native_baseline" "$OUT/kernel/native_topology"
 ldd "$OUT/kernel/native_topology"
 "$PY" "$OUT/kernel/diagnose.py"
 "$PY" "$OUT/kernel/run_controls.py" "$OUT/kernel/results"
 "$PY" "$OUT/kernel/audit_results.py" "$OUT/kernel/results"
 "$PY" "$OUT/kernel/test_fail_closed.py" "$OUT/kernel/results"
 "$PY" "$OUT/kernel/exact_support_audit.py"
 "$PY" "$OUT/kernel/extended_checks.py"
 "$PY" "$OUT/kernel/additional_exact_checks.py"
 "$PY" "$OUT/kernel/emitted_regressions.py"
 echo 'All QA checks completed successfully.'
) > "$OUT/final-run.log" 2>&1
printf 'QA replay passed. Evidence: %s\n' "$OUT"
