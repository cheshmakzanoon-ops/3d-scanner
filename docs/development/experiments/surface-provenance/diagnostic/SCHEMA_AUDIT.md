# Static schema-width audit

Compared external decode against native surface_checkpoint.cpp encode and load (native load lines378–460), and declared fields dense.hpp / surface.hpp. v2 changes only dense median confidence width. No diagnostic rerun yet.

| Serialized group | Width/order | External v2 |
|---|---|---|
| Magic/schema/length |8 bytes, u32,u32|match|
| width,height,cameras,vertices,triangles,points,samples |7×u32|match|
| depth identity; geometry,algorithm,maps digests |64 bytes;3×32 bytes|match|
| settings minimum confidence, depth jump, edge factor |f32,f64,f64|match|
| max vertices,max triangles,stride |3×u32|match|
| dense sampled,photometric,consistent |3×u64|match|
| dense median confidence;point budget |f64,u32|**v2 corrected f32→f64**|
| mesh input,accepted,rejected cells,rejected triangles,fused |5×u64|match|
| mesh median confidence |f32|match|
| each camera source count,source indexes,consistent count |u32,N×u32,u64|match|
| each point xyz,color,confidence,consistent views |3×f64,3×u8,f32,u8 =32 bytes|match|
| each sample xyz,color,confidence,views,camera,x,y,normal,normal confidence,normal views |3×f64,3×u8,f32,u8,u8,u16,u16,3×f64,f32,u8 =66 bytes|match|
| each vertex xyz,normal,color,confidence,supporting views |6×f64,3×u8,f32,u8 =56 bytes|match|
| each triangle a,b,c,source camera |3×u32,u8 =13 bytes|match|
| observation count |u32|match|
| each observation vertex,camera,x,y,sample ID |u32,u8,u16,u16,u64 =17 bytes|match|
| topology boundary,components,nonmanifold,intersections,ambiguous,removed defects |6×u64|match|
| topology incomplete |u8|match|
| settings voxel occupancy,fusion candidates |2×u32|match|
| dense completed,total,working bytes,resumed,recomputed,written,rejected,coarse,propagated,sparse,normal evaluated,normal refined,normal supported,normal rejected,normal unknown |15×u64|match|
| checksum |32 bytes SHA256 excluded from reader|match|

All vector components are doubles (Vec3), not declared PLY float. They are serialized untouched for exact byte-binding, then only validated PLY text is parsed as float for exported-coordinate analysis. Confidence helper now takes double so the dense confidence bound is checked without narrowing; float inputs widen exactly.

The first frozen run's independent analyzer reports 6137 crossing pairs, 33885 ambiguous pairs, **4768 implicated faces** (not total faces), 718870 candidate pairs, no exhausted budget. PLY header gives **5746 vertices,6734 total faces**. No checkpoint provenance was emitted during that failed run.
