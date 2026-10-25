# Independent three-photo CPU sparse reference — 25 October 2026

**All three exact licensed images can register under the same estimated 88 mm
pinhole assumption.** This is a COLMAP capability comparison, **not a scanner
pass, physical ground truth, measured calibration, dense surface or mesh result**.
The source-bound scanner `f506d2f` result and the subsequent
[`369457833` HOST run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40203631482)
both registered 2/3 cameras and reported the same measured partial geometry:
220 sparse / 1,623 dense points, 1,032 mesh vertices and 884 triangles. The
fragmented partial mesh remains unacceptable; the ordering fix did not improve
this observed result. Later candidates require their own actual-controller
evidence. No reconstruction threshold is changed here.

## Measured reference

Official CPU PyCOLMAP **4.2.1**, NumPy 2.2.6, Linux x86-64; two CPU threads,
1600-pixel analysis edge, SIFT `max_num_features=3500`. That setting is **not a
strict output-row cap**: CPU COLMAP retains entire DoG levels and multiple
orientations. Actual feature counts differ from the scanner.

| Input/pair | Features or raw matches | Verified matches |
|---|---:|---:|
| 10.JPG | 4,979 features | — |
| 11.JPG | 5,431 features | — |
| 12.JPG | 5,677 features | — |
| 10–11 | 1,413 matches | 1,402 |
| 11–12 | 468 matches | 452 |
| 10–12 | 110 matches | 99 |

- Initialized **11–12**, then registered 10; no automatic initialization relaxation
  or structure-less fallback was observed in the retained log. Default initial
  minimum 100 inliers / 16° triangulation stayed unchanged.
- **250 three-view tracks / 750 observations**, but **235 unique observed XY
  locations per image**: orientation duplicates are not independent landmarks.
- Binary-model read-back and independent NumPy projection confirmed **750/750
  positive-depth observations**. Original-resolution residuals: mean **0.485063 px**,
  median **0.357742 px**, p95 **1.305606 px**, maximum **2.907886 px**.
- **76** final tracks have all three direct verified edges; **174** use connected
  two-edge support. Of those tracks, **168** lack the direct 10–12 edge. Do not
  compare all 250 against the scanner's initial both-anchor closed-cycle gate.
- **5.543 s**, peak resident memory **1,097,380 KiB** (about 1.05 GiB). These are
  one host's measurements, not handset resource guarantees or repeatability claims.
- No dense reconstruction, meshing, metric alignment or physical accuracy test.
  Sparse points cannot certify the existing scanner mesh.

## Frozen camera and algorithm differences

Each unchanged original is 4272 × 2848. Shared fixed PINHOLE intrinsics:
`fx=fy=88*hypot(4272,2848)/hypot(36,24)=10442.666666666666`,
`cx=2136`, `cy=1424` in COLMAP corner coordinates. The scanner uses pixel-center
coordinates, so the equivalent principal point there is `(2135.5,1423.5)` and
observed COLMAP coordinates subtract `(0.5,0.5)`. Lens distortion is unknown and
assumed zero; this is not a calibrated camera. Rounded resized heights can also
make scanner analysis focal values differ slightly from exact original scaling.

CPU SIFT/RootSIFT, grayscale conversion/resampling, detector selection, matcher,
anchor choice and track building differ from Android/OpenCV. COLMAP's default
ratio is 0.8 with cross-check; this is not the scanner's 0.67 reciprocal matcher.
No descriptors, matches, poses or masks from the scanner entered the reference.
The reader created orientation-derived gravity rows but no finite position prior;
`use_prior_position=false`, and no global gravity-based mapper was used.

Intrinsics stayed exactly fixed through registration and bundle adjustment.
World-to-camera poses use x-right/y-down/z-forward; centers are `-Rᵀt`, not `t`.
All coordinates retain arbitrary gauge/scale. Database verified-pair qvec/tvec
were absent: exported relative poses in the local comparison are from final
bundle adjustment, not pair-verification poses. E/F/H and original keypoint IDs
remain available in the private SQLite database.

## Reproduce privately and offline

Use [`tools/qa/skull_reference.py`](../../../tools/qa/skull_reference.py) with
**separately installed official CPU PyCOLMAP 4.2.1** and NumPy 2.2.6. The runner
never installs software, downloads assets, uploads data or invokes the scanner.
It refuses wrong input/license hashes, symlink paths, repository-local data,
existing output directories, wrong PyCOLMAP/NumPy versions and CUDA builds.

```bash
/path/to/reference-venv/bin/python tools/qa/skull_reference.py \
  --input /path/to/private/licensed-originals \
  --output /path/to/private/fresh-reference-evidence
```

The input folder may contain other files, but only exact 10.JPG, 11.JPG, 12.JPG
and the pinned source README are admitted. Originals remain unchanged. Output is
private: original copies, logs, keypoints/database, poses and sparse models must
**not** be committed or automatically published. Keep attribution with derivatives.

The supervisor enforces 300 seconds for its entire worker and kills its process
group on timeout. SIGINT/KeyboardInterrupt and SIGTERM also kill and reap only
the supervisor-owned worker process group, then record `INTERRUPTED` with exit
130/143. Signals during process creation are deferred until its PID is owned;
handlers only record a flag, and waits check it at most every 250 ms. Signals
cannot unwind OS-reap bookkeeping before `Popen.returncode` is set. Repeat
signals cannot interrupt cleanup, and prior signal handlers are restored.
No external PID or process-name kill is accepted. The mapper has its own 120-second ceiling; worker virtual memory
is bounded at 4 GiB and individual files at 32 MiB. CPU threads are fixed to two.
`min_model_size=2` retains incomplete outcomes rather than silently dropping them;
it does not declare two-view reconstruction successful. A complete reference
requires three registered images and nonempty positive-depth observations.

`plan.json` freezes complete options before reconstruction; `effective-mapper.json`
records constant camera IDs; `matching.json`, `receipt.json` and `execution.json`
record actual results, independent projections, connectivity, resource use and
exit status. A failed, interrupted or missing receipt is not a pass. The runner
retains library-produced model files for read-back. Numeric counts may vary across
platforms or scheduling; the historical measurement above is not hard-coded as
expected output. Focused tests cover harness behavior, not scanner quality. The packaged runner was
then executed on the same pinned originals: it reproduced the registration,
feature/match counts and connectivity above, at 4.726 s / 1,101,048 KiB.
This second execution is retained separately; it is not the original measurement.
Historical reconstruction receipts retain their original runner hashes; subsequent
supervisor-only interruption cleanup is verified with bounded synthetic processes,
not relabeled as another execution of the licensed reconstruction.

## Licensed input and official tool provenance

["Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background" #10, #11,
#12](https://gitlab.com/photogrammetry-test-sets/skull-cameramoves-flash-no-background)
by [alansartlog](https://alansartlog.com),
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Pinned GitLab project 8724498 commit:
`0f31c229ac289a615b3ae981a5f584856bb24c47`.
Originals and license README were hash-verified before and after reconstruction:

| File | Bytes | SHA-256 |
|---|---:|---|
| 10.JPG | 3,086,546 | `9f034fe4bac3fa57ce6df66f949db69709b27e16e25ee31dd4358233d441dc5e` |
| 11.JPG | 3,124,295 | `dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c` |
| 12.JPG | 3,138,412 | `f771973ad8f3092d6e869d29c4cb88a0ad67d30c6cf03dfe9dce70c98cdd9bb1` |
| README.md | 1,205 | `01e8bfdc1aafda11e61fb13fb6b05b3701349f3820a9f04fc0c744700e2363e8` |

The fixture is a previously selected partial arc, not held-out or full-object
capture. Resampling, extracted observations, estimated cameras and reconstruction
are changed derivative representations, not author-provided models. No endorsement.

[Official PyCOLMAP documentation](https://colmap.github.io/pycolmap/index.html)
links to its [PyPI distribution](https://pypi.org/project/pycolmap/4.2.1/).
The 38.2 MB CPython 3.12 Linux wheel
`pycolmap-4.2.1-cp312-cp312-manylinux_2_28_x86_64.whl` has SHA-256
`711cef7bd0d30326a5d75967043371c98667f24db305b8cd5cd19339d02a7073`,
verified against PyPI's `colmap/colmap` trusted-publisher attestation at upstream
commit `bd1fcf654d2dd8fefa1466999c190a246f83f4b9`.
The script checks runtime version/CPU capability; it does **not** independently
attest an arbitrary preinstalled binary. Verify downloaded wheel provenance
separately before installation. The local measurement retains that evidence.

[COLMAP's BSD license](https://github.com/colmap/colmap/blob/4.2.1/COPYING.txt)
is separate from dependency licenses. This tool-only experiment adds no Android
runtime dependency or redistribution approval. Coordinate conventions are in the
[official database format](https://colmap.github.io/database.html); feature-count
behavior is in [upstream SIFT source](https://github.com/colmap/colmap/blob/4.2.1/src/colmap/feature/sift.cc).
