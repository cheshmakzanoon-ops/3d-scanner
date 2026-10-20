#!/usr/bin/env python3
import json, pathlib, re, subprocess
repo=pathlib.Path('/workspace/3d-scanner')
plan=(repo/'PRODUCTION_100_AGENT_EXECUTION_PLAN.md').read_text()
remote=subprocess.check_output(['git','-C',str(repo),'ls-remote','--heads','origin','refs/heads/agents/*'],text=True)
heads={line.split()[1].removeprefix('refs/heads/'):line.split()[0] for line in remote.splitlines()}
rows=[]
for n in range(31,61):
    section=plan.split(f'## Agent {n:03d}\n',1)[1].split('\n## Agent ',1)[0]
    branch=re.search(r'\*\*Branch:\*\* `([^`]+)`',section).group(1)
    root=pathlib.Path(f'/workspace/agents/{n:03d}')
    row={'agent':f'{n:03d}','branch':branch,'remote_sha':heads.get(branch)}
    if root.exists():
        row['local_sha']=subprocess.check_output(['git','-C',str(root),'rev-parse',branch],text=True).strip()
        row['checkout_branch']=subprocess.check_output(['git','-C',str(root),'branch','--show-current'],text=True).strip()
        row['remote_matches_local']=row['remote_sha']==row['local_sha']
        row['changes']=subprocess.check_output(['git','-C',str(root),'status','--porcelain'],text=True).splitlines()
        receipt=root/f'docs/agents/status/{n:03d}.md'
        if receipt.exists():
            text=subprocess.check_output(['git','-C',str(root),'show',row['local_sha']+':'+f'docs/agents/status/{n:03d}.md'],text=True)
            try:
                row['state']=json.loads(text)['state']
            except (ValueError,KeyError):
                state=re.search(r'^state:\s*(.+)$',text,re.M)
                row['state']=state.group(1) if state else 'RECEIPT_FORMAT_REQUIRES_REVIEW'
    rows.append(row)
pathlib.Path('/workspace/campaign/delivery.json').write_text(json.dumps(rows,indent=2)+'\n')
for row in rows:
    print(row['agent'],row.get('state','PENDING'),(row.get('remote_sha') or 'UNPUSHED')[:12],
          'MATCH' if row.get('remote_matches_local') else 'NOT_DELIVERED',len(row.get('changes',[])))
