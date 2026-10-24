"""Bounded native input roles: immutable bytes, discovered linker inputs, actual build outputs.
Generated output hashes are POSTBUILD observations, never preconsumption attestations.
"""
from pathlib import Path
import hashlib,json,os,re,shlex,shutil,stat
from .runtime import Failure,sha,atomic_json
from tools.native_process import native_compile_env
VERSIONS=('1.13.2',)
MAX_LOG=8*1024**2;MAX_PROOF=16*1024**2;MAX_NODES=24000;MAX_EDGES=100000
FILENAME='native-input-provenance.json'
PROBE=b'extern "C" int rf_link_input_probe(void) { return 1; }\n'
LIBRARIES=('-ldl','-lpthread','-lrt','-lz','-lm')
GEN_TARGETS=('gen_opencv_java_source','modules/core/opencl_kernels_core.cpp','modules/imgproc/opencl_kernels_imgproc.cpp','modules/features2d/opencl_kernels_features2d.cpp')
DOMAINS={'opencv':('build','opencv_java'),'scanner':('scanner','realityforge')}

def fail(message):raise Failure('Native provenance: '+message)
def read(path,limit=MAX_LOG):
 p=Path(path)
 if p.is_symlink() or not p.is_file() or p.stat().st_size>limit:fail('missing/aliased/oversized evidence file')
 data=p.read_bytes()
 if len(data)>limit:fail('evidence grew beyond limit')
 return data

def identity(path):
 p=Path(path)
 if p.resolve(strict=True)!=p or not p.is_file():fail('noncanonical/nonregular input')
 s=p.stat();h=sha(p);end=p.stat()
 if (s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns)!=(end.st_dev,end.st_ino,end.st_size,end.st_mtime_ns,end.st_ctime_ns):fail('input drift while hashing')
 return {'path':str(p),'sha256':h,'size':s.st_size,'mtimeNs':s.st_mtime_ns,'device':str(s.st_dev),'inode':str(s.st_ino)}

def checked_name(name):
 if not isinstance(name,str) or not name or len(name)>4096 or name.startswith('-') or any(ord(c)<32 or ord(c)>126 for c in name) or any(c in name for c in ('\n','\r','\x00',':')):fail('unsupported Ninja/dependency pathname')
 return name

def canonical(name,base):
 p=Path(checked_name(name));return str((p if p.is_absolute() else Path(base)/p).resolve())

def command(run,label,argv,seconds=30):
 argv=list(map(str,argv));env=native_compile_env(run.env);env['PYTHONDONTWRITEBYTECODE']='1'
 argmax=os.sysconf('SC_ARG_MAX');required=sum(len(x.encode())+1 for x in argv)+sum(len(k.encode())+len(v.encode())+2 for k,v in env.items())+8*(len(argv)+len(env)+2)
 if required>argmax-65536 or any(len(x.encode())>131071 for x in argv):fail('command argument/environment byte bound')
 return run.command(label,argv,seconds=seconds,limit=MAX_LOG,env=env)[0]

def record(run,label,argv):
 found=[r for r in run.commands if r.get('label')==label]
 if len(found)!=1:fail('missing/duplicate authoritative command')
 r=found[0]
 if r.get('argv')!=list(map(str,argv)) or r.get('runOwner')!=run.owner or type(r.get('starttime')) is not int or r['starttime']<=0 or r.get('complete') is not True or r.get('exit')!=0 or r.get('environmentBoundary')!='EXPLICIT_COMPILATION_TOOL' or any(k in r for k in ('error','cleanupError','secondaryErrors')):fail('foreign/incomplete command')
 raw=read(run.work/'logs'/(label+'.raw.log'))
 if len(raw)!=r.get('bytes') or hashlib.sha256(raw).hexdigest()!=r.get('sha256'):fail('command transcript changed')
 return raw

def manifests(build,run=None):
 result={}
 # CMake's Ninja files are local configuration products. No filesystem-root scan.
 pending=[Path(build)];found=[];entries=0
 while pending:
  directory=pending.pop()
  if run:run.remaining(1)
  with os.scandir(directory) as stream:
   for item in stream:
    entries+=1
    if entries>60000:fail('manifest discovery metadata bound')
    if run:run.remaining(1)
    path=Path(item.path)
    if item.is_dir(follow_symlinks=False):pending.append(path)
    elif path.suffix=='.ninja' or path==Path(build)/'CMakeCache.txt':
     if len(found)>=128:fail('manifest count limit')
     found.append(path)
 for p in sorted(found):
  if len(result)>=128:fail('manifest count limit')
  if p.is_symlink() or not p.is_file() or p.resolve()!=p:fail('manifest alias')
  raw=read(p);result[str(p)]=hashlib.sha256(raw).hexdigest()
  if p.suffix=='.ninja':
   for line in raw.decode().splitlines():
    if line.startswith(('include ','subninja ')):
     name=line.split(' ',1)[1]
     if '$' in name or canonical(name,build)!=str((Path(build)/name).absolute()) or not Path(canonical(name,build)).is_relative_to(build):fail('unbound Ninja manifest include')
     included=Path(canonical(name,build))
     if included.suffix!='.ninja' or not included.is_file():fail('unsupported Ninja manifest include')
 if str(Path(build)/'build.ninja') not in result:fail('primary Ninja manifest absent')
 return result

def dependency_rules(manifest_map):
 rules={}
 for filename in manifest_map:
  if not filename.endswith('.ninja'):continue
  current=None
  for line in read(filename).decode().splitlines():
   match=re.fullmatch(r'rule ([A-Za-z0-9_.+-]+)',line)
   if match:
    current=match[1]
    if current in rules:fail('duplicate Ninja rule')
    rules[current]=None
   elif line and not line.startswith((' ','#')):current=None
   elif current and re.match(r'  deps\s*=',line):
    match=re.fullmatch(r'  deps = (gcc|msvc)',line)
    if not match or rules[current] is not None:fail('unsupported/duplicate declared dependency rule')
    rules[current]=match[1]
 return rules

def target_names(raw):
 names=[]
 for line in raw.decode().splitlines():
  fields=line.rsplit(': ',1)
  if len(fields)!=2 or not re.fullmatch(r'[A-Za-z0-9_.+-]+',fields[1]):fail('unsupported Ninja targets format')
  names.append(checked_name(fields[0]))
 if not names or len(names)>MAX_NODES or len(set(names))!=len(names) or sum(len(x)+1 for x in names)>1024**2:fail('Ninja targets bound/duplicates')
 return sorted(names)

def graph_info(raw):
 if len(raw)>MAX_LOG:fail('graph bytes')
 lines=raw.decode('utf-8',errors='strict').splitlines()
 if lines[:4]!=['digraph ninja {','rankdir="LR"','node [fontsize=10, shape=box, height=0.25]','edge [fontsize=10]'] or lines[-1:]!=['}']:fail('unsupported graph format')
 nodes={};edges=[]
 for line in lines[4:-1]:
  node=re.fullmatch(r'"(0x[0-9a-f]+)" \[label=("(?:[^"\\]|\\.)*")(, shape=ellipse)?\]',line)
  edge=re.fullmatch(r'"(0x[0-9a-f]+)" -> "(0x[0-9a-f]+)"(?: \[(label="(?:[^"\\]|\\.)*"|arrowhead=none(?: style=dotted)?|style=dotted)\])?',line)
  if node:
   if node[1] in nodes:fail('duplicate graph node')
   nodes[node[1]]=(checked_name(json.loads(node[2])),bool(node[3]))
  elif edge:edges.append((edge[1],edge[2],edge[3]))
  else:fail('unsupported graph line')
  if len(nodes)>MAX_NODES or len(edges)>MAX_EDGES:fail('graph count limit')
 names=[name for name,isrule in nodes.values() if not isrule];producers={}
 if not names or len(names)!=len(set(names)):fail('empty/ambiguous graph names')
 for start,end,attributes in edges:
  if end not in nodes or nodes[end][1]:continue
  producer=None
  if start in nodes and nodes[start][1]:producer='edge:'+start
  elif attributes and attributes.startswith('label='):producer='direct:'+end
  if producer:
   name=nodes[end][0]
   if name in producers:fail('multiple declared producers')
   producers[name]=producer
 return sorted(names),producers

def graph_names(raw):return graph_info(raw)[0]

def aliases_for(nodes,paths,producers):
 aliases={}
 for name,path in paths.items():aliases.setdefault(path,[]).append(name)
 for path,names in aliases.items():
  if len(names)>1:
   if len({producers.get(name) for name in names})!=1 or producers.get(names[0]) is None:fail('ambiguous canonical producer aliases')
   # Query input strings for outputs of the same actual Ninja edge must be identical.
   if len({json.dumps(nodes[n],sort_keys=True) for n in names})!=1:fail('producer alias dependency mismatch')
 return {p:sorted(names) for p,names in aliases.items()}

def reachable(nodes,paths,producers,target):
 visiting=set();visited=set();paths_seen=set();edges=set()
 def walk(name):
  if name in visited:return
  if name in visiting or len(visiting)>=256:fail('configured graph cycle/depth')
  if name not in nodes:fail('incomplete configured graph query')
  visiting.add(name);node=nodes[name];paths_seen.add(paths[name])
  if node['rule'] and node['rule']!='phony':
   producer=producers.get(name)
   if not producer:fail('declared output lacks graph producer')
   edges.add(producer)
  for item in node['inputs']:walk(item['name'])
  visiting.remove(name);visited.add(name)
 walk(target)
 # All sibling outputs of a reachable producing edge are explicit, not arbitrary root members.
 for name,producer in producers.items():
  if producer in edges:paths_seen.add(paths[name])
 return sorted(paths_seen)

def parse_query(raw,expected):
 result={};current=None;section=None
 for line in raw.decode('utf-8',errors='strict').splitlines():
  if not line.startswith(' '):
   if not line.endswith(':'):fail('query target format')
   current=checked_name(line[:-1]);section=None
   if current in result:fail('duplicate queried target')
   result[current]={'rule':None,'inputs':[]}
  elif line.startswith('  input: '):
   if current is None or section is not None:fail('query input format')
   rule=line[len('  input: '):]
   if not re.fullmatch(r'[A-Za-z0-9_.+-]+',rule):fail('query rule format')
   result[current]['rule']=rule;section='input'
  elif line=='  outputs:':section='outputs'
  elif line.startswith('    ') and section in ('input','outputs'):
   name=line[4:];kind='explicit'
   if section=='input':
    if name.startswith('|| '):kind='order';name=name[3:]
    elif name.startswith('| '):kind='implicit';name=name[2:]
    result[current]['inputs'].append({'name':checked_name(name),'kind':kind})
   else:checked_name(name)
  else:fail('unsupported query section')
 if set(result)!=set(expected):fail('partial/extra query result')
 if sum(len(x['inputs']) for x in result.values())>MAX_EDGES:fail('query edge limit')
 return result

def link_deps(raw,target):
 if len(raw)>MAX_LOG:fail('link dependency bytes')
 lines=raw.decode().replace('\\\n',' ').splitlines();rows=[]
 for line in lines:
  if not line.strip():continue
  if line.count(':')!=1:fail('link dependency syntax')
  left,right=line.split(':');lhs=shlex.split(left);rhs=shlex.split(right)
  if len(lhs)!=1:fail('link dependency target')
  rows.append((checked_name(lhs[0]),[checked_name(p) for p in rhs]))
 if not rows or rows[0][0]!=str(target) or not rows[0][1] or len(rows[0][1])>256:fail('link dependency primary/count')
 # GNU ld emits empty compatibility rules for each input occurrence. No partial file acceptance.
 if any(rhs for _,rhs in rows[1:]) or [x for x,_ in rows[1:]]!=rows[0][1]:fail('incomplete linker dependency file')
 return rows[0][1]

def ninja_log(raw,build):
 lines=raw.decode().splitlines()
 if not lines or lines[0]!='# ninja log v7':fail('unsupported Ninja log version')
 result={}
 for line in lines[1:]:
  fields=line.split('\t')
  if len(fields)!=5 or any(not re.fullmatch(r'[0-9]+',v) for v in fields[:3]) or not re.fullmatch(r'[0-9a-f]{1,16}',fields[4]):fail('Ninja log record format')
  start,end,mtime=map(int,fields[:3]);path=canonical(fields[3],build)
  if end<start or mtime<=0:fail('ambiguous/stale Ninja log record')
  value={'startMs':start,'endMs':end,'mtimeNs':mtime,'commandHash':fields[4]}
  if path in result:
   previous=result[path]
   if fields[3] in previous['outputs'] or {k:v for k,v in previous.items() if k!='outputs'}!=value:fail('duplicate/conflicting Ninja log alias')
   previous['outputs'].append(fields[3])
  else:result[path]={**value,'outputs':[fields[3]]}
  if len(result)>MAX_NODES:fail('Ninja log count')
 return result

def grouped_deps(raw,build):
 result={};current=None;count=0
 for line in raw.decode().splitlines():
  if not line:
   if current is not None and len(result[current])!=count:fail('partial Ninja dependency record')
   current=None;continue
  if line.startswith('    '):
   if current is None:fail('orphan Ninja dependency')
   result[current].append(canonical(line[4:],build));continue
  if current is not None:fail('missing Ninja dependency separator')
  match=re.fullmatch(r'(.+): #deps ([0-9]+), deps mtime ([0-9]+) \(VALID\)',line)
  if not match:fail('stale/unsupported Ninja dependency record')
  current=canonical(match[1],build);count=int(match[2])
  if count>12000 or current in result or len(result)>=MAX_NODES:fail('dependency record count/duplicate')
  result[current]=[]
 if current is not None and len(result[current])!=count:fail('truncated Ninja dependency record')
 return result

def system_location(path,work):
 p=Path(path)
 if not p.is_absolute() or p.is_relative_to(work) or not any(p.is_relative_to(root) for root in (Path('/usr/lib/gcc'),Path('/usr/lib/x86_64-linux-gnu'))):fail('linker input outside reviewed system discovery locations')
 return p

def begin(run,source,compiler,cc,ninja,out):
 out=Path(out);version=command(run,'native-ninja-version',[ninja,'--version']).decode().strip()
 if version not in VERSIONS:fail('unreviewed Ninja version '+version[:80])
 driver=Path(compiler);raw=command(run,'native-linker-select',[driver,'-print-prog-name=ld']).decode().strip()
 if not raw or '\n' in raw:fail('linker selection format')
 linker=Path(raw) if Path(raw).is_absolute() else Path(shutil.which(raw,path=run.env['PATH']) or '/missing')
 linker=linker.resolve(strict=True);tools={k:identity(Path(p)) for k,p in [('compiler',compiler),('cc',cc),('ninja',ninja),('linker',linker)]}
 command(run,'native-linker-version',[linker,'--version'])
 probe=out/'link-input-probe';probe.mkdir();src=probe/'probe.cpp';obj=probe/'probe.o';lib=probe/'probe.so';dep=probe/'link.d';src.write_bytes(PROBE)
 command(run,'native-link-probe-compile',[driver,'-fPIC','-c',src,'-o',obj],60)
 command(run,'native-link-probe-link',[driver,'-shared',obj,*LIBRARIES,'-Wl,--dependency-file='+str(dep),'-o',lib],60)
 aliases={};inputs={}
 for name in link_deps(read(dep),lib):
  p=Path(name)
  if not p.is_absolute():fail('relative external link dependency')
  if p==obj:continue
  q=p.resolve(strict=True)
  system_location(q,run.work)
  aliases[name]=str(q);inputs[str(q)]=identity(q)['sha256']
 if not inputs:fail('empty external linker closure')
 for value in tools.values():
  if identity(Path(value['path']))!=value:fail('tool changed during probe')
 return {'schema':1,'sourceSha':source,'runOwner':run.owner,'workDir':str(run.work),'ninjaVersion':version,'tools':tools,'systemLink':{'aliases':aliases,'inputs':inputs,'depfileSha256':sha(dep),'sourceSha256':sha(src),'objectSha256':sha(obj),'outputSha256':sha(lib)},'graphs':{}}

def graph_before(run,state,builddir,target,label):
 if label not in DOMAINS or DOMAINS[label]!=(Path(builddir).name,target) or label in state['graphs']:fail('graph domain')
 build=Path(builddir);ninja=state['tools']['ninja']['path'];prefix='native-'+label
 cache=read(build/'CMakeCache.txt').decode()
 for key,expected in [('CMAKE_MAKE_PROGRAM',ninja),('CMAKE_CXX_COMPILER',state['tools']['compiler']['path'])]:
  values=re.findall(r'^'+key+r':(?:FILEPATH|STRING|UNINITIALIZED)=(.+)$',cache,re.M)
  if len(values)!=1 or str(Path(values[0]).resolve(strict=True))!=expected:fail('configured native tool differs')
 frozen=manifests(build,run);targets=target_names(command(run,prefix+'-targets',[ninja,'-C',build,'-t','targets','all']))
 if target not in targets:fail('selected declared target absent')
 raw=command(run,prefix+'-graph',[ninja,'-C',build,'-t','graph',*targets]);names=graph_names(raw);nodes={};batches=[]
 for index in range(0,len(names),64):
  run.remaining(1)
  batch=names[index:index+64];name=prefix+'-query-'+str(index//64);nodes.update(parse_query(command(run,name,[ninja,'-C',build,'-t','query',*batch]),batch));batches.append(name)
 paths={name:canonical(name,build) for name in names}
 producers=graph_info(raw)[1];aliases=aliases_for(nodes,paths,producers)
 if target not in nodes:fail('selected target absent')
 absent=[]
 for name,node in nodes.items():
  if node['rule'] and node['rule']!='phony':
   p=Path(paths[name])
   if not p.is_relative_to(build):fail('foreign declared output')
   if not p.exists():
    if p.is_symlink():fail('dangling output alias')
    absent.append(str(p))
 initial=build/'.ninja_log';baseline=read(initial) if initial.exists() else b''
 if baseline:ninja_log(baseline,build)
 (run.work/'capability'/('native-'+label+'-baseline-log.txt')).write_bytes(baseline)
 if manifests(build,run)!=frozen:fail('configured graph changed during observation')
 state['graphs'][label]={'target':target,'graphTargets':targets,'buildDirectory':str(build),'manifests':frozen,'dependencyRules':dependency_rules(frozen),'nodes':nodes,'paths':paths,'reachable':reachable(nodes,paths,producers,target),'queryLabels':batches,'producers':producers,'aliases':aliases,'absent':sorted(set(absent)),'baselineLogSha256':hashlib.sha256(baseline).hexdigest(),'baselineLogOutputs':sorted(ninja_log(baseline,build)) if baseline else []}

def pregenerate(run,state,cmake,seconds=120):
 from .capability import prebuild_snapshot
 graph=state['graphs']['opencv'];build=Path(graph['buildDirectory']);allowed=set()
 for target in GEN_TARGETS:
  if target not in graph['nodes']:fail('required pre-generation target absent')
  selected=set(reachable(graph['nodes'],graph['paths'],graph['producers'],target))
  if not selected<=set(graph['reachable']):fail('pre-generation target outside selected build')
  allowed.update(selected)
 initial=prebuild_snapshot(run,[build]);oldlog=read(build/'.ninja_log') if (build/'.ninja_log').exists() else b''
 if hashlib.sha256(oldlog).hexdigest()!=graph['baselineLogSha256']:fail('generation baseline changed')
 command(run,'native-opencv-generate',[cmake,'--build',build,'--target',*GEN_TARGETS,'--parallel','2'],seconds)
 if manifests(build,run)!=graph['manifests']:fail('graph changed during generation')
 after=prebuild_snapshot(run,[build]);metadata={str(build/'.ninja_log'),str(build/'.ninja_deps')}
 if any(after.get(name)!=digest for name,digest in initial.items() if name not in metadata):fail('preexisting configured input changed during generation')
 raw=read(build/'.ninja_log');records=ninja_log(raw,build)
 for path in records:
  if path not in allowed or path not in graph['absent'] or path in graph['baselineLogOutputs']:fail('unapproved/stale generation producer')
 for target in GEN_TARGETS:
  target_path=graph['paths'][target]
  if graph['nodes'][target]['rule']!='phony' and target_path not in records:fail('declared generation output not produced')
 frozen={name:digest for name,digest in after.items() if name not in initial and name not in metadata}
 if not frozen:fail('generation produced no frozen inputs')
 target=run.work/'capability/native-opencv-generation-log.txt';target.write_bytes(raw)
 state['generation']={'targets':list(GEN_TARGETS),'records':records,'logSha256':sha(target),'frozen':frozen,'preexisting':{n:h for n,h in initial.items() if n not in metadata}}
 return after

def graph_after(run,state,builddir,label):
 graph=state['graphs'][label];build=Path(builddir)
 if graph['buildDirectory']!=str(build) or manifests(build,run)!=graph['manifests']:fail('configured graph changed during build')
 raw=read(build/'.ninja_log');records=ninja_log(raw,build)
 target=run.work/'capability'/('native-'+label+'-build-log.txt');target.write_bytes(raw)
 graph['logSha256']=sha(target);graph['records']=records;graph['outputs']={};graph['outputStats']={}
 for name,node in graph['nodes'].items():
  p=graph['paths'][name]
  if node['rule'] and node['rule']!='phony' and p in graph['reachable'] and p in records:
   if p not in graph['absent'] or p in graph['baselineLogOutputs']:fail('generated output predates current build')
   value=identity(Path(p))
   graph['outputs'][p]=value['sha256'];graph['outputStats'][p]=value
 validate_edge_times(graph)

def validate_edge_times(graph):
 groups={}
 for path,record in graph['records'].items():
  if path not in graph['outputs']:continue
  names=record['outputs']
  if any(name not in graph['paths'] or graph['paths'][name]!=path for name in names):fail('log alias not declared in configured graph')
  producers={graph['producers'].get(name) for name in names}
  if len(producers)!=1 or None in producers:fail('ambiguous log producer aliases')
  edge=next(iter(producers));groups.setdefault(edge,[]).append(path)
 for edge,paths in groups.items():
  declared={graph['paths'][name] for name,p in graph['producers'].items() if p==edge}
  if set(paths)!=declared:fail('partial producer output evidence')
  records=[graph['records'][path] for path in paths]
  if len({(r['startMs'],r['endMs'],r['mtimeNs'],r['commandHash']) for r in records})!=1:fail('producer sibling log disagreement')
  # v1.13.2 logs command-start timestamps for ordinary rules, not file mtimes.
  # Freshness is initial absence + new owned build record + bound final stat/hash.

def labels(state):
 result=['native-opencv-generate','native-ninja-version','native-linker-select','native-linker-version','native-link-probe-compile','native-link-probe-link']
 for label in DOMAINS:
  if label in state['graphs']:result += ['native-'+label+'-targets','native-'+label+'-graph',*state['graphs'][label]['queryLabels']]
 return result

def input_paths(state):
 out=Path(state['workDir'])/'capability';probe=out/'link-input-probe'
 return {FILENAME:out/FILENAME,'native-opencv-generation-log.txt':out/'native-opencv-generation-log.txt',**{'native-tool-'+k:Path(v['path']) for k,v in state['tools'].items()},**{'native-probe-'+name:probe/name for name in ('probe.cpp','probe.o','probe.so','link.d')},**{'native-'+k+'-'+suffix+'.txt':out/('native-'+k+'-'+suffix+'.txt') for k in DOMAINS for suffix in ('build-log','baseline-log')}}

def roles(state,consumed,before,deps,run=None):
 if set(state['graphs'])!=set(DOMAINS):fail('incomplete native graph set')
 by_path={};outputs={};nodes={}
 for domain,graph in state['graphs'].items():
  for name,node in graph['nodes'].items():
   path=graph['paths'][name]
   if node['rule'] and node['rule']!='phony' and path in graph['reachable']:
    if path in by_path:
     olddomain,oldname=by_path[path]
     if olddomain!=domain or graph['producers'].get(oldname)!=graph['producers'].get(name):fail('ambiguous producers')
    else:by_path[path]=(domain,name)
  outputs.update(graph['outputs'])
 visiting=set();verified=set()
 def visit(path):
  if run:run.remaining(1)
  if path in verified:return
  if path in visiting or len(visiting)>=256:fail('dependency cycle/depth')
  visiting.add(path)
  if path in by_path:
   domain,name=by_path[path];graph=state['graphs'][domain];node=graph['nodes'][name]
   if path not in outputs:fail('declared output not actually produced')
   for item in node['inputs']:walk_name(domain,item['name'])
   for dep in deps.get(domain,{}).get(path,[]):visit(dep)
  elif path in before:
   if identity(Path(path))['sha256']!=before[path]:fail('recursive immutable input drift')
  elif path in state['systemLink']['inputs']:
   if identity(Path(path))['sha256']!=state['systemLink']['inputs'][path]:fail('recursive system input drift')
  else:fail('unbound recursive input '+path[:256])
  visiting.remove(path);verified.add(path)
 def walk_name(domain,name):
  graph=state['graphs'][domain]
  if name not in graph['nodes']:fail('unqueried graph dependency')
  node=graph['nodes'][name];path=graph['paths'][name]
  if node['rule']=='phony':
   if path in visiting:fail('phony dependency cycle')
   visiting.add(path)
   for item in node['inputs']:walk_name(domain,item['name'])
   visiting.remove(path)
  else:visit(path)
 for domain,graph in state['graphs'].items():walk_name(domain,graph['target'])
 result={}
 for path,digest in consumed.items():
  if path in before:
   if before[path]!=digest:fail('immutable input drift')
   scope='GENERATION_PHASE_PRECONSUMPTION_AND_POSTBUILD_IDENTICAL' if path in state.get('generation',{}).get('frozen',{}) else 'PREBUILD_AND_POSTBUILD_IDENTICAL'
   role={'sha256':digest,'scope':scope}
  elif path in state['systemLink']['inputs']:
   if state['systemLink']['inputs'][path]!=digest:fail('system link input drift')
   role={'sha256':digest,'scope':'SYSTEM_LINK_PREBUILD_AND_POSTBUILD_IDENTICAL'}
  elif path in by_path and outputs.get(path)==digest:
   visit(path);domain,name=by_path[path]
   role={'sha256':digest,'scope':'CURRENT_SUCCESSFUL_GRAPH_POSTBUILD_NOT_PRECONSUMPTION','graph':domain,'outputs':state['graphs'][domain]['aliases'][path]}
  else:fail('consumed input lacks verified role '+path[:256])
  result[path]=role
 return result

def validate(run,state,consumed,before,source):
 if not isinstance(state,dict) or set(state)!={'schema','sourceSha','runOwner','workDir','ninjaVersion','tools','systemLink','graphs','inputBindings','generation'} or state['schema']!=1 or state['sourceSha']!=source or state['runOwner']!=run.owner or state['workDir']!=str(run.work):fail('foreign provenance identity/schema')
 encoded=json.dumps(state,sort_keys=True,separators=(',',':')).encode()
 if len(encoded)>MAX_PROOF or getattr(run,'_native_provenance_owned',None)!=hashlib.sha256(encoded).hexdigest():fail('provenance differs from owned producer state')
 bindings={'consumed':hashlib.sha256(json.dumps(consumed,sort_keys=True).encode()).hexdigest(),'before':hashlib.sha256(json.dumps(before,sort_keys=True).encode()).hexdigest()}
 if state['inputBindings']!=bindings:fail('input ledger differs from producer')
 out=run.work/'capability';probe=out/'link-input-probe';driver=Path(state['tools']['compiler']['path']);ninja=Path(state['tools']['ninja']['path']);linker=Path(state['tools']['linker']['path'])
 for name,value in state['tools'].items():
  run.remaining(1)
  if identity(Path(value['path']))!=value:fail('selected native tool drift')
 if set(state['tools'])!={'compiler','cc','ninja','linker'}:fail('tool identity set')
 if record(run,'native-ninja-version',[ninja,'--version']).decode().strip()!=state['ninjaVersion'] or state['ninjaVersion'] not in VERSIONS:fail('Ninja version evidence')
 selected=record(run,'native-linker-select',[driver,'-print-prog-name=ld']).decode().strip()
 actual=Path(selected) if Path(selected).is_absolute() else Path(shutil.which(selected,path=run.env['PATH']) or '/missing')
 if actual.resolve()!=linker:fail('selected linker changed')
 version=record(run,'native-linker-version',[linker,'--version'])
 if not version.startswith(b'GNU ld '):fail('unsupported linker implementation')
 record(run,'native-link-probe-compile',[driver,'-fPIC','-c',probe/'probe.cpp','-o',probe/'probe.o'])
 record(run,'native-link-probe-link',[driver,'-shared',probe/'probe.o',*LIBRARIES,'-Wl,--dependency-file='+str(probe/'link.d'),'-o',probe/'probe.so'])
 system=state['systemLink']
 if read(probe/'probe.cpp')!=PROBE:fail('probe source differs')
 for filename,key in [('probe.cpp','sourceSha256'),('probe.o','objectSha256'),('probe.so','outputSha256'),('link.d','depfileSha256')]:
  if sha(probe/filename)!=system[key]:fail('link probe evidence changed')
 aliases={};members={}
 for name in link_deps(read(probe/'link.d'),probe/'probe.so'):
  p=Path(name)
  if p==probe/'probe.o':continue
  if not p.is_absolute():fail('relative linker input')
  q=p.resolve(strict=True)
  system_location(q,run.work)
  aliases[name]=str(q);members[str(q)]=identity(q)['sha256']
 if aliases!=system['aliases'] or members!=system['inputs']:fail('system linker input/alias drift')
 generation=state['generation'];build=out/'build';cmake=Path(shutil.which('cmake',path=run.env['PATH']) or '/missing').resolve()
 if generation['targets']!=list(GEN_TARGETS):fail('pre-generation target contract differs')
 record(run,'native-opencv-generate',[cmake,'--build',build,'--target',*GEN_TARGETS,'--parallel','2'])
 genlog=read(out/'native-opencv-generation-log.txt')
 if hashlib.sha256(genlog).hexdigest()!=generation['logSha256'] or ninja_log(genlog,build)!=generation['records']:fail('generation log changed')
 for name,digest in {**generation['preexisting'],**generation['frozen']}.items():
  run.remaining(1)
  if name in generation['frozen'] and before.get(name)!=digest:fail('generated bytes missing preconsumption snapshot')
  if identity(Path(name))['sha256']!=digest:fail('frozen generation/configured input changed')
 deps={}
 for domain,(dirname,target) in DOMAINS.items():
  if domain not in state['graphs']:fail('graph missing')
  graph=state['graphs'][domain];build=out/dirname;prefix='native-'+domain
  if graph['buildDirectory']!=str(build) or graph['target']!=target or manifests(build,run)!=graph['manifests'] or dependency_rules(graph['manifests'])!=graph['dependencyRules']:fail('graph source changed')
  targets=target_names(record(run,prefix+'-targets',[ninja,'-C',build,'-t','targets','all']))
  if targets!=graph['graphTargets'] or target not in targets:fail('declared graph target binding')
  raw=record(run,prefix+'-graph',[ninja,'-C',build,'-t','graph',*targets]);names=graph_names(raw);nodes={};query_labels=[]
  for index in range(0,len(names),64):
   run.remaining(1)
   batch=names[index:index+64];label=prefix+'-query-'+str(index//64);query_labels.append(label)
   nodes.update(parse_query(record(run,label,[ninja,'-C',build,'-t','query',*batch]),batch))
  paths={name:canonical(name,build) for name in names};producers=graph_info(raw)[1]
  if nodes!=graph['nodes'] or paths!=graph['paths'] or graph['queryLabels']!=query_labels or producers!=graph['producers'] or aliases_for(nodes,paths,producers)!=graph['aliases'] or reachable(nodes,paths,producers,target)!=graph['reachable']:fail('graph/query binding changed')
  baseline=read(out/(prefix+'-baseline-log.txt'))
  if hashlib.sha256(baseline).hexdigest()!=graph['baselineLogSha256'] or (sorted(ninja_log(baseline,build)) if baseline else [])!=graph['baselineLogOutputs']:fail('baseline Ninja log changed')
  final=read(out/(prefix+'-build-log.txt'))
  if sha(out/(prefix+'-build-log.txt'))!=graph['logSha256'] or read(build/'.ninja_log')!=final or ninja_log(final,build)!=graph['records']:fail('current Ninja log changed')
  build_records=[r for r in run.commands if r.get('label')==domain+'-build']
  if len(build_records)!=1:fail('missing authoritative build')
  build_record=build_records[0]
  expected=[str(Path(shutil.which('cmake',path=run.env['PATH']) or '/missing').resolve()),'--build',str(build),'--target',target,'--parallel','2']
  record(run,domain+'-build',expected)
  if not isinstance(build_record.get('seconds'),(int,float)):fail('owned build duration missing')
  for path,r in graph['records'].items():
   limit=build_record['seconds']
   if domain=='opencv' and path in generation['records']:
    if r!=generation['records'][path]:fail('frozen generator regenerated during compilation')
    limit=next(x for x in run.commands if x['label']=='native-opencv-generate')['seconds']
   if r['endMs']>1000*(limit+1):fail('Ninja record outside current phase elapsed bound')
  # Canonical current output bytes must still match every declared producing edge.
  for path,digest in graph['outputs'].items():
   run.remaining(1)
   if path not in graph['absent'] or path in graph['baselineLogOutputs'] or path not in graph['records'] or identity(Path(path))!=graph['outputStats'][path] or graph['outputStats'][path]['sha256']!=digest:fail('generated output/log drift')
  validate_edge_times(graph)
  depraw=record(run,domain+'-consumed-deps',[ninja,'-C',build,'-t','deps'])
  deps[domain]=grouped_deps(depraw,build)
  for name,node in graph['nodes'].items():
   path=graph['paths'][name];rule=node['rule']
   if path not in graph['reachable'] or rule in (None,'phony'):continue
   if rule not in graph['dependencyRules']:fail('producing rule missing from frozen manifests')
   kind=graph['dependencyRules'][rule]
   if kind not in (None,'gcc'):fail('unsupported declared compiler dependency kind')
   if kind=='gcc' and (path not in deps[domain] or not deps[domain][path]):fail('missing declared compiler/link dependency record')
 # No immutable leaf can change merely because it is not directly in flattened consumed set.
 # Scope this recheck to graph recursion, not the whole 1GiB system snapshot each time.
 rolemap=roles(state,consumed,before,deps,run)
 for path,digest in consumed.items():
  run.remaining(1)
  if identity(Path(path))['sha256']!=digest:fail('consumed bytes changed')
 return rolemap

def finish(run,state,consumed,before):
 state['inputBindings']={'consumed':hashlib.sha256(json.dumps(consumed,sort_keys=True).encode()).hexdigest(),'before':hashlib.sha256(json.dumps(before,sort_keys=True).encode()).hexdigest()}
 raw=json.dumps(state,sort_keys=True,separators=(',',':')).encode()
 if len((json.dumps(state,sort_keys=True,indent=2)+'\n').encode())>MAX_PROOF:fail('serialized proof file limit')
 run._native_provenance_owned=hashlib.sha256(raw).hexdigest()
 value=validate(run,state,consumed,before,state['sourceSha'])
 atomic_json(run.work/'capability'/FILENAME,state)
 return value
