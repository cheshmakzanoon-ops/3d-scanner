import os, subprocess, sys, urllib.parse
args=['/workspace/.tools/gradle-8.13/bin/gradle','--no-daemon','--max-workers=2']
proxy=os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    p=urllib.parse.urlparse(proxy)
    if p.username or p.password: raise SystemExit('Use supported platform proxy authentication')
    for protocol in ('https','http'):
        args += [f'-D{protocol}.proxyHost={p.hostname}',f'-D{protocol}.proxyPort={p.port or 80}']
raise SystemExit(subprocess.call(args+sys.argv[1:],cwd='/workspace/3d-scanner'))
