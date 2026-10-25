# External artifact-consumer process boundary

`validate.py` runs two independent tools over serialized export files:

- `node khronos.cjs <asset.glb>` loads the pinned glTF Validator JavaScript package
  and validates GLB bytes; external resource resolution is refused.
- `blender --background --factory-startup ... blender_roundtrip.py -- <input> <output>`
  uses Blender's import/export operators on GLB, OBJ, PLY or STL files.

Neither child script imports or loads the scanner's candidate native library.
Their environments therefore remove **only `LD_PRELOAD` and `LD_AUDIT`**, on a
copy, so candidate runtime injection does not instrument unrelated host tools.
All other variables, including sanitizer options and library selection, remain
unchanged. Parent pytest/Python, ctypes reconstruction, native fixtures, JNI
executions, and deliberate sanitizer baseline controls retain their original
runtime environments. This helper is private to these two artifact launches;
it is not a generic Python/JVM environment policy.

Nonzero exits, signals, missing output, malformed reports, validator errors, and
round-trip mismatches remain failures. Timeouts, acceptance checks, required tests
and sanitizer phase accounting are unchanged. This ownership correction is not a
sanitizer pass, a leak allowance, or a reconstruction-quality qualification.

If either consumer script is changed to load scanner native code, its process
classification must be reviewed before reusing this environment boundary.
