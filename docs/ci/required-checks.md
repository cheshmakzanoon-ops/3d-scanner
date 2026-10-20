# Exact-candidate CI contract

Require these four jobs of **Verify RealityForge** for the same candidate SHA:
`native`, `android`, `device`, and `candidate-evidence`. The final job executes
with `always()` and fails if any dependency failed, skipped or was cancelled.
Repository protection settings were not queried successfully or changed; an
administrator must configure these names if protected merges are desired.

The workflow runs on PR merge candidates, pushes to main/agents/integration
branches and manual dispatch. A PR tests GitHub's checked-out merge SHA, not an
unmerged head assertion. Agent 100 must form a combined integration candidate
and let all four checks execute again on that new SHA before merging. Individual
branch passes never prove a combined tree. No merge queue is assumed/configured,
so no `merge_group` trigger is installed. If a queue is later enabled, its event
must execute the same checks on the actual queue candidate before use.

Obsolete same-ref/PR runs cancel through concurrency. Native/Android/emulator/
aggregate jobs are bounded to 60/45/40/10 minutes. Fork PRs use read-only contents
permissions, no secrets, checkout credentials disabled, and read-only Gradle cache.
Actions are pinned to immutable commits resolved from their release tags during
implementation. No write API credentials, privileged deployment or main push is
performed by this workflow.

## Executed gates and specialist ownership

- Native consumes Agent 081 `tools/qa/native_regression/run.py` with its default
  GCC/Clang Release and Debug ASan/UBSan matrix. It retains every existing CTest,
  Python, synthetic three-photo BA, eight-view coverage and real Middlebury
  stereo/dense/surface/photographic texture gate. Compiler parity must pass.
  A missing compiler, a missing report or a sanitizer failure fails the job.
- Android preserves JDK17, Gradle8.13, platform36/build-tools36.0.0,
  NDK28.2.13676358, CMake3.22.1 from Agent004 build pins; it actually compiles both
  APKs, runs JVM tests/lint, checks merged INTERNET permission and 16KB ELF/ZIP
  alignment. This is no claim of 16KB runtime or physical-camera compatibility.
- Device consumes Agent092's serial-bound runner on API35/x86_64 emulator with
  the exact APK pair produced by Android in this same workflow run. It requires
  nonzero completed instrumentation, measured emulator identity and the newly
  produced synthetic full-session photographic screenshot. APK hashes must
  match between build and installed-candidate evidence. PRs run this gate too.
- Agent094 validates the exact tracked source manifest before source gates;
  untracked build outputs are never source identity. Its own source-snapshot
  workflow retains source archive/tree/hash records separately.

These consumers require the compatible 081/092/094 implementations integrated
into the candidate. Their owners' branches are never merged into Agent093's
exclusive delivery branch; Agent100 integrates the immutable deliveries.

## Portable evidence

`python3 tools/ci/validation/evidence.py begin` binds a new gate execution to
checked-out HEAD and a `run_id:run_attempt`. Run it before generating reports.
`collect` rejects stale/empty/missing files, failed/zero/all-skipped JUnit,
missing compiler configurations, absent CTest completion, command failures,
invalid PNG, failed APK validation and mismatched installed APK hashes. Each
sealed bundle contains `evidence.json` plus hashed files copied into `files/`.
The collector preserves partial skips separately from passes. It never turns
an unavailable device into a pass or claims a physical result from an emulator.
Device APKs can predate device startup because the prior Android job built them;
the aggregate enforces exact same-run identities across both gates instead.

`verify --directory DIR --source-sha SHA --run-id RUN:ATTEMPT --output SUMMARY`
requires exactly the three runtime gate bundles. It reparses actual JUnit and
instrumentation, verifies every content hash/size, all native command/log/binary
references, the PNG and APK validation, and compares APK identities across jobs.
Unknown/stale/duplicate gate records or missing artifacts fail. Artifact names
include source SHA and run attempt; downloads are scoped to the current workflow
run. Evidence is unsigned provenance under the Actions trust model, not an
independent attestation against an attacker who controls the workflow itself.

`candidate-evidence.json` is schema `realityforge.ci-evidence.v1`, includes source
SHA/run identity, per-gate tests and evidence kind, and explicitly retains
`physical_device: NOT_RUN`. Consumers099/100 may index this record only with its
underlying sealed artifacts and actual successful run URL. A local syntax/test
pass does not supply a hosted run URL or close physical release gates.

## Local validation

```bash
python3 -m pytest tests/test_ci_evidence_validation.py -q
actionlint .github/workflows/verify.yml
```

The test suite uses labeled synthetic evidence files to verify corruption and
failure rejection. It does not execute or certify an Android device. Actual
hosted PR and combined-candidate runs remain required; GitHub API Forbidden in
this environment prevents observing/dispatching those runs here. Agent081 has
reported Python/NumPy LeakSanitizer baseline leaks; these remain visible failures,
with detection enabled, until resolved by the owning dependency/toolchain work.
