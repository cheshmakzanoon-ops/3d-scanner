#!/bin/sh
# Run from any directory; args are repository root and fresh scratch output root.
set -eu
if [ "$#" -ne 2 ]; then
  echo "Usage: $0 REPOSITORY EMPTY_OUTPUT_DIRECTORY" >&2
  exit 2
fi
REPO=$(cd "$1" && pwd -P)
if [ ! -f "$REPO/CMakeLists.txt" ] || [ ! -d "$REPO/reconstruction" ]; then
  echo "Not a RealityForge source repository: $REPO" >&2
  exit 2
fi
# Resolve the output physically before creating any build/source artifacts.
# Require an existing empty directory (e.g. mktemp -d); never create a rejected target.
if [ ! -d "$2" ]; then
  echo "Output must be an existing empty directory: $2" >&2
  exit 2
fi
RUN=$(cd "$2" && pwd -P)
case "$RUN/" in
  "$REPO/"*) echo "Output must be outside the source repository: $RUN" >&2; exit 2 ;;
esac
if [ -n "$(find "$RUN" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  echo "Output directory must be empty: $RUN" >&2
  exit 2
fi
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BASE=b838b3c69d1a7a03bc3be3eb2cb9f099b8bc4df2
mkdir -p "$RUN/baseline-source"
git -C "$REPO" archive "$BASE" | tar -x -C "$RUN/baseline-source"
for CASE in baseline canonical; do
  SOURCE="$REPO"
  [ "$CASE" != baseline ] || SOURCE="$RUN/baseline-source"
  cmake -S "$SOURCE" -B "$RUN/$CASE-build" -DCMAKE_BUILD_TYPE=Release -DRF_REQUIRE_CAMPAIGN_031_060=ON
  cmake --build "$RUN/$CASE-build" --target realityforge -j2
  c++ -std=c++20 -O2 -I"$SOURCE/reconstruction/include" "$HERE/controls.cpp" \
    -L"$RUN/$CASE-build" -lrealityforge -Wl,-rpath,"$RUN/$CASE-build" -o "$RUN/$CASE-controls-runner"
  mkdir -p "$RUN/$CASE-controls"
  (cd "$RUN/$CASE-controls" && "$RUN/$CASE-controls-runner" > output.txt)
done
python3 "$HERE/coverage.py" "$RUN"
python3 "$HERE/compare-controls.py" "$RUN"
