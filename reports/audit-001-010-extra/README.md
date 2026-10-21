# Additional existing pure JVM tests for agents 001–010

## Result

**PASS: 67 existing tests in 11 classes.** This is supplementary execution of real
repository Kotlin code on the JVM. It is not a full Gradle build, Android
instrumentation result, Room migration result, or device performance claim.

The tested repository was `/workspace/scratch/16a420565b0d/3d-scanner` at
`54ae92a822693b439452cf53852bc4c30150002a`. Every selected source file remained
byte-identical during this run. The 44 tests in the earlier portable JVM evidence
were explicitly excluded. No new tests, production source changes, Android
substitutes, or Room substitutes were used for this check.

| Existing test class | Tests passed |
| --- | ---: |
| `agents/a001/OperationCoordinatorTest.kt` | 7 |
| `agents/a002/PipelineGraphTest.kt` | 14 |
| `agents/a007/DurableJobContractsTest.kt` | 8 |
| `agents/a009/AlignmentCheckpointContractTest.kt` | 4 |
| `agents/a009/ArtifactIdentityPolicyTest.kt` | 2 |
| `agents/a009/StageManifestCodecTest.kt` | 3 |
| `engine/LensGeometryTest.kt` | 5 |
| `engine/IncrementalRegistrationTest.kt` | 8 |
| `engine/MultiViewTracksTest.kt` | 3 |
| `engine/DenseWorkloadTest.kt` | 8 |
| `engine/ScanQualityEvidenceTest.kt` | 5 |
| **Total** | **67** |

Kotlin compilation exited 0 after 110.462 seconds. The single compiler process
used `-Xmx512m`; the subsequent JUnit process also used `-Xmx512m`. Both used
task-owned temporary storage through `-Djava.io.tmpdir`. No other compiler was
started by this runner. The exact JUnit output was:

```text
JUnit version 4.13.2
...................................................................
Time: 3.726

OK (67 tests)
```

The compiler reported one existing warning in `ArtifactContracts.kt`: the
generated `copy()` method exposes the non-public primary constructor of `Sha256`.
The pinned Kotlin 2.1.21 compiler accepts this; the diagnostic identifies a
future Kotlin 2.2 compatibility concern. The complete diagnostic is retained in
`compile.log`.

## Toolchain and reproducibility

The runner uses the real official Kotlin compiler 2.1.21, Kotlin standard library
2.1.21, coroutines 1.10.2, JSON 20250517, JUnit 4.13.2, and Hamcrest 1.3. Compiler
bootstrap dependencies match the earlier portable JVM runner. Each dependency's
SHA-256 was verified against the existing pinned dependency inventory before
compilation.

`provenance.json` records every actual production, fixture, and test source
SHA-256; dependency coordinates and SHA-256 values; the exact compiler and JUnit
commands; repository revision; excluded classes; timings; exit codes; and the
source stability check. `runner.py` is the exact executed runner. It uses the
task-owned output directory `/dev/shm/scanner-jvm-001-010-extra` and the previously
prepared toolchain `/dev/shm/scanner-jvm-checks`.

| Evidence file | SHA-256 |
| --- | --- |
| `runner.py` | `4cb50fff39fc9ff7246867ab7a81dc6fe3c083f7e386d0d6108a4b5bb4bf3248` |
| `provenance.json` | `c1677c0f1ede81a77f477559caaaf42258f58fe119b2b74de88549f06e914513` |
| `compile.log` | `81a39eadd3bf9ddef4ebb8e7d135b5987c07f4dc2fe73855676bd682cc5119d2` |
| `junit.log` | `3cdfcdf4d4b235202acb84013f0c8cf07126cd3fd1cbbfb30bd42cc46067fae5` |

## Scope limits

The two agent 006 JVM tests depend on `ProjectPathPolicy`, whose real production
source references `ScanPhoto` in the Room database entity file.
`ProductionCheckpointFilesTest` depends on the real repository and Android
calibration adapter through `PipelineJobCheckpointValidator`. The unnumbered
`CalibrationTest` depends on Android/OpenCV-backed `ImageLoader` and
`LensCalibration`. These were excluded from this supplementary runner and remain
part of the actual Gradle verification. The Android/device gates remain separate.
