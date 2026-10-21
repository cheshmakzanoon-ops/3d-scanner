# HOST sparse PLY export regression

## Scope and results

The new `RepositorySparseExportHostTest` executes actual generated Room DAOs/native SQLite,
`RoomJobStore` enqueue/acquire, production repository publication access, and
`ProjectPublicationService` commit. The preview and one-point PLY are explicitly synthetic
producer fixtures. They are not photographs or claimed reconstruction output.

The initial targeted attempt failed at Kotlin compilation, before test execution:
`lateinit` cannot hold the inline/value-class `JobId`. A fixture-only one-line change to
`Delegates.notNull()` preserved uninitialized-access detection. No production behavior was
changed to address that compilation failure.

The corrected focused Gradle run succeeded (35 seconds, log below). The executing owner
read and reported exact focused XML attributes: timestamp 2026-10-21T15:43:54.644Z,
2 tests, zero skips/errors/failures, 7.273 seconds. These remain in the tool transcript;
the owner confirms no separate raw XML copy was made. That focused XML was overwritten
by the subsequent full run before this report's preservation request arrived; this report
does not mislabel the later XML as the original focused report.

Independently preserved later XML: 2 tests, 0 skips, 0 failures, 0 errors, 0.421 seconds;
timestamp 2026-10-21T15:44:46.476Z. This verifies the two regression tests in the later run,
not the outcome of all other full-gate tests.

## Behavior established

- Real RUNNING durable job lease and current-publication identity are committed through the
  production publication transaction; job reaches COMPLETED and project result identity agrees.
- The immutable artifact set is byte-verified. Legacy flat preview/PLY files are absent,
  preventing accidental success through the legacy export path.
- A current-format sparse PLY appears in `RepositoryExportSource.available` as PLY /
  SPARSE_POINTS, with exact byte count and SHA-256.
- `materialize` copies the exact bytes; `validate` accepts them; the result remains current.
- Closing/reopening the file-backed database preserves publication and export behavior.
- Byte-verified but malformed PLY and dense_points content declared as sparse are not
  advertised as sparse exports; the valid diagnostics JSON remains available.

Not established: native reconstruction, camera/photo decoding, JNI/GPU execution, device
qualification, SAF/provider transport, process death or physical storage power-loss durability.
The separate native frontend/writer gate supplies native-writer interoperability evidence.

## Source and change identity

Base commit during report: `3db1aa0a0b0bbf5c6004afce3216471380c36254`, with reviewed regression
changes applied in the working tree. This is not a claim that this base commit contains them.

- Reviewed HOST test patch: `ec39c057657e97981c71393b38afaba8887fdec654bd7f1ab1e68db7d9ab4666`
- Fixture-only compile fix patch: `8febfc0c68b69b0fa0a3c42906486c2cfe5ac9717e3fe1b536fd6e0bfa1dbeec`
- Final test source SHA-256: `68d8b333e3d1d59d46e3c80eba362340cc6be979d449aab166c01121352de0d0`
- Parser source SHA-256 at observation: `4ab8ea4352830b28378d151418a93ce2e84dd7d7ed5dca022450e2c50f5c856c`

## Evidence identity

Raw logs remain local QA evidence; the report omits machine identity, proxy settings and
absolute workspace paths.

| Evidence | SHA-256 |
|---|---|
| `android-sparse-export-focused.log` (initial compile failure) | `112a84a0bb83ced1020a797036575f568edb8d1b8a8a8ac9e4236718727cca03` |
| `android-sparse-export-fixed-focused.log` (corrected focused success) | `0530bdc023e585e972e591f44910cc9057372d43fe7d023bc2d5dbffc10c10da` |
| Preserved later `TEST-com.realityforge.scanner.hostintegration.RepositorySparseExportHostTest.xml` | `55086dc4c506756528eea4dad0dc26eadce830469a19f1945940129857095c56` |
