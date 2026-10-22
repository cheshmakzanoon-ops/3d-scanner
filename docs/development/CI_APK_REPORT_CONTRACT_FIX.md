# APK report / CI collector contract correction

Recorded 2026-10-22 00:14 UTC. Original candidate:
`fdded23aed856229c679ba4c968160545007c32a`.

## Actual failure evidence

[Verify RealityForge run 39696643409, Android job 119555520470](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39696643409/job/119555520470)
completed with failure. Gradle assembleDebug, assembleDebugAndroidTest,
testDebugUnitTest and lintDebug step succeeded; explicit HOST Room integration and
the check_apk command also succeeded. The collector rejected the emitted JSON at
00:08:01 UTC: `CI evidence rejected: APK privacy/alignment validation failed`.

The actual schema-v1 JSON contains a permissions list without INTERNET or
ACCESS_NETWORK_STATE, `zipAlignment16k: true`, and 16 checked native libraries,
but lacks the `internetPermission` key that the collector requires to be exactly
`false`. This is a producer/collector schema mismatch, not evidence that an APK
network permission or alignment check failed. The overall Android CI gate remains
failed; a successful build command is not a successful complete CI run.

The original JSON is preserved without adding the missing flag:
`tests/fixtures/apk-validation-fdded23-ci.json`, SHA-256
`c57040896c079ebbac4e132cf60eb007a6d2a21aec1103355542b0eb0d5133af`.
It was extracted from the timestamped check_apk JSON in the job log, not generated
as a replacement receipt. The locally preserved job-log text SHA-256 is
`d492669cb21f784c9dc6c6dce6b375bd1007c327f39e21694b291601833d7cb3`.
CI APK SHA-256 reported by that checker:
`28d9e709bac352566e2e1756a4d386473f29f08986d743e1ef93ffd3a3956274`.

## Narrow correction

- The producer derives `internetPermission` from actual parsed manifest
  permissions. It rejects malformed permission names and checks both ordinary
  and SDK-23 permission declarations against the existing forbidden network set.
- The collector retains mandatory literal `internetPermission is False`, strict
  ZIP alignment, and nonempty ELF validation evidence. There is no legacy fallback.
- The collector additionally requires an explicit, well-formed list of qualified
  permission names and rejects INTERNET and ACCESS_NETWORK_STATE even if a false
  boolean attempts to hide them. Custom qualified application permissions remain
  supported. Missing, null, string, nonstring-element, empty-name, whitespace and
  contradictory permission evidence fail closed. An explicit empty list is known
  no-permission evidence, unlike a missing list.
- No Android app, JNI, native reconstruction, workflow, runtime permission or APK
  build input is changed by this fix; only host validation code and its tests.

## Regression evidence

The same final 30 regression cases were installed in the real repository before
changing the producer or collector: **19 failed, 11 passed**. The actual report
was fed through the real manifest-policy producer and collector using explicitly
synthetic file/JUnit fixtures; those fixtures do not qualify an APK or device.
The original missing-flag receipt is separately tested and remains rejected.

After the reviewed producer/collector patches and the existing synthetic helper's
explicit permissions field were applied: **30 new regressions passed**; combined
new contract, existing CI evidence and release package suites: **110 passed**
(1.94 seconds in the real repository). Exact logs are preserved in the local
handoff proof directory as `realrepo-red.txt` and `realrepo-green.txt`.
These are host regression results, not a new Actions result or installed-app test.

As of 00:14:50 UTC, the original run's native job was still executing Agent 081,
Android was failed and device was skipped. No rerun, dispatch or cancellation was
performed by the monitoring/fix agent. A later publication may supersede this run;
its terminal state must be reported separately rather than assumed.

## Exact five-file implementation snapshot

| Path | SHA-256 |
|---|---|
| `tools/release/package_validation/apk.py` | `c587eabf723f10050ddae01bd34ead22508a264aae9c529a8ad3cb883d146eed` |
| `tools/ci/validation/evidence.py` | `47734350a403187e2955d9ecaeee53216a5a3e873a9055e6ab6f0c8820447b9c` |
| `tests/test_ci_evidence_validation.py` | `ccf35558b9608b4dfbfbc5a59bc42b4cecf9d349088f5a626e4456db5e808bf7` |
| `tests/test_apk_ci_report_contract.py` | `0f4a26540331f534d69c6507e31e54de8571ac33ff841627615ee3b4701f0b7f` |
| `tests/fixtures/apk-validation-fdded23-ci.json` | `c57040896c079ebbac4e132cf60eb007a6d2a21aec1103355542b0eb0d5133af` |
