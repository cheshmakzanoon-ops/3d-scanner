import json,os,pathlib,subprocess,time,urllib.parse
root=pathlib.Path('/workspace/campaign/evidence/092/emulator-probe')
args=['/workspace/android-sdk/cmdline-tools/latest/bin/sdkmanager','--sdk_root=/workspace/campaign/emulator-sdk','--channel=0']
proxy=os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    value=urllib.parse.urlparse(proxy)
    if value.username or value.password: raise SystemExit('Use supported platform proxy authentication')
    args += ['--proxy=http','--proxy_host='+value.hostname,'--proxy_port='+str(value.port or 80)]
args += ['emulator','system-images;android-29;default;x86_64']
env=os.environ.copy();env['ANDROID_USER_HOME']='/workspace/campaign/emulator-user'
started=time.time()
with (root/'sdk-install.log').open('w') as output:
    try:
        result=subprocess.run(args,env=env,stdout=output,stderr=subprocess.STDOUT,timeout=480)
        receipt={'returncode':result.returncode,'duration_seconds':round(time.time()-started,2),'sdk_root':'/workspace/campaign/emulator-sdk','installed_requested_packages':args[-2:],'verification':'Official SDKmanager with TLS trust and authoritative repository checksums; no --no_https'}
    except subprocess.TimeoutExpired:
        receipt={'returncode':124,'duration_seconds':round(time.time()-started,2),'error':'Bounded480s SDK install timed out'}
(root/'sdk-install.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt))
raise SystemExit(receipt['returncode'])
