from pathlib import Path
import subprocess,json,yaml,re
BASE=Path('/workspace/campaign-061-080');proof=json.loads((BASE/'c16-matched-code-proof.json').read_text())
def original_data(raw):
 if raw.startswith('---'):return yaml.load(raw.split('---',2)[1],Loader=yaml.BaseLoader)
 try:
  d=yaml.load(raw,Loader=yaml.BaseLoader)
  if isinstance(d,dict):return d
 except yaml.YAMLError:pass
 for boundary in reversed([m.start() for m in re.finditer(r'\n\s*\n',raw)]):
  try:
   d=yaml.load(raw[:boundary],Loader=yaml.BaseLoader)
   if isinstance(d,dict) and 'agent' in d:return d
  except yaml.YAMLError:pass
 raise ValueError('Cannot read original identity metadata')
for role,p in proof.items():
 root=Path('/workspace/agent-work')/role;path=root/f'docs/agents/status/{role}.md'
 raw=subprocess.check_output(['git','-C',str(root),'show',p['role_head_before_receipt']+':docs/agents/status/'+role+'.md'],text=True)
 original=original_data(raw);current=yaml.safe_load(path.read_text())
 current['agent']=role
 own=sorted(str(x.relative_to(root)) for x in (root/'docs/agents/requests').glob(role+'-to-*.md'))
 external=[x for x in current['integration_requests'] if isinstance(x,str) and x.startswith('/workspace/')]
 current['integration_requests']=own+external
 current['interfaces_published']=original.get('interfaces_published',[])
 if 'dependencies' in original:current['dependencies']=original['dependencies']
 assert all(isinstance(x,str) for x in current['interfaces_published'])
 assert all(isinstance(x,str) and x.endswith('.md') for x in current['integration_requests'])
 path.write_text(yaml.safe_dump(current,sort_keys=False,width=112,allow_unicode=True))
 normalized=yaml.safe_load(path.read_text())
 assert normalized['agent']==role
 assert all(isinstance(x,str) for x in normalized['integration_requests']+normalized['interfaces_published'])
 print(role,len(own),'canonical request paths; original IDs',original.get('integration_requests'))
