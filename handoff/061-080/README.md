# Agents 061–080: preserved at user stop

Development and validation stopped at the user’s explicit request on 2026-10-20.
This branch preserves the authored environment setup, validation harnesses, drafts,
recorded evidence, and detached validation history. It is a preservation handoff,
not a final integrated product or release approval. No further product fix was
made after the stop request. The final 077 commit records existing findings.

All 20 dedicated agent branches were checked against GitHub with `git ls-remote`;
every local HEAD matched its remote HEAD and every agent working tree was clean.
The exact results are in `campaign/user-stop-branch-audit.json`.

| Agent | Dedicated branch | Saved commit |
| --- | --- | --- |
| 061 | `agents/061-glb-asset-contract` | `c9125e028928997a1be42787a8e348b1dd21a7dc` |
| 062 | `agents/062-obj-export` | `0323b8470797ce8fa4e31e1ca72cbdc6ab588c08` |
| 063 | `agents/063-stl-export` | `3da83289c83c11e618282c754a45d3a4c8b03b72` |
| 064 | `agents/064-ply-export` | `b9dab415e9631a012e1559207654c7db33290122` |
| 065 | `agents/065-mesh-lod` | `d5c968703f447ca0bc8b616180e2ff7b19d3b27b` |
| 066 | `agents/066-model-editing` | `6ca4ca792d3bac127f193c0f590e7668bdc72681` |
| 067 | `agents/067-export-coordinator` | `5a94a62a2387cdf9309df743d2ecf69ee181ca07` |
| 068 | `agents/068-model-loader` | `9483d69c80ec65524224759e2536e52955669b6d` |
| 069 | `agents/069-studio-renderer` | `1230d61816d2cc73889db4d6d48331a26973ebb4` |
| 070 | `agents/070-studio-controls` | `bb01bca912af85d7edd973e7327e818fcbac2577` |
| 071 | `agents/071-app-navigation-composition` | `0cec378b19d65dbaef94f739ef16cea6ed294530` |
| 072 | `agents/072-project-library-ui` | `7d918e90a260ce59877584314e8b1078a089cb75` |
| 073 | `agents/073-photo-review-project-ui` | `196f4afa47532aa2179aa7bd6938f3327e746c62` |
| 074 | `agents/074-processing-recovery-ui` | `4507a0d78c997c7540160d7c09825314bc6317ff` |
| 075 | `agents/075-capture-onboarding-instructions` | `39049fa4cfc5180231e6649acc6577527d63b8ca` |
| 076 | `agents/076-adaptive-design-system` | `e1a573bf92f5a40870efa438907a82af68d556a0` |
| 077 | `agents/077-accessibility-verification` | `7b13dc190f752a21de04958ea3e4b67d8d239e95` |
| 078 | `agents/078-localization-resources` | `2fc0fc283646e779dc8762e845cc95a37d3d7557` |
| 079 | `agents/079-preferences-about-privacy-ui` | `c39338ebc8a4cdfc0606396807469bf8647f6374` |
| 080 | `agents/080-actual-ui-end-to-end` | `2426c46ebbcc76ba41b19510c13c83c3633565c2` |

## Contents and provenance

- `toolchains/`: the authored setup/activation/proxy-aware Gradle runner and setup logs.
- `campaign/`: preserved campaign automation, compile/build adapters, test harnesses,
  draft handoffs, source snapshots and evidence. Original absolute paths and file
  hashes are listed in `copied-files.json`. Paths under `/workspace` remain as
  originally used; these historical scripts have not been rewritten or rerun.
- `validation-snapshots.bundle`: detached temporary validation commits, preserved
  without publishing combined histories to any numbered agent branch. Exact refs
  and source SHAs are in `validation-snapshot-refs.json`.
- `preservation/`: the archive creation script and an existing receipt-update helper.

Historical drafts, earlier failures, test-only adapters/stubs and old WORKING
receipts are retained for provenance. A later successful check does not erase an
older failure, and compile-only evidence does not establish runtime behavior.
Temporary build adapters do not constitute permanent owner integration.
`campaign/device-final-fixtures-e290ff563293/receipt.json` explicitly records the
user interruption and completed failures. The outer final UI phase did not run.

Downloaded SDK/JDK/Gradle distributions, third-party packages/reference sources,
credentials, caches, generated binaries/APKs/resources and disposable pytest
repositories are excluded. The toolchain setup script describes the pinned
installations. `preservation-summary.json` records the archive selection.

To inspect the preserved detached history from a clone containing the campaign
base `ad3029193f304c70931b94ea7a0df90b69d84b07`:

```sh
git bundle verify handoff/061-080/validation-snapshots.bundle
git bundle list-heads handoff/061-080/validation-snapshots.bundle
git fetch handoff/061-080/validation-snapshots.bundle 'refs/archive/061-080/*:refs/archive/061-080/*'
```

## State of verification at the stop

Latest combined snapshot: `e290ff56329322b4365298b97bba7df8b16d2470`.
Its recorded Android build assembled app/test APKs, passed 236 JVM tests and lint
with zero errors (28 warnings), and passed packaged offline/alignment checks.
The full Python run recorded 187 PASS and 7 FAIL, with no exclusions or xfails.
Native evidence retains the earlier 17-test execution separately from the later
source/binary-equivalence verification. Consult the immutable source/artifact
identities in `campaign/final-validation/` before using any result.

The last completed emulator classes recorded 077: 2 PASS/1 FAIL and 072: 1 PASS/
1 FAIL. The project-name editable-field accessibility failure remains open.
Remaining instrumentation was interrupted; the final 080 UI phase was NOT_RUN.
Separate historical GLES/Filament scoped passes do not establish a full-class pass.
New upstream 088 provider fixes were fetched for review but were not integrated
before the stop request.

Manual accessibility, supported physical-device qualification, final owner
integration and release acceptance remain open. This archive claims preservation,
not completion of those gates. Resume development only on a new user instruction.
