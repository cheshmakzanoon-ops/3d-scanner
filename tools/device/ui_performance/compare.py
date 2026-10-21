#!/usr/bin/env python3
"""Approve a proposed baseline profile only against comparable complete physical reports."""
import argparse,hashlib,json
from pathlib import Path
from report import compare
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--before',type=Path,required=True);p.add_argument('--after',type=Path,required=True);p.add_argument('--profile',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 data=a.profile.read_bytes()
 if not data or not any(line.startswith((b'H',b'S',b'P',b'L')) for line in data.splitlines()):raise ValueError('Missing baseline profile rules')
 result=compare(json.loads(a.before.read_text()),json.loads(a.after.read_text()))
 result.update(profileSha256=hashlib.sha256(data).hexdigest(),beforeSha256=hashlib.sha256(a.before.read_bytes()).hexdigest(),afterSha256=hashlib.sha256(a.after.read_bytes()).hexdigest())
 a.output.write_text(json.dumps(result,indent=2)+'\n');raise SystemExit(0 if result['retainBaselineProfile'] else 1)
