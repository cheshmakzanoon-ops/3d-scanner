# Development contract

Read PROJECT_PLAN.md, docs/MILESTONE_STATUS.md and docs/NEXT_MILESTONE.md before continuing.
Use one main development branch. Do not invent a remote repository or push without a selected
destination. Keep runtime reconstruction offline; do not add upload, analytics or model
fetching without an explicit product decision.

Run CMake/CTest and pytest after native changes. Run actual Gradle compilation/lint and
Android instrumentation before claiming the app compiles or works on a device. Source
contract tests, mocked UI previews and host JNI syntax checks do not replace these gates.

Preserve the distinction between sparse points, dense points, meshes, textures and generated
geometry. Do not fill unavailable stages with fake success, stock objects, fabricated coverage
or hard-coded demonstrations. Synthetic fixtures must stay labeled synthetic.

Version cache formats and include inputs/settings/engine version in artifact identity.
Preserve originals. Never publish partial output as the current completed project result.
