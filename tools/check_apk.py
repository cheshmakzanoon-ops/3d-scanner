#!/usr/bin/env python3
"""Validate a built APK with privacy, native ABI, ELF/ZIP alignment and signature gates."""
import argparse,json,os
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('apk',type=Path)
    p.add_argument('--sdk',type=Path,default=Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT',''))))
    p.add_argument('--report',type=Path);p.add_argument('--release',action='store_true')
    p.add_argument('--abis',default='arm64-v8a,x86_64');p.add_argument('--signing-mode',choices=['test','production'],default='test')
    p.add_argument('--certificate-sha256');p.add_argument('--require-notices',action='store_true')
    args=p.parse_args()
    from release.package_validation.apk import validate
    result=validate(args.apk,args.sdk,release=args.release,abis=args.abis.split(','),mode=args.signing_mode,
                    certificate=args.certificate_sha256,required_notices=args.require_notices)
    text=json.dumps(result,indent=2)+'\n'
    if args.report:args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(text)
    print(text,end='')
if __name__=='__main__':main()
