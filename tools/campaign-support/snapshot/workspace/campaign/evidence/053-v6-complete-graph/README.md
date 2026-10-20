The immutable v6 implementation reaches the complete eligible graph's maximum
matching of 50; the existing strict `fusedObservations > 50` assertion therefore
conflicts with the fixed conservative evidence rule on this fixture. This is an
internal quantitative acceptance conflict, not a physical-hardware blocker, and
is not a claim of DONE or release qualification.

`graph.cpp` exhaustively checks every usable two-camera original-observation pair,
then applies prospective centroid checks, and independently computes maximum
bipartite matching. It also executes the actual public `fuseDepthSurfaces` function.
The source uses production double footprint arithmetic/current bounded helpers,
actual float confidence threshold, each anchor's own centroid radius, original
pixel locations and matching source-order/weight arithmetic.

All numerical input bytes are preserved in `input.bin`; `receipt.json` records
camera calibration, settings, exact commits, hashes and interpretation. The tail
contains coarse images/calibration from the input capture but is explicitly unused
by this plain call. There is no ground-truth disparity in solver input. The saved
native library and source manifest bind the proof to commit
`5ef93abd39b46778ec57c51677c2ba5c6efdcb9f`.

Run `bash reproduce.sh /path/to/checkout-at-that-exact-commit` with GCC14.2. The
script checks captured hashes, compiles the diagnostic and loads the captured
library. Original executed command was:

```
g++ -std=c++20 -O2 -DNDEBUG -Wall -Wextra -Wpedantic -Werror -I/workspace/agents/053/reconstruction/include /tmp/agent053-v6-complete-graph.cpp -L/workspace/agents/053/build/host -Wl,-rpath,/workspace/agents/053/build/host -lrealityforge -o /tmp/agent053-v6-complete-graph
/tmp/agent053-v6-complete-graph /tmp/rf046-corrected-pixel-fixture.bin
```

The corrected independent evaluation retains the unchanged accuracy/geometry
thresholds. No metric, weight, search rule or count assertion was changed to force
an additional association. No source archive is claimed here; final dual source
archives await the coordinator's final immutable freeze.
