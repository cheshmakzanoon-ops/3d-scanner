# Failed physical-disambiguation feasibility — 25 October 2026

**Stop: do not promote this kernel as an observed-surface fusion repair.**
The bounded OFF experiment accepts two distinct planes separated by 0.01 scene
units under its predeclared 0.02 discrepancy bound and emits an unobserved
midpoint (0.005 truth error). Common-mode +0.01 depth bias also passes unchanged.
Residual agreement is not evidence of one physical surface. These are failed /
ambiguous geometry controls, not fixed by passing implementation tests.

## What the small experiment established

An isolated projective-distance-field patch can avoid the existing nine-depth
normal bottleneck using admitted source triangles and camera rays. It does not
establish layer identity. The fixed 32³ lattice, interval whole-cell/whole-face
support tests, all-supplied-triangle witness vetoes, consistent tetrahedral
extraction, derived labels and complete-patch refusal use one **5,921,856-byte**
owned workspace. Neither production code nor default native/Python test discovery
is linked to this standalone QA target. No dependency or production cache/export
change is required.

| Synthetic observation | Measured result |
|---|---|
| Coincident flat planes | 3,136 derived faces; exact analytic error 0; area **1.764775 / 2**; 171/231 truth samples within 0.01 |
| Coincident slanted planes | 2,200 faces; max truth error **4.274e-5**, max normal error **0.119°**; area **1.565059 / 2.032240** |
| Distinct layers, separation 0.01 | **Failed disambiguation:** accepted midpoint, truth error 0.005 |
| Common-mode +0.01 bias | **Undetectable:** output retains the error |
| Separation at/above 0.02, foreground contradiction, opposed sheets | Refused; no partial output |
| Unknown boundaries / holes | Not capped; conservative missing area remains explicit |

Boundary exclusion explains smaller area; fewer faces/intersections are not a
quality gain. General rigid rotations alter axis-aligned lattice coverage.
Initial slanted extraction encountered a tiny ambiguous crossing and refused;
final code refuses unresolved-sign cells rather than perturbing geometry.

## Checks and source binding

- Base: `0df4a2df5e7a9db0410b9c19c593be622474e1c2`.
- Frozen `patch.cpp` SHA256:
  `6fc3ce7ba1feec9574985521b231e81520ec1ce5c172a298a00632793cc3dde9`.
- Frozen `patch.hpp` SHA256:
  `1e08021034d806342f4a2184d784cd52db30ea2cf5a7992c449516728210b863`.
- GCC 14 Release, strict warnings: standalone CMake build / **1 CTest PASS**;
  **20 explicit Python evidence checks PASS**. These test recorded expectations,
  including the failed physical-disambiguation outcome—not geometry acceptance.
- Independent reviewer used the exact frozen core bytes: general Y rotations
  0/.17/.37/.73, analytic error <=8.9e-16 on those planes; no duplicate faces,
  >2-face edges or inconsistent oriented incidence. Late cancellation after
  3,136 attempted faces exposed no output and released storage. Independent
  strict compile and 19 checks passed before the final additional failure-label
  assertion; core bytes did not change.
- Final ASan/UBSan/LSan attempt: **FAIL / unavailable leak qualification** after
  37.48 seconds: `LeakSanitizer does not work under ptrace`. Leak checking was
  not disabled. No sanitizer or full-repository/device qualification claimed.
- Independent review records remain local at
  `/workspace/shared/observed-patch-independent-review/`; bulky logs and binaries
  are not committed. No raw photos/maps are committed or uploaded.

## Bounded replay provenance characterization — no kernel invocation

`characterize_frozen_replay.py` reads only checksum-pinned, existing **different-
pixel public-image native replay** inputs. It does not run the kernel, export
geometry, or claim actual production-controller pixel equivalence.

The baseline has 2,527 original verified samples, 1,353 admitted triangles and
zero retained fusions. Every emitted vertex has a singleton direct Accumulator
ledger reference. 712/1,625 singleton coordinates differ from their original
sample coordinates by at most **8.881784197001252e-16** because of arithmetic;
these are not labeled bit-exact originals.

The first pair after **596 deterministic pair visits** is camera 0 samples
(1,7,8), camera 1 samples (1013,1021,1022). Selection stops there. Original sample
coordinates are recovered through the verified ledger, not inferred from export
indices. Every one of the 1,353 admitted source triangles is inspected with
outward-rounded projected bounds: **15 potential witnesses (6/6/3 cameras),
1,338 disjoint, zero truncation**. Nine witnesses, including the selected pair,
would need fresh face admission if replacing singleton coordinates with original
samples; this is not silently waived. Unmeshed depth pixels are not certified
free-space witnesses.

Independent review reran this exact provenance-only adapter (SHA256
`fbd9b1ac1f239f7177c2f71ebb383bf223a3dce7da05039fa9cfefaad8f43d6c`)
and confirmed all counts, conservative witness inclusion and the no-invocation
stopping decision. This is not approval of any reconstructed geometry.

**The replay kernel was NOT invoked; no replay geometry was created.** Work
stopped at the failed synthetic physical-disambiguation gate and unresolved
original-coordinate readmission, without a tolerance sweep or further repair.

Actual `0df` HOST artifacts still omit depth maps, full verified samples and
source observation ledger rows. Production-input feasibility remains unavailable.
The source lacks a shared uncertainty-aware surface representation; this small
experiment does not close that architectural gap or repair the reported mesh.
