# Process-scan timeout attribution, not a preparation fix

Source: `0a6d4ec8061de7f32a6d9ece1d38afe58302651f`, tree
`0ce96035e03917393b0097ebb3aca62fdd72ac2c`.
HOST run 40180078458 archive SHA-256:
`eb291b67fd6db613f0ff7ac6a9e075c339d462a563e6c1faea6240368578558e`.

The retained command record identifies `header-provider-17-version` with zero
output bytes and no completed exit, reaching 10.001722 seconds against its
unchanged ten-second command phase. Forty-six ownership scans consumed only
0.012665 seconds; selector waits consumed 8.849633 seconds and preflight/loop
disk guards together 1.136217 seconds. Cleanup and restoration passed. This is
not evidence of an expensive ownership traversal. The scan's initial enclosing
deadline check was simply reached before the command loop's later timeout check.

The former message `Process ownership scan deadline` did not distinguish an
already exhausted command/global budget from the scan's own traversal bound.
The diagnostic-only change appends a fixed `deadlineScope` label:
`COMMAND_PHASE`, `OVERALL_WORK`, `OVERALL_CLEANUP`, or
`OWNERSHIP_TRAVERSAL`, plus whether scan admission had begun. It preserves the
original Deadline exception type, TIMEOUT status, refusal prefixes, all deadline
calculations, the two-second traversal cap, cleanup reserve and identity checks.
No command is allowed more time and no successful result is inferred.

Tests use deterministic clocks and synthetic owned-PID records. They perform no
live system-process/header audits and cover enclosing versus traversal limits,
boundary ties, cleanup independence from the command phase, successful ownership
registration and PID/starttime mismatch refusal.

This closes **misleading timeout attribution only**. The artifact does not
explain why the native Clang17 version probe produced no output before its bound.
No performance fix, deadline increase, preparation success or real-photo
reconstruction result follows. The underlying preparation timeout remains unresolved; no new reconstruction
outcome is available.
