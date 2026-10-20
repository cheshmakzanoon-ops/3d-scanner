"""Independent read-only096 review. All mutations stay in disposable fixtures."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile

parser=argparse.ArgumentParser()
parser.add_argument('--revision',required=True)
parser.add_argument('--real-scanner',type=Path)
args=parser.parse_args()
repo=Path('/workspace/agent-workspaces/096')
out=Path('/workspace/campaign/evidence/093/096-review')
revision=subprocess.check_output(['git','rev-parse',args.revision+'^{commit}'],cwd=repo,text=True).strip()
blob=subprocess.check_output(['git','show',revision+':tools/security/scan.py'],cwd=repo)
snapshot=out/(revision+'-scan.py');snapshot.write_bytes(blob)
spec=importlib.util.spec_from_file_location('published_scan_'+revision,snapshot)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
checks=[]
def record(name,ok,detail=None):
 checks.append({'case':name,'result':'PASS' if ok else 'FAIL','detail':detail})
def reject(name,root):
 try:
  m.requirements_closure(root/'requirements.txt')
 except (ValueError,OSError,RuntimeError):record(name,True)
 else:record(name,False,'requirements closure accepted invalid input')

with tempfile.TemporaryDirectory(prefix='published096-',dir=out) as tmp:
 base=Path(tmp)
 case=base/'positive';case.mkdir();(case/'nested').mkdir()
 (case/'requirements.txt').write_text('-r nested/child.txt\nOther_Name==2.0 \\\n --hash=sha256:'+('a'*64)+'\n')
 (case/'nested/child.txt').write_text('Package.One==1.2.3\n')
 flattened,hashes,packages=m.requirements_closure(case/'requirements.txt')
 record('nested includes flatten and bind every input',packages=={'package-one':'1.2.3','other-name':'2.0'} and len(hashes)==2 and '-r' not in flattened)
 invalid={
  'self cycle':'-r requirements.txt\n',
  'parent escape':'-r ../outside.txt\n',
  'absolute include':'-r /tmp/outside.txt\n',
  'network include':'-r https://example.invalid/pins.txt\n',
  'index option':'--index-url https://example.invalid\n',
  'find-links option':'--find-links ./wheels\n',
  'constraint option':'-c child.txt\n',
  'editable option':'-e .\n',
  'unpinned range':'package>=1.2\n',
  'direct URL':'package @ https://example.invalid/package.whl\n',
  'environment marker':'package==1.2; python_version>="3.12"\n',
  'unsupported hash option':'package==1.2 --hash=sha512:'+('b'*128)+'\n',
  'dangling continuation':'package==1.2 \\',
  'normalized duplicate pins':'Package.One==1.2.3\npackage_one==1.2.3\n',
 }
 for name,text in invalid.items():
  case=base/name.replace(' ','-');case.mkdir();(case/'requirements.txt').write_text(text);reject(name,case)
 case=base/'two-file-cycle';case.mkdir();(case/'requirements.txt').write_text('-r child.txt\n');(case/'child.txt').write_text('-r requirements.txt\n');reject('two-file cycle',case)
 case=base/'repeated-include';case.mkdir();(case/'requirements.txt').write_text('-r child.txt\n-r child.txt\n');(case/'child.txt').write_text('package==1.2\n');reject('repeated include',case)
 for name,inside,directory in [('leaf-symlink',True,False),('directory-symlink-inside',True,True),('directory-symlink-outside',False,True)]:
  case=base/name;case.mkdir();target=(case/'actual') if inside else (base/(name+'-target'));target.mkdir();(target/'pins.txt').write_text('package==1.2\n')
  alias=case/'alias';alias.symlink_to(target if directory else target/'pins.txt',target_is_directory=directory)
  (case/'requirements.txt').write_text('-r '+('alias/pins.txt' if directory else 'alias')+'\n');reject(name,case)
 case=base/'oversized';case.mkdir();(case/'requirements.txt').write_bytes(b'#'+b'x'*(4*1024*1024));reject('closure byte limit',case)
 case=base/'too-many-files';case.mkdir()
 for i in range(65):
  name='requirements.txt' if i==0 else f'f{i}.txt'
  (case/name).write_text(f'-r f{i+1}.txt\n' if i<64 else 'package==1.2\n')
 reject('closure file-count limit',case)

 case=base/'bounded-scan';case.mkdir();source=case/'source';source.mkdir();requirements=case/'requirements.txt';requirements.write_bytes(b'#'+b'x'*(4*1024*1024))
 original_read_bytes=Path.read_bytes
 unbounded=[]
 def monitored_read_bytes(path):
  if path==requirements:
   unbounded.append(str(path))
   raise RuntimeError('unbounded requirements read attempted')
  return original_read_bytes(path)
 Path.read_bytes=monitored_read_bytes
 try:report=m.scan(source,case/'missing-python',requirements)
 finally:Path.read_bytes=original_read_bytes
 record('scan enforces size before unbounded requirements read',report['status']=='NOT_RUN' and not unbounded,{'status':report['status'],'unbounded_read_attempts':len(unbounded)})
 case=base/'bounded-inside-root';case.mkdir();requirements=case/'requirements.txt';requirements.write_bytes(b'#'+b'x'*(4*1024*1024))
 unbounded=[]
 Path.read_bytes=monitored_read_bytes
 try:report=m.scan(case,case/'missing-python',requirements)
 finally:Path.read_bytes=original_read_bytes
 record('in-scope requirements enforce bounded pre-read',report['status']=='NOT_RUN' and not unbounded,{'status':report['status'],'unbounded_read_attempts':len(unbounded)})
 case=base/'independent-source-identity';case.mkdir();(case/'safe.py').write_bytes(b'answer = 3\n')
 expected_identity=hashlib.sha256(json.dumps([['safe.py',hashlib.sha256(b'answer = 3\n').hexdigest()]],separators=(',',':')).encode()).hexdigest()
 identity=m.source_identity(case)
 record('source identity independently matches exact bytes',identity['source_files_count']==1 and identity['source_files_sha256']==expected_identity)
 case=base/'empty-selected-pins';case.mkdir();source=case/'source';source.mkdir();requirements=case/'requirements.txt';requirements.write_text('# no selected pins\n')
 report=m.scan(source,case/'missing-python',requirements)
 record('empty selected pin set rejected',report['status']=='NOT_RUN' and report['checks']=={}, {'status':report['status'],'checks':report['checks']})
 real_run=m.run_json
 coverage={
  'exact normalized returned pins':[{'name':'package_one','version':'1.2.3','vulns':[]},{'name':'Other-Name','version':'2.0','vulns':[]}],
  'empty returned coverage':[],
  'partial returned coverage':[{'name':'package-one','version':'1.2.3','vulns':[]}],
  'wrong returned version':[{'name':'package-one','version':'1.2.4','vulns':[]},{'name':'other-name','version':'2.0','vulns':[]}],
  'extra returned dependency':[{'name':'package-one','version':'1.2.3','vulns':[]},{'name':'other-name','version':'2.0','vulns':[]},{'name':'extra','version':'1.0','vulns':[]}],
  'duplicate normalized returned dependency':[{'name':'package-one','version':'1.2.3','vulns':[]},{'name':'package_one','version':'1.2.3','vulns':[]},{'name':'other-name','version':'2.0','vulns':[]}],
  'skipped returned dependency':[{'name':'package-one','version':'1.2.3','vulns':[],'skip_reason':'fixture skipped'},{'name':'other-name','version':'2.0','vulns':[]}],
 }
 for name,rows in coverage.items():
  case=base/name.replace(' ','-');case.mkdir();source=case/'source';source.mkdir();(source/'safe.py').write_text('answer = 3\n');requirements=case/'requirements.txt';requirements.write_text('Package.One==1.2.3\nOther_Name==2.0\n')
  def runner(argv,**kwargs):
   if '-c' in argv:return m.PINS
   if 'bandit' in argv:return {'results':[]}
   if 'detect_secrets' in argv:return {'results':{}}
   return {'dependencies':rows}
  m.run_json=runner
  report=m.scan(source,Path(sys.executable),requirements)
  expected='PASS' if name=='exact normalized returned pins' else 'NOT_RUN'
  record(name,report['status']==expected and report['checks'].get('dependencies')==('PASS' if expected=='PASS' else 'NOT_RUN'),{'status':report['status'],'dependencies':report['checks'].get('dependencies')})

 for kind in ['scanned source','included requirements','root requirements','scanner driver']:
  case=base/('drift-'+kind.replace(' ','-'));case.mkdir();source=case/'source';source.mkdir();code=source/'safe.py';code.write_text('answer = 3\n');requirements=case/'requirements.txt';requirements.write_text('-r child.txt\n');child=case/'child.txt';child.write_text('package==1.2\n')
  def runner(argv,**kwargs):
   if '-c' in argv:return m.PINS
   if 'bandit' in argv:return {'results':[]}
   if 'detect_secrets' in argv:return {'results':{}}
   if kind=='scanner driver':snapshot.write_bytes(blob+b'\n# deliberate fixture driver drift\n')
   else:{'scanned source':code,'included requirements':child,'root requirements':requirements}[kind].write_text('package==9.9\n' if kind!='scanned source' else 'answer = 4\n')
   return {'dependencies':[{'name':'package','version':'1.2','vulns':[]}]}
  m.run_json=runner
  try:report=m.scan(source,Path(sys.executable),requirements)
  finally:snapshot.write_bytes(blob)
  record(kind+' drift during audit',report['status']=='NOT_RUN',{'status':report['status']})
 m.run_json=real_run
 for policy_case in ['ordinary reviewed policy','retargeted policy directory alias','transient actually parsed policy']:
  case=base/policy_case.replace(' ','-');case.mkdir();source=case/'source';source.mkdir();code=source/'code.py';code.write_text('eval(input())\n')
  policies=case/'policies';policies.mkdir();first=policies/'first';first.mkdir();second=policies/'second';second.mkdir()
  entry={'scanner':'bandit','path':'code.py','line':1,'rule':'B307','source_sha256':'sha256:'+hashlib.sha256(code.read_bytes()).hexdigest(),'expires':'2099-01-01','justification':'Synthetic independent fixture only: explicit alternate exception tests identity of the exact applied policy bytes.'}
  empty=json.dumps({'schema':1,'entries':[]});reviewed=json.dumps({'schema':1,'entries':[entry]})
  (first/'policy.json').write_text(reviewed if policy_case=='ordinary reviewed policy' else empty);(second/'policy.json').write_text(reviewed)
  if policy_case=='retargeted policy directory alias':
   alias=policies/'selected';alias.symlink_to(first,target_is_directory=True);policy=alias/'policy.json'
  else:policy=first/'policy.json'
  requirements=case/'requirements.txt';requirements.write_text('package==1.2\n')
  def runner(argv,**kwargs):
   if '-c' in argv:return m.PINS
   if 'bandit' in argv:return {'results':[{'filename':str(code),'line_number':1,'test_id':'B307','issue_severity':'HIGH'}]}
   if 'detect_secrets' in argv:return {'results':{}}
   if policy_case=='retargeted policy directory alias':alias.unlink();alias.symlink_to(second,target_is_directory=True)
   return {'dependencies':[{'name':'package','version':'1.2','vulns':[]}]}
  m.run_json=runner
  apply_target='apply_policy' if hasattr(m,'apply_policy') else 'apply_suppressions'
  real_apply=getattr(m,apply_target)
  mutations=[]
  if policy_case=='transient actually parsed policy':
   def transient_apply(*argv,**kwargs):
    mutations.append('changed policy around real policy application')
    policy.write_text(reviewed)
    try:return real_apply(*argv,**kwargs)
    finally:policy.write_text(empty)
   setattr(m,apply_target,transient_apply)
  try:report=m.scan(source,Path(sys.executable),requirements,policy)
  finally:setattr(m,apply_target,real_apply)
  if policy_case=='ordinary reviewed policy':expected=report['status']=='PASS' and len(report.get('suppressed',[]))==1
  elif policy_case=='retargeted policy directory alias':expected=report['status']=='NOT_RUN'
  else:expected=bool(mutations) and report['status'] in ('FAIL','NOT_RUN') and not report.get('suppressed') and report.get('suppressions_sha256')==hashlib.sha256(empty.encode()).hexdigest()
  record(policy_case,expected,{'status':report['status'],'raw':len(report.get('raw_findings',[])),'suppressed':len(report.get('suppressed',[])),'policy_mutations':len(mutations)})
 m.run_json=real_run
 if args.real_scanner:
  case=base/'actual-vulnerable-nested';case.mkdir();source=case/'source';source.mkdir();(source/'safe.py').write_text('answer = 3\n');requirements=case/'requirements.txt';requirements.write_text('-r child.txt\n');(case/'child.txt').write_text('requests==2.19.1\n')
  report=m.scan(source,args.real_scanner,requirements)
  (out/(revision+'-actual-vulnerable-nested.json')).write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
  record('actual pinned scanner finds vulnerable nested requests',report['status']=='FAIL' and report['checks'].get('dependencies')=='FAIL' and report.get('requirements_packages_count')==1 and any(row.get('package')=='requests' for row in report['findings']),{'status':report['status'],'checks':report['checks'],'package_count':report.get('requirements_packages_count')})

report={'schema':1,'source_sha':revision,'scanner_sha256':hashlib.sha256(blob).hexdigest(),'real_scanner_python':str(args.real_scanner) if args.real_scanner else None,'required_scanner_pins':m.PINS,'review':'independent read-only fixtures outside checkouts; synthetic scanner responses labeled separately from actual pinned scanner case','checks':checks,'status':'PASS' if all(row['result']=='PASS' for row in checks) else 'FAIL'}
path=out/(revision+'-review.json');path.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
print(json.dumps({'source_sha':revision,'checks':len(checks),'status':report['status'],'failed':[row for row in checks if row['result']!='PASS'],'record':str(path)}))
raise SystemExit(0 if report['status']=='PASS' else 1)
