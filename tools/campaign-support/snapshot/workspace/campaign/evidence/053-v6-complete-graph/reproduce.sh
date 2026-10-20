#!/usr/bin/env bash
set -euo pipefail
pinned_checkout="${1:-/workspace/agents/053}"
evidence_root="$(cd -- "$(dirname -- "$0")" && pwd)"
test "$(git -C "$pinned_checkout" rev-parse HEAD)" = 5ef93abd39b46778ec57c51677c2ba5c6efdcb9f
(cd "$evidence_root" && sha256sum -c SHA256SUMS)
task_tmp="$(mktemp -d /tmp/agent053-graph-replay.XXXXXX)"
trap 'rm -rf -- "$task_tmp"' EXIT
g++ -std=c++20 -O2 -DNDEBUG -Wall -Wextra -Wpedantic -Werror "-I$pinned_checkout/reconstruction/include" "$evidence_root/graph.cpp" "-L$evidence_root" "-Wl,-rpath,$evidence_root" -lrealityforge -o "$task_tmp/graph"
"$task_tmp/graph" "$evidence_root/input.bin"
