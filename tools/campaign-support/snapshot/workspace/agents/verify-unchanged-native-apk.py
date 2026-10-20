#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,sys,zipfile
baseline=Path('/workspace/agents/final-artifacts/2f18a4f5a87139c38ee388f6c70af33e0fb247f7-optimized-native-20261020T102013Z/app-debug.apk')
folder=Path(sys.argv[1]);current=folder/'app-debug.apk'
with zipfile.ZipFile(baseline) as before,zipfile.ZipFile(current) as after:
    native_before={n for n in before.namelist() if n.startswith('lib/') and n.endswith('.so')}
    native_after={n for n in after.namelist() if n.startswith('lib/') and n.endswith('.so')}
    assert native_before==native_after,(native_before,native_after)
    assert len(native_before)==12,len(native_before)
    evidence={}
    for name in sorted(native_before):
        a=before.read(name);b=after.read(name)
        assert a==b,name
        evidence[name]={'sha256':hashlib.sha256(b).hexdigest(),'bytes':len(b),'payload_identical':True}
report={'baseline_source_sha':'2f18a4f5a87139c38ee388f6c70af33e0fb247f7',
        'baseline_apk_sha256':hashlib.sha256(baseline.read_bytes()).hexdigest(),
        'source_sha':(folder/'reviewed-source.sha').read_text().strip(),
        'apk_sha256':hashlib.sha256(current.read_bytes()).hexdigest(),
        'classification':'All12 native APK entry payloads, including both optimized realityforge ABIs, are byte-identical to the prior reviewed optimized build.',
        'native_libraries':evidence}
(folder/'native-payload-identity.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'native_libraries_identical':len(evidence),'source_sha':report['source_sha'],'apk_sha256':report['apk_sha256']},indent=2))
