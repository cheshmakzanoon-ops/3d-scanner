"""Package gates with real SDK attestations and independently testable policy."""
from __future__ import annotations
import hashlib, re, struct, subprocess, tempfile, zipfile
from pathlib import Path
from xml.etree import ElementTree as ET
A = '{http://schemas.android.com/apk/res/android}'
FORBIDDEN = {'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE'}
LIBRARIES = {'librealityforge.so', 'libopencv_java4.so', 'libc++_shared.so', 'libandroidx.graphics.path.so', 'libimage_processing_util_jni.so', 'libsurface_util_jni.so'}
def sha256(path):
    with open(path,'rb') as f:
        return hashlib.file_digest(f,'sha256').hexdigest()
def manifest_policy(xml, release=False):
    root=ET.fromstring(xml); app=root.find('application')
    if app is None: raise ValueError('Missing application')
    permissions={p.get(A+'name') for p in root.findall('uses-permission')}
    if permissions & FORBIDDEN: raise ValueError('Unexpected network permission')
    for key in ('allowBackup','fullBackupContent','usesCleartextTraffic'):
        if app.get(A+key)!='false': raise ValueError('Unsafe application '+key)
    if not app.get(A+'dataExtractionRules'): raise ValueError('Missing data extraction rules')
    if release and (app.get(A+'debuggable','false')!='false' or app.get(A+'testOnly','false')!='false'):
        raise ValueError('Release is debuggable or testOnly')
    if root.get('package')!='com.realityforge.scanner': raise ValueError('Unexpected application ID')
    return {'applicationId':root.get('package'),'versionCode':root.get(A+'versionCode'),
            'versionName':root.get(A+'versionName'),'permissions':sorted(permissions)}
def elf_alignment(data):
    if len(data)<64 or data[:4]!=b'\x7fELF' or data[4]!=2 or data[5]!=1: raise ValueError('Need little-endian ELF64')
    if len(data)<64: raise ValueError('Truncated ELF')
    phoff=struct.unpack_from('<Q',data,32)[0]; size,count=struct.unpack_from('<HH',data,54)
    if size<56 or count==0 or phoff+size*count>len(data): raise ValueError('Invalid ELF program headers')
    loads=0
    for n in range(count):
        off=phoff+n*size; typ=struct.unpack_from('<I',data,off)[0]
        if typ!=1: continue
        loads+=1
        offset,vaddr=struct.unpack_from('<QQ',data,off+8); alignment=struct.unpack_from('<Q',data,off+48)[0]
        if alignment<16384 or alignment & (alignment-1) or (offset-vaddr)%16384:
            raise ValueError('Native library lacks 16 KB segment alignment')
    if not loads: raise ValueError('ELF has no LOAD segments')
def backup_policy(xml):
 root=ET.fromstring(xml)
 if root.tag!='data-extraction-rules':raise ValueError('Invalid data extraction root')
 for section in ('cloud-backup','device-transfer'):
  rule=root.find(section)
  if rule is None or rule.findall('include'):raise ValueError('Unsafe backup rule '+section)
  excluded={(e.get('domain'),e.get('path')) for e in rule.findall('exclude')}
  required={(domain,'.') for domain in ('root','file','database','sharedpref','external')}
  if not required<=excluded:raise ValueError('Incomplete backup exclusions '+section)
 return True

def archive_policy(path, abis=('arm64-v8a','x86_64'), release=False, required_notices=False):
    checked=[]; expected=set(abis)
    with zipfile.ZipFile(path) as z:
        names=z.namelist()
        if len(names)!=len(set(names)): raise ValueError('Duplicate ZIP entry')
        if any(n.startswith('/') or '..' in n.split('/') for n in names): raise ValueError('Unsafe ZIP path')
        if release and any(n.startswith(('assets/fixtures/','assets/synthetic/','assets/relief/','assets/a097-')) or
                re.search(r'(^|/)(left|right|view[-_]?[0-9]+)\.(png|jpg)$',n) for n in names):
            raise ValueError('Synthetic/test assets shipped in release')
        native={n for n in names if re.fullmatch(r'lib/[^/]+/[^/]+\.so',n)}
        actual={n.split('/')[1] for n in native}
        if actual!=expected: raise ValueError(f'ABI mismatch: expected {sorted(expected)}, got {sorted(actual)}')
        for abi in sorted(expected):
            present={Path(n).name for n in native if n.split('/')[1]==abi}
            if present!=LIBRARIES: raise ValueError(f'Native inventory mismatch for {abi}: {sorted(present)}')
        for n in sorted(native):
            data=z.read(n);elf_alignment(data)
            expected_machine={'arm64-v8a':183,'x86_64':62}[n.split('/')[1]]
            if struct.unpack_from('<H',data,18)[0]!=expected_machine:raise ValueError('ELF machine differs from ABI directory')
            checked.append({'path':n,'sha256':hashlib.sha256(z.read(n)).hexdigest()})
        if required_notices and not any(n.startswith('assets/third-party-notices/') for n in names):
            raise ValueError('Required third-party notices absent')
    return checked
def signature_policy(output, mode, certificate=None):
    if not re.search(r'Verified using v[234][^:]*:\s*true',output): raise ValueError('No verified modern APK signature')
    names=re.findall(r'Signer #\d+ certificate DN:\s*(.+)',output)
    if not names:raise ValueError('Missing signer certificate identity')
    if mode=='test' and any('CN=Android Debug' not in name for name in names):raise ValueError('Test mode requires the Android debug signing identity')
    if mode=='production' and any('CN=Android Debug' in name for name in names):raise ValueError('Debug signer cannot be production')
    certs=re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})',output)
    if not certs: raise ValueError('Missing signer certificate digest')
    if mode=='production' and not certificate: raise ValueError('Production requires expected certificate SHA256')
    if certificate and set(c.lower() for c in certs)!={certificate.lower()}:
        raise ValueError('Unexpected signing identity')
    return sorted(set(c.lower() for c in certs))
def validate(apk,sdk,*,release=False,abis=('arm64-v8a','x86_64'),mode='test',certificate=None,required_notices=False):
    apk=Path(apk);sdk=Path(sdk);bt=sdk/'build-tools/36.0.0'
    def run(args): return subprocess.check_output([str(x) for x in args],text=True,stderr=subprocess.PIPE)
    manifest=run([sdk/'cmdline-tools/latest/bin/apkanalyzer','manifest','print',apk])
    result=manifest_policy(manifest,release)
    backup_policy(run([sdk/'cmdline-tools/latest/bin/apkanalyzer','resources','xml','--file','res/xml/data_extraction_rules.xml',apk]))
    libraries=archive_policy(apk,abis,release,required_notices)
    run([bt/'zipalign','-c','-P','16','-v','4',apk])
    signer=signature_policy(run([bt/'apksigner','verify','--verbose','--print-certs',apk]),mode,certificate)
    return dict(result, schemaVersion=1, artifact=apk.name,sha256=sha256(apk),size=apk.stat().st_size,
                signingMode=mode,signerCertificateSha256=signer,releaseFlagsChecked=release,
                zipAlignment16k=True,backupExclusionsChecked=True,elfLibrariesChecked=libraries)
