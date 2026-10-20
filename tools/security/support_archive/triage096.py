"""One-time explicit reviewed triage for named integrated desktop QA sources.
This is outside the repository and is never a scanner filter or an auto-baseline.
Unknown files/rules/field formats stop review. No suspected values are printed.
"""
from pathlib import Path
import json,re,hashlib,base64,subprocess
ROOT=Path('/workspace/agent-workspaces/100/tools');OWN=Path('/workspace/agent-workspaces/096/tools')
EXPIRY='2026-11-20'
PURPOSES={
'agents/campaign.py':'Local Git roster, immutable source ancestry and source-owner/evidence checks.',
'agents/integrate.py':'Operator-authorized reviewed Git merges; explicit --check argv is an operator action, never photograph/model text.',
'ci/validation/evidence.py':'Current-run CI evidence sealing and Git identities; JUnit XML is produced by local test runners and bounded to64MiB before parsing.',
'dependencies/assemble_notices.py':'Recreates approved notices; official HTTPS origin bytes must match the committed content hashes before publication.',
'dependencies/install_qa.py':'Operator-selected external QA install root; exact committed hashes/publisher checksums verify HTTPS downloads before extraction/execution.',
'dependencies/inventory.py':'Operator-owned Gradle resolved/cache POMs, license metadata and package attribution; POMs are dependency-build inputs, not imported photographs/XML.',
'device/capture_validation/qualify.py':'Explicit serial-bound, operator-authorized capture test commands against the owned instrumented APK.',
'device/compatibility/runner.py':'Explicit serial-bound compatibility checks; caller chooses trusted ADB/SDK executable and candidate APK.',
'device/failure_recovery/stdio_controller.py':'Candidate/APK/test-APK/device/nonce-bound operator UI fixture controller. Remote shell argv are shlex-quoted; fixed hex run IDs and validated live PID bound its explicit private ACK/kill actions. Missing fixtures remain NOT_RUN, never substituted.',
'device/failure_recovery/controller.py':'Operator-authorized private test-provider/process fault injection; local argv runner, not remote source execution.',
'device/offline_privacy/audit.py':'Trusted SDK/aapt output for explicitly selected candidate APK; constructs elements from aapt text and does not parse external XML entities.',
'device/offline_privacy/journey.py':'Explicit operator-authorized serial-bound installed-APK offline test methods; no captured image text becomes host shell code.',
'device/resource_profiling/profile.py':'Explicit selected serial and ADB resource-counter commands; private trace output, no runtime app service added.',
'device/ui_performance/run.py':'Operator-authorized Gradle/ADB benchmark commands on the identified test device; results are separate from physical qualification.',
'device/ui_performance/validate_target.py':'Manifest strings are produced by selected trusted SDK from the locally built target/test APKs, checked for package/signing/profile policy.',
'docs/check_runbook.py':'Only three fixed tracked --help smoke scripts execute; Markdown command text is not executed.',
'qa/export_interoperability/provider_matrix.py':'Pinned immutable local Git snapshots and seven actual writer tests; XML is freshly generated pytest output, not user-selected XML.',
'qa/export_interoperability/validate.py':'Pinned external Node/Khronos and Blender QA readers use fixed scripts and asset paths as separate argv, with bounded timeouts.',
'qa/fuzz/run.py':'Checked source/compiler/native-library buildstamp plus bounded Clang/libFuzzer targets and explicit argv; fresh evidence and crash checks fail closed.',
'qa/geometry_quality/__main__.py':'Post-reconstruction Git identity only; independent mesh/photo data is not executed.',
'qa/geometry_quality/baseline.py':'Four fixed committed geometry benchmark scripts run through the selected Python; subprocess outcomes are retained.',
'qa/native_regression/run.py':'Explicit compiler/CMake/CTest/pytest commands from the fixed native matrix; errors and source identities are recorded.',
'qa/texture_quality/__main__.py':'Post-reconstruction Git identity only; independent texture data is not executed.',
'qa/texture_quality/baseline.py':'The fixed committed Middlebury gate runs with explicit argv and selected native library; legacy and independent outcomes stay separate.',
'qa/texture_quality/compact.py':'Post-reconstruction Git identity only; selected native library is an explicit developer choice, never photo-provided executable code.',
'release/package_validation/apk.py':'Trusted SDK manifest/resource XML output for a locally built candidate; policy rejects unsafe attributes and does not fetch XML entities.',
'release/package_validation/bundle.py':'Operator-selected SDK and checksum-pinned bundletool run with explicit argv; signer/artifact outcomes are checked.',
'release/package_validation/candidate.py':'Explicit operator-authorized Gradle candidate build, with checked command exit and no store publication.',
'release/qualification/aggregate.py':'Local Git candidate/source cleanliness and receipt checks use explicit argv; no input is interpolated into a host shell.',
'release/qualification/branches.py':'Local immutable Git refs/receipt reads only; no branch content is executed.',
'release/qualification/journey.py':'Operator-authorized selected physical/emulator serial and exact installed test methods; fixed argv and raw event evidence.',
'security/scan.py':'Pinned selected scanner Python/modules plus local Git source identity, explicit argv and redacted JSON; no scanned source/credential is executed.',
'source_manifest.py':'Local Git index/blob reads for exact tracked-set hashes; no tracked source is executed.',
'check_apk.py':'Legacy selected SDK package validation uses checked explicit argv and operator-owned artifact paths.'}
JSON_FILES={
'dependencies/evidence/pytest-9.0.3-provenance.json','dependencies/evidence/pytest-security-update.json',
'dependencies/evidence/aapt2-provenance.json','dependencies/evidence/benchmark/extracted-notices.json','dependencies/evidence/benchmark/inventory.json','dependencies/evidence/benchmark/sbom.cdx.json','dependencies/evidence/extracted-notices.json','dependencies/evidence/host-tools.json','dependencies/evidence/interop-host-lock-input.json','dependencies/evidence/inventory.json','dependencies/evidence/notice-origins.json','dependencies/evidence/opencv-sdk-provenance.json','dependencies/evidence/provenance.json','dependencies/evidence/qa-host-tools.json','dependencies/evidence/qa-install-validation.json','dependencies/evidence/release-aab-attribution.json','dependencies/evidence/release-apk-attribution.json','dependencies/evidence/sbom.cdx.json','dependencies/evidence/security-host-tools.json','dependencies/evidence/tests/extracted-notices.json','dependencies/evidence/tests/inventory.json','dependencies/evidence/tests/sbom.cdx.json','dependencies/qa-tools.json','qa/texture_quality/fixtures/middlebury-overlap.json'}
HEX_KEYS={'lockSha256','rawReportSha256','freshQaInstallationSha256','functionalLogSha256','sha256','gnuBuildId','pomSha256','content','installed_executable_sha256','full_tested_lock_sha256','archiveSha256','mavenAarSha256','sdkLibrarySha256','sourceCommit','app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle/verification-metadata.xml','gradle/dependency-locks/app.lockfile','tools/requirements.lock','installationReceiptSha256','installerSourceSha256','binarySha256','archiveSha512','qa-requirements.lock','security-requirements.lock','qa-tools.json','logSha256','090','096','artifactSha256','candidate_sha256','engine_sha256'}

def sha(p):return 'sha256:'+hashlib.sha256(p.read_bytes()).hexdigest()
def reason(f,root):
 path=f['path'];rule=f['rule'];line=(root/path).read_text().splitlines()[f['line']-1]
 if f['scanner']=='bandit':
  if path not in PURPOSES:raise ValueError('Unreviewed Python source: '+path)
  if rule=='B101':
   if not path.startswith('benchmark_'):raise ValueError('Unreviewed assertion: '+path)
   return 'Analytic benchmark regression assertion, not production input validation; execute normal Python without -O. '+PURPOSES.get(path,'Fixed licensed stereo/synthetic benchmark only.')
  if rule in ('B404','B603','B607'):return PURPOSES[path]+' Reviewed host QA invocation uses explicit argv with shell=False/default; trusted developer PATH/SDK is assumed and no Android runtime dependency is introduced.'
  if rule=='B405':return PURPOSES[path]+' Import presence is not entity/network resolution; reviewed uses are local test/SDK metadata, not external project XML.'
  if rule=='B314':return PURPOSES[path]+' ElementTree does not resolve external network/file entities; source is trusted local build/test metadata under the documented host-tool boundary.'
  if rule=='B310' and path in ('dependencies/assemble_notices.py','dependencies/install_qa.py'):return PURPOSES[path]+' The exact HTTPS-only origin manifest is additionally content-bound in context_sha256; changed origins reactivate this finding.'
  raise ValueError('Unreviewed Bandit rule: '+rule)
 if f['scanner']!='detect-secrets':raise ValueError('Vulnerabilities cannot be auto-triaged: '+f['scanner'])
 if path in JSON_FILES:
  json.loads((root/path).read_text())
  m=re.match(r'\s*"([^"\\]+)"\s*:\s*"([^"\\]*)"',line)
  if not m:raise ValueError('Unreviewed JSON finding shape: '+path)
  key,value=m.groups()
  if rule=='Hex High Entropy String':
   if key not in HEX_KEYS or len(value) not in (40,64,128) or not re.fullmatch('[0-9a-f]+',value):raise ValueError('Not a reviewed digest field: '+path+' '+key)
   if key=='content' and '"alg": "SHA-256"' not in '\n'.join((root/path).read_text().splitlines()[max(0,f['line']-4):f['line']]):raise ValueError('Unproven SBOM hash algorithm')
   return 'Public generated provenance field '+key+': hexadecimal SHA256/SHA512 artifact, POM, test/log/source digest or GNU ELF build ID. The reviewed producer emits content identities, never authentication material; exact evidence-file bytes/line are bound.'
  if rule=='Base64 High Entropy String':
   if key in ('integrity','npm_integrity_sha512'):
    if not value.startswith('sha512-') or len(base64.b64decode(value[7:],validate=True))!=64:raise ValueError('Invalid npm content integrity')
    return 'Public npm sha512 package-content integrity, not an API credential or signing key; exact QA package manifest/evidence bytes are bound.'
   if key=='archivePath' and value.startswith('OpenCV-android-sdk/') and ('license' in value.lower() or 'notice' in value.lower()):return 'Public fixed OpenCV SDK archive license-resource path. Filename characters trigger entropy detection; no credential/authentication value is present.'
   raise ValueError('Unreviewed Base64 field: '+path+' '+key)
 if rule=='Hex High Entropy String' and path=='qa/export_interoperability/provider_matrix.py':
  if not line.startswith('SOURCES = {'):raise ValueError('Unreviewed provider literal')
  return 'Public immutable061–064 provider Git commit identities in the pinned SOURCES map, not authentication tokens; Git objects are local published source snapshots.'
 if rule=='Hex High Entropy String' and path=='release/package_validation/bundle.py' and line.startswith('BUNDLETOOL_SHA256='):return 'Public pinned bundletool artifact SHA256, checked before executing the selected Java archive; not a credential.'
 if rule=='Base64 High Entropy String' and path=='qa/fuzz/run.py' and '-DCMAKE_BUILD_TYPE=RelWithDebInfo' in line:return 'Literal public CMake RelWithDebInfo build option in fixed compiler argv, not encoded authentication material.'
 if rule=='Secret Keyword' and path=='security/scan.py' and "report['checks']['secrets'] =" in line:return 'Public PASS/FAIL status enum assigned to the secret-scanner result; the literal is report metadata, never a secret credential value.'
 raise ValueError('Unreviewed secret-shaped finding: '+path+' '+rule)

entries={}
# Preserve only the still-present isolated legacy APK wrapper's four reviewed argv sites.
for entry in json.loads((OWN/'security/suppressions-v1.json').read_text())['entries']:
 if entry['path']=='check_apk.py' and entry['source_sha256']==sha(OWN/'check_apk.py'):
  entries[tuple(entry.get(k) for k in ('scanner','path','line','rule','source_sha256'))]=entry
for report_path,root in [(Path('/workspace/campaign/evidence/096/final-review-raw.json'),ROOT)]:
 report=json.loads(report_path.read_text())
 for f in report['raw_findings']:
  if f['rule']=='B101' and f['path'].startswith('benchmark_'):
   justification='Analytic benchmark regression assertion, not production input validation; normal Python without -O is required. Fixed licensed stereo/synthetic fixture gate only.'
  else:justification=reason(f,root)
  entry={**f,'source_sha256':sha(root/f['path']),'expires':EXPIRY,'justification':justification}
  if f['rule']=='B310':
   contexts=['dependencies/qa-tools.json'] if f['path'].endswith('install_qa.py') else ['dependencies/evidence/notice-origins.json','dependencies/evidence/opencv-sdk-provenance.json']
   for context in contexts:
    data=json.loads((root/context).read_text());text=json.dumps(data)
    if re.search(r'"(?:url|checksumSource)":\s*"(?!https://)',text):raise ValueError('Origin scheme needs source-owner hardening')
   entry['context_sha256']={path:sha(root/path) for path in contexts}
  key=tuple(entry.get(k) for k in ('scanner','path','line','rule','source_sha256'));entries[key]=entry
path=OWN/'security/suppressions-v1.json'
path.write_text(json.dumps({'schema':1,'reviewed_scope':'Named integrated desktop QA tools and generated public checksum/provenance metadata; no runtime/device assurance inferred. Every entry is exact and expires.','entries':sorted(entries.values(),key=lambda e:(e['scanner'],e['path'],e['line'],e['rule'],e['source_sha256']))},indent=2)+'\n')
print('Reviewed exact exceptions:',len(entries))
