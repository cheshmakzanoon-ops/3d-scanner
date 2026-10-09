#!/usr/bin/env python3
"""Release-blocking APK checks; requires an actually built APK and Android SDK tools."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("apk",type=Path)
    p.add_argument("--sdk",type=Path,default=Path(os.environ.get("ANDROID_HOME",os.environ.get("ANDROID_SDK_ROOT",""))))
    p.add_argument("--report",type=Path)
    args=p.parse_args()
    if not args.apk.is_file():p.error("APK does not exist; no validation can be claimed")
    analyzer=args.sdk/"cmdline-tools/latest/bin/apkanalyzer"
    align=args.sdk/"build-tools/36.0.0/zipalign"
    readelf=shutil.which("readelf")
    if not analyzer.exists() or not align.exists() or not readelf:p.error("Need apkanalyzer, zipalign 36.0.0, and readelf")
    permissions=subprocess.check_output([str(analyzer),"manifest","permissions",str(args.apk)],text=True)
    if "android.permission.INTERNET" in permissions:raise RuntimeError("Unexpected INTERNET permission")
    subprocess.run([str(align),"-c","-P","16","-v","4",str(args.apk)],check=True,capture_output=True,text=True)
    checked=[]
    with zipfile.ZipFile(args.apk) as archive, tempfile.TemporaryDirectory() as directory:
        for info in archive.infolist():
            if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                local=Path(directory)/("-".join(Path(info.filename).parts))
                local.write_bytes(archive.read(info))
                output=subprocess.check_output([readelf,"-lW",str(local)],text=True)
                segments=[line.split() for line in output.splitlines() if line.strip().startswith("LOAD ")]
                if not segments or any(int(fields[-1],16)<16384 for fields in segments):
                    raise RuntimeError(f"Native library lacks 16 KB segment alignment: {info.filename}")
                checked.append(info.filename)
    if not checked:raise RuntimeError("APK contains no native libraries")
    result={"apk":args.apk.name,"internetPermission":False,"zipAlignment16k":True,"elfLibrariesChecked":checked}
    if args.report:args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps(result,indent=2))

if __name__=="__main__":main()
