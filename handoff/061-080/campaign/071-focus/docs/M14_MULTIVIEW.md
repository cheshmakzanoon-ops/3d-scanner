# M14 — bounded eight-view dense reconstruction backend

This increment extends the **native** depth/surface/photo-atlas contract from four
to eight calibrated views. Android UI activation is a separate next increment.
It does not raise the sparse camera-registration cap, claim a complete object,
or weaken the photometric, reciprocal camera-z, normal, or visibility gates.

## Implementation and resource bounds

For two to four views the source list is exactly the previous all-other-views
list. For five to eight views, calibrated frustum overlap at three depth samples,
relative heading and baseline select at most three source images per reference.
This scheduling score is **not measured object coverage**. All selected reference
views receive a depth map, including unsupported ones (which may stay empty).
Only one reference and its selected sources have cached luminance at a time.
All camera-z maps and bounded input RGBA remain in memory; this is **not** disk
streaming or resumable large-scene reconstruction.

A preflight guard caps the entire sampled grid at 100,000 pixels; larger jobs
must lower resolution or increase stride rather than silently favor early views.
Up to 100,000 reciprocal-verified samples can enter fusion. Point exports remain
capped at 20,000 by default, mesh exports at 40,000 vertices / 80,000 triangles.
The source-selection map, per-reference confirmed pixel counts and peak cached
luminance bytes are returned in diagnostics. Counts describe actual solver data,
not full-object completeness. Mesh/output caps still limit large scenes.

Eight-camera exposure estimation uses a seven-variable bounded solve, not the
old three-variable buffer. Triangle source masks retain all eight bits. The
photographic atlas uses three columns for five to eight photos and retains the
existing two-column layout for smaller sets. Its 4.5-million-pixel budget matches
the existing in-app model loader. Larger photo sources are rejected explicitly
at the texture stage; accepted depth and untextured mesh products remain usable.

## Executed measurements

`python tools/benchmark_multiview_coverage.py --gate` renders eight known cameras
viewing a textured plane and measures on the **same fixed world grid** for both
three- and eight-view runs. The depth solver never receives the reference plane.
This tests depth/fusion given known poses, **not** recovered camera alignment.

On the authoring host, 476 / 816 eligible grid cells were covered with three
views and 816 / 816 with eight views, a 1.71x ratio for this fixture. Median
absolute plane-depth error was about 0.0202 and 0.0196 arbitrary units,
respectively. Mesh counts were 1,769 vertices / 3,507 triangles versus 3,161 /
6,675. The eight-view cached luminance peak was 126,464 bytes (four 104x76 planes)
rather than eight planes. These are **synthetic** fixture measurements, not
physical scanning quality, device RSS, or camera performance.

The existing real Middlebury stereo benchmarks were rerun without changing
inputs: 3,530 textured triangles, 2,454 mesh vertices, median disparity error
0.47948 source pixels. The real dataset is still a calibrated stereo pair.

Native and Python regression tests cover capped/deterministic source lists,
disjoint/opposing cameras, no texture, masked last view, invalid workloads,
all eight exposure variables, valid UV atlas exports and unchanged small-view
planning. Android instrumentation adds an eight-image JNI-to-GLB exercise;
its result must be checked in the commit-specific CI run.

## References and limitations

The source-count/cache-size tradeoff is consistent with COLMAP's published dense
reconstruction guidance (https://colmap.github.io/faq.html). The implementation
here is first-party bounded plane sweep, not COLMAP/PatchMatch or a Ceres port.
Image lifetime rules follow Android's bitmap memory guidance
(https://developer.android.com/topic/performance/graphics/manage-memory).

Still required: physical full-object captures; robust incremental SfM beyond the
anchor intersection; per-view depth priors; checkpoint/resume and native
cancellation; coarse-to-fine geometry; non-overlapping manifold fusion; compact
UV charts and texture blending; store-ready release validation. Passing these
regression tests is not a claim that the app is complete or perfect.
