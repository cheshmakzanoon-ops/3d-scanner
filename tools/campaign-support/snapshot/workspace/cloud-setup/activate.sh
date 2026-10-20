export JAVA_HOME=/workspace/toolchains/jdk-17.0.17+10
export ANDROID_HOME=/workspace/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME=/workspace/.android
unset ANDROID_SDK_HOME
export GRADLE_USER_HOME=/workspace/.gradle
export PATH="/workspace/.venvs/3d-scanner/bin:$JAVA_HOME/bin:/workspace/toolchains/gradle-8.13/bin:$ANDROID_HOME/platform-tools:$PATH"
# Java does not automatically use the platform's HTTPS_PROXY environment variable.
proxy_options=$(python - <<'PY'
import os, urllib.parse
u=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY', ''))
if u.hostname:
    if u.username: raise SystemExit('Authenticated proxy requires platform configuration')
    print(f'-Dhttps.proxyHost={u.hostname} -Dhttps.proxyPort={u.port or 80} -Dhttp.proxyHost={u.hostname} -Dhttp.proxyPort={u.port or 80}')
PY
)
export JAVA_TOOL_OPTIONS="$proxy_options -Duser.home=/workspace/.android-user -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
unset proxy_options
