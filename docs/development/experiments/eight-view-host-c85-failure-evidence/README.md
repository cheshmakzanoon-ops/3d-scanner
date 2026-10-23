# Eight-view HOST prerequisite failure — c85ff880

**FAIL; zero production-pipeline attempts. This is infrastructure evidence, not reconstruction quality.**

- Source: `c85ff88066e411f5f171c45f5c2f92077b3bc3f8`; tree `302c29df682db01586b9ceb03dba8ec7a79374a7`.
- Parent: `83fefee29b273d2132dd82b80c7071a4b015f3bc`.
- [GitHub run39997467140](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/39997467140), job120564631195, attempt1. Started2026-10-23T17:43:54Z; completed FAILURE, final API update17:46:18Z. No retry.
- The source/event/parent/three-path admission and source manifest passed. Preinstalled JDK17 and SDK platform36/build-tools36.0.0 were admitted; there was no SDKmanager command. Existing SDK license hashes remained unchanged.
- Original failure: `FileNotFoundError` naming an owned Gradle8.13 `caches/8.13/transforms/...` directory during `gradle-task-graph`, after85.932s. That command is incomplete and has no admitted exit result; retained raw stdout contains Gradle startup, not a completed task graph. The inventory-race interpretation is supported by controller source; the hosted receipt does **not** contain a Python traceback proving the exact raising line.
- Separate cleanup/restoration command failed with explicit `NDK not configured` / preferred `27.0.12077973`. The app source pins `28.2.13676358`; the default-resolution cause is not established by this log. No missing-package installation, pin change or successful restoration is claimed.
- Source worktree/status postchecks PASS, SDK-license postcheck PASS, owned-process cleanup PASS. Restoration remains a recorded secondary failure. `pipelineAttempts=0`; no current-source OpenCV/scanner native capability build, selected Robolectric invocation, JUnit events or eight-view reconstruction result occurred.

## Exact evidence

`artifact/` preserves all17 files from Actions artifact12519938503, including its original `SHA256SUMS`. The downloaded ZIP was10593B, SHA256 `70ea27b5d73bb55b3ef035fca8bb7a7ced56b670e78ef8c92d5e59aecf8dcbcf`; ZIP bytes are deliberately not republished here. Expanded contents are25595B. Every original manifest entry and command bytecount/SHA was verified.

`job.log` is the connector-decoded full job log. Its four ordered, unique `terminal-receipt` base64 chunks reconstruct12219B, SHA256 `5e78614cae2297c9cfccc375e5893e0b3989413be6a660203742a677da966511`, exactly equal to `artifact/receipt.json`. Artifact upload success is not test success. `verified-evidence.json` records these independent checks; `final-run.json` records the terminal API result.

Verify this compact package with `sha256sum -c SHA256SUMS`, then `(cd artifact && sha256sum -c SHA256SUMS)`. Reconstruct the ordered log frame and verify its stated bytecount/hash against `artifact/receipt.json` for cross-channel identity. No executables, libraries, SDK/JAR caches, native sources or fixture binaries are bundled.

This HOST diagnostic never substitutes for original Android/device/full-pipeline/performance/dense/surface qualification gates. Their outcomes are separate; no general release PASS is asserted.
