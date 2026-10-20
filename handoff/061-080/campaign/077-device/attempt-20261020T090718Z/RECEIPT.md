# Agent077 device attempt

Result: **NOT_RUN — package installation timed out**. Device gate: **BLOCKED_EXTERNAL**.

Attempt: `attempt-20261020T090718Z`. Exclusive campaign `device.lock` covered readiness/install operations. The lock is released. No application or test source edits.

Device: software emulator `emulator-5556`; API 29; ABI `x86_64`; fingerprint `Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys`. `ro.kernel.qemu=1`, `sys.boot_completed=1`, boot animation stopped. No physical-hardware or human TalkBack result.

Build source supplied by root: `0c76ec2d2f9ab4dd3c7480036e9566b84307a515`. Observed Agent077 delivery head: `d5f4c3bccb1ab6fcb00ce3356775fc5315c1e671`. Source/build paths under app are unchanged between those revisions.

- App APK SHA256: `5830c15c4fcd7675dcb03b8414c6937f6671550fdc22225966057cd541d5eb02`; 118301749 bytes.
- Test APK SHA256: `22bd5f6a68b06ca1e79d82d5c611b3d87821f4f9bf6ed537c71d42b31a6a82a2`; 2239070 bytes.

The prior streamed installation failed with package-service Broken pipe; its original receipt is preserved at `../app-debug.apk.install.log`. This attempt used `adb -s emulator-5556 install --no-streaming -r -t /workspace/agent-work/077/app/build/outputs/apk/debug/app-debug.apk`. Transfer completed in 26.241 s at 4.3 MB/s, but package installation emitted no Success and hit its 180.109 s deadline. The test APK was not installed. `ActualAccessibilityTest` was not invoked; all 3 tests are **NOT_RUN**. Installation completion state remains unknown after timeout.

Basic shell checks were intermittent, although `system_server` PID 4585 remained observable and activity/package services were found. Logs before installation record Android system DeadSystemException, `/system/bin/sdcard` SIGABRT with `failed to sdcardfs_setup`, mount permission failures, FUSE timeout and watchdog delays. These pre-existing guest problems and the install timeout block device qualification; no app assertion failed in this attempt.

The timeout left the container ADB install client alive. Its exact argv and host PID 123590/container PID 541 were verified, then that client received TERM. The ADB server was retained. `container-clients-final.log` confirms no install client remains.

Full identities: `identity.json`. Exact commands, exit codes, UTC times and deadlines: `commands.json`; cleanup identity: `cleanup-command.json`. Install output: `install-0.log`. Readiness and before/after guest crash/system logs accompany these files. Machine-readable outcome: `result.json`. Artifact hashes: `SHA256SUMS`.
