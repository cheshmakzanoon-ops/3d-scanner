# Topology-only aliases — unqualified candidate

Based on `c445fefd54aa777fb19084348673bb1ad9be1815`. No new production HOST,
Android/device qualification, physical coverage or watertightness claim.

## Contract

The canonical vertex prefix and unique original observation/unattached rows stay
unchanged. `topologyAliasSources[i]` maps appended representation vertex
`canonicalVertexCount+i` directly to a canonical vertex. Every attribute is
copied bit-for-bit, including the already formed normal. Aliases are not new
samples, supporting views, confidence weights or measurements. Face order,
orientation, sourceCamera and ordered corner attributes remain unchanged.

Only disconnected links consisting of consistently oriented simple paths/cycles
are split. Duplicate link edges, branching and winding conflicts are unsupported.
All corners of any face incident to an unsupported star are protected; even an
otherwise eligible neighboring bowtie is left unchanged. Unsupported-star count
is separate from post-normalization invalid links: protected neighboring bowties
can make the latter larger. Pre/post counts use oriented multiplicity-aware links;
the existing source-grid loss diagnostic retains its original undirected meaning.

The operation plans against immutable input; it exposes no replayable external
plan. It validates complete component partitions, recomputes pre/post counters,
and commits only after cancellation and validation checkpoints. No face removal,
coordinate jitter, interpolation, hole filling or re-fusion occurs. Existing
median confidence remains canonical-domain; texture exposure sampling uses only
the canonical prefix for both its stride and sample loop.

## Consumers and identity

Geometry JSON schema 8 remains unchanged in vertex/triangle row shape. Observation
ledger 2 requires alias mapping, canonical count and separate pre/post/unsupported
counts; fusion 13 cannot reopen with stripped alias metadata. Native checkpoint 4
has a new magic/version, filename and fusion 13/provenance 2/topology 3 algorithm
domain. Existing checkpoints remain immutable. Depth and sparse identities are
unchanged; surface identity invalidates downstream products. Checkpoint validation
rejects direct-target violations, attribute drift, arbitrary connected-fan tears,
unsupported-face rewrites, observation references to aliases and forged counters,
including no-alias counters. Existing source-ID/pixel uniqueness checks remain.

PLY/GLB remain geometry exports, not complete source-ledger serialization. JSON
and the native checkpoint retain the alias semantics. Texture geometry uses
representation indices; exposure evidence uses canonical vertices. A controlled
2000→2001-vertex test preserves legacy/compact exposure and atlas pixels. Universal
byte-identical textures are NOT claimed: UV edge-index tie ordering may differ.
LOD remains explicitly unimplemented by the pipeline adapter. Public mesh cleanup
rejects nonempty alias mappings: deletion/copy after normalization would invalidate
component and provenance bindings. Existing compaction/cleanup runs only before
alias creation; no alias-unaware reindexing is permitted.

## Resources and refusal

Limits remain 40,000 vertices / 80,000 faces. Alias planning has bounded corner
adjacency and at most 64 incident faces/128 link nodes per source star. Existing
fusion locals are released before the normalization wrapper runs. Admission
counts retained mesh capacities, simultaneous candidate vertex/face/alias buffers,
`6*V+12*F+8192` scratch, with scratch evaluated at the final vertex count within 10 MiB. This is a
bounded geometry/normalization allocation contract, NOT process RSS. Checkpoint
admission separately includes dense points/samples plus alias validation scratch
inside its unchanged 10 MiB geometry budget; serialized file bound remains 12 MiB.
The preexisting encoded-buffer/working reserve is not a claim of 10 MiB total RSS.
Resource/cancellation failures never publish a partial normalized result.

## Evidence scope

`actual-0df-alias.json` is a representation-only test of the production PLY from
ZIP SHA256 `6c1eddc02b1999af70cbb550c2c1c65ec48263a90d78bea46c99f29aff2ffa8e`;
PLY SHA256 `e0a3bb5b79a8c0c6120fbe66591da553a5531d24d3e2a078bbe3875c1c72dc7a`.
It has 1618 canonical vertices/1296 faces and 80 simple bowties; aliases produce 1698
vertices and zero invalid links. The exact 558 definite intersection pairs,
3111 ambiguous pairs and 1,580 boundary edges remain. 110 ambiguous pairs lose
shared-index adjacency but remain reported as coincident/tolerance contacts.
This does not prove production ledger preservation or object completeness.

`frozen-ledger-proof.json` uses separately pinned public replay inputs, NOT the
production ledger: baseline/local6 retain 2,522/2,538 unique original accepted sample
identities, all attached/unattached rows, ordered face attributes/sourceCamera,
and evidence statistics exactly. They add 77/76 aliases ; 600/842 native intersections
remain unchanged. Input pins are included. These controlled proofs cannot replace
a source-bound production HOST run or independent physical-quality reference.

Native tests cover no-op fans/cycles, idempotence, permutations, mixed/adjacent
unsupported stars, bounds, stale partitions, bitwise attribute drift, counter and
checkpoint tampering, unique source rows and interruption at every test-routed
normalization checkpoint. Portable Kotlin tests are not Android compilation or
instrumentation. Independent review is required before integration/publication.
