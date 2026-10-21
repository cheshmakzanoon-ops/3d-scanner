# Private native trace capture: minimal reproducibility delta

This is external HOST evidence, not a production change, APK rebuild, physical-device qualification, or replay-equivalence claim. The baseline COMPACT three-photo core assertions remain in place. A private trace-enabled engine and original-source JNI shim with explicit RUNPATH provide an input snapshot and exact live raw mesh; live counters are disabled.

## Sources
Copy `tools/relief_quality_validation` at commit `124deebbccb341cb04af9676a2385b79953d65ab` into a new external directory. Verify `source-pins.json` base hashes and apply `source.patch` with `patch -p1`; verify captured-source hashes. Unchanged loader runner/bridge/prefix files are not duplicated. The patch includes the fifth-checkpoint capture, actual JVM library mapping proof, bounded supervisor, and full post-restoration pin verification. `compile-pid-fix.patch` documents the minimal failed-first-helper correction; it is already included in `source.patch` and must NOT be applied twice. Reverse it only to reconstruct the preserved failed source.

The private machine-specific configuration (582 artifact records including572 provenance records) is intentionally not duplicated. Its hash is recorded; the full original configuration and captures remain retained externally. Prepare explicit reviewed configuration from source-build receipts and the validator schema; never replace hashes with guesses. Native maps helper intentionally pins exact reviewed private paths/identities. Rebinding paths/toolchain is a new freeze requiring review, not a portable automatic setup.

## Bounded invocation
`python validate.py --config <reviewed-config> --config-sha256 <frozen-sha> --expected-commit <clean-40hex-HEAD> --output <fresh-owned-output>`

One dedicated JUnit test only;150s core wait/180s JUnit/5min task watchdog,1GiB JVM heap (not RSS),one Gradle worker,strict offline dependency verification. Normal flags: `-PcandidateSigning=test -PrealityForgeTestBuildType=debug -PrealityForgeInstrumentationQa=false`. No emulator, LD overrides, production policy changes, truth injection, or original native mutation. Ordinary test compilation is restored after the stopped process group; terminal success requires restoration and full bound-input equality.

Original five quality artifacts remain unchanged in inventory. New sibling `native-trace` owned0700 contains `input.rffuse` and `live-mesh.bits`, each at most12MiB, plus before/after maps receipts. Actual JVM mapping path/device/inode/hash evidence binds engine1b032 and shim4bb816. These are separate private capabilities; original engine4384 and shim427 remain unchanged.

## History
First invocation failed compile because `ProcessHandle` is not in the Android compile API; no JVM capture occurred. The bounded `/proc/self/status` actual PID successor compiled and ran successfully. Sanitized receipts retain original receipt hashes, assertions, bounds, artifact identities, and restoration status. Raw captures, complete logs, machine config and large native binaries are deliberately omitted. StageC must separately establish live/original/trace raw-bit and JSON/PLY equivalence before interpreting counters.
