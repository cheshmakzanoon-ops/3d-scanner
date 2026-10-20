# Offline scanner input assurance, version 1

Scope: hostile local files chosen by the user, transferred capture bundles, image
metadata/calibration, imported GLB, cached native buffers, SDK/export destinations,
and development dependencies. Offline operation removes unsolicited upload, not
the need to bound untrusted bytes. Private originals and credentials must stay out
of public reports. We assume the operator controls the selected scanner/SDK binary;
this is not a sandbox for executing an adversary-supplied tool executable.

## Implemented capture boundary

`tools/capture_audit.py` parses the ZIP end directory before `ZipFile` allocation:
at most252 records,1MiB directory,2GiB archive/uncompressed sum, no multidisk/ZIP64.
It rejects duplicate names (including session/calibration), directories, encrypted
or unsupported compression, symlinks, >150MiB members and >200x compression ratios.
Photos must use literal ASCII `photos/NAME`; normalized traversal, separators,
NUL, absolute paths and unreferenced members fail. ZIP CRC/overlap/decode errors
become predictable `ValueError` results without printing input bytes.

JSON rejects duplicate keys, nonfinite numbers (including exponent overflow),
unsupported schema types, nonobject records, invalid hashes and boolean focal
metadata. All photo records are checked before decoding. JPEG/PNG headers are
read first: max200M source pixels, max16M decoded pixels. PNG above16M is refused;
large JPEG uses the decoder's reduced1/8 path before allocation. EXIF auto rotation
is disabled for header/dimension consistency. Metadata reports original dimensions,
while feature extraction downsizes to1600 pixels. Other image encodings are explicit
unsupported input rather than guessed safe. Original archive bytes are never modified.

## Reproducible security checks

```bash
python -m venv /tmp/rf-security
/tmp/rf-security/bin/pip install -r tools/security/requirements-lock.txt
export RF_SECURITY_PYTHON=/tmp/rf-security/bin/python
pytest -q tests/test_capture_audit.py tests/test_security_evidence.py
python tools/security/scan.py tools --scanner-python "$RF_SECURITY_PYTHON" --requirements tools/requirements-real-benchmark.txt --suppressions tools/security/suppressions-v1.json --output /tmp/security-redacted.json
```

Bandit1.8.6, detect-secrets1.5.0 and pip-audit2.9.0 pins plus transitive pins are
committed. Static scan is limited to Python tools; it is not a Kotlin/C++ audit.
Dependency scan queries public PyPI package/version vulnerability records, never
photographs or source content. Secret verification over the network is disabled.
Only scanner/path/line/rule/severity or package/version/vulnerability IDs enter
the output; code snippets, secret values, detector hashes and subprocess stderr
are excluded. Missing tools, failed services, invalid JSON or incomplete analysis
return `NOT_RUN`/exit2. Findings return `FAIL`/exit1; zero findings and all three
executed checks return `PASS`/exit0. Tests actually execute external scanners
against eval/fake-key source and requests2.19.1; intentional fixtures must fail.

Suppressions are source-hash/path/line/rule specific, justified and expire on
2026-11-20. Existing benchmark assertions are analytic test gates and require
normal Python (never `-O`). Existing SDK/scanner subprocesses use checked argv
without shell evaluation. The keyword detector's public status-enum assignment
is a false positive. Every suppressed finding remains visible in the report;
changed source, new finding, invalid justification or expiration fails closed.
No blanket rule exclusion or automatic baseline acceptance is allowed.

## Other boundaries and retained gates

| Entry | Owner / required independent check | Current limit |
|---|---|---|
| Android import/EXIF/image buffers |011–018; decoder pixel/byte bounds, interruption tests | This host parser does not validate Android behavior |
| Calibration |027/031; finite intrinsics/distortion, same lens/profile identity | Included host calibration JSON is not calibration validity |
| GLB buffers/images/references |068 and090 adversarial bounded readers/validators | Malformed target assets must fail before GPU allocation |
| Feature/depth checkpoint |009/033/050; version/hash/count/CRC checks | Never trust stale or partial cache as completed model |
| JNI/native counts/exception boundary |003/046/053 and081/082 sanitizers/fuzz | Current unguarded allocation paths need owner integration |
| Export/provider/archive mutation |006/025/026/067/088; atomic staging/fault injection | Preserve originals and completed outputs on failure |
| Exported activity/permissions/backup |008/089/091; APK inspection and offline device run | Launcher export is expected; no network permission added |

No physical handset, installed release journey or complete-object qualification
was executed by096. Agent099 must bind current reports to exact candidate hashes;
a host scan cannot close device, JNI, runtime dependency or release signing gates.
