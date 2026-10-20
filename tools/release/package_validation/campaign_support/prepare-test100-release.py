#!/usr/bin/env python3
"""Create an exact final100 release integration checkout; never mutate owner checkout."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('source_sha')
p.add_argument('--phase', choices=['preliminary', 'final'], default='final')
p.add_argument('--refresh', action='store_true')
p.add_argument('--evidence-name')
p.add_argument('--direct', action='store_true', help='Require the committed reviewed wiring; make no overlay')
a = p.parse_args()
if not re.fullmatch('[0-9a-f]{40}', a.source_sha):
    raise SystemExit('Full frozen100 source SHA required')
root = Path('/workspace/campaign/test100-release')
evidence_name = a.evidence_name or 'release-'+a.phase
if not re.fullmatch(r'release-[a-zA-Z0-9_-]+', evidence_name):
    raise SystemExit('Expected a simple release evidence directory name')
evidence = Path('/workspace/campaign/evidence/100')/evidence_name
if evidence.exists() and any(evidence.iterdir()):
    raise SystemExit('Refusing to overwrite preserved evidence; choose a new --evidence-name')
if root.exists() and not a.refresh:
    raise SystemExit('Refusing to overwrite an existing test checkout')
if a.refresh and not root.exists():
    raise SystemExit('Refresh requires the existing isolated test checkout')
evidence.mkdir(parents=True, exist_ok=True)
def run(*args, cwd=None):
    return subprocess.check_output([str(x) for x in args], cwd=cwd, text=True)
if a.refresh:
    if run('git', 'status', '--porcelain', cwd=root).strip():
        raise SystemExit('Refusing to refresh a dirty test checkout')
    run('git', 'checkout', '--detach', a.source_sha, cwd=root)
else:
    run('git', '-C', '/workspace/agent-workspaces/100', 'worktree', 'add', '--detach', root, a.source_sha)
# Render the exact owned convention already proven under AGP8.11.1. Inline avoids
# the known AGP Kotlin applied-script lint crash without disabling lint checks.
rendered = evidence / 'app-release.gradle.kts'
run('python', root/'tools/release/package_validation/render_convention.py',
    '--base', root/'app/build.gradle.kts', '--fragment', root/'gradle/release-validation.gradle.kts',
    '--label', 'gradle/release-validation.gradle.kts', '--output', rendered)
if a.direct:
    if (root/'app/build.gradle.kts').read_bytes()!=rendered.read_bytes():
        raise SystemExit('Direct candidate must already contain the exact reviewed inline convention')
    if (root/'app/gradle.lockfile').read_bytes()!=(root/'gradle/dependency-locks/app.lockfile').read_bytes():
        raise SystemExit('Direct candidate module lock must equal the approved combined lock')
    run('python', root/'tools/source_manifest.py', 'verify', '--source', 'HEAD', cwd=root)
    patch, paths = b'', []
else:
    (root/'app/build.gradle.kts').write_bytes(rendered.read_bytes())
    (root/'app/gradle.lockfile').write_bytes((root/'gradle/dependency-locks/app.lockfile').read_bytes())
    run('git', 'add', 'app/build.gradle.kts', 'app/gradle.lockfile', cwd=root)
    run('python', root/'tools/source_manifest.py', 'generate', '--source', 'index', cwd=root)
    run('git', 'add', 'SOURCE_MANIFEST.sha256', cwd=root)
    run('python', root/'tools/source_manifest.py', 'verify', '--source', 'index', cwd=root)
    run('git', 'diff', '--cached', '--check', cwd=root)
    patch = run('git', 'diff', '--cached', '--binary', cwd=root).encode()
    paths = run('git', 'diff', '--cached', '--name-only', cwd=root).splitlines()
    if set(paths) != {'app/build.gradle.kts', 'app/gradle.lockfile', 'SOURCE_MANIFEST.sha256'}:
        raise SystemExit('Unexpected overlay paths: '+str(paths))
    run('git', 'commit', '-m', 'test(agent-100): inline reviewed release convention and enforce approved module locks', cwd=root)
(evidence/'release-overlay.patch').write_bytes(patch)
tested = run('git', 'rev-parse', 'HEAD', cwd=root).strip()
result = {'schemaVersion': 1, 'phase': a.phase, 'directCommittedSource': a.direct, 'campaignSourceSha': a.source_sha, 'testedSourceCommit': tested, 'testedOverlayCommit': tested,
          'releaseBuildProperties': {'realityForgeTestBuildType': 'release', 'candidateSigning': 'test'},
          'overlayPatchSha256': hashlib.sha256(patch).hexdigest(), 'overlayPaths': paths,
          'fragmentSha256': hashlib.sha256((root/'gradle/release-validation.gradle.kts').read_bytes()).hexdigest(),
          'moduleLockSha256': hashlib.sha256((root/'app/gradle.lockfile').read_bytes()).hexdigest(),
          'note': ('Exact clean committed100 source; no overlay.' if a.direct else 'Temporary test-only source; never pushed or merged into role branches.')+' Test signing only.'}
(evidence/'tested-source.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))
