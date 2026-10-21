# Additional existing JVM checks for agents 021–030

**PASS: 96 existing tests in 17 classes**, using actual Kotlin 2.1.21 and
JUnit 4.13.2. The compiler completed successfully; JUnit reported `OK (96 tests)`
with an internal test time of 5.755 seconds. Provenance separately records the
complete JUnit process duration, including startup.

The runner snapshotted 31 complete production files, 17 existing test files, and
one existing fixture from local source `54ae92a822693b439452cf53852bc4c30150002a`.
That local tree exactly matches published commit
`aad8e1d5754491d8f0e6a0aca96a9f1f02f53ba6`. Dependency and source hashes were
verified; both JVM heaps were limited to 512 MiB. No production or test source
fixes were needed.

| Existing agent scope | Tests passed |
| --- | ---: |
| 021 storage accounting | 3 |
| 022 deletion protocol, cleaner, and reducer | 10 |
| 023 actual-file publication protocol | 10 |
| 026 destination transfer protocol | 15 |
| 027 calibration codec, evaluator, store, and metadata adapter | 16 |
| 029 notification policies, mapping, authorization, and service draining | 29 |
| 030 resource policy and workload selection | 13 |
| **Total** | **96** |

These are supplementary portable JVM/protocol checks. Existing port fixtures
remain explicitly unit fixtures. No Android, Room, OpenCV, or test-API substitute
was introduced. Full Android Gradle, Room integration, JNI, instrumentation, and
physical qualification remain separate gates. The earlier 44, 67, and 128 test
batches contain no overlapping test classes.

The portable selection excludes codecs co-located with Room sources, the
application-bound photo-removal request, the actual Room reopening service,
journaled calibration publication's Room entity dependency, Compose storage
state, and runtime Android notifications. Those existing tests remain
registered in the actual Android build.

## Evidence

`runner.py` and `selection.json` retain the exact execution harness and complete
selection. `prepared-provenance.json` records the immutable preparation.
`provenance.json` records source/dependency hashes, exact commands, Java release,
timestamps, exit codes, and compiled class hashes. `compile.log` and `junit.log`
retain the real diagnostics and result. Only trailing line whitespace and blank final lines are normalized
in the stored log copies; both captured and stored log hashes are recorded.
The two existing constructor/copy visibility warnings are preserved.

The normal reproducible Android build uses the committed Gradle wrapper. This
local evidence harness does not replace it or qualify the excluded gates.
