"""Audit immutable094 refs without changing a checkout or a remote."""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,datetime

root=Path('/workspace/agent-workspaces/100')
repo=Path('/workspace/agent-workspaces/094')
out=Path('/workspace/campaign/evidence/094')
base='ad3029193f304c70931b94ea7a0df90b69d84b07'
approved='4f9bc76c42c96f571ba885975b23bd204f148ef3'
remote='bf3c6ea2b0d8043a8ddb882d487473733de7228a'
ci='01215c7bc58701f33710c2b2c7fd8fbb2a48355b'
parser_source='86df647bcdba4b110ad36dfb6ce572adb4261462'
parser_blob=subprocess.check_output(['git','show',parser_source+':tools/agents/campaign.py'],cwd=root)
parser_snapshot=out/'audit-campaign-86df647.py';parser_snapshot.write_bytes(parser_blob)
spec=importlib.util.spec_from_file_location('frozen_campaign_audit',parser_snapshot);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
rows=m.roster((root/'PRODUCTION_100_AGENT_EXECUTION_PLAN.md').read_text())
def git(*argv):return subprocess.check_output(['git',*argv],cwd=repo,text=True).strip()
def paths(*argv):return [p.decode() for p in subprocess.check_output(['git',*argv],cwd=repo).split(b'\0') if p]
entries=[]
for agent,sha,label in [('093',ci,'approved093'),('094',approved,'approved094'),('094',remote,'live_remote094')]:
 errors=m.ownership(root,rows,agent,base,sha)
 raw=git('show',sha+f':docs/agents/status/{agent}.md')
 entries.append({'agent':agent,'source_sha':sha,'label':label,'ownership_status':'FAIL' if errors else 'PASS','unowned_paths':errors,'receipt_state':m.receipt_state(raw),'receipt_errors_with_frozen86_parser':m.receipt_checks(raw),'acceptance_rows_with_frozen86_parser':len(m.acceptance_rows(m.receipt_record(raw)))})
changed=paths('diff','--no-renames','--name-only','-z',approved,remote)
source_row=next(row for row in rows if row['agent']=='094')
own_changed=[name for name in changed if m.owned(source_row,name)]
unchanged_core=['tools/source_manifest.py','tools/ci/source_snapshot/archive.py','tests/test_source_manifest_tool.py','.github/workflows/export-source-snapshot.yml']
core={name:{'approved_blob':git('rev-parse',approved+':'+name),'remote_blob':git('rev-parse',remote+':'+name)} for name in unchanged_core}
verify=subprocess.run(['python3',str(repo/'tools/source_manifest.py'),'verify','--source',remote,'--repo',str(repo)],capture_output=True,text=True)
live=git('ls-remote','origin','refs/heads/agents/093-parallel-branch-ci-validation','refs/heads/agents/094-source-provenance-reproducibility')
parents={sha:git('show','-s','--format=%P',sha).split() for sha in [remote,'eda74a783d864397b841ea06188fb347eef1ce49','5241f687636cce719c051c7bae4b6ccc55e666ef','f84e8278a0b6fa5cf162902244072eb627bd044e','7e338dfcb2031b9d8c26cd3b4e97865b033aff92']}
report={'schema':1,'observed_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'audit_kind':'read-only immutable Git refs; no checkout changes, fetch, merge, pull or remote mutation','campaign_base':base,'campaign_validator_source':parser_source,'campaign_validator_sha256':hashlib.sha256(parser_blob).hexdigest(),'refs':entries,'live_ls_remote':live,'approved_is_remote_ancestor':git('merge-base',approved,remote)==approved,'remote_merge_parents':parents,'changed_paths_approved_to_remote':changed,'changed_paths_count':len(changed),'owned_changes_approved_to_remote':own_changed,'unchanged_provenance_core_blobs':core,'remote_manifest_verification':{'exit_code':verify.returncode,'stdout':verify.stdout.strip(),'stderr':verify.stderr.strip()},'safe_disposition':'Preserve live remote bf3c6ea unchanged; do not fast-forward local094, merge foreign remote into100, force-push or claim whole-branch ownership. Approved own-scope delivery remains immutable4f9bc76; root100 retains/consumes only that already-reviewed slice and fixes YAML parser compatibility separately.'}
p=out/'live-remote-094-ownership-audit.json';p.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
print(json.dumps({'record':str(p),'record_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'refs':[{'label':e['label'],'ownership':e['ownership_status'],'unowned_count':len(e['unowned_paths']),'receipt_errors':e['receipt_errors_with_frozen86_parser']} for e in entries],'remote_delta_paths':len(changed),'owned_changed_paths':own_changed,'manifest':report['remote_manifest_verification'],'live_remote':live}))
