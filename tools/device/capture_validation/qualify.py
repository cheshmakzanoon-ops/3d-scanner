#!/usr/bin/env python3
"""Private physical-capture evidence audit; hashes and JPEG metadata, no uploads."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import math
import re
from pathlib import Path
import subprocess
import zipfile
from PIL import Image

SCENARIOS=('permissionDenial','interruptedCapture','rotation','focusExposure','importReconstructReopen')

def sha(data): return hashlib.sha256(data).hexdigest()

def checked_file(path, expected, base):
    if not isinstance(expected,str) or not re.fullmatch('[0-9a-f]{64}',expected):raise ValueError('Invalid referenced SHA256')
    source=Path(path)
    if not source.is_absolute():source=base/source
    if not source.is_file() or source.stat().st_size>2*1024**3:raise ValueError('Missing/oversized referenced evidence file')
    digest=hashlib.sha256()
    with source.open('rb') as stream:
        for block in iter(lambda:stream.read(65536),b''):digest.update(block)
    if digest.hexdigest()!=expected:raise ValueError('Referenced evidence file changed')
    return source

def physical(device):
    if (device.get('schema') != 1 or device.get('emulator') is not False or device.get('abi') != 'arm64-v8a'
            or not device.get('fingerprint') or not device.get('serial') or len(device.get('appSha256','')) != 64):
        raise ValueError('Actual identified ARM64 handset and app hash required; emulators cannot qualify')


def jpeg(data):
    if len(data)>150*1024*1024 or not data.startswith(b'\xff\xd8'):
        raise ValueError('Original is not a bounded JPEG')
    with Image.open(io.BytesIO(data)) as image:
        if image.format!='JPEG' or image.width*image.height>100_000_000:
            raise ValueError('Invalid JPEG dimensions/encoding')
        exif=image.getexif()
        extra=exif.get_ifd(0x8769) if 0x8769 in exif else {}
        orientation=exif.get(274,1)
        if orientation not in range(1,9): raise ValueError('Invalid EXIF orientation')
        image.verify()
        def number(key):
            value=extra.get(key)
            if value is None:return None
            value=float(value)
            if not math.isfinite(value) or value<=0:raise ValueError('Invalid exposure/focal metadata')
            return value
        return {'width':image.width,'height':image.height,'exifOrientation':orientation,
                'exposureSeconds':number(33434),'fNumber':number(33437),
                'focalLengthMm':number(37386),'lensModel':str(extra.get(42036,'')),
                'focusDistanceMeters':number(37382)}


def audit(session, device, archive, base=Path.cwd()):
    physical(device)
    if session.get('schema')!=1 or session.get('authorized') is not True or session.get('source')!='physical-camera':
        raise ValueError('Explicit authorized physical-camera provenance required')
    if session.get('appSha256')!=device['appSha256'] or session.get('deviceFingerprint')!=device['fingerprint']:
        raise ValueError('Session does not match selected device/candidate')
    if not session.get('objectId') or not session.get('cameraId') or session.get('zoomRatio')!=1:
        raise ValueError('Object, selected camera and fixed 1x optical setup required')
    observations=session.get('observations',{})
    for scenario in SCENARIOS:
        item=observations.get(scenario,{})
        if item.get('status')!='PASS' or len(item.get('evidenceSha256',''))!=64:
            raise ValueError('Missing actual scenario evidence: '+scenario)
        checked_file(item.get('evidencePath',''),item['evidenceSha256'],base)
    calibration=session.get('calibration',{})
    if calibration.get('cameraId')!=session['cameraId'] or calibration.get('acceptedViews',0)<10:
        raise ValueError('Same-lens independent checkerboard metadata required')
    for field in ('profileSha256','checkerboardManifestSha256'):
        if len(calibration.get(field,''))!=64:raise ValueError('Calibration identity missing: '+field)
    checkerboard_path=checked_file(calibration.get('checkerboardManifestPath',''),calibration['checkerboardManifestSha256'],base)
    checkerboard=json.loads(checkerboard_path.read_text())
    if checkerboard.get('schema')!=1 or checkerboard.get('cameraId')!=session['cameraId'] or not 10<=len(checkerboard.get('photos',[]))<=40:
        raise ValueError('Checkerboard source views/camera identity invalid')
    if len({photo.get('sha256') for photo in checkerboard['photos']})!=len(checkerboard['photos']):raise ValueError('Duplicate checkerboard source views')
    for photo in checkerboard['photos']:
        checked_file(photo.get('localPath',''),photo.get('sha256'),checkerboard_path.parent)
    reference=session.get('referenceGeometry',{})
    if len(reference.get('sha256',''))!=64 or not reference.get('independentMethod') or reference.get('units') not in ('m','mm'):
        raise ValueError('Independent reference geometry and units required')
    checked_file(reference.get('localPath',''),reference['sha256'],base)
    if len(session.get('heights',[]))<2 or not session.get('lighting'):
        raise ValueError('Multiple heights and lighting record required')
    rows=[]
    with zipfile.ZipFile(archive) as z:
        names=z.namelist()
        if len(set(names))!=len(names):raise ValueError('Duplicate archive entries')
        infos={i.filename:i for i in z.infolist()}
        if len(infos)>253 or sum(i.file_size for i in infos.values())>2*1024**3:
            raise ValueError('Capture archive exceeds bounds')
        if 'session.json' not in infos or infos['session.json'].file_size>200_000:raise ValueError('Missing/big capture manifest')
        manifest=json.loads(z.read('session.json'))
        photos=manifest.get('photos',[])
        if manifest.get('schema')!=1 or not 12<=len(photos)<=250:raise ValueError('12–250 actual originals required')
        if 'camera-calibration-v1.json' not in infos or infos['camera-calibration-v1.json'].file_size>10000:
            raise ValueError('Calibrated profile absent from app export')
        profile=z.read('camera-calibration-v1.json')
        if sha(profile)!=calibration['profileSha256']:raise ValueError('Exported calibration bytes changed')
        measured=json.loads(profile)
        if measured.get('schema')!=1 or measured.get('model')!='brown-conrady-5' or measured.get('orientation')!='exif-upright' or measured.get('acceptedViews')!=calibration['acceptedViews']:
            raise ValueError('Calibration profile metadata mismatch')
        for key in ('fx','fy','cx','cy','k1','k2','k3','p1','p2','reprojectionRmsPixels'):
            if type(measured.get(key)) not in (int,float) or not math.isfinite(measured[key]):raise ValueError('Invalid measured calibration')
        if not 0<=measured['reprojectionRmsPixels']<=2.5 or measured.get('width',0)<=0 or measured.get('height',0)<=0:raise ValueError('Rejected checkerboard calibration')
        hashes=[]
        for item in photos:
            name=item.get('path','')
            if not name.startswith('photos/') or len(name.split('/'))!=2 or '\\' in name or name.split('/')[-1] in ('.','..',''):
                raise ValueError('Unsafe original path')
            if name not in infos or infos[name].file_size>150*1024**2:raise ValueError('Missing/oversized original')
            data=z.read(name);digest=sha(data)
            if digest!=item.get('sha256') or digest in hashes:raise ValueError('Original hash changed or duplicate photo bytes')
            hashes.append(digest)
            metadata=jpeg(data)
            upright=(metadata['height'],metadata['width']) if metadata['exifOrientation'] in (5,6,7,8) else (metadata['width'],metadata['height'])
            if abs(upright[0]/upright[1]-measured['width']/measured['height'])>=.005:raise ValueError('Calibration crop/orientation differs from originals')
            rows.append({'path':name,'sha256':digest,**metadata})
    if set(hashes)!=set(session.get('savedOriginalSha256s',[])):raise ValueError('Saved-original instrumented identity does not match archive')
    return {'schema':1,'kind':'physical_capture_input_audit','status':'PASS','device':device,
            'objectId':session['objectId'],'cameraId':session['cameraId'],'archiveSha256':sha(Path(archive).read_bytes()),
            'calibration':calibration,'referenceGeometry':reference,'photos':rows,
            'limitations':['Operator scenario evidence requires independent review','EXIF is metadata, not a focus/geometry measurement','Geometry accuracy and completeness require083/084 evaluation']}


def discover(serial, apk):
    def shell(*args):
        result=subprocess.run(['adb','-s',serial,'shell',*args],capture_output=True,text=True,timeout=30,check=True)
        return result.stdout.strip()
    installed=shell('pm','path','com.realityforge.scanner').splitlines()
    if len(installed)!=1 or not installed[0].startswith('package:'):raise ValueError('One installed base APK required')
    digest=sha(apk.read_bytes())
    if shell('sha256sum',installed[0][8:]).split()[0]!=digest:raise ValueError('Installed APK differs from selected candidate')
    device={'schema':1,'serial':serial,'fingerprint':shell('getprop','ro.build.fingerprint'),
            'api':int(shell('getprop','ro.build.version.sdk')),'abi':shell('getprop','ro.product.cpu.abi'),
            'emulator':shell('getprop','ro.kernel.qemu')=='1','appSha256':digest}
    physical(device)
    return device


def main():
    parser=argparse.ArgumentParser(description=__doc__); sub=parser.add_subparsers(dest='mode',required=True)
    d=sub.add_parser('discover');d.add_argument('--serial',required=True);d.add_argument('--app-apk',type=Path,required=True);d.add_argument('--output',type=Path,required=True)
    a=sub.add_parser('audit');a.add_argument('session',type=Path);a.add_argument('archive',type=Path);a.add_argument('--device',type=Path,required=True);a.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    try:
        report=discover(args.serial,args.app_apk) if args.mode=='discover' else audit(json.loads(args.session.read_text()),json.loads(args.device.read_text()),args.archive,args.session.parent)
    except Exception as error:
        report={'schema':1,'status':'FAIL','error':str(error)}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n')
    raise SystemExit(1 if report.get('status')=='FAIL' else 0)

if __name__=='__main__':main()
