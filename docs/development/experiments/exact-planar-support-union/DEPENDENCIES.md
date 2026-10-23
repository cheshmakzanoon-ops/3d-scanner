# QA dependencies and primary references

No third-party implementation source is copied into the Android/native app.
This isolated Python feasibility experiment calls:

- Shapely2.1.2, BSD3-Clause. Installed from official PyPI; license preserved as
  `licenses/SHAPELY-BSD3.txt`. [Project](https://shapely.readthedocs.io/).
- Its bundled GEOS binary, LGPL2.1. License preserved as
  `licenses/GEOS-LGPL2.1.txt`. [Project](https://libgeos.org/).
- NumPy2.2.6, existing QA numerical dependency.

No GPL/AGPL implementation is copied or proposed for production. The supplied
native adapters are original, small consumers of the existing engine API. This
package contains no vendor binary, photograph, or generated mesh.

Primary references informed architecture, not copied code:

1. Turk and Levoy, *Zippered Polygon Meshes from Range Images*, SIGGRAPH1994,
   [author publication page](https://lightfield.stanford.edu/papers/zipper/).
   Overlapping measured meshes require explicit shared surface construction,
   not simply concatenating independently triangulated grids.
2. Curless and Levoy, *A Volumetric Method for Building Complex Models from Range
   Images*, SIGGRAPH1996,
   [author paper](https://graphics.stanford.edu/papers/volrange/paper_1_level/paper.html).
   Volumetric integration is a larger alternative; its gap-filling behavior is
   not automatically appropriate to this application's unknown-surface contract.
3. [Shapely constrained Delaunay API](https://shapely.readthedocs.io/en/stable/reference/shapely.constrained_delaunay_triangles.html).
   Constrained polygon triangulation retains polygon edges; unconstrained
   triangulation can cross support boundaries. The experiment therefore uses
   noded polygon cells and constrained triangulation, not a convex hull.

The arithmetic agreement check is not calibrated physical noise estimation.
This research code provides no production port, license-integration review,
Android resource qualification or native cooperative cancellation guarantee.
