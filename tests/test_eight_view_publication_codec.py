"""Actual production Kotlin publication codec interoperability, synthetic artifact set."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import pytest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from tools.eight_view_host import policy
from tools.eight_view_host.runtime import Failure


def test_actual_kotlin_publication_codec(tmp_path):
    lib=Path(os.environ.get('RF_KOTLIN_TEST_LIB','/workspace/shared/export-stream-qa/lib'))
    java=Path(os.environ.get('RF_PHASE_JAVA_HOME','/workspace/shared/phase-jdk-tools/jdk-17.0.21+9'))/'bin/java'
    compiler=sorted(lib.glob('*.jar'))
    if not java.is_file() or not (lib/'kotlin-compiler-embeddable-2.1.21.jar').is_file():
        if os.environ.get('RF_REQUIRE_PUBLICATION_CODEC')=='1':pytest.fail('Required Kotlin/JDK fixture tools missing')
        pytest.skip('Actual Kotlin/JDK tools unavailable')
    metadata=ET.parse(ROOT/'gradle/verification-metadata.xml').getroot()
    pins={a.get('name'):h.get('value') for c in metadata.iter() if c.tag.endswith('component') for a in c if a.tag.endswith('artifact') for h in a if h.tag.endswith('sha256')}
    for jar in compiler:assert hashlib.sha256(jar.read_bytes()).hexdigest()==pins[jar.name]
    path=Path(__file__).with_name('test_eight_view_complete_measurement.py')
    spec=importlib.util.spec_from_file_location('publication_complete_fixture',path)
    fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
    docs,artifacts,identity,observations=fixture.fixture()
    publication=json.loads(docs['before-reopen-publication.json'])
    q=lambda value:json.dumps(value,ensure_ascii=True)
    entries=[]
    for row in publication['entries']:
        entries.append('ArtifactManifestEntry(ArtifactDeclaration('+','.join(q(row[k]) for k in ('logicalName','relativePath','representation'))+',true,'+str(row['maxBytes'])+'L),ArtifactPresence.PRESENT,'+str(row['bytes'])+'L,'+q(row['sha256'])+')')
    harness=tmp_path/'PublicationInterop.kt'
    harness.write_text('''package com.realityforge.scanner.data.publication
import java.util.Base64
import java.security.MessageDigest
import java.nio.ByteBuffer
fun main(){
 val identity=PublicationIdentity(PublicationPurpose.RESULT_SET,'''+q(publication['projectId'])+','+str(publication['projectRevision'])+'L,'+q(publication['operationId'])+','+str(publication['attempt'])+'L,'+','.join(q(publication[k]) for k in ('inputIdentitySha256','settingsIdentitySha256','engineVersion'))+''')
 val entries=listOf('''+','.join(entries)+''')
 val manifest=PublicationCodec.createManifest(identity,'''+str(publication['createdAtEpochMillis'])+'''L,entries)
 val bytes=PublicationCodec.encodeManifest(manifest)
 check(PublicationCodec.decodeManifest(bytes)==manifest)
 var refused=0
 fun reject(block:()->Unit){var failed=false;try{block()}catch(e:IllegalArgumentException){failed=true}catch(e:IllegalStateException){failed=true};check(failed);refused++}
 reject{PublicationCodec.encodeManifest(manifest.copy(setId="0".repeat(64)))}
 reject{PublicationCodec.decodeManifest(bytes.copyOf().also{it[20]=(it[20].toInt() xor 1).toByte()})}
 reject{PublicationCodec.decodeManifest(bytes.copyOf().also{ByteBuffer.wrap(it).putInt(4,2)})}
 reject{PublicationCodec.decodeManifest(bytes+byteArrayOf(0))}
 val badSchema=bytes.copyOf();ByteBuffer.wrap(badSchema).putInt(12,2)
 val payloadSize=ByteBuffer.wrap(badSchema).getInt(8)
 MessageDigest.getInstance("SHA-256").digest(badSchema.copyOfRange(12,12+payloadSize)).copyInto(badSchema,12+payloadSize)
 reject{PublicationCodec.decodeManifest(badSchema)}
 check(PublicationCodec.createManifest(identity,manifest.createdAtEpochMillis,entries.reversed()).setId!=manifest.setId)
 println(manifest.setId);println(Base64.getEncoder().encodeToString(bytes));println(refused)
}
''')
    folder=ROOT/'app/src/main/java/com/realityforge/scanner/data/publication'
    sources=[folder/'PublicationModels.kt',folder/'PublicationCodec.kt']
    stdlib=lib/'kotlin-stdlib-2.1.21.jar';classes=tmp_path/'classes'
    cmd=[str(java),'-Xmx768m','-XX:ActiveProcessorCount=2','-cp',os.pathsep.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',str(stdlib),'-d',str(classes),*map(str,sources),str(harness)]
    result=subprocess.run(cmd,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90)
    assert result.returncode==0,result.stdout.decode(errors='replace')[-6000:]
    run=subprocess.run([str(java),'-cp',os.pathsep.join(map(str,[classes,stdlib])),'com.realityforge.scanner.data.publication.PublicationInteropKt'],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30)
    assert run.returncode==0,run.stdout.decode(errors='replace')[-6000:]
    set_id,encoded,refusals=run.stdout.decode().strip().splitlines()
    assert int(refusals)==5
    envelope=base64.b64decode(encoded,validate=True)
    magic,version,size=struct.unpack_from('>III',envelope)
    assert (magic,version)==(0x5246504d,1) and size==len(envelope)-44
    payload=envelope[12:12+size]
    assert hashlib.sha256(payload).hexdigest()==set_id==fixture.publication_digest(publication)
    assert hashlib.sha256(payload).digest()==envelope[-32:]
    publication['setId']=set_id
    for phase in ('before-reopen','after-reopen'):docs[phase+'-publication.json']=fixture.encode(publication)
    accepted=policy.complete_measurement_proof(docs,artifacts,identity,observations)
    assert accepted['publicationSetId']==set_id
    publication['setId']='0'*64
    for phase in ('before-reopen','after-reopen'):docs[phase+'-publication.json']=fixture.encode(publication)
    with pytest.raises(Failure):policy.complete_measurement_proof(docs,artifacts,identity,observations)
    output=os.environ.get('RF_PUBLICATION_CODEC_EVIDENCE_DIR')
    if output:
        out=Path(output);out.mkdir(parents=True,exist_ok=True)
        (out/'manifest.bin').write_bytes(envelope)
        (out/'receipt.json').write_text(json.dumps({'scope':'ACTUAL_PRODUCTION_KOTLIN_CODEC_SYNTHETIC_COMPLETE_ARTIFACT_SET_NOT_RECONSTRUCTION','setId':set_id,'manifestBytes':len(envelope),'kotlinRefusals':5,'pythonMutationRefused':True,'sources':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}},sort_keys=True,indent=2)+'\n')
