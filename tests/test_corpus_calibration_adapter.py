import copy
import json
from pathlib import Path
import subprocess
import sys

from PIL import Image
import pytest

from tools.qa.corpus.calibration import convert
from tools.qa.corpus.fixture import generate
from tools.qa.corpus.manifest import InvalidManifest, sha256, validate


@pytest.fixture
def inputs(tmp_path):
    manifest = generate(tmp_path)
    profile = tmp_path/'original-app-profile.json'
    profile.write_text(json.dumps({'schema':1,'model':'brown-conrady-5','orientation':'exif-upright',
        'width':32,'height':24,'fx':30.25,'fy':31.5,'cx':15.5,'cy':11.5,
        'k1':.01,'k2':-.002,'p1':.0003,'p2':-.0004,'k3':.00001,
        'acceptedViews':12,'reprojectionRmsPixels':.25,'focal35mm':28}))
    photos = [tmp_path/p['path'] for p in manifest['photos']]
    return tmp_path, manifest, profile, photos


def test_profile_wrapper_keeps_original_distortion_and_exact_intrinsics(inputs):
    root, manifest, profile, photos = inputs
    original = profile.read_bytes()
    wrapped = convert(profile, manifest['capture']['lens_id'], photos, root)
    assert wrapped['K'] == [[30.25,0,15.5],[0,31.5,11.5],[0,0,1]]
    assert wrapped['distortion'] == {'model':'brown-conrady-5','k1':.01,'k2':-.002,'p1':.0003,'p2':-.0004,'k3':.00001}
    assert wrapped['source_profile']['sha256'] == sha256(profile)
    assert wrapped['coordinate_frame'] == 'raw-pixels-exif-identity'
    assert profile.read_bytes() == original
    calibration = root/'calibration.json';calibration.write_text(json.dumps(wrapped))
    manifest['calibration']['sha256'] = sha256(calibration)
    receipt = validate(manifest, root)
    assert receipt['status']=='PASS' and receipt['files_verified']==6
    assert receipt['physical_qualification']=='NOT_APPLICABLE_SYNTHETIC'
    data=json.loads(profile.read_text());data['acceptedViews']=13;profile.write_text(json.dumps(data))
    with pytest.raises(InvalidManifest,match='tampered'):
        validate(manifest, root)


@pytest.mark.parametrize('orientation', range(2,9))
def test_nonidentity_exif_refused_without_rotating_original(inputs, orientation):
    root, manifest, profile, photos = inputs
    photo = root/'oriented.jpg'
    exif=Image.Exif();exif[274]=orientation
    Image.new('RGB',(32,24),(17,31,43)).save(photo,exif=exif)
    original=photo.read_bytes()
    with pytest.raises(InvalidManifest,match='nonidentity EXIF'):
        convert(profile,manifest['capture']['lens_id'],[photo],root)
    assert photo.read_bytes()==original


@pytest.mark.parametrize('change', [
    {'width':24,'height':32}, {'orientation':'raw'}, {'fx':True},
    {'fy':float('nan')}, {'k1':float('inf')}, {'schema':2}, {'model':'unknown'},
])
def test_incompatible_profile_metadata_refused(inputs, change):
    root, manifest, profile, photos=inputs
    data=json.loads(profile.read_text());data.update(change);profile.write_text(json.dumps(data))
    with pytest.raises(InvalidManifest):convert(profile,manifest['capture']['lens_id'],photos,root)


def test_modified_wrapper_cannot_change_k_or_discard_distortion(inputs):
    root, manifest, profile, photos=inputs
    wrapped=convert(profile,manifest['capture']['lens_id'],photos,root)
    for mutate in (lambda d:d['K'][0].__setitem__(0,99), lambda d:d.pop('distortion')):
        candidate=copy.deepcopy(wrapped);mutate(candidate)
        calibration=root/'calibration.json';calibration.write_text(json.dumps(candidate))
        manifest['calibration']['sha256']=sha256(calibration)
        with pytest.raises(InvalidManifest,match='differs from original'):
            validate(manifest,root)


def test_duplicate_fields_and_external_profile_refused(inputs):
    root, manifest, profile, photos=inputs
    profile.write_text('{"schema":1,"schema":1}')
    with pytest.raises(InvalidManifest):convert(profile,manifest['capture']['lens_id'],photos,root)
    outside=root.parent/'outside-profile.json';outside.write_text('{}')
    with pytest.raises(InvalidManifest,match='inside corpus root'):
        convert(outside,manifest['capture']['lens_id'],photos,root)


def test_cli_writes_new_wrapper_and_refuses_original_overwrite(inputs):
    root, manifest, profile, photos=inputs
    output=root/'wrapped.json'
    args=[sys.executable,'-m','tools.qa.corpus.calibration',str(profile),'--root',str(root),
          '--lens-id',manifest['capture']['lens_id'],'--output',str(output)]
    for photo in photos:args+=['--photo',str(photo)]
    run=subprocess.run(args,capture_output=True,text=True)
    assert run.returncode==0,run.stdout+run.stderr
    assert json.loads(run.stdout)['status']=='PASS_INPUT_CONVERSION_ONLY'
    assert subprocess.run(args,capture_output=True).returncode==1
