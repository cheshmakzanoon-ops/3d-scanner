"""Real AGP serializer/Robolectric Fs with actual Gradle projection, not Android resource parsing."""
import hashlib,json,os,subprocess,zipfile
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1]
CONFIG='com/android/tools/test_config.properties'
JARS={'gradle-8.11.1.jar':'3f71d6d3bf4226a2c83131fe28980842f834b84375f04bf124cb00ab72506ea2',
      'resources-4.14.1.jar':'54eb274a47dec9e74cc9b45b9e4335bb5d83857c63b2a49dd8d1993e6321f2c5'}
JAVA=r'''
import com.android.build.gradle.tasks.GenerateTestConfig;
import org.robolectric.res.Fs;
import java.nio.file.*;import java.net.*;import java.io.*;import java.util.*;
public class RealAgpResources {
 public static void main(String[] args) throws Exception {
  if(args[0].equals("generate")) {
   GenerateTestConfig.generateTestConfigFile(new GenerateTestConfig.TestConfigProperties("build/resources.ap_","build/assets","build/AndroidManifest.xml","fixture.resources"),Paths.get(args[1]));return;
  }
  try(URLClassLoader loader=new URLClassLoader(new URL[]{Paths.get(args[1]).toUri().toURL()},null)) {
   Enumeration<URL> all=loader.getResources("com/android/tools/test_config.properties");int count=0;while(all.hasMoreElements()){all.nextElement();count++;}if(count!=1)throw new AssertionError("config multiplicity");
   Properties p=new Properties();try(InputStream in=loader.getResourceAsStream("com/android/tools/test_config.properties")){p.load(in);}
   if(!p.getProperty("android_custom_package").equals("fixture.resources"))throw new AssertionError("package");
   for(String key:new String[]{"android_resource_apk","android_merged_assets","android_merged_manifest"}) {
    Path path=Fs.fromUrl(p.getProperty(key));
    if(key.equals("android_merged_assets")){if(!Files.readString(path.resolve("fixture.txt")).equals("asset bytes"))throw new AssertionError("assets");}
    else if(Files.size(path)==0)throw new AssertionError("empty resource");
   }
   System.out.println("ACTUAL_AGP_SERIALIZER_AND_ROBOLECTRIC_FS_OK_NOT_ANDROID_PARSE");
  }
 }
}
'''
GROOVY=r'''
def projectDirFixture=file('app');def buildFixture=file('app/build');def configDir=file('app/build/intermediates/unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out')
def expected=[android_resource_apk:'build/resources.ap_',android_merged_assets:'build/assets',android_merged_manifest:'build/AndroidManifest.xml',android_custom_package:'fixture.resources']
def declared=[android_resource_apk:file('app/build/resources.ap_'),android_merged_assets:file('app/build/assets'),android_merged_manifest:file('app/build/AndroidManifest.xml')]
// Projection-level typed descriptors only; real task graph/type provenance is not qualified by this fixture.
def producers=[android_resource_apk:[task:':app:packageDebugUnitTestForUnitTest',taskType:'com.android.build.gradle.internal.tasks.PackageForHostTest',outputProperty:'apkForHostTest'],android_merged_assets:[task:':app:mergeDebugAssets',taskType:'com.android.build.gradle.tasks.MergeSourceSetFolders',outputProperty:'outputDir'],android_merged_manifest:[task:':app:processDebugManifestForPackage',taskType:'com.android.build.gradle.tasks.ProcessPackagedManifestTask',outputProperty:'packageManifests']]
tasks.register('verifyProjection') { doLast {
 def work=file('projection');work.mkdirs();def left=file('left');left.mkdirs();def right=file('right');right.mkdirs()
 def cp=[left,configDir,right];def config=new File(configDir,EightViewAgpResources.CONFIG);byte[] original=config.bytes
 def project={ List paths,File dir,Map outputs,Map values -> EightViewAgpResources.project(paths,projectDirFixture,buildFixture,dir,configDir,outputs,values,producers) }
 def result=project(cp,work,declared,expected)
 assert result.cp[0]==left && result.cp[2]==right && result.cp[1]==new File(work,'agp-test-config')
 assert result.ledger.index==1 && result.ledger.originalValues==expected && config.bytes==original
 assert new File(work,'agp-original-test-config.properties').bytes==original
 assert result.ledger.resources*.key==EightViewAgpResources.PATH_KEYS
 assert result.ledger.properties.android_custom_package==expected.android_custom_package
 assert project(cp,work,declared,expected)==result
 file('projection.json').text=groovy.json.JsonOutput.toJson(result.ledger)
 def refused=[]
 def reject={String name,Closure action -> boolean failed=false;try {action()}catch(AssertionError|IOException error){failed=true};assert failed:name;refused<<name }
 def fresh={String name -> def f=file('negative/'+name);f.mkdirs();f}
 reject('duplicate-slot'){project([left,configDir,configDir,right],fresh('duplicate-slot'),declared,expected)}
 reject('missing-slot'){project([left,right],fresh('missing-slot'),declared,expected)}
 def jar=file('duplicate.jar');new java.util.zip.ZipOutputStream(new FileOutputStream(jar)).withCloseable { z -> z.putNextEntry(new java.util.zip.ZipEntry(EightViewAgpResources.CONFIG));z.write(original);z.closeEntry() }
 reject('duplicate-jar'){project(cp+[jar],fresh('duplicate-jar'),declared,expected)}
 def extra=new File(configDir,'unexpected');extra.text='extra'
 reject('extra-config-content'){project(cp,fresh('extra-config-content'),declared,expected)};extra.delete()
 ['unknown-key':'unknown=value\n','duplicate-key':'android_custom_package=fixture.resources\n'].each {name,line ->
  config.bytes=(new String(original,'ISO-8859-1')+line).getBytes('ISO-8859-1');reject(name){project(cp,fresh(name),declared,expected)};config.bytes=original
 }
 ['traversal':'../outside','url':'file:/outside','absolute':'/outside'].each {name,value ->
  config.text=new String(original,'ISO-8859-1').replace('build/resources.ap_',value)
  reject(name){project(cp,fresh(name),declared,expected)};config.bytes=original
 }
 reject('task-value-mismatch'){project(cp,fresh('task-value-mismatch'),declared,expected+[android_custom_package:'wrong.package'])}
 def apk=declared.android_resource_apk;byte[] apkBytes=apk.bytes;apk.delete()
 reject('missing-apk'){project(cp,fresh('missing-apk'),declared,expected)};apk.bytes=apkBytes
 def foreign=file('foreign.ap_');foreign.bytes=apkBytes;apk.delete();java.nio.file.Files.createSymbolicLink(apk.toPath(),foreign.toPath())
 reject('symlink-apk'){project(cp,fresh('symlink-apk'),declared,expected)};java.nio.file.Files.delete(apk.toPath());apk.bytes=apkBytes
 reject('foreign-declared'){project(cp,fresh('foreign-declared'),declared+[android_resource_apk:foreign],expected)}
 def manifest=declared.android_merged_manifest;byte[] manifestBytes=manifest.bytes;manifest.delete()
 reject('missing-manifest'){project(cp,fresh('missing-manifest'),declared,expected)};manifest.bytes=manifestBytes
 def assets=declared.android_merged_assets;def savedAssets=file('saved-assets');assert assets.renameTo(savedAssets)
 reject('missing-assets'){project(cp,fresh('missing-assets'),declared,expected)};assert savedAssets.renameTo(assets)
 def link=new File(assets,'outside-link');java.nio.file.Files.createSymbolicLink(link.toPath(),foreign.toPath())
 reject('assets-symlink'){project(cp,fresh('assets-symlink'),declared,expected)};java.nio.file.Files.delete(link.toPath())
 def reordered=project([configDir,left,right],fresh('reordered'),declared,expected)
 assert reordered.ledger.index==0 && reordered.ledger!=result.ledger && reordered.cp[1]==left && reordered.cp[2]==right

 def projected=new File(work,'agp-test-config/'+EightViewAgpResources.CONFIG);byte[] projectedBytes=projected.bytes;projected.text='forged'
 reject('changed-derived'){project(cp,work,declared,expected)};projected.bytes=projectedBytes
 // Re-observation binds changed resources to changed identity, never silently preserves old proof.
 apk.bytes=(apkBytes.toList()+[0]) as byte[];def changed=project(cp,fresh('changed-apk'),declared,expected)
 assert changed.ledger.resources!=result.ledger.resources;apk.bytes=apkBytes
 assert project(cp,work,declared,expected)==result
 def empty=new File(assets,'empty-added');empty.mkdir();def topology=project(cp,fresh('topology'),declared,expected)
 assert topology.ledger.resources[1].files==result.ledger.resources[1].files
 assert topology.ledger.resources[1].directories!=result.ledger.resources[1].directories
 assert empty.delete();assert project(cp,work,declared,expected)==result
 def canonicalMethod=EightViewAgpResources.getDeclaredMethod('canonical',File,Boolean.TYPE)
 boolean swapped=false
 try {
  EightViewAgpResources.metaClass.static.canonical={ File value,boolean directory ->
   canonicalMethod.invoke(null,value,directory)
   if(value==apk && !swapped){swapped=true;assert apk.delete();java.nio.file.Files.createSymbolicLink(apk.toPath(),foreign.toPath())}
  }
  reject('nofollow-substitution'){EightViewAgpResources.fileIdentity(apk)}
  assert swapped
 } finally {GroovySystem.metaClassRegistry.removeMetaClass(EightViewAgpResources);java.nio.file.Files.deleteIfExists(apk.toPath());apk.bytes=apkBytes}
 def identityMethod=EightViewAgpResources.getDeclaredMethod('fileIdentity',File,Long.TYPE)
 boolean grown=false
 try {
  EightViewAgpResources.metaClass.static.fileIdentity={ File value,long maximum ->
   def record=identityMethod.invoke(null,value,maximum)
   if(value==config && !grown){grown=true;config.append('x'*65537)}
   record
  }
  reject('bounded-config-growth'){EightViewAgpResources.values(config)}
  assert grown
 } finally {GroovySystem.metaClassRegistry.removeMetaClass(EightViewAgpResources);config.bytes=original}
 assert project(cp,work,declared,expected)==result
 file('refusals.json').text=groovy.json.JsonOutput.toJson(refused)
} }
'''

def test_actual_agp_serializer_and_robolectric_paths_through_real_gradle(tmp_path):
 tools=Path(os.environ.get('RF_AGP_RESOURCE_TOOLS','/missing'))
 gradle=Path(os.environ.get('RF_GRADLE_HOME','/missing'))
 jdk=Path(os.environ.get('RF_PHASE_JAVA_HOME',os.environ.get('JAVA_HOME','/missing')))
 if not all((tools/n).is_file() for n in JARS) or not (gradle/'bin/gradle').is_file() or not (jdk/'bin/javac').is_file():
  if os.environ.get('RF_GRADLE_REQUIRE_RUNTIME')=='1':pytest.fail('Required official AGP/Robolectric/Gradle/JDK fixture tools unavailable')
  pytest.skip('Official fixture tools unavailable; no runtime qualification')
 for name,digest in JARS.items():assert hashlib.sha256((tools/name).read_bytes()).hexdigest()==digest
 env=dict(os.environ,JAVA_HOME=str(jdk));env['PATH']=str(jdk/'bin')+os.pathsep+env['PATH']
 def run(argv,**kwargs):return subprocess.run([str(x) for x in argv],env=env,capture_output=True,text=True,timeout=120,**kwargs)
 version=run([gradle/'bin/gradle','-g',tmp_path/'gradle-home','--version']);assert version.returncode==0 and 'Gradle 8.13\n' in version.stdout
 source=tmp_path/'RealAgpResources.java';source.write_text(JAVA)
 cp=os.pathsep.join([str(tools/name) for name in JARS]+[str(gradle/'lib/*'),str(gradle/'lib/plugins/*'),str(tmp_path)])
 compiled=run([jdk/'bin/javac','-cp',cp,source]);assert compiled.returncode==0,compiled.stderr
 build=tmp_path/'app/build';config=build/'intermediates/unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out';build.mkdir(parents=True)
 (build/'assets').mkdir();(build/'assets/fixture.txt').write_text('asset bytes');(build/'AndroidManifest.xml').write_text('<manifest package="fixture.resources"/>')
 with zipfile.ZipFile(build/'resources.ap_','w') as archive:archive.writestr('fixture.txt','not an Android resource parser fixture')
 generated=run([jdk/'bin/java','-cp',cp,'RealAgpResources','generate',config]);assert generated.returncode==0,generated.stderr
 raw=(config/CONFIG).read_bytes();assert b'#Generated by the Android Gradle plugin' in raw
 isolated=tmp_path/'runtime-cwd';isolated.mkdir()
 baseline=run([jdk/'bin/java','-cp',cp,'RealAgpResources','read',config],cwd=isolated)
 assert baseline.returncode!=0 and 'NoSuchFileException' in baseline.stderr
 probe=(ROOT/'tools/eight_view_host/probe.init.gradle').read_text();helpers=probe[:probe.index('allprojects { afterEvaluate')]
 (tmp_path/'build.gradle').write_text(helpers+'\n'+GROOVY);(tmp_path/'settings.gradle').write_text("rootProject.name='actual-agp-projection-fixture'\n")
 checked=run([gradle/'bin/gradle','--no-daemon','--offline','--console=plain','-g',tmp_path/'gradle-home','verifyProjection'],cwd=tmp_path)
 assert checked.returncode==0,checked.stdout+checked.stderr
 final=run([jdk/'bin/java','-cp',cp,'RealAgpResources','read',tmp_path/'projection/agp-test-config'],cwd=isolated)
 assert final.returncode==0,final.stderr
 assert 'ACTUAL_AGP_SERIALIZER_AND_ROBOLECTRIC_FS_OK_NOT_ANDROID_PARSE' in final.stdout
 assert list(isolated.iterdir())==[] and (config/CONFIG).read_bytes()==raw
 ledger=json.loads((tmp_path/'projection.json').read_text());assert ledger['index']==1 and len(ledger['resources'])==3
 import sys,types
 sys.path.insert(0,str(ROOT))
 from tools.eight_view_host import controller
 effective=[{'kind':'directory','path':str(tmp_path/'left'),'files':[]},
            {'kind':'directory','path':ledger['derivedDirectory'],'files':[{'path':ledger['derived']['path'],'sha256':ledger['derived']['sha256']}]},
            {'kind':'directory','path':str(tmp_path/'right'),'files':[]}]
 record={'schema':4,'appBuildDirectory':str(build),'agpResources':ledger,'classpath':effective}
 authority=types.SimpleNamespace(root=tmp_path,work=tmp_path/'projection',remaining=lambda maximum,**kwargs:maximum)
 controller.agp_resources_check(record,authority)
 original_apk=(build/'resources.ap_').read_bytes();(build/'resources.ap_').write_bytes(bytes([original_apk[0]^1])+original_apk[1:])
 with pytest.raises(controller.Failure,match='bytes/inventory changed'):controller.agp_resources_check(record,authority)
 (build/'resources.ap_').write_bytes(original_apk);controller.agp_resources_check(record,authority)

 assert len(json.loads((tmp_path/'refusals.json').read_text()))==19
 evidence=os.environ.get('RF_AGP_RESOURCE_EVIDENCE_DIR')
 if evidence:
  dest=Path(evidence);dest.mkdir(parents=True,exist_ok=True)
  for name,data in [('gradle.log',checked.stdout+checked.stderr),('baseline.log',baseline.stderr),('consumer.log',final.stdout),('projection.json',json.dumps(ledger,indent=2))]:(dest/name).write_text(data)


def test_actual_37fd_failure_replay_has_no_raw_config_bytes():
 """Retained XML establishes failed relative resolution only, not resource existence/content."""
 import xml.etree.ElementTree as ET
 name=os.environ.get('RF_AGP_REPLAY_ZIP')
 if not name:
  if os.environ.get('RF_REQUIRE_NATIVE_REPLAY')=='1':pytest.fail('Required exact37fd resource failure artifact unavailable')
  pytest.skip('Actual resource failure archive unavailable; no replay claim')
 archive=Path(name);assert archive.stat().st_size<=32*1024**2
 assert hashlib.sha256(archive.read_bytes()).hexdigest()=='892dd9bfab32abd081c8701eeb85d495a531907d552988737a3920aa5b4d56de'
 with zipfile.ZipFile(archive) as z:
  path='xml/TEST-com.realityforge.scanner.hostintegration.EightViewSparsePrefixHostTest.xml'
  assert z.getinfo(path).file_size<=8*1024**2
  xml=z.read(path).decode();root=ET.fromstring(xml)
  assert root.attrib['tests']=='1' and root.attrib['failures']=='1' and root.attrib['skipped']=='0'
  assert 'runtime-cwd/build/intermediates/apk_for_local_test/debugUnitTest/packageDebugUnitTestForUnitTest/apk-for-local-test.ap_' in xml
  assert 'No manifest file found at build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml' in xml
  assert not any(n.endswith(CONFIG) for n in z.namelist())
  prepared=json.loads(z.read('prepared.json'))
  config=[f for e in prepared['classpath'] for f in e.get('files',[]) if f['path'].endswith(CONFIG)]
  assert len(config)==1 and config[0]['sha256']=='66d508cea544acb8e8bbb5e6db896a62cc584bc1b9cdd55fdc95c8133cbf43e5'
