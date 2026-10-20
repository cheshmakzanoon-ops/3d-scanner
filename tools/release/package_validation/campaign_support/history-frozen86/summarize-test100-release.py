#!/usr/bin/env python3
import hashlib,json,shutil,subprocess,xml.etree.ElementTree as E
from pathlib import Path
root=Path('/workspace/campaign/test100-release')
evidence=Path('/workspace/campaign/evidence/100/release-final')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
source=json.loads((evidence/'tested-source.json').read_text())
# Preserve generated Room schema output outside the source checkout; these newly
# generated untracked files are evidence, never silently added to source provenance.
schemas=root/'app/schemas'
if schemas.exists():
 tracked=subprocess.check_output(['git','ls-files','app/schemas'],cwd=root,text=True)
 if tracked.strip():raise ValueError('Tracked schema must remain in source')
 shutil.move(schemas,evidence/'generated-room-schemas')
source['workingTreeAfterBuild']=subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True)
if source['workingTreeAfterBuild']:raise ValueError('Unexpected source changes after build')
reports={name:json.loads((evidence/name).read_text()) for name in ['privacy-policy.json','privacy-bundle-universal.json','signer-agreement.json','attribution-apk.json','attribution-aab.json']}
assert all(report['status']=='PASS' for report in reports.values())
bundle=json.loads((evidence/'package-validation/splits/bundle-validation.json').read_text())
assert len(bundle['splits'])==85, len(bundle['splits'])
unit_results=list((root/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
unit_counts={'tests':0,'failures':0,'errors':0,'skipped':0}
for result in unit_results:
 suite=E.parse(result).getroot()
 for key in unit_counts:unit_counts[key]+=int(suite.get(key,'0'))
assert unit_counts['tests']>=35 and not any(unit_counts[k] for k in ['failures','errors','skipped'])
artifacts=json.loads((evidence/'package-validation/artifact-hashes.json').read_text())
artifacts['app/build/outputs/apk/androidTest/release/app-release-androidTest.apk']=sha(root/'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')
result={'schemaVersion':1,'status':'PASS','source':source,'artifactSha256':artifacts,
        'generatedApksValidated':len(bundle['splits']),'unitTests':unit_counts,'appTestSignerAgreement':reports['signer-agreement.json'],
        'packagedPolicy':reports['privacy-policy.json'],'bundleUniversalPackagedPolicy':reports['privacy-bundle-universal.json'],'attribution':{kind:reports['attribution-'+kind+'.json'] for kind in ['apk','aab']},
        'evidenceSha256':{str(p.relative_to(evidence)):sha(p) for p in evidence.iterdir() if p.is_file() and p.name not in {'execution.log','release-integration-summary.json'}},
        'runtimeInstrumentation':'NOT_RUN','productionSigning':'NOT_RUN','firstPartyDistributionLicense':'BLOCKED_EXTERNAL'}
(evidence/'release-integration-summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'status':'PASS','source':source,'generatedApksValidated':85,'unitTests':unit_counts,'summarySha256':sha(evidence/'release-integration-summary.json')},indent=2))
