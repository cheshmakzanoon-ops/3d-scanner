#!/usr/bin/env python3
"""Render a reviewable owner build-file integration without invoking AGP applied-script lint bugs."""
import argparse
from pathlib import Path

def render(base,fragment,label):
 marker='// Inline convention from '+label
 imports=[line for line in fragment.splitlines() if line.startswith('import ')]
 body='\n'.join(line for line in fragment.splitlines() if not line.startswith('import '))
 if marker in base:
  if body.strip() not in base:raise ValueError('Existing inline convention differs; owner review required')
  return base
 apply='apply(from = "../'+label+'")'
 base='\n'.join(line for line in base.splitlines() if line.strip()!=apply)+'\n'
 imports=[line for line in imports if line not in base.splitlines()]
 return ('\n'.join(imports)+'\n' if imports else '')+base+'\n'+marker+' (AGP lint applied-script UAST workaround)\n'+body+'\n'
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--base',type=Path,required=True);p.add_argument('--fragment',type=Path,required=True);p.add_argument('--label',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 a.output.write_text(render(a.base.read_text(),a.fragment.read_text(),a.label))
