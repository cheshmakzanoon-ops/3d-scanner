"""Explicitly synthetic JPEG/parser fixtures; no physical evidence generated."""
import importlib.util
import io
from pathlib import Path
import pytest
from PIL import Image
spec=importlib.util.spec_from_file_location('capture',Path(__file__).resolve().parents[1]/'tools/device/capture_validation/qualify.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
DEVICE={'schema':1,'emulator':False,'abi':'arm64-v8a','fingerprint':'synthetic parser fixture','serial':'fixture','appSha256':'a'*64}

@pytest.mark.parametrize('change',[{'emulator':True},{'abi':'x86_64'},{'fingerprint':''},{'appSha256':''}])
def test_emulator_or_unidentified_inputs_rejected(change):
    with pytest.raises(ValueError):m.physical(DEVICE|change)


def test_synthetic_scene_and_missing_actual_observations_cannot_pass(tmp_path):
    with pytest.raises(ValueError,match='provenance'):m.audit({'schema':1,'authorized':True,'source':'synthetic'},DEVICE,tmp_path/'absent.zip')
    session={'schema':1,'authorized':True,'source':'physical-camera','appSha256':'a'*64,'deviceFingerprint':DEVICE['fingerprint'],'objectId':'synthetic parser input','cameraId':'0','zoomRatio':1}
    with pytest.raises(ValueError,match='permissionDenial'):m.audit(session,DEVICE,tmp_path/'absent.zip')


def test_actual_jpeg_exif_orientation_decodes():
    image=Image.new('RGB',(96,64),(42,55,66));exif=Image.Exif();exif[274]=6
    out=io.BytesIO();image.save(out,format='JPEG',exif=exif)
    row=m.jpeg(out.getvalue())
    assert (row['width'],row['height'],row['exifOrientation'])==(96,64,6)
    assert row['exposureSeconds'] is None

@pytest.mark.parametrize('data',[b'not jpeg',b'\xff\xd8truncated'])
def test_corrupt_original_rejected(data):
    with pytest.raises((ValueError,OSError)):m.jpeg(data)


def test_complete_synthetic_parser_contract_audits_bytes_then_detects_tampering(tmp_path):
    import json,zipfile
    evidence=tmp_path/'synthetic-observations.json';evidence.write_text('{"syntheticParserFixture":true}')
    reference=tmp_path/'synthetic-reference.ply';reference.write_text('synthetic parser fixture reference bytes')
    photos=[];checkers=[]
    archive=tmp_path/'synthetic.zip'
    profile={'schema':1,'model':'brown-conrady-5','orientation':'exif-upright','acceptedViews':10,'width':96,'height':64,
             'fx':100,'fy':100,'cx':48,'cy':32,'k1':0,'k2':0,'k3':0,'p1':0,'p2':0,'reprojectionRmsPixels':.4}
    profile_bytes=json.dumps(profile).encode()
    with zipfile.ZipFile(archive,'w') as z:
        for index in range(12):
            out=io.BytesIO();Image.new('RGB',(96,64),(index*17,50,60)).save(out,format='JPEG')
            data=out.getvalue();photo={'path':f'photos/{index}.image','sha256':m.sha(data)};photos.append(photo)
            z.writestr(photo['path'],data)
            if index<10:
                f=tmp_path/f'checker-{index}.jpg';f.write_bytes(data)
                checkers.append({'localPath':f.name,'sha256':m.sha(data)})
        z.writestr('session.json',json.dumps({'schema':1,'photos':photos}))
        z.writestr('camera-calibration-v1.json',profile_bytes)
    checker=tmp_path/'checkerboard.json';checker.write_text(json.dumps({'schema':1,'cameraId':'0','photos':checkers}))
    session={'schema':1,'authorized':True,'source':'physical-camera','appSha256':'a'*64,'deviceFingerprint':DEVICE['fingerprint'],
             'objectId':'SYNTHETIC parser fixture','cameraId':'0','zoomRatio':1,'heights':[0,1],'lighting':'synthetic fixture',
             'savedOriginalSha256s':[p['sha256'] for p in photos],
             'observations':{key:{'status':'PASS','evidencePath':evidence.name,'evidenceSha256':m.sha(evidence.read_bytes())} for key in m.SCENARIOS},
             'calibration':{'cameraId':'0','acceptedViews':10,'profileSha256':m.sha(profile_bytes),'checkerboardManifestPath':checker.name,'checkerboardManifestSha256':m.sha(checker.read_bytes())},
             'referenceGeometry':{'localPath':reference.name,'sha256':m.sha(reference.read_bytes()),'independentMethod':'synthetic parser fixture','units':'mm'}}
    report=m.audit(session,DEVICE,archive,tmp_path)
    assert report['status']=='PASS' and len(report['photos'])==12
    reference.write_text('changed bytes')
    with pytest.raises(ValueError,match='changed'):m.audit(session,DEVICE,archive,tmp_path)
