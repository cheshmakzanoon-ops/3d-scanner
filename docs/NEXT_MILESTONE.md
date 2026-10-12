# Next gate after M12 — complete-session reliability and measured scan quality

M10 corrected face normals and glTF UV orientation and reduced exposure mismatch.
M11 adds bounded local photographic GLB inspection and real shader/lifecycle tests.
M12 adds revision/operation safety and a complete synthetic Android session test.
None of these results establishes physical full-scene scanning.

1. Exercise a complete import → reconstruction → photographic inspection →
   reopen/export session, with malformed profiles, process death, cancellation,
   storage shortages and changed inputs. Preserve originals and never publish a
   partly complete result. Add exact regressions for every observed defect.
2. Collect authorized physical ARM64 capture bundles of at least two matte
   textured objects, 12–40 photos, a same-lens checkerboard profile, independent
   reference geometry and actual peak RAM/thermal/battery measurements.
3. Remove the three-low-resolution-view bottleneck using benchmarked incremental
   reconstruction, visibility/coverage selection, checkpointing and memory bounds.
   Measure completeness, not only retained-point error on one stereo pair.
4. Improve nonmanifold/overlapping triangle diagnostics, conservative cleanup,
   compact atlas charts and blending. Do not invent unseen surfaces or silently
   close genuine openings. Add mesh optimization/LODs and further export formats.
5. Build the production model studio and release workflow only after the above
   geometry, appearance and device evidence is available. The Filament studio,
   store-ready AAB, signing, accessibility, localization and external beta gates
   from PROJECT_PLAN.md remain open.

No claim of perfection or release completeness follows from compiler success,
synthetic rendering tests, or isolated rectified-stereo reconstruction results.
