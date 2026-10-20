#!/usr/bin/env python3
"""Write the save-only handoff from exact remote-verified branch receipts."""
import datetime,json
from pathlib import Path
root=Path('/workspace/campaign')
rows=json.loads((root/'delivery.json').read_text())
providers=json.loads((root/'all-authored-delivery-branches.json').read_text())['branches']
snapshots=json.loads((root/'local-snapshot-push-receipt.json').read_text())['snapshots']
url='https://github.com/cheshmakzanoon-ops/3d-scanner'
lines=['# Development stopped: committed and pushed handoff','',
       'Development and test execution stopped at the user’s request on 2026-10-20. '
       'All authored source, tests, unfinished code, contracts and receipts have been saved on their dedicated branches. '
       'All 30 cohort branch heads and the additional provider heads were verified against GitHub. '
       'No further implementation or development test was performed after the stop.','',
       '| Agent | Dedicated branch | Pushed commit |','| --- | --- | --- |']
for r in rows:
    sha=r['remote_sha']
    lines.append(f"| {r['agent']} | [{r['branch']}]({url}/tree/{r['branch']}) | [{sha[:12]}]({url}/commit/{sha}) |")
lines+=['','## Additional receiving and provider branches','','| Branch | Pushed commit |','| --- | --- |']
cohort={r['branch'] for r in rows}
for r in providers:
    if r['branch'] not in cohort:
        lines.append(f"| [{r['branch']}]({url}/tree/{r['branch']}) | [{r['commit'][:12]}]({url}/commit/{r['commit']}) |")
lines+=['','## Preserved local checkpoints','','These are archive snapshots of intermediate integration/validation work, not qualified releases.','',
        '| Archive branch | Commit |','| --- | --- |']
for r in snapshots:
    lines.append(f"| [{r['branch']}]({url}/tree/{r['branch']}) | [{r['commit'][:12]}]({url}/commit/{r['commit']}) |")
lines+=['','Setup scripts, external validation tools, diagnostic source, draft receipts and text evidence are saved on '
        f'[`agents/cloud-environment-campaign-support`]({url}/tree/agents/cloud-environment-campaign-support). '
        'Its preservation inventory records original paths and SHA-256 values. '
        'Installed third-party SDK/toolchain/dependency caches, generated executable/APK/library/archive binaries and public reference photo data remain local; they are not authored repository source. '
        'Their existing checksums and validation receipts are retained. Credentials and authentication stores are excluded.','',
        '## Unfinished state preserved','',
        '- Agent 012: conservative physical-orientation scanner and JVM tests are saved as unfinished WIP at '
        '`7843eb76b285a12ee7d149cf4112d502b603ff97`. The Android malformed-tag fixture, final review/build and device retest are unfinished. '
        'The tested intermediate APK is separate from this WIP.',
        '- Agent 049: confirmed-support observation provenance is an explicitly unimplemented design, saved in its contract and the receiving 046 handoff.',
        '- Agent 051: per-kernel cancellation timing harness is an explicitly unimplemented plan. Existing cancellation tests remain available.',
        '- Agent 053: unchanged acceptance gate remains FAIL: actual 50 fusions versus required >50. No gate was lowered.',
        '- Physical ARM64, camera/GPU/thermal and full-object independent-reference qualification remains unrun. Optional Vulkan remains disabled.','',
        '## Last completed checks and interrupted work','',
        'Results below belong to their exact recorded source/artifact identities; they do not qualify newly saved WIP.','',
        '- Native Release 36/36 PASS; sanitizers 36/36 PASS.',
        '- Full host pytest: 211 PASS, 1 surface-count FAIL, 0 skipped.',
        '- Android builds: 124 JVM tests PASS, both APKs built, lint 0 errors/8 warnings, no INTERNET permission, 16 KB ZIP/all12 ELF checks PASS.',
        '- API29 software emulator: JNI/geometry 11/11 PASS; original platform 9 PASS/1 decoder-classification FAIL.',
        '- Production pipeline deliberately INTERRUPTED_BY_USER_REQUEST after 833.44 seconds. Resource batch NOT_RUN_USER_REQUEST.',
        '- Instrumentation/app stopped without clearing data; leased API35 emulator verified and restored with SIGCONT.','',
        'The complete tracked manifests and bounded credential/build scan passed for all30 final cohort heads. '
        'The receiving004 branch preserves the concurrent upstream release-wiring/lockfile changes through a normal merge; no fresh build is claimed for that save merge. '
        'No force push or main merge was performed by this campaign.','']
(root/'DELIVERY.md').write_text('\n'.join(lines))
print('Saved current stop-only delivery index for',len(rows),'cohort branches')
