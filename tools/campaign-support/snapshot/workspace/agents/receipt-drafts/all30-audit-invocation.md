# Rerun after delivery receipts are frozen

Reviewed outside-checkout script: `/tmp/independent_campaign_manifest_audit.py`.
It reads local roster refs and immutable object blobs, not worktree files; it performs
no checkout, reset, merge, push or branch edits. It overwrites
`/workspace/campaign/independent-manifest-audit.json`, so preserve the previous file first.

Run from any directory after all30 receipt heads are committed/pushed and stable:

```bash
source /workspace/cloud-setup/activate.sh
cp /workspace/campaign/independent-manifest-audit.json /workspace/campaign/independent-manifest-audit-before-final-receipts.json
python /tmp/independent_campaign_manifest_audit.py
python - <<'PYCODE'
import json,subprocess
from pathlib import Path
r=json.loads(Path('/workspace/campaign/independent-manifest-audit.json').read_text())
s=r['summary']
assert s['branches_checked']==s['passed']==30 and s['failed']==0,s
assert s['refs_advanced_during_audit']==[],s
# The audit script itself does NOT exit nonzero on findings/ref drift and it
# reads local refs only. Explicitly enforce both assertions and remote equality.
refs=['refs/heads/'+row['branch'] for row in r['branches']]
raw=subprocess.check_output(['git','-C','/workspace/3d-scanner','ls-remote','--heads','origin',*refs],text=True)
remote={name:sha for sha,name in (line.split() for line in raw.splitlines())}
for row in r['branches']:
 local=subprocess.check_output(['git','-C','/workspace/3d-scanner','rev-parse',row['ref']],text=True).strip()
 assert local==row['sha']==remote.get(row['ref']),(row['agent'],row['sha'],local,remote.get(row['ref']))
print('PASS: all30 exact local/remote heads, manifest bytes, diff checks, bounded credential/build scan and no ref drift')
PYCODE
```

Use a new preservation filename for each later rerun. The bounded marker scan is
not a claim that every possible secret format is absent. The final integration
candidate ancestry/tree audit remains a separate gate.

Reviewed script SHA256: `c6c7392d864207f04f82269962091b78b3e3076a0967b03f45bce8530525fbcf`.
