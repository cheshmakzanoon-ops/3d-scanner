# Bounded exact planar ownership — opt-in host QA only

This is a portable C++20 **QA counterpart**, not a production mesher, SurfaceMesh
adapter, binary64 mesh export, Android dependency, or repair of the translated
production-plane defect. It preserves the Python reference's stable-ID ownership,
convex subtraction, global boundary noding, centroid fans, exact original-corner
barycentrics, and winding. Each face is wholly inside **one** original source
triangle and all its corners are INTERPOLATED. Conflicting attributes are **not**
combined: external immutable metadata must remain keyed by retained source ID.

## Arithmetic and admission

The reviewed dependency is **Boost 1.86.0**, Boost Software License 1.0.
There is no runtime/build-time automatic network access and no vendored Boost tree.
Obtain the official archive and verify its publisher checksum before configuring:

```sh
curl --fail --location https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.bz2 -o boost_1_86_0.tar.bz2
echo '1bed88e40401b2cb7a1f76d4bab499e352fa4d0c5f31c0dbae64e24d34d7513b  boost_1_86_0.tar.bz2' | sha256sum --check
# Inspect/preserve LICENSE_1_0.txt alongside the headers.
tar -xjf boost_1_86_0.tar.bz2 boost_1_86_0/boost boost_1_86_0/LICENSE_1_0.txt
```

Publisher checksum: [official archive metadata](https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.bz2.json).
License: `boost_1_86_0/LICENSE_1_0.txt` in that verified archive. No Boost libraries
are built or linked; the isolated static QA target alone consumes these headers.
The header rejects any other Boost version at compile time.

`Integer` is `cpp_int_backend<4096,4096,signed_magnitude,checked,void>` with
expression templates **off**, not arbitrary-precision `cpp_int`/`cpp_rational`.
A small wrapper normalizes every rational using an explicitly charged Euclidean
remainder loop. Every arithmetic expression/intermediate is checked. Overflow,
including comparison cross-products, refuses the entire patch; an overflowed
Boost value is never reused. Checked intermediate width can reject a case the
Python reference accepts after normalization. This is conservative refusal, not
admission equivalence. Neither `long double` nor an epsilon substitutes for exact
predicates. Exact IEEE754 binary64 decoding handles every finite value, subnormal
and signed zero; nonfinite values refuse. Admission of a coordinate does **not**
guarantee the geometry derived from it fits the arithmetic budget. There is no
rational-to-float output conversion.

**Important allocator detail:** Boost's default fixed-backend Karatsuba path
allocates variable-precision scratch. The isolated target publicly defines
`BOOST_MP_KARATSUBA_CUTOFF=4097`, asserted in the header, making that path unreachable
for 4096-bit operands. Schoolbook multiplication and the fixed-backend division
path use bounded fixed storage. The generic Boost GCD, which can also allocate,
is not used. This definition must be consistent across all consumers of this QA
header; do not mix it with differently configured Boost translation units.

## Explicit resource/refusal contract

Limits may only be lowered from:

| Resource | Maximum |
|---|---:|
| Immutable sources | 32 |
| Retained/intermediate fragments | 512 |
| Output faces | 2048 |
| Integer numerator/denominator magnitude | 4096 bits |
| Charged work units | 200,000 |
| Vertices per clipped polygon | 128 |
| Collected node candidates / noded boundary vertices | 8192 |
| Internal PMR workspace | 64 MiB |

Node candidate admission counts duplicates **before** deduplication; it is a
conservative cap. Internal containers use a single heap monotonic arena with
`null_memory_resource()` upstream; reclaimed temporary space is not reused, so
arena exhaustion may refuse simple cases under reduced limits. The second large
heap allocation is fixed `sizeof(Output)` (39,269,920 bytes on the tested GCC
64-bit ABI), holding 32 source records and 2048 faces. Total owned bulk heap is
at most workspace + `sizeof(Output)`, about **101.5 MiB** on that ABI, not 64 MiB.
Both are heap allocations, not large thread-stack arrays. Automatic arithmetic
and geometry temporaries have fixed width and bounded algorithmic nesting;
standard-library sort uses bounded depth for the explicitly capped containers.
Input/callback storage, exception runtime bookkeeping and CLI/iostream transport
are outside the kernel's bulk-heap accounting. This is not a mobile-memory,
wall-clock latency, or OS allocator-overhead guarantee.

A work unit is a bounded rational operation/comparison, representation check,
GCD iteration, or candidate/container visit. One rational operation performs a
fixed small number of <=4096-bit primitive operations; Euclidean iterations are
charged separately. Comparisons used by sort and uniqueness are charged.
Cancellation is checked before bulk allocation, every 64 charged units, and
immediately before publishing. The binary64 decoder is separate and executes
at most 1074 fixed-width doubling steps. Arbitrary callback work is outside the
budget; callbacks must not mutate concurrently accessed input.

Typed input has exactly three 3D corners per source. IDs must be unique printable
non-space ASCII, 1–128 bytes (a narrower domain than Python Unicode IDs), ordered
lexicographically. Coordinates are explicit numerator/denominator pairs; negative
denominators normalize, zero denominators refuse. Output owns normalized exact
source coordinates and copied IDs. Noncoplanarity (including a one-ULP fold or
thin layer), opposite winding, degeneracy, invalid limits, caps, arithmetic or
allocation failure, and cancellation return UNCHANGED with a **null output**.
`discarded_faces` is a QA counter only, never partial geometry. Original input
remains untouched. Callback exceptions are isolated and propagate, even when
they have the same exception type used for internal overflow/allocation failures.

## Reproduction and evidence

From repository root (set the dependency path to your verified extraction):

```sh
cmake -S . -B build-exact-qa -DRF_EXACT_PLANAR_OWNER_QA=ON \
  -DRF_QA_BOOST_ROOT="$PWD/boost_1_86_0" -DCMAKE_BUILD_TYPE=Release
cmake --build build-exact-qa --target rf_exact_planar_owner_tests rf_exact_planar_owner_cli -j2
ctest --test-dir build-exact-qa -R '^native_exact_planar_owner_qa$' --output-on-failure
RF_EXACT_PLANAR_OWNER_CLI="$PWD/build-exact-qa/reconstruction/qa/exact_planar_owner/rf_exact_planar_owner_cli" \
  python -m pytest -q reconstruction/qa/exact_planar_owner/differential_qa.py \
  tests/test_coherent_planar_owner_reference.py
```

The differential harness is explicitly invoked, not added to default discovery.
Missing its executable is an error, not silently skipped coverage. It compares
exact native faces/ownership/barycentrics against the reviewed Python reference
and reuses the independent convex-intersection/inclusion-exclusion union oracle.
Tests cover holes with two boundary loops, disjoint/touching/overlap, global
T-junctions, 2^-60 slivers, the exact binary64 0.01 shift, oblique planes, reversed
winding, original-corner attribute transfer, deterministic/random fixtures,
malformed transport/inputs, all caps, early/late cancellation and checked overflow.
The CLI is only a bounded QA transport, not a native/Kotlin cache format.

The dedicated GitHub Actions lane builds only these targets and runs native
ASan/UBSan/LSan in ordinary standalone native test and CLI processes. The Python
oracle invokes that linked sanitized CLI without preloading sanitizer libraries
into Python, and separately checks the release CLI. Manifest verification, binary
hashes and linked sanitizer dependencies are retained with each run. The lane
has a 10-minute job cap, a 180-second sanitized differential cap and a 120-second
release differential cap; failures in one do not suppress the other result. Local LeakSanitizer under ptrace is not a pass and must not
be disabled to manufacture one. Release/UBSan host tests and this analytic
experiment do not qualify real captures, Android, binary64 output, noisy surface
reconciliation, production topology or application release readiness.
