#!/usr/bin/env python3
"""Run pinned Gradle using the runtime's existing proxy, without saving credentials."""
import os
import sys
from urllib.parse import urlparse
args = ['/workspace/toolchains/gradle-8.13/bin/gradle', '--no-daemon', '--max-workers=1']
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
