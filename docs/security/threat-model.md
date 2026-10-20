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
python -m pip install --require-hashes -r tools/requirements.lock
/tmp/rf-security/bin/pip install --require-hashes -r tools/dependencies/security-requirements.lock
export RF_SECURITY_PYTHON=/tmp/rf-security/bin/python
pytest -q tests/test_capture_audit.py tests/test_security_evidence.py
for lock in tools/requirements.lock tools/dependencies/qa-requirements.lock tools/dependencies/security-requirements.lock; do
  python tools/security/scan.py tools --source-sha EXACT_CLEAN_SOURCE_SHA --scanner-python "$RF_SECURITY_PYTHON" --requirements "$lock" --suppressions tools/security/suppressions-v1.json --output "/tmp/security-$(basename "$lock").json"
done
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

## Integrated host-tool review, October 2026

The integrated scan uses canonical paths relative to its selected scope (`tools`),
regardless of the working directory. It records the actual Git source SHA/dirty
state, count and aggregate SHA256 of selected regular source bytes, requirements
and policy hashes. `--source-sha FULL_SHA` requires the exact clean candidate.
Source bytes, Git identity, policy and requirements must stay unchanged throughout
the run; a race or mismatch returns NOT_RUN, even if child scanners succeeded.
Raw findings, active findings and reviewed exclusions retain separate counters
and rule/severity breakdowns. Scanner stderr/source text/credential detector hashes
remain absent from reports. Exclusions never imply that their rules were disabled.

The explicit integrated review covers generated artifact/POM/SBOM SHA256/SHA512
and GNU build IDs, immutable Git identities, npm package-content integrity,
public SDK license archive paths and a literal CMake build flag. They are public
content identities or fixed options, not authentication values. Each exclusion
binds the complete file bytes, exact relative path, line and rule; changed code or
evidence does not inherit it. Duplicate findings on the same line remain separate
in raw/suppressed totals. Review expires 2026-11-20.

Reviewed Python subprocess calls are desktop developer/CI orchestration with
explicit argv and shell=False/default: trusted Git/SDK/ADB/compiler/scanner,
fixed benchmark scripts, pinned Node/Khronos/Blender and bundletool. Explicit
operator-selected executables and `integrate --check` are authorized tool actions;
this is not a sandbox for adversary-provided executables or PATH. Photograph/model
bytes do not become host shell programs. These programs are not APK runtime code.

The XML exceptions refer to trusted local test, Gradle POM or SDK manifest output.
CI JUnit enforces its 64MiB bound before parsing; provider XML is generated by
seven pinned native-writer tests. APK/benchmark XML is trusted SDK serialization
of the selected build. ElementTree does not resolve external file/network
entities. Raw imported photo/project XML is outside those reviewed call sites.
No general permission to parse arbitrary project XML is inferred.

The three urllib sites retrieve approved license/QA artifacts using verified TLS
and content hashes/publisher checksums. Their exact HTTPS-only origin manifests
are bound through additional `context_sha256` file hashes; editing an origin
reactivates its finding even if the downloader source remains unchanged. No URL
is taken from a photograph or runtime project. Changed/new findings remain active.

Example exact candidate scan from any working directory:

```bash
python /path/to/checkout/tools/security/scan.py /path/to/checkout/tools \
  --source-sha FULL_FROZEN_SOURCE_SHA \
  --scanner-python /path/to/pinned-security-python \
  --requirements /path/to/checkout/tools/dependencies/qa-requirements.lock \
  --suppressions /path/to/checkout/tools/security/suppressions-v1.json \
  --output /private/evidence/security-qa.json
```

Audit the security-tool lock separately as well. Each report names its exact
requirements hash; one selected requirements scan is not an audit of other Python
locks, the Android dependency graph or native redistributables. JVM/native and
physical installed-candidate security gates remain separately required.


The updated workflow installs hash-verified095 resolved host/security locks and
independently audits the resolved ordinary, QA and security graphs. These files
are a required095 integration contract; an isolated096 checkout does not invent
copies. The old004 `tools/requirements*.txt` declarations still include vulnerable
pytest8.4.1 at the reviewed snapshot. Their separate actual FAIL evidence is
retained and request096-to-004 requires an owner pin update. Switching the active
workflow to a fixed resolved lock does not declare those legacy inputs fixed.
The095 QA/resolved lock upgrade itself must pass the actual advisory audit and
fresh environment tests before final candidate acceptance.


Requirement includes are not delegated to pip-audit's --disable-pip parser: it
silently omits nested `-r` inputs in2.9.0. The wrapper flattens at most64 bounded
local files/4MiB, permits only exact pins and optional SHA256 hashes, rejects
cycles/duplicate pins/ranges/options/escaping includes, binds every closure file,
and checks the returned audited package/version set exactly. An omitted package
or changed included file returns NOT_RUN. The retained pre-fix nested-input PASS
is an intentional counterexample; it must never qualify a candidate.


Suppression policy JSON is read once before external analysis, at most4MiB. The
recorded policy SHA256 hashes those same captured bytes, and only that parsed
object is applied to findings. Symlink ancestors are rejected for the policy
route; original route and bytes are rechecked before/after application. Transient
replace-and-restore cannot substitute a different policy during application.
Selected source-file identities use streaming SHA256, so an oversized included
requirements file inside tools cannot allocate its full size before closure
budgets run. Both route retargeting and parsed-byte substitution have negative
regressions. No candidate pass is inferred from a source- or policy-drift report.
