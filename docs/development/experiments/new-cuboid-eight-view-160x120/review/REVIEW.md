# New fixed cuboid fixture independent review

Approved as a fixed NEW synthetic eight-view 160×120 fixture, not a historical three-view recreation, reconstruction result, or qualified render replay controller.

Verified all package and pre-render hashes; copied generator is byte-exact to `ba259b96d3fdcba8c304a8214764d0d70e51be80:tools/qa/visible_surface_baseline/generate.py`. Installed Blender/prlimit executable hashes match frozen config. Every PNG decodes to 160×120 RGBA, has valid chunk CRCs and expected SHA256, nonconstant actual pixels, entirely opaque alpha, and no textual metadata. Camera intrinsics match the generator: fx=fy=200, cx=79.5, cy=59.5, zero distortion. Exact decoded-pixel hashes are in verification.json.

Observation metadata contains only image identity/path/hash, intrinsic calibration and synthetic provenance. Rendered poses, mesh, ray witnesses and near/far settings remain explicitly evaluation-only; no reconstruction should consume those. Baseline, registration, dense solver and candidate are NOT_RUN. No fresh render was necessary or performed; no claim of original lost fixture identity.

Found historical wrapper gaps: post-exit log/time limits are not rechecked, and interruption during Popen construction can precede handle assignment and escape reliable child cleanup. Root chose fixture-only publication; README now explicitly marks original source/render.py HISTORICAL_EXECUTED_ONLY and not an approved reusable runner. Executed source/config/receipt and assets remain unchanged. A later safe rerender wrapper requires separate revision/review. These limitations do not alter the delivered fixed asset hashes or truth separation.

Reviewed docs-only manifest `19dc94cfb6dee7e95f2777f18acf1b974d212a19c588eed5bebe5b16fa0795f3`. No root edits, external writes, renderer rerun, or algorithm experiments.
