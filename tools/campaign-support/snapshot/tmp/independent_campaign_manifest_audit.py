from __future__ import annotations
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

REPO=Path('/workspace/3d-scanner')
OUTPUT=Path('/workspace/campaign/independent-manifest-audit.json')
BASE='ad3029193f304c70931b94ea7a0df90b69d84b07'
MANIFEST='SOURCE_MANIFEST.sha256'

def git(*args: str, check: bool=True):
    return subprocess.run(['git','-C',str(REPO),*args],check=check,stdout=subprocess.PIPE,stderr=subprocess.PIPE)

def tree(sha: str):
    entries={}
    for row in git('ls-tree','-r','-z','--full-tree',sha).stdout.split(b'\0'):
        if not row: continue
        meta,name=row.split(b'\t',1)
        mode,kind,oid=meta.decode('ascii').split()
        entries[name.decode('utf-8')]={'mode':mode,'type':kind,'oid':oid}
    return entries

started=datetime.datetime.now(datetime.timezone.utc).isoformat()
plan_ref='refs/heads/agents/031-lens-coordinate-geometry'
plan_sha=git('rev-parse',plan_ref).stdout.decode().strip()
plan=git('show',plan_sha+':PRODUCTION_100_AGENT_EXECUTION_PLAN.md').stdout.decode()
roster=[]
for number in range(31,61):
    section=plan.split(f'## Agent {number:03d}\n',1)[1].split('\n## Agent ',1)[0]
    branch=re.search(r'^\*\*Branch:\*\* `([^`]+)`',section,re.M).group(1)
    roster.append((f'{number:03d}',branch))
refs={}
for row in git('for-each-ref','--format=%(refname)\t%(objectname)','refs/heads/agents/').stdout.decode().splitlines():
    name,sha=row.split('\t',1); refs[name]=sha
base_tree=tree(BASE)
snapshots=[]
all_oids=set()
for agent,branch in roster:
    ref='refs/heads/'+branch
    sha=refs.get(ref)
    entry={'agent':agent,'branch':branch,'ref':ref,'sha':sha,'failures':[]}
    if sha is None:
        entry['failures'].append({'kind':'missing_delivery_ref'})
        snapshots.append((entry,{}));continue
    entries=tree(sha)
    snapshots.append((entry,entries))
    all_oids.update(e['oid'] for e in entries.values() if e['type']=='blob')

# Read object bytes directly from the object database, never checkout HEAD or working files.
request=''.join(oid+'\n' for oid in sorted(all_oids)).encode()
process=subprocess.Popen(['git','-C',str(REPO),'cat-file','--batch'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
output,error=process.communicate(request)
if process.returncode: raise RuntimeError('git cat-file failed: '+error.decode())
objects={}; offset=0
for wanted in sorted(all_oids):
    newline=output.index(b'\n',offset)
    oid,kind,size=output[offset:newline].decode().split()
    size=int(size); offset=newline+1
    assert oid==wanted and kind=='blob'
    blob=output[offset:offset+size]; assert len(blob)==size
    assert output[offset+size:offset+size+1]==b'\n'
    objects[oid]=blob; offset+=size+1
assert offset==len(output)
sha256={oid:hashlib.sha256(blob).hexdigest() for oid,blob in objects.items()}

credential_patterns=[
    ('private_key',re.compile(rb'-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----')),
    ('github_token',re.compile(rb'\bgh[pousr]_[A-Za-z0-9]{30,255}\b')),
    ('github_fine_grained_token',re.compile(rb'\bgithub_pat_[A-Za-z0-9_]{30,255}\b')),
    ('aws_access_key_id',re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b')),
    ('slack_token',re.compile(rb'\bxox[baprs]-[A-Za-z0-9-]{20,}\b')),
]
credential_names={'credentials','credentials.json','service-account.json','service_account.json','id_rsa','id_dsa','id_ecdsa','id_ed25519'}
compiled_extensions={'.o','.obj','.a','.so','.dll','.dylib','.class','.pyc','.apk','.aab','.jks','.keystore','.p12','.pfx'}
generated_parts={'build','.gradle','.cxx','__pycache__','node_modules','.idea'}

for row,entries in snapshots:
    sha=row['sha']
    if sha is None: continue
    unsupported=[name for name,e in entries.items() if e['mode'] not in ('100644','100755') or e['type']!='blob' or '\n' in name or '\r' in name]
    if unsupported: row['failures'].append({'kind':'unsupported_tracked_entries','paths':unsupported})
    expected_names=sorted(name for name in entries if name!=MANIFEST)
    row['tracked_files']=len(entries)
    row['manifest_expected_entries']=len(expected_names)
    expected=''.join(f"{sha256[entries[name]['oid']]}  {name}\n" for name in expected_names if entries[name]['type']=='blob').encode()
    row['expected_manifest_sha256']=hashlib.sha256(expected).hexdigest()
    actual=objects.get(entries.get(MANIFEST,{}).get('oid'))
    if actual is None:
        row['manifest_match']=False
        row['failures'].append({'kind':'missing_source_manifest'})
    else:
        row['actual_manifest_sha256']=hashlib.sha256(actual).hexdigest()
        row['manifest_match']=actual==expected
        row['manifest_blob']=entries[MANIFEST]['oid']
        if actual!=expected:
            parsed={}; malformed=[];duplicates=[]
            for line_no,line in enumerate(actual.decode('utf-8',errors='replace').splitlines(),1):
                match=re.fullmatch(r'([a-f0-9]{64})  (.+)',line)
                if match is None: malformed.append(line_no);continue
                digest,path=match.groups()
                if path in parsed: duplicates.append(path)
                parsed[path]=digest
            row['failures'].append({'kind':'manifest_mismatch',
                'missing_paths':sorted(set(expected_names)-set(parsed)),
                'extra_paths':sorted(set(parsed)-set(expected_names)),
                'checksum_mismatches':sorted(name for name in expected_names if name in parsed and parsed[name]!=sha256[entries[name]['oid']]),
                'duplicate_paths':duplicates,'malformed_line_numbers':malformed,
                'byte_format_or_order_mismatch':set(parsed)==set(expected_names) and all(parsed.get(name)==sha256[entries[name]['oid']] for name in expected_names)})
    diff=git('diff','--check',BASE,sha,check=False)
    row['diff_check']={'base':BASE,'sha':sha,'returncode':diff.returncode,'passed':diff.returncode==0}
    if diff.returncode:
        # No source-line values (which might contain a credential) are copied into the report.
        diagnostics=[]
        for line in diff.stdout.decode(errors='replace').splitlines():
            if re.search(r':\d+: (?:trailing whitespace|new blank line at EOF|space before tab)',line): diagnostics.append(line)
        row['failures'].append({'kind':'diff_check','diagnostics':diagnostics,'output_sha256':hashlib.sha256(diff.stdout+diff.stderr).hexdigest()})
    changed=[name for name,e in entries.items() if name!=MANIFEST and (name not in base_tree or base_tree[name]['oid']!=e['oid'])]
    row['changed_tracked_files_since_base']=len(changed)
    risks=[]
    for name in changed:
        e=entries[name]
        if e['type']!='blob': continue
        path=Path(name); lower_parts={part.lower() for part in path.parts}; lower_name=path.name.lower()
        if lower_parts & generated_parts or path.suffix.lower() in compiled_extensions or lower_name=='local.properties':
            risks.append({'kind':'generated_or_compiled_path','path':name})
        if lower_name in credential_names or (lower_name.startswith('.env') and not any(word in lower_name for word in ('example','template','sample'))) or lower_name in ('google-services.json',):
            risks.append({'kind':'credential_like_path','path':name})
        blob=objects[e['oid']]
        if len(blob)>10*1024*1024: risks.append({'kind':'large_changed_blob','path':name,'bytes':len(blob)})
        if b'\0' not in blob:
            for label,pattern in credential_patterns:
                for match in pattern.finditer(blob):
                    if b'EXAMPLE' in match.group(): continue
                    risks.append({'kind':'credential_marker','marker':label,'path':name,'line':blob.count(b'\n',0,match.start())+1})
    row['credential_build_scan']={'scope':'introduced_or_modified_tracked_blobs_since_campaign_base','findings':risks}
    if risks: row['failures'].append({'kind':'credential_or_build_findings','findings':risks})
    row['passed']=not row['failures']

ending_refs={}
for entry in git('for-each-ref','--format=%(refname)\t%(objectname)','refs/heads/agents/').stdout.decode().splitlines():
    name,sha=entry.split('\t',1); ending_refs[name]=sha
rows=[]
for row,_ in snapshots:
    row['ref_sha_after_audit']=ending_refs.get(row['ref'])
    row['ref_advanced_during_audit']=row['ref_sha_after_audit']!=row['sha']
    rows.append(row)
report={
    'schema':1,'started_utc':started,'finished_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'repository':str(REPO),'campaign_base':BASE,'plan_ref':plan_ref,'plan_sha':plan_sha,
    'protocol':'SHA-256 of every tracked regular blob sorted by path, excluding only SOURCE_MANIFEST.sha256; exact manifest bytes required',
    'source':'Immutable local refs/heads roster commits; no checkout HEAD, worktree content, remote mutations or resets used',
    'credential_scan_limit':'Explicit private-key/token markers and credential-like tracked paths; this bounded scan does not establish absence of every possible secret format',
    'summary':{'branches_checked':len(rows),'passed':sum(bool(r.get('passed')) for r in rows),'failed':sum(not r.get('passed',False) for r in rows),
               'refs_advanced_during_audit':[r['branch'] for r in rows if r['ref_advanced_during_audit']]},
    'branches':rows,
}
OUTPUT.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'artifact':str(OUTPUT),'summary':report['summary'],'failures':[{'agent':r['agent'],'sha':r['sha'],'failures':r['failures']} for r in rows if r['failures']]},indent=2))
