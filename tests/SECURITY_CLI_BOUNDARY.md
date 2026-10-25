# External security-tool integration ownership (test only)

Six real scanner fixtures use `security_cli_boundary.scan_external` to execute
exact reviewed `tools/security/scan.py` source via its actual CLI. It calls the
unchanged `scan()` with the same root, requirements and suppression inputs. The
installer-owned scanner venv still must pass the production exact-version probe
before real Bandit, detect-secrets or pip-audit executes. No finding is mocked.

This child closure owns external host-tool functional validation, not candidate
C++ instrumentation. Its copied environment excludes injected loaders and Python,
Git and candidate-library routing. The driver uses `-I -S`; external scanner
children retain their installed site packages, with user site/cwd insertion and
bytecode writes disabled. Sanitizer option values are unchanged. Parent pytest,
all native/JNI/library tests, deliberate sanitizer baselines, and production
security-tool environment behavior remain unchanged. No leak suppression, exit
waiver, full sanitizer pass or leak-free external-tool claim follows.

Admission binds reviewed driver bytes, the current host interpreter, the selected
installer `security-python` venv and explicit reviewed security-lock hash. An
existing full installation receipt is checked when present; a security-only use
of the existing installer venv function need not pretend Blender/Node/scientific
tools were installed. Package snapshots are stability checks, not proof of the
installer invocation; its lock-verified install log remains separate evidence. Startup hooks, unreviewed bytecode, symlinks and unsafe package ownership
fail closed. Pre/post snapshots detect persistent closure changes; these are not
an attestation against a hostile same-uid host or concurrent replace-and-restore.
External installed packages and the host stdlib remain trusted dependencies.

The real driver writes a fresh bounded report outside scanned source; stream
output is private and not propagated in failure messages. Missing/malformed or
identity-mismatched reports, signals, timeouts, output overflow and mismatched
status/exit codes fail. Flag-only SIGINT/SIGTERM handling preserves owned-session
cleanup through spawn and reap; original handlers/signals are restored/delivered
after cleanup. WNOWAIT retains the leader identity until group termination.
PASS/FAIL/NOT_RUN remain exactly 0/1/2. An exit of 1 is
not ignored: it must match a security-finding FAIL, and every original test
assertion remains. The canonical-identity fixture additionally requires actual
static/secrets PASS and intentional missing-dependency NOT_RUN.

All mocked mutation, missing-tool, source/policy/requirements and native controls
remain in-process as before. The prior sanitizer version-probe failures remain
valid historical failures; external CLI results do not retrospectively relabel
those runs. Local adversarial/synthetic CLI checks do not replace executing the
six fixtures with the actual pinned installed scanners.
