# Licensed numeric PnP regression fixture

`centered-translation-45.json` contains exactly 45 `(X,Y,Z,u,v)` rows and the
unchanged normalized reprojection threshold `5.113144661933157E-4`. It contains
no images, image metadata, descriptors, database, cache payload or supplied pose.
Coordinates are derived observations/estimated sparse points, **not ground truth**.

## Attribution and transformations

Derived from [Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background](https://gitlab.com/photogrammetry-test-sets/skull-cameramoves-flash-no-background)
by [alansartlog](https://alansartlog.com), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Source GitLab project 8724498, commit `0f31c229ac289a615b3ae981a5f584856bb24c47`,
images 10.JPG, 11.JPG and 12.JPG. No endorsement is implied. Changes include
resampling, feature observations, estimated sparse geometry and conversion to
this numeric-only test fixture. The derivative fixture remains CC BY 4.0.
See `docs/development/experiments/skull-colmap-reference.md` for original byte
hashes and license README verification; no source photographs are redistributed here.

Extracted without tuning or row filtering from validated `measurement/pnp-replay.json`
of source `4b23941e8424b59ba2056f54c18a3b4bfff634bd`,
[run 40217424869](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40217424869).
Replay bytes SHA-256: `d7dcb3daa08de6be743da959d05e19599ce7aa2ae01cc3b20c8f5f9a89808361`.
Replay binary-double rows SHA-256: `9c36eb4c0587023c845ed3a71545bcadd98ef19109820b8ed13ae9182721cfe7`.
This JSON asset is 4,223 bytes, SHA-256
`661a975f2bebcd509538ad9f7507f86c8cf63170b9f4c67714ac99e4d6bdd12a`.
Its rows and threshold are copied verbatim as numeric values from that replay;
additional replay/cache provenance and historical failure text are omitted.

## Scope

The Android test calls actual cancellable JNI camera registration on these rows
and on a bounded world-origin translation `(1,-0.5,2)`, each at the original
threshold. It requires at least 40/45 distinct reported inliers, strict measured
reprojection/positive depth for every reported row, a proper rotation, and
`t' = t - R*d` within `1e-5`. It does not require an identical hypothesis count
across ABIs. All native calls own one test-scoped cancellation lease released in
`finally`. The asset's exact hash, closed schema, row shape and finite bounds are
checked before JNI.

This is a numerical regression on a fixed previously selected partial arc, not
image extraction, a held-out benchmark, complete reconstruction, UI/SAF/graphics,
physical quality, metric accuracy, performance or ARM64 qualification. An ATD
pass remains supplementary non-UI Android x86_64 evidence only.
