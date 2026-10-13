# M18 — Per-reference depth checkpoints and interrupted-scan recovery

This milestone **resumes expensive per-camera plane-sweep depth maps** between
reconstruction attempts. It does not persist intermediate triangle meshes or
texture atlases, guarantee a watertight model, or restore an Android process
without the user restarting the scan. It is not a substitute for real-phone tests.

## Correctness and bounds

- The Kotlin identity is a SHA-256 digest over the engine-specific format
  version, selected sparse artifact key (which includes the project revision),
  every rectified RGBA byte, calibrated camera matrix, width/height, camera
  count, near/far bounds, plane count and sampling stride. A single changed
  input results in a different identity. Photos never leave the device.
- App-private checkpoint directories have a 64-lowercase-hex name. Each native
  per-camera file contains format magic/version, dimensions, exact identity,
  reference index, a length bound and a CRC32 over camera-z depth and
  confidence floats. The native reader rejects truncated, corrupt, missing,
  wrong-resolution or wrong-identity files; reconstruction recomputes just
  those references. Accepted values are finite and bounded.
- Native files are written through a temporary sibling and atomically renamed
  **after** an entire reference was computed and cancellation rechecked.
  No partial map is published as a finished reference or as a GLB/PLY model.
  Incomplete `.partial-*` files may remain after process termination and are
  ignored. The app retains only the current and one recent identity directory.
- The native checkpoint scope is thread-local and nested safely under the
  M17 token. No global cancellation or checkpoint session leaks between
  concurrent callers. Depth results are recombined and verified via the full
  original reciprocal camera-z check and conservative meshing/UV algorithms.
  JSON exposes `resumedDepthMaps` and `writtenDepthMaps` as diagnostics.
- File flush plus rename addresses process interruption, not an absolute
  power-loss durability guarantee. Cached files are app-private and are
  **not** included in GLB export or user capture bundles.

## Gates and limits

Host C++ tests cover cold/warm runs, per-camera replay parity, corruption,
truncation, wrong identity and size, cancelled sessions, and unchanged
accepted checkpoints. JVM tests cover input sensitivity, invalid identities
and bounded pruning. The Android emulator tests the JNI path with a real
per-reference file saved to app cache, a second reconstruction that reuses
it, and one corrupted map triggering selective recomputation.

This is still an offline bounded stereo engine with up to eight depth views,
12 sparse views, and open textured geometry in arbitrary scene units. Actual
ARM64 camera captures, process-kill recovery on physical devices, thermal/RAM
budgets and complete 360-degree reconstructions are outstanding.
