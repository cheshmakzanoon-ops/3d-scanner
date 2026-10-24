# Parallel native evidence gate

Verify runs the unchanged GCC/Clang release and sanitizer profiles once each in
parallel. The `native` job remains the aggregate check, and `candidate-evidence`
still requires it alongside Android, device and benchmark-build evidence.

Each producer validates its source manifest, records its exact repository/source/run/attempt
and profile before execution, and seals command logs, binaries, test reports and
QA installation records on that producer. Failed or incomplete profiles remain
failed and retain diagnostic uploads; another successful profile never waives a
failure. The matrix uses `fail-fast: false`.

The aggregate downloads separate current-run artifact directories, verifies the
original sealed evidence against the explicit `GITHUB_REPOSITORY` identity, and recomputes the existing numerical comparisons.
Downloaded file timestamps do not establish execution freshness. Original paths
are interpreted within each producer's own provenance namespace, never flattened
across workers. Final candidate verification revalidates this native evidence;
its NumPy dependency comes from the existing hash-locked host requirements.

Verify retains its stable per-ref cancellation group. The standalone native
workflow handles only push branches outside `main`, `agents/**`, and
`integration/**`, plus explicit manual diagnostics. Verify handles the overlapping
push branches and all pull requests. This partition avoids duplicate automatic
native workloads without dropping coverage on other push branches. A push and a
pull-request event are still distinct events; this change does not cross-reuse
evidence between runs or authorize changes to branch rules.

Before publication, recheck the repository's actual required checks and rulesets.
Static workflow tests establish wiring and event coverage only, not successful
GitHub execution, sanitizer cleanliness, Android emulation or device qualification.

## Transport resource and stability bounds

Sealed payload enumeration rejects symlinks/special files and is bounded to 4,096
files, 8,192 entries, 8 GiB total, 1 GiB per file, depth 16, and 30 seconds per
inventory. These bound retained logs/binaries rather than raw build directories;
exceeding a limit is a failure, never truncation or a successful partial gate.
Producer files retain their original mtime in the seal. Before validation and
again after copying, the sealer compares no-follow file-descriptor identity,
size, mtime, ctime and SHA-256; copied bytes must match the original snapshot.
Downloaded mtimes have no execution-freshness meaning. Each shard must retain
installation.json, publisher hash, install logs and tool-check logs.

Successful commands may legitimately emit zero stdout/stderr bytes. Only command
logs explicitly bound to PASS, exit 0, no execution-failure reason and the exact
recorded log hash may carry the `command-log` role and be empty. Their original
freshness, regular-file identity, transported size/hash and command receipt are
still checked. JSON, XML, binaries and QA evidence remain nonempty. Existing
nonempty command logs with the original `artifact` role remain valid. Failure
uploads retain numerical/benchmark JSON and pytest XML alongside logs, not raw
build directories.
