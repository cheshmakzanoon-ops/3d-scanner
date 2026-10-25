"""Synthetic schema/refusal controls; never actual photographic replay evidence."""
import copy,hashlib,json,struct,math
from pathlib import Path
import pytest
from tools.eight_view_host import controller as c,experiments as e,pnp_replay as p
from tools.eight_view_host.runtime import Failure


def value():
 rows=[[(i-10)*.03,.1,5.,((i-10)*.03-.4)/5,.02] for i in range(20)]
 focal=88*math.hypot(1600,1067)/math.hypot(36,24)
 poses=[[10,1.,0.,0.,0.,1.,0.,0.,0.,1.,0.,0.,0.],[11,1.,0.,0.,0.,1.,0.,0.,0.,1.,-.2,0.,0.]]
 return dict(schema=1,classification=p.CLASSIFICATION,status='VERIFIED_REPLAY',originalHashes=p.ORIGINALS,
  cacheHashes={k:'a'*64 for k in ['feature10','feature11','feature12','matches','matchesChecksum','frontier']},
  matchingStage='b'*64,sparseStage='c'*64,candidateLabel=12,count=20,threshold=2/focal,
  numericFailure='[pnp1 raw=20 distinct=20 required=16 best=1 tested=450 valid=450 model=dlt planarity=0.064929]',
  rowsSha256=hashlib.sha256(b''.join(struct.pack('>d',x) for row in rows for x in row)).hexdigest(),
  poseRowsSha256=hashlib.sha256(b''.join(struct.pack('>d',x) for row in poses for x in row[1:])).hexdigest(),
  triangulationRowsSha256='e'*64,pointsSha256='f'*64,rows=rows,anchorPoses=poses,
  projections=[[i,1600,1067,focal,focal,799.5,533.,0.,0.,0.,0.,0.] for i in (10,11,12)],
  provenance=[dict(ambiguous=False,tracks=[dict(track=i,candidateKey=i,sources=[[10,i,row[0]/5,.02,i],[11,i,(row[0]-.2)/5,.02,i]])]) for i,row in enumerate(rows)])

def raw(v=None):return json.dumps(value() if v is None else v).encode()


def test_closed_replay_is_not_complete_measurement():
 assert p.validate(raw(),e.REAL_THREE)['count']==20
 c.real_evidence_privacy_check('measurement/pnp-replay.json',raw(),profile=e.REAL_THREE)
 assert 'pnp-replay.json' not in c.policy.COMPLETE_DOCUMENTS
 failure={'schema':1,'classification':p.CLASSIFICATION,'status':'DIAGNOSTIC_FAILURE','code':'READ_ONLY_REPLAY_UNAVAILABLE'}
 assert p.validate(raw(failure),e.REAL_THREE)==failure

@pytest.mark.parametrize('profile',[e.REAL_STEREO,e.COMPLETE,e.RELIEF,e.LEGACY,None])
def test_only_exact_real_three_profile(profile):
 with pytest.raises(Failure):p.validate(raw(),profile)

@pytest.mark.parametrize('field,new',[
 ('schema',True),('status','PASS'),('classification','ORIGINAL_CALL_CAPTURE'),('originalHashes',list(reversed(p.ORIGINALS))),
 ('candidateLabel',True),('candidateLabel',13),('count',20.),('count',129),('threshold',True),('threshold',float('nan')),
 ('threshold',0.),('rowsSha256','0'*64),('matchingStage','../private'),('rows',[[0]*5]*20),
 ('nativeText','secret'),('rawDescriptors',[]),('code','other'),('cacheHashes',{}),
 ('numericFailure','[pnp1 raw=21 distinct=20 required=16 best=1 tested=450 valid=450 model=dlt planarity=0.064929]'),
 ('numericFailure','[pnp1 raw=20 distinct=20 required=16 best=16 tested=450 valid=450 model=dlt planarity=0.064929]'),
 ('numericFailure','[pnp1 raw=20 distinct=20 required=16 best=1 tested=450 valid=451 model=dlt planarity=0.064929]'),
 ('numericFailure','[pnp1 raw=20 distinct=20 required=16 best=1 tested=450 valid=450 model=none planarity=0.064929]'),
])
def test_tampered_claims_refused(field,new):
 v=value();v[field]=new
 with pytest.raises(Failure):p.validate(raw(v),e.REAL_THREE)

@pytest.mark.parametrize('cell',[float('nan'),float('inf'),True,100001,'private'])
def test_invalid_numeric_rows_even_with_other_valid_fields(cell):
 v=value();v['rows'][0][0]=cell
 with pytest.raises(Failure):p.validate(raw(v),e.REAL_THREE)

@pytest.mark.parametrize('encoded',[b'',b'{}',b'[]',b'null',b'x'*65537,b'{"schema":1,"schema":1}'],ids=['empty','empty-object','array','null','oversized-bytes','duplicate-schema'])
def test_malformed_size_duplicates(encoded):
 with pytest.raises(Failure):p.validate(encoded,e.REAL_THREE)


def test_source_uses_read_only_growth_and_no_photo_or_state_writer():
 root=Path(__file__).resolve().parents[1]
 source=(root/c.ADAPTER/'RealThreePnpReplay.kt').read_text()
 for denied in ['SparseFeatureCache(','.load(','.publish(','.store(','.mkdir','decodeFile','AdmittedRegistrationSession.grow','System.load']:
  assert denied not in source
 assert 'IncrementalRegistration.grow' in source and 'checkpoint={}' in source
 assert 'FeatureCacheFormat.decode' in source and 'read.verify()' in source
 assert 'actualReason==expectedNumeric' in source
 assert 'RealThreePnpReplay.kt' in c.source_names(e.REAL_THREE)
 assert all('RealThreePnpReplay.kt' not in c.source_names(profile) for profile in e.ALL_PROFILES if profile!=e.REAL_THREE)
 fixture=(root/c.ADAPTER/'RealLicensedThreeCompleteControllerHostTest.kt').read_text()
 assert fixture.index('assertStrictPreview(fixture')<fixture.index('capturePnpReplay(fixture')
 assert 'NativeFrontierGeometry.forOperation(token)' in fixture and 'NativeCore.finishReconstruction(token)' in fixture
 assert 'f.assertOriginals();value' in fixture


def test_huge_integer_is_closed_failure_not_overflow():
 for field in ['threshold','row']:
  v=value()
  if field=='row':v['rows'][0][0]=10**1000
  else:v[field]=10**1000
  with pytest.raises(Failure):p.validate(raw(v),e.REAL_THREE)


def test_org_json_signed_zero_number_spelling_preserves_binary_row_hash():
 v=value();v['rows'][0][0]=-0.0
 v['provenance'][0]['tracks'][0]['sources'][0][2]=-0.0
 v['provenance'][0]['tracks'][0]['sources'][1][2]=-.04
 v['rowsSha256']=hashlib.sha256(b''.join(struct.pack('>d',x) for row in v['rows'] for x in row)).hexdigest()
 encoded=raw(v).replace(b'-0.0,',b'-0,').replace(b'-0.0]',b'-0]')
 assert p.validate(encoded,e.REAL_THREE)['rowsSha256']==v['rowsSha256']


def test_oversized_numeric_failure_is_bounded_before_integer_parse():
 v=value();v['numericFailure']='[pnp1 raw='+('9'*5000)+' distinct=20 required=16 best=1 tested=450 valid=450 model=dlt planarity=0.064929]'
 with pytest.raises(Failure):p.validate(raw(v),e.REAL_THREE)


def test_control_junit_source_is_not_selected_into_host_runtime():
 assert 'RealThreePnpReplayCheck.kt' not in c.source_names(e.REAL_THREE)


@pytest.mark.parametrize('kind',['pose_hash','projection','threshold','source_label','source_ray','link','ambiguity','unknown'])
def test_provenance_must_bind_source_geometry(kind):
 v=value()
 if kind=='pose_hash':v['anchorPoses'][0][10]=.001
 elif kind=='projection':v['projections'][0][3]*=1.01
 elif kind=='threshold':v['threshold']*=1.01
 elif kind=='source_label':v['provenance'][0]['tracks'][0]['sources'][0][0]=12
 elif kind=='source_ray':v['provenance'][0]['tracks'][0]['sources'][0][2]+=.1
 elif kind=='link':v['provenance'][0]['tracks'][0]['sources'][0][4]=123
 elif kind=='ambiguity':v['provenance'][0]['ambiguous']=True
 else:v['provenance'][0]['tracks'][0]['privatePath']='secret'
 with pytest.raises(Failure):p.validate(raw(v),e.REAL_THREE)


def test_ambiguous_equivalent_source_tracks_are_explicit_not_invented_unique():
 v=value();entry=v['provenance'][0];second=copy.deepcopy(entry['tracks'][0]);second['track']=100;second['candidateKey']=100
 for source in second['sources']:source[1]=100;source[4]=100
 entry['tracks'].append(second);entry['ambiguous']=True
 assert p.validate(raw(v),e.REAL_THREE)['provenance'][0]['ambiguous'] is True


@pytest.mark.parametrize('kind',['track','candidateKey','sourceKey'])
def test_source_or_target_identity_cannot_be_claimed_by_multiple_rows(kind):
 v=value();first=v['provenance'][0]['tracks'][0];second=v['provenance'][1]['tracks'][0]
 if kind=='track':second['track']=first['track']
 elif kind=='candidateKey':
  second['candidateKey']=first['candidateKey']
  for source in second['sources']:source[4]=first['candidateKey']
 else:second['sources'][0][1]=first['sources'][0][1]
 with pytest.raises(Failure):p.validate(raw(v),e.REAL_THREE)
