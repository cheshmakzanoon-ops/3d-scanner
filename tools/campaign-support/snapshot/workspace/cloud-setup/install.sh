#!/usr/bin/env bash
set -euo pipefail
cd /workspace/3d-scanner
mkdir -p /workspace/toolchains /workspace/.android /workspace/.android-user /workspace/setup-logs
python -m venv /workspace/.venvs/3d-scanner
/workspace/.venvs/3d-scanner/bin/python -m pip install -r tools/requirements-real-benchmark.txt cmake==3.31.6 ninja==1.11.1.4
if [ ! -x /workspace/toolchains/gradle-8.13/bin/gradle ]; then
  curl -fsSL --max-time 180 https://downloads.gradle.org/distributions/gradle-8.13-bin.zip -o /tmp/gradle.zip
  curl -fsSL --max-time 60 https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256 -o /tmp/gradle.sha256
  python -c 'import hashlib,pathlib; assert hashlib.sha256(pathlib.Path("/tmp/gradle.zip").read_bytes()).hexdigest()==pathlib.Path("/tmp/gradle.sha256").read_text().strip()'
  unzip -q /tmp/gradle.zip -d /workspace/toolchains
fi
if [ ! -x /workspace/toolchains/jdk-17.0.17+10/bin/javac ]; then
  url='https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.17%2B10/OpenJDK17U-jdk_x64_linux_hotspot_17.0.17_10.tar.gz'
  curl -fsSL --max-time 180 "$url" -o /tmp/jdk17.tar.gz
  curl -fsSL --max-time 60 "$url.sha256.txt" -o /tmp/jdk17.sha256
  python -c 'import hashlib,pathlib; assert hashlib.sha256(pathlib.Path("/tmp/jdk17.tar.gz").read_bytes()).hexdigest()==pathlib.Path("/tmp/jdk17.sha256").read_text().split()[0]'
  tar -xzf /tmp/jdk17.tar.gz -C /workspace/toolchains
fi
if [ ! -x /workspace/android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  curl -fsSL --max-time 180 https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o /tmp/android-cli.zip
  curl -fsSL --max-time 60 https://dl.google.com/android/repository/repository2-1.xml -o /tmp/android-repository.xml
  python - <<'PY'
import xml.etree.ElementTree as E, hashlib
for c in E.parse('/tmp/android-repository.xml').iter('complete'):
    if c.findtext('url') == 'commandlinetools-linux-13114758_latest.zip':
        s=c.find('checksum')
        assert hashlib.new(s.get('type','sha1'),open('/tmp/android-cli.zip','rb').read()).hexdigest()==s.text
        break
else: raise SystemExit('Archive absent from official metadata')
PY
  tmp=$(mktemp -d)
  unzip -q /tmp/android-cli.zip -d "$tmp"
  mkdir -p /workspace/android-sdk/cmdline-tools
  mv "$tmp/cmdline-tools" /workspace/android-sdk/cmdline-tools/latest
  rmdir "$tmp"
fi
source /workspace/cloud-setup/activate.sh
# Accept SDK licenses noninteractively; retain sdkmanager's exit status.
set +o pipefail
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
set -o pipefail
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1' 'platform-tools'
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python -m pytest tests -q --junitxml=/workspace/setup-logs/pytest.xml
