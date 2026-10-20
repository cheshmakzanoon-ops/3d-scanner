#!/usr/bin/env python3
import hashlib,json,os,re,subprocess
from pathlib import Path
root=Path('/workspace/campaign/test100-release')
evidence=Path(os.environ.get('RF_RELEASE_EVIDENCE','/workspace/campaign/evidence/100/release-final'))
source=json.loads((evidence/'tested-source.json').read_text())
records={}
for kind,name in [('app','app/build/outputs/apk/release/app-release.apk'),('test','app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')]:
 path=root/name
 output=subprocess.check_output([str(Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0/apksigner'),'verify','--verbose','--print-certs',str(path)],text=True,stderr=subprocess.PIPE)
 certs=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})',output)
 if len(certs)!=1 or not re.search(r'Verified using v[234][^:]*:\s*true',output):raise ValueError('Missing verified unique modern signature')
 records[kind]={'apkSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'signerCertificateSha256':certs[0].lower(),'modernSignatureVerified':True}
if records['app']['signerCertificateSha256']!=records['test']['signerCertificateSha256']:raise ValueError('App/test signing certificates disagree')
result={'schemaVersion':1,'phase':source['phase'],'sourceSha':source['testedOverlayCommit'],'status':'PASS','appTestSignerAgreement':True,'artifacts':records,'physicalExecution':'NOT_RUN'}
(evidence/'signer-agreement.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
