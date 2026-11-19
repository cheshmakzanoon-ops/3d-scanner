# Sixty assignments in ten waves

**Completed campaign:** all 60 distinct assignments finished. The final full host run exited 0 with 4,964 passing parent tests, 362 passing subtests and 33 unchanged skips. Physical M20 and release qualification remain open.

Each wave groups six assignments. At most three child agents ran concurrently, with the coordinator outside the 60-assignment count. Independent work could overlap between wave groups. Assignment completion describes work performed; it does not turn a failed test into a pass or complete physical M20.

| Wave | Assignments | Focus | Observed work and status |
|---|---|---|---|
| 1 | 01–06 | Roadmap, baseline and mechanism diagnosis | Identified M20 external gates and the software, topology-accounting and fuzz-output prerequisites. |
| 2 | 07–12 | Initial qualification and native attribution implementation | Implemented stricter qualification and schema-4 native attribution, then identified further report hardening needs. |
| 3 | 13–18 | Report validation, immutable IO and parity tooling | Added strict report/state validation, immutable IO, saved-envelope handling and a reusable parity runner. |
| 4 | 19–24 | Adversarial accounting, provenance and output review | Found and corrected population/provenance gaps; reviewed preservation and parity evidence. |
| 5 | 25–30 | Compatibility, loaded-library correction and physical handoff | Preserved formats and limits; corrected stale library loading and parity output isolation; prepared physical instructions. |
| 6 | 31–36 | Documentation, preserved gates and validation protocol | Updated current status and diagnostic interpretation; documented failing sanitizer evidence and exact manifest protocol. |
| 7 | 37–42 | Initial full qualification and evidence review | Retained the initial full-run FAIL with one existing-test failure; independently checked native and receipt evidence. |
| 8 | 43–48 | Integration failure diagnosis and independent review | Corrected synthetic fixture instability without production gate changes and identified two CLI error-message gaps. |
| 9 | 49–54 | Final corrections, refreshed evidence and full qualification | Fixed CLI errors, regenerated and packaged receipts, and reviewed CI/shipping; final full run 52 passed with its exact tested source retained. |
| 10 | 55–60 | Acceptance, source fidelity and campaign handoff | Completed acceptance, diff, source, transport and final scope reviews and prepared this ledger; the final full host result is recorded, with publication subject to final source checks and commit-specific CI. |

The machine-readable companion, `agent-assignments.json`, contains each actual task name, ordinal, wave, role, status and outcome. The initial failed full-suite run and the subsequent passing final run remain distinct. Physical ARM64 captures, reference comparisons, resource measurements and release qualification are separate open gates.
