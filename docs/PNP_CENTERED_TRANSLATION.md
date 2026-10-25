# PnP centered-translation denormalization

The nonplanar eight-row DLT initializer fits a projective camera on centered,
normalized world points. Its linear block is then projected to a proper rotation.
The fitted centered translation must be retained until that rotation is known:

`translation = centeredTranslation / scale - rotation * worldCenter`.

Subtracting the unprojected linear block times `worldCenter` before replacing
that block with a rotation makes the returned rigid pose depend on an arbitrary
world origin. For `X' = X + d`, a correct pose must satisfy `t' = t - R*d`.
The former ordering instead introduced `(R - M)*d` into projected coordinates.
The planar initializer already applies its rigid rotation before subtracting the
world center and is unchanged.

This correction does not change eight-row sampling, the 450-hypothesis ceiling,
random seed, planarity thresholds, reprojection gates, minimum distinct support,
positive-depth checks, nonlinear refinement, row provenance or cancellation.
The PnP semantic policy changes so old frontier/sparse/downstream geometry cannot
be reused as if generated under the corrected initializer. Feature and pair-match
cache formats are unchanged; their identities and geometric verification still
apply before reuse.

Synthetic regression coverage tests origin/scale transformations, proper poses,
known-world reprojection, planar behavior, outliers and degeneracy. A private,
validated licensed-fixture cache replay also localized a rejection to the old
projection/denormalization ordering. Replay diagnostics are not original JNI-call
bytes, full three-camera registration, or a scanner quality pass. Existing HOST
failures remain historical failures; this source change needs independent review
and source-bound native/Android/runtime qualification before promotion.
