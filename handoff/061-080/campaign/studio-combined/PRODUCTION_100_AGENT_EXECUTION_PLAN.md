# RealityForge: production completion plan for 100 parallel agents

**Repository:** https://github.com/cheshmakzanoon-ops/3d-scanner

**Plan date:** 18 October 2026

**Audited implementation:** `fdd16fce4aa7b1b7d365c0f43e89964d3028bf42` on `main`, version `0.19.0-m19`

**Purpose:** exactly 100 executable assignments, numbered **001–100**, each with its own branch, exclusive source ownership, implementation work and success criteria.

**Plan status at publication:** the audit and instructions are complete; the implementation assignments below are pending. Publishing this document does not implement the remaining product or launch 100 workers.

## Start here: “You are Agent X”

If the user tells you **“You are Agent X; do what the repository tells you and do not stop until success”**, that is an instruction to execute this guide, not to write another plan.

1. Normalize X to three digits: `1` means `001`, `17` means `017`, and `100` means `100`. Accept only 1–100. Read `AGENTS.md`, this guide's shared instructions, and your exact `Agent NNN` section.
2. Read `PROJECT_PLAN.md`, `docs/MILESTONE_STATUS.md` and `docs/NEXT_MILESTONE.md`, treating their old milestone statements as historical where this audit and current code supersede them. Inspect the current implementation before changing it.
3. Use the exact branch in your assignment. Work in your own clone or Git worktree; never switch the branch of a checkout another agent is using.
4. Execute the implementation, integrate its real dependencies, run the applicable checks, fix discovered defects, commit and push your branch. Continue through the role's acceptance criteria rather than stopping after scaffolding, a report or a passing source-string assertion.
5. Record progress in `docs/agents/status/NNN.md`. When you need another owner's file changed, send a precise handoff using the protocol below; do not edit it concurrently.
6. Do not claim success without evidence. If a physical phone, signing credential or authorized dataset is genuinely unavailable, finish all runnable code/harness work, record the exact remaining gate as `BLOCKED_EXTERNAL`/`NOT_RUN`, and state what enables it. Missing evidence is not a pass.

**This campaign explicitly authorizes separate agent branches.** It supersedes the earlier “one main development branch” rule for these numbered assignments. `main` remains the final integration branch. Agents 001–099 never independently merge or force-push `main`; Agent 100 coordinates integration. All other offline, truthful-output, data-preservation and validation requirements in `AGENTS.md` remain in force.

## 1. What the repository already does

This is a working M19 engineering prototype with substantial first-party implementation, not an empty application shell. Preserve its useful foundations:

- Native Kotlin/Compose/CameraX application with local photo import, private originals and Room revision tracking.
- OpenCV SIFT and reciprocal matching; native essential-matrix reconstruction, PnP, multi-view tracks, incremental camera registration and robust joint bundle adjustment with currently fixed intrinsics.
- Bounded multi-camera CPU depth estimation, reciprocal checks, observed open triangle patches, photograph-based UV atlases and exposure correction.
- Local photographic GLES3 viewing, point/surface diagnostic views, GLB/PLY/PNG/JSON export and an original-photo capture archive.
- Per-operation native cancellation for dense/surface/texture work, checksummed per-reference depth checkpoints, guarded reopening/export, and honest M19 scan-evidence diagnostics.
- An actual CI workflow that builds and exercises native and Android code. Source-contract tests already say they do not substitute for runtime evidence.

The audit examined the tracked source, tests, build files, implementation plans and exact-commit GitHub Actions results. **No source implementation was changed as part of this audit. No local Android build or physical-device test was performed for the plan.** This audit environment lacked the Android SDK/emulator and several host test dependencies; the baseline test results below were verified from GitHub Actions, not invented as local runs.

### Baseline evidence

| Evidence | Verified result | Meaning and limit |
|---|---|---|
| [Verification run 38488820626](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/38488820626) | Successful native, Android and device jobs for exact audited SHA | Establishes this commit's configured CI gates, not commercial readiness |
| [Native job](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/38488820626/job/115608888180) | **11/11 CTest targets** and **117 pytest checks** passed; configured real/synthetic benchmark commands passed | The real benchmark is a limited stereo scene; synthetic coverage is not unknown-pose physical 360-degree reconstruction |
| [Android job](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/38488820626/job/115608888429) | Debug APK/test APK compilation, JVM tests, debug lint and APK checks succeeded | No release/R8/AAB/production signing acceptance follows |
| [Device job](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/38488820626/job/115609952400) | **32 instrumentation tests** completed successfully on API 35 x86_64 emulator | Does not measure physical CameraX/lens behavior, ARM64 performance, thermals, battery or real object accuracy |
| Source inventory at audited SHA | 191 tracked files; 190 manifest entries; every listed checksum matched | `SOURCE_MANIFEST.sha256` is the only tracked file excluded from its own manifest |

### Production gaps found in the code

“Missing” below means absent in the inspected implementation. “Unverified” means the repository does not supply the required evidence. “Source risk” identifies a concrete code path requiring a reproducer; it does not claim a crash or data-loss incident was observed on a phone.

| Finding | Source evidence at audited SHA | Remaining work / agents |
|---|---|---|
| Scale and detail remain far below the approved product direction | `SparsePipeline.kt` takes 12 photos; `DenseWorkload.kt` selects at most 8 views with 256/384 px depth edges and a 100,000-sample budget | Scalable graph/features/SfM, bounded tiled processing, honest adaptive profiles and physical evidence: 002,030–052,083–088 |
| Whole-object geometry remains unverified | `PROJECT_PLAN.md`, `docs/NEXT_MILESTONE.md`, stereo/synthetic benchmark harnesses | Licensed calibrated 360-degree inputs, independent reference geometry and per-scene results: 083–087,099 |
| Sparse reconstruction still has numerical/coverage limitations | `bundle_adjustment.cpp` fixes both anchor poses and intrinsics; selection and registration remain bounded | Robust gauge, intrinsic refinement, loop closure, view/track selection and degeneracy handling: 031–044 |
| Pixel-center conventions need reconciliation | `LensGeometry.kt` uses direct principal-point scaling while `DensePreview.kt` uses half-pixel-adjusted scaling | Reproduce with projection/resampling fixtures, then unify the convention: 031 with 002/027 |
| Meshes and textures are still conservative preview outputs | `surface.cpp` creates image-grid patches; `texture.cpp` tiles photographs and selects sources per face; no general LOD/edit/export modules | Fusion/deduplication, normals, topology, safe cleanup, compact UVs, seam blending, LODs and edits: 053–066 |
| Full-session execution is not durable | `MainActivity.onStop()` calls `pause()`; ViewModel holds the task; Room only marks interrupted projects; M18 checkpoints cover reference depth | Durable jobs, validated service lifecycle, full-stage identities/recovery and notifications: 001–010,023,029,050–051,088 |
| Capture completion can use mutable selection | `CameraScreen.kt` callback carries a file; `ScannerViewModel.captureReady()` reads the currently selected project; BackHandler can dismiss an in-flight camera screen | Session/project/revision-bound callbacks and a deterministic late-callback regression: 001/014 |
| Storage has DB/filesystem crash boundaries | `ProjectRepository.delete()` deletes the database row before recursively deleting files; import/publication use separate filesystem and DB steps | Journals, tombstones, leases, recovery and storage-budget tests: 005–007,011,018–026,088 |
| Native failure containment is uneven | Several `jni.cpp` legacy exports allocate outside a complete C++ exception boundary; `dense.cpp`/`surface.cpp` cast voxel coordinates without representable-range checks | JNI exception/resource safety and numeric input checks: 003,046,053,081–082 |
| Point-export metadata can misdescribe dense output | `SparsePipeline.run()` dense-point export path uses `NativeCore.exportPoints`; `core.cpp` embeds sparse labels in the shared point writer | Explicit representation contract and correct independent exports: 003,037,061/064 |
| Cache/publication guarantees need expansion | Feature-cache parsing in `SparsePipeline.kt` is weaker than the M18 depth format; depth cache uses flush+rename without demonstrated power-loss durability | Versioned per-stage identity, checksums, durable writes and safe reuse: 009,020,023,033,050 |
| User workflow remains incomplete | Large `ScannerApp.kt` combines library/project/capture/calibration/export; photo curation, meaningful settings and production studio are missing or limited | Capture guidance, curation, production screens, Filament/studio, exports, localization/accessibility: 011–030,061–080 |
| Release engineering is debug-focused | `app/build.gradle.kts` disables minification; `verify.yml` uses debug builds, and its device job excludes PRs; no release-candidate pipeline | Reproducible wrapper/build, release/R8/AAB/splits/signing validation, current API/ABI and merge gates: 004,081–082,089–097 |
| Documentation and dependency evidence are stale/incomplete | README top says M17, current build says M19; older docs claim absent features now implemented; `THIRD_PARTY_NOTICES.md` says inventory incomplete; no committed Room schema | Migration provenance, exact source manifests, notices/SBOM and current runbooks: 005,094–095,098/100 |

Source links for reviewing these findings: [pipeline](https://github.com/cheshmakzanoon-ops/3d-scanner/blob/fdd16fce4aa7b1b7d365c0f43e89964d3028bf42/app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt), [workload limits](https://github.com/cheshmakzanoon-ops/3d-scanner/blob/fdd16fce4aa7b1b7d365c0f43e89964d3028bf42/app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt), [ViewModel](https://github.com/cheshmakzanoon-ops/3d-scanner/blob/fdd16fce4aa7b1b7d365c0f43e89964d3028bf42/app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt), [repository](https://github.com/cheshmakzanoon-ops/3d-scanner/blob/fdd16fce4aa7b1b7d365c0f43e89964d3028bf42/app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt), [JNI](https://github.com/cheshmakzanoon-ops/3d-scanner/blob/fdd16fce4aa7b1b7d365c0f43e89964d3028bf42/reconstruction/src/jni.cpp), [CI](https://github.com/cheshmakzanoon-ops/3d-scanner/blob/fdd16fce4aa7b1b7d365c0f43e89964d3028bf42/.github/workflows/verify.yml), [approved product direction](https://github.com/cheshmakzanoon-ops/3d-scanner/blob/fdd16fce4aa7b1b7d365c0f43e89964d3028bf42/PROJECT_PLAN.md).

## 2. Product scope and the meaning of complete

Complete the approved **offline, photograph-based Android scanner**: library → capture/import → review/calibration → real reconstruction → observed mesh and photographic texture → local studio/editing → useful export → reliable reopen/recovery. Preserve original photographs and distinguish every data representation. This is a cohesive product goal; it is not 100 equally sized tasks or a promise that a hundred workers remove engineering dependencies.

The target supported workload is the plan's roughly 30–250-photo direction. Exercise 12-photo compatibility and 30/100/250-photo profiles. Remove hard limits only with scalable algorithms, explicit memory/storage budgets, timeouts/cancellation and measured device qualification. The active working set may remain small while the total input collection grows. Do not merely change `12` to `250` or relabel low-resolution previews as high fidelity. If a profile fails its declared budget or quality gate, keep it unavailable and report the remaining blocker; do not silently shrink the approved scope and declare the product complete.

Expected outputs include photographic GLB, representation-correct PLY, OBJ/MTL packages and triangle STL when appropriate, plus original-photo archives and diagnostic reports. The production studio must support usable inspection, reversible orientation/crop/scale, meaningful measurement provenance and derived LODs. Unknown surfaces stay unknown, and arbitrary scale stays arbitrary unless supported by a real reference.

The photos-only path must function without an account, server, analytics, model download or runtime internet permission. Optional user-selected sharing/document providers may transfer data at the user's direction. An optional Vulkan backend has a real implementation assignment 052 but remains disabled until CPU parity and driver/resource qualification pass; the correct CPU path is the production fallback. ARCore assistance and RAW-specific capture are optional future extensions, not prerequisites for this photos-only release. Do not create unrelated social, payment, cloud-sync or generative-object features.

**Code completion and release validation are different states.** Runnable code and comprehensive tests can be completed before hardware or production credentials are available. “Production ready” requires the actual installed release candidate, physical capture/quality/resource evidence, supported-device behavior and release packaging gates. No agent may substitute a build, line count, synthetic screenshot, healthy average or assumed coverage percentage for those gates.

## 3. Parallel working agreement

### One worker, one branch, one checkout

The roster below is authoritative. All 100 agents may begin immediately. Agents read their current source, establish a reproducer or baseline, implement owned code and tests, and publish contracts while peers work. Integration is coordinated continuously; agents do not wait for the preceding number to finish.

The common campaign base is the commit that first introduces this guide on `origin/main`. It includes the routing rule in `AGENTS.md`. The audited SHA above is evidence, not a request to discard subsequent repository work. Agent 100 records the actual campaign base in its integration ledger. Before any merge, incorporate newer `main` work without overwriting it.

Example for a fresh **Agent 001** checkout; use your own exact roster branch:

```bash
git clone https://github.com/cheshmakzanoon-ops/3d-scanner.git 3d-scanner-agent-001
cd 3d-scanner-agent-001
git fetch origin
RF_CAMPAIGN_BASE=$(git log --diff-filter=A -1 --format=%H origin/main -- PRODUCTION_100_AGENT_EXECUTION_PLAN.md)
test -n "$RF_CAMPAIGN_BASE"
git switch --create agents/001-app-composition "$RF_CAMPAIGN_BASE"
```

If the branch already exists, inspect its status and remote history and resume it; do not recreate, reset or overwrite another active worker's branch. In a shared machine use a separate worktree or clone and separate `build/host`, Android outputs and test-report directories. Shared read-only SDKs are fine; do not run competing installs into shared toolchain directories or edit another checkout. Do not stash/reset a user's unrelated edits to make your checkout convenient.

Push only your assigned branch with a normal fast-forward push. Use small meaningful commits and keep your remote branch current. Do not force-push, delete other branches, change repository permissions, alter branch protection or publish to a store as part of an ordinary numbered assignment. Agent 100 may merge reviewed, passing work into the selected repository; signing/uploading a public release still requires the user's release authorization and actual credentials.

### Source ownership and shared-file rules

Each existing source path below has one semantic owner. A “new” path is a reserved responsibility, **not a claim that the path exists today**. Narrow package directories group related code; do not create an independent application, duplicate pipeline, or Gradle module for every agent. If current code already satisfies a proposed requirement, characterize it and close the specific remaining gap instead of replacing it unnecessarily.

All agents may add their own focused tests and receipts under these collision-free paths, creating only the files actually needed:

- `docs/agents/status/NNN.md`, `docs/agents/contracts/NNN.md`, and `docs/agents/requests/NNN-to-MMM.md`.
- `app/src/test/java/com/realityforge/scanner/agents/aNNN/` and matching `androidTest/.../agents/aNNN/`.
- `reconstruction/tests/agent_NNN_*.cpp` and `tests/test_agent_NNN_*.py` when the existing semantic test location is not assigned to them.
- A feature's own `app/src/main/res/values/agent_NNN.xml` for new default strings, with resource names prefixed `aNNN_`; 078 coordinates shared copy, locale policy and translations. Do not have every screen agent edit `strings.xml`.

An unlisted existing file is read-only until Agent 100 records a concrete owner. Related historical tests may be assigned to the semantic owner through an explicit ledger amendment. Additions in someone else's reserved directory need a handoff. Do not edit central files to make your branch compile without consulting their owner.

| Shared area | Sole semantic owner | How other agents integrate |
|---|---|---|
| ViewModel/activity/application composition | 001 | Submit request-bound action/state interfaces and precise wiring requirements |
| `SparsePipeline.kt` / `DensePreview.kt` | 002 | Deliver real stage implementations; request extraction/wiring without parallel monolith edits |
| `jni.cpp` / `NativeCore.kt` | 003 | Agree shape, types, ownership, units, failure and cancellation behavior together |
| CMake, Gradle, wrapper and dependency pins | 004 | Submit exact source/test registrations, approved dependencies and small build proposals |
| `Database.kt`, schema versions and migrations | 005 | Agree entity/index/transaction contracts; own business services outside the schema owner files |
| `ProjectRepository.kt` façade and leases | 006 | Deliver import/archive/publication/deletion services and adapter requests |
| Main manifest and execution service declarations | 008 | Request permission/component changes with platform evidence and tests |
| Test manifest, fault document provider and external process controller | 088 | 026 writes transfer tests; 080 writes UI scenarios; both reuse 088 infrastructure and never ship it in the main app |
| Artifact identities / stage manifests | 009 | Use canonical hashes and versioned schemas, never an ad-hoc cache key |
| Diagnostics/failure/progress schema | 010 | Supply raw metrics and typed causes; UI owners localize them |
| `DenseWorkload.kt` | 030 | 044 provides geometric selection; resource/backend owners consume the same admission budgets |
| Legacy native core/dense/surface/texture writers | 037/046/053/057 respectively | 061–064 supply serializers; legacy owners route their call sites |
| Existing viewer parser/renderer/UI files | 068/069/070 respectively | Share validated assets and controls through declared contracts |
| `ScannerApp.kt` | 071 | Screen owners deliver Composables/controllers; 071 handles navigation/composition |
| Original `strings.xml` | 078 | Use assigned per-agent resources for new features; request central message changes |
| CI verification / source contracts | 093 /094 | Report exact checks/semantic contract updates; never delete assertions to manufacture green tests |
| Guide, agent routing and integrated release decision | 100 | Request a recorded ownership/scope amendment; do not edit the global plan concurrently |
| `SOURCE_MANIFEST.sha256` |**Mechanical exception for every agent** | Regenerate from the complete staged tree; only 094 changes its generation/validation rules |

### Make a cross-owner change concrete

Write `docs/agents/requests/NNN-to-MMM.md` on your branch with: requesting/receiving agent, source commit, exact target path/symbol, problem/reproducer, agreed interface fields/units/lifetimes, proposed minimal adapter diff or pseudocode, affected tests, and the expected verification command. This is a source handoff, not permission to send people messages outside the repository.

The requester must commit and push the handoff. Agent 100 indexes its immutable branch, commit and path in the integration ledger; every receiving owner fetches and checks its incoming indexed requests before declaring DONE. The receiving owner implements the actual change on its own branch and references the supplying commit. Agent 100 integrates the compatible pair. Use immutable commit IDs for test integration; do not depend on the uncontrolled latest state of 99 branches. A temporary integration checkout may merge published commits for testing, but do not push those combined histories to another agent's branch. Your release branch should contain only your scope plus reviewed upstream integration.

### Contract-first work without dependency deadlock

The **integration inputs** in each assignment are cooperation/verification relationships. They are not instructions to sit idle until every named agent is marked done. Application and repository owners necessarily consume components that also consume their interfaces. Resolve this in two deliveries:

1. **Compatible foundation delivery:**001–010,030,061,071,076,078,094 and 100 publish minimal concrete contracts/adapters around the working M19 behavior. These commits must compile and preserve the baseline path. No production fake, canned success, throwaway stub or unsupported feature button is allowed.
2. **Feature delivery and final wiring:** algorithm/storage/screen owners implement against those contracts and demonstrate behavior with controlled fixtures and actual runtime paths. The foundation owner then connects the new implementation and reruns the combined flow. Its initial adapter merge is not its final task completion.

Before a contract producer has merged, consumers can develop pure algorithms, parser/state-machine tests, fixture adapters and failure harnesses in their own files. Test-only doubles must remain labeled and confined to tests. Every accepted production feature must ultimately be wired and exercised. “My helper works in isolation” is insufficient.

Agent 100 keeps a concrete queue of mergeable slices, not a circular “wait until everyone is done” barrier. Ordinary technical dependencies are resolved through published contracts and small integration commits. Only genuinely unavailable external resources use `BLOCKED_EXTERNAL`.

## 4. Shared interface contracts

These are required contract semantics, not pre-existing classes to assume are already in the repo. Keep schemas small, typed and versioned; owners settle exact signatures with consumers in their contract files before breaking a call site.

| Contract | Owner | Required content and consumers |
|---|---|---|
| C1 — operation/application state | 001 | Immutable project ID, revision, request/session ID and job ID; capture/import/export callbacks cannot infer a new destination from selection |
| C2 — pipeline stages | 002 | Validated input snapshot, stage identity, cancellation/progress, typed result/representation and artifact references; runners consume it |
| C3 — JNI/native boundary | 003 | Checked buffer layout/counts, units and coordinate convention, lifetime, operation token, failure mapping and no escaping C++ exceptions through either JNI or exported C ABI wrappers; domain source owners implement their own C boundary |
| C4 — build registration | 004 | Exact sources/targets, test invocation, pinned dependencies and supported toolchains; no runtime downloads |
| C5 — durable database model | 005 | Stable IDs, schema version, migration, revision/attempt comparisons, leases and transactional invariants |
| C6 — project access/lease | 006 | Safe canonical roots, operation conflict policy, immutable inputs and validated current-result access |
| C7 — execution lifecycle | 008 | Job acquisition, platform-compliant start, foreground notification, pause/cancel/timeouts, recovery and resource release |
| C8 — artifact/stage identity | 009 | SHA256 input/calibration/settings/engine identity, parents, representation, sizes/hashes and committed status; sparse/depth/edit/LOD descendants invalidate correctly |
| C9 — diagnostic evidence | 010 | Stable stage/error codes, optional measured totals, observed support, unknown scale and explicit unavailable evidence; presentation is localized separately |
| C10 — canonical 3D asset | 061 | Sparse/dense points versus observed/photographic triangles; positions/indices/normals/UVs/materials, units, transforms and provenance |
| C11 — edit/measurement recipe | 066 | Immutable parent asset, reversible operation list, crop/transform/scale reference, uncertainty, version and derived artifact identity |
| C12 — export request/outcome | 067 | Fixed project/artifact/revision/format/options, eligibility, packaging, progress/cancel and success only after validated transfer |
| C13 — validated model load | 068 | Supported file subset, bounded buffers/images, explicit unsupported/error outcome and no external URI resolution |
| C14 — renderer backend | 069 | Immutable validated asset, surface/GPU ownership, load/render/release, resource budgets, capability/fallback and error reporting |
| C15 — studio interaction | 070 | Orbit/pan/fit/reset, representation, edit/measurement actions and accessible alternatives; camera interaction does not mutate master geometry |
| C16 — integration/evidence receipt | 100 with 099 | Source commit, artifact hash, agent, command/environment/device/data identity, measured result, gate status and remaining blocker |

**Coordinates:** the current internal convention is x-right/y-down/z-forward, world-to-camera row-major poses, with arbitrary scale. glTF converts to a right-handed y-up frame. 031/061 must document and test the exact transformations, pixel-center rules and winding; changes must invalidate affected artifacts. Do not guess meters, Euler ordering, camera-to-world orientation or texture origin.

**Batch revisions:** project and capture-session identity stay fixed while each committed photograph advances project revision. 011/014/018 and 005 use the latest expected revision for the next mutation, or a defined admitted transaction/batch. Immutable request binding must not reject shot2/import2 simply because shot1/import1 advanced revision. Reconstructed/exported artifacts still require their fixed source revision.

**No schema success by omission:** new readers either support a declared old version through a tested migration or reject it explicitly. Unknown data cannot be silently read as zero, an empty completed mesh, identity pose, valid coverage or successful texture.

## 5. Checksums, commits and merge safety

Every currently tracked file except `SOURCE_MANIFEST.sha256` is hashed in that manifest. A documentation edit can fail the existing source-contract test if its hash is not updated. Every agent may regenerate the manifest mechanically; this is the only shared generated-file exception. Only 094 changes the generator or verification policy.

Before 094's tool is available, use this exact procedure after staging **only your owned changes**, new tests and receipt. The script hashes Git's staged blobs, so additions/deletions/renames are represented and unstaged unrelated files are not accidentally included. Do not commit unstaged tracked source changes alongside a manifest generated from another tree.

```bash
git diff --check
git add -- path/to/your/owned/file docs/agents/status/NNN.md
python3 - <<'PY'
import hashlib
import subprocess
from pathlib import Path

records = subprocess.check_output(['git', 'ls-files', '--stage', '-z']).split(b'\0')
rows = []
for record in records:
    if not record:
        continue
    metadata, raw_path = record.split(b'\t', 1)
    mode, oid, stage = metadata.split()
    path = raw_path.decode('utf-8')
    if stage != b'0':
        raise SystemExit('Resolve all semantic merge conflicts before regenerating the manifest.')
    if path == 'SOURCE_MANIFEST.sha256':
        continue
    if mode not in (b'100644', b'100755') or '\n' in path or '\r' in path:
        raise SystemExit('Unsupported tracked entry; coordinate with 094: ' + path)
    content = subprocess.check_output(['git', 'cat-file', 'blob', oid.decode('ascii')])
    rows.append((path, hashlib.sha256(content).hexdigest()))
Path('SOURCE_MANIFEST.sha256').write_text(
    ''.join(f'{digest}  {path}\n' for path, digest in sorted(rows)), encoding='utf-8')
PY
git add SOURCE_MANIFEST.sha256
git diff --check --cached
git diff --exit-code
```

`path/to/your/owned/file` and `NNN` are placeholders to replace, not files to create. Inspect `git diff --cached --stat` and the actual diff. Run applicable validation before the final commit; once 094 publishes the equivalent verified tool, use its documented command instead of maintaining private variants.

For a manifest-only conflict, resolve all source files first, stage that semantic result, remove the manifest's unmerged index state by staging its temporary content, regenerate the **entire** manifest from the final index and stage it again. Never choose `ours`/`theirs` as the final manifest, hand-edit a convenient hash, drop new files or weaken the test to pass. 094 must strengthen exact tracked-set checks, not simply bless any listed subset.

Normal delivery:

```bash
git commit -m "feat(agent-NNN): describe the completed behavior"
git push --set-upstream origin YOUR_EXACT_ROSTER_BRANCH
```

Before reporting a push as successful, fetch/inspect the remote branch and verify its head equals the delivered commit. Create a draft PR when the work is reviewable, with role ID, source/target SHAs, owned files, related requests, implementation changes, test evidence and unresolved gates. No mass user mentions or review requests are needed. A PR is not a substitute for a working branch and clear receipt.

## 6. Verification gates and evidence requirements

Run the smallest meaningful set that proves your change and all mandatory repository gates for the affected layer. Do not repeatedly run unrelated expensive suites to inflate evidence. For a behavior change add a regression that would fail before the fix; do not add hundreds of tests that merely inspect names or mirror implementation statements. New native source/test targets are registered by 004, and 094 owns updates to existing source-string contracts when real implementations legitimately move.

### Available baseline commands

These are commands supported by the audited repository, not claims that the current agent environment has all dependencies installed. First-time build setup may require network access; installed scanning must not.

**HOST** — from an isolated repository checkout:

```bash
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python3 -m pytest tests -q
```

**ANDROID** — actual Gradle compilation, JVM tests and lint:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

**DEVICE** — an authorized connected emulator/phone; specify its serial when multiple devices exist:

```bash
./gradlew :app:connectedDebugAndroidTest
```

**SANITIZER** — separate native build, plus 081's current compiler/sanitizer matrix:

```bash
cmake -S . -B build/asan -G Ninja -DCMAKE_BUILD_TYPE=Debug -DRF_SANITIZE=ON
cmake --build build/asan --parallel 2
ctest --test-dir build/asan --output-on-failure
```

**Existing quality regression commands** — use the pinned Python requirements and licensed input policy. Do not confuse their limited scene coverage with full-object qualification:

```bash
python3 tools/bundle_three_view.py
python3 tools/benchmark_multiview_coverage.py --gate --output reports/m14-eight-view-coverage.json
python3 tools/benchmark_middlebury.py --gate --output reports/m05-middlebury-real.json
python3 tools/benchmark_dense_middlebury.py --gate --output reports/m07-middlebury-dense.json
python3 tools/benchmark_surface_middlebury.py --gate --output reports/m08-middlebury-surface.json
python3 tools/benchmark_texture_middlebury.py --gate --output reports/m10-middlebury-texture.json
```

Some baseline harnesses write to tracked historical report paths. Run them in a disposable validation checkout or copy their new output to an ignored evidence directory; do not commit incidental historical-report churn from another owner's task. Record actual run data separately with its current source SHA. Do not reuse old report timestamps as new results.

### Gate owners and final conditions

| Gate | Required evidence | Lead owners |
|---|---|---|
| PLAN | Exactly 100 IDs/branches, source ownership without semantic overlap, valid referenced paths/IDs, coherent interfaces, status and evidence schema | 100/099/094 |
| HOST / SANITIZER / FUZZ | Compiled actual native implementation; current CTest/pytest; sanitizer and bounded hostile-input runs; reproducer for each corrected defect | 004/081/082/096 |
| ANDROID | Pinned wrapper build, lint and JVM results attached to exact source SHA; no source-only compilation claim | 004/093 |
| INTEGRATION | Actual combined production entry points exercised on a candidate with compatible interfaces; include applicable DEVICE/RESILIENCE/INTEROPERABILITY gates, not only isolated helper tests | 002/080/093/099/100 |
| DEVICE / COMPATIBILITY | Actual JNI/UI/lifecycle tests, lowest supported API 29, representative intermediate/current target devices, arm64-v8a and x86_64, and actual 16 KB runtime where required | 080/086/092 |
| GEOMETRY | Known ground truth/scale-aligned geometry, registration rate, observable-surface completeness, accuracy distribution and per-scene failures; no supplied-pose fixture masquerading as recovered poses | 083/084/086 |
| TEXTURE | Independent alignment/visibility/seam/color checks and held-out views; texel coverage is not unseen-object completeness | 057–060/085 |
| RESILIENCE | Real external process kill/relaunch, storage exhaustion, corruption, stale revisions, interrupted export and installed database upgrade; original hashes preserved | 005–009/022–026/050/088 |
| PERFORMANCE | Actual process-memory and native-allocation evidence, trustworthy GPU counters where exposed, peak disk, sustained thermal/power data, pause latency and viewer frame/startup traces for each enabled profile; document unavailable counters and approved alternatives without fabricating readings or waiving the supported profile budgets | 030/052/069/087/097 |
| ACCESSIBILITY | Real semantics/focus/actions plus TalkBack/keyboard/switch-access review, large font, pseudo-locales/RTL and adaptive layouts | 070/076–080 |
| INTEROPERABILITY | Independent GLB/OBJ/PLY/STL readers/validators and target-tool imports; correct bounds, units, normals, winding, UVs and package references | 061–067/090 |
| PRIVACY / SECURITY / DEPENDENCIES | Merged package/runtime network-free checks, consented document export, backup settings, bounded parsers, current notices/SBOM and verified dependencies | 089/095/096 |
| RELEASE | Release APK and AAB/splits, R8/JNI runtime, signing-mode identity, package alignment, upgrade/install and candidate artifact hashes; missing production credentials remain explicit | 004/091/092/099 |

Gate names in individual roles refer to this table. Additional named checks describe work their owner must implement and register; they are not existing script names to pretend have run. Agents must publish the real invocation once implemented. Source-string tests remain supplementary; replacing them with genuine behavioral tests must preserve the protected invariant and receive 094 review.

### Optional features and gate applicability

Agent 100 with 099 freezes required-versus-optional gate applicability before measurements in `docs/agents/integration/gate-applicability.json`. Required photos-only capabilities, data integrity and supported-profile physical qualification cannot be waived. Optional 052 acceleration may remain disabled when its driver/parity/resource gates are unrun or fail; keep those receipts NOT_RUN/FAIL and prove the enabled CPU fallback independently. This can permit a qualified CPU release, but it does not make Vulkan tested or every optional assignment complete. Never reclassify a failed required gate as optional after the result is known.

Use `OPTIONAL_DISABLED` only for an implemented, isolated optional path whose enabled fallback is verified and whose omitted qualification is explicitly tracked. Such a role remains open for feature qualification and is not falsely marked DONE. A missing required device or credential still blocks the corresponding required release gate. 097 retains a baseline profile only after measured benefit; a measured no-benefit result is valid grounds to omit that optional optimization, not to fabricate a gain.

### Declare thresholds before measuring

083/084/085/087/097 with 100 must freeze versioned test manifests, supported hardware/profile matrix, conditions and numeric thresholds **before** accepting optimized results. Start from `PROJECT_PLAN.md` aspirations of roughly 95% registration and scale-aligned error below about 1% of object bounding-box diagonal on eligible controlled scenes; these are acceptance targets to investigate, not current measurements or universal guarantees. Use percentile/tail errors, observable-surface completeness and per-scene rejection alongside averages. Completeness must use an independently defined visible reference surface, not the retained output as its own denominator.

Measure 12/30/100/250-photo workloads, at least two authorized matte textured 360-degree objects with independent reference geometry, and the planned range of textured/thin/flat/adverse materials. Include shiny, transparent, textureless and moving inputs for honest rejection/recapture behavior. Separate known-pose synthetic numerical fixtures from unknown-pose real captures. Do not hide difficult cases, tune on held-out data or relax a threshold after seeing a failure simply to call it complete. Any justified scope/threshold revision needs an explicit evidence record and must be reflected in public claims and remaining blockers.

RAM, temperature, time and power limits depend on the actual supported devices. Do not invent universal measurements here. Define budgets before trials, expose only qualified profiles, and retain every failing scene/device outcome. If the required profile still cannot pass, continue work or report the gate open.

### External resources and sensitive data

Use only authorized captures and licensed datasets. Keep people's original photographs/private scan archives out of public Git by default; use small licensed fixtures, content hashes/manifests and documented local acquisition. Do not invent ground-truth meshes or label generated imagery as physical capture. Do not include signing keys, passwords, tokens, SDK caches, large build artifacts or unlicensed code in a branch.

A lack of hardware or credentials does not justify stopping all work. Complete the harness, validators, failure handling and reproducible operator steps, then record the exact blocked measurement or signing step. Do not claim long-term execution in the background when your agent session has ended. If a session is interrupted, commit a truthful checkpoint and resume from its receipt in the next session.

## 7. Status, handoff and definition of success

Each agent's `docs/agents/status/NNN.md` is independently owned. Use this compact record and fill actual values; a placeholder is not completion evidence:

```yaml
agent: NNN
role: exact roster role
branch: exact roster branch
campaign_base: commit introducing this guide
implementation_commits: [actual commits]
state: WORKING
owned_paths_changed: []
interfaces_published: []
integration_requests: []
acceptance_results:
  - criterion: exact criterion from your assignment
    result: NOT_RUN
    command: actual command or pending explicit invocation
    source_sha: exact tested source
    artifact_sha256: null
    environment: compiler or Android API/ABI/device identity
    dataset_identity: null
    evidence: artifact path or CI URL
remaining_work: []
external_blockers: []
next_action: concrete next implementation or validation step
```

States:

- `WORKING`: implementation, tests or integration are still active.
- `READY_FOR_INTEGRATION`: the owned change is implemented and verified sufficiently for a compatible merge; list final combined/hardware gates still open. This is not final success.
- `INTEGRATED_PENDING_VERIFICATION`: combined code exists, but final assignment criteria still need execution on the integrated candidate.
- `DONE`: every role-specific required criterion passes, the feature is actually wired or the validation tool is actually executable, evidence names the relevant source/artifact, and the branch/remote delivery is verified.
- `OPTIONAL_DISABLED`: an implemented optional path is unavailable in the shipping configuration pending its own qualification; preserve NOT_RUN/FAIL evidence and prove the enabled fallback. This is not a claim the optional feature passed.
- `BLOCKED_EXTERNAL`: all feasible work is complete but a named external requirement is unavailable. Include what was tried and exact next action; this is not `DONE` and not release-ready.

Test receipts use `PASS`, `FAIL`, `NOT_RUN` or `BLOCKED_EXTERNAL`; do not call a skip a pass. No receipts may imply an emulator is a physical ARM64 phone. A guide/contract merge or dependency stub does not close an implementation assignment. QA agents fix their own harness defects and send product defects to the source owner; they do not only write criticism and leave the concrete validation tool missing.

Freeze the candidate source SHA and release artifact hashes before qualification. Store final receipts in immutable CI artifacts or a later evidence-index commit referring to that candidate; do not try to embed a commit's own unknown SHA into itself. Receipt-only follow-ups do not retroactively test a new tree. 099 must verify they change only declared evidence/metadata paths; any build/runtime/input change requires a new candidate and affected reruns.

Every agent must make its delivery reviewable: explain what changed and why, exact checks/results, remaining limitations, related owner requests and commit/branch links. Save progress often enough to resume after context loss, but avoid empty progress commits, generated bloat or repeated historical reports. No “100% complete” claim based on task count is permitted.

## 8. Integration order while all agents remain active

| Merge stage | Work eligible for integration | Requirement before promotion |
|---|---|---|
| A — compatible foundation | Build/wrapper/manifest tooling, source contracts, typed identities/state, schema baseline, composition seams and shared asset/UI interfaces | Existing M19 path compiles and retains its runtime/integrity behavior; contracts are concrete and discoverable |
| B — independent correctness/components | Parser/JNI fixes, image/capture services, feature/SfM/depth components, storage services, serializer/mesh/texture modules and independent harnesses | Owned tests plus affected existing gates pass; no unresolved ABI/schema migration mismatch |
| C — joined product paths | Durable service/store wiring, scalable pipeline, capture/review screens, production studio, edits/LODs/export, accessibility/localization | Actual application invokes real components; combined install/capture/import/reconstruct/reopen/export/resume tests pass |
| D — measured quality and compatibility | Physical captures, CPU/Vulkan comparisons, full-object quality, texture/error budgets, resources, offline/privacy and target-tool interoperability | Declared thresholds and supported profiles pass on actual identified datasets/devices; all failures retained |
| E — release candidate | Minified APK/AAB/splits, upgrade, signing identity, notices/SBOM, current documentation and independent 099 qualification | Exact integrated source/artifact passes every required gate; external blockers remain blockers |

These are **merge/acceptance stages, not a command to launch agents sequentially**. A hardware/benchmark agent begins its acquisition/protocol/harness work on day one. A screen agent begins with current DTOs/adapters. A late final gate can be prepared and regression-tested before the new implementation arrives. Agent 100 continuously merges small compatible slices and requests follow-up wiring from original owners; no hundred-way simultaneous merge is attempted.

If two branches touch a semantic file despite the ownership rule, stop that merge, determine the real owner, replay the intended change through that owner and rerun affected tests. Do not automatically choose one branch, drop a fix or claim a clean textual merge proves integration. Keep source and generated checksum resolution separate.

## 9. Agent directory

P0 means correctness, data integrity, buildability or an essential validation blocker. P1 means required product capability/quality. P2 means a measured enhancement that must remain honestly gated if unqualified. Priority changes integration attention, not permission to omit a required role. Every assignment includes an immediate starting action so all 100 can begin together.

### 001–010: Application, persistence and build foundations

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 001](#agent-001) | Android application composition and operation ownership | `agents/001-app-composition` |
| [Agent 002](#agent-002) | Reconstruction orchestration and resumable stage graph | `agents/002-pipeline-orchestration` |
| [Agent 003](#agent-003) | JNI safety, ownership and native API compatibility | `agents/003-jni-boundary` |
| [Agent 004](#agent-004) | Reproducible build foundation and dependency integration | `agents/004-build-foundation` |
| [Agent 005](#agent-005) | Database schemas, upgrades and durable state model | `agents/005-database-migrations` |
| [Agent 006](#agent-006) | Project repository boundaries and operation leases | `agents/006-repository-boundaries` |
| [Agent 007](#agent-007) | Persistent reconstruction jobs and recovery ledger | `agents/007-durable-job-store` |
| [Agent 008](#agent-008) | Android long-running execution and platform lifecycle | `agents/008-execution-lifecycle` |
| [Agent 009](#agent-009) | Artifact identity, stage manifests and safe checkpoint reuse | `agents/009-artifact-provenance` |
| [Agent 010](#agent-010) | Typed failures, truthful diagnostics and progress evidence | `agents/010-diagnostics-contract` |

### 011–030: Capture, image handling and reliable storage

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 011](#agent-011) | Bounded photo import and document-provider ingestion | `agents/011-bounded-photo-import` |
| [Agent 012](#agent-012) | Image decoding, EXIF orientation and memory ownership | `agents/012-image-decoding-lifetimes` |
| [Agent 013](#agent-013) | Lens and capture metadata identity | `agents/013-capture-metadata-identity` |
| [Agent 014](#agent-014) | Camera session identity, lifecycle and permission recovery | `agents/014-camera-session-lifecycle` |
| [Agent 015](#agent-015) | Camera optical controls and device capabilities | `agents/015-camera-optical-controls` |
| [Agent 016](#agent-016) | Bounded live capture quality analysis | `agents/016-live-capture-quality` |
| [Agent 017](#agent-017) | Measured overlap and capture diversity guidance | `agents/017-capture-overlap-guidance` |
| [Agent 018](#agent-018) | Photo curation commands and reconstruction selection | `agents/018-photo-curation` |
| [Agent 019](#agent-019) | Project library queries, rename and independent duplication | `agents/019-project-library-commands` |
| [Agent 020](#agent-020) | Derived artifact and cache garbage collection | `agents/020-derived-cache-gc` |
| [Agent 021](#agent-021) | Storage preflight, reservations and peak-space budgets | `agents/021-storage-budgets` |
| [Agent 022](#agent-022) | Deletion tombstones and recoverable private-file removal | `agents/022-deletion-tombstones` |
| [Agent 023](#agent-023) | Atomic result publication and last-completed preservation | `agents/023-atomic-result-publication` |
| [Agent 024](#agent-024) | Safe reopening and typed preview schema validation | `agents/024-reopen-schema-integrity` |
| [Agent 025](#agent-025) | Capture archive export, validation and project restoration | `agents/025-capture-archive-roundtrip` |
| [Agent 026](#agent-026) | SAF export transfer, cancellation and partial failure handling | `agents/026-saf-export-io` |
| [Agent 027](#agent-027) | Lens calibration and profile lifecycle workflow | `agents/027-lens-calibration-workflow` |
| [Agent 028](#agent-028) | Storage inspection and safe cleanup screen | `agents/028-storage-management-ui` |
| [Agent 029](#agent-029) | Processing notification controls and permission experience | `agents/029-processing-notifications` |
| [Agent 030](#agent-030) | Thermal and memory-aware Android workload scheduling | `agents/030-resource-thermal-scheduling` |

### 031–043: Features, calibration and sparse reconstruction

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 031](#agent-031) | Lens geometry and pixel coordinate consistency | `agents/031-lens-coordinate-geometry` |
| [Agent 032](#agent-032) | Bounded feature extraction | `agents/032-feature-extraction` |
| [Agent 033](#agent-033) | Feature cache integrity and eviction | `agents/033-feature-cache-integrity` |
| [Agent 034](#agent-034) | Scalable overlap candidate graph | `agents/034-overlap-candidate-graph` |
| [Agent 035](#agent-035) | Descriptor matching and verified correspondence service | `agents/035-verified-feature-matching` |
| [Agent 036](#agent-036) | Stable scalable multi-view track graph | `agents/036-stable-feature-tracks` |
| [Agent 037](#agent-037) | Two-view initializer and degeneracy diagnostics | `agents/037-two-view-initializer` |
| [Agent 038](#agent-038) | Robust PnP camera resection | `agents/038-robust-camera-resection` |
| [Agent 039](#agent-039) | Incremental registration and restartable frontier | `agents/039-incremental-registration` |
| [Agent 040](#agent-040) | Multi-view triangulation and sparse geometry refinement | `agents/040-multiview-triangulation` |
| [Agent 041](#agent-041) | Scalable robust bundle adjustment and gauge | `agents/041-bundle-adjustment` |
| [Agent 042](#agent-042) | Bounded intrinsic refinement model | `agents/042-intrinsic-refinement` |
| [Agent 043](#agent-043) | Verified loop closure and global drift constraints | `agents/043-loop-closure-drift` |

### 044–052: Dense reconstruction, checkpoints and acceleration

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 044](#agent-044) | Geometry-aware dense reference and source selection | `agents/044-dense-view-selection` |
| [Agent 045](#agent-045) | Versioned image pyramids and exact rectification | `agents/045-image-pyramids-rectification` |
| [Agent 046](#agent-046) | Bounded tiled CPU depth core | `agents/046-tiled-cpu-depth-core` |
| [Agent 047](#agent-047) | Coarse-to-fine depth and local refinement | `agents/047-depth-refinement` |
| [Agent 048](#agent-048) | Photometric matching costs and validity masks | `agents/048-photometric-matching-costs` |
| [Agent 049](#agent-049) | Reciprocal confidence and occlusion consistency | `agents/049-depth-confidence-occlusion` |
| [Agent 050](#agent-050) | Depth checkpoints and bounded durable cache | `agents/050-depth-checkpoint-durability` |
| [Agent 051](#agent-051) | Scoped cancellation and measurable native progress | `agents/051-native-cancellation-progress` |
| [Agent 052](#agent-052) | Measured Vulkan depth backend with CPU fallback | `agents/052-vulkan-depth-backend` |

### 053–060: Fusion, topology and photographic appearance

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 053](#agent-053) | Observed surface fusion and overlap reduction | `agents/053-surface-fusion-meshing` |
| [Agent 054](#agent-054) | Surface normals and geometric uncertainty | `agents/054-normals-geometric-uncertainty` |
| [Agent 055](#agent-055) | Mesh topology and self-intersection diagnostics | `agents/055-mesh-topology-diagnostics` |
| [Agent 056](#agent-056) | Conservative mesh cleanup and explicit repair | `agents/056-conservative-mesh-cleanup` |
| [Agent 057](#agent-057) | Photographic atlas pipeline and bounded encoding | `agents/057-photographic-atlas-pipeline` |
| [Agent 058](#agent-058) | Compact UV charts and atlas packing | `agents/058-uv-charts-packing` |
| [Agent 059](#agent-059) | Seam-aware photographic blending and rebake | `agents/059-texture-seam-blending` |
| [Agent 060](#agent-060) | Measured exposure and color consistency | `agents/060-photometric-color-consistency` |

### 061–070: Assets, export, model editing and studio

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 061](#agent-061) | Canonical 3D asset model and correct GLB export | `agents/061-glb-asset-contract` |
| [Agent 062](#agent-062) | OBJ and MTL photographic export | `agents/062-obj-export` |
| [Agent 063](#agent-063) | STL export and printability diagnostics | `agents/063-stl-export` |
| [Agent 064](#agent-064) | PLY export with normals, confidence and provenance | `agents/064-ply-export` |
| [Agent 065](#agent-065) | Detail-preserving mesh decimation and LODs | `agents/065-mesh-lod` |
| [Agent 066](#agent-066) | Non-destructive orientation, crop, scale and measurements | `agents/066-model-editing` |
| [Agent 067](#agent-067) | Android export product flow and format orchestration | `agents/067-export-coordinator` |
| [Agent 068](#agent-068) | Bounded model loading and GLB parser hardening | `agents/068-model-loader` |
| [Agent 069](#agent-069) | Production Filament renderer and GPU lifecycle | `agents/069-studio-renderer` |
| [Agent 070](#agent-070) | Interactive studio controls and diagnostic viewers | `agents/070-studio-controls` |

### 071–080: Product UI, accessibility and localization

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 071](#agent-071) | Application navigation and feature composition | `agents/071-app-navigation-composition` |
| [Agent 072](#agent-072) | Production project library screen | `agents/072-project-library-ui` |
| [Agent 073](#agent-073) | Photo review, project detail and recapture actions | `agents/073-photo-review-project-ui` |
| [Agent 074](#agent-074) | Processing progress, pause and recovery screen | `agents/074-processing-recovery-ui` |
| [Agent 075](#agent-075) | Offline onboarding and practical capture instructions | `agents/075-capture-onboarding-instructions` |
| [Agent 076](#agent-076) | Shared design system and adaptive layout primitives | `agents/076-adaptive-design-system` |
| [Agent 077](#agent-077) | Accessibility semantics and complete-flow verification | `agents/077-accessibility-verification` |
| [Agent 078](#agent-078) | Localization resources and structured user messages | `agents/078-localization-resources` |
| [Agent 079](#agent-079) | Preferences, supported profiles and about/privacy screen | `agents/079-preferences-about-privacy-ui` |
| [Agent 080](#agent-080) | Actual Android UI end-to-end and interruption tests | `agents/080-actual-ui-end-to-end` |

### 081–090: Independent quality, device and reliability evidence

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 081](#agent-081) | Host numerical regression and sanitizer harness | `agents/081-host-numerical-sanitizers` |
| [Agent 082](#agent-082) | Native, JNI and parser fuzzing harness | `agents/082-native-jni-parser-fuzzing` |
| [Agent 083](#agent-083) | Licensed real benchmark corpus and fixture tooling | `agents/083-licensed-benchmark-corpus` |
| [Agent 084](#agent-084) | Full-object geometric quality benchmarks | `agents/084-full-object-geometry-quality` |
| [Agent 085](#agent-085) | Photographic texture quality benchmarks | `agents/085-photographic-texture-quality` |
| [Agent 086](#agent-086) | Physical ARM64 camera-capture validation | `agents/086-physical-arm64-capture-validation` |
| [Agent 087](#agent-087) | Physical memory, thermal and power profiling | `agents/087-physical-resource-profiling` |
| [Agent 088](#agent-088) | Process-death, storage-fault and resume instrumentation | `agents/088-failure-recovery-instrumentation` |
| [Agent 089](#agent-089) | Runtime offline and privacy verification | `agents/089-runtime-offline-privacy` |
| [Agent 090](#agent-090) | Independent exported-asset interoperability validators | `agents/090-export-interoperability-validation` |

### 091–099: Release, compatibility, provenance and qualification

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 091](#agent-091) | Release APK/AAB, signing and R8 validation | `agents/091-release-package-validation` |
| [Agent 092](#agent-092) | Android API, ABI and 16KB runtime matrix | `agents/092-android-compatibility-matrix` |
| [Agent 093](#agent-093) | Per-branch, PR and integration CI validation | `agents/093-parallel-branch-ci-validation` |
| [Agent 094](#agent-094) | Source manifest and source-snapshot reproducibility | `agents/094-source-provenance-reproducibility` |
| [Agent 095](#agent-095) | Dependency, notices, SBOM and supply provenance | `agents/095-dependency-notices-sbom` |
| [Agent 096](#agent-096) | Security hardening and hostile-input assurance | `agents/096-security-hostile-input-assurance` |
| [Agent 097](#agent-097) | Startup and viewer performance qualification | `agents/097-startup-viewer-macrobenchmarks` |
| [Agent 098](#agent-098) | Current documentation and developer runbook | `agents/098-current-documentation-runbook` |
| [Agent 099](#agent-099) | Complete release-candidate journey qualification | `agents/099-release-candidate-journey-qualification` |

### 100–100: Integration and final acceptance

| Agent | Role | Exact branch |
|---|---|---|
| [Agent 100](#agent-100) | Integration lead and release acceptance coordinator | `agents/100-integration-lead` |


## 10. Individual instructions

The common working agreement and definition of success above apply to every role. Exact files take precedence over any broad package description. Read integration inputs as named handoffs, not as “wait until all peers finish.”

## Agent 001

**Role:** Android application composition and operation ownership

**Branch:** `agents/001-app-composition`

**Priority:** P0

**Status receipt:** `docs/agents/status/001.md`

**Mission:** Make application wiring and visible state explicit while preserving the working capture-to-model path throughout integration.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt): reserveOperation, captureReady, reconstruct and export share mutable selection and one activity-scoped job.
- [app/src/main/java/com/realityforge/scanner/MainActivity.kt](app/src/main/java/com/realityforge/scanner/MainActivity.kt): onStop unconditionally calls model.pause(); RealityForgeApplication constructs Room and repository directly.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt`
- `app/src/main/java/com/realityforge/scanner/MainActivity.kt`
- `app/src/main/java/com/realityforge/scanner/RealityForgeApplication.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/application/`
- `app/src/main/java/com/realityforge/scanner/domain/OperationContext.kt`
- `app/src/main/java/com/realityforge/scanner/domain/ScannerState.kt`

**Implementation instructions:**

1. Extract a small composition root and immutable UI state; keep domain work out of AndroidViewModel and avoid a framework-only rewrite.
2. Bind every capture, import, export and reconstruction request to project ID, revision and operation ID before suspension; never recover its destination from the later selected project.
3. Adapt the existing implementation first, then wire durable jobs, storage services, typed results and export controllers supplied by their owners. Remove activity-stop cancellation only when the durable runner passes lifecycle tests.
4. Preserve selection-generation checks, operation reservation and cancellation ownership; distinguish selecting another project from cancelling an existing job. Route localized errors through the presentation boundary.

**Success criteria:**

- A delayed capture/export result cannot mutate a different project after selection changes or activity recreation.
- The baseline import/reconstruct/reopen/export flow keeps working after the first refactor; final flow observes the persistent job state without owning its lifetime.
- No duplicate job, stale preview publication or UI-thread reconstruction occurs under rapid repeated actions and lifecycle changes.

**Required verification:** ANDROID; DEVICE; Focused ViewModel/operation identity regressions.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 007](#agent-007); [Agent 008](#agent-008); [Agent 010](#agent-010); [Agent 014](#agent-014); [Agent 067](#agent-067); [Agent 071](#agent-071).

**Interface handoff:** Own OperationContext and ScannerState under contract C1. Consume repository, job and export interfaces; Agent 071 owns ScannerApp screen wiring, Agent 008 the service/manifest. Submit API changes as versioned handoffs.

**Start immediately:** Characterize the current ViewModel transitions and land a behavior-preserving composition boundary using the current pipeline; final wiring follows provider components, without holding their work hostage.

## Agent 002

**Role:** Reconstruction orchestration and resumable stage graph

**Branch:** `agents/002-pipeline-orchestration`

**Priority:** P0

**Status receipt:** `docs/agents/status/002.md`

**Mission:** Turn the monolithic scan path into a real stage graph that can scale, resume and reuse upstream work without losing representation or revision guarantees.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt): run handles selection, features, matching, SfM, caches, diagnostics and all output stages; line 53 takes only 12 photos.
- [app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt](app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt): run combines decode, rectification, native work, texture fallback and publication.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt`
- `app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/pipeline/`

**Implementation instructions:**

1. Extract feature, matching, sparse, dense, surface and texture calls behind typed stage boundaries while preserving the existing implementation as the initial adapter.
2. Wire independently delivered algorithms, cancellation, bounded queues and artifact identities. Replace take(12) with validated selection/profiles only after the relevant camera/track/resource limits are removed together.
3. Persist accepted sparse alignment and downstream stage checkpoints via Agent 009; changing only texture/export options must reuse valid geometry, while changed photos/calibration invalidate the correct descendants.
4. Separate completed sparse/dense/mesh/textured outcomes and recoverable stage failures; never make a fallback look like successful higher-fidelity reconstruction.
5. Route new exports through the asset contract and atomic publisher; integrate peers through this file owner, not duplicate parallel pipelines.

**Success criteria:**

- Recorded resume tests skip exactly unchanged completed stages and recompute every stale stage after input/settings/engine changes.
- 12-photo compatibility and target 30/100/250-photo jobs use the actual production entry point with bounded memory and explicit unsupported-profile rejection until device qualification.
- Cancelling any stage leaves originals and the last valid completed result intact; failed texture work remains visibly distinct from textured success.

**Required verification:** HOST; ANDROID; DEVICE; GEOMETRY; RESILIENCE.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 030](#agent-030); [Agent 031](#agent-031); [Agent 032](#agent-032); [Agent 033](#agent-033); [Agent 034](#agent-034); [Agent 035](#agent-035); [Agent 036](#agent-036); [Agent 039](#agent-039); [Agent 041](#agent-041); [Agent 044](#agent-044); [Agent 045](#agent-045); [Agent 046](#agent-046); [Agent 050](#agent-050); [Agent 051](#agent-051); [Agent 053](#agent-053); [Agent 057](#agent-057); [Agent 061](#agent-061).

**Interface handoff:** Own C2 orchestration adapters, not every algorithm. Accept stage DTOs/artifacts from owners and return a typed PipelineResult carrying provenance. Agent 001/008 invoke this graph; Agent 023 publishes its validated artifacts.

**Start immediately:** Map run() into stage seams and write regression traces against the current backend. Land compatible extraction first; integrate new providers incrementally rather than waiting for all thirty engine tasks.

## Agent 003

**Role:** JNI safety, ownership and native API compatibility

**Branch:** `agents/003-jni-boundary`

**Priority:** P0

**Status receipt:** `docs/agents/status/003.md`

**Mission:** Make every Kotlin/native transition bounded, exception-safe and versioned, including sparse operations and allocation failure paths.

**Why this work remains:**

- [reconstruction/src/jni.cpp](reconstruction/src/jni.cpp): most legacy entrypoints allocate vectors/shared state without a complete exception boundary; newer checkpoint wrappers already catch std::exception.
- [app/src/main/java/com/realityforge/scanner/engine/NativeCore.kt](app/src/main/java/com/realityforge/scanner/engine/NativeCore.kt) and tests/test_project_contracts.py: exact exported-method parity is enforced.

**Exclusive existing files:**

- `reconstruction/src/jni.cpp`
- `app/src/main/java/com/realityforge/scanner/engine/NativeCore.kt`
- `tools/native_api.py`
- `app/src/androidTest/java/com/realityforge/scanner/NativeSmokeTest.kt`

**Reserved new files/packages:**

- `reconstruction/include/realityforge/jni_support.hpp`
- `app/src/main/java/com/realityforge/scanner/engine/nativebridge/`

**Implementation instructions:**

1. Audit every entrypoint for checked count arithmetic, array length, nulls, finite values, strings, pending Java exceptions and acquired-resource release.
2. Contain std::bad_alloc and all C++ exceptions inside JNI; map cancellation, invalid input and resource exhaustion to typed recoverable failures without swallowing VM exceptions.
3. Replace ambiguous ownership with RAII and stable operation IDs; preserve cancellation-token isolation and ensure finish cannot free state used by an active call.
4. Add new API adapters for typed representations, cancellation/progress and staged algorithms only on written owner contracts. Coordinate source-contract updates with Agent 094 and build registrations with 004.
5. Specify the same no-throw guarantee for all exported rf_* C ABI wrappers, including allocation while formatting errors; native source owners 037/038/040/041/046/053/057 implement their own boundary fixes. Define an ownership-safe allocation-failure return that rf_free and every caller handle correctly.

**Success criteria:**

- Malformed and oversized arrays, injected allocation failure and stale/cancelled handles cannot abort the process or leak a JNI resource.
- Exported declarations match Kotlin and survive minified release loading on both supported ABIs.
- Repeated begin/cancel/finish races are deterministic; independent operations cannot cancel each other and no C++ exception crosses JNI.

**Required verification:** HOST; ANDROID; DEVICE; SANITIZER; RELEASE.

**Integration inputs and cooperating owners:** [Agent 010](#agent-010); [Agent 051](#agent-051); [Agent 061](#agent-061); [Agent 081](#agent-081); [Agent 082](#agent-082); [Agent 091](#agent-091).

**Interface handoff:** Own C3 JNI method definitions and NativeCore together. Domain C++ source owners keep their own C ABI implementations; send exact safety requirements to them. Agent 002 is the primary consumer. Keep the host tools/native_api.py C ABI adapter compatible with the actual native methods; its ownership is also 003.

**Start immediately:** Harden existing JNI exports and add boundary failure tests immediately; new functions remain additive until their owners provide verified implementations.

## Agent 004

**Role:** Reproducible build foundation and dependency integration

**Branch:** `agents/004-build-foundation`

**Priority:** P0

**Status receipt:** `docs/agents/status/004.md`

**Mission:** Provide one reproducible build graph for all contributors without turning one hundred assignments into one hundred artificial modules.

**Why this work remains:**

- gradlew, gradlew.bat and tools/bootstrap-wrapper.* bootstrap an absent wrapper JAR; CI invokes a separately installed Gradle.
- CMakeLists.txt lists all native sources/tests explicitly; app/build.gradle.kts is a shared integration point for NDK, Room and future renderer/background modules.

**Exclusive existing files:**

- `CMakeLists.txt`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `app/build.gradle.kts`
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `tools/bootstrap-wrapper.sh`
- `tools/bootstrap-wrapper.ps1`
- `tools/requirements.txt`
- `tools/requirements-real-benchmark.txt`
- `.gitignore`

**Reserved new files/packages:**

- `gradle/wrapper/gradle-wrapper.jar`
- `cmake/`
- `build-logic/`

**Implementation instructions:**

1. Commit a verified official wrapper and distribution checksum; prove clean-checkout builds use the same Gradle/JDK/SDK/NDK versions on local machines and CI.
2. Add explicit sources/test targets and only justified logical module boundaries. Process peers' small registration requests continuously; no broad source glob that silently ships test code.
3. Integrate approved Room/job/Filament/Vulkan/release dependencies with 095 provenance; retain offline runtime and minSdk compatibility.
4. Separate host, debug, instrumentation and release configuration; apply 091 release and 097 benchmark conventions after their actual implementations exist.
5. Document bootstrap failures precisely and make generated build outputs ignored; never vendor SDKs or large binary toolchains into source.

**Success criteria:**

- A fresh checkout builds via the wrapper without depending on globally installed Gradle; the distribution is checksum-verified.
- All owned source/test registrations compile under warning-as-error host and pinned Android toolchains; no release package includes benchmark fixtures.
- Build dependencies and generated files are reproducible, licensed and reviewed; runtime retains its network-free contract.

**Required verification:** HOST; ANDROID; RELEASE; Clean-checkout wrapper build on Linux and Windows/macOS as available.

**Integration inputs and cooperating owners:** [Agent 091](#agent-091); [Agent 094](#agent-094); [Agent 095](#agent-095); [Agent 097](#agent-097).

**Interface handoff:** Own C4 shared build registration and dependency pins. Feature owners provide exact source paths, target names and required versions through requests; Agent 093 owns CI workflow orchestration.

**Start immediately:** Verify and commit the wrapper, reproduce baseline build commands and open the registration queue before feature work is ready. Integrate additive requests in small commits throughout the campaign.

## Agent 005

**Role:** Database schemas, upgrades and durable state model

**Branch:** `agents/005-database-migrations`

**Priority:** P0

**Status receipt:** `docs/agents/status/005.md`

**Mission:** Create a migration-safe persistence model for production operations while preserving every existing project and original photograph.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/Database.kt](app/src/main/java/com/realityforge/scanner/data/Database.kt): version 1 uses string project state, no job/artifact/edit/tombstone entities and no per-photo inclusion/order metadata.
- app/build.gradle.kts exports Room schemas but app/schemas is absent; DatabaseContractTest uses an in-memory database.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/data/Database.kt`
- `app/src/androidTest/java/com/realityforge/scanner/DatabaseContractTest.kt`

**Reserved new files/packages:**

- `app/schemas/`
- `app/src/main/java/com/realityforge/scanner/data/migrations/`

**Implementation instructions:**

1. Check in the true baseline Room schema, then add reviewed entities/indices and migrations for job leases, photo curation, capture identity, artifacts, edits and deletion journals as contracts arrive.
2. Define legal project/job transitions and transaction boundaries; use stable IDs, revision comparisons and unique constraints instead of display strings as business logic.
3. Provide actual on-disk migration fixtures from version 1 and test interrupted upgrades, index/foreign-key integrity and restart behavior.
4. Keep schema registration here while job/library/import owners implement their own DAOs or repositories. Never enable destructive migration as a shortcut.

**Success criteria:**

- An installed version 1 database with originals, calibration and completed output upgrades without losing references or changing photo hashes.
- Duplicate job acquisition, stale completion and repeated deletion recovery are rejected or idempotent under concurrent transactions.
- Exported schemas match generated schemas and migration tests execute every shipped upgrade path; failed upgrades keep recoverable data.

**Required verification:** ANDROID; DEVICE; Installed upgrade and Room MigrationTestHelper coverage.

**Integration inputs and cooperating owners:** [Agent 007](#agent-007); [Agent 013](#agent-013); [Agent 018](#agent-018); [Agent 022](#agent-022); [Agent 023](#agent-023); [Agent 066](#agent-066).

**Interface handoff:** Own C5 schema names, columns, indices and version increments. Peers own business services and send entity contracts; schema additions are coordinated before merged code relies on them.

**Start immediately:** Capture the existing schema and build a real version 1 upgrade fixture now. Land additive migrations as coherent bundles with provider entities, without waiting for UI work.

## Agent 006

**Role:** Project repository boundaries and operation leases

**Branch:** `agents/006-repository-boundaries`

**Priority:** P0

**Status receipt:** `docs/agents/status/006.md`

**Mission:** Keep a thin, well-defined repository façade over independently owned storage services and prevent conflicting filesystem/database operations.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt) combines create/import/preview validation/ZIP export/delete and atomic filesystem writes.
- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt) uses repository.dao directly, and its busy flag is process-local.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/operations/`
- `app/src/main/java/com/realityforge/scanner/data/ProjectAccess.kt`

**Implementation instructions:**

1. Characterize existing duplicate/revision/path protections before extracting import, publication, reopening, archive and deletion operations into their assigned services.
2. Introduce project-scoped read/write reservations with durable job identity where required; completion, export, deletion and input mutation must share the same conflict rules.
3. Validate project/artifact IDs and canonical storage roots at the façade boundary, preserving originals and refusing path traversal or stale-result access.
4. Wire service providers incrementally through real adapters; remove obsolete monolithic implementations only after their replacement is exercised by the production app.

**Success criteria:**

- Import/export/delete/reconstruct races cannot expose a deleted, incomplete or wrong-revision artifact.
- The façade remains a small transaction/coordination layer with one implementation for each storage operation.
- Existing session reliability tests pass and new concurrent-operation tests cover two projects and restart recovery.

**Required verification:** ANDROID; DEVICE; RESILIENCE.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 007](#agent-007); [Agent 011](#agent-011); [Agent 019](#agent-019); [Agent 020](#agent-020); [Agent 021](#agent-021); [Agent 022](#agent-022); [Agent 023](#agent-023); [Agent 024](#agent-024); [Agent 025](#agent-025); [Agent 026](#agent-026).

**Interface handoff:** Own C6 repository façade and project lease contracts. Each storage owner supplies a concrete service; Agent 001 consumes the façade instead of spreading direct DAO/filesystem calls across screens.

**Start immediately:** Extract interfaces around the working repository and prove unchanged behavior first. Complete service delegation as providers arrive; do not centralize all business logic in a renamed class.

## Agent 007

**Role:** Persistent reconstruction jobs and recovery ledger

**Branch:** `agents/007-durable-job-store`

**Priority:** P0

**Status receipt:** `docs/agents/status/007.md`

**Mission:** Represent scan execution durably so process recreation can recover meaningful work and cannot run or publish the same job twice.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/Database.kt](app/src/main/java/com/realityforge/scanner/data/Database.kt): recoverInterrupted only converts PROCESSING projects to a message; there is no durable queue or stage cursor.
- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt): task and nativeOperation exist only in the ViewModel process.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/jobs/`

**Implementation instructions:**

1. Define job ID, immutable input snapshot, revision/settings identity, stage cursor, attempt/lease token and typed terminal state using the shared C1/C2/C5 contracts.
2. Implement compare-and-set acquisition, renewal and relinquishing with monotonic attempt tokens; completion from an expired worker must be rejected.
3. Recover interrupted work by validating actual artifacts/checkpoints, preserving explicit user cancellation and requiring a new attempt for retry.
4. Expose bounded observable queue/progress history and idempotent pause/resume/cancel commands to the runner and UI, without persisting per-pixel events.

**Success criteria:**

- Two workers cannot hold a valid publishing lease for one job; a superseded attempt cannot commit output.
- Process kill and relaunch resume from accepted stage artifacts with the same input identity, while changed input starts a new job.
- Terminal states survive restart and distinguish completed, failed, paused, cancelled, stale and externally blocked work.

**Required verification:** ANDROID; DEVICE; RESILIENCE.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 009](#agent-009); [Agent 023](#agent-023).

**Interface handoff:** Provide JobStore/JobLease/JobSnapshot to 008,001,074 and 088. Agent 005 registers entities/migrations; 023 checks attempt+revision before publication.

**Start immediately:** Implement and test the job state machine and repository against an isolated real Room fixture while 005 finalizes app schema registration.

## Agent 008

**Role:** Android long-running execution and platform lifecycle

**Branch:** `agents/008-execution-lifecycle`

**Priority:** P0

**Status receipt:** `docs/agents/status/008.md`

**Mission:** Run user-initiated local reconstruction reliably within current Android background limits, with visible control and recoverable interruption.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/MainActivity.kt](app/src/main/java/com/realityforge/scanner/MainActivity.kt): onStop pauses all tasks; app/src/main/AndroidManifest.xml contains no processing service or job declarations.
- [docs/BUILDING.md](docs/BUILDING.md) still describes visible-app processing and an unimplemented long-running foreground architecture.

**Exclusive existing files:**

- `app/src/main/AndroidManifest.xml`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/work/execution/`

**Implementation instructions:**

1. Implement a durable runner over 007/002; decide and document the appropriate foreground service or bounded worker arrangement for offline image processing.
2. Verify current service-type requirements, notification behavior, background-start restrictions, Android 16 worker quotas and relevant service timeouts using official Android documentation.
3. Handle activity rotation/backgrounding, screen off, OS stop, task removal and service timeout by safely checkpointing/cancelling; never promise uninterrupted execution after force-stop.
4. Wire 029 notification actions,030 resource admission and 001 application lifecycle. Keep camera capture lifecycle separate from CPU reconstruction and preserve no-network permissions.

**Success criteria:**

- Processing survives the supported activity/background transitions or clearly checkpoints and resumes after permitted restart; it never publishes partial output.
- Pause/cancel notification controls act on the correct job and release native resources within measured bounds.
- Foreground/service behavior is tested on the supported API matrix, including permission denial and timeout paths, with no illegal service-type workaround.

**Required verification:** ANDROID; DEVICE; RESILIENCE; COMPATIBILITY.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 002](#agent-002); [Agent 007](#agent-007); [Agent 029](#agent-029); [Agent 030](#agent-030); [Agent 051](#agent-051).

**Interface handoff:** Own C7 runner lifecycle and the application manifest. 001 removes unconditional onStop cancellation only when this path is integrated; 029 supplies notifications and 007 authoritative job state.

**Start immediately:** Write lifecycle state transitions and an executable runner using the existing bounded pipeline. Verify API behavior before adding background declarations or switching ownership from the ViewModel.

## Agent 009

**Role:** Artifact identity, stage manifests and safe checkpoint reuse

**Branch:** `agents/009-artifact-provenance`

**Priority:** P0

**Status receipt:** `docs/agents/status/009.md`

**Mission:** Give every reusable stage an explicit versioned identity, representation and integrity record, including persisted sparse alignment.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt](app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt) and reconstruction/src/depth_checkpoint.cpp implement depth-specific identity/checksums.
- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt) uses a monolithic settings fingerprint and output JSON; complete sparse/alignment session checkpoints and per-stage artifact manifests are missing.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt`
- `app/src/test/java/com/realityforge/scanner/engine/DepthCheckpointIdentityTest.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/artifacts/`

**Implementation instructions:**

1. Specify canonical input/algorithm/settings/calibration hashes and stage-parent identities; include image order/inclusion, coordinate convention and relevant engine version.
2. Implement bounded manifests with byte sizes, content hashes, representation kind and completion state. Reject unknown versions, bad hashes and incomplete dependencies before use.
3. Serialize accepted feature graph/cameras/tracks/sparse geometry through a versioned alignment checkpoint that 002 can resume; preserve existing depth-cache compatibility only when algorithms are unchanged.
4. Define precise invalidation: edited capture/lens/geometry settings invalidate descendants; texture, LOD and export changes do not rerun unrelated alignment.
5. Coordinate atomic durable writes with 023/050 and artifact cleanup with 020; never make a valid checksum alone prove valid geometry.

**Success criteria:**

- Changing every relevant input independently invalidates exactly the affected stages; unchanged work is reused after restart.
- Truncated, reordered, malicious-size, wrong-version or mismatched-stage manifests are rejected without large allocation or stale publication.
- Sparse and dense checkpoints reproduce uncached accepted geometry within declared numerical tolerances and remain tied to project revision/attempt.

**Required verification:** ANDROID; DEVICE; RESILIENCE; Stage identity and corruption property tests.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 007](#agent-007); [Agent 023](#agent-023); [Agent 031](#agent-031); [Agent 033](#agent-033); [Agent 050](#agent-050); [Agent 061](#agent-061).

**Interface handoff:** Own C8 ArtifactRef/StageManifest/InputSnapshot serialization and identity policy. 002 orchestrates,023 commits,024 reads,020 collects and 067 exports; 050 owns native depth binary format.

**Start immediately:** Document current M19 hashes and create canonical identity/manifest tests against existing artifacts; evolve schemas with adapters and explicit version rejection, not silent cache reinterpretation.

## Agent 010

**Role:** Typed failures, truthful diagnostics and progress evidence

**Branch:** `agents/010-diagnostics-contract`

**Priority:** P0

**Status receipt:** `docs/agents/status/010.md`

**Mission:** Keep operational state and quality evidence machine-readable, locally useful and honest as the pipeline expands.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/ScanQualityEvidence.kt](app/src/main/java/com/realityforge/scanner/engine/ScanQualityEvidence.kt) and ScanQualityReport.kt already separate observed evidence from whole-object completeness.
- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt) and engine/SparsePipeline.kt still propagate free-text exceptions and string stage/progress messages.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/engine/ScanQualityEvidence.kt`
- `app/src/main/java/com/realityforge/scanner/engine/ScanQualityReport.kt`
- `app/src/test/java/com/realityforge/scanner/engine/ScanQualityEvidenceTest.kt`
- `app/src/androidTest/java/com/realityforge/scanner/ScanQualityReportDeviceTest.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/diagnostics/`

**Implementation instructions:**

1. Define stable error codes, stage outcomes, retry/recapture actions and progress units with optional totals; carry context without private file paths or embedded photographs.
2. Extend evidence to visibility support, topology, units, calibration assumptions and stage failures supplied by owners; keep missing evidence null/unknown rather than zero or success.
3. Preserve per-scene/per-stage failure reasons and emit a versioned local diagnostic report linked to the exact artifact and job.
4. Move display/localization into 074/078. Add bounded event retention and opt-in diagnostic export through 026/067 with no analytics upload.

**Success criteria:**

- A sparse fallback, missing texture, unknown scale and unobserved surface have distinct typed outcomes in both JSON and UI.
- Progress is monotonic only within a fixed (job, attempt, stage); a new stage, retry or recomputed checkpoint may reset its measured counter. No invented global percentage or cross-job update is allowed.
- Diagnostic import/export validates schema/revision, redacts unwanted private data, and retains existing M19 evidence tests.

**Required verification:** ANDROID; DEVICE; Focused deterministic diagnostics regressions.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 049](#agent-049); [Agent 055](#agent-055); [Agent 057](#agent-057).

**Interface handoff:** Own C9 ScanEvidence/StageProgress/ScanFailure. All stage owners report raw metrics through this contract; 074 presents them and 084/085 validate them against independent measurements.

**Start immediately:** Preserve and extend the current M19 tests into a typed compatibility model before integrating new measurements.

## Agent 011

**Role:** Bounded photo import and document-provider ingestion

**Branch:** `agents/011-bounded-photo-import`

**Priority:** P0

**Status receipt:** `docs/agents/status/011.md`

**Mission:** Implement a reusable offline import service that preserves original bytes, binds each request to its initiating project revision, and reports actionable per-photo outcomes.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt):85-129 importPhoto bounds one stream but mixes copying, validation, duplicate detection, persistence and cleanup.
- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt):87-106 import holds transient URI list and reports only the final rejection reason; interrupted provider access has no durable batch identity.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/importing/`
- `docs/agents/status/011.md`
- `app/src/test/java/com/realityforge/scanner/agents/a011/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a011/`

**Implementation instructions:**

1. Define immutable import request/result records with operation ID, destination project, source URI, duplicate/rejected/imported outcomes and bounded progress events.
2. Move copying and validation into staged, cancellable 64 KiB streaming operations; enforce byte, image-dimension, batch and free-space budgets without trusting provider MIME or length.
3. Delegate decode/metadata, identity, original publication and database updates to their assigned owners; send owner 006 exact facade replacement instructions.
4. Handle provider revocation, zero-byte reads, corrupt images, duplicate races and cancellation without retaining full-resolution batches or deleting committed originals.
5. Keep project/session identity fixed throughout a batch while advancing expected revision after each committed import, or use an explicitly admitted transactional batch. Before durable dispatch privately stage sources or retain offered persistable URI grants when needed; track/release owned grants and recover from revocation.

**Success criteria:**

- Instrumentation imports supported local/provider fixtures and verifies exact original SHA-256 equality, duplicate outcomes and coherent revision increments.
- Fault-injected short reads, permission loss, disk-full and cancellation leave no published partial photo and no untracked staging files after recovery.
- The real UI imports a mixed batch with per-photo failure details, and returning from a picker cannot import into another project.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 012](#agent-012); [Agent 013](#agent-013); [Agent 021](#agent-021); [Agent 023](#agent-023).

**Interface handoff:** Expose ImportService.import(ImportRequest) with typed progress/results; 006 delegates repository calls and 001 binds pending activity results. Use 023 publication, not direct DAO/file coordination.

**Start immediately:** Implement provider adapters and a deterministic failing-stream fixture suite against baseline types; integrate real persistence/publication ports before marking success.

## Agent 012

**Role:** Image decoding, EXIF orientation and memory ownership

**Branch:** `agents/012-image-decoding-lifetimes`

**Priority:** P0

**Status receipt:** `docs/agents/status/012.md`

**Mission:** Make all supported image decoding deterministic, bounded, correctly oriented and explicit about color and bitmap ownership while preserving original photographs.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/ImageLoader.kt](app/src/main/java/com/realityforge/scanner/engine/ImageLoader.kt):22-51 upright bounds decode and handles EXIF transforms, but intermediate allocations can escape cleanup when metadata/transforms throw.
- [app/src/main/java/com/realityforge/scanner/engine/ImageLoader.kt](app/src/main/java/com/realityforge/scanner/engine/ImageLoader.kt):59-66 quality simultaneously allocates bitmap-sized pixel and grayscale buffers; color handling and supported-format behavior lack dedicated coverage.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/engine/ImageLoader.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/imaging/`
- `docs/agents/status/012.md`
- `app/src/test/java/com/realityforge/scanner/agents/a012/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a012/`

**Implementation instructions:**

1. Document upright, bounds, focal and quality contracts; maintain compatibility adapters for existing engine callers while defining explicit owned decoded-image lifetimes.
2. Cover all eight EXIF orientations, mirrored images, odd aspect ratios and malformed metadata; ensure every superseded/interrupted bitmap is released safely.
3. Define analysis color-space and alpha behavior for JPEG, PNG, WebP and supported HEIF/HEIC decoders; preserve embedded original bytes independently.
4. Bound transient allocations before decode/scale/quality computation, move appropriate work off the UI thread and provide typed unsupported-format/resource errors.

**Success criteria:**

- Golden asymmetric fixtures for all EXIF orientations produce expected upright pixel coordinates and dimensions without modifying original hashes.
- Malformed/truncated/oversized files and decoder failures terminate with typed errors while instrumentation reports no retained bitmap growth across repeated runs.
- Peak allocation measurements fit a documented per-image budget for supported large inputs, with no whole-collection full-resolution allocation.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Physical-device supported-format decode matrix.

**Integration inputs and cooperating owners:** [Agent 010](#agent-010).

**Interface handoff:** Preserve ImageLoader compatibility until 002/006/027 migrate; expose metadata-independent DecodeRequest/OwnedImage contracts to 011, 013, 016 and preview owners.

**Start immediately:** Build asymmetric orientation and corrupt-image fixtures against existing ImageLoader immediately; implement exception-safe ownership independently of new persistence contracts.

## Agent 013

**Role:** Lens and capture metadata identity

**Branch:** `agents/013-capture-metadata-identity`

**Priority:** P0

**Status receipt:** `docs/agents/status/013.md`

**Mission:** Introduce versioned, evidence-based photo and camera metadata so reconstruction/calibration can distinguish known compatible captures from assumptions.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/Database.kt](app/src/main/java/com/realityforge/scanner/data/Database.kt):23-35 ScanPhoto records dimensions and focal35mm but no camera/lens/zoom/crop/orientation identity.
- [app/src/main/java/com/realityforge/scanner/engine/LensCalibration.kt](app/src/main/java/com/realityforge/scanner/engine/LensCalibration.kt):96-104,142-145 estimates compatibility from image dimensions and EXIF focal ratios, without a physical camera identifier.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/capture/metadata/`
- `docs/agents/status/013.md`
- `app/src/test/java/com/realityforge/scanner/agents/a013/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a013/`

**Implementation instructions:**

1. Define immutable metadata records for capture source, camera/physical lens identifier when available, zoom, crop, image orientation, focal values and uncertainty.
2. Implement CameraX/Camera2 and EXIF adapters that retain provenance per field, identify missing/conflicting observations and never invent a physical lens ID.
3. Produce canonical identity inputs and compatibility assessments for owner 009 and profile owner 027; send owner 005 exact additive schema/serialization requirements.
4. Separate original metadata preservation from user-selected derived-export privacy choices; do not silently remove original EXIF or introduce telemetry.

**Success criteria:**

- Camera and imported-image fixture matrices distinguish same lens, changed zoom/crop, rotated orientation and absent metadata with deterministic reasons.
- Canonical serialized metadata round-trips without loss and changes relevant input identities when optical settings actually change.
- Calibration/reconstruction integration rejects or explicitly downgrades unsupported compatibility assumptions while unchanged original hashes remain intact.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Physical Camera2 metadata verification.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 012](#agent-012).

**Interface handoff:** Publish CaptureMetadata, FieldEvidence and LensCompatibilityResult; consumed by 011, 014, 015, 027 and 009. Database owner 005 owns all schema changes.

**Start immediately:** Implement pure metadata schemas, EXIF fixtures and canonical compatibility rules now; wire real camera results when 014/015 expose them.

## Agent 014

**Role:** Camera session identity, lifecycle and permission recovery

**Branch:** `agents/014-camera-session-lifecycle`

**Priority:** P0

**Status receipt:** `docs/agents/status/014.md`

**Mission:** Build a continuous capture session whose every image belongs to its initiating project and whose camera resources survive or stop cleanly across Android lifecycle events.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):68-70 completion carries only File, while ScannerViewModel.captureReady: 108-116 reads the current selected project.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):74 system Back can close the camera during taking; CameraScreen.kt:29-30,57-60 lacks permanent-denial/resume recovery.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/capture/session/`
- `app/src/main/java/com/realityforge/scanner/capture/permissions/`
- `docs/agents/status/014.md`
- `app/src/test/java/com/realityforge/scanner/agents/a014/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a014/`

**Implementation instructions:**

1. Replace bare-file completion with a session/project/revision/token-bound capture result; reject stale callbacks and clean their private staging files.
2. Keep capture open for repeated photographs, display real saved counts, and handle Back, rotation, process recreation, camera disposal and queued callbacks coherently.
3. Implement camera permission rationale, permanent denial/settings return, revocation recheck and device camera availability states while retaining photo import access.
4. Bind target rotation/resolution and only this session's use cases; integrate optical controls, analysis and guidance through their owners' interfaces.

**Success criteria:**

- A deliberately delayed image callback after Back and project change never imports into the newly selected project or leaves orphan temporary files.
- Instrumentation covers deny/regrant/revoke, pause/resume, rotation, disposal during capture and repeated shots without leaked camera bindings.
- A real camera session captures multiple correctly oriented originals under one stable identity and reports save/focus/device failures without false success.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Physical-device multi-shot/permission/lens tests.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 006](#agent-006); [Agent 011](#agent-011); [Agent 013](#agent-013); [Agent 015](#agent-015); [Agent 016](#agent-016); [Agent 017](#agent-017); [Agent 071](#agent-071).

**Interface handoff:** Expose CaptureSessionState and CapturedPhoto(projectId, revision, sessionToken, stagedFile, metadata). 001 validates publication; 071 passes immutable session identity and navigation events. Multi-shot session identity remains fixed, but each committed photo advances revision; obtain the next expected revision instead of rejecting later shots against the initial revision.

**Start immediately:** Reproduce the delayed callback race with a controllable capture adapter and implement session state/permission logic before dependent camera features arrive.

## Agent 015

**Role:** Camera optical controls and device capabilities

**Branch:** `agents/015-camera-optical-controls`

**Priority:** P1

**Status receipt:** `docs/agents/status/015.md`

**Mission:** Expose reliable capability-aware optical controls that improve capture consistency and record the actual applied settings needed by reconstruction.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):43-46 binds DEFAULT_BACK_CAMERA, maximize-quality JPEG and zoom 1× with no focus/exposure/WB policy.
- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):74 explicitly leaves exposure locking planned; calibration requires matching optical conditions.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/capture/controls/`
- `docs/agents/status/015.md`
- `app/src/test/java/com/realityforge/scanner/agents/a015/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a015/`

**Implementation instructions:**

1. Inspect CameraX/Camera2 capabilities and report available physical cameras, focus modes, AE/AWB control, zoom, resolution and stabilization without assuming universal support.
2. Implement safe focus/exposure/WB behavior and clear lock/pending/unavailable state; confirm applied settings through results when the platform exposes them.
3. Record lens, zoom, crop and capture parameters using owner 013 metadata; avoid silent lens switching during an active calibrated scan.
4. Provide a composable controls model to 014 with bounded asynchronous camera commands, error recovery and documented device-specific fallbacks.

**Success criteria:**

- Unit/camera-adapter tests show unsupported controls remain unavailable and a requested lock is never shown as applied before acknowledgment.
- Physical-device runs record actual capture metadata demonstrating stable selected optics across multiple shots and honest handling of unavailable locks.
- Cancel/rebind/permission-loss tests leave no outstanding camera-control futures that update a disposed or different capture session.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Physical camera capability and setting-result matrix.

**Integration inputs and cooperating owners:** [Agent 010](#agent-010); [Agent 013](#agent-013).

**Interface handoff:** Expose CameraCapabilities, OpticalControlRequest and AppliedOpticsState to 014; use 013 metadata and session-token checks. CameraScreen ownership remains 014.

**Start immediately:** Implement capability interpretation and fake-adapter state regressions now; validate supported controls through a real CameraX binding before completion.

## Agent 016

**Role:** Bounded live capture quality analysis

**Branch:** `agents/016-live-capture-quality`

**Priority:** P1

**Status receipt:** `docs/agents/status/016.md`

**Mission:** Provide measured, resource-bounded live feedback about blur, exposure and camera motion so users can improve photographs before saving them.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):43-45 binds only Preview and ImageCapture; there is no live ImageAnalysis use case.
- [app/src/main/java/com/realityforge/scanner/engine/ImageLoader.kt](app/src/main/java/com/realityforge/scanner/engine/ImageLoader.kt):59-66 and ui/ScannerApp.kt: 348-350 compute/display quality only after import.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/capture/analysis/`
- `docs/agents/status/016.md`
- `app/src/test/java/com/realityforge/scanner/agents/a016/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a016/`

**Implementation instructions:**

1. Implement a CameraX ImageAnalysis adapter with documented YUV/rotation handling, keep-latest backpressure and unconditional ImageProxy closure.
2. Compute bounded quality observations at a controlled cadence, carrying timestamps, confidence and unknown/unsupported states instead of invented quality percentages.
3. Derive thresholds from representative retained photographs and expose actionable retake/stabilize/exposure advice with temporal smoothing that avoids flicker.
4. Integrate budget changes from 030 and quality UI/state through 014; analyzer failures must not prevent normal camera capture.

**Success criteria:**

- Analyzer unit/instrumentation fixtures correlate blur/exposure signals with defined image changes and preserve rotation-sensitive behavior.
- Every success, rejection, exception, cancellation and disposed-session path closes its ImageProxy exactly once and stops publishing stale observations.
- Physical-device capture records demonstrate bounded analyzer CPU/memory/latency and compare suggested actions against the corresponding saved-image quality.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Physical live-analysis quality and resource measurements.

**Integration inputs and cooperating owners:** [Agent 010](#agent-010); [Agent 012](#agent-012); [Agent 013](#agent-013); [Agent 030](#agent-030).

**Interface handoff:** Expose timestamped CaptureQualityObservation and analyzer lifecycle hooks to 014/017; 030 supplies cadence/budget constraints. No direct DAO or camera binding changes.

**Start immediately:** Implement synthetic YUV/blur/exposure fixtures, buffer ownership and pure quality logic immediately; retain physical validation as a distinct required gate.

## Agent 017

**Role:** Measured overlap and capture diversity guidance

**Branch:** `agents/017-capture-overlap-guidance`

**Priority:** P1

**Status receipt:** `docs/agents/status/017.md`

**Mission:** Turn real observed overlap and viewpoint evidence into useful capture advice while keeping unknown and unobserved coverage explicit.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):55 provides only static capture instructions and line 74 says live coverage is planned.
- [app/src/main/java/com/realityforge/scanner/engine/ScanQualityEvidence.kt](app/src/main/java/com/realityforge/scanner/engine/ScanQualityEvidence.kt) provides post-reconstruction recapture advice; no live image-overlap or viewpoint-diversity module exists.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/capture/guidance/`
- `docs/agents/status/017.md`
- `app/src/test/java/com/realityforge/scanner/agents/a017/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a017/`

**Implementation instructions:**

1. Define a bounded recent-frame/photo observation window and guidance model with overlap evidence, geometric confidence and explainable movement suggestions.
2. Consume actual matched features/validated poses through an engine adapter coordinated with 002; combine motion/quality observations only when their timestamps and session identities agree.
3. Avoid monotonic fake coverage rings or claims about unseen surfaces; label image overlap separately from confirmed geometric object coverage.
4. Evaluate guidance on intentional low-overlap, repeated-view, pure-rotation and moving-object sequences; integrate user-visible advice via 014 and instructions owner 075.

**Success criteria:**

- Deterministic fixtures produce distinct unknown, repeated-view, insufficient-overlap and useful-diversity outcomes based on observed inputs.
- Real capture comparisons report registration/completeness outcomes for guided and unguided sequences without treating synthetic rigs as physical evidence.
- Memory, retained-frame count and update cadence remain within declared limits, and stale-session evidence cannot affect a new capture.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Authorized physical capture guidance benchmark.

**Integration inputs and cooperating owners:** [Agent 002](#agent-002); [Agent 010](#agent-010); [Agent 013](#agent-013); [Agent 016](#agent-016); [Agent 030](#agent-030).

**Interface handoff:** Expose GuidanceObservation with evidence IDs, confidence and action codes; 014 renders it, 075 explains it, and 002 supplies real matching/pose adapters.

**Start immediately:** Implement bounded evidence/state rules and replay fixtures against an injected matcher now; final acceptance requires the real engine path and physical sequences.

## Agent 018

**Role:** Photo curation commands and reconstruction selection

**Branch:** `agents/018-photo-curation`

**Priority:** P0

**Status receipt:** `docs/agents/status/018.md`

**Mission:** Allow users to control which photographs contribute to a scan through reversible, revision-safe curation commands that preserve originals and invalidate appropriate outputs.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/Database.kt](app/src/main/java/com/realityforge/scanner/data/Database.kt):38-55 exposes insert/photos/invalidate but no exclusion, removal or ordering commands.
- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt):53 selects the first twelve stored photos, while ScannerApp.PhotoRow: 338-352 provides no curation actions.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/curation/`
- `docs/agents/status/018.md`
- `app/src/test/java/com/realityforge/scanner/agents/a018/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a018/`

**Implementation instructions:**

1. Define include/exclude/reorder/remove commands with expected revision, stable photo IDs, reasons and an explicit distinction between exclusion and permanent deletion.
2. Implement transactional curation through 005/006 ports, keeping ordered reconstruction inputs consistent across running jobs, previews and export identities.
3. Specify removal staging and restore/undo behavior using 022/023; never mutate or silently discard originals when merely excluding a bad photo.
4. Supply clear resulting selection/status data to 073 and exact engine-selection integration requirements to 002; reconcile selected-input counts with existing diagnostics.

**Success criteria:**

- Randomized command sequences retain a deterministic selection order and increment revisions only for real input changes.
- A running job cannot publish as current after include/exclude/reorder/remove changes its selected inputs; undo restores a coherent selection.
- The actual photo-review UI excludes poor images and reruns using that selection while all retained original SHA-256 hashes remain unchanged.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest.

**Integration inputs and cooperating owners:** [Agent 002](#agent-002); [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 022](#agent-022); [Agent 023](#agent-023).

**Interface handoff:** Expose PhotoCurationService and immutable SelectedPhotoSet; 005 owns schema/DAO, 006 coordinates commands, 002 reads the selected set and 073 offers actions.

**Start immediately:** Build pure command/revision/order models and race fixtures against repository ports now; integrate actual DAO and pipeline selection before completion.

## Agent 019

**Role:** Project library queries, rename and independent duplication

**Branch:** `agents/019-project-library-commands`

**Priority:** P1

**Status receipt:** `docs/agents/status/019.md`

**Mission:** Provide coherent project management for a growing offline collection with safe rename/query and independently editable duplicates.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/Database.kt](app/src/main/java/com/realityforge/scanner/data/Database.kt):40-45 supports an unfiltered createdAt-sorted project list and insertion only.
- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt):79-83 validates names only during create; ui/ScannerApp.kt: 102-110 shows basic project rows without management actions.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/library/`
- `docs/agents/status/019.md`
- `app/src/test/java/com/realityforge/scanner/agents/a019/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a019/`

**Implementation instructions:**

1. Define repository-level rename, search/filter/sort and duplicate commands with validated names, stable IDs, revision expectations and bounded library summaries.
2. Specify additive indexed query/schema requirements to 005 and facade adapters to 006; keep filesystem and Room operations off the main thread.
3. Implement duplicate staging through artifact/original publication contracts, preserving provenance while assigning independent project/operation identities and preventing shared-file deletion hazards.
4. Expose progress, cancellation and storage-preflight failures to 072; define whether derived results are copied/reused only when identity validation proves compatibility.

**Success criteria:**

- Library queries return stable documented order/filter results for large fixture collections and handle names with Unicode/empty/oversized content.
- Duplicating, editing and deleting one project cannot alter the other's originals, metadata or completed results.
- Interrupted duplication/rename and disk-full faults leave a recoverable coherent project state with no false completed duplicate.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 021](#agent-021); [Agent 023](#agent-023).

**Interface handoff:** Expose ProjectLibraryService commands and paged/bounded ProjectSummary flows to 001/072; 005 owns indexes/migrations and 006 is the public repository facade.

**Start immediately:** Implement validation, sorting/query contracts and copy-operation fixtures against baseline snapshots; integrate staged persistence when 005/023 are available.

## Agent 020

**Role:** Derived artifact and cache garbage collection

**Branch:** `agents/020-derived-cache-gc`

**Priority:** P0

**Status receipt:** `docs/agents/status/020.md`

**Mission:** Keep derived storage bounded through a reference-aware collector that cannot delete originals, active inputs or completed results still retained by policy.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt](app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt):35-54 bounds depth identities but does not manage feature caches, old result artifacts or orphan staging globally.
- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt):24-30 creates project directories and artifacts without a unified retention inventory.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/cachegc/`
- `docs/agents/status/020.md`
- `app/src/test/java/com/realityforge/scanner/agents/a020/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a020/`

**Implementation instructions:**

1. Build a typed inventory of feature/depth/intermediate/result artifacts from owner 009/023 manifests and job references instead of guessing from filenames alone.
2. Define retention and cache-eviction rules for stale identities, obsolete results and abandoned temporary files, with explicit preservation sets and space accounting.
3. Implement resumable dry-run/execute cleanup with safe path validation, cancellation and active-operation exclusion; delegate true project/original deletion to 022.
4. Expose per-category reclaimable bytes and cleanup results to storage UI 028 and resource budgets 021; validate identities before reusing remaining checkpoints.

**Success criteria:**

- Fixture trees with active jobs, retained outputs, originals, unknown files and stale caches produce an exact expected deletion plan.
- Cancellation, failure and concurrent publication never remove current references; repeating cleanup is idempotent and reconciles interrupted work.
- A real multi-revision scan/cache-clear/rerun session preserves original/result hashes and recomputes only deleted derived stages.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest.

**Integration inputs and cooperating owners:** [Agent 006](#agent-006); [Agent 007](#agent-007); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 021](#agent-021); [Agent 023](#agent-023).

**Interface handoff:** Expose CacheInventory, CleanupPlan and CacheCollector execution results to 021/028; consume 007 active-job references and 023 manifests. 022 owns tombstones/original removal.

**Start immediately:** Implement deterministic inventory/retention logic and adversarial filesystem fixtures now; use actual job/publication references for final integration.

## Agent 021

**Role:** Storage preflight, reservations and peak-space budgets

**Branch:** `agents/021-storage-budgets`

**Priority:** P0

**Status receipt:** `docs/agents/status/021.md`

**Mission:** Prevent predictable storage exhaustion with explicit operation-level estimates and reservations while handling remaining I/O failures safely.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt):88 checks a fixed 512 MiB once before import; line 104 caps a single source at 150 MiB.
- [app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt](app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt) publishes depth/mesh/texture artifacts without a shared reservation covering simultaneous originals, caches, staging and outputs.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/storagebudget/`
- `docs/agents/status/021.md`
- `app/src/test/java/com/realityforge/scanner/agents/a021/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a021/`

**Implementation instructions:**

1. Define a storage model for preserved originals, analysis pyramids, feature/depth caches, mesh/texture/export staging and retained previous results.
2. Implement filesystem free-space probing plus revision/operation-bound reservations with conservative uncertainty; do not present estimated capacity as guaranteed available space.
3. Recheck budgets at meaningful stage boundaries and release/recover reservations on cancel, success or process restart; cooperate with 020 for safe cache reclamation.
4. Provide actionable required/available/reclaimable values to 011, 019, 025, 026, 028 and 030 without changing shared repository or database files.

**Success criteria:**

- Pure budget tests cover concurrent operations, arithmetic overflow, unknown provider sizes and worst-case temporary duplication with documented conservative bounds.
- Fault injection exhausts disk before/mid-import, reconstruction and export without publishing partial results or losing prior completed artifacts.
- Reservation recovery after process death releases abandoned claims while retaining active/recoverable work; UI reports actual measured available space.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Android storage-exhaustion fault gate.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 007](#agent-007); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 023](#agent-023).

**Interface handoff:** Expose StorageEstimate, ReservationToken and SpaceDecision to import/jobs/export/storage UI; 005 owns reservation persistence and 006/008 integrate lifecycle.

**Start immediately:** Build arithmetic and failure-path tests with a controllable filesystem quota provider now; calibrate estimates against measured real pipeline artifacts before final acceptance.

## Agent 022

**Role:** Deletion tombstones and recoverable private-file removal

**Branch:** `agents/022-deletion-tombstones`

**Priority:** P0

**Status receipt:** `docs/agents/status/022.md`

**Mission:** Make explicit project/photo deletion resumable and truthful across process death without deleting unrelated or still-referenced files.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt): 191-195 delete removes the Room row before recursive file deletion; failure can leave inaccessible private originals.
- [app/src/main/java/com/realityforge/scanner/data/Database.kt](app/src/main/java/com/realityforge/scanner/data/Database.kt):53-55 recovers only PROCESSING state and deletes projects directly; no durable cleanup tombstone exists.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/deletion/`
- `docs/agents/status/022.md`
- `app/src/test/java/com/realityforge/scanner/agents/a022/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a022/`

**Implementation instructions:**

1. Define durable deletion requests/tombstones with project/photo scope, operation ID, confirmed intent and cleanup progress; submit schema/DAO requirements to 005.
2. Stage logical deletion so new work cannot start, enumerate canonical app-private owned paths, and remove files with bounded resumable traversal.
3. Coordinate current jobs, exports, curation and cache cleanup through 006/007; keep excluded photos distinct from explicit permanent removal.
4. Recover outstanding tombstones at startup and expose cleanup failure/retry state instead of silently dropping every visible reference to remaining private data.

**Success criteria:**

- Crashing at each database/file boundary yields a recoverable tombstone and a deterministic retry; deletion completion is reported only after the defined cleanup succeeds.
- Symlinks/traversal-like filenames, shared references and concurrent active artifacts never cause removal outside authorized project-owned paths.
- Real UI project/photo deletion, cancel-before-confirmation and cleanup retry tests preserve unrelated projects and external source photographs.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Crash-boundary deletion recovery gate.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 007](#agent-007); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 023](#agent-023).

**Interface handoff:** Expose DeletionService and PendingDeletion state to 006/018/019/072/073; 005 persists tombstones. 020 manages derived retention, not project deletion.

**Start immediately:** Implement path ownership, retryable state transitions and fault-injected filesystem fixtures immediately; wire persistence and real facade entrypoints later.

## Agent 023

**Role:** Atomic result publication and last-completed preservation

**Branch:** `agents/023-atomic-result-publication`

**Priority:** P0

**Status receipt:** `docs/agents/status/023.md`

**Mission:** Introduce a durable multi-artifact publication protocol that preserves the last completed result and never exposes partial or stale work as current.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt): 116-123 import renames before DB publication; writeAtomic: 197-203 covers one file only.
- [app/src/main/java/com/realityforge/scanner/data/Database.kt](app/src/main/java/com/realityforge/scanner/data/Database.kt):49-52 clears result references on PROCESSING and completes a single key; SparsePipeline.kt: 410-445 publishes multiple artifacts sequentially.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/publication/`
- `docs/agents/status/023.md`
- `app/src/test/java/com/realityforge/scanner/agents/a023/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a023/`

**Implementation instructions:**

1. Define staged artifact-set manifests with required/optional representations, hashes, input/settings/engine identity and operation/revision ownership from 009.
2. Implement file sync, completion validation and durable publication journal states coordinated with 005 transactions; document filesystem durability guarantees accurately.
3. Preserve prior completed output independently from current inputs and active jobs; publish a new current result only after every required artifact validates and expected revision still matches.
4. Reconcile process death between stage writes, renames and database updates, including staged original imports; provide exact producer adapters to 002/006 rather than editing them.

**Success criteria:**

- Fault injection at every publication boundary never produces a current result referencing absent/truncated/mismatched artifacts.
- Failed/cancelled/stale reruns retain an accessible explicitly previous completed result, and original photograph hashes remain unchanged.
- A killed-process reconstruction/import resumes or safely reconciles its journal, with repeated recovery yielding the same valid final state.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Process-kill publication recovery gate.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 007](#agent-007); [Agent 009](#agent-009); [Agent 010](#agent-010).

**Interface handoff:** Expose ArtifactPublicationSession, VerifiedArtifactSet and reconciliation APIs; 002 produces staged outputs, 006 coordinates storage, 005 commits metadata, 024/026 read only verified sets.

**Start immediately:** Implement journal state transitions and fault-injected filesystem/database-port tests against the baseline artifact set now; final proof must use actual producers.

## Agent 024

**Role:** Safe reopening and typed preview schema validation

**Branch:** `agents/024-reopen-schema-integrity`

**Priority:** P0

**Status receipt:** `docs/agents/status/024.md`

**Mission:** Validate completed scan data into bounded typed models before it reaches UI or export, with useful recovery when stored artifacts are corrupt or obsolete.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt):32-76 currentPreview bounds framing/revision but returns arbitrary JSONObject without validating every consumed field.
- [app/src/main/java/com/realityforge/scanner/ui/PointCloudViewer.kt](app/src/main/java/com/realityforge/scanner/ui/PointCloudViewer.kt):21-34 and ui/SurfaceMeshViewer.kt:24-59 parse arrays and allocate inside Compose; valid-JSON corruption can reach UI assumptions.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/reopening/`
- `docs/agents/status/024.md`
- `app/src/test/java/com/realityforge/scanner/agents/a024/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a024/`

**Implementation instructions:**

1. Define versioned result/representation schemas and immutable display DTOs with explicit sparse/dense/mesh/texture capabilities and unavailable-stage reasons.
2. Validate counts, field types, finite coordinates, index ranges, array lengths, identity and linked artifact manifests before allocating display buffers.
3. Load and prepare previews off the UI thread with cancellation, resource bounds and project/revision rechecks; do not silently reinterpret legacy schemas.
4. Provide explicit read-only recovery, previous-result selection or rerun actions through 001/006/070/074; delegate photographic GLB structural validation to 068.

**Success criteria:**

- A mutation corpus of revision-valid JSON with missing fields, wrong types, invalid indices and oversized arrays returns typed failure without crashing Compose.
- Valid baseline artifacts preserve identical geometry and diagnostics after DTO conversion and bounded reopening across schema versions.
- Concurrent input change/deletion or cancelled reopen never republishes stale previews; current and previous completed results remain clearly distinguished.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Corrupt preview schema/fuzz gate.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 023](#agent-023); [Agent 068](#agent-068).

**Interface handoff:** Expose ReopenResult and bounded ScanDisplayModel to 001/070/073/074; 006 delegates currentPreview replacement, 068 parses photographic GLB, 009 supplies identity/schema versions.

**Start immediately:** Build a valid baseline JSON corpus and systematically corrupted variants now; implement typed validation independently of publication integration.

## Agent 025

**Role:** Capture archive export, validation and project restoration

**Branch:** `agents/025-capture-archive-roundtrip`

**Priority:** P1

**Status receipt:** `docs/agents/status/025.md`

**Mission:** Provide a versioned offline capture/project archive round trip that preserves original bytes and can restore safely into a new independent project.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt): 131-188 exportCaptureBundle preserves photos/profile and validates revisions but there is no archive import.
- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt): 138 writes stale engineVersion m05-real-validation-1; archive source is correctly labelled unverified and must remain truthful.

**Exclusive existing files:**

- `app/src/androidTest/java/com/realityforge/scanner/OfflineCaptureBundleTest.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/archives/`
- `docs/agents/status/025.md`
- `app/src/test/java/com/realityforge/scanner/agents/a025/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a025/`

**Implementation instructions:**

1. Define a manifest containing schema, accurate engine identity, per-member hashes/lengths, metadata/provenance, calibration and original-input ordering.
2. Implement streaming archive writer and bounded staged importer; reject traversal, duplicate paths, decompression bombs, unsupported schemas, excessive entries and inconsistent hashes.
3. Restore only after full validation through 005/006/009/023, assigning a new project identity and preserving accurate synthetic/unverified/physical provenance.
4. Integrate cancellation/storage budgets and 026 destination handling; do not auto-upload, silently share, or claim arbitrary complete app-backup compatibility.

**Success criteria:**

- Export/import round trips reproduce all original SHA-256 bytes, ordering and compatible calibration metadata while yielding an independently editable project.
- An adversarial ZIP corpus including traversal, duplicate names, oversized inflated members and missing completion manifest cannot write outside staging or publish a project.
- Disk-full, cancellation and process death preserve existing projects and clean/reconcile uncommitted archive imports without a false completion notice.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Archive adversarial-input and byte-roundtrip gate.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 013](#agent-013); [Agent 021](#agent-021); [Agent 023](#agent-023); [Agent 026](#agent-026); [Agent 027](#agent-027).

**Interface handoff:** Expose CaptureArchiveWriter/Importer and validated manifest records; 006 replaces legacy archive implementation, 026 handles chosen destination, 019/072 may expose restore.

**Start immediately:** Implement archive schema and byte-roundtrip/adversarial fixtures using baseline capture bundles; integrate real staged publication when 023 arrives.

## Agent 026

**Role:** SAF export transfer, cancellation and partial failure handling

**Branch:** `agents/026-saf-export-io`

**Priority:** P0

**Status receipt:** `docs/agents/status/026.md`

**Mission:** Make user-directed file transfers reliable and transparent across Android document providers while preserving correct project identity and source integrity.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt): 245-275 export checks revisions and copies cancellably but does not verify an artifact hash and leaves partial external documents.
- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt): 228-242 capture export likewise warns the user to delete incomplete destinations; provider transaction capabilities are unmodelled.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/data/safexport/`
- `docs/agents/status/026.md`
- `app/src/test/java/com/realityforge/scanner/agents/a026/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a026/`

**Implementation instructions:**

1. Define destination request ownership and capabilities for newly created versus pre-existing documents; bind URI callbacks to immutable export requests from 001/067.
2. Verify staged source artifact/hash/revision before transfer, stream with bounded buffers and cancellation, then verify available completion evidence without assuming provider atomicity.
3. Use local staging or temporary-provider documents only when supported; clean only owned newly created incomplete outputs, never delete unrelated/pre-existing user documents.
4. Return structured cancellation, permission, storage and partial-destination outcomes with actionable UI; support model and archive streams without duplicating format encoders.

**Success criteria:**

- A test document provider exercises short writes, revoked access, unavailable destination, disk-full and cancellation with correct typed outcomes and no false success.
- Successful exports preserve exact expected bytes/hash and cannot switch source project after a picker callback or input revision change.
- Failure cleanup affects only newly-created owned documents; unsupported provider atomicity remains explicitly represented to the user.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; SAF document-provider fault matrix.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 006](#agent-006); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 021](#agent-021); [Agent 023](#agent-023); [Agent 067](#agent-067).

**Interface handoff:** Expose DestinationTransferService(VerifiedExportSource, BoundDestinationRequest) to 067 and 025; 001 binds activity results. 067 owns format selection/orchestration and 023 verifies sources. Consume 088's single shared FaultDocumentProvider/process controller for transfer-level tests; do not implement a competing provider.

**Start immediately:** Implement a configurable failing DocumentsProvider and transfer state machine immediately; wire actual format outputs and picker requests before final acceptance.

## Agent 027

**Role:** Lens calibration and profile lifecycle workflow

**Branch:** `agents/027-lens-calibration-workflow`

**Priority:** P0

**Status receipt:** `docs/agents/status/027.md`

**Mission:** Make lens calibration a reproducible, inspectable, cancellable workflow with versioned compatible profiles and reliable publication.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/LensCalibration.kt](app/src/main/java/com/realityforge/scanner/engine/LensCalibration.kt):69-163 calibrates 10–40 checkerboard images but persists no per-view evidence or physical lens identity.
- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt): 119-160 invalidates before file replace/delete; profile mutation is not a durable operation and failure guidance is transient.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/engine/LensCalibration.kt`
- `app/src/test/java/com/realityforge/scanner/CalibrationTest.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/capture/calibration/`
- `docs/agents/status/027.md`
- `app/src/test/java/com/realityforge/scanner/agents/a027/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a027/`

**Implementation instructions:**

1. Preserve the existing offline checkerboard computation while adding per-view accepted/rejected observations, measurable coverage/quality reasons and source metadata compatibility.
2. Integrate 013 lens/zoom/crop identity and 031 LensGeometry contracts; separate unsupported calibration assumptions from genuine numeric convergence failures.
3. Version profile serialization/import validation, acquire/copy calibration inputs safely, and define profile save/remove transactions using 023 publication and expected project revision.
4. Expose actionable calibration session progress/results to 001/073/075; ensure OpenCV Mats, bitmaps and temporary inputs release on all failure/cancel paths.

**Success criteria:**

- Known-valid, low-diversity, mixed-lens/crop, malformed and incompatible-profile fixtures produce deterministic acceptance/rejection with concrete evidence.
- Interrupting calibration, profile save or removal preserves originals and cannot publish geometry under the wrong optical model.
- Physical same-lens calibration/capture results record reprojection errors and compatibility metadata, with emulator/synthetic evidence separately labelled.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Physical same-lens checkerboard/profile validation.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 012](#agent-012); [Agent 013](#agent-013); [Agent 021](#agent-021); [Agent 023](#agent-023); [Agent 031](#agent-031).

**Interface handoff:** Expose CalibrationSessionResult/ProfileStore ports; 001 manages operation identity, 073 renders workflow, 031 owns LensGeometry mathematics and 023 commits profile/input revisions.

**Start immediately:** Extend existing calibration fixtures with per-view rejection and resource-failure cases now; do not modify LensGeometry or controller files owned elsewhere.

## Agent 028

**Role:** Storage inspection and safe cleanup screen

**Branch:** `agents/028-storage-management-ui`

**Priority:** P1

**Status receipt:** `docs/agents/status/028.md`

**Mission:** Give users an accurate view of local scan storage and safe control over disposable derived data without risking originals.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt): 304-307 offers photo rows and project deletion but no cache/storage inspection or cleanup route.
- [app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt](app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt) has retention logic, yet the app cannot explain reusable depth space or safely clear derived caches.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/storage/`
- `docs/agents/status/028.md`
- `app/src/test/java/com/realityforge/scanner/agents/a028/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a028/`

**Implementation instructions:**

1. Build a lifecycle-aware screen showing measured originals, completed results, feature/depth caches, temporary files and available/reclaimable bytes separately.
2. Display dry-run cleanup plans and explain recomputation consequences before executing 020 cleanup; project/original deletion remains explicitly separate.
3. Integrate 021 budget failures with actionable cleanup/retry navigation and display ongoing cleanup progress, cancellation and recoverable errors.
4. Support per-project/global bounded inventories, accessible semantics and feature-prefixed strings; send only a route hook to 071/079 rather than editing their screens.

**Success criteria:**

- Screen totals exactly match controlled filesystem inventory fixtures, and pending measurement is shown distinctly from zero bytes.
- A real cache-clear/rerun flow preserves original/completed hashes, reports reclaimed bytes and recomputes only discarded derived work.
- Large text, rotation, TalkBack and a concurrently running scan cannot hide destructive scope or offer unsafe cleanup actions.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Integrated storage UI and accessibility gate.

**Integration inputs and cooperating owners:** [Agent 010](#agent-010); [Agent 020](#agent-020); [Agent 021](#agent-021); [Agent 022](#agent-022); [Agent 023](#agent-023); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Expose StorageScreen(state, onAction) and route contract to 071/079; consume 020 inventories/plans and 021 budget decisions. Own only ui/storage and its feature resources.

**Start immediately:** Build previews and interaction tests against measured-shaped inventory fixtures now; replace fixture providers with real services before completion.

## Agent 029

**Role:** Processing notification controls and permission experience

**Branch:** `agents/029-processing-notifications`

**Priority:** P0

**Status receipt:** `docs/agents/status/029.md`

**Mission:** Provide honest, actionable Android notifications for durable offline work without allowing stale actions to control another scan.

**Why this work remains:**

- app/src/main/java/com/realityforge/scanner/AndroidManifest.xml declares only camera/network-removal permissions and no processing service; current work stops in MainActivity.kt:19.
- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt):26,164-209 exposes in-app progress/cancel only; no notification content/action adapter exists.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/processing/notifications/`
- `docs/agents/status/029.md`
- `app/src/test/java/com/realityforge/scanner/agents/a029/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a029/`

**Implementation instructions:**

1. Define notification channels/content mapping for real queued/running/paused/failed/completed states, with meaningful stage progress and unknown-duration presentation.
2. Implement immutable project/job/operation-bound PendingIntent actions for pause/resume/cancel/open using foreground execution owner 008.
3. Provide notification permission/rationale/denial state and foreground visibility behavior consistent with current official Android requirements; request manifest/service hooks from 008.
4. Restore notification state from persisted jobs after restart, rate-limit updates, and avoid placing private image contents or detailed sensitive paths on the lock screen.

**Success criteria:**

- Notification action tests prove stale/cancelled-job intents cannot affect replacement jobs or another project, including process recreation.
- Denied/granted notification permission, background transition and foreground-service restart follow verified platform behavior with accurate app UI state.
- Actual running pipeline notifications match persisted stage progress and terminate/update correctly on success, failure, pause and cancellation.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Official Android foreground/notification requirement review; Physical background/notification action gate.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 007](#agent-007); [Agent 008](#agent-008); [Agent 010](#agent-010); [Agent 074](#agent-074); [Agent 078](#agent-078).

**Interface handoff:** Expose ProcessingNotificationController and typed JobNotificationAction to 008/001; 007 supplies durable state and 074 surfaces permission/recovery actions. 008 owns manifest changes.

**Start immediately:** Implement pure state-to-notification mapping, stable action identity and notification test fixtures now; complete actual foreground integration with 008.

## Agent 030

**Role:** Thermal and memory-aware Android workload scheduling

**Branch:** `agents/030-resource-thermal-scheduling`

**Priority:** P0

**Status receipt:** `docs/agents/status/030.md`

**Mission:** Adapt Android work safely to measured device resources without treating increased numeric limits as a reconstruction-quality improvement.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt](app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt):19-48 chooses compact/standard solely from max managed heap and fixed 256/384 depth limits.
- [app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt](app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt):26-31 chooses references by sequence spacing; scheduling lacks thermal/battery/storage feedback and physical evidence.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt`
- `app/src/test/java/com/realityforge/scanner/engine/DenseWorkloadTest.kt`
- `app/src/androidTest/java/com/realityforge/scanner/AdaptiveDensePipelineTest.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/processing/resources/`
- `docs/agents/status/030.md`
- `app/src/test/java/com/realityforge/scanner/agents/a030/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a030/`

**Implementation instructions:**

1. Preserve current conservative limits as baseline; expose versioned workload plans and explicit estimated managed/native/GPU/storage costs to scheduling consumers.
2. Observe supported thermal, memory-pressure, power and battery conditions locally, defining hysteresis and safe pause/degrade/resume boundaries.
3. Coordinate native view-selection owner 044 and engine orchestrator 002 through pure workload inputs; avoid duplicate camera-selection implementations or shared-file edits.
4. Integrate 021 storage reservations and 008 execution constraints; calibrate concurrency/resolution/profile thresholds from repeatable physical ARM64 resource measurements.

**Success criteria:**

- Deterministic policy tests cover thermal oscillation, low memory, insufficient storage and unavailable sensors without flapping or unsafe limit increases.
- Physical runs record peak managed/native memory, thermal state and stage timing for baseline/proposed plans while retaining cancellation responsiveness.
- Resource-driven pause/restart preserves completed checkpoints and cannot publish partial results; metadata records the actual chosen settings and identity.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Physical ARM64 sustained resource/thermal benchmark.

**Integration inputs and cooperating owners:** [Agent 002](#agent-002); [Agent 007](#agent-007); [Agent 008](#agent-008); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 021](#agent-021).

**Interface handoff:** Expose DeviceResourceSnapshot, WorkloadPlan and ResourcePolicyDecision; 044 provides view selection and 002 applies plans. Keep existing DenseWorkload compatibility until consumers migrate.

**Start immediately:** Implement pure policy/state tests and a local measurement adapter using baseline workloads now; final threshold changes require actual device evidence.

## Agent 031

**Role:** Lens geometry and pixel coordinate consistency

**Branch:** `agents/031-lens-coordinate-geometry`

**Priority:** P0

**Status receipt:** `docs/agents/status/031.md`

**Mission:** Establish one mathematically consistent coordinate convention from original EXIF-oriented pixels through sparse analysis, depth rectification and photographic projection. Correct the identified scaling discrepancy with independent numerical evidence, preserving valid calibration and arbitrary world scale.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/LensGeometry.kt](app/src/main/java/com/realityforge/scanner/engine/LensGeometry.kt):30-44 LensProfile.forScan scales principal points without pixel-center offset
- [app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt](app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt): 238-242 uses a different pixel-center scaling formula
- [app/src/main/java/com/realityforge/scanner/engine/LensGeometry.kt](app/src/main/java/com/realityforge/scanner/engine/LensGeometry.kt):67-97 normalized distortion inversion

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/engine/LensGeometry.kt`
- `app/src/test/java/com/realityforge/scanner/engine/LensGeometryTest.kt`
- `tests/test_camera_calibration.py`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/geometry/PixelTransform.kt`
- `app/src/test/java/com/realityforge/scanner/engine/geometry/PixelTransformTest.kt`

**Implementation instructions:**

1. Document pixel centers, camera axes, crop, orientation and resize composition in an executable PixelTransform API; compare current formulas before changing outputs.
2. Implement principal-point and focal transformations for unequal dimensions, cropping and all eight EXIF orientations; expose inverse mappings to image and pipeline owners.
3. Validate Brown–Conrady inversion over the actual image domain, reject noninvertible profiles, and retain measured versus estimated intrinsics distinctions.
4. Provide minimal replacement call-site snippets to owners 002 and 045; coordinate profile provenance/migration with the calibration owner without editing its files.

**Success criteria:**

- Independent analytic and finite-difference projection tests cover asymmetric intrinsics, radial/tangential distortion and down/upscaling; the pipeline mappings meet a documented subpixel tolerance.
- Known image corners and feature rays round-trip through orientation/crop/resize without systematic half-pixel drift; malformed profiles fail explicitly.
- Existing lens tests and actual Android rectification/projection fixtures pass; calibration changes invalidate affected stages through owner 009.

**Required verification:** ANDROID (JVM unit tests); ANDROID; GEOMETRY.

**Integration inputs and cooperating owners:** No new provider prerequisite; start against the audited baseline and shared contracts.

**Interface handoff:** Publish immutable PixelTransform and LensProjection contracts to 002, the calibration owner, 042 and 045. Coordinate changes through one projection contract; never introduce private competing formulas.

**Start immediately:** Derive exact invariants from current LensProjection, add owned analytic fixtures, and reproduce the cross-resolution discrepancy against baseline before requesting any integration.

## Agent 032

**Role:** Bounded feature extraction

**Branch:** `agents/032-feature-extraction`

**Priority:** P1

**Status receipt:** `docs/agents/status/032.md`

**Mission:** Extract and improve the existing SIFT implementation as a resource-bounded service suitable for larger photo collections. Preserve current exact descriptor semantics as the comparison baseline and justify every quality or feature-retention change with measured evidence.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt): 449-470 loadFrame embeds SIFT and Mat ownership
- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt): 454 SIFT.create(3500) is fixed
- [app/src/main/java/com/realityforge/scanner/engine/ImageLoader.kt](app/src/main/java/com/realityforge/scanner/engine/ImageLoader.kt):13 fixed 1600-pixel analysis edge

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/features/FeatureExtractor.kt`
- `app/src/main/java/com/realityforge/scanner/engine/features/FeatureFrame.kt`
- `app/src/androidTest/java/com/realityforge/scanner/engine/features/FeatureExtractorTest.kt`

**Implementation instructions:**

1. Implement typed keypoint, descriptor and extractor-setting contracts with explicit ownership and close semantics for OpenCV Mats.
2. Move extraction behavior into the owned module, keeping one decoded image active and cancellation checks before and after expensive native calls.
3. Evaluate feature spatial distribution, weak-texture retention and per-profile limits on real authorized photographs; avoid adding alternate detectors without demonstrated improvement.
4. Deliver an integration adapter to 002 and settings identity to 009/033; remove duplicated extraction only through the SparsePipeline owner.

**Success criteria:**

- Baseline and new extractor comparisons report repeatability, verified-match recall and feature counts across rotated, scaled, repeated and low-texture scenes without hiding failures.
- Repeated extraction, cancellation and corrupt-image runs release every Bitmap/Mat and remain inside measured working-set bounds; collection size does not multiply decoded image memory.
- Actual Android OpenCV instrumentation and compile/lint gates pass, with unchanged valid baseline SfM results after the coordinator wires the service.

**Required verification:** ANDROID (JVM unit tests); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 031](#agent-031); [Agent 045](#agent-045).

**Interface handoff:** FeatureFrame supplies immutable feature identities, coordinates and managed descriptor ownership to 033/035. Expose settings and decoder-transform identity; callers must not cache raw native pointers or entire photo collections.

**Start immediately:** Build the service around current SIFT.create(3500), test against identical baseline photographs in a dedicated Android fixture, and document exact integration signatures for 002.

## Agent 033

**Role:** Feature cache integrity and eviction

**Branch:** `agents/033-feature-cache-integrity`

**Priority:** P0

**Status receipt:** `docs/agents/status/033.md`

**Mission:** Make cached features trustworthy, bounded and safely reusable without reimplementing reconstruction. Replace acceptance of arbitrary finite corrupted payloads with strict versioned identity and integrity validation, while preserving automatic recomputation and originals.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt): 449-451 cache keyed by photo SHA and sift1600-v1
- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt): 472-501 binary feature cache lacks payload checksum and full extractor settings identity

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/features/FeatureCache.kt`
- `app/src/main/java/com/realityforge/scanner/engine/features/FeatureCacheFormat.kt`
- `app/src/test/java/com/realityforge/scanner/engine/features/FeatureCacheTest.kt`

**Implementation instructions:**

1. Define a bounded binary format including schema, original content hash, decoder transform, extractor/options/OpenCV version, keypoint/descriptor shape and checksum.
2. Validate coordinate ranges, finite descriptor values, exact lengths and allocation bounds before constructing arrays or Mats; reject stale or malformed records.
3. Implement atomic temporary writes, interruption cleanup, byte-based eviction and cache-miss behavior that never deletes original photographs or completed models.
4. Supply migration/recompute and call-site handoff to 002; share identity fields with 009 and FeatureFrame layout with 032 rather than duplicating them.

**Success criteria:**

- Tests reject truncation, appended bytes, settings/version mismatches, coordinate corruption and finite floating-point bit flips; every rejection causes safe recomputation.
- Unchanged input cache hits reproduce descriptors and identities exactly; interrupted writes and concurrent readers never accept partial data.
- Storage exhaustion, eviction and large collections obey configured byte budgets, preserve originals and do not convert cache failure into fabricated scan success.

**Required verification:** ANDROID (JVM unit tests); ANDROID; INTEGRATION.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 032](#agent-032).

**Interface handoff:** Provide FeatureCache.load/store/invalidate with explicit miss reasons and ownership rules; 002 is the sole owner of removing old readFrame/writeFrame. The global identity owner approves shared stage-version fields.

**Start immediately:** Implement the format and corruption tests with pure synthetic feature records immediately; compare records with the baseline layout and provide a converter that safely recomputes unsupported versions.

## Agent 034

**Role:** Scalable overlap candidate graph

**Branch:** `agents/034-overlap-candidate-graph`

**Priority:** P1

**Status receipt:** `docs/agents/status/034.md`

**Mission:** Replace order-sensitive pair enumeration and quadratic collection growth with an evidence-based bounded graph of plausible neighbors. Candidate scores must remain distinct from geometrically verified overlap and actual reconstructed coverage.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt):83-86 initial pairs are order-local and capped at 24
- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt): 245-249 all registered pairs capped at 66
- [app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt](app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt): 114-121 rebuilds all registered pairs

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/matching/OverlapGraph.kt`
- `app/src/main/java/com/realityforge/scanner/engine/matching/PairCandidate.kt`
- `app/src/test/java/com/realityforge/scanner/engine/matching/OverlapGraphTest.kt`

**Implementation instructions:**

1. Build candidate retrieval from existing image/feature summaries, retaining source photo identities and bounded per-image neighbor lists.
2. Rank useful baseline/diversity candidates and report disconnected or weakly connected components; expose revisited-view candidates for loop closure.
3. Evaluate recall against exhaustive verified matching on small real datasets and measure candidate count, peak memory and runtime on 30/80/250-photo manifests.
4. Publish a baseline-compatible graph API to 002/035/039/043; do not change matching thresholds or registration code in this branch.

**Success criteria:**

- Shuffling input photo order does not arbitrarily lose the verified object sequence; duplicate views and disconnected scenes remain explicitly identified.
- At equal accuracy gates, candidate recall and compute reduction versus exhaustive matching are reported per dataset, with a declared minimum supported recall.
- Tests enforce bounded edges/storage and deterministic identities on large collections; no candidate edge is surfaced as measured coverage before geometric verification.

**Required verification:** ANDROID (JVM unit tests); GEOMETRY; PERFORMANCE; INTEGRATION.

**Integration inputs and cooperating owners:** [Agent 032](#agent-032).

**Interface handoff:** Expose candidate photo pairs, confidence provenance and graph diagnostics; 035 verifies them, 039 registers connected views and 043 investigates loops. The coordinator controls when each graph stage is persisted.

**Start immediately:** Implement deterministic graph operations using baseline feature summaries and manifest fixtures; compare with the existing gap-based candidate list without waiting for new matching or registration code.

## Agent 035

**Role:** Descriptor matching and verified correspondence service

**Branch:** `agents/035-verified-feature-matching`

**Priority:** P1

**Status receipt:** `docs/agents/status/035.md`

**Mission:** Produce efficient, identity-safe correspondences and an explicit geometric verification handoff while retaining the exact reciprocal SIFT matcher as a reference. Improved scale must preserve false-match rejection on repeated texture and difficult overlap.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt): 504-529 reciprocal BF ratio matching embedded in pipeline
- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt): 509-512 two full k-nearest-neighbor passes
- [app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt](app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt):8-14 FeaturePairs requires one-to-one identities

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/matching/FeatureMatcher.kt`
- `app/src/main/java/com/realityforge/scanner/engine/matching/VerifiedMatches.kt`
- `app/src/androidTest/java/com/realityforge/scanner/engine/matching/FeatureMatcherTest.kt`

**Implementation instructions:**

1. Extract descriptor validation, reciprocal nearest neighbors, ratio settings and confidence/provenance into a bounded service with explicit Mat lifetime.
2. Process candidate pairs in cancellable blocks; evaluate approximate matching only against measured exact-reference recall and geometric success.
3. Represent tentative and verified matches separately, pass normalized observations to native verification, and preserve one-to-one keypoint identities through filtering.
4. Coordinate new native calls through 003 and pipeline removal through 002; send relevant settings to 009/033 and verified edges to 036.

**Success criteria:**

- Exact-reference regression covers empty/invalid descriptors, repeated patterns, illumination/scale changes and duplicate candidates; one-to-one identities always remain valid.
- Benchmarks show bounded memory and matching recall/false-positive tradeoffs on authorized real collections without silently relaxing geometric thresholds.
- Actual Android OpenCV/JNI tests pass and cancellation releases match buffers; integrated sparse initialization and registration retain current accepted quality.

**Required verification:** ANDROID (JVM unit tests); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 031](#agent-031); [Agent 032](#agent-032); [Agent 037](#agent-037).

**Interface handoff:** FeatureMatcher consumes FeatureFrame and PairCandidate; outputs typed tentative and geometrically verified correspondence sets. 036 may add only appropriately verified identities; normalization is delegated to 031.

**Start immediately:** Wrap the current exact BF matching behavior in the owned service and construct adversarial matching fixtures; use baseline NativeCore.reconstruct for initial geometric verification comparisons.

## Agent 036

**Role:** Stable scalable multi-view track graph

**Branch:** `agents/036-stable-feature-tracks`

**Priority:** P1

**Status receipt:** `docs/agents/status/036.md`

**Mission:** Preserve the existing conflict-aware graph while making track identity durable across incremental growth, interruption and larger camera sets. Avoid geometry corruption caused by recycling snapshot indices as persistent landmark identities.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt](app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt):34 limits graph to 12 views
- [app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt](app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt):84-104 rejects conflicting same-photo tracks
- [app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt](app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt): 109-138 uses sorted snapshot ordinal track IDs

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt`
- `app/src/test/java/com/realityforge/scanner/engine/MultiViewTracksTest.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/tracks/TrackSnapshot.kt`
- `app/src/test/java/com/realityforge/scanner/engine/tracks/TrackSnapshotTest.kt`

**Implementation instructions:**

1. Define stable source-observation and track identities with versioned snapshot serialization compatible with owner 009.
2. Retain epipolar constraints and same-photo conflict rejection, validate every normalized observation and pose, and record meaningful rejected-edge reasons.
3. Bound graph/packing memory, emit complete tracks in batches and avoid truncating a track midway when native observation budgets are reached.
4. Expose incremental snapshots to 039/040/041; coordinate larger camera limits with windowed solver consumers instead of lifting constants independently.

**Success criteria:**

- Insertion-order permutations and resume/rebuild cycles preserve deterministic stable track membership and correctly map native point results to observations.
- Conflicting keypoints, invalid normalized rays and disjoint graphs fail safely; current graph tests and new long visibility-chain tests pass.
- A 250-photo graph workload obeys declared memory and batch bounds, and integrated reconstruction shows no identity swaps, duplicate cameras or lost observation provenance.

**Required verification:** ANDROID (JVM unit tests); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 031](#agent-031); [Agent 035](#agent-035).

**Interface handoff:** Publish TrackSnapshot with immutable IDs and explicit local-to-global batch mappings. Native kernels may use compact local indices only with the supplied mapping; persistence must not treat array positions as permanent identities.

**Start immediately:** Extend baseline graph tests with stable snapshot/permutation cases and design the additive snapshot API; retain existing packedRows compatibility until consumers are integrated.

## Agent 037

**Role:** Two-view initializer and degeneracy diagnostics

**Branch:** `agents/037-two-view-initializer`

**Priority:** P1

**Status receipt:** `docs/agents/status/037.md`

**Mission:** Improve the mathematical robustness and diagnostic value of existing two-view SfM without replacing valid measured geometry with plausible shapes. Initializer quality must be measured separately from later dense reconstruction and photometric output.

**Why this work remains:**

- [reconstruction/src/core.cpp](reconstruction/src/core.cpp):79-92 normalized eight-point essential fit and sample degeneracy check
- [reconstruction/src/core.cpp](reconstruction/src/core.cpp): 132-159 RANSAC/pose selection has no operation checkpoints
- [reconstruction/src/core.cpp](reconstruction/src/core.cpp): 174-189 legacy sparse point writers require export-owner handoff

**Exclusive existing files:**

- `reconstruction/src/core.cpp`
- `reconstruction/include/realityforge/core.hpp`
- `reconstruction/tests/core_test.cpp`

**Reserved new files/packages:**

- `reconstruction/tests/initializer_degeneracy_test.cpp`

**Implementation instructions:**

1. Audit conditioning, essential decomposition, consensus refits, parallax and cheirality; add precise failure categories for planar, collinear, repeated-texture and rotation-only configurations.
2. Improve hypothesis selection and noise handling against the existing initializer, using independent truth and per-scene support/error evidence.
3. Add operation checkpoints through 051 and implement checked finite, magnitude and loop-budget constraints in the owned kernel; preserve public ABI through 003.
4. Coordinate removal/migration of legacy GLB/PLY writer bodies with 061/064. Preserve representation semantics until canonical exporters are integrated.
5. Apply 003's no-throw C ABI contract to exported rf_* functions in your owned source, including error serialization/allocation failures. Keep rf_free/caller ownership defined and add a direct C ABI failure regression; a safe JNI wrapper alone is insufficient.

**Success criteria:**

- Independent known-pose/outlier/noise fixtures and existing real stereo regressions satisfy declared rotation, translation-direction and reprojection tolerances.
- Planar/pure-rotation and weak-baseline cases produce explicit honest outcomes rather than unstable depth; valid nondegenerate scenes do not regress.
- Sanitizer, extreme-input and cancellation tests pass, and actual Android JNI exercises the updated kernel with no process abort or stale output.

**Required verification:** HOST (CMake/CTest); HOST (pytest); ANDROID; GEOMETRY.

**Integration inputs and cooperating owners:** [Agent 051](#agent-051).

**Interface handoff:** Keep existing Match/Settings/Result compatibility or publish versioned adapters. Return measured pose and support diagnostics for 002/039; require export owners to preserve source representation and coordinate conventions.

**Start immediately:** Create numerical degeneracy/noise fixtures around baseline rf::reconstruct and compare hypotheses; implement owned kernel fixes with existing structs before any pipeline changes.

## Agent 038

**Role:** Robust PnP camera resection

**Branch:** `agents/038-robust-camera-resection`

**Priority:** P1

**Status receipt:** `docs/agents/status/038.md`

**Mission:** Make additional-camera registration numerically reliable on supported object scenes, including difficult near-planar views, while retaining strict reprojection/cheirality verification. Better registration must not simply accept more incorrect poses.

**Why this work remains:**

- [reconstruction/src/pnp.cpp](reconstruction/src/pnp.cpp):55-75 eight-point DLT rejects degenerate and planar geometry
- [reconstruction/src/pnp.cpp](reconstruction/src/pnp.cpp): 121-145 pose refinement lacks explicit monotonic objective acceptance
- [reconstruction/src/pnp.cpp](reconstruction/src/pnp.cpp): 157-193 fixed sampling loop and consensus gates

**Exclusive existing files:**

- `reconstruction/src/pnp.cpp`
- `reconstruction/include/realityforge/pnp.hpp`

**Reserved new files/packages:**

- `reconstruction/tests/pnp_robustness_test.cpp`

**Implementation instructions:**

1. Evaluate the current normalized DLT/Gram–Schmidt initializer against independently known noisy, outlier and near-planar configurations; improve the pose hypothesis method when justified.
2. Add checked monotonic refinement, conditioning and consensus diagnostics; preserve bounded robust iteration policy and unique inlier indices.
3. Use 051 cancellation and checked local numeric guards; deliver versioned native outputs to bridge owner 003 and registrar 039.
4. Provide honest unsupported handling for truly ambiguous configurations and measure planar support rather than relying on a generic non-flat capture instruction.
5. Apply 003's no-throw C ABI contract to exported rf_* functions in your owned source, including error serialization/allocation failures. Keep rf_free/caller ownership defined and add a direct C ABI failure regression; a safe JNI wrapper alone is insufficient.

**Success criteria:**

- Known-pose tests independently verify rotation, translation and reprojection error over multiple camera scales, noise levels and outlier ratios.
- Repeated texture, low parallax and degenerate correspondences are rejected or explicitly marked ambiguous; no result reports a nonrigid rotation or invalid inliers.
- Host sanitizer/cancellation tests and actual JNI visibility-chain instrumentation pass without reducing existing accepted-scene consensus or accuracy.

**Required verification:** HOST (CMake/CTest); HOST (pytest); ANDROID; GEOMETRY.

**Integration inputs and cooperating owners:** [Agent 051](#agent-051).

**Interface handoff:** Supply CameraPose with validated inlier identity and explicit failure/conditioning metadata; 039 independently verifies returned observations. Do not mutate track IDs or choose which photographs enter the project.

**Start immediately:** Add a dedicated PnP test target around the unchanged API and record current behavior on noisy near-planar and low-support fixtures before modifying the solver.

## Agent 039

**Role:** Incremental registration and restartable frontier

**Branch:** `agents/039-incremental-registration`

**Priority:** P1

**Status receipt:** `docs/agents/status/039.md`

**Mission:** Extend the implemented visibility-chain registrar to larger, reorderable and resumable photo sets. Preserve its two-view-supported landmarks, independent pose verification and explicit remaining-photo reporting rather than recreating anchor-only registration.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt](app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt):97-178 implements frontier growth but caps 12 templates
- [app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt](app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt): 146-169 accepts first successful pending photo
- [app/src/main/java/com/realityforge/scanner/engine/NativeFrontierGeometry.kt](app/src/main/java/com/realityforge/scanner/engine/NativeFrontierGeometry.kt) native registration adapter

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt`
- `app/src/main/java/com/realityforge/scanner/engine/NativeFrontierGeometry.kt`
- `app/src/test/java/com/realityforge/scanner/engine/IncrementalRegistrationTest.kt`
- `app/src/androidTest/java/com/realityforge/scanner/IncrementalNativeRegistrationTest.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/registration/RegistrationFrontier.kt`

**Implementation instructions:**

1. Rank pending views by verified stable landmark support and coverage value, not import order; retry failures only when new supporting geometry becomes available.
2. Consume persistent 036 track identities and expose a bounded serializable frontier with camera/observation provenance and meaningful failure reasons.
3. Use local retriangulation/refinement windows, explicit native cancellation and adaptive candidate batches; retain a correct shared arbitrary-scale frame.
4. Provide stage-resume and final integration handoffs to 002/009, consuming 034 neighbors and 043 closure suggestions without writing job/database internals.

**Success criteria:**

- Existing production-JNI visibility-chain test remains green; shuffled circular visibility sequences recover the expected supported cameras within independent pose tolerances.
- Interrupted/resumed growth matches clean growth, rejects duplicate photo identities and preserves unregistered reasons; disconnected scenes never merge through guessed poses.
- 30/80/250-photo scheduling fixtures and supported real datasets obey resource budgets with no silent 12-photo truncation or unbounded all-pairs matching.

**Required verification:** ANDROID (JVM unit tests); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 034](#agent-034); [Agent 036](#agent-036); [Agent 038](#agent-038); [Agent 040](#agent-040); [Agent 041](#agent-041); [Agent 043](#agent-043); [Agent 051](#agent-051).

**Interface handoff:** Publish RegistrationFrontier and progress/failure events to the pipeline/job owners. Compact native camera indices must carry stable photo and track mappings; only the coordinator publishes completed revisions.

**Start immediately:** Extend the current deterministic visibility-chain JVM fixtures with order permutations and frontier round-trip serialization while using the unchanged baseline native adapters.

## Agent 040

**Role:** Multi-view triangulation and sparse geometry refinement

**Branch:** `agents/040-multiview-triangulation`

**Priority:** P1

**Status receipt:** `docs/agents/status/040.md`

**Mission:** Improve accepted sparse landmarks and their provenance under larger camera graphs without hiding poor conditioning behind point counts. Preserve positive-depth, multi-view support and bounded observation contracts as the foundation for dense priors.

**Why this work remains:**

- [reconstruction/src/multiview.cpp](reconstruction/src/multiview.cpp): 158-177 limits views/observations and groups track data
- [reconstruction/src/multiview.cpp](reconstruction/src/multiview.cpp): 181-239 triangulates and refines accepted observations
- [reconstruction/include/realityforge/multiview.hpp](reconstruction/include/realityforge/multiview.hpp) normalized observation and pose contracts

**Exclusive existing files:**

- `reconstruction/src/multiview.cpp`
- `reconstruction/include/realityforge/multiview.hpp`
- `reconstruction/tests/multiview_test.cpp`
- `tests/test_multiview.py`

**Reserved new files/packages:**

- `reconstruction/tests/triangulation_conditioning_test.cpp`

**Implementation instructions:**

1. Audit triangulation hypothesis selection, observation rejection and refinement against independent geometry, including noisy, weak-baseline and heterogeneous-visibility cases.
2. Expose conditioning and geometric uncertainty metadata with stable 036 track IDs and explicit local/global batch mappings.
3. Support bounded camera windows and observation batches consumed by 039/041; integrate 051 cancellation and checked local numeric budgets.
4. Keep outlier removal conservative and report which observations support each point; pass actual support to 044/047/054 rather than using unseen scene truth.
5. Apply 003's no-throw C ABI contract to exported rf_* functions in your owned source, including error serialization/allocation failures. Keep rf_free/caller ownership defined and add a direct C ABI failure regression; a safe JNI wrapper alone is insufficient.

**Success criteria:**

- Independent projection/triangulation fixtures verify cheirality, reprojection, parallax and retained track identity across coordinate scales and camera permutations.
- Existing native/real regressions retain accepted quality; new low-parallax and outlier tests demonstrate improved or explicitly rejected geometry with uncertainty consistent with error.
- Windowed large-graph and cancellation tests stay within resource bounds, produce deterministic supported landmarks and execute successfully through actual Android JNI.

**Required verification:** HOST (CMake/CTest); HOST (pytest); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 036](#agent-036); [Agent 051](#agent-051).

**Interface handoff:** Maintain versioned MultiViewResult/observation contracts with confidence, support and batch mappings. 041 owns joint optimization; this agent must not duplicate its solver or alter shared bridge files.

**Start immediately:** Construct analytic noisy/degenerate multi-camera fixtures using baseline ViewPose and TrackObservation, add independent error measurements, and implement owned triangulation improvements against existing APIs.

## Agent 041

**Role:** Scalable robust bundle adjustment and gauge

**Branch:** `agents/041-bundle-adjustment`

**Priority:** P1

**Status receipt:** `docs/agents/status/041.md`

**Mission:** Extend the implemented coupled robust optimizer to correct real calibration/anchor drift and larger supported photo sets. Preserve monotonic objective checks and honest nonconvergence instead of treating a high point count or a solver return as sufficient success.

**Why this work remains:**

- [reconstruction/src/bundle_adjustment.cpp](reconstruction/src/bundle_adjustment.cpp): 107-118 fixes both complete anchor poses and caps 12 cameras
- [reconstruction/src/bundle_adjustment.cpp](reconstruction/src/bundle_adjustment.cpp): 157-165 allocates per-point blocks against all free cameras
- [reconstruction/src/bundle_adjustment.cpp](reconstruction/src/bundle_adjustment.cpp): 272-328 robust fixed-intrinsics LM/Schur solver exists

**Exclusive existing files:**

- `reconstruction/src/bundle_adjustment.cpp`
- `reconstruction/include/realityforge/bundle_adjustment.hpp`
- `reconstruction/tests/bundle_adjustment_test.cpp`
- `tests/test_bundle_adjustment.py`

**Reserved new files/packages:**

- `reconstruction/tests/bundle_gauge_scaling_test.cpp`

**Implementation instructions:**

1. Implement and numerically validate minimal gauge constraints that allow correction of the second anchor pose without releasing global scale/orientation ambiguity.
2. Use observation sparsity, bounded local/global refinement and checked working-set estimates rather than raising camera constants in the dense normal equations.
3. Integrate 042 intrinsic groups/priors and 043 verified closure constraints; expose convergence, conditioning, rejected observations and per-camera residual evidence.
4. Add sparse-operation cancellation through 051, keep stable 036 track mappings and coordinate any approved numerical-library adoption with build/license owners.
5. Apply 003's no-throw C ABI contract to exported rf_* functions in your owned source, including error serialization/allocation failures. Keep rf_free/caller ownership defined and add a direct C ABI failure regression; a safe JNI wrapper alone is insufficient.

**Success criteria:**

- Independent noisy fixtures perturb both anchors and verify recovered camera/point accuracy, gauge invariance and analytic Jacobians against finite differences.
- Robust cost never silently increases, low-information intrinsic groups stay constrained, and existing native/real geometry gates show no accuracy regression.
- 30/80/250-camera sparse workloads demonstrate bounded resource behavior and useful convergence or explicit supported-limit failure; actual JNI cancellation/registration tests pass.

**Required verification:** HOST (CMake/CTest); HOST (pytest); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 036](#agent-036); [Agent 040](#agent-040); [Agent 042](#agent-042); [Agent 043](#agent-043); [Agent 051](#agent-051).

**Interface handoff:** Expose an additive versioned BundleProblem/BundleResult preserving stable IDs and calibrated-versus-refined intrinsics. 003 owns JNI, 002 schedules/publishes and 039 supplies windows; optimizer correctness remains solely owned here.

**Start immediately:** Extend the existing bundle tests with anchor perturbation, gauge and Jacobian fixtures against the baseline solver, then implement an independently testable minimal-gauge path.

## Agent 042

**Role:** Bounded intrinsic refinement model

**Branch:** `agents/042-intrinsic-refinement`

**Priority:** P1

**Status receipt:** `docs/agents/status/042.md`

**Mission:** Provide a mathematically tested camera projection and intrinsic-parameter block that the existing optimizer can consume. Support unknown or imperfect photo intrinsics while protecting trustworthy calibration and rejecting unobservable parameter estimation.

**Why this work remains:**

- [reconstruction/include/realityforge/bundle_adjustment.hpp](reconstruction/include/realityforge/bundle_adjustment.hpp):10 describes already-calibrated inputs
- [reconstruction/src/bundle_adjustment.cpp](reconstruction/src/bundle_adjustment.cpp): 163-165 observes normalized image rays with fixed intrinsics
- [app/src/main/java/com/realityforge/scanner/engine/LensGeometry.kt](app/src/main/java/com/realityforge/scanner/engine/LensGeometry.kt):12-43 only fixed profile projections exist

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/intrinsics.hpp`
- `reconstruction/src/intrinsics.cpp`
- `reconstruction/tests/intrinsics_test.cpp`

**Implementation instructions:**

1. Define typed focal, principal-point and supported Brown–Conrady parameters, explicit fixed/refinable flags and groups tied to actual lens/crop provenance.
2. Implement projection/unprojection and analytic derivatives with correct pixel-center conventions from 031, finite bounds and physically meaningful priors.
3. Add observability/conditioning checks so weak or planar captures retain constrained parameters rather than producing arbitrary distortion.
4. Deliver residual/Jacobian and parameter-layout interfaces to 041 and serialization fields to 009/003; do not create a second optimizer or alter LensCalibration ownership.

**Success criteria:**

- Analytic derivatives match independent finite differences over realistic focal/distortion values, asymmetric images and difficult image edges.
- Synthetic diverse-view problems recover independently known intrinsics within specified tolerance, while fixed measured profiles remain unchanged and unobservable scenes are clearly diagnosed.
- Native sanitizer/numeric tests pass and integrated 041 runs improve or preserve pose/surface error without overfitting calibration training observations.

**Required verification:** HOST (CMake/CTest); GEOMETRY; INTEGRATION.

**Integration inputs and cooperating owners:** [Agent 031](#agent-031).

**Interface handoff:** Supply immutable camera-model metadata plus optimization parameter blocks, priors and derivatives. 041 owns solving; 031 owns Android coordinate math; 009 owns persisted stage-version identity.

**Start immediately:** Implement projection/Jacobian tests against baseline LensProjection equations and independent numerical formulas, exposing additive C++ types without waiting for optimizer integration.

## Agent 043

**Role:** Verified loop closure and global drift constraints

**Branch:** `agents/043-loop-closure-drift`

**Priority:** P1

**Status receipt:** `docs/agents/status/043.md`

**Mission:** Detect real revisited object views and produce robust global consistency constraints that reduce accumulated drift. Descriptor similarity alone must never merge distinct surfaces, duplicate objects or disconnected scan components.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt](app/src/main/java/com/realityforge/scanner/engine/IncrementalRegistration.kt): 110-175 adds views without explicit loop-closure state
- [app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt](app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt): 245-270 final all-pairs tracks are not a dedicated loop solver
- [docs/NEXT_MILESTONE.md](docs/NEXT_MILESTONE.md) complete-object registration and intrinsic/drift correction remain open

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/registration/LoopClosure.kt`
- `reconstruction/include/realityforge/loop_closure.hpp`
- `reconstruction/src/loop_closure.cpp`
- `reconstruction/tests/loop_closure_test.cpp`
- `app/src/test/java/com/realityforge/scanner/engine/registration/LoopClosureTest.kt`

**Implementation instructions:**

1. Use 034 candidate graph and 036 stable observations to propose revisited-view constraints with explicit source evidence.
2. Geometrically verify loops using reprojection, parallax, common landmarks and robust relative-pose consistency; reject repeated-texture and disconnected-component traps.
3. Quantify pre/post closure drift, construct bounded constraints for 041, and preserve the project's arbitrary scale and immutable photo identities.
4. Expose closure decisions/progress to 039/002 and persistable evidence to 009 without editing optimizer, JNI or pipeline hotspots.

**Success criteria:**

- Independent circular camera trajectories with drift show reduced withheld pose/surface error after integrated refinement, not merely reduced training residual.
- Repeated textures, symmetric objects, wrong-photo matches and disconnected scenes reject false loops with deterministic diagnostic reasons.
- Existing non-loop reconstructions retain quality, closure memory/compute is bounded, and cancelled or failed corrections leave the last valid geometry unchanged.

**Required verification:** ANDROID (JVM unit tests); HOST (CMake/CTest); GEOMETRY; INTEGRATION.

**Integration inputs and cooperating owners:** [Agent 034](#agent-034); [Agent 036](#agent-036); [Agent 040](#agent-040).

**Interface handoff:** Provide verified LoopConstraint records and drift metrics; 041 consumes constraints, 039 schedules revisits and 002 chooses accepted output. A rejected loop is valid evidence, never a reason to synthesize missing geometry.

**Start immediately:** Create analytic loop and false-loop fixtures using baseline registered cameras/tracks, implement proposal/verification independently, and hand the optimizer owner a concrete constraint schema.

## Agent 044

**Role:** Geometry-aware dense reference and source selection

**Branch:** `agents/044-dense-view-selection`

**Priority:** P1

**Status receipt:** `docs/agents/status/044.md`

**Mission:** Spend the dense reconstruction budget on cameras that provide useful observed coverage and stereo geometry. Replace index-based subsampling while preserving explicit limits, real source support and honest reporting of excluded photographs.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt](app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt):25-31 selects eight cameras by registration index
- [reconstruction/include/realityforge/dense_selection.hpp](reconstruction/include/realityforge/dense_selection.hpp):19-59 uses frustum/heading heuristics and at most three sources
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 152 source plan is reused by each reference

**Exclusive existing files:**

- `reconstruction/include/realityforge/dense_selection.hpp`
- `reconstruction/tests/dense_selection_test.cpp`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/dense/DenseViewSelection.kt`
- `app/src/test/java/com/realityforge/scanner/engine/dense/DenseViewSelectionTest.kt`

**Implementation instructions:**

1. Score reference views from verified tracks, camera diversity, view incidence and useful baseline; distinguish geometric candidate overlap from measured dense support.
2. Rank bounded source sets per reference, account for occlusion/texture availability and retain enough reciprocal connectivity for consistency checks.
3. Measure coverage and error against the current index/frustum baseline at equal sample and compute budgets, including permuted photo order.
4. Publish a pure selection plan to 002/030 and 046; do not edit DenseWorkload.kt or change global resolution thresholds in this branch.

**Success criteria:**

- Camera permutations produce equivalent supported selection and do not systematically lose rear or side views; disjoint or weak-overlap cameras remain explicitly excluded.
- Synthetic known-view and real authorized multi-view comparisons improve useful observable coverage at unchanged accuracy gates and resource budgets.
- All plans honor reference/source caps, stable photo IDs and cancellation decisions; existing dense source selection regressions remain valid or are explained by measured improvement.

**Required verification:** ANDROID (JVM unit tests); HOST (CMake/CTest); GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 036](#agent-036); [Agent 040](#agent-040).

**Interface handoff:** Return ordered reference photo IDs, source adjacency, support rationale and exclusion reasons; 030 sets resource caps and 046 executes the plan. Sparse registration order must not determine appearance coverage.

**Start immediately:** Implement selection fixtures using current ViewPose, track visibility and baseline denseSourcePlan; compare output against registration-index selection before any orchestrator integration.

## Agent 045

**Role:** Versioned image pyramids and exact rectification

**Branch:** `agents/045-image-pyramids-rectification`

**Priority:** P1

**Status receipt:** `docs/agents/status/045.md`

**Mission:** Provide exact, bounded, reusable image levels for sparse analysis, dense stereo and photographic baking. Reduce repeated decoding and make higher detail feasible without retaining full-resolution photo collections or weakening original preservation.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt](app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt):45-79 decodes and rectifies each depth photo
- [app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt](app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt): 212-250 repeats decode for photographic sources
- [app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt](app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt): 253-279 per-pixel Kotlin rectification

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/engine/imaging/PyramidStore.kt`
- `app/src/main/java/com/realityforge/scanner/engine/imaging/PyramidRectifier.kt`
- `app/src/main/java/com/realityforge/scanner/engine/imaging/PyramidLevel.kt`
- `app/src/androidTest/java/com/realityforge/scanner/engine/imaging/PyramidRectifierTest.kt`

**Implementation instructions:**

1. Build levels one image at a time, carrying exact original-to-level orientation/crop/pixel transforms and validity masks from 031.
2. Implement tiled/streamed rectification with explicit border sampling and cancellation, retaining the baseline CPU path as the numerical reference.
3. Version and checksum derived cache levels through 009, enforce disk and active-buffer budgets from 030, and recover safely from incomplete levels.
4. Supply owned module adapters to 002/032/046/057; coordinate exception-safe original decode with the ImageLoader owner instead of editing its file.

**Success criteria:**

- Independent asymmetric/checkerboard/color fixtures verify rectification and all EXIF/crop/scale mappings, including half-pixel and invalid-border cases.
- Cached versus uncached and tiled versus full reference results agree within documented interpolation tolerance; settings/calibration changes invalidate levels.
- Large image collections, cancellation, corrupt sources and disk exhaustion remain within measured Bitmap/native/cache budgets, preserve originals and never publish partial levels as completed scans.

**Required verification:** ANDROID (JVM unit tests); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 030](#agent-030); [Agent 031](#agent-031).

**Interface handoff:** PyramidLevel exposes bounded pixel access, immutable transform/calibration identity and validity masks. Consumers request levels/tiles with explicit lifetimes; 002 owns removal of embedded rectification and decoding loops.

**Start immediately:** Implement the level/transform contract and Android interpolation fixtures around baseline ImageLoader output, using an owned temporary cache and no changes to production orchestration.

## Agent 046

**Role:** Bounded tiled CPU depth core

**Branch:** `agents/046-tiled-cpu-depth-core`

**Priority:** P1

**Status receipt:** `docs/agents/status/046.md`

**Mission:** Turn the implemented CPU reference into a bounded tiled production execution path while preserving numerical correctness and observability. This remains the mandatory fallback; higher limits require measurements, and Vulkan work must not block a correct CPU profile.

**Why this work remains:**

- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 109-136 validates eight views and bounded image dimensions
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 140-283 plane-sweep solver retains all per-reference maps
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 271-273 voxel int64 casts lack representability guards
- [reconstruction/include/realityforge/dense.hpp](reconstruction/include/realityforge/dense.hpp):66-68 defines fronto-parallel CPU reference

**Exclusive existing files:**

- `reconstruction/src/dense.cpp`
- `reconstruction/include/realityforge/dense.hpp`
- `reconstruction/include/realityforge/stereo_warp.hpp`
- `reconstruction/tests/dense_test.cpp`
- `reconstruction/tests/stereo_warp_test.cpp`
- `tests/test_dense.py`
- `tests/test_dense_multicamera.py`
- `app/src/androidTest/java/com/realityforge/scanner/MultiCameraNativeTest.kt`

**Reserved new files/packages:**

- `reconstruction/src/depth_tiles.cpp`
- `reconstruction/include/realityforge/depth_tiles.hpp`
- `reconstruction/tests/depth_tiles_test.cpp`

**Implementation instructions:**

1. Introduce explicit reference/source tile working sets, halos, map storage and byte admission rather than holding all image/map buffers as resolution and photo counts grow.
2. Integrate 044 selection, 047 refinement, 048 costs and 049 consistency through narrow interfaces while retaining a reproducible reference mode.
3. Use 050 tile/reference checkpoints and 051 operation progress/cancellation; expose true work completion rather than a monolithic stage percentage. For the separate dense POINTS output, replace first-sample/camera-order retention with deterministic confidence-weighted or coverage-balanced fusion/downsampling that preserves observation provenance and thin surfaces.
4. Fix voxel-range conversions locally with checked representability and meaningful coordinate bounds; account for all sample, serialization and buffer allocations.
5. Apply 003's no-throw C ABI contract to exported rf_* functions in your owned source, including error serialization/allocation failures. Keep rf_free/caller ownership defined and add a direct C ABI failure regression; a safe JNI wrapper alone is insufficient.

**Success criteria:**

- Tiled versus untiled CPU outputs match specified numerical tolerances including halos, occluders and discontinuities; existing real stereo gates retain accuracy. At equal point budget, camera-order permutations must not materially alter retained quality or starve later views.
- Sanitizers including floating-cast overflow pass adversarial coordinate/dimension tests; cancellation, cache resume and storage exhaustion preserve valid prior results.
- Physical supported-device measurements demonstrate bounded peak RSS and acceptable sustained work for declared profiles; unavailable physical evidence is NOT_RUN and cannot justify raised defaults.

**Required verification:** HOST (CMake/CTest); HOST (pytest); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 044](#agent-044); [Agent 047](#agent-047); [Agent 048](#agent-048); [Agent 049](#agent-049); [Agent 050](#agent-050); [Agent 051](#agent-051).

**Interface handoff:** Expose versioned tile-capable DenseSettings/DenseResult and resource/progress records to 002/003/030/050/052. 053 consumes verified observations; direct native entrypoints retain strict checked input bounds.

**Start immediately:** Build tile/halo equivalence tests against current reconstructDense and fix the unguarded voxel conversions immediately; introduce additive execution adapters without waiting for new algorithms.

## Agent 047

**Role:** Coarse-to-fine depth and local refinement

**Branch:** `agents/047-depth-refinement`

**Priority:** P1

**Status receipt:** `docs/agents/status/047.md`

**Mission:** Recover useful detail and slanted geometry with robust per-reference/per-tile depth priors and coarse-to-fine refinement. Improve accuracy/completeness at measured cost rather than inflating triangle counts or fabricating missing depth.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt](app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt):59-84 computes one global percentile depth interval
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 145-149 uses a fixed global inverse-depth plane list
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 189-217 winner selection has only local parabolic interpolation

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/depth_refinement.hpp`
- `reconstruction/src/depth_refinement.cpp`
- `reconstruction/tests/depth_refinement_test.cpp`

**Implementation instructions:**

1. Derive depth intervals from actual visible sparse observations and their conditioning; use conservative fallback for weakly supported regions instead of clipping foreground/background by global percentiles.
2. Implement pyramid-based hypothesis propagation and local depth/normal refinement using 048 costs, preserving uncertainty and invalid pixels.
3. Bound candidate counts, iteration work and tile halos; expose deterministic checkpoint-compatible settings and progress to 046/050.
4. Compare against existing 56-plane sweep on tilted, curved, depth-layer and real photo fixtures with withheld truth that never enters solver inputs.

**Success criteria:**

- Per-scene surface/depth error and observable completeness improve or remain within declared tradeoffs against the CPU baseline at comparable resource budgets.
- Foreground/background layers, low-texture regions and weak sparse priors do not cause invented surfaces or silently clipped valid ranges; confidence reflects ambiguity.
- Pyramid/tile boundaries and resumed versus clean execution agree, runtime/memory bounds are enforced, and integrated native/Android paths preserve cancellation and current regression gates.

**Required verification:** HOST (CMake/CTest); HOST (pytest); GEOMETRY; PERFORMANCE; INTEGRATION.

**Integration inputs and cooperating owners:** [Agent 040](#agent-040); [Agent 044](#agent-044); [Agent 048](#agent-048); [Agent 054](#agent-054).

**Interface handoff:** Provide bounded DepthPrior and RefinementResult APIs on baseline views/maps; 046 owns orchestration and accepts refined output only through retained quality gates. 050/009 must include every refinement setting in identity.

**Start immediately:** Implement analytic slanted-plane and multi-depth fixtures, expose local prior/refinement routines against baseline DenseView structures, and quantify reference error before integrating.

## Agent 048

**Role:** Photometric matching costs and validity masks

**Branch:** `agents/048-photometric-matching-costs`

**Priority:** P1

**Status receipt:** `docs/agents/status/048.md`

**Mission:** Provide robust, efficient and independently tested image matching costs for the CPU depth pipeline. Preserve explicit invalid/ambiguous evidence and improve source aggregation without disguising unsupported pixels as reconstructed geometry.

**Why this work remains:**

- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp):55-84 fixed ZNCC reference patches and variance gate
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 189-196 takes the best single-source photometric cost
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 133-134 RGBA alpha represents strict pixel validity

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/matching_cost.hpp`
- `reconstruction/src/matching_cost.cpp`
- `reconstruction/tests/matching_cost_test.cpp`

**Implementation instructions:**

1. Extract a deterministic reference cost implementation with documented sampling, linearity, patch support and validity-mask semantics.
2. Evaluate robust multi-source aggregation and illumination-resistant costs on difficult real and analytical fixtures; retain exact ZNCC for comparison.
3. Handle saturation, border/rectification invalidity, weak texture and repeated patterns explicitly, with bounded scratch buffers and vectorization only after numerical equivalence.
4. Expose cost curves, usable-source counts and ambiguity measures to 047/049/046; do not alter depth acceptance or surface code in this branch.

**Success criteria:**

- Analytic photometric transforms, resampling, invalid masks, borders and saturated patches produce independently expected valid/invalid costs and finite bounds.
- Compared with baseline ZNCC, real fixture error/retention evidence justifies each adopted cost; no threshold-only coverage gain is reported as an algorithm improvement.
- Scratch allocation and operation counts are bounded, optimized and scalar paths agree, sanitizer tests pass and final Android CPU integration retains existing benchmark accuracy.

**Required verification:** HOST (CMake/CTest); HOST (pytest); GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** No new provider prerequisite; start against the audited baseline and shared contracts.

**Interface handoff:** MatchingCost consumes bounded image windows and exact camera warp hypotheses, returning cost plus support/ambiguity. 047 searches hypotheses and 049 verifies geometry; this module never declares a project complete.

**Start immediately:** Port baseline ZNCC into an owned pure helper and compare byte-identical fixture windows against independent calculations before adding any robust aggregation experiment.

## Agent 049

**Role:** Reciprocal confidence and occlusion consistency

**Branch:** `agents/049-depth-confidence-occlusion`

**Priority:** P1

**Status receipt:** `docs/agents/status/049.md`

**Mission:** Strengthen confidence and visibility decisions at depth discontinuities and thin surfaces while keeping the current reciprocity checks as a baseline. A nearby matching depth or a high photometric score alone must not imply true surface support.

**Why this work remains:**

- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp):87-93 retrieves the nearest sampled neighbor depth
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 242-263 uses relative depth and reverse reprojection confirmations
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 107-120 accepts depth agreement from any nearby map cell

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/depth_consistency.hpp`
- `reconstruction/src/depth_consistency.cpp`
- `reconstruction/tests/depth_consistency_test.cpp`

**Implementation instructions:**

1. Implement calibrated cross-view depth/normal/reprojection consistency with explicit occlusion, ambiguity and unknown classifications.
2. Distinguish photometric confidence from independent geometric support; retain source observation IDs and bounded support sets.
3. Evaluate spatial interpolation and discontinuity handling on slanted surfaces, foreground occluders, thin sheets and repeated texture with independently rendered truth.
4. Expose verified sample/rejection records to 046/053/057 and confidence evidence to 054/diagnostics owners; preserve invalid pixels instead of inpainting them.

**Success criteria:**

- Independent occlusion/visibility fixtures show correct support around thin edges and discontinuities, including cases where nearby-grid agreement would be misleading.
- Precision/recall and confidence-versus-error measurements meet declared criteria on synthetic and real datasets without sacrificing safety for completeness.
- Camera permutations, tile halos and resumed execution preserve decisions within tolerance; memory, support counts, numeric bounds and cancellation behavior remain bounded.

**Required verification:** HOST (CMake/CTest); HOST (pytest); GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 048](#agent-048); [Agent 054](#agent-054).

**Interface handoff:** Return geometric support, uncertainty and visibility classifications alongside verified sample provenance. 046 owns acceptance integration, 053 fusion and 057 texture visibility; do not duplicate their orchestration.

**Start immediately:** Construct foreground/background and thin-sheet fixtures with baseline DenseDepthMap inputs, compare current nearest-grid behavior, and implement the owned verifier independently.

## Agent 050

**Role:** Depth checkpoints and bounded durable cache

**Branch:** `agents/050-depth-checkpoint-durability`

**Priority:** P0

**Status receipt:** `docs/agents/status/050.md`

**Mission:** Preserve working per-reference depth resumption and extend it to reliable bounded tile/reference storage. Clearly distinguish interrupted-process recovery from power-loss durability, and never promote cached intermediates to a completed project result.

**Why this work remains:**

- [reconstruction/src/depth_checkpoint.cpp](reconstruction/src/depth_checkpoint.cpp):54-86 validates CRC/SHA-bound reference maps
- [reconstruction/src/depth_checkpoint.cpp](reconstruction/src/depth_checkpoint.cpp): 121-129 flushes and renames without file/directory fsync
- [app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt](app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt):35-51 retains two identity directories but no measured byte budget

**Exclusive existing files:**

- `reconstruction/src/depth_checkpoint.cpp`
- `reconstruction/include/realityforge/depth_checkpoint.hpp`
- `reconstruction/tests/depth_checkpoint_test.cpp`
- `app/src/androidTest/java/com/realityforge/scanner/DepthCheckpointDeviceTest.kt`

**Reserved new files/packages:**

- `reconstruction/include/realityforge/depth_cache_manifest.hpp`
- `reconstruction/src/depth_cache_manifest.cpp`
- `reconstruction/tests/depth_cache_manifest_test.cpp`

**Implementation instructions:**

1. Version reference/tile manifests and validate exact source, pose, calibration, solver and settings identities supplied by 009 before allocating or loading payloads.
2. Implement atomic durable writes where supported, unique temporary names, orphan cleanup, corruption fallback and byte-based eviction with resource policy from 030.
3. Record cached, recomputed, rejected and written work explicitly; interruption during a current tile must retain previously completed valid work.
4. Coordinate Kotlin identity/cache-directory behavior through owner 009 and Android lifecycle tests through job/device owners; do not edit their existing files.

**Success criteria:**

- Bit flips, truncation, duplicate/stale manifests, changed pixels/poses/settings/engine and malformed dimensions are rejected before reuse; clean resume matches recomputation.
- Actual process termination/relaunch instrumentation demonstrates retained completed work with no partial publication; untested power-loss guarantees are marked NOT_RUN.
- Disk exhaustion, eviction and concurrent access remain bounded and preserve originals/completed models; native sanitizer and cancellation tests pass with clear error evidence.

**Required verification:** HOST (CMake/CTest); ANDROID; INTEGRATION; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 030](#agent-030); [Agent 051](#agent-051).

**Interface handoff:** Expose strict cache load/store/manifest operations with bounded caller-provided identities and explicit status. 046 owns depth execution; 009 owns Android identity/path lifecycle; publication remains with the repository/job coordinator.

**Start immediately:** Extend baseline checkpoint tests with durability ordering, interrupted writes and byte-budget fixtures; retain legacy validated caches or safely recompute after a version change.

## Agent 051

**Role:** Scoped cancellation and measurable native progress

**Branch:** `agents/051-native-cancellation-progress`

**Priority:** P0

**Status receipt:** `docs/agents/status/051.md`

**Mission:** Extend the existing operation-scoped cancellation model across sparse and dense reconstruction, with real bounded work progress and safe lifetimes. Preserve token isolation and checkpoint resumption rather than adding global stop flags or invented percentages.

**Why this work remains:**

- [reconstruction/include/realityforge/cancellation.hpp](reconstruction/include/realityforge/cancellation.hpp) existing thread-local operation scope
- [reconstruction/src/cancellation.cpp](reconstruction/src/cancellation.cpp) existing atomic cancellation checkpoint
- [reconstruction/src/core.cpp](reconstruction/src/core.cpp): 139-153 and bundle_adjustment.cpp: 287-310 lack sparse iteration checkpoints
- [docs/MILESTONE_STATUS.md](docs/MILESTONE_STATUS.md) M17 states sparse cancellation only at Kotlin boundaries

**Exclusive existing files:**

- `reconstruction/src/cancellation.cpp`
- `reconstruction/include/realityforge/cancellation.hpp`
- `reconstruction/tests/cancellation_test.cpp`

**Reserved new files/packages:**

- `reconstruction/include/realityforge/progress.hpp`
- `reconstruction/src/progress.cpp`
- `reconstruction/tests/progress_test.cpp`

**Implementation instructions:**

1. Define thread-safe scoped progress/cancellation contracts that restore nested scopes, isolate simultaneous operations and never retain stale callback state.
2. Provide checkpoint policies and integration snippets to owners 037–041,046,050,053,057 and 060; those owners alone edit their kernels.
3. Cooperate with bridge003 on lifetime leases, sparse call cancellation and Java exception propagation; avoid posting per-pixel events or blocking the main thread.
4. Measure worst observed cancellation latency for representative native loops and expose stage/reference/tile completed work through 002 without claiming an unknown whole-scan percentage.

**Success criteria:**

- Nested scopes, cancellation before/during execution, stale handles and simultaneous operations are tested without cross-operation interruption or leaked progress callbacks.
- Kernel-owner integration tests demonstrate cancellation inside sparse iterations and long dense/texture loops; cancelled calls never publish partial results.
- Measured cancellation/progress behavior passes actual Android JNI tests and bounded-event/resource tests; absent physical latency evidence is explicitly NOT_RUN.

**Required verification:** HOST (CMake/CTest); ANDROID; INTEGRATION; PERFORMANCE.

**Integration inputs and cooperating owners:** No new provider prerequisite; start against the audited baseline and shared contracts.

**Interface handoff:** Provide minimal scoped checkpoint and progress primitives; 003 owns JNI handle registry, 002 owns user-visible progress, and kernel owners insert checks. A cancellation exception must never be swallowed as a successful quality warning.

**Start immediately:** Expand existing cancellation fixtures for nested/concurrent scope lifetimes and publish the progress API with baseline loop integration examples before coordinating kernel edits.

## Agent 052

**Role:** Measured Vulkan depth backend with CPU fallback

**Branch:** `agents/052-vulkan-depth-backend`

**Priority:** P2

**Status receipt:** `docs/agents/status/052.md`

**Mission:** Implement a bounded Vulkan acceleration option against the verified CPU algorithms while keeping the CPU backend available and default until actual numerical, driver and resource evidence supports enabling it. Acceleration must never be inferred from compilation alone.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md) Architecture preserved and phase 18 require CPU reference before Vulkan acceleration
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 140-283 currently implements CPU-only depth
- CMakeLists.txt current native sources contain no Vulkan backend
- [app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt](app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt):6-23 is a limited allocation policy, not driver evidence

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/vulkan_depth.hpp`
- `reconstruction/src/vulkan/`
- `reconstruction/shaders/depth/`
- `reconstruction/tests/vulkan_depth_contract_test.cpp`

**Implementation instructions:**

1. Define capability detection and checked compute/image/buffer budgets; handle unsupported formats, driver failures and allocation limits through an explicit CPU fallback.
2. Implement narrowly scoped cost/depth kernels compatible with 046–049, deterministic output validation and scoped051 cancellation; keep runtime assets packaged and offline.
3. Supply shader/build registration requests to 004 and optional JNI/backend selection to 003/002 without editing central files.
4. Measure CPU/GPU equivalence, peak combined memory, dispatch overhead and sustained performance on actual supported Android hardware; retain a disabled experimental profile if evidence is unavailable.

**Success criteria:**

- CPU and Vulkan results meet declared numerical, accuracy and completeness tolerances across known depth/occlusion/mask fixtures; no quality gate is relaxed for speed.
- Real device capability, driver-loss/allocation failure, cancellation and thermal/resource tests confirm safe fallback and a measured benefit for each enabled profile.
- Shader/native builds and validation layers pass; unavailable hardware evidence is recorded NOT_RUN, CPU remains the production default, and no accelerated-success claim is made.

**Required verification:** HOST (CMake/CTest); ANDROID; GEOMETRY; PERFORMANCE; DEVICE.

**Integration inputs and cooperating owners:** [Agent 004](#agent-004); [Agent 030](#agent-030); [Agent 046](#agent-046); [Agent 047](#agent-047); [Agent 048](#agent-048); [Agent 049](#agent-049); [Agent 051](#agent-051).

**Interface handoff:** Expose a capability-gated DepthBackend adapter consuming the CPU contract and reporting actual backend/fallback reasons. 046 owns numerical semantics, 030 owns admission and 003/002 own integration; no separate renderer or online model service.

**Start immediately:** Implement capability/budget contracts, host-side descriptor validation and shader fixtures against baseline CPU cases; build the backend without enabling it until real device parity evidence exists.

## Agent 053

**Role:** Observed surface fusion and overlap reduction

**Branch:** `agents/053-surface-fusion-meshing`

**Priority:** P1

**Status receipt:** `docs/agents/status/053.md`

**Mission:** Improve the implemented observed mesh into a coherent supported surface while retaining openings, thin components and explicit unknown regions. More triangles or a closed-looking shape do not establish reconstruction fidelity.

**Why this work remains:**

- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp):37-40 converts voxel coordinates to int64 without representability checks
- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp): 100-142 fuses by Euclidean proximity and view mask
- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp): 157-205 constructs per-image grid triangles and deduplicates identical triples only

**Exclusive existing files:**

- `reconstruction/src/surface.cpp`
- `reconstruction/include/realityforge/surface.hpp`
- `reconstruction/tests/surface_test.cpp`
- `tests/test_surface.py`

**Reserved new files/packages:**

- `reconstruction/src/surface_fusion.cpp`
- `reconstruction/include/realityforge/surface_fusion.hpp`
- `reconstruction/tests/surface_fusion_test.cpp`

**Implementation instructions:**

1. Fix voxel integer range checks and validate all source/sample/pose dimensions before spatial allocation; use bounded partitions for large supported workloads.
2. Integrate confidence/normal/visibility-aware fusion from 049/054 to avoid merging nearby opposite sides and remove view-order bias.
3. Reduce redundant overlapping image-grid patches, carry source observation provenance and preserve valid boundaries; use 055/056 diagnostics and conservative cleanup.
4. Coordinate canonical export migration with 061/064 and provide mesh artifacts to 057/065 without editing exporter or texture-owned code.
5. Apply 003's no-throw C ABI contract to exported rf_* functions in your owned source, including error serialization/allocation failures. Keep rf_free/caller ownership defined and add a direct C ABI failure regression; a safe JNI wrapper alone is insufficient.

**Success criteria:**

- Thin sheets, open handles, disconnected real components, curved/slanted surfaces and duplicate overlapping patches retain correct geometry against independent truth.
- Topology/error reports show reduced duplicates and invalid intersections without hiding missing surfaces; correct winding and observed-region provenance survive fusion.
- Extreme coordinate/dimension sanitizer cases, cancellation and resource tests pass; integrated real multi-view output preserves existing stereo accuracy and declares unmeasured physical completeness honestly.

**Required verification:** HOST (CMake/CTest); HOST (pytest); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 046](#agent-046); [Agent 049](#agent-049); [Agent 054](#agent-054); [Agent 055](#agent-055); [Agent 056](#agent-056).

**Interface handoff:** Own SurfaceMesh and supported fusion orchestration; 054 supplies observation normals/uncertainty,055 reports defects,056 proposes conservative operations,057 textures and 065 builds LODs. Never silently promote repaired/generated regions to observed geometry.

**Start immediately:** Add range-overflow, opposing thin-surface and overlapping-grid fixtures around baseline fuseDepthSurfaces, fixing direct numeric defects before integrating new fusion modules.

## Agent 054

**Role:** Surface normals and geometric uncertainty

**Branch:** `agents/054-normals-geometric-uncertainty`

**Priority:** P1

**Status receipt:** `docs/agents/status/054.md`

**Mission:** Provide trustworthy geometric normals, conditioning and uncertainty for observed points/depth neighborhoods. These signals should improve refinement, thin-surface fusion and diagnostic honesty, without pretending heuristic confidence is a physical accuracy measurement.

**Why this work remains:**

- [reconstruction/include/realityforge/dense.hpp](reconstruction/include/realityforge/dense.hpp):34-53 dense samples carry heuristic confidence but no estimated normal uncertainty
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp): 215-217 confidence derives from cost margin and correlation
- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp): 209-217 derives final area-weighted normals after triangulation

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/surface_evidence.hpp`
- `reconstruction/src/surface_evidence.cpp`
- `reconstruction/tests/surface_evidence_test.cpp`

**Implementation instructions:**

1. Implement robust local normal estimation using actual supported samples and camera geometry, retaining distinct point/depth/mesh normal semantics.
2. Estimate conditioning and uncertainty from parallax, observation noise and neighborhood support; clearly label calibrated versus heuristic evidence.
3. Reject unstable neighborhoods at boundaries, occlusions or mixed surfaces and preserve normal orientation relative to supporting cameras.
4. Publish bounded independent utilities to 040/047/049/053; owners 046/053 alone extend shared structs and integrate computed evidence.

**Success criteria:**

- Analytic planes, curved patches, noisy observations and opposing thin surfaces yield expected normal directions and uncertainty ordering within defined tolerances.
- Boundary/discontinuity and sparse-support fixtures report unknown/unstable normals rather than smoothing across real gaps; orientation remains consistent under camera/coordinate transforms.
- Held-out depth/geometry tests calibrate confidence against actual error, and working-set/cancellation/sanitizer tests keep the module suitable for CPU Android profiles.

**Required verification:** HOST (CMake/CTest); GEOMETRY; PERFORMANCE; INTEGRATION.

**Integration inputs and cooperating owners:** No new provider prerequisite; start against the audited baseline and shared contracts.

**Interface handoff:** Return typed ObservedNormal/GeometryUncertainty with supporting observation IDs and validity reasons. Consumers must not treat absent uncertainty as zero error or equate confidence with whole-object completeness.

**Start immediately:** Implement independent analytic plane/noise fixtures using baseline VerifiedDepthSample and SurfaceMesh data, keeping algorithms in new files and returning additive evidence structures.

## Agent 055

**Role:** Mesh topology and self-intersection diagnostics

**Branch:** `agents/055-mesh-topology-diagnostics`

**Priority:** P0

**Status receipt:** `docs/agents/status/055.md`

**Mission:** Make mesh quality assessable with concrete geometric and topological evidence. Diagnose real boundaries, disconnected components, nonmanifold structures and intersections without automatically deleting legitimate observed geometry or asserting watertightness.

**Why this work remains:**

- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp):61-68 validateMesh checks bounds, finite values and indices only
- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp): 171-174 only identical vertex triples are deduplicated
- [docs/NEXT_MILESTONE.md](docs/NEXT_MILESTONE.md) item4 requires nonmanifold/overlapping triangle diagnostics

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/mesh_topology.hpp`
- `reconstruction/src/mesh_topology.cpp`
- `reconstruction/tests/mesh_topology_test.cpp`

**Implementation instructions:**

1. Build bounded edge/vertex adjacency and component labeling with exact source indices and deterministic defect records.
2. Detect degenerate/duplicate faces, winding conflicts, nonmanifold edges/vertices and intersecting/overlapping triangles through an appropriate bounded spatial index.
3. Report boundary loops and observed/generated distinctions, including uncertainty where numeric tolerance makes classification ambiguous.
4. Publish a pure diagnostic API to 053/056/058/065 and quality-report/studio owners; keep repair, export and UI implementation outside this branch.

**Success criteria:**

- Analytic meshes with known defect counts produce exact expected diagnostics; valid open surfaces, thin sheets and separated components avoid false watertight or invalid labels.
- Adversarial near-coplanar, tiny/large scale and repeated-triangle cases pass robust-predicate/sanitizer tests with documented tolerances.
- Large supported meshes stay within memory/runtime bounds and integrated diagnostics identify topology changes without replacing observed geometry or hiding failures in aggregate metrics.

**Required verification:** HOST (CMake/CTest); GEOMETRY; PERFORMANCE; INTEGRATION.

**Integration inputs and cooperating owners:** No new provider prerequisite; start against the audited baseline and shared contracts.

**Interface handoff:** MeshTopologyReport contains stable face/vertex IDs, component/boundary data, defect categories and measurement tolerances. 056 may act on specified defects; 053 and release validators decide acceptance, never this reporter alone.

**Start immediately:** Create exact synthetic topology fixtures and implement diagnostics against the current SurfaceMesh API; this requires no pending engine or UI change.

## Agent 056

**Role:** Conservative mesh cleanup and explicit repair

**Branch:** `agents/056-conservative-mesh-cleanup`

**Priority:** P1

**Status receipt:** `docs/agents/status/056.md`

**Mission:** Reduce evidenced mesh defects while preserving the observed shape, true openings and meaningful disconnected components. Any optional generated repair must remain identifiable, reversible and separate from reconstructed evidence.

**Why this work remains:**

- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp): 219-227 compacts unused vertices but has no topology-aware cleanup
- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp): 157-205 leaves overlapping grid patch risks
- [PROJECT_PLAN.md](PROJECT_PLAN.md) phases 11-12 require thin-surface tests and conservative repair preserving genuine openings

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/mesh_cleanup.hpp`
- `reconstruction/src/mesh_cleanup.cpp`
- `reconstruction/tests/mesh_cleanup_test.cpp`

**Implementation instructions:**

1. Consume 055 reports to remove verified duplicates/degenerates and resolve supported overlaps with explicit geometric displacement budgets.
2. Use observation confidence and component support rather than arbitrary size thresholds that might delete thin or small real structures.
3. Preserve boundary loops and sharp/thin features; if a user-requested repair is supported, record affected faces and generated provenance instead of silently filling holes.
4. Return proposed operations and resulting metrics to 053/066, preserving the high-fidelity source mesh and enabling rollback; do not edit studio or persistence code.

**Success criteria:**

- Open handle, thin sheet, small legitimate component, duplicate patch and noisy surface fixtures demonstrate reduced target defects without destroying known openings.
- Measured surface/normal displacement and component changes stay inside declared budgets, with exact generated/observed provenance and reversibility.
- Malformed input, cancellation and large bounded workloads pass tests; cleanup cannot turn a failed reconstruction into a falsely complete or watertight success.

**Required verification:** HOST (CMake/CTest); GEOMETRY; PERFORMANCE; INTEGRATION.

**Integration inputs and cooperating owners:** [Agent 055](#agent-055).

**Interface handoff:** Expose CleanupPlan/CleanupResult with explicit operations, before/after diagnostics and provenance mapping. 053 owns default pipeline adoption; 066 owns user-requested edits; 065 must retain source and repaired versions distinctly.

**Start immediately:** Implement non-destructive duplicate/degenerate cleanup on analytic baseline meshes and verify it against 055-compatible diagnostic fixtures before adding any optional repair.

## Agent 057

**Role:** Photographic atlas pipeline and bounded encoding

**Branch:** `agents/057-photographic-atlas-pipeline`

**Priority:** P1

**Status receipt:** `docs/agents/status/057.md`

**Mission:** Integrate real photographic baking into compact, visible, resource-bounded texture outputs while preserving unsupported faces and the original mesh. Retain measured exposure and UV orientation fixes; do not misrepresent a textured subset as a complete object.

**Why this work remains:**

- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 229-257 copies whole photographs into atlas tiles
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 293-294 tests other-camera visibility near the face centroid
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp):51-92 constructs uncompressed raw/zlib/PNG copies
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 348-385 outputs only supported textured faces

**Exclusive existing files:**

- `reconstruction/src/texture.cpp`
- `reconstruction/include/realityforge/texture.hpp`
- `reconstruction/tests/texture_test.cpp`
- `tests/test_texture.py`

**Reserved new files/packages:**

- `reconstruction/src/texture_visibility.cpp`
- `reconstruction/include/realityforge/texture_visibility.hpp`
- `reconstruction/src/texture_png.cpp`
- `reconstruction/include/realityforge/texture_png.hpp`
- `reconstruction/tests/texture_visibility_test.cpp`
- `reconstruction/tests/texture_png_test.cpp`

**Implementation instructions:**

1. Decompose current atlas construction into 058 UV charts, actual triangle/texel visibility,060 radiometry and 059 seam-aware baking, preserving source provenance.
2. Replace centroid-only visibility with bounded geometric/depth support, accounting for partially occluded/masked triangles and genuine unknown texels.
3. Implement bounded streaming lossless PNG encoding with an approved dependency, cancellation and sink failure handling; coordinate canonical GLB writer migration through 061.
4. Integrate 2K/4K profiles only within 030 measured budgets, and emit separate texel density, supported-face coverage, alignment and seam metrics.
5. Apply 003's no-throw C ABI contract to exported rf_* functions in your owned source, including error serialization/allocation failures. Keep rf_free/caller ownership defined and add a direct C ABI failure regression; a safe JNI wrapper alone is insufficient.

**Success criteria:**

- Known occluders, holes, masks and photographic fixtures project only visible source pixels; independent UV orientation and current real photo accuracy regressions pass.
- Actual atlas/bake metrics show bounded distortion, supported coverage and seam improvement without hiding untextured geometry; source originals and high-fidelity mesh remain available.
- Independent PNG decode preserves pixels exactly; disk-full/cancelled writes are not published, and representative texture profiles meet measured peak RSS/driver limits.

**Required verification:** HOST (CMake/CTest); HOST (pytest); ANDROID; GEOMETRY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 030](#agent-030); [Agent 031](#agent-031); [Agent 046](#agent-046); [Agent 049](#agent-049); [Agent 053](#agent-053); [Agent 058](#agent-058); [Agent 059](#agent-059); [Agent 060](#agent-060).

**Interface handoff:** Own photographic pipeline integration and visibility/encoding adapters; 058 owns UVs,059 blends,060 balances colors and 061 writes canonical GLB.002/003 alone change Android/JNI call sites and publication.

**Start immediately:** Add partially occluded triangle and PNG memory/equality fixtures against baseline bakePhotographicAtlas, then extract owned helpers while keeping existing outputs as reference.

## Agent 058

**Role:** Compact UV charts and atlas packing

**Branch:** `agents/058-uv-charts-packing`

**Priority:** P1

**Status receipt:** `docs/agents/status/058.md`

**Mission:** Replace wasteful whole-photo tiling with compact, distortion-bounded surface charts and accurate texel mappings. Improve effective texture detail without inventing photographic data, damaging open surfaces or creating overlapping UVs.

**Why this work remains:**

- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 229-233 atlas dimensions derive from whole-image tiles
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 355-368 UV coordinates directly reference source photograph tiles
- [PROJECT_PLAN.md](PROJECT_PLAN.md) phase 13 calls for UV unwrapping and photographic baking

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/uv_atlas.hpp`
- `reconstruction/src/uv_atlas.cpp`
- `reconstruction/tests/uv_atlas_test.cpp`

**Implementation instructions:**

1. Implement chart segmentation, parameterization and deterministic packing with explicit distortion, texel-density and padding budgets.
2. Preserve stable face/vertex provenance and support sharp features, boundaries and disconnected observed components; cooperate with 065 LOD mapping.
3. Evaluate an exact approved unwrapping dependency if needed, handing package/version/license and build changes to the dependency/004 owners.
4. Provide surface-to-chart/texel mappings and mipmap-safe gutters to 057/059, keeping photographic source selection and seam blending out of this module.

**Success criteria:**

- Independent UV tests prove no chart overlap, bounded orientation/stretch and valid round-trip mapping for curved, thin and open surfaces.
- Packing utilization and effective texel density are measured at 2K/4K budgets against baseline photo tiling; gutters prevent cross-chart bleeding under mipmaps.
- Deterministic packing, invalid topology, cancellation and maximum supported mesh-size tests pass with measured memory bounds; integrated atlas sampling preserves photographic alignment.

**Required verification:** HOST (CMake/CTest); GEOMETRY; PERFORMANCE; INTEGRATION.

**Integration inputs and cooperating owners:** [Agent 055](#agent-055).

**Interface handoff:** Return UVAtlasLayout with chart IDs, vertex splits, packed rectangles, padding and source face mappings. 057 owns bake adoption,059 consumes texel mappings and 065 supplies LOD correspondences; no private conflicting UV conventions.

**Start immediately:** Build analytic plane/cube/open-mesh chart fixtures against baseline SurfaceMesh and implement layout validation immediately; propose dependency approval only after comparing bounded options.

## Agent 059

**Role:** Seam-aware photographic blending and rebake

**Branch:** `agents/059-texture-seam-blending`

**Priority:** P1

**Status receipt:** `docs/agents/status/059.md`

**Mission:** Reduce visible photographic seams with visibility-respecting texel blending and valid LOD rebaking. Discrete view-switch counts are insufficient evidence; preserve texture detail, occlusion edges and truthful unsupported regions.

**Why this work remains:**

- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 309-347 only smooths camera labels with a bounded ICM energy
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 348-377 each face selects one photograph source
- [docs/MILESTONE_STATUS.md](docs/MILESTONE_STATUS.md) appearance is measured exposure/label smoothing, not seamless blending

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/texture_blend.hpp`
- `reconstruction/src/texture_blend.cpp`
- `reconstruction/tests/texture_blend_test.cpp`

**Implementation instructions:**

1. Implement per-texel source weighting using only 057 verified visibility/support and 060 radiometric corrections.
2. Add chart-aware seam control and bounded multiscale blending that does not smear texture across holes, object edges or invalid masks.
3. Support tile halos and deterministic rebake mappings for 065 LODs, preserving high-fidelity source textures and explicit output density settings.
4. Measure color discontinuity, photometric alignment and detail loss separately; supply integration to 057 without rewriting atlas selection or encoding.

**Success criteria:**

- Independent color/exposure/edge fixtures and real photo tests show reduced seam color error while retaining known high-frequency detail and source alignment.
- Foreground occluders, thin gaps, missing sources and chart borders never receive unsupported blended pixels; alpha/provenance remain consistent.
- Tiled versus untiled and resumed versus clean baking agree within documented tolerance;2K/4K and LOD workloads satisfy memory/cancellation gates and valid material mapping.

**Required verification:** HOST (CMake/CTest); GEOMETRY; PERFORMANCE; INTEGRATION.

**Integration inputs and cooperating owners:** [Agent 058](#agent-058); [Agent 060](#agent-060).

**Interface handoff:** TextureBlend consumes layout, verified source samples and radiometric transforms, returning bounded texel tiles plus support/seam metrics. 057 owns final texture publication; 065 supplies source-to-LOD mappings and must not copy stale UVs.

**Start immediately:** Implement controlled overlapping-photo and masked-seam fixtures using baseline exposure gains and synthetic UV layouts; measure seam/detail behavior before coupling to new atlas code.

## Agent 060

**Role:** Measured exposure and color consistency

**Branch:** `agents/060-photometric-color-consistency`

**Priority:** P1

**Status receipt:** `docs/agents/status/060.md`

**Mission:** Improve photographic consistency under realistic exposure and white-balance variation while preserving actual image detail and color provenance. Extend the working bounded gain solver; do not invent material albedo or overwrite source photographs.

**Why this work remains:**

- [reconstruction/src/photometric.cpp](reconstruction/src/photometric.cpp):39-43 implements sRGB/linear conversion
- [reconstruction/src/photometric.cpp](reconstruction/src/photometric.cpp) existing balanceExposure solves bounded camera gains
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 142-166 samples sparse shared surface evidence for exposure balance
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): 235-253 applies measured linear-sRGB gains

**Exclusive existing files:**

- `reconstruction/src/photometric.cpp`
- `reconstruction/include/realityforge/photometric.hpp`
- `reconstruction/tests/photometric_test.cpp`

**Reserved new files/packages:**

- `reconstruction/tests/photometric_adversarial_test.cpp`

**Implementation instructions:**

1. Audit linear/sRGB transformations, clipping/saturation rejection, shared sample conditioning and disconnected camera-group behavior.
2. Implement robust bounded per-channel corrections and optional supported spatial terms only when withheld measurements demonstrate benefit; retain uncorrected fallback.
3. Separate geometric visibility failures from photometric disagreement and carry sample/group confidence through 057/059.
4. Add checked resource/iteration bounds and 051 cancellation, reporting before/after color residuals rather than only optimized gains.

**Success criteria:**

- Independent exposure/white-balance synthetic truth validates recovered corrections, transfer functions and bounded output without clipping or nonfinite colors.
- Dark/saturated inputs, outliers, weak overlap and disconnected source groups produce safe identity/fallback transforms with honest diagnostics.
- Real varying-light photographs show measured seam/color improvement without detail or alignment regression; native sanitizer/cancellation tests and integrated Android photographic sessions pass.

**Required verification:** HOST (CMake/CTest); HOST (pytest); ANDROID; GEOMETRY.

**Integration inputs and cooperating owners:** [Agent 051](#agent-051).

**Interface handoff:** Expose RadiometricBalance with per-group correction, measurement support, residuals and validity. 057 gathers genuinely co-visible samples,059 blends corrected values and 009 versions changed settings; camera geometry is untouched.

**Start immediately:** Extend current balanceExposure tests with independent clipped/outlier/disconnected scenarios and record the baseline residuals before modifying robust fitting or correction models.

## Agent 061

**Role:** Canonical 3D asset model and correct GLB export

**Branch:** `agents/061-glb-asset-contract`

**Priority:** P0

**Status receipt:** `docs/agents/status/061.md`

**Mission:** Give points, observed meshes and photographic assets one explicit representation contract and export standards-conforming GLB without semantic mislabeling.

**Why this work remains:**

- [reconstruction/src/core.cpp](reconstruction/src/core.cpp): writePly/writeGlb label rf::Result as sparse_points even when the dense-point export path in SparsePipeline.run supplies dense points.
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp) and surface.cpp contain separate mesh/GLB writers; representation and buffer conventions need a shared validated boundary.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/asset_model.hpp`
- `reconstruction/include/realityforge/glb_export.hpp`
- `reconstruction/src/asset_model.cpp`
- `reconstruction/src/glb_export.cpp`

**Implementation instructions:**

1. Define validated PointAsset/SurfaceAsset/TexturedAsset views with indices, normals, UVs, source provenance, arbitrary-versus-metric units and no accidental geometry copies.
2. Implement bounded GLB serialization for each representation, correct coordinate/winding transforms, byte lengths/alignment, material/image references and finite accessor bounds.
3. Make sparse and dense point semantics distinct in embedded metadata; never use POINTS to advertise a surface or mark vertex color as a photographic UV texture.
4. Have 037/053/057 delegate legacy writers through small compatibility adapters; 003 exposes the typed choice and 067 builds available format options. Use independent 090 validation.

**Success criteria:**

- Sparse POINTS, dense POINTS, observed triangles and textured triangles retain correct representation after external import.
- GLB lengths/indices/accessors/materials pass an independent validator; opposite-axis fixtures prove winding and UV orientation.
- Oversized/nonfinite/invalid input is rejected before large allocation or output publication; export is cancellable and preserves original geometry.

**Required verification:** HOST; ANDROID; DEVICE; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 003](#agent-003); [Agent 009](#agent-009); [Agent 037](#agent-037); [Agent 053](#agent-053); [Agent 057](#agent-057).

**Interface handoff:** Own C10 canonical asset representation and GLB serializer. Domain owners retain their source files and call this module; 062–066,068–070 and 090 consume the same declared geometry/units.

**Start immediately:** Build independent serialization fixtures from current point/mesh output and reproduce the sparse-versus-dense label issue before wiring legacy writers.

## Agent 062

**Role:** OBJ and MTL photographic export

**Branch:** `agents/062-obj-export`

**Priority:** P1

**Status receipt:** `docs/agents/status/062.md`

**Mission:** Export useful OBJ packages with valid geometry, material references and the actual photographic atlas.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md): GLB/OBJ/PLY/STL exports are part of the product contract; current ScannerViewModel.export whitelist has no OBJ/MTL.
- [reconstruction/src/texture.cpp](reconstruction/src/texture.cpp): photographic triangle/UV/atlas data already exists and can be exported without reconstructing it again.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/obj_export.hpp`
- `reconstruction/src/obj_export.cpp`

**Implementation instructions:**

1. Consume 061 asset data, writing stable vertex/UV/normal indices and correct face winding with a documented coordinate/units convention.
2. Write MTL and source-texture references with safe relative filenames; use a package/ZIP manifest through 067 for providers that cannot create sibling documents.
3. Stream large text output with cancellation and output budgets; preserve holes, disconnected observed components and photometric provenance.
4. Keep sparse point export explicit and unsupported combinations rejected; do not silently convert points into faces.

**Success criteria:**

- An independently imported OBJ package has the expected triangle count, bounding box, normals, UV orientation and photographed appearance.
- Texture filenames resolve after ZIP extraction into a different directory, with no external path or missing sibling resource.
- Cancellation, short writes and large-model limits produce a clear failed export without changing the current project artifact.

**Required verification:** HOST; ANDROID; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 061](#agent-061); [Agent 067](#agent-067).

**Interface handoff:** Provide a descriptor plus file-set writer to 067; 026 owns document-provider transport. 090 validates in an independent consumer, including a checker texture and asymmetric geometry.

**Start immediately:** Implement against hand-constructed canonical asset fixtures and current photographic outputs; request shared build registration early.

## Agent 063

**Role:** STL export and printability diagnostics

**Branch:** `agents/063-stl-export`

**Priority:** P1

**Status receipt:** `docs/agents/status/063.md`

**Mission:** Offer an honest, interoperable triangle-only STL export with explicit scale and topology limitations.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md) requires STL where appropriate; current code provides no STL writer.
- [README.md](README.md): geometry has arbitrary scale and may be nonmanifold/open; a print-ready promise would be false.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/stl_export.hpp`
- `reconstruction/src/stl_export.cpp`

**Implementation instructions:**

1. Implement bounded binary STL writing from 061 surface assets with correct facet normals, face count and orientation.
2. Require explicit units or an acknowledged arbitrary-scale conversion through 066; preserve the stored master geometry and disclose STL material/color limitations.
3. Use 055 topology results to report open/nonmanifold meshes; never automatically fill holes or claim printability from successful file writing.
4. Integrate format eligibility and output size estimation into 067 with cancellation, finite-value checks and external parser tests.

**Success criteria:**

- An independent STL parser reports identical triangle count and the selected scale/bounds for an asymmetric reference fixture.
- Point clouds and missing/invalid scale choices receive clear appropriate handling, and an open mesh remains identified as open.
- Invalid normals/coordinates/count overflow and interrupted writes cannot be published as successful STL exports.

**Required verification:** HOST; ANDROID; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 055](#agent-055); [Agent 061](#agent-061); [Agent 066](#agent-066); [Agent 067](#agent-067).

**Interface handoff:** Consume canonical observed surface data and EditRecipe scale; expose printability diagnostics to 067/074. Do not add a repair pipeline in the serializer.

**Start immediately:** Build the writer and topology/scale cases on local analytic fixtures immediately; add real meshes when 053/055 are available.

## Agent 064

**Role:** PLY export with normals, confidence and provenance

**Branch:** `agents/064-ply-export`

**Priority:** P1

**Status receipt:** `docs/agents/status/064.md`

**Mission:** Preserve useful scan evidence in interoperable point and surface PLY exports.

**Why this work remains:**

- [reconstruction/src/core.cpp](reconstruction/src/core.cpp): writePly embeds sparse_points semantics in a shared result writer.
- [reconstruction/src/dense.cpp](reconstruction/src/dense.cpp) and surface.cpp recover confidence and surface observations, but the export contract does not consistently retain them.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/ply_export.hpp`
- `reconstruction/src/ply_export.cpp`

**Implementation instructions:**

1. Implement explicit sparse, dense and surface variants with vertex colors, optional normals, confidence/support fields and faces where appropriate.
2. Support a documented bounded binary encoding and a small readable fixture path, with stable property order/types and checked byte counts.
3. Carry unit/coordinate/observed-representation metadata without claiming standard readers understand custom comments; preserve unknown scale.
4. Route legacy writers through their owners 037/053 and expose choices through 067; coordinate normal conventions with 054 and GLB transforms with 061.

**Success criteria:**

- Independent readers recover expected points/faces, colors, normal directions, confidence fields and bounds from roundtrip fixtures.
- Dense outputs are never labeled sparse; surface exports carry actual face indices and sparse exports never fabricate faces.
- Malformed geometry, unsupported field sizes and cancellation produce explicit errors with bounded memory.

**Required verification:** HOST; ANDROID; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 037](#agent-037); [Agent 053](#agent-053); [Agent 054](#agent-054); [Agent 061](#agent-061); [Agent 067](#agent-067).

**Interface handoff:** Provide the PLY serializer and metadata descriptor to 067.061 owns shared representation/coordinate policy,054 supplies normals, and 090 verifies externally.

**Start immediately:** Reproduce current point semantics with existing export fixtures and add a tiny independent property parser before integrating with the app.

## Agent 065

**Role:** Detail-preserving mesh decimation and LODs

**Branch:** `agents/065-mesh-lod`

**Priority:** P1

**Status receipt:** `docs/agents/status/065.md`

**Mission:** Create smaller view/export variants with measured geometric and photographic error while preserving the highest-fidelity source.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md): mesh optimization/LODs and rebaking are open product phases.
- [reconstruction/src/surface.cpp](reconstruction/src/surface.cpp) builds observed patches; there is no decimation/LOD module in reconstruction/src.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/include/realityforge/mesh_lod.hpp`
- `reconstruction/src/mesh_lod.cpp`

**Implementation instructions:**

1. Implement constrained decimation that preserves open boundaries, disconnected components, material/UV seams and thin structures.
2. Generate deterministic LOD recipes from explicit face/error budgets; reject reductions that break topology constraints or invert faces.
3. Keep master geometry immutable and label every derived LOD with parent identity, algorithm version and measured deviation.
4. Coordinate optional rebake/UV transfer with 057/058 and expose LOD selection to 067/069; measure geometric error independently with 084.

**Success criteria:**

- Requested reductions meet predeclared geometric error bounds on closed, open, thin and disconnected fixtures without invented surface closure.
- UV/normal/face integrity and texture seams remain valid after reduction, or the operation refuses an unsupported case.
- LOD reuse/invalidation follows 009 identities; master counts/hashes remain unchanged and cancellation never replaces the master.

**Required verification:** HOST; GEOMETRY; TEXTURE; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 055](#agent-055); [Agent 057](#agent-057); [Agent 058](#agent-058); [Agent 061](#agent-061).

**Interface handoff:** Provide an immutable derived SurfaceAsset plus error report to 067 and 069.055 sets topology invariants,057 handles any rebaking, and 009 identities distinguish every LOD.

**Start immediately:** Develop constrained simplification and boundary tests on existing open mesh fixtures; use actual reduced geometry rather than a renderer-only triangle skip.

## Agent 066

**Role:** Non-destructive orientation, crop, scale and measurements

**Branch:** `agents/066-model-editing`

**Priority:** P1

**Status receipt:** `docs/agents/status/066.md`

**Mission:** Let users make useful local edits and measurements through reversible recipes with explicit scale provenance.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md): production studio requires crop/orient/scale; current viewer only orbits, zooms and resets.
- [README.md](README.md) and docs/ARCHITECTURE.md disclose arbitrary scale and open observed geometry.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/studio/editing/`
- `reconstruction/include/realityforge/asset_edit.hpp`
- `reconstruction/src/asset_edit.cpp`

**Implementation instructions:**

1. Define versioned EditRecipe operations for orientation, crop/component selection, metric reference and undo/redo, stored separately from the source asset.
2. Implement coordinate-correct transforms, normal/winding updates and conservative crop behavior that preserves open boundaries and photographic UV correspondence.
3. Require a real known-distance reference before claiming metric accuracy; propagate units and uncertainty into diagnostics and exports.
4. Persist edit state through 005/009, connect controls through 070 and export through 067 without rerunning camera alignment for a view/edit change.

**Success criteria:**

- Undo/redo and reopening reproduce exact edit recipes while source geometry/photo hashes remain unchanged.
- Known-distance synthetic fixtures yield the expected scale/measurement and axis orientation; unknown scale is never displayed as measured meters.
- Crop/transform outputs pass topology and external-format checks, including negative/reflected transforms if supported; unsupported operations fail explicitly.

**Required verification:** HOST; ANDROID; DEVICE; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 005](#agent-005); [Agent 009](#agent-009); [Agent 055](#agent-055); [Agent 061](#agent-061).

**Interface handoff:** Own C11 EditRecipe/MeasurementEvidence and its pure geometry operations. 070 renders controls,005 persists recipes and 067 materializes selected variants; no direct edits to viewer or database owner files.

**Start immediately:** Build reversible edit/coordinate tests on existing assets and provide a controller that the current viewer can adopt without a renderer migration.

## Agent 067

**Role:** Android export product flow and format orchestration

**Branch:** `agents/067-export-coordinator`

**Priority:** P1

**Status receipt:** `docs/agents/status/067.md`

**Mission:** Present and execute valid export choices tied to the exact model, representation, edits and destination.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt): export uses a hard-coded filename-extension whitelist and copies a selected file.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt) defines numerous separate export document launchers; there is no unified representation/format/options flow.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/export/`

**Implementation instructions:**

1. Build ExportCoordinator and typed ExportRequest from immutable artifact/revision/representation/format/options; derive availability from real completed assets.
2. Connect 061–064 writers,065 LODs,066 edit recipes,025 archives and 026 document transport with progress, cancellation and byte estimates.
3. Distinguish source bundle, diagnostics, points, observed mesh, photographic mesh and print formats; disclose units/material limitations in meaningful UI copy.
4. Support safe multi-file packages and validation before transport; failure must not mark a partial destination successful or invalidate the saved model.

**Success criteria:**

- Every displayed export option produces a valid independent-reader artifact, and unavailable representation/format combinations cannot be selected.
- A pending system-picker result stays bound to the initiating project/artifact even after navigation, process recreation or concurrent changes.
- Export-only choices reuse upstream reconstruction and preserve originals/master artifacts through cancellation and provider errors.

**Required verification:** ANDROID; DEVICE; INTEROPERABILITY; RESILIENCE.

**Integration inputs and cooperating owners:** [Agent 009](#agent-009); [Agent 024](#agent-024); [Agent 025](#agent-025); [Agent 026](#agent-026); [Agent 061](#agent-061); [Agent 062](#agent-062); [Agent 063](#agent-063); [Agent 064](#agent-064); [Agent 065](#agent-065); [Agent 066](#agent-066).

**Interface handoff:** Own C12 ExportRequest/ExportOption/ExportOutcome and the app export coordinator. 001 delegates actions,073/070 present options,026 owns external I/O and 090 validates outputs.

**Start immediately:** Replace the extension whitelist concept with a typed catalog backed initially by current GLB/PLY/PNG/JSON outputs; add new serializers as they pass independent validation.

## Agent 068

**Role:** Bounded model loading and GLB parser hardening

**Branch:** `agents/068-model-loader`

**Priority:** P0

**Status receipt:** `docs/agents/status/068.md`

**Mission:** Keep model loading safe and correct as exported geometry, atlases and studio capabilities grow.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt](app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt) already bounds file/image size, checks PNG CRC and rejects external model data, but supports a restricted in-memory asset subset.
- [app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt](app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt) reads the entire accepted asset before viewing; production profiles and richer exported assets need coordinated loader budgets.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/viewer/loading/`

**Implementation instructions:**

1. Document the supported GLB contract and validate chunks/accessor ranges, strides, indices, dimensions, PNG structure and integer arithmetic before allocation.
2. Add bounded staged or streaming reads where useful; budget CPU image/geometry copies and decoded GPU upload data together with 030/069.
3. Maintain explicit rejection of external URI resolution and unsupported extensions; do not silently drop primitives, materials or transforms.
4. Expose immutable validated assets/errors through 061 representation adapters and exercise parser failures with 082 fuzz corpora.

**Success criteria:**

- Generated current and new 061 GLBs load with identical geometry, UV orientation and image dimensions within the supported contract.
- Malformed/truncated/oversized/overlapping buffers and decompression-bomb inputs fail without crash, unbounded allocation or external access.
- Repeated open/cancel/close cycles release CPU assets and integrate with renderer lifecycle; supported maximum assets stay within measured budget.

**Required verification:** ANDROID; DEVICE; FUZZ; INTEROPERABILITY.

**Integration inputs and cooperating owners:** [Agent 030](#agent-030); [Agent 061](#agent-061); [Agent 082](#agent-082).

**Interface handoff:** Own C13 ValidatedAsset loading and PhotographicGlb. 069 consumes validated buffers and 070 owns UI; parser security changes are implemented here even when found by 082/096.

**Start immediately:** Use the existing parser and viewer fixtures for a malformed-input corpus and profile current allocation paths before expanding support.

## Agent 069

**Role:** Production Filament renderer and GPU lifecycle

**Branch:** `agents/069-studio-renderer`

**Priority:** P1

**Status receipt:** `docs/agents/status/069.md`

**Mission:** Implement the production model renderer with correct photographed appearance, resource control and a dependable supported fallback.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt](app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt) is an unlit GLES3 photographic viewer with bounded buffers and lifecycle tests.
- [PROJECT_PLAN.md](PROJECT_PLAN.md) specifies a production Filament studio, which is not present in app/build.gradle.kts.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt`
- `app/src/androidTest/java/com/realityforge/scanner/PhotographicViewerTest.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/viewer/filament/`
- `app/src/main/java/com/realityforge/scanner/viewer/RenderBackend.kt`

**Implementation instructions:**

1. Integrate a pinned/licensed Filament build through 004/095 and define a RenderBackend adapter around validated 068 assets.
2. Support faithful unlit photographic inspection plus clearly labeled neutral-lit geometry inspection, normals, wireframe/coverage overlays and available LODs without inventing PBR measurements.
3. Own swap-chain, surface, texture, vertex/index buffer and engine lifetime across rotation, backgrounding, context loss and repeated model changes.
4. Measure GPU/CPU residency, upload times and frame pacing; retain the verified GLES path where the production renderer is unsupported until compatibility is demonstrated.

**Success criteria:**

- Photographic reference pixels, UV orientation and geometry bounds match independent fixtures with explicit color-space handling.
- Repeated load/close/background/context-loss cycles produce no growing resource usage, stale rendering or fatal driver errors on qualified devices.
- The supported asset/profile matrix meets measured interaction budgets and falls back or refuses safely before exhausting GPU memory.

**Required verification:** ANDROID; DEVICE; COMPATIBILITY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 004](#agent-004); [Agent 030](#agent-030); [Agent 061](#agent-061); [Agent 065](#agent-065); [Agent 068](#agent-068); [Agent 095](#agent-095).

**Interface handoff:** Own C14 RenderBackend and GPU resources. 070 owns controls,068 validates files,065 supplies LODs,097 measures frame/startup behavior. New libraries require 095 provenance and 004 integration.

**Start immediately:** Wrap and characterize the current GLES renderer first; implement Filament behind the same concrete asset contract and compare actual output rather than replacing working code blindly.

## Agent 070

**Role:** Interactive studio controls and diagnostic viewers

**Branch:** `agents/070-studio-controls`

**Priority:** P1

**Status receipt:** `docs/agents/status/070.md`

**Mission:** Make all actual scan representations inspectable through responsive, accessible controls and truthful diagnostic overlays.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt](app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt) provides orbit/pinch/reset in a full-screen dialog; PointCloudViewer.kt and SurfaceMeshViewer.kt are separate diagnostic paths.
- [PROJECT_PLAN.md](PROJECT_PLAN.md) requires local model inspection and crop/orient/scale with production usability.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt`
- `app/src/main/java/com/realityforge/scanner/ui/PointCloudViewer.kt`
- `app/src/main/java/com/realityforge/scanner/ui/SurfaceMeshViewer.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/studio/`

**Implementation instructions:**

1. Integrate 068/069 model loading/rendering behind loading/failed/ready states; preserve point and untextured-mesh inspection when photographic output is unavailable.
2. Add predictable orbit/pan/zoom, fit/reset, orientation presets, clipping/section inspection where supported and clear representation switches.
3. Connect 066 non-destructive edit/measurement controllers,065 LOD selection and 067 export options without editing master data directly.
4. Provide buttons and semantic actions alongside gestures; handle insets, tablets, keyboard/accessibility navigation and lifecycle cancellation with 071/076/077/078.

**Success criteria:**

- Users can inspect sparse, dense, observed mesh and photographic output without confusing them, including unsupported/failed-renderer cases.
- Camera controls and edits remain stable through resizing/reopen; measured scale and unknown scale stay visibly distinct.
- Accessible controls perform the same essential actions as gestures, and repeated studio sessions pass lifecycle/performance tests.

**Required verification:** ANDROID; DEVICE; ACCESSIBILITY; PERFORMANCE.

**Integration inputs and cooperating owners:** [Agent 010](#agent-010); [Agent 065](#agent-065); [Agent 066](#agent-066); [Agent 067](#agent-067); [Agent 068](#agent-068); [Agent 069](#agent-069); [Agent 071](#agent-071); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Own C15 studio interaction state and the three existing viewer Composables. 069 only renders,066 only owns edit logic,071 handles navigation and 077 supplies semantics/testing guidance.

**Start immediately:** Improve and test controls against the current GLES/diagnostic viewers first; accept renderer/editor interfaces as additive providers so UI work begins immediately.

## Agent 071

**Role:** Application navigation and feature composition

**Branch:** `agents/071-app-navigation-composition`

**Priority:** P0

**Status receipt:** `docs/agents/status/071.md`

**Mission:** Turn the existing app shell into a coherent, fully wired capture-to-export flow while preserving all real implemented functionality.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):44-325 contains library, capture, calibration, processing, diagnostics, viewers, export launchers and destructive dialogs in one function.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):53-74 saves UI booleans but pending picker requests and selected project have no common durable route/operation model.

**Exclusive existing files:**

- `app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/navigation/`
- `docs/agents/status/071.md`
- `app/src/test/java/com/realityforge/scanner/agents/a071/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a071/`

**Implementation instructions:**

1. Define stable project-aware routes for library, capture, review, processing/recovery, studio, export and settings with restoration/back-navigation behavior.
2. Extract screen-specific composition by delegating to owners 072–079/028/014/070; retain only shared shell/navigation/activity-result integration in ScannerApp.
3. Bind document/camera callbacks to owner 001 immutable pending requests; prevent stale results, duplicate launches and loss of operation identity during recreation.
4. Integrate feature screens, actual services, shared design, localization and accessibility; move engineering milestone prose into appropriate diagnostics/development context.

**Success criteria:**

- The real app completes create/import/capture/review/reconstruct/studio/export/reopen through wired routes with no dead buttons or fixture-only screens.
- Back, rotation, process recreation and returning activity results preserve correct project/operation identity without opening an unrelated model.
- All migrated baseline capabilities remain reachable and regression-tested; route navigation and user-facing state match persisted operations.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; 080 integrated UI end-to-end gate.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 006](#agent-006); [Agent 014](#agent-014); [Agent 024](#agent-024); [Agent 028](#agent-028); [Agent 067](#agent-067); [Agent 070](#agent-070); [Agent 072](#agent-072); [Agent 073](#agent-073); [Agent 074](#agent-074); [Agent 075](#agent-075); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078); [Agent 079](#agent-079).

**Interface handoff:** Own only root composition/routes and launcher plumbing; accept immutable screen state/actions from 001 and feature composables from designated owners. Never duplicate repository/work execution.

**Start immediately:** Map existing routes/actions and implement the navigation adapter with baseline screens now; progressively replace them with real feature implementations before final verification.

## Agent 072

**Role:** Production project library screen

**Branch:** `agents/072-project-library-ui`

**Priority:** P1

**Status receipt:** `docs/agents/status/072.md`

**Mission:** Provide a usable offline project library that accurately represents scan availability, progress and recoverable failures as collections grow.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):91-112 contains only create plus createdAt-sorted basic cards with state/message/date.
- [app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt](app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt):79-83 creates named projects but existing UI has no search, rename, duplicate or recoverable cleanup state.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/library/`
- `docs/agents/status/072.md`
- `app/src/test/java/com/realityforge/scanner/agents/a072/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a072/`

**Implementation instructions:**

1. Implement bounded project-list rendering, loading/empty/error states and search/filter/sort controls consuming owner 019 queries.
2. Add create/rename/duplicate/delete actions with validation, progress and confirmation appropriate to preserved originals and pending deletion.
3. Show meaningful result representations, selected photo counts, last-completed availability and interrupted/failed jobs without exposing internal milestone labels as primary state.
4. Wire open/restore/import-archive actions through 071/001 and provide accessible feature-prefixed resources and design-system components.

**Success criteria:**

- Large project fixtures scroll/filter/sort within a declared UI frame budget and maintain stable item identity and restored position.
- Actual create/rename/duplicate/delete/reopen actions update persisted library state, and editing/deleting one duplicate leaves the other unchanged.
- TalkBack, keyboard, large text and rotation preserve clear destructive scope, recoverable errors and access to previous completed results.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; 080 library management flow.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 006](#agent-006); [Agent 019](#agent-019); [Agent 022](#agent-022); [Agent 024](#agent-024); [Agent 025](#agent-025); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Expose LibraryScreen(LibraryUiState, LibraryAction) to 071; 019 owns queries/commands, 022 owns deletion, 001 routes operations. Own only ui/library and prefixed resources.

**Start immediately:** Build library composition and interaction tests with representative immutable states now; integrate real repository flows and commands before completion.

## Agent 073

**Role:** Photo review, project detail and recapture actions

**Branch:** `agents/073-photo-review-project-ui`

**Priority:** P0

**Status receipt:** `docs/agents/status/073.md`

**Mission:** Make photo review an actionable stage where users inspect images, curate reconstruction inputs, understand failures and prepare a valid scan.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt): 338-352 PhotoRow is a 72 dp thumbnail plus metadata/exposure text without zoom or curation actions.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt): 125-160,167-197 mixes calibration/processing/evidence cards but does not link bad photos to corrective selection or retakes.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/photoreview/`
- `docs/agents/status/073.md`
- `app/src/test/java/com/realityforge/scanner/agents/a073/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a073/`

**Implementation instructions:**

1. Build a bounded thumbnail grid/list and full-photo review with zoom, source metadata, measured blur/exposure and known calibration compatibility.
2. Integrate include/exclude/reorder/remove/undo actions from 018 with clear original-preservation semantics and immediate selected-input count updates.
3. Present calibration workflow/results from 027 and existing structured diagnostics with links to affected photos/cameras and meaningful recapture actions.
4. Launch processing, capture and viewers through 071/001/070; do not duplicate decoder buffers, reconstruction state or numerical quality heuristics.

**Success criteria:**

- A real mixed-photo project can inspect/exclude/reorder poor inputs and reconstruct using exactly the resulting persisted selection.
- Calibration/quality failures identify concrete affected inputs or unknown evidence without claiming complete object coverage from texture statistics.
- Large images/collections, rotation and repeated review navigation remain within measured thumbnail/bitmap budgets and expose all actions accessibly.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; 080 photo curation/calibration/reconstruction flow.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 006](#agent-006); [Agent 012](#agent-012); [Agent 013](#agent-013); [Agent 018](#agent-018); [Agent 024](#agent-024); [Agent 027](#agent-027); [Agent 070](#agent-070); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Expose ProjectReviewScreen with typed photo/quality/calibration state/actions; 018 performs curation, 027 calibrates, 024 supplies verified results and 071 owns routes.

**Start immediately:** Implement photo review against baseline ScanPhoto/diagnostic adapters and bitmap-lifetime tests now; replace adapters with integrated typed state before final acceptance.

## Agent 074

**Role:** Processing progress, pause and recovery screen

**Branch:** `agents/074-processing-recovery-ui`

**Priority:** P0

**Status receipt:** `docs/agents/status/074.md`

**Mission:** Present the durable pipeline truthfully so users can pause, return, recover and inspect previous results without losing track of their scan.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt): 154-160 shows only stage count, progress bar and Stop at safe boundary.
- [app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt](app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt): 185-201 reduces interruption to a notice/state; no durable queued/retry/resume/previous-result experience exists.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/processing/`
- `docs/agents/status/074.md`
- `app/src/test/java/com/realityforge/scanner/agents/a074/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a074/`

**Implementation instructions:**

1. Define UI states for queued, running, paused, interrupted, failed, cancelled and completed jobs using owner 007/008 events and persisted identity.
2. Render measured stage counts, real cache reuse and resource constraints; use indeterminate progress when totals or timing are unknown.
3. Wire pause/resume/retry/cancel/open-previous-result actions with safe enablement, versioned input/settings display and notification permission/recovery routes.
4. Integrate structured errors and suggested corrections, keeping detailed technical evidence accessible without overwhelming the main progression.

**Success criteria:**

- A real background/process-killed job reconnects to matching persisted state after reopen and offers valid resume/retry actions without duplicate work.
- Displayed progress/cached stages/status match actual execution events; missing stages and unknown time never become fabricated completion percentages.
- Rapid repeated actions, changed inputs, rotation and notification entry cannot operate on a stale job or discard previous completed output.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; 080 interrupted/background reconstruction UI flow.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 007](#agent-007); [Agent 008](#agent-008); [Agent 010](#agent-010); [Agent 023](#agent-023); [Agent 024](#agent-024); [Agent 029](#agent-029); [Agent 030](#agent-030); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Expose ProcessingScreen(JobUiState, JobAction) to 071; 007/008 own durable execution and 001 validates user commands. Render 029 permission/action state without creating services.

**Start immediately:** Implement state rendering and action-availability tests using real-shaped baseline events now; finalize against actual durable execution and kill/restart instrumentation.

## Agent 075

**Role:** Offline onboarding and practical capture instructions

**Branch:** `agents/075-capture-onboarding-instructions`

**Priority:** P1

**Status receipt:** `docs/agents/status/075.md`

**Mission:** Explain the supported offline scanning workflow clearly enough that a new user can gather useful photographs and understand unsupported outcomes.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):54-55 offers two short capture sentences; ScannerApp.kt: 130 prints checkerboard instructions inline.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):98,142,207,306 exposes mixed milestone/development text rather than a coherent new-user capture guide.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/onboarding/`
- `app/src/main/assets/onboarding/`
- `docs/agents/status/075.md`
- `app/src/test/java/com/realityforge/scanner/agents/a075/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a075/`

**Implementation instructions:**

1. Create a short skippable, revisitable guide for choosing a rigid matte textured subject, moving around it with overlap, preserving optics and reviewing poor photos.
2. Explain calibration, arbitrary versus measured scale, unobserved/reflective/transparent surfaces and partial reconstruction in plain language tied to actual capabilities.
3. Provide offline checkerboard instructions/assets derived from the existing approved board, with correct inner-corner dimensions and no implied real-scale guarantee.
4. Integrate context-specific help with 014/017/027/073 and persist onboarding preferences through 079; avoid network links as a prerequisite for scanning.

**Success criteria:**

- An offline fresh-install walkthrough reaches a real capture/import session, and every instruction/help asset remains available in airplane mode.
- Content validation checks board geometry, capability descriptions and scale/coverage wording against implemented feature contracts and current evidence.
- Large text, TalkBack, Back/skip/revisit and process recreation make the guide usable without trapping or delaying returning users.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Offline fresh-install usability/content review.

**Integration inputs and cooperating owners:** [Agent 010](#agent-010); [Agent 014](#agent-014); [Agent 017](#agent-017); [Agent 027](#agent-027); [Agent 073](#agent-073); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078); [Agent 079](#agent-079).

**Interface handoff:** Expose OnboardingScreen and context-help components/action IDs to 071/014/073; 079 owns preference persistence and 027 owns calibration acceptance truth.

**Start immediately:** Draft offline help states/assets from PROJECT_PLAN.md and actual current capabilities now; reconcile all promised features with integrated implementation before acceptance.

## Agent 076

**Role:** Shared design system and adaptive layout primitives

**Branch:** `agents/076-adaptive-design-system`

**Priority:** P1

**Status receipt:** `docs/agents/status/076.md`

**Mission:** Give the app a consistent, responsive visual system that remains readable and operable across supported Android form factors.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):35-40 hard-codes one dark palette; layout is a single full-width LazyColumn.
- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):52-74 and ui/PhotographicModelViewer.kt:44-63 each define ad hoc sizing and text arrangements.

**Exclusive existing files:**

- `app/src/main/res/values/styles.xml`
- `app/src/main/res/drawable/ic_launcher.xml`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/designsystem/`
- `app/src/main/res/values/design_system.xml`
- `docs/agents/status/076.md`
- `app/src/test/java/com/realityforge/scanner/agents/a076/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a076/`

**Implementation instructions:**

1. Define semantic typography, color, spacing, shape and state tokens with system light/dark support and accessible contrast.
2. Implement reusable screen scaffolds, status/error/progress components, labelled controls and responsive width/column policies without editing feature screens.
3. Provide clear interaction states for destructive actions, partial results, unknown evidence and disabled capabilities; avoid decorative elements that imply unavailable scan coverage.
4. Coordinate migrations through 071/014/070/072–079 and resource owner 078, keeping APIs small and documenting only necessary usage contracts.
5. Integrate the real Android theme/style and launcher resources you own with 001; keep visual assets lightweight, licensed and consistent with actual product capabilities.

**Success criteria:**

- Representative screens render without clipped actions/text at small-phone, landscape and tablet sizes with 100% and 200% font scales.
- Contrast/touch-target checks pass for documented token states in light/dark themes and error/disabled/selection variants.
- Integrated feature screens consume the shared components without duplicate theme definitions or visual state disagreement.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Adaptive layout/theme screenshot review.

**Integration inputs and cooperating owners:** [Agent 078](#agent-078).

**Interface handoff:** Expose AppTheme and focused design components to screen owners; 071 alone replaces the old ScannerApp palette. Own only designsystem code and uniquely named design resources.

**Start immediately:** Implement reusable tokens/components and representative state previews now; screen owners can adopt them while final integrated screenshot checks run later.

## Agent 077

**Role:** Accessibility semantics and complete-flow verification

**Branch:** `agents/077-accessibility-verification`

**Priority:** P0

**Status receipt:** `docs/agents/status/077.md`

**Mission:** Make the actual capture/review/processing/studio/export workflow operable with assistive technologies and verify beyond basic label presence.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/PointCloudViewer.kt](app/src/main/java/com/realityforge/scanner/ui/PointCloudViewer.kt):39, ui/SurfaceMeshViewer.kt:65 and ui/PhotographicModelViewer.kt: 111 have descriptions but no accessible orbit/zoom equivalents.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt): 251-254 displays a separate switch and Wireframe text; progress/focus/large-text workflows lack dedicated accessibility tests.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/ui/accessibility/`
- `docs/agents/status/077.md`
- `app/src/test/java/com/realityforge/scanner/agents/a077/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a077/`

**Implementation instructions:**

1. Provide reusable semantic actions/state descriptions, grouped labelled controls and focus/announcement helpers for progress, errors and viewer interactions.
2. Define a screen-by-screen audit covering TalkBack, keyboard/switch access, zoom/orbit alternatives, touch targets, contrast, large fonts and RTL.
3. Implement dedicated accessibility instrumentation and submit exact scoped remediation requests to each screen owner; do not edit their files or mark gaps resolved by a helper alone.
4. Verify focus after navigation/dialogs, lifecycle recovery and dynamic progress changes, ensuring announcements are useful rather than continuously disruptive.

**Success criteria:**

- The integrated app's primary offline scan/export path completes through accessibility actions without requiring inaccessible pinch/drag-only gestures.
- Automated semantic/layout checks and a recorded TalkBack/keyboard review pass at 200% text with clear errors and destructive confirmations.
- Every remediation request is integrated by its owner and rechecked against the actual screen; unresolved hardware/manual evidence remains explicitly open.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Manual TalkBack/keyboard/switch-access device gate.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 014](#agent-014); [Agent 028](#agent-028); [Agent 070](#agent-070); [Agent 071](#agent-071); [Agent 072](#agent-072); [Agent 073](#agent-073); [Agent 074](#agent-074); [Agent 075](#agent-075); [Agent 076](#agent-076); [Agent 078](#agent-078); [Agent 079](#agent-079).

**Interface handoff:** Expose semantic helpers/test assertions and an evidence-backed remediation ledger; feature owners retain their source files. Coordinate with 080 for flows while owning accessibility-specific verification.

**Start immediately:** Audit existing semantics and implement baseline accessibility tests immediately; execute final acceptance after real screens and viewer controls are wired.

## Agent 078

**Role:** Localization resources and structured user messages

**Branch:** `agents/078-localization-resources`

**Priority:** P1

**Status receipt:** `docs/agents/status/078.md`

**Mission:** Make all shipped user-facing content localizable with correct formatting, plurals, locale behavior and accessible wording.

**Why this work remains:**

- [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml) contains only app_name and an outdated M01 milestone label.
- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt), ui/CameraScreen.kt and ScannerViewModel.kt hard-code nearly all user text, formatting and failure messages in English.

**Exclusive existing files:**

- `app/src/main/res/values/strings.xml`

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/localization/`
- `app/src/main/res/values/localization.xml`
- `docs/agents/status/078.md`
- `app/src/test/java/com/realityforge/scanner/agents/a078/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a078/`

**Implementation instructions:**

1. Define stable feature-prefixed resource-key conventions and a structured user-message resolver compatible with owner 010 errors/evidence.
2. Migrate common resources and supply exact replacement instructions to source owners; each feature owns its unique English resource XML, avoiding shared-file collisions.
3. Handle plural counts, measurement/date/number formatting and text direction; remove stale milestone text from normal UI without concealing technical diagnostics.
4. Enable pseudo-locale/RTL verification through build owner 004 and add completeness/format checks; do not require invented translations or claim unreviewed language support.

**Success criteria:**

- A resource/source audit finds no unjustified hard-coded user-facing sentences in integrated primary screens or transient operation errors.
- Pseudo-localized long text and RTL runs show no missing resources, format crashes, clipped critical controls or reversed measurement meaning.
- Structured errors preserve technical evidence separately while resolving actionable localized UI messages consistently across screens/notifications.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Pseudo-locale/RTL and resource completeness gate.

**Integration inputs and cooperating owners:** [Agent 004](#agent-004); [Agent 010](#agent-010).

**Interface handoff:** Expose UserMessageResolver and formatting/key contracts to all UI, notification and controller owners. Own original strings.xml/common localization resources; feature-prefixed files remain with their feature owners.

**Start immediately:** Inventory baseline strings and define key/message contracts now so feature agents can author compliant resources immediately; verify the final merged app later.

## Agent 079

**Role:** Preferences, supported profiles and about/privacy screen

**Branch:** `agents/079-preferences-about-privacy-ui`

**Priority:** P1

**Status receipt:** `docs/agents/status/079.md`

**Mission:** Provide reliable local preferences and clear capability/privacy information tied to the app actually shipped.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt](app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt):59 keeps fallback focal length only in rememberSaveable; no durable user-preferences/settings screen exists.
- [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml) and res/xml/data_extraction_rules.xml enforce offline/no-backup defaults that need accurate user-facing explanation.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/main/java/com/realityforge/scanner/preferences/`
- `app/src/main/java/com/realityforge/scanner/ui/preferences/`
- `docs/agents/status/079.md`
- `app/src/test/java/com/realityforge/scanner/agents/a079/`
- `app/src/androidTest/java/com/realityforge/scanner/agents/a079/`

**Implementation instructions:**

1. Define versioned local user preferences for approved processing/display defaults, onboarding and accessibility-compatible settings, with safe migration and validation.
2. Expose only profiles supported by owner 030/engine evidence; changing defaults must not silently mutate an active project's recorded reconstruction settings.
3. Build settings/about/privacy surfaces showing current version, offline behavior, original preservation/uninstall consequences, explicit provider exports and notices links/assets.
4. Route storage management, calibration help and diagnostic views through 028/075/071; preserve no-network/no-analytics defaults and keep owner-managed licensing notices authoritative.

**Success criteria:**

- Preferences survive process recreation/update, reject corrupt/unsupported values and keep active job settings/identity unchanged.
- Fresh-install/airplane-mode settings/about/privacy routes function entirely locally and describe actual manifest/backup/export behavior accurately.
- Profile/settings selection is accessible, localized and reflected in the next actual operation without claiming unsupported quality or background behavior.

**Required verification:** ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; Offline preferences persistence/privacy consistency gate.

**Integration inputs and cooperating owners:** [Agent 004](#agent-004); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 028](#agent-028); [Agent 030](#agent-030); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078).

**Interface handoff:** Expose PreferencesStore and PreferencesScreen to 001/071/075; 009 snapshots chosen settings into input identity, 030 supplies supported plans. Build/notice owners supply authoritative version/licenses.

**Start immediately:** Implement small validated preference schemas and baseline about/privacy content now; integrate real supported-profile and storage routes before completion.

## Agent 080

**Role:** Actual Android UI end-to-end and interruption tests

**Branch:** `agents/080-actual-ui-end-to-end`

**Priority:** P0

**Status receipt:** `docs/agents/status/080.md`

**Mission:** Verify the complete user-visible app through actual UI interactions, document providers and process/lifecycle interruptions on the final integrated code.

**Why this work remains:**

- [app/src/androidTest/java/com/realityforge/scanner/SessionReliabilityTest.kt](app/src/androidTest/java/com/realityforge/scanner/SessionReliabilityTest.kt):39-112 exercises the actual ViewModel/pipeline but invokes controller actions directly for key steps.
- [app/src/androidTest/java/com/realityforge/scanner/DepthCheckpointDeviceTest.kt](app/src/androidTest/java/com/realityforge/scanner/DepthCheckpointDeviceTest.kt):16-62 reuses persisted files within a test, rather than killing/relaunching the full application.

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `app/src/androidTest/java/com/realityforge/scanner/uiflow/`
- `tools/android-ui-e2e/`
- `docs/agents/status/080.md`

**Implementation instructions:**

1. Add deterministic UI fixtures that consume 088's shared test document provider using explicitly synthetic photographs; traverse create/import/review/process/studio/export/reopen through real controls.
2. Cover permission denial/regrant, late camera callbacks, changed inputs, cancellation, repeated actions, rotation, background/lock and external picker return with stable operation identity.
3. Build UI scenario adapters over 088's shared external process-kill/relaunch controller and assert artifact/revision outcomes; coordinate CI/dependency registration with 004 without duplicating the controller or editing shared build files.
4. Add physical-device capture flow entrypoints and evidence collection without labelling synthetic/emulator success as real-world geometry or thermal validation.

**Success criteria:**

- The integrated real UI imports/reconstructs/views/exports/reopens known fixtures with exact exported/original hashes and no uncaught crash or stale result.
- Process-kill/picker/camera/provider fault scenarios recover to valid persisted state, preserving originals and previous completed output without duplicate publication.
- Head-specific test reports contain device/API/ABI, commands, failures and genuine screenshots; unsupported hardware gates remain explicitly pending.

**Required verification:** ./gradlew :app:assembleDebug :app:lintDebug; ./gradlew :app:connectedDebugAndroidTest; External process-kill/relaunch UI gate; Physical camera/permission/user-flow gate.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 004](#agent-004); [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 007](#agent-007); [Agent 008](#agent-008); [Agent 011](#agent-011); [Agent 014](#agent-014); [Agent 018](#agent-018); [Agent 023](#agent-023); [Agent 024](#agent-024); [Agent 025](#agent-025); [Agent 026](#agent-026); [Agent 027](#agent-027); [Agent 029](#agent-029); [Agent 030](#agent-030); [Agent 067](#agent-067); [Agent 070](#agent-070); [Agent 071](#agent-071); [Agent 072](#agent-072); [Agent 073](#agent-073); [Agent 074](#agent-074); [Agent 075](#agent-075); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078); [Agent 079](#agent-079).

**Interface handoff:** Own UI-flow tests/orchestration and evidence only; request product fixes from assigned file owners. Consume 077 accessibility helpers while independently proving full action-to-artifact paths. 088 owns shared fault injection and process-control infrastructure; 080 owns the real UI scenarios that consume it.

**Start immediately:** Automate the existing baseline UI and capture its regressions now; expand continuously as feature branches integrate and rerun against the final head.

## Agent 081

**Role:** Host numerical regression and sanitizer harness

**Branch:** `agents/081-host-numerical-sanitizers`

**Priority:** P0

**Status receipt:** `docs/agents/status/081.md`

**Mission:** Turn the existing valuable native tests into reproducible numerical and memory-safety gates for the current revision. Preserve calibrated and synthetic distinctions, existing acceptance thresholds, and the CPU reference; optional GPU acceleration is outside this assignment.

**Why this work remains:**

- CMakeLists.txt:3,14–16 — RF_SANITIZE exists; :19–51 defines eleven CTest targets
- [.github/workflows/verify.yml](.github/workflows/verify.yml):22–38 — one Release compiler job; no sanitizer matrix
- reports/validation-summary.json:3–10 — historical M01 sanitizer evidence

**Exclusive existing files:**

- `tests/test_pipeline.py`
- `tools/photo_pair.py`
- `tools/three_view.py`
- `tools/bundle_three_view.py`
- `tools/benchmark_stereo_kernel.py`

**Reserved new files/packages:**

- `tools/qa/native_regression/`
- `tests/test_native_regression_harness.py`
- `.github/workflows/native-sanitizers.yml`

**Implementation instructions:**

1. Implement a bounded runner for GCC/Clang Release and ASan/UBSan builds, CTest, Python integration and existing benchmark commands, using separate build directories.
2. Record compiler versions, source and binary hashes, commands, exit codes, numeric metrics and failing seeds in machine-readable evidence.
3. Compare deterministic outputs with tolerances justified by algorithm contracts; retain per-scene failures and reject nonfinite results.
4. Add reproductions for actual numerical or lifetime defects and hand runtime fixes to their algorithm owners; request shared CMake registration from 004.

**Success criteria:**

- Both compiler configurations execute existing native tests, with current logs and no unexplained sanitizer failures or suppressed regressions.
- A deliberately failing numerical fixture and a failing subprocess cause nonzero harness exits while preserving diagnostic evidence.
- Missing compiler/dependency support is reported NOT_RUN; historical reports never satisfy a current-commit gate.

**Required verification:** CMake/CTest; pytest; GCC + Clang; ASan/UBSan.

**Integration inputs and cooperating owners:** 004 for shared build registration; native owners for discovered runtime fixes.

**Interface handoff:** Publish stable JSON evidence to 093 and 099; send minimal failure inputs and source locations to 031–060.

**Start immediately:** Build the runner around baseline commands and small analytic fixtures immediately; no future reconstruction feature is required.

## Agent 082

**Role:** Native, JNI and parser fuzzing harness

**Branch:** `agents/082-native-jni-parser-fuzzing`

**Priority:** P0

**Status receipt:** `docs/agents/status/082.md`

**Mission:** Exercise malformed, adversarial and boundary inputs through real parsers and native entry points, with reproducible failures and strict resource limits. Strengthen evidence without editing algorithm, storage or GLB parser files owned by other agents.

**Why this work remains:**

- [reconstruction/src/jni.cpp](reconstruction/src/jni.cpp) — native array/argument bridge
- [reconstruction/src/depth_checkpoint.cpp](reconstruction/src/depth_checkpoint.cpp) — binary checkpoint reader
- [app/src/androidTest/java/com/realityforge/scanner/NativeSmokeTest.kt](app/src/androidTest/java/com/realityforge/scanner/NativeSmokeTest.kt): 168–224 — invalid-buffer/token regressions
- [app/src/androidTest/java/com/realityforge/scanner/PhotographicViewerTest.kt](app/src/androidTest/java/com/realityforge/scanner/PhotographicViewerTest.kt):89–117 — malformed GLB cases

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `reconstruction/fuzz/`
- `tools/qa/fuzz/`
- `app/src/androidTest/java/com/realityforge/scanner/qa/FuzzBridgeContractTest.kt`
- `.github/workflows/native-fuzz.yml`

**Implementation instructions:**

1. Create standalone sanitizer-enabled native fuzz targets for numeric buffers, checkpoints and export structures; request build wiring through 004.
2. Add bounded Android bridge tests for length mismatches, invalid handles, nonfinite values, cancellation races and repeated calls.
3. Maintain compact licensed/generated seed corpora and minimize discovered failures; label synthetic test data explicitly.
4. Provide scheduled and short CI fuzz modes with reproducible seeds, time/memory budgets and crash artifact retention; forward source fixes to the relevant owner.

**Success criteria:**

- Documented smoke commands execute current native implementations under sanitizers and complete within declared test-resource bounds.
- Known invalid buffers and corrupted seeds return supported failures without crashes, uncontrolled allocations, stale-token reuse or published partial models.
- Every discovered crash has a minimized reproduction and owner; unavailable Android execution stays NOT_RUN rather than a host-only pass.

**Required verification:** Native sanitizer fuzz smoke; Android JNI instrumentation; Corpus regression tests.

**Integration inputs and cooperating owners:** 004 build integration; 068 for GLB-parser fixes; Native/storage owners for runtime fixes.

**Interface handoff:** Deliver corpus formats and reproductions to 096, native owners and 068; emit exact-build evidence for 093/099.

**Start immediately:** Use current C ABI, JNI and parser contracts to construct seeds and executable harnesses now; future implementations add adapters later.

## Agent 083

**Role:** Licensed real benchmark corpus and fixture tooling

**Branch:** `agents/083-licensed-benchmark-corpus`

**Priority:** P0

**Status receipt:** `docs/agents/status/083.md`

**Mission:** Create a reproducible, permission-aware corpus interface for independent real-object evaluation. Supply tools and honest input manifests rather than inventing scans or committing massive private capture archives to Git.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md) — phases 2–3 require licensed real datasets/reference geometry
- [tools/benchmark_middlebury.py](tools/benchmark_middlebury.py):4–7,125–145 — one real stereo pair and explicit limits
- benchmarks/fixtures/relief/calibration.json — existing synthetic fixture
- [tools/capture_audit.py](tools/capture_audit.py): 121–127 — audit does not prove reconstruction

**Exclusive existing files:**

- `tools/make_fixture.py`

**Reserved new files/packages:**

- `benchmarks/corpus/`
- `tools/qa/corpus/`
- `tests/test_benchmark_corpus.py`

**Implementation instructions:**

1. Define validated manifests containing dataset identity, input hashes, capture lens/settings, calibration, independent geometry, units, scene difficulty and permitted use/redistribution.
2. Provide local private-data discovery and checksum verification plus explicit authorized acquisition instructions; keep raw captures outside Git by default.
3. Separate synthetic regression, development real scenes and held-out evaluation; include matte textured objects, thin parts and adverse glossy/transparent/low-texture cases.
4. Generate small deterministic synthetic fixtures for validator tests and reject missing provenance, tampering, duplicate photos and inconsistent calibration metadata.

**Success criteria:**

- A clean environment can validate supplied manifests and reproduce compact fixtures without uploading any photographs.
- Missing real captures or licenses produce BLOCKED_EXTERNAL/NOT_RUN records and cannot count toward physical or quality acceptance.
- Tampered inputs, invalid units and unknown ground-truth provenance fail validation; authorized real manifests resolve to exact local content hashes.

**Required verification:** Corpus schema tests; Checksum/provenance validation; Synthetic fixture reproducibility.

**Integration inputs and cooperating owners:** 027 calibration contract; 086 authorized physical captures; 095 license inventory review.

**Interface handoff:** Publish manifest schema and fixture API early to 084–090 and 099; preserve physical/synthetic distinctions in every handoff.

**Start immediately:** Implement schemas, validator and synthetic tests from existing fixtures immediately; document precise authorized real inputs still needed.

## Agent 084

**Role:** Full-object geometric quality benchmarks

**Branch:** `agents/084-full-object-geometry-quality`

**Priority:** P0

**Status receipt:** `docs/agents/status/084.md`

**Mission:** Measure camera registration, geometric accuracy, observable completeness and topology independently on entire real objects. Prevent favorable retained-point errors or triangle counts from hiding missing sides, duplicates and disconnected patches.

**Why this work remains:**

- [tools/benchmark_dense_middlebury.py](tools/benchmark_dense_middlebury.py):53–67 — two-camera dense benchmark
- [tools/benchmark_surface_middlebury.py](tools/benchmark_surface_middlebury.py):52–69 — partial stereo surface metrics
- [tools/benchmark_multiview_coverage.py](tools/benchmark_multiview_coverage.py):80–88 — synthetic observable-grid gates
- [docs/NEXT_MILESTONE.md](docs/NEXT_MILESTONE.md) — full-object accuracy/topology evidence remains open

**Exclusive existing files:**

- `tests/test_real_benchmark.py`
- `tools/benchmark_middlebury.py`
- `tools/benchmark_dense_middlebury.py`
- `tools/benchmark_surface_middlebury.py`
- `tools/benchmark_multiview_coverage.py`

**Reserved new files/packages:**

- `tools/qa/geometry_quality/`
- `tests/test_geometry_quality_metrics.py`
- `benchmarks/quality_protocols/geometry.json`

**Implementation instructions:**

1. Implement documented similarity/gauge alignment and independent camera-registration, drift and per-scene failure metrics without feeding ground truth into reconstruction.
2. Compute bidirectional surface distances, eligible visible-region completeness, outlier percentiles, connected components, boundaries, nonmanifold defects and duplicate/self-intersecting geometry.
3. Validate metrics using analytic shapes with deliberately removed sides, thin components, overlapping patches, scale changes and known pose errors.
4. Run baseline stereo/synthetic regressions alongside authorized full-object scenes, preserve held-out inputs, and attach exact engine/settings/data hashes.

**Success criteria:**

- Analytic fixtures return expected tolerances; deleting difficult surfaces worsens completeness even when remaining points are accurate.
- Reports retain individual failed objects, distinguish points/open patches/manifold outputs, and never relabel arbitrary scale as measured metric scale.
- Real-object gates run on approved independent references; absent captures remain BLOCKED_EXTERNAL and baseline stereo success cannot substitute.

**Required verification:** Metric unit tests; Existing geometry benchmark regressions; Authorized full-object evaluation.

**Integration inputs and cooperating owners:** 083 manifest contract; 031–060 result contracts; 086 physical captures.

**Interface handoff:** Provide measurable regressions and minimal failures to native owners; deliver artifact-bound quality evidence to 099.

**Start immediately:** Develop metric code and analytic failure fixtures against current exports now; integrate real manifests and improved algorithms when available.

## Agent 085

**Role:** Photographic texture quality benchmarks

**Branch:** `agents/085-photographic-texture-quality`

**Priority:** P0

**Status receipt:** `docs/agents/status/085.md`

**Mission:** Evaluate photographic appearance across complete observed objects with independently checked UVs, seams, visibility and color behavior. Distinguish coverage of existing triangles from reconstruction of the object and preserve visibly unknown regions.

**Why this work remains:**

- [tools/benchmark_texture_middlebury.py](tools/benchmark_texture_middlebury.py):2–5 — two-view photographic benchmark
- [tools/benchmark_texture_middlebury.py](tools/benchmark_texture_middlebury.py): 126–133 — face/seam/exposure regression gates
- [docs/M10_VALIDATION.md](docs/M10_VALIDATION.md):32,62–63 — no seamless/full-object claim
- [app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt](app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt):88–91 — current encoded-sRGB diagnostic path

**Exclusive existing files:**

- `tools/benchmark_texture_middlebury.py`

**Reserved new files/packages:**

- `tools/qa/texture_quality/`
- `tests/test_texture_quality_metrics.py`
- `benchmarks/quality_protocols/texture.json`

**Implementation instructions:**

1. Create independent metrics for UV bounds/distortion, missing or overlapping charts, seam discontinuities, texture density and supported face coverage.
2. Add calibrated checkerboard/color fixtures detecting vertical flips, wrong color space, incorrect alpha, texture substitution and camera-view projection errors.
3. Support held-out photograph comparisons with occlusion masks and documented alignment; keep reference images out of reconstruction inputs for that evaluation.
4. Run current Middlebury gates unchanged and emit per-scene appearance reports plus compact visual review artifacts with source/output hashes.

**Success criteria:**

- Known flipped UVs, missing textures, opaque unknown areas and exposed atlas seams produce meaningful failures or measured regressions.
- Better scores cannot be obtained by excluding hard triangles without reporting decreased support; reports separate geometry and appearance limitations.
- Authorized multi-side scenes execute against the candidate; missing physical/reference data remains BLOCKED_EXTERNAL/NOT_RUN.

**Required verification:** Texture metric tests; Existing photographic benchmark gates; Held-out appearance evaluation.

**Integration inputs and cooperating owners:** 083 dataset manifests; Texturing owners within 051–060; 069 renderer contract.

**Interface handoff:** Send concrete UV/color/seam failures to texturing and renderer owners; share validation fixtures with 090 and evidence with 099.

**Start immediately:** Implement metric fixtures and adapters for existing GLB/PNG outputs immediately; await real held-out scenes only for their final evidence.

## Agent 086

**Role:** Physical ARM64 camera-capture validation

**Branch:** `agents/086-physical-arm64-capture-validation`

**Priority:** P0

**Status receipt:** `docs/agents/status/086.md`

**Mission:** Produce an executable, repeatable physical-camera qualification procedure for real ARM64 phones. Validate photographs and calibration through the app, while keeping emulator success and manufactured image fixtures separately labeled.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt](app/src/main/java/com/realityforge/scanner/ui/CameraScreen.kt):36–50,64–74 — CameraX JPEG capture and limitations
- [.github/workflows/verify.yml](.github/workflows/verify.yml):97–103 — API 35 x86_64 emulator only
- [docs/NEXT_MILESTONE.md](docs/NEXT_MILESTONE.md) — authorized same-lens physical captures required
- [docs/M19_SCAN_EVIDENCE.md](docs/M19_SCAN_EVIDENCE.md):59–60 — physical scans remain unverified

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `tools/device/capture_validation/`
- `app/src/androidTest/java/com/realityforge/scanner/qa/PhysicalCaptureContractTest.kt`
- `docs/device-protocols/physical-capture.md`

**Implementation instructions:**

1. Build device-selection, build-fingerprint and capture-session evidence tooling covering camera permission denial, interrupted capture, rotation, selected lens and saved-original identity.
2. Specify authorized matte textured object sessions with same-lens calibration, multiple heights/viewpoints, independent reference geometry and consistent lighting.
3. Verify actual JPEG/EXIF/orientation, focus/exposure behavior and local import-to-reconstruction flow; hand application defects to capture owners and calibration defects to 027.
4. Store only manifests, hashes and small approved reports in Git; provide explicit local/private archive paths and commands for authorized operators.

**Success criteria:**

- The harness rejects emulator-only evidence for physical gates and records actual ARM64 device, camera/lens, OS, app hash and input identities.
- At least two authorized real-object sessions follow the protocol and produce original-byte-preserving capture manifests with independently verifiable calibration metadata.
- When hardware or authorized scenes are unavailable, finish runnable tooling/reproduction steps and report BLOCKED_EXTERNAL/NOT_RUN without invented photographs or metrics.

**Required verification:** Device harness tests; Physical CameraX capture; Calibration/input audit.

**Integration inputs and cooperating owners:** 011–026 capture implementations; 027 calibration; 083 manifests; 008 permission/lifecycle integration.

**Interface handoff:** Provide approved local capture bundles/manifests to 083–085 and 087–090; route runtime fixes to file owners.

**Start immediately:** Implement discovery, evidence schema, instrumented contracts and operator protocol using baseline capture today; reserve actual handset results explicitly.

## Agent 087

**Role:** Physical memory, thermal and power profiling

**Branch:** `agents/087-physical-resource-profiling`

**Priority:** P0

**Status receipt:** `docs/agents/status/087.md`

**Mission:** Measure actual sustained resource behavior on supported ARM64 devices and feed honest budgets into scheduling and image handling. Keep measurement-only hooks out of production user flows and never add remote analytics.

**Why this work remains:**

- [README.md](README.md):41–46 — bounded inputs are not measured phone resource budgets
- [docs/NEXT_MILESTONE.md](docs/NEXT_MILESTONE.md) — M20 physical RSS/thermal/power gate
- [app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt](app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt):58–96 — CPU/GPU image and buffer allocations
- [tools/benchmark_stereo_kernel.py](tools/benchmark_stereo_kernel.py):4 — host speed is not phone performance

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `tools/device/resource_profiling/`
- `app/src/debug/java/com/realityforge/scanner/qa/ResourceProbe.kt`
- `tests/test_resource_trace_analysis.py`
- `docs/device-protocols/resource-profiling.md`

**Implementation instructions:**

1. Implement timestamped collection of available process RSS/PSS, Java/native allocation signals, stage durations, storage pressure, thermal status and documented battery/power indicators.
2. Correlate bounded debug hooks with real process traces; disclose unsupported counters and avoid inferring precise energy from coarse battery percentages.
3. Run cold/warm and sustained reconstruction/viewer workloads, including current 12-sparse/eight-depth limits and authorized larger profiles only after implementation.
4. Document charging state, ambient context and device build; route identified allocation leaks, excessive concurrency or thermal behavior to 030 and implementation owners.

**Success criteria:**

- Trace parsers reject gaps, stale process IDs and impossible counters; fixture traces remain explicitly synthetic parser tests.
- Actual phone runs attach device/app/input/settings hashes and measured peaks, sustained timing and thermal events, with sampling limitations.
- Missing hardware or measurement APIs produce BLOCKED_EXTERNAL/NOT_RUN for affected claims; host allocations cannot certify phone budgets.

**Required verification:** Trace analysis tests; ARM64 resource runs; Sustained thermal/power protocol.

**Integration inputs and cooperating owners:** 030 scheduler; 086 authorized captures; 069 renderer; 004 test-only build hooks.

**Interface handoff:** Publish measured budget recommendations to 030 and resource owners; send candidate-bound evidence to 099.

**Start immediately:** Build samplers, trace validators and protocol immediately against baseline workload; physical evidence follows only actual execution.

## Agent 088

**Role:** Process-death, storage-fault and resume instrumentation

**Branch:** `agents/088-failure-recovery-instrumentation`

**Priority:** P0

**Status receipt:** `docs/agents/status/088.md`

**Mission:** Prove interrupted work recovers safely through actual process boundaries and storage failures. Test the real database/filesystem/pipeline rather than treating ViewModel recreation or repeated native calls as process-death validation.

**Why this work remains:**

- [app/src/androidTest/java/com/realityforge/scanner/DepthCheckpointDeviceTest.kt](app/src/androidTest/java/com/realityforge/scanner/DepthCheckpointDeviceTest.kt):38–61 — repeated calls within one process
- [app/src/androidTest/java/com/realityforge/scanner/DatabaseContractTest.kt](app/src/androidTest/java/com/realityforge/scanner/DatabaseContractTest.kt):30–38 — in-memory recovery test
- [docs/M18_DEPTH_CHECKPOINTS.md](docs/M18_DEPTH_CHECKPOINTS.md):31–32,46–47 — durability/process-kill limits
- [app/src/main/java/com/realityforge/scanner/MainActivity.kt](app/src/main/java/com/realityforge/scanner/MainActivity.kt):19 — onStop pause

**Exclusive existing files:**

- `app/src/androidTest/java/com/realityforge/scanner/SessionReliabilityTest.kt`

**Reserved new files/packages:**

- `tools/device/failure_recovery/`
- `app/src/androidTest/java/com/realityforge/scanner/qa/RecoveryStageContractTest.kt`
- `app/src/androidTest/java/com/realityforge/scanner/qa/FaultDocumentProvider.kt`
- `tests/test_recovery_controller.py`
- `app/src/androidTest/AndroidManifest.xml`

**Implementation instructions:**

1. Build an external controller that stops/kills and relaunches the target between import, registration, checkpoint, mesh/texture and final-publication stages.
2. Add test-only document-provider and bounded storage-fault cases for truncated/slow reads, revoked destinations, short writes, cancellation and controlled ENOSPC.
3. Verify original hashes, revision identities, completed artifacts, selective checkpoint reuse and cleanup across repeated sessions and installed-build upgrades.
4. Use disposable emulator/test storage for exhaustion; never fill a user's device indiscriminately. Send storage fixes to 005/data owners and scheduling/service fixes to 030/008.

**Success criteria:**

- Controller-driven separate process invocations demonstrate safe resume and never expose stale or partial artifacts as current results.
- Fault cases preserve originals and prior exports, disclose incomplete external writes and recover after conditions are restored.
- Emulator and physical outcomes are separate; absent hardware is BLOCKED_EXTERNAL, and unexecuted scenarios remain NOT_RUN.

**Required verification:** Recovery controller tests; Android failure instrumentation; Physical relaunch/upgrade protocol.

**Integration inputs and cooperating owners:** 005 persistence/schema; 008 service lifecycle; 030 scheduling; Checkpoint/data owners.

**Interface handoff:** Provide deterministic stage/fault hooks to source owners and exact-artifact reliability evidence to 099. Own the shared FaultDocumentProvider, process controller and androidTest manifest registrations for 026/080/092/099. Keep these components test-only and supply deterministic fixtures, not duplicate provider implementations per agent.

**Start immediately:** Develop the controller and bounded fault provider using existing M18 behavior now; future stages integrate through documented hooks.

## Agent 089

**Role:** Runtime offline and privacy verification

**Branch:** `agents/089-runtime-offline-privacy`

**Priority:** P0

**Status receipt:** `docs/agents/status/089.md`

**Mission:** Verify the final app's offline contract and user-data boundaries through executable package and runtime checks. Keep originals local, user-selected sharing explicit, and permission/disclosure statements matched to actual shipped behavior.

**Why this work remains:**

- [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml):4–13 — network removal and backup configuration
- [app/src/main/res/xml/data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml):3–16 — backup/transfer exclusions
- [docs/PRIVACY.md](docs/PRIVACY.md):17–24 — explicit provider destinations and incomplete release disclosures
- [app/src/androidTest/java/com/realityforge/scanner/OfflineCaptureBundleTest.kt](app/src/androidTest/java/com/realityforge/scanner/OfflineCaptureBundleTest.kt):25–76 — existing private-byte preservation test

**Exclusive existing files:**

- `docs/PRIVACY.md`
- `app/src/main/res/xml/data_extraction_rules.xml`

**Reserved new files/packages:**

- `tools/device/offline_privacy/`
- `app/src/androidTest/java/com/realityforge/scanner/qa/OfflinePrivacyJourneyTest.kt`
- `tests/test_privacy_evidence.py`

**Implementation instructions:**

1. Exercise clean installation, local capture/import, reconstruction, reopen and export with networking disabled and local document providers.
2. Inspect candidate merged permissions, backup/device-transfer rules and logs; test camera-optional use and no runtime model/library downloads.
3. Verify project deletion affects only intended private data and does not delete outside originals or existing user exports; examine diagnostic redaction.
4. Update privacy documentation from evidence, distinguishing app networking from chosen cloud providers and OS/device limitations; request manifest changes only through 008.

**Success criteria:**

- A complete offline journey executes against the recorded candidate hash; packaged dependencies initialize without network availability.
- Permission/backup tests reject intentionally noncompliant packages, and deletion tests prove external originals/exports remain byte-identical.
- Physical backup/transfer or offline scenarios not executed are NOT_RUN/BLOCKED_EXTERNAL; source manifests alone never count as runtime proof.

**Required verification:** Privacy evidence unit tests; Merged-package inspection; Offline Android journey.

**Integration inputs and cooperating owners:** 008 manifest/service owner; 091 candidate package validator; Data/export owners.

**Interface handoff:** Supply verified policy assertions to 095/098/099 and actionable defects to owning agents; add no analytics, accounts or upload.

**Start immediately:** Implement tests around the current offline manifest and repository flow immediately; finalize disclosures after candidate dependencies are known.

## Agent 090

**Role:** Independent exported-asset interoperability validators

**Branch:** `agents/090-export-interoperability-validation`

**Priority:** P0

**Status receipt:** `docs/agents/status/090.md`

**Mission:** Validate exported assets independently of the app's own parser and renderer. Establish truthful interchange behavior for initial-release formats while keeping point clouds, open surfaces, textured meshes and measured units distinct.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md) — phase 15 requires GLB/OBJ/PLY/STL target-tool imports
- [tools/benchmark_texture_middlebury.py](tools/benchmark_texture_middlebury.py):19–29 — bespoke local GLB parsing
- [app/src/androidTest/java/com/realityforge/scanner/PhotographicViewerTest.kt](app/src/androidTest/java/com/realityforge/scanner/PhotographicViewerTest.kt):80–117 — bounded supported GLB subset
- [README.md](README.md):48–52 — open geometry and arbitrary scale

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `tools/qa/export_interoperability/`
- `tests/test_export_interoperability.py`
- `benchmarks/export_fixtures/`
- `docs/quality-protocols/export-interoperability.md`

**Implementation instructions:**

1. Integrate pinned official/independent format validators and reproducible target-tool import checks, with dependency changes requested through 004/095.
2. Check indices, winding, normals, UVs, material/image references, units/axes, point-versus-triangle modes and embedded versus companion assets.
3. Create compact adversarial and known-good export fixtures that expose flipped textures, missing sidecars, invalid indices and incorrect scale metadata.
4. Exercise real outputs from export owners, preserve originals/source models and report unsupported formats until their required implementations actually land.

**Success criteria:**

- Known-invalid files fail an independent validator even if the app's parser accepts them; valid fixtures load with expected geometry/materials.
- Candidate GLB/OBJ/PLY/STL formats promised for release round-trip through documented target tools with orientation, representation and supported attributes preserved.
- Missing external tools or unimplemented formats are NOT_RUN/failed release requirements, not silently successful skipped checks.

**Required verification:** Validator wrapper tests; Independent format validation; Target-tool import/round-trip.

**Integration inputs and cooperating owners:** 061–067 export implementations; 068 GLB parser contract; 085 texture fixtures; 095 validator licenses.

**Interface handoff:** Return concrete failing assets to exporters and parser owners; expose package-format evidence to 099 without rewriting runtime exporters.

**Start immediately:** Implement wrappers and fixtures for baseline GLB/PLY immediately; add format adapters as the corresponding export contracts are available.

## Agent 091

**Role:** Release APK/AAB, signing and R8 validation

**Branch:** `agents/091-release-package-validation`

**Priority:** P0

**Status receipt:** `docs/agents/status/091.md`

**Mission:** Create a reproducible release-candidate build/validation path that exercises minified native functionality and actual delivered APK splits. Keep signing secrets external and distinguish prepared release infrastructure from permission to publish.

**Why this work remains:**

- app/build.gradle.kts:29–35 — R8 disabled, no signing setup
- [.github/workflows/verify.yml](.github/workflows/verify.yml):64–74 — Debug artifacts only
- [tools/check_apk.py](tools/check_apk.py):26–41 — existing INTERNET/alignment checks
- [app/proguard-rules.pro](app/proguard-rules.pro):1–2 — broad NativeCore/OpenCV keep rules

**Exclusive existing files:**

- `tools/check_apk.py`
- `app/proguard-rules.pro`

**Reserved new files/packages:**

- `gradle/release-validation.gradle.kts`
- `.github/workflows/release-candidate.yml`
- `tools/release/package_validation/`
- `tests/test_release_package_validation.py`
- `app/src/androidTest/java/com/realityforge/scanner/qa/ReleaseNativeContractTest.kt`

**Implementation instructions:**

1. Implement narrow tested R8/JNI/OpenCV rules in app/proguard-rules.pro and provide a release convention fragment for 004 to apply to shared Gradle/build files.
2. Extend APK checks for expected ABIs/libraries, release flags, permissions/backup rules, test assets, signatures and retained 16KB ZIP/ELF checks.
3. Add pinned bundletool AAB/split validation and minified install/reopen/reconstruct/export tests; retain mapping/native-symbol artifacts and exact hashes.
4. Create a manual candidate workflow using approved secret injection, test signing for dry runs and explicit refusal when production credentials are absent.

**Success criteria:**

- Release APK/AAB builds and generated splits validate; minified JNI/OpenCV reconstruction and model reopening execute without missing classes/resources.
- Invalid permission/alignment/signature fixtures fail; each real report identifies artifact hash, version and signing mode.
- Unavailable signing credentials or devices remain BLOCKED_EXTERNAL/NOT_RUN; no keys enter Git/logs and no store publication occurs without authorization.

**Required verification:** Release assemble/bundle/lint; APK/AAB validators; Minified Android instrumentation.

**Integration inputs and cooperating owners:** 004 shared build owner; 092 device matrix; 095 inventory/notices.

**Interface handoff:** Supply candidate artifacts/provenance to 092/099 and exact Gradle integration requests to 004; 008 owns manifest changes.

**Start immediately:** Implement validators and test-signed workflow against baseline now; final production signing waits for genuine approved credentials.

## Agent 092

**Role:** Android API, ABI and 16KB runtime matrix

**Branch:** `agents/092-android-compatibility-matrix`

**Priority:** P0

**Status receipt:** `docs/agents/status/092.md`

**Mission:** Prove declared Android compatibility across relevant OS, ABI and page-size environments. Preserve distinctions between native-library alignment, successful emulator execution and actual physical ARM64 camera/GPU behavior.

**Why this work remains:**

- app/build.gradle.kts:14–19 — API 29+, ARM64/x86_64 support
- [.github/workflows/verify.yml](.github/workflows/verify.yml):97–103 — API 35 x86_64 only
- tools/run-device-tests.sh:7,10,17 — hardcoded adb -e
- CMakeLists.txt:10–12 — Android 16KB link alignment flag

**Exclusive existing files:**

- `tools/run-device-tests.sh`
- `tools/check_screenshot.py`
- `tests/test_screenshot_artifact.py`

**Reserved new files/packages:**

- `.github/workflows/android-compatibility.yml`
- `tools/device/compatibility/`
- `app/src/androidTest/java/com/realityforge/scanner/qa/CompatibilityContractTest.kt`

**Implementation instructions:**

1. Parameterize the existing runner for an explicit device serial while retaining screenshot validation, reliable cleanup and failure propagation.
2. Define reproducible minimum/target API and ABI coverage plus available actual 16KB runtime configurations; record device build and measured page size.
3. Exercise native loading, bundled OpenCV, camera-optional import, GPU shader/model loading and representative reconstruction/export on candidate splits.
4. Pin workflow actions/tooling, bound execution and publish per-environment results; route manifest/build/runtime defects to 008/004 and source owners.

**Success criteria:**

- API/ABI environments promised for release execute against exact candidate hashes, with correct architecture and page-size evidence.
- Missing screenshots, native-load failures or unavailable required environments cannot be reported as success; unavailable hardware is BLOCKED_EXTERNAL/NOT_RUN.
- A package passing ZIP/ELF alignment alone never receives the runtime-16KB or physical-device acceptance label.

**Required verification:** Parameterized runner tests; Android API/ABI instrumentation; Actual 16KB runtime check.

**Integration inputs and cooperating owners:** 004 build matrix registration; 008 compatibility declarations; 091 candidate splits; 086 physical devices.

**Interface handoff:** Provide stable runner arguments to 093 and per-device package evidence to 099; keep physical captures with 086.

**Start immediately:** Implement device discovery, serial parameterization and emulator matrix now; record concrete instructions for missing physical environments.

## Agent 093

**Role:** Per-branch, PR and integration CI validation

**Branch:** `agents/093-parallel-branch-ci-validation`

**Priority:** P0

**Status receipt:** `docs/agents/status/093.md`

**Mission:** Make 100 independently developed branches reviewable without allowing untested combined code onto main. Preserve existing numerical, Android and device gates while attaching all results to the actual candidate commit.

**Why this work remains:**

- [.github/workflows/verify.yml](.github/workflows/verify.yml):3–6 — main pushes and PR trigger
- [.github/workflows/verify.yml](.github/workflows/verify.yml):75–77 — device excludes PRs
- [tests/test_project_contracts.py](tests/test_project_contracts.py):68–77 — hash-manifest gate
- [AGENTS.md](AGENTS.md) — host, Gradle and instrumentation evidence required

**Exclusive existing files:**

- `.github/workflows/verify.yml`

**Reserved new files/packages:**

- `tools/ci/validation/`
- `tests/test_ci_evidence_validation.py`
- `docs/ci/required-checks.md`

**Implementation instructions:**

1. Add appropriate PR/integration device execution, bounded concurrency/timeouts, cancellation of obsolete runs and strict failure for missing expected reports.
2. Consume parameterized runners and specialist workflows without duplicating their ownership; pin action revisions and preserve minimal permissions.
3. Validate exact source/artifact identities and test completion, separating synthetic, emulator and physical results in machine-readable summaries.
4. Document required checks and merge-cohort/queue behavior; use merge_group only if a queue is configured, and never infer repository protection settings or force-push main.

**Success criteria:**

- A branch PR and a combined integration candidate both execute required host/Android checks on their recorded SHAs.
- Intentional failed tests, missing screenshots or stale evidence make CI fail; a skipped device job cannot become an implied device pass.
- Parallel branches retain independent artifacts/build directories, and forked/untrusted work never obtains write credentials or privileged secrets.

**Required verification:** Workflow validation; CI evidence tests; Real PR/integration workflow runs.

**Integration inputs and cooperating owners:** 081 host harness; 092 device runner; 094 manifest tooling; 004 build registration.

**Interface handoff:** Publish required-check names and merge evidence contracts to 100/099; other workflow owners retain semantic control of their files.

**Start immediately:** Improve the baseline workflow and evidence handling immediately; consume new specialty gates incrementally without disabling existing checks.

## Agent 094

**Role:** Source manifest and source-snapshot reproducibility

**Branch:** `agents/094-source-provenance-reproducibility`

**Priority:** P0

**Status receipt:** `docs/agents/status/094.md`

**Mission:** Provide deterministic source provenance that works across 100 branches without weakening checks. SOURCE_MANIFEST.sha256 is a generated-file exception for all agents; only this owner changes generation/validation semantics.

**Why this work remains:**

- [SOURCE_MANIFEST.sha256](SOURCE_MANIFEST.sha256) — 190 current tracked-file hashes
- [tests/test_project_contracts.py](tests/test_project_contracts.py):68–77 — listed hashes checked, exact set not checked
- [.github/workflows/export-source-snapshot.yml](.github/workflows/export-source-snapshot.yml):12–19 — tar workspace snapshot
- [AGENTS.md](AGENTS.md) — preserve source/artifact identity

**Exclusive existing files:**

- `.github/workflows/export-source-snapshot.yml`
- `tests/test_project_contracts.py`

**Reserved new files/packages:**

- `tools/source_manifest.py`
- `tests/test_source_manifest_tool.py`
- `tools/ci/source_snapshot/`

**Implementation instructions:**

1. Implement canonical SHA256 generation from staged tracked regular-file content, including additions/deletions/renames and excluding only the manifest itself.
2. Strengthen exact tracked-set, duplicate/path-format and hash checks while preserving unrelated existing source-contract tests; coordinate requested contract updates.
3. Require semantic merge conflict resolution before full manifest regeneration; never resolve generated hashes with ours/theirs or omit failing/new files.
4. Create reproducible exact-revision source archives with normalized metadata, documented exclusions, archive hashes and verification against the Git tree.

**Success criteria:**

- Clean, staged and merged-tree generation is deterministic; tampering, omitted new files, stale entries and unexpected exclusions fail validation.
- Two archives of the same source revision match under the documented reproducibility policy and contain no ignored build outputs/secrets.
- Every branch can regenerate mechanically without changing generator rules, and final integration checks validate the combined tracked tree.

**Required verification:** Manifest tool tests; Exact source-contract pytest; Source archive reproducibility.

**Integration inputs and cooperating owners:** 100 branch/ownership protocol; 093 CI integration.

**Interface handoff:** Publish one safe generation/verification command to every agent; provide archive/tree/hash records to 095/099.

**Start immediately:** Implement against the already valid baseline manifest immediately; preserve its entries until content changes and stage only owned files.

## Agent 095

**Role:** Dependency, notices, SBOM and supply provenance

**Branch:** `agents/095-dependency-notices-sbom`

**Priority:** P0

**Status receipt:** `docs/agents/status/095.md`

**Mission:** Reconcile the actual shipped dependency graph with reproducible resolution, applicable notices and package-level provenance. Do not assume planned libraries are already approved, and do not choose the owner's first-party license.

**Why this work remains:**

- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md):14–18 — final inventory/notices incomplete
- app/build.gradle.kts:35,42–65 — license-resource exclusion and direct dependencies
- gradle/wrapper/gradle-wrapper.properties:3–7 — distribution without committed SHA
- tools/requirements.txt and tools/requirements-real-benchmark.txt — direct pins only

**Exclusive existing files:**

- `THIRD_PARTY_NOTICES.md`

**Reserved new files/packages:**

- `tools/dependencies/`
- `gradle/verification-metadata.xml`
- `gradle/dependency-locks/`
- `tools/requirements.lock`
- `app/src/main/assets/third-party-notices/`
- `tests/test_dependency_inventory.py`

**Implementation instructions:**

1. Inventory resolved Android/JVM/native dependencies separately from host-only benchmark tools; generate SBOM, hashes, licenses and version provenance.
2. Create lock/verification metadata and hashed host-tool resolution; request all dependency-pin/shared Gradle changes from 004 instead of editing those files.
3. Produce complete applicable notice texts and check final APK/AAB contents against inventory, including excluded generic license resources and native redistributables.
4. Assess newly proposed reconstruction/renderer/validator dependencies individually, document unresolved terms and provide an owner decision request when needed.

**Success criteria:**

- Clean dependency resolution reproduces approved versions/hashes and rejects an intentionally altered or unapproved artifact.
- Final candidate native/JVM inventory reconciles with SBOM and included notices; missing required attribution fails the package gate.
- Unknown license terms or first-party licensing decisions remain BLOCKED_EXTERNAL; no fabricated permission, unsupported legal conclusion or secret disclosure.

**Required verification:** Locked resolution; SBOM/inventory tests; Final package notice audit.

**Integration inputs and cooperating owners:** 004 dependency/build owner; 091 candidate artifacts; 094 source provenance.

**Interface handoff:** Supply lock/notice integration instructions to 004/091 and verified dependency evidence to 096/099.

**Start immediately:** Inventory the current resolved graph and define deterministic schemas now; keep unavailable artifacts/owner decisions explicitly unresolved.

## Agent 096

**Role:** Security hardening and hostile-input assurance

**Branch:** `agents/096-security-hostile-input-assurance`

**Priority:** P0

**Status receipt:** `docs/agents/status/096.md`

**Mission:** Harden concrete untrusted-input boundaries and provide maintainable security checks for the offline app and tooling. Prioritize measurable defects over speculative warnings, keep threat assumptions explicit, and preserve other agents' runtime ownership.

**Why this work remains:**

- [tools/capture_audit.py](tools/capture_audit.py):49–55 — ZIP entries collapsed before bounds
- [tools/capture_audit.py](tools/capture_audit.py):84–89 — full decode before pixel bound
- [app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt](app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt) — bounded imported-asset parser
- [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml):14 — exported launcher activity

**Exclusive existing files:**

- `tools/capture_audit.py`
- `tests/test_capture_audit.py`

**Reserved new files/packages:**

- `tools/security/`
- `tests/test_security_evidence.py`
- `.github/workflows/security-analysis.yml`
- `docs/security/threat-model.md`

**Implementation instructions:**

1. Harden capture-bundle parsing for duplicate archive entries, malformed manifest types, path rules, compression/uncompressed budgets and bounded image decode.
2. Add dependency/security/static checks with pinned tooling and actionable suppressions requiring justification; keep scanning credentials and private photographs out of logs.
3. Document entry points for photos, metadata, calibration, GLB, checkpoints, JNI and exports; add adversarial regressions coordinated with 082 and 068.
4. Send application/native fixes to their owners with reproductions, severity and verification; do not add network permissions, analytics or automatic reporting.

**Success criteria:**

- Hostile compact ZIP/image/JSON fixtures fail predictably without uncontrolled memory use, while valid baseline capture bundles remain compatible.
- Security jobs fail on intentional secret/insecure-package fixtures and produce reproducible reports without silently suppressing real findings.
- All confirmed high-impact findings are fixed and retested or explicitly release-blocking; missing execution remains NOT_RUN, never a clean scan.

**Required verification:** Capture audit pytest; Security/static analysis; Adversarial input regressions.

**Integration inputs and cooperating owners:** 082 fuzz evidence; 068 parser owner; 008 manifest owner; 095 dependency inventory.

**Interface handoff:** Publish actionable defects to source owners and final threat/scan evidence to 099; do not modify their semantic source paths.

**Start immediately:** Fix and test the current host capture parser boundaries immediately while building the broader evidence harness.

## Agent 097

**Role:** Startup and viewer performance qualification

**Branch:** `agents/097-startup-viewer-macrobenchmarks`

**Priority:** P1

**Status receipt:** `docs/agents/status/097.md`

**Mission:** Measure startup and model-inspection responsiveness on actual release-like builds, keeping visual correctness and thermal context intact. Add justified baseline profiles only after measurements; optional graphics acceleration is not a required shortcut.

**Why this work remains:**

- [app/src/main/java/com/realityforge/scanner/MainActivity.kt](app/src/main/java/com/realityforge/scanner/MainActivity.kt):12–19 — launch/onStop path
- [app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt](app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt):58–98 — model/atlas upload
- [app/src/androidTest/java/com/realityforge/scanner/PhotographicViewerTest.kt](app/src/androidTest/java/com/realityforge/scanner/PhotographicViewerTest.kt): 123–154 — correctness/lifecycle tests
- app/build.gradle.kts — no macrobenchmark/baseline-profile module

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `macrobenchmark/`
- `gradle/macrobenchmark-validation.gradle.kts`
- `app/src/main/baseline-prof.txt`
- `tools/device/ui_performance/`
- `docs/quality-protocols/ui-performance.md`

**Implementation instructions:**

1. Create macrobenchmark scenarios for cold/warm launch, library navigation, model reopen, atlas upload, orbit/zoom and repeated viewer lifecycle changes.
2. Provide a convention/module integration fragment to 004; coordinate stable test identifiers with UI owners without changing their source files.
3. Measure startup duration, frame timing/jank, input response and memory across bounded small/large model fixtures on representative physical devices.
4. Generate reproducible baseline profiles where supported, compare before/after against identical build/data conditions and forward bottlenecks to 069/070.
5. Retain baseline profiles only when representative before/after measurements show benefit without regressions; if measurements show no benefit, document that result and omit the profile artifact rather than forcing a positive claim.

**Success criteria:**

- Scenarios execute on the documented minified/release-like target and preserve render/texture correctness during interaction and context changes.
- Performance reports identify exact device/app/model hashes, warmup and thermal conditions; unsupported counters are explicitly marked.
- Retained baseline profiles show measured benefit without changing behavior; unavailable physical measurements remain BLOCKED_EXTERNAL/NOT_RUN rather than emulator performance claims.

**Required verification:** Macrobenchmark scenario validation; Physical startup/viewer runs; Baseline-profile comparison.

**Integration inputs and cooperating owners:** 004 build integration; 069 renderer; 070 viewer UI; 071–080 navigation/accessibility; 087 resource context.

**Interface handoff:** Deliver bounded performance budgets and regressions to UI/renderer owners, candidate evidence to 099.

**Start immediately:** Build deterministic model fixtures and executable baseline scenarios now; validate physical budgets only when real devices are available.

## Agent 098

**Role:** Current documentation and developer runbook

**Branch:** `agents/098-current-documentation-runbook`

**Priority:** P1

**Status receipt:** `docs/agents/status/098.md`

**Mission:** Give developers one accurate current account of capabilities, architecture, supported limits and verification. Preserve historical milestone evidence while removing contradictions and documenting the user's authorized parallel-branch workflow.

**Why this work remains:**

- [README.md](README.md):3,48–52,82 — outdated M17/native-cancellation/main-only statements
- [docs/BUILDING.md](docs/BUILDING.md):99–102 — stale CI-not-run statement
- [docs/MILESTONE_STATUS.md](docs/MILESTONE_STATUS.md) — M12 opening with later increments
- app/build.gradle.kts:16–17 — current M19 version

**Exclusive existing files:**

- `README.md`
- `docs/BUILDING.md`
- `docs/ARCHITECTURE.md`
- `docs/MILESTONE_STATUS.md`
- `docs/NEXT_MILESTONE.md`

**Reserved new files/packages:**

- `tools/docs/check_runbook.py`
- `tests/test_runbook_consistency.py`

**Implementation instructions:**

1. Reconcile version, cancellation/checkpoint behavior, current output representations and resource limits with actual source rather than the planned agent tasks.
2. Write tested clean-build, instrumentation, benchmark, recovery and candidate-release commands with clear prerequisites and exact artifact locations.
3. Separate current evidence from historical reports, link immutable candidate records, and describe missing physical/signing/license gates honestly.
4. Document ownership routing, per-agent branches, generated-manifest procedure and integration gates from 001/094/100; avoid duplicating instructions that can drift.

**Success criteria:**

- All documented paths/commands resolve and capability/version statements agree with the integrated code and exact evidence records.
- Historical M01–M19 claims remain dated/history-labeled; green CI is never presented as real-object or physical-device qualification.
- A fresh developer can build, run required tests and locate assigned-agent instructions; unavailable prerequisites are explicit without claiming execution.

**Required verification:** Documentation consistency tests; Link/path checks; Verified command smoke run.

**Integration inputs and cooperating owners:** 100 execution contract/integrated state; 004 build guide; 094 manifest tools; 099 evidence index.

**Interface handoff:** Collect concise implementation receipts from all owners; update shared narrative only after corresponding code/evidence is integrated.

**Start immediately:** Correct baseline contradictions and design the runbook checker immediately; update future capabilities only as their implementations actually land.

## Agent 099

**Role:** Complete release-candidate journey qualification

**Branch:** `agents/099-release-candidate-journey-qualification`

**Priority:** P0

**Status receipt:** `docs/agents/status/099.md`

**Mission:** Qualify the assembled initial-release candidate through the real user journey and independently aggregated evidence. Keep optional acceleration separate from required functionality and provide a truthful release decision, never a synthetic readiness percentage.

**Why this work remains:**

- [PROJECT_PLAN.md](PROJECT_PLAN.md) — phases 18–20 resource/reliability/release gates
- [app/src/androidTest/java/com/realityforge/scanner/SessionReliabilityTest.kt](app/src/androidTest/java/com/realityforge/scanner/SessionReliabilityTest.kt):39–115 — synthetic complete session
- [docs/M19_SCAN_EVIDENCE.md](docs/M19_SCAN_EVIDENCE.md):59–60 — real physical/release gates absent
- [README.md](README.md):68–73 — exact-commit CI evidence boundary

**Exclusive existing files:**

- None. Implement in your reserved new paths and request changes from existing-file owners.

**Reserved new files/packages:**

- `tools/release/qualification/`
- `schemas/release-evidence-v1.json`
- `tests/test_release_qualification.py`
- `docs/release/qualification.md`
- `app/src/androidTest/java/com/realityforge/scanner/qa/ReleaseJourneyTest.kt`

**Implementation instructions:**

1. Define evidence records with source/artifact hashes, commands, inputs, device class, metrics and PASS/FAIL/BLOCKED_EXTERNAL/NOT_RUN; reject stale or missing required records.
2. Exercise install/upgrade, capture/import, calibration, reconstruction, inspection/editing, export, reopen, interrupted recovery and offline use on candidate builds.
3. Combine geometry/texture quality, resource, accessibility, interoperability, security, dependency and release-package gates with per-scene/device failure retention.
4. Prepare a concrete external-beta protocol and blocker ledger; send defects to owners and retest fixes on the combined candidate, preserving originals and previous projects.

**Success criteria:**

- Known-stale, skipped, emulator-only physical evidence and mismatched artifacts make the readiness aggregator fail closed.
- The actual candidate completes required journeys and independent gates on supported physical devices, with reproducible operator steps and retained results.
- If hardware, licenses, signing or external testers are unavailable, executable harness/checklist is complete but release remains BLOCKED_EXTERNAL; no public publishing is implied.

**Required verification:** Evidence schema/aggregator tests; Integrated release journey; Physical real-scene qualification.

**Integration inputs and cooperating owners:** 081–098 verification outputs; 100 integrated candidate/evidence contract; Required feature owners.

**Interface handoff:** Provide a concrete candidate decision and blocker list to 100; consume immutable receipts and never merge other agents' code yourself.

**Start immediately:** Implement fail-closed evidence aggregation and baseline journey orchestration now; integrate independent gates as owners deliver them.

## Agent 100

**Role:** Integration lead and release acceptance coordinator

**Branch:** `agents/100-integration-lead`

**Priority:** P0

**Status receipt:** `docs/agents/status/100.md`

**Mission:** Integrate all one hundred contributions into a coherent, evidence-backed release candidate and keep unproven production gates visibly open.

**Why this work remains:**

- [AGENTS.md](AGENTS.md) and README.md previously mandated main-only development; this campaign explicitly authorizes one branch per numbered agent.
- [SOURCE_MANIFEST.sha256](SOURCE_MANIFEST.sha256) hashes every tracked file except itself, and verify.yml currently runs device tests only after a main push or selected manual run.

**Exclusive existing files:**

- `AGENTS.md`
- `PRODUCTION_100_AGENT_EXECUTION_PLAN.md`
- `VERSION`
- `PROJECT_PLAN.md`

**Reserved new files/packages:**

- `docs/agents/integration/`
- `tools/agents/`
- `.github/PULL_REQUEST_TEMPLATE/agent-delivery.md`

**Implementation instructions:**

1. Record the campaign base, ownership registry and interface decisions; provide an executable plan/ownership validator and a per-agent status/hand-off index rather than a shared editable checklist.
2. Land behavior-preserving foundation contracts first and then merge small compatible implementation slices. Resolve cross-owner wiring through the named owner, maintain an integration branch and serialize updates to main.
3. For every candidate, verify source ownership, dependency compatibility, tests and exact manifest contents; regenerate mechanical manifest conflicts after semantic merges and never waive red gates.
4. Request that each foundation owner implement follow-up wiring on its own branch; integrate the resulting immutable commits on agents/100-integration-lead, rerun affected cross-feature flows, and reconcile version numbers with 004/098 while recording tested artifact/commit identities.
5. Close the campaign only when all required assignments and final release gates pass. Retain BLOCKED_EXTERNAL for absent devices, signing credentials or authorized real datasets; prepare concrete next actions without claiming success.

**Success criteria:**

- All 100 IDs have a traceable branch/status/commit/evidence record; every required component is reachable through the app or executable validation tooling.
- The integrated release candidate passes the declared host/Android/device/quality/privacy/interoperability/release matrix on its actual commit and signed artifact where required.
- No unmerged required work, stale source-manifest entry, unresolved source conflict, placeholder success or unmeasured required production claim remains.

**Required verification:** PLAN; All applicable final release gates; Independent 099 qualification on final integrated artifact.

**Integration inputs and cooperating owners:** [Agent 001](#agent-001); [Agent 002](#agent-002); [Agent 003](#agent-003); [Agent 004](#agent-004); [Agent 005](#agent-005); [Agent 006](#agent-006); [Agent 007](#agent-007); [Agent 008](#agent-008); [Agent 009](#agent-009); [Agent 010](#agent-010); [Agent 011](#agent-011); [Agent 012](#agent-012); [Agent 013](#agent-013); [Agent 014](#agent-014); [Agent 015](#agent-015); [Agent 016](#agent-016); [Agent 017](#agent-017); [Agent 018](#agent-018); [Agent 019](#agent-019); [Agent 020](#agent-020); [Agent 021](#agent-021); [Agent 022](#agent-022); [Agent 023](#agent-023); [Agent 024](#agent-024); [Agent 025](#agent-025); [Agent 026](#agent-026); [Agent 027](#agent-027); [Agent 028](#agent-028); [Agent 029](#agent-029); [Agent 030](#agent-030); [Agent 031](#agent-031); [Agent 032](#agent-032); [Agent 033](#agent-033); [Agent 034](#agent-034); [Agent 035](#agent-035); [Agent 036](#agent-036); [Agent 037](#agent-037); [Agent 038](#agent-038); [Agent 039](#agent-039); [Agent 040](#agent-040); [Agent 041](#agent-041); [Agent 042](#agent-042); [Agent 043](#agent-043); [Agent 044](#agent-044); [Agent 045](#agent-045); [Agent 046](#agent-046); [Agent 047](#agent-047); [Agent 048](#agent-048); [Agent 049](#agent-049); [Agent 050](#agent-050); [Agent 051](#agent-051); [Agent 052](#agent-052); [Agent 053](#agent-053); [Agent 054](#agent-054); [Agent 055](#agent-055); [Agent 056](#agent-056); [Agent 057](#agent-057); [Agent 058](#agent-058); [Agent 059](#agent-059); [Agent 060](#agent-060); [Agent 061](#agent-061); [Agent 062](#agent-062); [Agent 063](#agent-063); [Agent 064](#agent-064); [Agent 065](#agent-065); [Agent 066](#agent-066); [Agent 067](#agent-067); [Agent 068](#agent-068); [Agent 069](#agent-069); [Agent 070](#agent-070); [Agent 071](#agent-071); [Agent 072](#agent-072); [Agent 073](#agent-073); [Agent 074](#agent-074); [Agent 075](#agent-075); [Agent 076](#agent-076); [Agent 077](#agent-077); [Agent 078](#agent-078); [Agent 079](#agent-079); [Agent 080](#agent-080); [Agent 081](#agent-081); [Agent 082](#agent-082); [Agent 083](#agent-083); [Agent 084](#agent-084); [Agent 085](#agent-085); [Agent 086](#agent-086); [Agent 087](#agent-087); [Agent 088](#agent-088); [Agent 089](#agent-089); [Agent 090](#agent-090); [Agent 091](#agent-091); [Agent 092](#agent-092); [Agent 093](#agent-093); [Agent 094](#agent-094); [Agent 095](#agent-095); [Agent 096](#agent-096); [Agent 097](#agent-097); [Agent 098](#agent-098); [Agent 099](#agent-099).

**Interface handoff:** Own C16 integration decisions, guide amendments and final status. Agent 100 is the only campaign merge coordinator; it can request source-owner fixes but cannot silently expand another agent's ownership or bypass branch protection.

**Start immediately:** Start immediately with inventory, ownership/interface validation and baseline verification. Do not wait for 99 completed branches before doing integration; merge tested compatible slices continuously, reserving final success for full acceptance.


## 11. Final completion audit

Agent 099 independently qualifies the exact integrated candidate. Agent 100 may declare the campaign complete only when every required row below passes with immutable evidence; required missing physical or signing evidence leaves release blocked.

| Question | Evidence required |
|---|---|
| Are all 100 roles accounted for? | Unique branch, scope, implementation commit and truthful status for 001–100; no duplicate source ownership or abandoned required handoff |
| Is this the intended product? | Complete offline capture/import→review→reconstruction→studio/edit→export→reopen journey; supported profiles and excluded cases match the documented contract |
| Is the scan actually reconstructed? | Camera registration, calibrated coordinate tests, real observed geometry, photographed texture and independently measured full-object evidence; no generative substitute or canned model |
| Is the source maintainable? | One active implementation per stage, small orchestration boundaries, explicit contracts, removal of dead scaffolding and reviewed/licensed dependencies |
| Does data survive failures and upgrades? | Original hashes, transactional/recovery evidence, actual process-kill and storage-failure runs, current-result provenance and installed-schema upgrade results |
| Do outputs work elsewhere? | Independently imported GLB/OBJ/PLY/STL with correct representation, topology disclosure, scale, texture and file references |
| Is the app usable across its supported devices? | API/ABI/page-size tests, real capture/lens cases, accessibility/adaptive UI and measured CPU/GPU/memory/disk/thermal/power budgets |
| Is the distributed binary the tested binary? | Exact source tree, release APK/AAB/split hashes, R8/JNI execution, signing identity, notices/SBOM and final candidate install/upgrade evidence |
| Is the merged state clean and current? | Latest integrated commits, all checksums validated, no uncommitted required changes, no conflict markers and no stale evidence accepted as current |

### Scope coverage against the approved product phases

| PROJECT_PLAN phase | Primary accountable agents |
|---|---|
| 1 Product specification | 010,098,099,100 |
| 2 Reference datasets | 083,086,095 |
| 3 Desktop reference | 081,083–085,090 |
| 4 Native core and Android bridge | 003,004,031–060,081–082,092 |
| 5 Camera and import | 011–015,018,025,027,086 |
| 6 Feature extraction | 012,031–033,045 |
| 7 Matching and verification | 017,034–037 |
| 8 Sparse SfM | 036–040,043 |
| 9 Bundle adjustment | 031,041–043 |
| 10 Dense MVS | 030,044–052 |
| 11 Dense fusion | 049,053–054 |
| 12 Meshing | 053–056 |
| 13 Photographic texturing | 057–060,085 |
| 14 Optimization | 030,052,065,087,097 |
| 15 Export | 025–026,061–067,090 |
| 16 3D studio | 066,068–070,097 |
| 17 Guided capture | 013–018,027,073,075,086 |
| 18 Resource engineering | 007–009,020–021,028–030,045–052,068–069,087,092,097 |
| 19 Reliability | 001–010,011–030,050–051,077–080,082,088–089,096 |
| 20 Release | 004–005,071–080,089–100 |

### Platform references checked for this plan

These primary references were consulted on 18 October 2026. Execution agents must recheck requirements relevant to their actual target OS/library/distribution versions before changing release configuration.

- [Android long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running): WorkManager can use a foreground service, but long-running workers on Android 16 can consume job quota. 008 must choose and test the actual execution arrangement.
- [Android foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout): bounded service behavior and timeout handling must be part of the recovery design; no infinite-background guarantee.
- [Android 16 KB page-size support](https://developer.android.com/guide/practices/page-sizes): validate packaged native libraries and actual supported runtime behavior. Linker flags alone do not prove a delivered package works.

### Copyable universal launch instruction

Replace X with a number from 1 to 100. The agent then reads its exact branch and assignment from this repository:

```text
You are Agent X for cheshmakzanoon-ops/3d-scanner. Read AGENTS.md and
PRODUCTION_100_AGENT_EXECUTION_PLAN.md, including the shared rules and your
exact numbered assignment. Use your assigned branch in an isolated checkout.
Implement your complete scope, coordinate through the named file owners,
test the real behavior, fix failures, commit and push your branch, and keep
docs/agents/status/NNN.md current. Continue until your acceptance criteria
are satisfied. Do not stop at a plan or scaffolding, and do not claim success
for unrun physical-device, quality, signing or release gates. Complete all
feasible work and identify any genuine external blocker precisely.
```

For Agent 100, the same instruction starts continuous integration coordination and final acceptance work; it is not an instruction to wait for 99 finished branches. No agent may turn this guide into a claim that the product is already production-ready.
