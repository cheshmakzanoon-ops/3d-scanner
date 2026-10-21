# Isolated readiness gate (not yet guest-executed)

Run only after separately authorized API30 one-core boot, with the existing
current-boot `adb logcat -v threadtime` continuous capture already active:

```
python tools/android_runtime_readiness.py \
  --adb /absolute/path/to/adb --serial emulator-SERIAL \
  --log /evidence/current-boot-live-logcat.txt \
  --output /evidence/new-readiness.jsonl
```

No boot/install/security/settings/watchdog operations are performed. Exit0 means
only this readiness observation passed; not an application/device-quality test.
Exit124 is bounded NOT_STABLE; other exceptions are failure, never readiness.
The output must not already exist. Archive the script hash alongside evidence.

The 90-second continuous observation window and 600-second overall limit remain
unchanged. Services must return sensible values, user must be RUNNING_UNLOCKED,
and system_server/network-stack PIDs must be single, nonempty and unchanged
before/after each probe and throughout the window. A sampled check cannot prove
there was no unobserved PID reuse; process/lifecycle logs remain necessary.

Log cursor ignores history at initial attachment; each new critical event resets
the window, not permanently blocks it. Rotation/truncation or changed EOF anchor
also resets the window; timestamps are not used (guest clock changes cannot
rewind the gate). Critical partial lines are detected before newline. Bounded
log reads fail closed on malformed/oversized input. A log that does not grow for
30 seconds and probe gaps over30 seconds reset readiness. This conservative
freshness guard may reject a genuinely quiet healthy guest: do not silently
relax it to obtain a pass. It is not an emulator/application timeout change.

The script does not assert network connectivity from PID presence; current-boot
network-service connection evidence should be retained separately, and passing
the gate does not guarantee later stability. No application tests have executed
using this draft. The build owner alone owns boot and invocation.

Tests: `python -m pytest tests/test_android_runtime_readiness.py`


Boot completion must explicitly equal `1`. User-unlocked state is matched only
inside the current user's exact `UserInfo{id:...}` section, with exactly one
`State: RUNNING_UNLOCKED`. Duplicate/missing/ambiguous sections fail closed.
The current user is rechecked after service probes. This uses the actual
[Android11 UserManagerService dump format](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/pm/UserManagerService.java#4693),
not an assumed newer `cmd user is-user-unlocked` API.
Evidence includes `boot_completed`, `current_user`, `current_user_unlocked`,
`log_ok`, `probe_duration_seconds`, `probe_gap_seconds`, and `probe_gap_reset`:
a slow probe/log freshness failure is NOT evidence of a framework restart.
