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
  After installing the baseline requirements, Agent095's QA-only installer runs
  after the native start marker and before the matrix:
  `python tools/dependencies/install_qa.py --root "$RUNNER_TEMP/rf-qa" --github-env "$GITHUB_ENV" --github-path "$GITHUB_PATH" --require-portable-blender`.
  Its isolated, pinned scientific/pytest environment includes Trimesh4.5.3;
  official Node24.19.0 and Khronos gltf-validator2.0.0-dev.3.10, Blender4.3.2 and
  a separate pinned security-scanner environment satisfy the real090/096 tools.
  `RF_GLTF_VALIDATOR`, `RF_SECURITY_PYTHON` and `SCANNER_PYTHON` plus PATH persist
  to later steps through the GitHub environment/path files. Publisher/package
  checksums stay enforced; installer failure prevents native tests from starting.
  The required installation.json receipt, install/check logs and Blender publisher
  checksum list are fresh, sealed and hashed with native artifacts, and retained
  for diagnosis on failure. Installation writes only
  under RUNNER_TEMP and user caches; Android/app/runtime dependency pins stay
  under their existing owners.
- Android preserves JDK17, Gradle8.13, platform36/build-tools36.0.0,
  NDK28.2.13676358, CMake3.22.1 from Agent004 build pins; it actually compiles both
  APKs, runs JVM tests/lint, checks merged INTERNET permission and 16KB ELF/ZIP
  alignment. This is no claim of 16KB runtime or physical-camera compatibility.
- Device consumes Agent092's serial-bound runner with `--scope debug` on the
  API35/x86_64 emulator, using
  the exact APK pair produced by Android in this same workflow run. It requires
  completed debug compatibility instrumentation, measured emulator identity and
  the newly produced synthetic full-session photographic screenshot. The exact
  session and native/page-size compatibility selectors must actually pass.
  All ordinary baseline/new tests remain discovered; only source-declared
  specialist selectors use AndroidJUnitRunner notClass exclusions. APK hashes must
  match between build and installed-candidate evidence. PRs run this gate too.
  The generic debug run excludes required physical capture, externally controlled
  recovery, physical resource profiling, externally verified offline radios and
  minified-release contracts. Every excluded gate remains `NOT_RUN`, with its
  selector and reason, and needs its owner's separate qualification. A generic
  compatibility or 16KB runtime pass never qualifies those specialist gates.
- Agent094 validates the exact tracked source manifest before source gates;
  untracked build outputs are never source identity. Its own source-snapshot
  workflow retains source archive/tree/hash records separately.

These consumers require the compatible 081/092/094/095 implementations integrated
into the candidate. Their owners' branches are never merged into Agent093's
exclusive delivery branch; Agent100 integrates the immutable deliveries.

## Portable evidence

`python3 tools/ci/validation/evidence.py begin` binds a new gate execution to
checked-out HEAD and a `run_id:run_attempt`. Run it before generating reports.
`collect` rejects stale/empty/missing files, failed/zero/all-skipped JUnit,
missing compiler configurations, absent CTest completion, command failures,
invalid PNG, failed APK validation and mismatched installed APK hashes. Native
evidence must include all six numerical/benchmark JSON reports. Sealed output
directories cannot overwrite earlier evidence. Each
sealed bundle contains `evidence.json` plus hashed files copied into `files/`.
The collector independently parses AndroidJUnitRunner1.6.2 raw start/terminal
events: code0 passes, -3 ignored and -4 assumptions remain skips; -1/-2 fail.
The transcript must finish with a unique `INSTRUMENTATION_CODE: -1` and contain
one standalone OK summary; embedded text or truncated transcripts cannot pass. It
requires complete unique events, declared test counts and contiguous current
indices, compares the producer counts, and requires at least one actual pass.
The collector binds Agent092's `suites.json` manifest to the exact candidate Git
blob and seals it with the device record. The verifier requires debug scope,
matching manifest hash/selectors, actual required selector passes and absence of
excluded selector events, including skipped events. Missing or overstated scope
records fail validation.
JUnit OK summaries exclude ignored tests but include assumptions, so their total
is never relabeled as passes. API35/emulator-5554/x86_64 identity is checked.
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
`physical_device: NOT_RUN`, `release_qualification: NOT_RUN`, and all five
`required_specialist_gates` rows with `status: NOT_RUN`. The same complete suite
record remains under the device gate. A top-level CI `status: PASS` covers only
the declared host/build/debug-compatibility checks; independent099 must require
separate specialist receipts before release qualification. Consumers099/100 may
index this record only with its
underlying sealed artifacts and actual successful run URL. A local syntax/test
pass does not supply a hosted run URL or close physical release gates.

## Local validation

```bash
python3 -m pytest tests/test_ci_evidence_validation.py -q
actionlint .github/workflows/verify.yml
```

The test suite uses labeled synthetic evidence files to verify corruption and
failure rejection. It does not execute or certify an Android device. Actual
hosted PR and combined-candidate runs remain required. GitHub API Forbidden
prevents API dispatch/PR creation here; the public web UI independently showed
branch run39392960956 queued on01f48ddf, without any success claim. Agent081 has
reported Python/NumPy LeakSanitizer baseline leaks; these remain visible failures,
with detection enabled, until resolved by the owning dependency/toolchain work.
