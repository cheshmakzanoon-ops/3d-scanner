# Published source: scoped validation receipts

- [Dense incumbent correction](../cloud-dense-incumbent-validation.json): published
  `166b898dc262d49497a24f5018535659053fd85a`, tree
  `1b938a080af63676c01c97cb9e8dafefbd0ec44b`. Combined host run: **1,442 ordinary
  tests + 6 subtests, zero skips**. The earlier artifact-build source separately
  passed 46 CTest targets, 1,421 ordinary Python tests + 6 subtests, 770 Android
  unit tests (including 2 new policy tests), and 18 HOST integration tests.
- [CI diagnostics](../cloud-ci-diagnostic-validation.json): published
  `81a84a7739a5d4103d0477b4eef1fd1bc3dda55f`, tree
  `189250e2466b45cc635452c1ae383fa28d6d8cef`. Exact-tree full host run: **1,474
  ordinary tests + 6 subtests, zero skips**; focused run: **86 + 4 subtests**.
  Four workflow contracts and three YAML parses passed. These local results
  do not qualify the scanner engine under sanitizers or establish hosted CI success.

Historical test/build commit identities are retained, not relabeled as public
builds. Their recorded tree relationships were checked during preparation.
The engine/debug APK were built at
`b95b7a5958fd285c7c22f99ac41f3048a84e760f`, **not** the later published commit.
The six listed dense bindings matched build, tested and published sources; their
complete trees are not asserted equal. This validation-only APK does not replace
the [historical fdded23a engineering delivery](../engineering-apk-fdded23a-delivery.json).

Android build/lint passed, but lint's 77 warnings + 3 hints are not a warning-fix
claim: only 14 dependency-update notices disappeared in the offline comparison.
The final serial build reused already-executed JVM/Kotlin/lint results; earlier
failures/retries remain summarized. No fresh candidate HOST JNI relief replay,
scanner-engine sanitizer/device/physical qualification or 3D-quality improvement
is established. Later/separate checkpoint/provider/OpenCV/fixture repairs are
excluded. The unchanged observer produced **zero per-audit records** because
relocated temporary outputs missed its fixture matcher; ordinary scanner/tests
were unchanged by that relocation. Deliberate sanitizer diagnostic controls do
not constitute scanner-engine qualification.

## Read-only verification

Use Python 3; the verifier has no third-party dependencies. From the repository root:

```sh
python reports/published-validation/verify.py --repo .
```

**Public/default mode:** requires both published commit objects, their exact trees
and all their recorded source bindings. Retained test/build aliases are not on
published history and are not expected in a fresh public clone. An absent retained
role is reported as `NOT_CHECKED_RETAINED_COMMIT_UNAVAILABLE`, with overall
`sourceLinkScope: PARTIAL`; it is not silently verified. Every available retained
object is still fully checked. A wrong tree, wrong source binding, invalid
published commit, malformed object or Git error always fails.

**Full retained-source mode** is for an evidence custodian who already has the
original test/build objects and artifacts; it does not fetch or publish them:

```sh
python reports/published-validation/verify.py --repo . \
  --require-retained-source-objects \
  --native-library "$VALIDATED_HOST_LIBRARY" \
  --debug-apk "$VALIDATED_DEBUG_APK"
```

This additionally requires every retained source object. Artifact options are
independent and usable in either mode: supplied engine/APK bytes are stream-hashed,
including both packaged native-library entries. Omitted artifacts report
`NOT_CHECKED_NOT_SUPPLIED`, never `PASS`. Byte checks do not repair unavailable
build-source evidence or turn `PARTIAL` into complete source verification.

[Schema v1](receipt.schema.json), cross-receipt identities and source references
are always checked. Its bundled validator supports exactly this schema’s keyword
subset and rejects unknown keywords; it is not a general JSON Schema engine. No checkout edits, builds, test reruns, remote-ref checks or
network fetches are performed. Raw logs, private reports, machine inventories
and binaries are intentionally excluded. Evidence digests identify retained
summaries/outputs; hash checks are not independent reproducible-build attestations.

Run the synthetic verifier regressions (not scanner tests) with:

```sh
python -m pytest -q tests/test_published_validation_receipts.py
```
