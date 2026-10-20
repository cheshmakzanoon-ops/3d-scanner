"""Check current runbook version, local links, actual limits and safe CLI smoke.

No device/build capability follows from documentation checks. Exit nonzero on drift.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote

DOCS = ('README.md', 'docs/BUILDING.md', 'docs/ARCHITECTURE.md', 'docs/MILESTONE_STATUS.md', 'docs/NEXT_MILESTONE.md')


def check(root):
    failures = []
    def expect(value, message):
        if not value:
            failures.append(message)
    version = (root / 'VERSION').read_text().strip()
    gradle = (root / 'app/build.gradle.kts').read_text()
    declared = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    expect(declared is not None and declared[1] == version, 'VERSION differs from Android versionName')
    bodies = {name: (root / name).read_text() for name in DOCS}
    for name in ('README.md', 'docs/BUILDING.md', 'docs/ARCHITECTURE.md'):
        expect(version in bodies[name].split('## Historical')[0], name + ': current version missing/stale')
    sparse = (root / 'app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt').read_text()
    dense = (root / 'app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt').read_text()
    cap = re.search(r'dao\.photos\(projectId\)\.take\((\d+)\)', sparse)
    views = re.search(r'const val MAX_VIEWS\s*=\s*(\d+)', dense)
    expect(cap is not None and cap[1] == '12', 'Sparse cap changed; reconcile supported runbook limits')
    expect(views is not None and views[1] == '8', 'Dense cap changed; reconcile supported runbook limits')
    expect('MAX_SAMPLES = 100_000L' in dense and 'MAX_ATLAS_PIXELS = 4_500_000L' in dense, 'Dense budgets changed')
    expect('384 else 256' in dense and '512 else if (views > 4) 640 else 768' in dense, 'Resolution profiles changed')
    expect('twelve photographs' in bodies['README.md'] and 'eight registered views' in bodies['README.md'], 'README supported-limit disclosure missing')
    expect('Native computation remains\nnoninterruptible inside individual JNI calls' not in bodies['README.md'], 'Stale native cancellation statement')
    expect('It has not been executed' not in bodies['docs/BUILDING.md'], 'Stale CI evidence statement')
    expect('isMinifyEnabled = false' in gradle and 'R8 disabled' in bodies['docs/BUILDING.md'], 'Release minification disclosure drift')
    links = 0
    for name, body in bodies.items():
        # Exclude code fences; relative Markdown links must resolve to real files.
        prose = re.sub(r'```.*?```', '', body, flags=re.S)
        for target in re.findall(r'\[[^\]]+\]\(([^)]+)\)', prose):
            target = target.strip('<>').split('#', 1)[0]
            if not target or re.match(r'[a-zA-Z]+:', target):
                continue
            resolved = ((root / name).parent / unquote(target)).resolve()
            expect(resolved.is_relative_to(root.resolve()) and resolved.is_file(), f'{name}: broken/escaping local link {target}')
            links += 1
        for script in re.findall(r'(?:python3?|bash)\s+(tools/[A-Za-z0-9_./-]+\.(?:py|sh))', body):
            expect((root / script).is_file(), f'{name}: missing documented command {script}')
    for required in ('cmake --build build/host --parallel 1', ':app:assembleDebugAndroidTest', 'tools/run-device-tests.sh', 'reports/apk-validation.json', 'BLOCKED_EXTERNAL'):
        expect(required in bodies['docs/BUILDING.md'], 'Missing build/evidence instruction: ' + required)
    return {'schema': 1, 'status': 'FAIL' if failures else 'PASS', 'version': version, 'documents': len(DOCS), 'local_links_checked': links, 'failures': failures}


def smoke(root):
    results = []
    for script in ('tools/capture_audit.py', 'tools/check_apk.py', 'tools/benchmark_multiview_coverage.py'):
        run = subprocess.run([sys.executable, str(root / script), '--help'], cwd=root, capture_output=True, text=True, timeout=30)
        results.append({'script': script, 'status': 'PASS' if run.returncode == 0 and 'usage:' in run.stdout.lower() else 'FAIL'})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    report = check(args.root)
    if args.smoke:
        report['command_smoke'] = smoke(args.root)
        if any(item['status'] != 'PASS' for item in report['command_smoke']):
            report['status'] = 'FAIL'
    print(json.dumps(report, indent=2))
    return report['status'] != 'PASS'


if __name__ == '__main__':
    raise SystemExit(main())
