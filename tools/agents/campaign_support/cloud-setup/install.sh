#!/usr/bin/env bash
set -euo pipefail
cd /workspace/3d-scanner
mkdir -p /workspace/.setup/3d-scanner /workspace/.tools /workspace/.android-3d-scanner/cache
python3 -m venv /workspace/.venvs/3d-scanner
export PIP_CACHE_DIR=/workspace/.cache/pip
# Reviewed Agent095 host resolution (754f2892902eb18ece95c6bc1bfde0d4a20861af); legacy repository direct pins stay unchanged.
cat > /workspace/.setup/3d-scanner/host-requirements.lock <<'RF_HOST_LOCK'
# CPython 3.12 Linux x86_64 wheel resolution. Host-only, never Android runtime.
ImageIO==2.38.1 --hash=sha256:36d23eb7423d2fb63f637098758edb3d2df3687f7125e0a5ce8596572022dcf9
iniconfig==2.3.1 --hash=sha256:9121e2c1fdb355232495be3194c8dfe87ccc2d5dee45947b78e68f499790d7a7
lazy-loader==0.6 --hash=sha256:77253be3391b06124a0e16105bd663b6c54470af1a9ca8e1cf026f38d58ed056
networkx==3.7 --hash=sha256:e3fd2c13a7814cee3746340d8d7f8598a67f16a58bf47fb7f8793fab6efca1b0
numpy==2.2.6 --hash=sha256:fd83c01228a688733f1ded5201c678f0c53ecc1006ffbc404db9f7a899ac6249
opencv-python-headless==4.12.0.88 --hash=sha256:236c8df54a90f4d02076e6f9c1cc763d794542e886c576a6fee46ec8ff75a7a9
packaging==26.3 --hash=sha256:d7193f7c8e4e93f444fde0262bf90af30e16fa0ad0ad44cb553c87339b23cd1c
pillow==13.0.0 --hash=sha256:42d63be3af4f9b951b199b0a9d23365d37ab14687e6d77b74940432c8515c4ca
pluggy==1.7.0 --hash=sha256:7dd7b0d8832ba3cb632c306926ded123429211b83641b35dc5c41ad2d34f9bec
Pygments==2.21.0 --hash=sha256:2363c69b61c4a97c838da3b130dcd6468f4848992b21a82f2a63ec34377137d9
pytest==9.0.3 --hash=sha256:2c5efc453d45394fdd706ade797c0a81091eccd1d6e4bccfcd476e2b8e0ab5d9
scikit-image==0.25.2 --hash=sha256:a17e17eb8562660cc0d31bb55643a4da996a81944b82c54805c91b3fe66f4824
scipy==1.18.1 --hash=sha256:f55fa87b6c612ecd6b058f167c53231b1d14e412efe361d3d6e38b3631c73218
tifffile==2026.10.15 --hash=sha256:3dcf6c56731a45c1f85c81e90dad922b0a380a2d639e77f71207e010f8ef0f7b
RF_HOST_LOCK
/workspace/.venvs/3d-scanner/bin/pip install --require-hashes --only-binary=:all: -r /workspace/.setup/3d-scanner/host-requirements.lock
/workspace/.venvs/3d-scanner/bin/pip install cmake==3.31.6 ninja==1.11.1.4
export ANDROID_HOME=/workspace/android-sdk
export ANDROID_USER_HOME=/workspace/.android-3d-scanner
export GRADLE_USER_HOME=/workspace/.gradle-3d-scanner
export JAVA_TOOL_OPTIONS="-Duser.home=/workspace/.home-3d-scanner -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
export JAVA_HOME=/workspace/.tools/jdk-17.0.16+8
export PATH=/workspace/.venvs/3d-scanner/bin:/workspace/.tools/gradle-8.13/bin:/workspace/android-sdk/platform-tools:/workspace/android-sdk/cmdline-tools/latest/bin:$PATH
python3 - <<'PY'
import hashlib, os, pathlib, shutil, subprocess, urllib.parse, xml.etree.ElementTree as E, zipfile
p=pathlib.Path('/workspace/.setup/3d-scanner')
def download(url, dest):
    subprocess.run(['curl','-fL','--retry','2',url,'-o',str(dest)],check=True)
if not pathlib.Path('/workspace/.tools/gradle-8.13/bin/gradle').exists():
    download('https://services.gradle.org/distributions/gradle-8.13-bin.zip',p/'gradle-8.13-bin.zip')
    download('https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256',p/'gradle.sha256')
    assert hashlib.sha256((p/'gradle-8.13-bin.zip').read_bytes()).hexdigest()==(p/'gradle.sha256').read_text().strip()
    with zipfile.ZipFile(p/'gradle-8.13-bin.zip') as z: z.extractall('/workspace/.tools')
    pathlib.Path('/workspace/.tools/gradle-8.13/bin/gradle').chmod(0o755)
jdk=pathlib.Path('/workspace/.tools/jdk-17.0.16+8')
if not (jdk/'bin/javac').exists():
    base='https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz'
    download(base,p/'jdk17.tar.gz')
    download(base+'.sha256.txt',p/'jdk17.sha256')
    assert hashlib.sha256((p/'jdk17.tar.gz').read_bytes()).hexdigest()==(p/'jdk17.sha256').read_text().split()[0]
    import tarfile
    with tarfile.open(p/'jdk17.tar.gz') as t: t.extractall('/workspace/.tools',filter='data')
sdk=pathlib.Path('/workspace/android-sdk/cmdline-tools/latest/bin/sdkmanager')
if not sdk.exists():
    download('https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip',p/'cmdline-tools.zip')
    download('https://dl.google.com/android/repository/repository2-1.xml',p/'repository.xml')
    for a in E.parse(p/'repository.xml').findall('.//archive'):
        u=a.find('./complete/url')
        if u is not None and u.text=='commandlinetools-linux-13114758_latest.zip':
            c=a.find('./complete/checksum')
            assert hashlib.new(c.attrib.get('type','sha1'),(p/'cmdline-tools.zip').read_bytes()).hexdigest()==c.text
            break
    else: raise SystemExit('Authoritative Android checksum missing')
    with zipfile.ZipFile(p/'cmdline-tools.zip') as z: z.extractall('/workspace/android-sdk/bootstrap')
    sdk.parent.parent.parent.mkdir(parents=True,exist_ok=True)
    shutil.move('/workspace/android-sdk/bootstrap/cmdline-tools',sdk.parent.parent)
    for f in sdk.parent.iterdir(): f.chmod(0o755)
args=[str(sdk),'--sdk_root=/workspace/android-sdk']
proxy=os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    u=urllib.parse.urlparse(proxy)
    if u.username or u.password: raise SystemExit('Use supported platform proxy authentication')
    args+=['--proxy=http','--proxy_host='+u.hostname,'--proxy_port='+str(u.port or 80)]
subprocess.run(args+['--licenses'],input='y\n'*100,text=True,check=True)
subprocess.run(args+['platforms;android-36','build-tools;36.0.0','ndk;28.2.13676358','cmake;3.22.1'],check=True)
(p/'env.sh').write_text('export PATH=/workspace/.venvs/3d-scanner/bin:/workspace/.tools/gradle-8.13/bin:/workspace/android-sdk/platform-tools:/workspace/android-sdk/cmdline-tools/latest/bin:$PATH\nexport ANDROID_HOME=/workspace/android-sdk\nexport ANDROID_USER_HOME=/workspace/.android-3d-scanner\nexport GRADLE_USER_HOME=/workspace/.gradle-3d-scanner\nexport JAVA_TOOL_OPTIONS="-Duser.home=/workspace/.home-3d-scanner -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"\nexport JAVA_HOME=/workspace/.tools/jdk-17.0.16+8\n')
(p/'gradle.py').write_text('''import os, subprocess, sys, urllib.parse
args=['/workspace/.tools/gradle-8.13/bin/gradle','--no-daemon','--max-workers=2']
proxy=os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    p=urllib.parse.urlparse(proxy)
    if p.username or p.password: raise SystemExit('Use supported platform proxy authentication')
    for protocol in ('https','http'):
        args += [f'-D{protocol}.proxyHost={p.hostname}',f'-D{protocol}.proxyPort={p.port or 80}']
raise SystemExit(subprocess.call(args+sys.argv[1:],cwd='/workspace/3d-scanner'))
''')
PY
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python -m pytest tests -q --junitxml=/workspace/.setup/3d-scanner/pytest.xml
python /workspace/.setup/3d-scanner/gradle.py :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python tools/check_apk.py app/build/outputs/apk/debug/app-debug.apk --sdk "$ANDROID_HOME" --report /workspace/.setup/3d-scanner/apk-validation.json
