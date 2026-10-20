# Corpus v1

Run `python -m tools.qa.corpus generate /tmp/rf-corpus/synthetic` and
`python -m tools.qa.corpus validate /tmp/rf-corpus/synthetic/manifest.json`.
The generated manifest is the complete runnable example. All paths are relative
and SHA256 binds exact bytes. Raw real images stay outside Git. `discover ROOT`
finds local manifests; `qualification ROOT --output receipt.json` always records
physical qualification as BLOCKED_EXTERNAL until downstream candidate quality,
held-out capture and device receipts exist. Exit codes: 0 valid, 1 invalid, 2
external acceptance blocked. No command accesses a network or uploads inputs.

Acquire real data only from an owner-authorized capture operator or a source
whose license explicitly allows this evaluation. Record the license identifier,
source URL or private archive identity, authorization, provenance and independent
redistribution boolean. Do not infer redistribution from permission to evaluate.
Copy originals into a private local dataset directory, record lens/settings and
same-lens calibration JSON (`lens_id`, `image_size`, finite positive `K`). Hash
all originals, calibration and reference with SHA256. Declare independent
reference acquisition method, provenance and units; candidate-generated meshes
cannot serve as truth. Use `kind: real`, `capture.physical: true`,
`split: development` or `held_out`; reserve held-out images before reconstruction
using photo `role: held_out`. Hold-out photo IDs must never be sent to an engine.
A manifest is operator attestation plus byte verification, not proof of license
legality, calibration accuracy or physical provenance.

Required corpus remains absent: two matte textured full 360-degree objects with
independent reference geometry; textured stone, shoes, figurines, wood, flat and
thin subjects; glossy, transparent, low-texture and moving rejection cases;
12/30/100/250-photo workloads; independent held-out photographs. Middlebury
stereo and generated fixtures cannot supply these full-object requirements.
Metric units may be m/mm only with separately justified scale verification;
`arbitrary` must never advertise metric measurements.


Incoming086 profile handoff `d79926797c19d0bb8d5479da0a91c740edf4e08a`
(`docs/agents/requests/086-to-083.md`) is implemented by
`python -m tools.qa.corpus.calibration PROFILE.json --root PRIVATE_ROOT
--lens-id EXPLICIT_LENS --photo ORIGINAL1 --photo ORIGINAL2 --output WRAPPED.json`.
The original app schema1/brown-conrady-5/exif-upright JSON stays unchanged and
content-bound through source_profile. The wrapper retains all five distortion
coefficients, exact fx/fy/cx/cy, source dimensions and an explicit coordinate frame.
The full original file also retains RMS, accepted-view and focal metadata.

Only EXIF1 (including absent orientation) with exactly matching raw dimensions is
supported: raw and upright coordinates coincide. EXIF2–8, unknown profile frames,
nonfinite values and implicit scale/resolution changes fail. Do not clear EXIF,
rotate/recompress originals or transpose K to evade that restriction.027/086 must
publish an independently tested orientation/rectification adapter before rotated
capture evaluation. Conversion does not rectify distortion or prove calibration
accuracy.085 pinhole evaluation still needs independently rectified held-out data.

Use WRAPPED.json as the manifest calibration file with its hash, same explicit
lens ID and operator provenance. Validation rechecks the original profile hash,
all photo frames, K and retained distortion; substituted/edited wrappers fail.
Evaluation/redistribution rights, independent reference, physical evidence, units,
and reconstruction/held-out roles remain independently required. The wrapper does
not construct or authorize them. Physical acquisition and rotated-photo
qualification remain NOT_RUN/BLOCKED_EXTERNAL; synthetic tests only verify the seam.
