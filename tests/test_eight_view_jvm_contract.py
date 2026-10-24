"""Real Kotlin contract tests; historical replay plus labeled synthetic exporter extension.
No new compiler/exporter evidence is attributed to the original retained run.
"""
import hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET,zipfile
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1]
ARTIFACT_SHA='d23b1aec0841b94ef24b7e69e824c8af05e49500471b6f8646300ff02026215b'
SOURCE=ROOT/'docs/development/experiments/eight-view-sparse-runtime-successor'
FILES={'value':'capability/capability.json','native':'capability/native-input-provenance.json',
       'before':'capability/prebuild-inputs.json','consumed':'capability/consumed-inputs.json','provenance':'capability/consumed-provenance.json'}
HARNESS=r'''
package com.realityforge.scanner.hostintegration
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.json.JSONArray
object JvmContractHarness {
 @JvmStatic fun main(args:Array<String>) {
  val root=File(args[0]); val value=JSONObject(File(root,"value.json").readText());val native=JSONObject(File(root,"native.json").readText())
  val header=value.getJSONObject("headerProvider");val before=JSONObject(File(root,"before.json").readText());val consumed=JSONObject(File(root,"consumed.json").readText());val provenance=JSONObject(File(root,"provenance.json").readText())
  val producer=value.getJSONObject("producer");val expected=CapabilityIdentity.Producer(producer.getString("runOwner"),producer.getString("workDir"),producer.getString("sourceSha"))
  fun derive(v:JSONObject=value,n:JSONObject=native,h:JSONObject=header,b:JSONObject=before,c:JSONObject=consumed,p:JSONObject=provenance,e:CapabilityIdentity.Producer=expected)=NativeCapabilityContract.derive(v,n,h,b,c,p,e)
  fun copy(value:JSONObject)=JSONObject(value.toString())
  var refused=0;val unexpectedlyAccepted=mutableListOf<String>()
  fun reject(name:String,action:()->Unit){var failed=false;try{action()}catch(e:IllegalArgumentException){failed=true}catch(e:IllegalStateException){failed=true}catch(e:org.json.JSONException){failed=true};if(failed)refused++ else {unexpectedlyAccepted.add(name);println("ACCEPTED_INVALID_CONTRACT: $name")}}
  val historical=JSONObject(File(root,"historical-value.json").readText())
  reject("historical-capability-lacks-export-extension"){derive(v=historical)}
  val accepted=derive()
  check(accepted.inputs.keys==value.getJSONObject("inputs").keys().asSequence().toSet())
  val commands=producer.getJSONArray("commands")
  check(accepted.labels==(0 until commands.length()).map{commands.getJSONObject(it).getString("label")}.toSet())
  check(NativeCapabilityContract.canonicalMapHash(before)==native.getJSONObject("inputBindings").getString("before"))
  check(NativeCapabilityContract.canonicalMapHash(consumed)==native.getJSONObject("inputBindings").getString("consumed"))
  check(provenance.keys().asSequence().map{provenance.getJSONObject(it).getString("scope")}.toSet().size==4)
  for(field in listOf("sourceSha","runOwner","workDir")) {val n=copy(native);n.put(field,if(field=="workDir")"/foreign" else "0".repeat(if(field=="sourceSha")40 else 32));reject("native-$field"){derive(n=n)}}
  for(field in listOf("sourceSha","runOwner","workDir")) {val h=copy(header);h.put(field,if(field=="workDir")"/foreign" else "0".repeat(if(field=="sourceSha")40 else 32));reject("header-$field"){derive(h=h)}}
  for(field in listOf("schema","extra")){val n=copy(native);n.put(field,99);reject("native-$field"){derive(n=n)}}
  for(name in listOf("compiler","native-tool-ninja","headerProviderDriver-16","native-opencv-baseline-log.txt",
    "exportNativeOwner","exportJniSource","jniObject","exportJniObject","jniDepfile","exportJniDepfile")) {
   val v=copy(value);v.getJSONObject("inputs").remove(name);reject("missing-$name"){derive(v=v)}
  }
  // Both real prepared-owner forms are admitted: sealed JAR snapshots or paired directory classes.
  run {val v=copy(value);val i=v.getJSONObject("inputs");val base=expected.workDir+"/synthetic-class-directory"
   i.getJSONObject("nativeOwner").put("path",base+"/com/realityforge/scanner/engine/NativeCore.class")
   i.getJSONObject("exportNativeOwner").put("path",base+"/com/realityforge/scanner/export/NativeAssetExports.class")
   derive(v=v)
   i.getJSONObject("exportNativeOwner").put("path",expected.workDir+"/owner-com.realityforge.scanner.export.NativeAssetExports.class")
   reject("mixed-owner-containers"){derive(v=v)}
  }
  for(name in listOf("exportNativeOwner","exportJniSource","jniObject","exportJniObject","jniDepfile","exportJniDepfile")) {
   val v=copy(value);v.getJSONObject("inputs").getJSONObject(name).put("path",expected.workDir+"/wrong-"+name)
   reject("export-path-$name"){derive(v=v)}
  }
  for(label in listOf("scanner-export-javap","scanner-jni-compile","scanner-export-compile")) {
   val v=copy(value);val rows=v.getJSONObject("producer").getJSONArray("commands")
   val row=(0 until rows.length()).map{rows.getJSONObject(it)}.single{it.getString("label")==label}
   row.put("label",label+"-drift")
   reject("export-label-$label"){derive(v=v)}
  }
  run {val v=copy(value);v.getJSONObject("inputs").getJSONObject("exportJniSource").put("sha256","0".repeat(64));reject("export-source-hash"){derive(v=v)}}
  run {val b=copy(before);b.put(value.getJSONObject("inputs").getJSONObject("exportJniSource").getString("path"),"0".repeat(64));reject("export-source-prebuild-drift"){derive(b=b)}}
  run {val v=copy(value);v.getJSONObject("inputs").put("unknown-input",v.getJSONObject("inputs").getJSONObject("compiler"));reject("extra-input"){derive(v=v)}}
  run {val n=copy(native);n.getJSONObject("graphs").getJSONObject("opencv").put("baselineLogSha256","0".repeat(64));reject("baseline-hash"){derive(n=n)}}
  run {val n=copy(native);n.getJSONObject("graphs").getJSONObject("opencv").getJSONArray("baselineLogOutputs").put("unowned");reject("baseline-nonempty"){derive(n=n)}}
  run {val n=copy(native);n.getJSONObject("graphs").getJSONObject("scanner").getJSONObject("orderSentinel").put("kind","explicit");reject("order-kind"){derive(n=n)}}
  run {val n=copy(native);val phases=n.getJSONObject("generation").getJSONArray("phases");val first=phases.get(0);phases.put(0,phases.get(1));phases.put(1,first);reject("phase-order"){derive(n=n)}}
  run {val n=copy(native);n.getJSONObject("graphs").getJSONObject("opencv").getJSONArray("queryLabels").put("unapproved-label");reject("query-label"){derive(n=n)}}
  run {val h=copy(header);val ps=h.getJSONArray("providers");val a=ps.get(0);ps.put(0,ps.get(1));ps.put(1,a);reject("header-order"){derive(h=h)}}
  val key=consumed.keys().asSequence().first()
  for(scope in listOf("UNKNOWN","POST_GENERATION_ONLY_NOT_PRECONSUMPTION")) {val p=copy(provenance);p.getJSONObject(key).put("scope",scope);reject("scope-$scope"){derive(p=p)}}
  run {val p=copy(provenance);p.getJSONObject(key).put("extra",true);reject("role-extra"){derive(p=p)}}
  run {val p=copy(provenance);p.remove(key);reject("role-missing"){derive(p=p)}}
  run {val p=copy(provenance);p.getJSONObject(key).put("sha256","0".repeat(64));reject("role-drift"){derive(p=p)}}
  run {val v=copy(value);v.getJSONObject("inputs").getJSONObject("native-opencv-baseline-log.txt").put("path",expected.workDir+"/capability/../escaped.txt");reject("baseline-escape"){derive(v=v)}}
  for(scope in provenance.keys().asSequence().map{provenance.getJSONObject(it).getString("scope")}.toSet()) {
   val roleKey=provenance.keys().asSequence().first{provenance.getJSONObject(it).getString("scope")==scope}
   val p=copy(provenance);p.getJSONObject(roleKey).put("scope",if(scope=="PREBUILD_AND_POSTBUILD_IDENTICAL")"SYSTEM_LINK_PREBUILD_AND_POSTBUILD_IDENTICAL" else "PREBUILD_AND_POSTBUILD_IDENTICAL")
   reject("role-substitution-$scope"){derive(p=p)}
  }
  run {val produced=provenance.keys().asSequence().first{provenance.getJSONObject(it).has("outputs")};val p=copy(provenance);p.getJSONObject(produced).getJSONArray("outputs").put("unapproved-alias");reject("role-output-alias"){derive(p=p)}}
  run {val v=copy(value);val inputs=v.getJSONObject("inputs");val first=inputs.get("consumed-0");inputs.put("consumed-0",inputs.get("consumed-1"));inputs.put("consumed-1",first);reject("consumed-index-swap"){derive(v=v)}}
  run {val n=copy(native);val admission=n.getJSONObject("generation").getJSONObject("firstAdmission");val p=admission.keys().asSequence().first();admission.put(p,99);reject("first-admission-phase"){derive(n=n)}}
  val fifth=File(root,"fifth")
  val fv=JSONObject(File(fifth,"value.json").readText());val fn=JSONObject(File(fifth,"native.json").readText());val fc=JSONObject(File(fifth,"consumed.json").readText());val fp=JSONObject(File(fifth,"provenance.json").readText())
  val fifthContract=derive(v=fv,n=fn,c=fc,p=fp)
  check(fifthContract.inputs.keys==fv.getJSONObject("inputs").keys().asSequence().toSet())
  check(fp.keys().asSequence().map{fp.getJSONObject(it).getString("scope")}.toSet().size==5)
  val packageKey=fp.keys().asSequence().single{fp.getJSONObject(it).getString("scope")=="CURRENT_SUCCESSFUL_JAVA_PACKAGE_POSTBUILD_NOT_PRECONSUMPTION"}
  run {val n=copy(fn);n.getJSONObject("generation").getJSONObject("packaging").put("outputs",JSONArray());reject("unbound-package-role"){derive(v=fv,n=n,c=fc,p=fp)}}
  run {val p=copy(fp);p.getJSONObject(packageKey).put("graph","scanner");reject("wrong-package-graph"){derive(v=fv,n=fn,c=fc,p=p)}}
  for(kind in listOf("missing","extra","duplicate")) {
   val v=copy(value);val rows=v.getJSONObject("producer").getJSONArray("commands")
   when(kind){"missing"->rows.remove(0);"extra"->{val extra=copy(rows.getJSONObject(0));extra.put("label","unapproved-command");rows.put(extra)};else->rows.put(rows.getJSONObject(0))}
   reject("command-$kind"){derive(v=v)}
  }
  val unicode=JSONObject(File(root,"unicode.json").readText());check(NativeCapabilityContract.canonicalMapHash(unicode)==File(root,"unicode.sha256").readText())
  check(NativeCapabilityContract.codepointSorted(listOf("\uD800\uDC00","\uE000"))==listOf("\uE000","\uD800\uDC00"))
  val privateWork=File(root,"private-work");val cap=File(privateWork,"capability");cap.mkdirs()
  for(domain in listOf("opencv","scanner")) {
   val name="native-$domain-baseline-log.txt";val file=File(cap,name);file.writeBytes(byteArrayOf())
   val spec=NativeCapabilityContract.Input(file.path,NativeCapabilityContract.EMPTY_SHA,true)
   NativeCapabilityContract.verifyInput(name,spec,privateWork.path)
   reject("generic-empty-$domain"){RuntimeProviderIdentity.fileHash(file)}
   reject("wrong-empty-role-$domain"){NativeCapabilityContract.verifyInput("compiler",spec,privateWork.path)}
   reject("cross-work-$domain"){NativeCapabilityContract.verifyInput(name,spec,File(root,"foreign").path)}
   reject("false-empty-flag-$domain"){NativeCapabilityContract.verifyInput(name,spec.copy(emptyBaseline=false),privateWork.path)}
   file.writeText("changed");reject("baseline-changed-$domain"){NativeCapabilityContract.verifyInput(name,spec,privateWork.path)}
   file.delete();val target=File(root,"empty-target-$domain");target.writeBytes(byteArrayOf());Files.createSymbolicLink(file.toPath(),target.toPath())
   reject("baseline-symlink-$domain"){NativeCapabilityContract.verifyInput(name,spec,privateWork.path)};Files.delete(file.toPath())
  }
  val normal=File(cap,"normal-file");normal.writeText("fixture bytes");val normalHash=RuntimeProviderIdentity.fileHash(normal)
  val normalSpec=NativeCapabilityContract.Input(normal.path,normalHash)
  NativeCapabilityContract.verifyInput("compiler",normalSpec,privateWork.path)
  normal.writeText("drifted bytes");reject("normal-drift"){NativeCapabilityContract.verifyInput("compiler",normalSpec,privateWork.path)}
  val json=File(cap,"bounded.json");json.writeText("{\"fixture\":true}");val jsonHash=RuntimeProviderIdentity.fileHash(json)
  check(NativeCapabilityContract.readJson(json,jsonHash,1024).getBoolean("fixture"))
  reject("json-cap"){NativeCapabilityContract.readJson(json,jsonHash,1)}
  reject("json-hash"){NativeCapabilityContract.readJson(json,"0".repeat(64),1024)}
  reject("json-escape"){NativeCapabilityContract.readJson(File(cap,"../capability/bounded.json"),jsonHash,1024)}
  val parent=File(privateWork,"real-parent");parent.mkdir();val aliasedParent=File(privateWork,"aliased-parent");Files.createSymbolicLink(aliasedParent.toPath(),parent.toPath())
  val realChild=File(parent,"child");realChild.writeText("parent alias fixture");val aliasSpec=NativeCapabilityContract.Input(File(aliasedParent,"child").path,RuntimeProviderIdentity.fileHash(realChild))
  reject("parent-directory-symlink"){NativeCapabilityContract.verifyInput("compiler",aliasSpec,privateWork.path)}
  // Actual cached adapter invocation with test-only constructor reflection. No successful
  // native/runtime load is claimed; each test must reach its precise refusal boundary.
  val cachedRoot=File(root,"cached-work");val cachedFile=File(cachedRoot,"capability/capability.json")
  val cachedValue=JSONObject(cachedFile.readText());val cachedHash=RuntimeProviderIdentity.fileHash(cachedFile)
  val constructor=QualifiedCapability::class.java.getDeclaredConstructor(File::class.java,String::class.java,JSONObject::class.java,File::class.java);constructor.isAccessible=true
  fun cached(v:JSONObject=cachedValue)=constructor.newInstance(cachedFile,cachedHash,v,File(root,"deliberately-unused-policy"))
  val propertyKeys=listOf("rf.loader.workDir","rf.loader.runOwner","rf.loader.sourceSha");val oldProperties=propertyKeys.associateWith{System.getProperty(it)}
  try {
   System.setProperty(propertyKeys[0],cachedRoot.path);System.setProperty(propertyKeys[1],expected.runOwner);System.setProperty(propertyKeys[2],expected.sourceSha)
   val changed=copy(cachedValue);changed.put("inMemoryMutation",true)
   var memoryRefused=false
   try{cached(changed).verifyCurrent()}catch(failure:IllegalArgumentException){memoryRefused=failure.stackTrace.any{it.className.endsWith("QualifiedCapability")&&it.methodName=="verifyCurrent"}}
   check(memoryRefused){"Mutable cached manifest did not fail verifyCurrent boundary"};refused++
   val proof=File(cachedRoot,"capability/native-input-provenance.json");val originalProof=proof.readBytes();proof.appendText(" ")
   var proofRefused=false
   try{cached().verifyCurrent()}catch(failure:IllegalArgumentException){proofRefused=failure.message=="Bound capability JSON changed"}
   check(proofRefused){"Stale native proof did not fail bound JSON identity"};refused++;proof.writeBytes(originalProof)
   // Establish that the relocated metadata itself is accepted before touching inputs.
   val cachedNative=JSONObject(proof.readText());val cachedHeader=JSONObject(File(cachedRoot,"capability/header-provider.json").readText())
   val cachedBefore=JSONObject(File(cachedRoot,"capability/prebuild-inputs.json").readText());val cachedConsumed=JSONObject(File(cachedRoot,"capability/consumed-inputs.json").readText());val cachedProvenance=JSONObject(File(cachedRoot,"capability/consumed-provenance.json").readText())
   val relocated=CapabilityIdentity.Producer(expected.runOwner,cachedRoot.path,expected.sourceSha)
   NativeCapabilityContract.derive(cachedValue,cachedNative,cachedHeader,cachedBefore,cachedConsumed,cachedProvenance,relocated)
   var inputRefused=false
   try{cached().verifyCurrent()}catch(failure:IllegalArgumentException){inputRefused=failure.message=="Capability input changed: compiler"}
   check(inputRefused){"Cached input drift did not reach compiler identity gate"};refused++
  } finally {for((key,value)in oldProperties){if(value==null)System.clearProperty(key)else System.setProperty(key,value)}}
  check(unexpectedlyAccepted.isEmpty()) {"Accepted invalid contracts: $unexpectedlyAccepted"}
  File(root,"result.json").writeText(JSONObject().put("scope","RETAINED_METADATA_CONTRACT_NOT_LIVE_NATIVE_QUALIFICATION").put("inputs",accepted.inputs.size).put("labels",accepted.labels.size).put("refusals",refused).put("exportExtensionFixture","SYNTHETIC_NEW_EXPORT_JNI_ROLES_NOT_ORIGINAL_REPLAY").put("fifthRoleFixture","SYNTHETIC_ADDITION_USING_OBSERVED_DECLARED_PACKAGE_OUTPUT").toString(2))
  println("JVM_NATIVE_CONTRACT_PASS refusals=$refused")
 }
}
'''


def test_actual_kotlin_native_contract_retained_metadata_and_adversaries(tmp_path):
 archive=Path(os.environ.get('RF_JVM_CONTRACT_REPLAY_ZIP','/missing'))
 lib=Path(os.environ.get('RF_KOTLIN_TEST_LIB','/missing'));json_jar=Path(os.environ.get('RF_JVM_JSON_JAR','/missing'))
 java=Path(os.environ.get('RF_PHASE_JAVA_HOME',os.environ.get('JAVA_HOME','/missing')))/'bin/java'
 if not archive.is_file() or not (lib/'kotlin-compiler-embeddable-2.1.21.jar').is_file() or not json_jar.is_file() or not java.is_file():
  if os.environ.get('RF_REQUIRE_JVM_CONTRACT')=='1':pytest.fail('Required actual Kotlin/JVM contract tools/artifact unavailable')
  pytest.skip('Real contract fixture prerequisites unavailable; no runtime qualification')
 assert archive.stat().st_size<=32*1024**2 and hashlib.sha256(archive.read_bytes()).hexdigest()==ARTIFACT_SHA
 metadata=ET.parse(ROOT/'gradle/verification-metadata.xml').getroot()
 pins={a.get('name'):h.get('value') for c in metadata.iter() if c.tag.endswith('component') for a in c if a.tag.endswith('artifact') for h in a if h.tag.endswith('sha256')}
 compiler=sorted(lib.glob('*.jar'))
 for jar in compiler+[json_jar]:assert hashlib.sha256(jar.read_bytes()).hexdigest()==pins[jar.name],jar.name
 data={}
 with zipfile.ZipFile(archive) as z:
  for key,name in FILES.items():
   assert z.getinfo(name).file_size<=16*1024**2
   raw=z.read(name);data[key]=json.loads(raw);(tmp_path/(key+'.json')).write_bytes(raw)
  for domain in ('opencv','scanner'):assert z.read('capability/native-'+domain+'-baseline-log.txt')==b''
 # The retained e818 run predates exporter JNI inclusion. Preserve it as a negative,
 # then enrich a COPY with explicitly synthetic new roles/commands, not new historical proof.
 (tmp_path/'historical-value.json').write_text(json.dumps(data['value']))
 value=data['value'];native=data['native'];inputs=value['inputs'];work=value['producer']['workDir']
 assert not {'exportNativeOwner','exportJniSource','jniObject','exportJniObject','jniDepfile','exportJniDepfile'} & inputs.keys()
 export_source=inputs['jniSource']['path'].removesuffix('/jni.cpp')+'/asset_exports_jni.cpp'
 assert export_source in data['before'] and export_source not in data['consumed']
 digest=data['before'][export_source]
 inputs['exportJniSource']={'path':export_source,'sha256':digest}
 inputs['exportNativeOwner']={'path':work+'/owner-com.realityforge.scanner.export.NativeAssetExports.class',
                              'sha256':hashlib.sha256(b'SYNTHETIC exported owner contract metadata').hexdigest()}
 for role,name in [('jniObject','jni.o'),('exportJniObject','export-jni.o'),('jniDepfile','jni.d'),('exportJniDepfile','export-jni.d')]:
  inputs[role]={'path':work+'/capability/'+name,'sha256':hashlib.sha256(('SYNTHETIC direct compiler output '+role).encode()).hexdigest()}
 data['consumed'][export_source]=digest
 data['provenance'][export_source]={'sha256':digest,'scope':'PREBUILD_AND_POSTBUILD_IDENTICAL'}
 for key in list(inputs):
  if key.startswith('consumed-') and key[9:].isdigit():del inputs[key]
 for i,path in enumerate(sorted(data['consumed'])):inputs['consumed-'+str(i)]={'path':path,'sha256':data['consumed'][path]}
 native['inputBindings']['consumed']=hashlib.sha256(json.dumps(data['consumed'],sort_keys=True).encode()).hexdigest()
 command=next(row for row in value['producer']['commands'] if row['label']=='scanner-javap')
 for label in ('scanner-export-javap','scanner-jni-compile','scanner-export-compile'):
  row=json.loads(json.dumps(command));row['label']=label
  row['argv']=['SYNTHETIC_METADATA_EXTENSION_NOT_EXECUTED',label]
  value['producer']['commands'].append(row)
 for key in ('before','consumed','provenance','native'):
  raw=(json.dumps(data[key],sort_keys=True,indent=2)+'\n').encode()
  inputs[Path(FILES[key]).name]['sha256']=hashlib.sha256(raw).hexdigest()
 value['nativeInputProvenance']=dict(inputs['native-input-provenance.json'])
 for key in FILES:(tmp_path/(key+'.json')).write_text(json.dumps(data[key]))
 fifth=tmp_path/'fifth';fifth.mkdir();changed=json.loads(json.dumps(data))
 native=changed['native'];value=changed['value'];consumed=changed['consumed'];provenance=changed['provenance']
 package=next(p for p in native['generation']['packaging']['outputs'] if p.endswith('/bin/opencv-4120.jar'))
 digest=native['graphs']['opencv']['outputs'][package]
 assert package not in consumed
 consumed[package]=digest;provenance[package]={'sha256':digest,'scope':'CURRENT_SUCCESSFUL_JAVA_PACKAGE_POSTBUILD_NOT_PRECONSUMPTION','graph':'opencv','outputs':native['graphs']['opencv']['aliases'][package]}
 native['inputBindings']['consumed']=hashlib.sha256(json.dumps(consumed,sort_keys=True).encode()).hexdigest()
 inputs=value['inputs']
 for key in list(inputs):
  if key.startswith('consumed-') and key[9:].isdigit():del inputs[key]
 for i,path in enumerate(sorted(consumed)):inputs['consumed-'+str(i)]={'path':path,'sha256':consumed[path]}
 for key,filename in [('native','native-input-provenance.json'),('consumed','consumed-inputs.json'),('provenance','consumed-provenance.json')]:
  raw=(json.dumps(changed[key],sort_keys=True,indent=2)+'\n').encode();inputs[filename]['sha256']=hashlib.sha256(raw).hexdigest()
  if key=='native':value['nativeInputProvenance']['sha256']=inputs[filename]['sha256']
 for key in ('value','native','consumed','provenance'):(fifth/(key+'.json')).write_text(json.dumps(changed[key]))
 # Explicit metadata relocation for EARLY cached-refusal boundaries, never native qualification.
 cached_root=tmp_path/'cached-work';cached_cap=cached_root/'capability';cached_cap.mkdir(parents=True)
 old_work=data['value']['producer']['workDir'];old_compiler=data['value']['inputs']['compiler']['path']
 compiler_file=cached_root/'selected-compiler';compiler_file.write_bytes(b'deliberately changed compiler bytes')
 def relocate(item):
  if isinstance(item,str):return item.replace(old_work,str(cached_root)).replace(old_compiler,str(compiler_file))
  if isinstance(item,list):return [relocate(v) for v in item]
  if isinstance(item,dict):return {relocate(k):relocate(v) for k,v in item.items()}
  return item
 cached_data=relocate(data);cv=cached_data['value'];cn=cached_data['native'];ci=cv['inputs']
 cn['inputBindings']={key:hashlib.sha256(json.dumps(cached_data[key],sort_keys=True).encode()).hexdigest() for key in ('before','consumed')}
 for key,name in FILES.items():
  if key=='value':continue
  raw=(json.dumps(cached_data[key],sort_keys=True,indent=2)+'\n').encode();filename=Path(name).name
  (cached_cap/filename).write_bytes(raw);ci[filename]['sha256']=hashlib.sha256(raw).hexdigest()
 raw=(json.dumps(cv['headerProvider'],sort_keys=True,indent=2)+'\n').encode();(cached_cap/'header-provider.json').write_bytes(raw)
 ci['header-provider.json']['sha256']=hashlib.sha256(raw).hexdigest();cv['nativeInputProvenance']=dict(ci['native-input-provenance.json'])
 (cached_cap/'capability.json').write_text(json.dumps(cv,sort_keys=True,indent=2)+'\n')
 unicode={'\ue000':'value','\U00010000':'astral','quote\"\n':'\t'}
 (tmp_path/'unicode.json').write_text(json.dumps(unicode))
 (tmp_path/'unicode.sha256').write_text(hashlib.sha256(json.dumps(unicode,sort_keys=True).encode()).hexdigest())
 harness=tmp_path/'JvmContractHarness.kt';harness.write_text(HARNESS)
 sources=[SOURCE/name for name in ('NativeCapabilityContract.kt','CapabilityIdentity.kt','RuntimeProviderIdentity.kt','QualifiedCapability.kt','EvidenceFailureHandling.kt')]
 runtime=[lib/'kotlin-stdlib-2.1.21.jar',json_jar];classpath=lambda items:os.pathsep.join(map(str,items))
 command=[str(java),'-Xmx768m','-XX:ActiveProcessorCount=2','-cp',classpath(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',classpath(runtime),'-d',str(tmp_path/'classes'),*map(str,sources),str(harness)]
 compiled=subprocess.run(command,text=True,capture_output=True,timeout=120);assert compiled.returncode==0,compiled.stdout+compiled.stderr
 result=subprocess.run([str(java),'-Xmx768m','-XX:ActiveProcessorCount=2','-cp',classpath([tmp_path/'classes']+runtime),'com.realityforge.scanner.hostintegration.JvmContractHarness',str(tmp_path)],capture_output=True,text=True,timeout=120)
 assert result.returncode==0,result.stdout+result.stderr
 assert 'JVM_NATIVE_CONTRACT_PASS' in result.stdout
 evidence=os.environ.get('RF_JVM_CONTRACT_EVIDENCE_DIR')
 if evidence:
  dest=Path(evidence);dest.mkdir(parents=True,exist_ok=True)
  for name in ('result.json','JvmContractHarness.kt'):shutil.copyfile(tmp_path/name,dest/name)
  (dest/'compile.log').write_text(compiled.stdout+compiled.stderr);(dest/'run.log').write_text(result.stdout+result.stderr)


def test_qualified_adapter_rechecks_sealed_contract_at_load_reuse_and_capture():
 source=(SOURCE/'QualifiedCapability.kt').read_text()
 current=source[source.index('    fun verifyCurrent()'):source.index('    /** Called only after')]
 for call in ('NativeCapabilityContract.readJson','contract(value,','NativeCapabilityContract.verifyInputs(contract)','verifyCommands(value,contract,'):
  assert call in current
 load=source[source.index('@Synchronized fun load()'):]
 assert 'accepted?.let { it.verifyCurrent();return it }' in load
 assert 'NativeCapabilityContract.verifyInputs(contract)' in load and 'verifyCommands(value,contract,' in load
 capture=source[source.index('    fun captureRuntime('):source.index('    companion object')]
 assert 'verifyCurrent()' in capture
 assert 'POST_GENERATION_ONLY_NOT_PRECONSUMPTION' not in source
 assert 'NativeCapabilityContract.derive(' in source
 # The generic binary helper remains nonempty; only the derived baseline role owns zero bytes.
 runtime=(SOURCE/'RuntimeProviderIdentity.kt').read_text()
 assert 'file.length() in 1..limit' in runtime
