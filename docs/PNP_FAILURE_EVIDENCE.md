# Bounded PnP failure evidence

This is diagnostic-only: no hypothesis, threshold, iteration, support, pose,
refinement, cancellation or acceptance rule changes. Successful camera JSON is
unchanged. The existing C++ `Error`, C ABI `{"error":"..."}`, JNI forwarding and
Kotlin `IllegalArgumentException` classification remain unchanged. Original
failure message prefixes remain intact; there is no new public API/schema.

Three existing rejection sites append a fixed ASCII suffix:

```
 [pnp1 raw=45 distinct=43 required=16 best=9 tested=450 valid=287 model=dlt planarity=0.012345]
```

This example is illustrative, not a measured run. Fields mean:

- `raw`: admitted input-row count, before exact XYZ+normalizedUV deduplication.
- `distinct`: distinct exact tuples; not statistically independent support.
- `required`: unchanged minimum `max(16, ceil(0.28*distinct))` (16 when fewer
  than16 distinct rows are rejected before hypotheses).
- `best`: greatest RANSAC inlier count, before consensus refits.
- `tested`: RANSAC iterations attempted; at most450.
- `valid`: hypotheses for which existing DLT/planar construction returned
  normally, not hypotheses independently proven correct.
- `model`: `dlt` or `plane` for the available supported pose, otherwise `none`.
- `planarity`: already-computed pose-initialization covariance ratio, rounded
  to six decimals, only when the available value is finite. It describes that
  initialization sample/support, **not an all-input scene-planarity diagnosis**.
- `final`: present only for the existing final-refinement consensus-loss
  rejection; reports its final inlier count. Its model/ratio describe that
  candidate, while `best` remains the pre-refit RANSAC best.

The insufficient-distinct-input rejection reports zero tested/valid/best and
`model=none`, without a fabricated default planarity. Likewise, no supported
RANSAC pose means `model=none`. Invalid input, cancellation, allocation and
other boundary errors do not acquire solver evidence.

Only bounded counters and fixed enum/numeric text are included: no coordinates,
feature/track/photo IDs, paths, arbitrary strings or observation arrays.
Classic locale/fixed precision prevent locale-specific numeric formatting.
The longest existing prefix is70characters; bounded counts (rows<=5000,
hypotheses<=450), enum `plane` and ratio `1.000000` keep the full longest message
at176characters, below existing `RegistrationAttempt.reason.take(200)`.
The shorter final-refinement prefix has room for its additional `final` count.

Native/C ABI tests exercise rejection evidence and unchanged success/invalid/
cancellation behavior. Kotlin tests execute the existing JSON decoder and
incremental registration reason propagation. JNI source forwarding is checked;
those tests are not JNI/Android runtime qualification. Historical cached failure
strings can lack the new suffix; absence is not evidence of zero hypotheses or
zero support. No geometry/cache identity changes are needed for diagnostic text.
