# Structural edge provenance: mixed-camera nonmanifold edges

Read-only analysis of the admitted fusion-telemetry baseline and rejected one-diagonal candidate, using the same frozen actual input snapshot. No geometry or production policy change.

| Exact indexed-edge result | Original | Rejected candidate |
|---|---:|---:|
| Triangles | 6,741 | 6,711 |
| Boundary edges | 4,873 | 4,876 |
| Nonmanifold edges (>2 incident faces) | 166 | 257 |
| Mixed native source-camera nonmanifold edges | 166 | 257 |
| Same-camera nonmanifold edges | 0 | 0 |
| Native degenerate faces excluded | 0 | 0 |

Every nonmanifold edge has observations from multiple cameras at both endpoints. Thus these particular indexed-edge defects combine triangles from different source cameras at shared vertices. This identifies a structural assembly symptom; it does not prove which fusion guard is wrong, that all interpenetrations share this mechanism, or that a proposed global field repairs it. Native camera references 0/1/2 are not guessed photo IDs. The candidate remains REJECT/HOLD: its earlier measured coverage fell and nonmanifold edges increased.

## Validation and limits

The original native `mesh_topology.hpp` supplies exact face eligibility and edge/component totals from bounded raw logical mesh fields. Decoding must roundtrip bit-exactly. The helper uses intersection budget zero solely to obtain eligibility/edge counts: no intersection predicate is called, and its crossing status is not a replacement for prior complete native counts (6,190 original / 5,742 candidate).

The Python reader validates observation sample-ID/camera/pixel joins, vertex supporting-view sets, and triangle camera support at all endpoints against admitted telemetry. Ordered indices and native scalar fields match receipt-bound native JSON. Exact native excluded face IDs are applied before edge classification. Both edge totals must agree with stored native/JSON totals. All 15 structural fixture groups passed. The actual run completed in 4.515 seconds with pinned inputs unchanged; see `findings.json` for aggregate histograms, proof hashes, command outcomes and receipt identity. Raw indexed rows remain external, not duplicated here.

No causal quality claim, alternative fuser, face deletion, new vertices, threshold change, or crossing rerun is part of this diagnostic. This result belongs to the current 6,190-crossing baseline lineage, not the earlier 6,137 provenance capture.

## Reproduction

The five source files are exact copies of the reviewed executed diagnostic. Place `snapshot.hpp` and `secure.py` beside them using the already published sources/hashes in `source-pins.json`. Provide receipt-bound original/candidate raw meshes, native JSON, admitted StageC sample rows, original engine and native headers. Machine-specific config/tool inventory and full raw meshes remain in private evidence; construct a new config for a new environment rather than claiming this source-only archive is a portable turnkey capture.

`run.py --config CONFIG --work FRESH_OUTPUT` checks named tool/source/artifact pins before and after, runs fixture tests, compiles one native eligibility helper, verifies original-engine linkage, then runs both classifications. The reviewed private recipe bounds aggregate runtime to 180 seconds, individual compile to 60 seconds, tests to 30 seconds, and each helper/classifier to 60 seconds, with 2 GiB per subprocess and process-group cleanup. It rebuilds no engine and uses no Gradle/network. Shared sources and private reviewed recipe hashes are in `source-pins.json`; full OS/linker provenance is not claimed.
