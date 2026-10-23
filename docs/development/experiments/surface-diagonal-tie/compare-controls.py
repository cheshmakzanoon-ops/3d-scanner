"""Compare geometry separately from the intentionally bumped algorithm identity."""
import json, sys
from pathlib import Path
root=Path(sys.argv[1])
report={}
for case in ['rotated','reverse','translated','partial','curved','step','nontie']:
    before=json.loads((root/'baseline-controls'/f'{case}.json').read_text())
    after=json.loads((root/'canonical-controls'/f'{case}.json').read_text())
    changed=[key for key in sorted(set(before)|set(after)) if before.get(key)!=after.get(key)]
    report[case]={'changedFields':changed,'geometryFieldsEqualIgnoringVersion':all(key=='fusionVersion' for key in changed)}
print(json.dumps(report,indent=2))
(root/'comparison.json').write_text(json.dumps(report,indent=2)+'\n')
