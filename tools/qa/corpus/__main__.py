import argparse
import json
from pathlib import Path
from .manifest import validate, discover, audit_corpus, InvalidManifest
from .fixture import generate


def main():
    parser = argparse.ArgumentParser(description='Offline corpus validation and synthetic fixture generation')
    parser.add_argument('operation', choices=('validate', 'generate', 'discover', 'qualification'))
    parser.add_argument('path', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        if args.operation == 'generate':
            result = validate(generate(args.path), args.path)
        elif args.operation == 'validate':
            result = validate(json.loads(args.path.read_text()), args.path.parent)
        elif args.operation == 'discover':
            result = {'manifests': [str(p) for p in discover(args.path)]}
        else:
            receipts = audit_corpus(discover(args.path))
            real = [r for r in receipts if r['kind'] == 'real']
            result = {'schema_version': 1, 'status': 'BLOCKED_EXTERNAL', 'validated': receipts,
                      'blocker': 'Requires two authorized matte textured 360-degree physical objects, independent reference geometry, held-out/adverse scenes and 12/30/100/250-photo workloads; input validation alone cannot qualify quality',
                      'real_manifests_present': len(real)}
        code = 2 if result.get('status') == 'BLOCKED_EXTERNAL' else 0
    except (InvalidManifest, OSError, ValueError) as exc:
        result, code = {'status': 'FAIL', 'error': str(exc)}, 1
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True)+'\n')
    print(json.dumps(result, indent=2, sort_keys=True))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
