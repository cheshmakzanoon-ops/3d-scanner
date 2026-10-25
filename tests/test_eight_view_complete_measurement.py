"""Synthetic complete-proof binding controls; no reconstruction/device qualification."""
import copy,hashlib,importlib.util,json,struct
from pathlib import Path
import pytest
from tools.eight_view_host import experiments as e,policy as p
from tools.eight_view_host.runtime import Failure


def encode(value):return json.dumps(value,separators=(',',':'),sort_keys=True).encode()
def sha(raw):return hashlib.sha256(raw).hexdigest()
def formats():
    path=Path(__file__).with_name('test_eight_view_complete_formats.py')
    spec=importlib.util.spec_from_file_location('complete_format_test_builders',path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def mesh_ply():
    lines=['ply','format ascii 1.0','comment RealityForge observed open surface','element vertex 36']
    lines+=['property float '+key for key in ('x','y','z','nx','ny','nz')]
    lines+=['property uchar '+key for key in ('red','green','blue')]
    lines+=['element face 50','property list uchar int vertex_indices','end_header']
    for y in range(6):
        for x in range(6):lines.append(f'{x} {y} 0 0 0 1 128 128 128')
    for y in range(5):
        for x in range(5):
            i=y*6+x;lines += [f'3 {i} {i+1} {i+6}',f'3 {i+1} {i+7} {i+6}']
    return ('\n'.join(lines)+'\n').encode()

def publication_digest(publication):
    def string(v):raw=v.encode();return struct.pack('>i',len(raw))+raw
    value=struct.pack('>ii',1,0)+string(publication['projectId'])+struct.pack('>q',publication['projectRevision'])+string(publication['operationId'])+struct.pack('>q',publication['attempt'])
    for key in ('inputIdentitySha256','settingsIdentitySha256','engineVersion'):value+=string(publication[key])
    value+=struct.pack('>qi',publication['createdAtEpochMillis'],len(publication['entries']))
    for row in publication['entries']:
        for key in ('logicalName','relativePath','representation'):value+=string(row[key])
        value+=bytes([row['required']])+struct.pack('>qiq',row['maxBytes'],0,row['bytes'])+b'\1'+string(row['sha256'])
    return sha(value)

def fixture():
    f=formats();glbdoc,binary=f.fixture();glb=f.glb(glbdoc,binary)
    settings=encode({'schema':2,'focal35':32.88,'resourceSelection':{'schema':1,'tier':'standard',
        'identitySha256':sha(b'resource-selection-v1|standard|depth=384|texture=768|views=8|samples=100000|native-threads=1')}})
    identity=[{'photoId':str(i),'fixturePath':f'images/view-{i}.jpg','imageSha256':sha(str(i).encode())} for i in range(3)]
    observations={'images':{row['fixturePath']:row['imageSha256'] for row in identity}}
    preview={'artifactKey':'synthetic-result','revision':3,'denseStatus':'ready','meshStatus':'ready','textureStatus':'ready',
      'finalSparseOutcome':'global_typed_ba_admitted12','bundleAdjustment':{},'pointCount':3,'registeredCameras':3,
      'cameras':[{'photoId':str(i)} for i in range(3)],'densePreview':{'pointCount':60,'consistentPixels':60},
      'meshPreview':{'vertexCount':36,'triangleCount':50},
      'texturePreview':{'uvVertexCount':3,'triangleCount':1,'atlasWidth':1,'atlasHeight':1,'faceCoverage':.5}}
    # Unexported roles are retained and hash-bound; the three export encodings use real parser fixtures.
    payloads={'preview':encode(preview),'sparse-ply':f.ply(),'mesh-ply':mesh_ply(),'textured-glb':glb,'texture-png':f.png(),
      'sparse-glb':b'synthetic sparse publication bytes','dense-glb':b'synthetic dense publication bytes',
      'dense-ply':b'synthetic dense point publication bytes','mesh-glb':b'synthetic mesh publication bytes'}
    hashes={k:sha(v) for k,v in payloads.items()}
    job={'projectId':'synthetic-project','projectRevision':3,'operationId':'synthetic-operation','attempt':1,
      'inputIdentitySha256':'1'*64,'settingsIdentitySha256':sha(settings),'engineVersion':'m19-integrated-runner-v2'}
    publication={'schema':1,'setId':'','createdAtEpochMillis':123,'purpose':'RESULT_SET',**job,
      'entries':[{'logicalName':name,'relativePath':path,'representation':representation,'required':True,'maxBytes':len(payloads[name]),
      'presence':'PRESENT','bytes':len(payloads[name]),'sha256':hashes[name]} for name,path,representation in p.COMPLETE_ARTIFACTS]}
    publication['setId']=publication_digest(publication)
    result={'schema':1,'classification':e.get_profile(e.COMPLETE).classification,'synthetic':True,'modeledResources':True,
      'jobId':'synthetic-job','originalsPreserved':True,'exportHashes':hashes,'state':'COMPLETED','acceptedStage':'PUBLISHING',**job}
    exports={extension:{'path':'artifact-'+hashes[name]+'.bin','sha256':hashes[name],'bytes':len(payloads[name]),'publishedLogicalName':name}
      for extension,name in [('ply','sparse-ply'),('mesh.ply','mesh-ply'),('textured.glb','textured-glb')]}
    model={'classification':'MODELED_HOST_RESOURCES','availableSystemBytes':2*1024**3,'thermalStatus':0,'batteryPercent':80,
      'charging':True,'powerSave':False,'statFsBlocks':2000000,'statFsAvailableBlocks':1500000,
      'managedHeap':'actual JVM runtime; not modeled phone heap','storageReservation':'production FileSpaceProbe and StorageBudgetService unchanged'}
    docs={'complete-result.json':encode(result),'job-settings.json':settings,'modeled-resources.json':encode(model)}
    for phase in ('before-reopen','after-reopen'):
        for kind,value in [('preview',preview),('publication',publication),('artifacts',hashes),('exports',exports)]:docs[phase+'-'+kind+'.json']=encode(value)
    return docs,{'artifact-'+hashes[k]+'.bin':v for k,v in payloads.items()},identity,observations


def edit(docs,name,mutator):
    value=json.loads(docs[name]);mutator(value);docs[name]=encode(value)


def test_complete_raw_publication_export_proof():
    result=p.complete_measurement_proof(*fixture())
    assert result['registeredCameraCount']==3 and result['artifactCount']==9 and result['exportCount']==3
    assert result['measurements']['meshTriangleCount']==50
    assert result['classification']=='SYNTHETIC_HOST_FULL_CONTROLLER_EXPORT_NOT_DEVICE_QUALITY'
    assert len(encode(result))<8192


def test_complete_profile_activation_does_not_weaken_prefix():
    assert e.ACTIVE==e.REAL_EIGHT_ORBIT
    assert e.get_profile(e.COMPLETE).test_source=='ReliefThreeCompleteControllerHostTest.kt'
    assert e.get_profile(e.COMPLETE).selector.endswith('.fixedThreePhotosCompletePublishReopenAndExportOrFail')
    with pytest.raises(Failure):p.measurement_proof({},[],[],{},profile=e.COMPLETE)
    for profile in (e.LEGACY,e.RELIEF,'unknown',None,{}):
        with pytest.raises(Failure):p.complete_measurement_proof(*fixture(),profile=profile)

@pytest.mark.parametrize('name',p.COMPLETE_DOCUMENTS)
def test_missing_document_refused(name):
    docs,artifacts,identity,observations=fixture();del docs[name]
    with pytest.raises(Failure):p.complete_measurement_proof(docs,artifacts,identity,observations)

@pytest.mark.parametrize('mode',['extra-doc','extra-artifact','missing-artifact','changed-artifact','truncated-artifact','settings','duplicate-json','nonfinite-json','trailing-json','byte-cap','wrong-identity','wrong-observations'])
def test_raw_evidence_tampering_refused(mode):
    docs,artifacts,identity,observations=fixture()
    if mode=='extra-doc':docs['extra.json']=b'{}'
    elif mode=='extra-artifact':artifacts['artifact-'+('0'*64)+'.bin']=b'foreign'
    elif mode=='missing-artifact':artifacts.pop(next(iter(artifacts)))
    elif mode=='changed-artifact':key=next(iter(artifacts));artifacts[key]+=b'x'
    elif mode=='truncated-artifact':key=next(iter(artifacts));artifacts[key]=b''
    elif mode=='settings':docs['job-settings.json']=b'{}'
    elif mode=='duplicate-json':docs['complete-result.json']=b'{"schema":1,"schema":1}'
    elif mode=='nonfinite-json':docs['complete-result.json']=b'{"x":NaN}'
    elif mode=='trailing-json':docs['complete-result.json']+=b'{}'
    elif mode=='byte-cap':docs['complete-result.json']=b'x'*(16*1024**2+1)
    elif mode=='wrong-identity':identity[0]['imageSha256']='0'*64
    elif mode=='wrong-observations':observations['images']['foreign']='0'*64
    with pytest.raises(Failure):p.complete_measurement_proof(docs,artifacts,identity,observations)

@pytest.mark.parametrize('name,key,value',[
    ('complete-result.json','schema',True),('complete-result.json','state','FAILED'),
    ('complete-result.json','acceptedStage','DENSE'),('complete-result.json','projectRevision',True),
    ('complete-result.json','projectId','foreign'),('complete-result.json','originalsPreserved',False),
    ('complete-result.json','classification','PASS'),('complete-result.json','synthetic',1),
    ('complete-result.json','modeledResources',False),('complete-result.json','attempt',False),
    ('complete-result.json','engineVersion','foreign'),('modeled-resources.json','charging',1),
    ('before-reopen-publication.json','setId','0'*64),('after-reopen-publication.json','projectId','foreign')])
def test_document_semantic_tampering_refused(name,key,value):
    docs,artifacts,identity,observations=fixture();edit(docs,name,lambda row:row.__setitem__(key,value))
    with pytest.raises(Failure):p.complete_measurement_proof(docs,artifacts,identity,observations)

@pytest.mark.parametrize('mode',['entry-order','optional','absent','path','bytes','hash','set-id'])
def test_rehashed_publication_contract_cannot_weaken(mode):
    docs,artifacts,identity,observations=fixture();publication=json.loads(docs['before-reopen-publication.json'])
    row=publication['entries'][0]
    if mode=='entry-order':publication['entries'].reverse()
    elif mode=='optional':row['required']=False
    elif mode=='absent':row['presence']='ABSENT'
    elif mode=='path':row['relativePath']='../preview.json'
    elif mode=='bytes':row['bytes']+=1
    elif mode=='hash':row['sha256']='0'*64
    publication['setId']=publication_digest(publication) if mode!='set-id' else '0'*64
    for phase in ('before-reopen','after-reopen'):docs[phase+'-publication.json']=encode(publication)
    with pytest.raises(Failure):p.complete_measurement_proof(docs,artifacts,identity,observations)


def repack(docs,artifacts,payloads):
    """Adversary recomputes every enclosing hash; semantic checks must still hold."""
    hashes={k:sha(v) for k,v in payloads.items()}
    publication=json.loads(docs['before-reopen-publication.json'])
    for row in publication['entries']:
        name=row['logicalName'];row.update(bytes=len(payloads[name]),maxBytes=len(payloads[name]),sha256=hashes[name])
    publication['setId']=publication_digest(publication)
    exports={extension:{'path':'artifact-'+hashes[name]+'.bin','sha256':hashes[name],'bytes':len(payloads[name]),'publishedLogicalName':name}
      for extension,name in [('ply','sparse-ply'),('mesh.ply','mesh-ply'),('textured.glb','textured-glb')]}
    result=json.loads(docs['complete-result.json']);result['exportHashes']=hashes;docs['complete-result.json']=encode(result)
    for phase in ('before-reopen','after-reopen'):
        docs[phase+'-preview.json']=payloads['preview'];docs[phase+'-artifacts.json']=encode(hashes)
        docs[phase+'-publication.json']=encode(publication);docs[phase+'-exports.json']=encode(exports)
    artifacts.clear();artifacts.update({'artifact-'+hashes[k]+'.bin':v for k,v in payloads.items()})

@pytest.mark.parametrize('mode',['dense-fallback','mesh-fallback','texture-fallback','typed-ba','camera-identity','camera-duplicate','sparse-count','dense-count','consistent-count','mesh-vertices','mesh-triangles','texture-triangles','texture-vertices','texture-width','coverage','atlas-content','sparse-format','mesh-format','glb-format'])
def test_fully_rehashed_semantic_tampering_refused(mode):
    docs,artifacts,identity,observations=fixture();hashes=json.loads(docs['before-reopen-artifacts.json'])
    payloads={name:artifacts['artifact-'+digest+'.bin'] for name,digest in hashes.items()};preview=json.loads(payloads['preview'])
    if mode.endswith('-fallback'):preview[mode.split('-')[0]+'Status']='unavailable'
    elif mode=='typed-ba':preview['finalSparseOutcome']='fallback'
    elif mode=='camera-identity':preview['cameras'][0]['photoId']='foreign'
    elif mode=='camera-duplicate':preview['cameras'][1]=preview['cameras'][0]
    elif mode=='sparse-count':preview['pointCount']=4
    elif mode=='dense-count':preview['densePreview']['pointCount']=59
    elif mode=='consistent-count':preview['densePreview']['consistentPixels']=59
    elif mode=='mesh-vertices':preview['meshPreview']['vertexCount']=37
    elif mode=='mesh-triangles':preview['meshPreview']['triangleCount']=51
    elif mode=='texture-triangles':preview['texturePreview']['triangleCount']=2
    elif mode=='texture-vertices':preview['texturePreview']['uvVertexCount']=4
    elif mode=='texture-width':preview['texturePreview']['atlasWidth']=2
    elif mode=='coverage':preview['texturePreview']['faceCoverage']=0
    elif mode=='atlas-content':payloads['texture-png']=b'not same embedded PNG'
    elif mode=='sparse-format':payloads['sparse-ply']=b'not PLY'
    elif mode=='mesh-format':payloads['mesh-ply']=b'not PLY'
    elif mode=='glb-format':payloads['textured-glb']=b'not GLB'
    payloads['preview']=encode(preview);repack(docs,artifacts,payloads)
    with pytest.raises(Failure):p.complete_measurement_proof(docs,artifacts,identity,observations)


def test_format_scope_is_explicit():
    assert p.complete_measurement_proof(*fixture())['formatValidationScope']=='THREE_EXPORTED_FORMATS; NINE_PUBLISHED_ARTIFACTS_HASH_BOUND'


def change_settings(docs,settings):
    docs['job-settings.json']=encode(settings);digest=sha(docs['job-settings.json'])
    edit(docs,'complete-result.json',lambda value:value.__setitem__('settingsIdentitySha256',digest))
    publication=json.loads(docs['before-reopen-publication.json']);publication['settingsIdentitySha256']=digest
    publication['setId']=publication_digest(publication)
    for phase in ('before-reopen','after-reopen'):docs[phase+'-publication.json']=encode(publication)


def test_both_genuine_resource_tiers_admitted_without_forcing():
    docs,artifacts,identity,observations=fixture()
    settings={'schema':2,'focal35':32.88,'resourceSelection':{'schema':1,'tier':'compact',
      'identitySha256':sha(b'resource-selection-v1|compact|depth=256|texture=512|views=6|samples=50000|native-threads=1')}}
    change_settings(docs,settings)
    assert p.complete_measurement_proof(docs,artifacts,identity,observations)['state']=='COMPLETED'

@pytest.mark.parametrize('mode',['schema','focal','tier','selection-hash','selection-schema','extra'])
def test_rehashed_bad_job_settings_refused(mode):
    docs,artifacts,identity,observations=fixture();settings=json.loads(docs['job-settings.json'])
    if mode=='schema':settings['schema']=True
    elif mode=='focal':settings['focal35']=50
    elif mode=='tier':settings['resourceSelection']['tier']='unbounded'
    elif mode=='selection-hash':settings['resourceSelection']['identitySha256']='0'*64
    elif mode=='selection-schema':settings['resourceSelection']['schema']=True
    elif mode=='extra':settings['override']=True
    change_settings(docs,settings)
    with pytest.raises(Failure):p.complete_measurement_proof(docs,artifacts,identity,observations)


def test_coordinated_unknown_engine_version_is_not_a_reviewed_producer():
    docs,artifacts,identity,observations=fixture()
    result=json.loads(docs['complete-result.json']);result['engineVersion']='unreviewed-engine'
    docs['complete-result.json']=encode(result)
    for phase in ('before-reopen','after-reopen'):
        publication=json.loads(docs[phase+'-publication.json'])
        publication['engineVersion']='unreviewed-engine';publication['setId']=publication_digest(publication)
        docs[phase+'-publication.json']=encode(publication)
    with pytest.raises(Failure,match='engine version'):p.complete_measurement_proof(docs,artifacts,identity,observations)
