#!/bin/sh
# QA-only: build the frozen baseline in a fresh external workspace.
set -eu
[ "$#" -eq 2 ] || { echo "Usage: $0 REPOSITORY EXISTING_EMPTY_OUTPUT" >&2; exit 2; }
REPO=$(cd "$1" && pwd -P)
[ -f "$REPO/CMakeLists.txt" ] && [ -d "$REPO/reconstruction" ] || { echo "Invalid source repository" >&2; exit 2; }
[ -d "$2" ] || { echo "Output must already exist and be empty" >&2; exit 2; }
RUN=$(cd "$2" && pwd -P)
case "$RUN/" in "$REPO/"*) echo "Output must be outside repository" >&2; exit 2;; esac
[ -z "$(find "$RUN" -mindepth 1 -maxdepth 1 -print -quit)" ] || { echo "Output must be empty" >&2; exit 2; }
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
mkdir "$RUN/source" "$RUN/kernel"
git -C "$REPO" archive c30e123df1d64414417a84d19807918a06cf6390 | tar -x -C "$RUN/source"
cmake -S "$RUN/source" -B "$RUN/build" -DCMAKE_BUILD_TYPE=Release -DRF_REQUIRE_CAMPAIGN_031_060=ON
cmake --build "$RUN/build" --target realityforge -j2
for FILE in test_fail_closed.py invariant_checks.py chart_overlay.py make_controls.py run_controls.py audit_results.py native_baseline.cpp native_topology.cpp; do cp "$HERE/$FILE" "$RUN/kernel/$FILE"; done
for NAME in native_baseline native_topology; do
 c++ -std=c++20 -O2 -I"$RUN/source/reconstruction/include" "$RUN/kernel/$NAME.cpp" \
  -L"$RUN/build" -lrealityforge -Wl,-rpath,"$RUN/build" -o "$RUN/kernel/$NAME"
done
python3 -m venv "$RUN/venv"
"$RUN/venv/bin/python" -m pip install --index-url https://pypi.org/simple -r "$HERE/requirements-qa.txt"
"$RUN/venv/bin/python" "$RUN/kernel/run_controls.py" "$RUN/results"
"$RUN/venv/bin/python" "$RUN/kernel/audit_results.py" "$RUN/results"
"$RUN/venv/bin/python" "$RUN/kernel/test_fail_closed.py" "$RUN/results"
