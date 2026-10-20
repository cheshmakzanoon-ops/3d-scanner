# Actual Android UI evidence runner

Run against a frozen clean source checkout and the APK pair built from it. The
build owner supplies a small JSON receipt with `source_sha`, `apk_sha256`, and
`test_apk_sha256`; hashes are lowercase SHA-256 and source is the full Git SHA.
An arbitrary existing APK is not proof that a different source commit passed.

```sh
python tools/android-ui-e2e/run.py \
  --source-sha <full-candidate-SHA> \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --build-receipt build/ui-build-receipt.json \
  --serial <authorized-device-serial> \
  --output build/ui-evidence/new-run
```

`--adb /path/to/session-or-container-adb-wrapper` is supported. The session
wrapper must be able to read both APK paths and write pulled screenshot paths.
The runner installs with `-r -t`, preserves app data, and does not uninstall or
clear projects. UI tests must create and clean up their own explicitly synthetic
projects. Execute only on an authorized test device with preserved originals.

By default two real-control scenarios run: create/rotation/reopen/destructive
cancel, and synthetic photo import/reconstruction/view/export/reopen. Use
`--scenario ID` repeatedly for a subset or `--all-required` for the complete
assignment matrix. Every row records `PASS`, `FAIL`, `NOT_RUN`, or
`BLOCKED_EXTERNAL`; unselected scenarios remain `NOT_RUN`. Top-level `status`
covers the selected scenarios, while `assignment_status` includes every required
row. A successful subset does not close the assignment.

`--scenario shared_provider_import_fault` traverses DocumentsUI to consume
owner 088's registered `Synthetic QA faults` provider, imports its normal JPEG,
then rejects truncated/revoked inputs while retaining exact original hashes.
The integrated test APK must contain the pinned 088 provider and test manifest;
080 does not register a second provider. This row does not prove interrupted
export or process-kill recovery.

`permission_denial_regrant` runs the owned `CameraPermissionJourneyTest` directly.
Before instrumentation starts, the host executes and records `pm revoke` and
`pm clear-permission-flags ... user-set user-fixed`; reset failure prevents the
test from starting. The test asserts initial denial and drives real system denial
and regrant controls. Revocation never occurs inside the instrumented process.

`changed_inputs_and_repeated_actions` drives actual DocumentsUI cancellation,
keeps the same C1 request UUID/project/revision across Activity recreation while
the picker remains foreground, consumes088's normal fixture, imports it again
without duplicate publication, and imports a second explicitly synthetic input.
All four requests must have distinct UUIDs; another test-created project stays
untouched, original hashes stay exact, and revisions advance only for new input.
`background_return` uses the real HOME accessibility action, observes the launcher,
returns to the existing app task, recreates and reopens through visible controls,
then checks the same project/revision/original hashes. These tests require actual
001 C1 production state and088's test provider in the combined APK. They do not
claim lock/unlock, process death, in-flight cancellation or late camera callbacks.

`screen_lock_return` requires an awake, unlocked fixture with no secure keyguard.
It issues the real `GLOBAL_ACTION_LOCK_SCREEN`, waits until KeyguardManager
reports locked and PowerManager reports noninteractive, then invokes external
shell `input keyevent 224` and `wm dismiss-keyguard` only after rechecking that
the fixture is nonsecure. It must observe awake/unlocked before returning to the
app and asserting the same UUID/revision/original hashes. A credential-protected
fixture fails its precondition; no credential bypass is implemented. This is a
UI-state lock/wake test. `background_job_recovery` remains the separate external
008/088 gate for interrupted execution, job reconnect and process recovery.

Exit codes are 0/PASS, 1/FAIL, 2/BLOCKED_EXTERNAL, 3/NOT_RUN. `adb` returning zero
does not qualify a test: started/completed identities, counts, no skips, the
JUnit summary, and `INSTRUMENTATION_CODE: -1` must all agree. Empty/stale runs,
wrong methods, crashes and assumptions fail closed. Each output directory is new;
existing evidence is never overwritten. Candidate/APK/test-APK hashes, API/ABI,
device fingerprint/identity hash, exact commands, raw runner logs and screenshots
are retained in `evidence.json` and adjacent files.

The test receives `rfEvidenceRunId`, a lowercase 32-character UUID hex string.
Screenshots must be real UiAutomation captures written as
`/data/local/tmp/rf-ui-<rfEvidenceRunId>-<safe-label>.png`. Only that invocation's
prefix is pulled, with safe-path, byte-budget, dimensions and PNG CRC checks.
Previous screenshots on the device are retained and cannot qualify a new run.

## Agent 088 controller adapter

`--process-controller /path/to/owner088-controller` consumes the shared external
process/provider infrastructure. This tool does not duplicate kill, relaunch,
permission, provider or process-control implementations. Without that integration
the corresponding rows are `NOT_RUN`.

The executable reads one JSON request on stdin and writes one JSON response on
stdout. Diagnostic text belongs on stderr. Request fields are `schema_version:1`,
`scenario`, unique `run_id`, `candidate` with the source/APK hashes,
`device_identity_sha256`, the adb executable and serial, `required_evidence`, and
`request_sha256` (SHA-256 of canonical sorted compact JSON before adding that
field). It must perform the named actual UI scenario through owner 088's shared
controller and supply the following response:

- Echo version, scenario, candidate, device hash and request hash exactly.
- Return `status`; unavailable gates need a concrete `reason`.
- A PASS needs nonempty identical `original_hashes_before/after` maps, unchanged
  valid `previous_result_sha256_before/after`, an identified
  `dataset_identity_sha256`, and `synthetic:true` for these controlled scenarios.
- Supply `test_identity`, `instrumentation_stdout`, and
  `instrumentation_returncode` showing that exact actual UI test executed and
  passed. Zero tests, skipped tests and stale source/device records fail.
- Capture at least one real PNG with the request's `run_id` prefix. It is pulled
  independently by this runner rather than trusted as an arbitrary local path.

This is the proposed 080/088 adapter contract. Owner 088 must accept/implement it
or deliver an agreed adapter before its scenario can be recorded PASS.

## Physical camera entrypoint

Select `--scenario physical_capture --hardware-evidence-id <operator-receipt>
--physical-scene-authorized` after positioning an authorized nonprivate scene.
The receipt is a 1–128 character identifier using letters/digits and `._:/-`.
The owned default is
`PhysicalCaptureJourneyTest#capturesWithRealCameraAndReopensTheSameOriginal`;
an arbitrary replacement class cannot qualify this row. Emulator/QEMU/generic
identities are refused. The host resets CAMERA before instrumentation and passes
the exact run/operator/fingerprint/device identity plus explicit scene consent.
The test itself rejects emulator identities, checks a back-facing camera, grants
permission through the actual system dialog, taps the enabled `Take photograph`
control, and waits for real CameraX publication into its test-created project.
It checks the JPEG signature/dimensions/length and SHA256, then recreates/reopens
and requires the same original and revision. The runner additionally validates the
bounded `rfPhysicalCaptureEvidence` terminal result: exact run/device/operator,
project/photo UUIDs, one revision increment and identical original/reopened hashes.
Missing or stale metadata fails even if JUnit reports success. The JPEG is never
exported; the success screenshot is captured only after returning to the library,
and automatic failure screenshots are disabled for physical capture.
Synthetic/emulator success never qualifies real capture, geometry quality,
memory, thermal behavior or commercial readiness. Manual camera/operator and
assistive-technology evidence remain separate required gates.
