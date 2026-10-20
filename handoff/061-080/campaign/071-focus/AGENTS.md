# Development contract

## Numbered parallel-agent assignments

When the user assigns you an agent number from 1 to 100, read
[PRODUCTION_100_AGENT_EXECUTION_PLAN.md](PRODUCTION_100_AGENT_EXECUTION_PLAN.md)
and execute its shared working agreement plus your exact `Agent NNN` section.
Normalize the number to three digits. The guide defines your role, exact branch,
owned files, implementation steps, integration handoffs and success criteria.
Implement, verify, commit and push your assigned work; do not stop at another plan.

For this user-authorized campaign, each agent works in its own branch and isolated
checkout. This explicitly supersedes the previous main-only rule for numbered
assignments. Agent 100 coordinates merges; agents 001–099 do not independently
merge or force-push main. Record evidence in `docs/agents/status/NNN.md` and keep
unrun physical-device or release gates explicit. The source checksum manifest
must be regenerated using the guide's mechanical-file protocol.

## Shared product and validation contract

Read PROJECT_PLAN.md, docs/MILESTONE_STATUS.md and docs/NEXT_MILESTONE.md before continuing.
Outside the numbered campaign, use one main development branch unless the user
instructs otherwise. Do not invent a remote repository or push without a selected
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
