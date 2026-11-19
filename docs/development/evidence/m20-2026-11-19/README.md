# Compact M20 preparation evidence — 19 November 2026

The execution receipts and control summaries are byte-identical copies of their
observed local records. The completed assignment ledger records the campaign.
They support the [campaign record](../../M20_PREPARATION_2026_11_19.md); they do
not establish physical M20 completion, Android execution or release readiness.

## What each file establishes

| File | Observed scope |
| --- | --- |
| [native-release-ctest.log](native-release-ctest.log) | Rebuilt candidate Release CTest: all 53 tests passed. This host run is separate from sanitizer and Android/device qualification. |
| [profile-sweep.json](profile-sweep.json) | The final six-profile synthetic native sweep: one baseline and five rejected alternatives, with exact fixture, native-library and helper-source identities. |
| [loss-report.json](loss-report.json) | The report generated from the retained candidate textured three-view native result. Its input SHA-256 identifies exactly the bytes parsed. |
| [surface-parity.json](surface-parity.json) | Three synthetic baseline/candidate comparisons passed with ordinary child exits, equal non-diagnostic result fields and byte-equal GLB/PLY exports. |
| [initial-host-pytest.json](initial-host-pytest.json) | The first complete candidate host run **FAILED**: 4,955 passing parent tests, 362 passing subtests, one failure and 33 skips. Its exit code remains 1. |
| [initial-host-source.json](initial-host-source.json) | The staged source tree and manifest measured by that first host run, together with the selected native-library identity. |
| [final-host-pytest.json](final-host-pytest.json) | The subsequent complete host run **PASSED** with exit 0: 4,964 passing parent tests, 362 passing subtests, 33 unchanged skips and zero failures/errors. |
| [final-host-source.json](final-host-source.json) | The exact staged tree and manifest tested by the passing run, retained independently of later documentation additions. |
| [agent-assignments.json](agent-assignments.json) / [wave summary](agent-wave-summary.md) | All 60 actual assignments completed in ten groups of six, with outcomes and qualification limits kept distinct. |
| [host-synthetic-cli.json](host-synthetic-cli.json) | Earlier host CLI checks for schema 3, a constructed schema-4 saved-preview envelope, immutable output and contradictory-input rejection. The envelope was synthetic; no Android controller ran. |
| [shared-ancestor-control.json](shared-ancestor-control.json) | A controlled reproduction showing unrelated shared-directory activity changed ancestor metadata while provider-subtree metadata and driver hashes stayed unchanged. This is diagnostic evidence for the synthetic fixture correction, not compiler qualification. |

## Source and interpretation boundaries

The final native receipts preserve their recorded source context:
`a373aff43a03bd736edb16707cbf5edabf5f2156` with a **dirty worktree**. That HEAD is
baseline context, not a claim that the candidate changes were committed or
executed after publication. Recorded helper hashes were checked against the
actual files before these receipts were copied.

Library hashes identify tested binary bytes. They do not independently attest
to a library's build-source revision; the sweep records
`nativeLibraryBuildProvenance: null`. The loss report's input identity likewise
does not authenticate the source of that artifact. Absolute local paths in
unchanged receipts describe the measurement environment, not portable commands.

The initial full-suite failure and its tested tree remain retained. The passing
final run is a separate source-identified record and does not rewrite that
failure. Its tested tree is preserved in implementation commit `63a6249a6bc3031e5e923bf10a4823d0fe7f6651`,
created after execution. Later documentation and ledger additions have their
own final manifest. The earlier CLI control predates the final two error-message
corrections and does not replace final integrated verification.

Raw native JSON, fixture arrays, exported geometry, parity process logs, native binaries
and full JUnit logs are not embedded in this compact bundle. The receipts retain
their relevant hashes; replay requires the corresponding inputs and tools.
[Profile reproduction](../../DENSE_PROFILE_SWEEP.md) and the
[parity procedure](../../DENSE_SURFACE_LOSS_DIAGNOSTICS.md) describe how to create
new evidence in fresh output paths. A new run produces its own identities and
cannot retroactively replace these observations.

Synthetic parity and diagnostic consistency do not measure physical accuracy,
completeness, appearance, handset resources or installed offline workflows.
Physical inputs remain **BLOCKED_EXTERNAL**, local sanitizer execution remains
**BLOCKED_ENVIRONMENT**, and unexecuted Android/release checks remain **NOT_RUN**.
Candidate CI must supply its own commit-specific outcome.

## Copied-file identities

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `profile-sweep.json` | 6,652 | `c66dd64246809aaf7c7cad1ec44957f03b8d34d74f39468a8038bff70c867ab5` |
| `loss-report.json` | 6,998 | `f97c363ebaa338fdc2c0e7ca86138eebd746f21ea004b444e01da4d9242cf3f9` |
| `surface-parity.json` | 8,478 | `1ae9c6113ef0830f27b7b5b3bb30f89a85d9b89e35ba3f5b0aec3b4fda1361db` |
| `initial-host-pytest.json` | 3,463 | `286c56e23e9b1a0a32dc5dc8d615151e434fa95099ce2570b6d298fbf9bb3925` |
| `initial-host-source.json` | 982 | `d593145eb9a00015cd8cd2958729897188dd5ee01150b6e88bb0daf0a9a23241` |
| `host-synthetic-cli.json` | 2,597 | `0e3683837d6d4bd45b1dda95cd96821af1631d9393f1eb61e166ca965c92b2d3` |
| `shared-ancestor-control.json` | 570 | `b8c998e3cbc51fbd85c08292d16269d6acbbdeca6677759dfd559e2c84ec6cb0` |
| `final-host-pytest.json` | 5,947 | `96623cdc0bec7aee8d7d360b56f558737c9c2e55719ff7304e9cba4f7e505c86` |
| `final-host-source.json` | 1,027 | `5f703da79880ba01cc32d62417efb46f37ce8c75bc9ccc16e7f70f4c1303de16` |
| `native-release-ctest.log` | 6,352 | `fb74572f74ba735c3634f6f80df664b71f337ab90ba0d16d4c6e0f06f8190565` |
