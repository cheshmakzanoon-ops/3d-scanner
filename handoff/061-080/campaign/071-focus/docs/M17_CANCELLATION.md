# M17 — Cooperative, operation-scoped CPU cancellation

## Scope

M16 already supports up to eight registered dense views, but tapping Pause could
only mark Kotlin's coroutine cancelled; long-running native stereo often kept
processing until it completed. M17 stops the same **active native operation**
by passing a unique, opaque, non-recycled handle through JNI. A process-scoped
registry holds `shared_ptr<atomic_bool>` tokens with at most 16 simultaneous
handles, while a `thread_local` scoped pointer isolates actual computation.

A cancelled worker exits via `rf::cancellation::Cancelled`, returned at top
level as `{ "error": "Reconstruction cancelled" }`. Cancellation isn't a
nonfatal mesh/texture error and never publishes a partially generated model.
Before and after the JNI call, Kotlin checks coroutine cancellation. Existing
scratch `.partial` outputs are removed in `finally`. Previously completed
exports and original imported photographs remain untouched.

## Verified native contracts

`ctest -R native_cancellation` verifies five cases: pre-cancelled worker,
nested scope restoration, top-level C ABI error, independently cancellable
threads, and live cancellation while a CPU plane sweep is running. The host
library is compiled with warnings as errors; unchanged reconstruction and
benchmarks must also pass.

## Android gate

`NativeSmokeTest.nativeCancellationTokensAreIsolatedAndRejectStaleHandles`
checks JNI begin/cancel/finish, simultaneous token isolation, rejection of
stale handles, clean cancellation output, and that a valid second job can
still compute. The `AdaptiveDensePipelineTest` explicitly holds its token
until all native work completes. CI must still assemble an APK, run lint/JVM
and instrumentation on emulator before M17 can be marked device validated.

## Remaining limits

- This is cooperative, not OS-process preemption; no hard real-time deadline.
- Front-end feature detection and sparse BA only observe Kotlin cancellation
  boundaries; the M17 native flag covers depth, surface and texture CPU loops.
- No checkpoint/resume yet, no real handset memory/thermal measurements, no
  comprehensive 360° physical-object geometry benchmark or Play release claim.
