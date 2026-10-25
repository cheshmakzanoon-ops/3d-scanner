"""Actual offline Gradle/JUnit selection controls; no AGP/native pipeline qualification."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import pytest
ROOT=Path(__file__).resolve().parents[1]
PROFILES={
    'real-licensed-stereo-complete':('RealLicensedStereoCompleteControllerHostTest','fixedTwoLicensedPhotosCompletePublishReopenAndExportOrFail'),
    'eight-cuboid':('EightViewSparsePrefixHostTest','fixedEightPhotosPersistSparseOrRecordUnmodifiedProductionFailure'),
    'relief-three':('ReliefThreeSparsePrefixHostTest','fixedThreePhotosPersistSparseOrRecordUnmodifiedProductionFailure'),
    'relief-three-complete':('ReliefThreeCompleteControllerHostTest','fixedThreePhotosCompletePublishReopenAndExportOrFail'),
}
PACKAGE='com.realityforge.scanner.hostintegration.'


def test_real_gradle_junit_selects_one_closed_profile_only(tmp_path):
 home=Path(os.environ.get('RF_GRADLE_HOME','/missing'))
 java_home=Path(os.environ.get('RF_PHASE_JAVA_HOME',os.environ.get('JAVA_HOME','/missing')))
 jars=Path(os.environ.get('RF_KOTLIN_TEST_LIB','/workspace/shared/export-stream-qa/lib'))
 runtime=[jars/'junit-4.13.2.jar',jars/'hamcrest-core-1.3.jar']
 if not (home/'bin/gradle').is_file() or not (java_home/'bin/javac').is_file() or not all(p.is_file() for p in runtime):
  if os.environ.get('RF_GRADLE_REQUIRE_RUNTIME')=='1':pytest.fail('Required Gradle8.13/JDK17/pinnedJUnit fixtures unavailable')
  pytest.skip('Actual selection tools unavailable; no execution qualification')
 metadata=ET.parse(ROOT/'gradle/verification-metadata.xml').getroot()
 pins={a.get('name'):h.get('value') for c in metadata.iter() if c.tag.endswith('component') for a in c if a.tag.endswith('artifact') for h in a if h.tag.endswith('sha256')}
 for jar in runtime:assert hashlib.sha256(jar.read_bytes()).hexdigest()==pins[jar.name]
 env=dict(os.environ,JAVA_HOME=str(java_home));env['PATH']=str(java_home/'bin')+os.pathsep+env['PATH']
 def run(args):return subprocess.run([str(home/'bin/gradle'),'--offline','--no-daemon','--console=plain','--max-workers=1','-g',str(tmp_path/'gradle-home'),*args],cwd=tmp_path,env=env,text=True,capture_output=True,timeout=120)
 version=run(['--version']);assert version.returncode==0 and 'Gradle 8.13\n' in version.stdout
 source=(ROOT/'tools/eight_view_host/probe.init.gradle').read_text()
 start=source.index('class HostSparseExperiment {');end=source.index('// Additive task only.',start)
 helper=source[start:end]
 java_dir=tmp_path/'src/test/java/com/realityforge/scanner/hostintegration';java_dir.mkdir(parents=True)
 for profile,(name,method) in PROFILES.items():
  (java_dir/(name+'.java')).write_text('package '+PACKAGE[:-1]+';\npublic class '+name+' {\n'
    '@org.junit.Test public void '+method+'() throws Exception { java.nio.file.Files.writeString(java.nio.file.Paths.get(System.getProperty("fixture.marker")),"'+profile+'"); }\n'
    '@org.junit.Test public void unrelatedMethodMustNotRun(){ throw new AssertionError("method filter bypassed"); }\n}\n')
 (tmp_path/'settings.gradle').write_text("rootProject.name='closed-profile-selector-fixture'\n")
 (tmp_path/'build.gradle').write_text("plugins { id 'java' }\n"+helper+'''
repositories { }
dependencies { testImplementation files('''+','.join(json.dumps(str(p)) for p in runtime)+''') }
def cfg=[experiment:project.findProperty('fixtureProfile'),selector:project.findProperty('fixtureSelector')]
if(project.hasProperty('numericProfile'))cfg.experiment=3
def experiment=HostSparseExperiment.select(cfg)
tasks.register('selectedProbe',Test) {
 dependsOn tasks.named('testClasses')
 testClassesDirs=sourceSets.test.output.classesDirs
 classpath=sourceSets.test.runtimeClasspath
 include experiment.include
 filter { includeTestsMatching experiment.selector; failOnNoMatchingTests=true }
 useJUnit()
 systemProperty 'fixture.marker',project.property('fixtureMarker')
 outputs.upToDateWhen {false}
}
''')
 observations=[]
 for profile,(name,method) in PROFILES.items():
  marker=tmp_path/(profile+'.marker');selector=PACKAGE+name+'.'+method
  result=run(['selectedProbe','-PfixtureProfile='+profile,'-PfixtureSelector='+selector,'-PfixtureMarker='+str(marker)])
  assert result.returncode==0,result.stdout+result.stderr
  assert marker.read_text()==profile
  xml=list((tmp_path/'build/test-results/selectedProbe').glob('TEST-*.xml'))
  assert len(xml)==1
  suite=ET.parse(xml[0]).getroot();cases=suite.findall('testcase')
  assert suite.attrib['tests']=='1' and suite.attrib['failures']=='0' and suite.attrib['errors']=='0' and suite.attrib['skipped']=='0'
  assert len(cases)==1 and cases[0].attrib['name']==method and cases[0].attrib['classname']==PACKAGE+name
  observations.append({'profile':profile,'selector':selector,'tests':1,'status':'ONLY_SELECTED_SYNTHETIC_JUNIT_CLASS_AND_METHOD'})
 legacy=PACKAGE+'.'.join(PROFILES['eight-cuboid']);relief=PACKAGE+'.'.join(PROFILES['relief-three'])
 for label,profile,selector,extra in [('mixed-eight','eight-cuboid',relief,[]),('mixed-relief','relief-three',legacy,[]),('mixed-complete','relief-three-complete',relief,[]),('mixed-real','real-licensed-stereo-complete',relief,[]),('unknown','not-reviewed',relief,[]),('numeric','relief-three',relief,['-PnumericProfile=true'])]:
  marker=tmp_path/(label+'.marker')
  result=run(['selectedProbe','-PfixtureProfile='+profile,'-PfixtureSelector='+selector,'-PfixtureMarker='+str(marker),*extra])
  assert result.returncode!=0
  assert ('Mixed experiment selector' if label.startswith('mixed') else 'Unknown closed experiment profile') in result.stdout+result.stderr
  assert not marker.exists()
  observations.append({'profile':label,'status':'REFUSED_BEFORE_JUNIT'})
 evidence=os.environ.get('RF_PROFILE_SELECTION_EVIDENCE_DIR')
 if evidence:
  destination=Path(evidence);destination.mkdir(parents=True,exist_ok=True)
  (destination/'receipt.json').write_text(json.dumps({'scope':'ACTUAL_GRADLE_JUNIT_SELECTION_NOT_NATIVE_PIPELINE','helperSha256':hashlib.sha256(helper.encode()).hexdigest(),'observations':observations},indent=2)+'\n')
