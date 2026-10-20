#!/usr/bin/env bash
set -euo pipefail
cd /workspace/3d-scanner
python3 -m venv /workspace/.venvs/3d-scanner
export PATH=/workspace/.venvs/3d-scanner/bin:$PATH
export PIP_CACHE_DIR=/workspace/toolchains/pip-cache
python -m pip install -r tools/requirements-real-benchmark.txt cmake==3.31.6 ninja==1.11.1.4
mkdir -p /workspace/toolchains/downloads
python - <<'PY'
from pathlib import Path
import hashlib, subprocess, zipfile, shutil
p=Path('/workspace/toolchains/downloads')
def fetch(url,name):
    target=p/name
    if not target.exists():
        subprocess.run(['curl','-fsSL','--retry','2',url,'-o',str(target)+'.partial'],check=True)
        Path(str(target)+'.partial').replace(target)
    return target
gradle=fetch('https://services.gradle.org/distributions/gradle-8.13-bin.zip','gradle.zip')
checksum=fetch('https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256','gradle.sha256').read_text().strip()
assert len(checksum)==64 and hashlib.sha256(gradle.read_bytes()).hexdigest()==checksum
android=fetch('https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip','android-tools.zip')
# Official repository2-1.xml checksum for this exact archive.
assert hashlib.sha1(android.read_bytes()).hexdigest()=='5fdcc763663eefb86a5b8879697aa6088b041e70'
if not Path('/workspace/toolchains/gradle-8.13/bin/gradle').exists():
    with zipfile.ZipFile(gradle) as z:z.extractall('/workspace/toolchains')
latest=Path('/workspace/toolchains/android-sdk/cmdline-tools/latest')
if not latest.exists():
    with zipfile.ZipFile(android) as z:z.extractall('/workspace/toolchains/android-tmp')
    latest.parent.mkdir(parents=True,exist_ok=True)
    shutil.move('/workspace/toolchains/android-tmp/cmdline-tools',latest)
for f in [Path('/workspace/toolchains/gradle-8.13/bin/gradle'),*latest.joinpath('bin').iterdir()]:
    f.chmod(f.stat().st_mode|0o111)
import tarfile
jdk_url='https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz'
jdk=fetch(jdk_url,'jdk17.tar.gz')
jdk_hash=fetch(jdk_url+'.sha256.txt','jdk17.sha256').read_text().split()[0]
assert hashlib.sha256(jdk.read_bytes()).hexdigest()==jdk_hash
if not Path('/workspace/toolchains/jdk-17.0.16+8/bin/javac').exists():
    with tarfile.open(jdk) as t:t.extractall('/workspace/toolchains',filter='data')
PY
export JAVA_HOME=/workspace/toolchains/jdk-17.0.16+8
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/workspace/toolchains/android-sdk
export ANDROID_USER_HOME=/workspace/toolchains/android-user
export GRADLE_USER_HOME=/workspace/toolchains/gradle-user
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
# sdkmanager closes stdin once all licenses are accepted; ignore only yes's SIGPIPE.
set +o pipefail
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
set -o pipefail
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1' platform-tools
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python -m pytest tests -q

cat > /workspace/toolchains/activate-3d-scanner.sh <<'CLOUD_SETUP_EOF'
export JAVA_HOME=/workspace/toolchains/jdk-17.0.16+8
export PATH=$JAVA_HOME/bin:/workspace/.venvs/3d-scanner/bin:/workspace/toolchains/gradle-8.13/bin:/workspace/toolchains/android-sdk/platform-tools:$PATH
export ANDROID_HOME=/workspace/toolchains/android-sdk
export ANDROID_USER_HOME=/workspace/toolchains/android-user
export GRADLE_USER_HOME=/workspace/toolchains/gradle-user
CLOUD_SETUP_EOF

cat > /workspace/toolchains/gradle-cloud.py <<'CLOUD_SETUP_EOF'
#!/usr/bin/env python3
"""Run pinned Gradle using the runtime's existing proxy, without saving credentials."""
import os
import sys
from urllib.parse import urlparse
args = ['/workspace/toolchains/gradle-8.13/bin/gradle', '--no-daemon', '--max-workers=2']
if os.path.isfile('/etc/ssl/certs/java/cacerts'):
    args += ['-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
for protocol in ('http', 'https'):
    raw = os.environ.get(protocol.upper() + '_PROXY')
    if raw:
        proxy = urlparse(raw)
        if proxy.username or proxy.password:
            raise SystemExit('Authenticated proxy requires supported platform configuration')
        args += [f'-D{protocol}.proxyHost={proxy.hostname}', f'-D{protocol}.proxyPort={proxy.port or 80}']
os.execv(args[0], args + sys.argv[1:])
CLOUD_SETUP_EOF

source /workspace/toolchains/activate-3d-scanner.sh
python /workspace/toolchains/gradle-cloud.py --version
