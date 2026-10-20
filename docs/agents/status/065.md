agent: '065'
branch: agents/065-mesh-lod
state: READY_FOR_INTEGRATION
base_commit: ad3029193f304c70931b94ea7a0df90b69d84b07
candidate_commit: 8a81ece56a4fb7e2e5a63e7d1e2599546a3e1892
native_candidate_commit: d71b496ece5d926bc4c69540cdd43714bd077f3e
interfaces_published:
- C10-derived-LOD
- parent-observation-index-sidecars
integration_requests:
- docs/agents/requests/065-to-004.md
- docs/agents/requests/065-to-009.md
- docs/agents/requests/065-to-053.md
- docs/agents/requests/065-to-055.md
- docs/agents/requests/065-to-057.md
- docs/agents/requests/065-to-058.md
- docs/agents/requests/065-to-067.md
- docs/agents/requests/065-to-069.md
- docs/agents/requests/065-to-084.md
- docs/agents/requests/065-to-085.md
- /workspace/campaign-061-080/combined/docs/agents/requests/080-to-094.md
- /workspace/campaign-061-080/combined/docs/agents/requests/080-to-053.md
acceptance_results:
- criterion: Supported reductions obey explicit displacement, topology, seam and work constraints; unsupported reductions
    are refused
  result: PASS
  command: RF_ASSET_CONTRACT_ROOT=/workspace/agent-work/061 RF_TOPOLOGY_CONTRACT_ROOT=/workspace/campaign-061-080/dependencies/055
    python -m pytest tests/test_agent_065_mesh_lod.py -q
  source_sha: 8a81ece56a4fb7e2e5a63e7d1e2599546a3e1892
  environment: GCC 14.2.0 C++20 -Wall -Wextra -Werror, Linux x86_64
  dataset_identity: explicitly synthetic open grid, closed octahedron, thin strip, UV seam and disconnected grids
  evidence: reconstruction/tests/agent_065_mesh_lod.cpp
  artifact_sha256: null
- criterion: A useful bounded mesh reduction produces actual derived geometry
  result: PASS
  command: same focused native executable, 20000 to 10000 triangle fixture, work limit 2000000, displacement limit
    3 arbitrary units
  source_sha: 8a81ece56a4fb7e2e5a63e7d1e2599546a3e1892
  environment: Host synthetic numerical regression, not phone profiling
  evidence: reconstruction/tests/agent_065_mesh_lod.cpp
  artifact_sha256: null
  dataset_identity: null
- criterion: Independent topology and GLB checks preserve boundaries, components, winding and buffer integrity
  result: PASS
  command: actual Agent 055 analyzeMeshTopology plus independent binary GLB decoding and Khronos gltf-validator 2.0.0-dev.3.10
  source_sha: 8a81ece56a4fb7e2e5a63e7d1e2599546a3e1892
  artifact_sha256: 72db135f71e3170ac2ddf9a2d48f04642bff3d52716c948d404d5305f033d902
  environment: Pinned owner 055 header at 47705706512d04da64fd5c090b2325d91d888aa1, unchanged in incoming 2c659b78b597f63988cb65e9cce9d44fc475bac3
  evidence: /workspace/campaign-061-080/065-gltf-validation.json
  dataset_identity: null
- criterion: LOD reuse/invalidation and actual export/renderer selection follow owner 009 identities
  result: NOT_RUN
  source_sha: 8a81ece56a4fb7e2e5a63e7d1e2599546a3e1892
  command: Integrate owners 004/009/057/058/067/069, then execute the real selected-variant path
  evidence: docs/agents/requests/065-to-009.md
  artifact_sha256: null
  environment: Managed Linux x86_64; JDK17.0.16+8, Gradle8.13, Kotlin2.1.21, SDK36, NDK28.2.13676358, CMake3.22.1;
    Android29 arm64-v8a/x86_64 cross-compilation; no device execution
  dataset_identity: null
- criterion: Independent real geometry and photographic texture qualification on supported assets
  result: NOT_RUN
  source_sha: 8a81ece56a4fb7e2e5a63e7d1e2599546a3e1892
  command: Owners 084/085/090 measure the final materialized variant on frozen eligible data and thresholds
  evidence: docs/agents/requests/065-to-084.md
  artifact_sha256: null
  environment: Managed Linux x86_64; JDK17.0.16+8, Gradle8.13, Kotlin2.1.21, SDK36, NDK28.2.13676358, CMake3.22.1;
    Android29 arm64-v8a/x86_64 cross-compilation; no device execution
  dataset_identity: null
- criterion: C16 combined app/test-APK compilation, JVM regression, lint, offline and16KB packaging with explicit
    external verification adapters
  result: PASS
  command: /workspace/.venvs/3d-scanner/bin/python /workspace/toolchains/gradle-cloud.py --init-script /workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/final.init.gradle
    :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug; /workspace/.venvs/3d-scanner/bin/python
    /workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/source/tools/check_apk.py /workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/source/app/build/outputs/apk/debug/app-debug.apk
    --sdk /workspace/toolchains/android-sdk --report /workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/apk-check.json
  source_sha: a18aa6eb6d8fac17a8db6f1b489f7f04ebb95502
  role_input_sha: e4ed1e369e38ee562b09e9879f2069fe4261915c
  artifact_sha256: 01c36bd21f5ccd563002f9fca3ecc28c3abe265b0092afe2875f4cb90e3585df
  environment: Managed Linux x86_64; JDK17.0.16+8, Gradle8.13, Kotlin2.1.21, SDK36, NDK28.2.13676358, CMake3.22.1;
    Android29 arm64-v8a/x86_64 cross-compilation; no device execution
  dataset_identity: Committed regression/unit fixtures and packaged Android artifacts; no physical scans or device
    accuracy qualification
  evidence: /workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/result.json
  evidence_id: c16-combined-android-a18
  test_apk_sha256: f61f0b99cfd1be04c10425f929d4eb70843450e23d9cbec72262c36963c4932a
  result_json_sha256: 02f9e1474c3545e68df72c8320a91c4aecd0f281d52b8b413da726686e9e4846
  jvm_tests:
    tests: 214
    failures: 0
    errors: 0
    skipped: 0
  lint:
    errors: 0
    warnings: 28
  packaging:
    internet_permission: false
    elf_libraries_16kb_checked: 16
    zip_alignment_16kb: true
    new_native_symbols_in_both_abis: 7
  verification_adapters:
    cmake:
      path: /workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/cmake/CMakeLists.txt
      sha256: 89f6bf6fe268e5d7539521a63564d8ef9ffed49111ba6e33f71258618ad058b6
    gradle:
      path: /workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/final.init.gradle
      sha256: 6b648ca0f3ff8f8fb28a499b6cf0a28b965f746f88cd4a4ef489d481f1c5e5fa
    owner004_app_build_script_import_fix:
      path: /workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/app-build.gradle.kts
      sha256: eb6e182ad0a2fa5e371a78f58de2ef6a540255e6734a59ac818192295c142d89
  adapter_scope: External owner004 URI/MessageDigest import fix plus CMake registration of seven native sources and
    exact Filament/gltfio1.56.0 dependencies. No committed source changed; this is not an unadapted standalone build
    pass.
  matched_code_proof:
    path: /workspace/campaign-061-080/c16-matched-code-proof.json
    sha256: cc903b80c527dc3c5959d84f7ec3d7b7c7e949b5c58440ec07eb469f1df492c7
    role_head: e4ed1e369e38ee562b09e9879f2069fe4261915c
    changed_non_doc_nonmanifest_paths: 4
    all_role_blobs_equal_combined: true
  limitations:
  - External verification CMake adapter registers seven native sources on owner004 realityforge target.
  - External verification Gradle adapter adds exact Filament/gltfio1.56.0 dependencies requested by owner069.
  - No source-set replacement, handwritten stubs or foreign worktree source injection is used.
  - Compilation and packaged symbol inclusion do not prove JNI/application routing or runtime invocation.
  - No instrumentation/device execution, physical accuracy, frame budget, accessibility or production readiness is
    claimed.
  - Original owner004 app/build.gradle.kts fails compilation because java DSL accessor shadows package qualifiers;
    external build script adds URI and MessageDigest imports and replaces exactly those two package references. Original
    immutable attempt remains FAIL in its separate result directory.
  native_packaged_library_evidence:
  - apk_entry: lib/arm64-v8a/librealityforge.so
    bytes: 2868680
    sha256: 2d0b0a200eeb7f8d61899dba9a9ed2123383d1ff61865a3e170b73991cfab82c
    verified_exported_symbols:
    - rf::asset::validate(
    - rf::asset::writeGlb(
    - rf::asset::writeObj(
    - rf::asset::writeStl(
    - rf::asset::writePly(
    - rf::asset::simplifySurface(
    - rf::asset::applyEdits(
  - apk_entry: lib/x86_64/librealityforge.so
    bytes: 2830504
    sha256: 1c3abdf4c213dc7a41262b84dbd8941883acfa1b273fe565ca27b30f6e100b88
    verified_exported_symbols:
    - rf::asset::validate(
    - rf::asset::writeGlb(
    - rf::asset::writeObj(
    - rf::asset::writeStl(
    - rf::asset::writePly(
    - rf::asset::simplifySurface(
    - rf::asset::applyEdits(
- criterion: Original immutable combined attempt without the owner004 app-build-script import verification adapter
  result: FAIL
  command: /workspace/.venvs/3d-scanner/bin/python /workspace/toolchains/gradle-cloud.py --init-script /workspace/campaign-061-080/final-validation/a18aa6eb6d8f/final.init.gradle
    :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
  source_sha: a18aa6eb6d8fac17a8db6f1b489f7f04ebb95502
  role_input_sha: e4ed1e369e38ee562b09e9879f2069fe4261915c
  artifact_sha256: null
  environment: Managed Linux x86_64; JDK17.0.16+8, Gradle8.13, Kotlin2.1.21, SDK36, NDK28.2.13676358, CMake3.22.1;
    Android29 arm64-v8a/x86_64 cross-compilation; no device execution
  dataset_identity: Committed regression/unit fixtures and packaged Android artifacts; no physical scans or device
    accuracy qualification
  evidence: /workspace/campaign-061-080/final-validation/a18aa6eb6d8f/result.json
  result_json_sha256: b91d66de19322cf513a7ff1d4e04e095a836660bb1938bf773abd6ab97e39b92
  failure: Owner004 build-script java DSL accessor shadows java.net.URI and java.security.MessageDigest package qualifiers;
    preserved original failure, external adapter only in separate qualifying attempt.
- criterion: Full combined Python regression suite; failures retained without deselection, xfail or skips
  result: FAIL
  command: /workspace/.venvs/3d-scanner/bin/python -m pytest tests -q --junitxml=/workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/full-pytest.xml
    --basetemp=/workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/pytest-temp
  source_sha: a18aa6eb6d8fac17a8db6f1b489f7f04ebb95502
  role_input_sha: e4ed1e369e38ee562b09e9879f2069fe4261915c
  artifact_sha256: 6ec42c7bb2def41274a07431aacbf70e5b24f508d3d946520d878d8bb126e789
  environment: Managed Linux x86_64; JDK17.0.16+8, Gradle8.13, Kotlin2.1.21, SDK36, NDK28.2.13676358, CMake3.22.1;
    Android29 arm64-v8a/x86_64 cross-compilation; no device execution
  dataset_identity: Committed regression/unit fixtures and packaged Android artifacts; no physical scans or device
    accuracy qualification
  evidence: /workspace/campaign-061-080/final-validation/a18aa6eb6d8f-buildscript-relative/pytest-result.json
  passed: 176
  failed: 7
  skipped: 0
  result_json_sha256: b938450ef682bec9ae1df65963cfb47134f48803f014fafb68ef20eb763846bf
  failed_cases:
  - tests/test_project_contracts.py::test_sparse_output_disclosed_in_ui
  - tests/test_project_contracts.py::test_capture_package_requires_explicit_destination_and_preserves_hashes
  - tests/test_project_contracts.py::test_m09_camera_projected_uvs_are_embedded_and_exportable
  - tests/test_project_contracts.py::test_photographic_viewer_uses_bounded_local_assets_and_on_demand_gl
  - tests/test_project_contracts.py::test_m18_depth_resume_is_input_identified_atomic_and_native_scoped
  - tests/test_project_contracts.py::test_m19_persisted_scan_evidence_and_local_export
  - tests/test_surface.py::test_android_mesh_is_shown_and_exportable_without_network_calls
  failure_classification: Six mapped legacy UI/lifecycle source-literal expectations require094/053 semantic migration;
    seventh M18 depth-resume identity expectation remains unclassified and requires094 review. No test weakened or
    failure converted to success.
  owner_requests:
  - /workspace/campaign-061-080/combined/docs/agents/requests/080-to-094.md
  - /workspace/campaign-061-080/combined/docs/agents/requests/080-to-053.md
- criterion: Required physical-device, manual accessibility/adaptive/locale, process-death and measured runtime acceptance
  result: NOT_RUN
  command: Run role-specific080/100 integrated device and manual acceptance matrix
  source_sha: a18aa6eb6d8fac17a8db6f1b489f7f04ebb95502
  role_input_sha: e4ed1e369e38ee562b09e9879f2069fe4261915c
  artifact_sha256: null
  environment: Managed Linux x86_64; JDK17.0.16+8, Gradle8.13, Kotlin2.1.21, SDK36, NDK28.2.13676358, CMake3.22.1;
    Android29 arm64-v8a/x86_64 cross-compilation; no device execution
  dataset_identity: Committed regression/unit fixtures and packaged Android artifacts; no physical scans or device
    accuracy qualification
  evidence: Prior required device/manual/owner gates remain open; Android build does not execute instrumentation.
remaining_work:
- Owner 004 registers mesh_lod.cpp and the actual native test target after C10 source registration
- Owner 009 assigns descendant identity from complete recipe, parent, UV/normal inputs, algorithm version and implementation
  hash
- Owners 053/057 compose parent vertex/face maps with observation provenance; 058/057 qualify any rebake
- Owners 067/069 consume real derived geometry through source-owner native wiring; 100 integrates compatible immutable
  commits
- Independent real-data geometry/appearance/interoperability qualification remains open
- Owner004 must adopt the tested build-script imports/native registration; owner069 Filament dependencies remain external
  verification adapters in this evidence.
- Owners094/053 must resolve the full combined pytest7 failures, including the unclassified M18 depth identity expectation,
  without weakening protected behavior.
- Required physical/manual/runtime gates remain NOT_RUN; compile/test-APK packaging does not establish instrumentation
  or device success.
external_blockers:
- Qualified physical complete-object geometry and photographic texture measurements are unavailable; synthetic bounds
  are not physical scan accuracy
- Agent100 must integrate tested source plus receiving-owner fixes; unadapted build and full pytest are not green.
next_action: Integrate exact tested implementation with receiving-owner build/persistence/executor/publication fixes;
  resolve preserved pytest failures and run outstanding role-specific device/manual gates. Receipt-only metadata delivery
  is not DONE.
role: Detail-preserving mesh decimation and LODs
campaign_base: ad3029193f304c70931b94ea7a0df90b69d84b07
implementation_commits:
- 983bf9e64c5b7e1b3b6445155694f975b89bcc83
- 500982de076f54b670b9021364a26a333aca6510
- c6e52e4f554665d9ca261f0a6b8100428526c1e3
- 9c4ba41536debc25f451651a97e87403fbc79509
- d71b496ece5d926bc4c69540cdd43714bd077f3e
- 8a81ece56a4fb7e2e5a63e7d1e2599546a3e1892
- e4ed1e369e38ee562b09e9879f2069fe4261915c
owned_paths_changed:
- SOURCE_MANIFEST.sha256
- docs/agents/contracts/065.md
- docs/agents/requests/065-to-004.md
- docs/agents/requests/065-to-009.md
- docs/agents/requests/065-to-053.md
- docs/agents/requests/065-to-055.md
- docs/agents/requests/065-to-057.md
- docs/agents/requests/065-to-058.md
- docs/agents/requests/065-to-067.md
- docs/agents/requests/065-to-069.md
- docs/agents/requests/065-to-084.md
- docs/agents/requests/065-to-085.md
- docs/agents/status/065.md
- reconstruction/include/realityforge/mesh_lod.hpp
- reconstruction/src/mesh_lod.cpp
- reconstruction/tests/agent_065_mesh_lod.cpp
- tests/test_agent_065_mesh_lod.py
prior_evidence_notes: 'Version `rf-constrained-endpoint-lod-v2` performs deterministic batched interior

  endpoint collapses. Boundary/seam identities stay locked. Link conditions,

  nonmanifold vertex/edge checks, duplicate-face checks and face-normal orientation

  constraints refuse unsafe reductions. Targets, vertex/UV displacement and work

  limits are explicit; no partially reduced result is returned on failure or

  cancellation. The algorithm never fills a hole or replaces the source master.

  A 20,000-face analytic grid reduces to 10,000 within the declared 2,000,000-work

  budget. Work units are algorithm bookkeeping, not a measured device time/RAM

  budget. Source endpoint colors, unit normals, confidence and support fields are

  preserved and explicitly tested; parent index sidecars retain observation lookup.


  At native candidate d71b496ece5d926bc4c69540cdd43714bd077f3e, the baseline CMake

  Release build, all 11 CTests and all 118 pytest checks passed with explicit C10

  and topology providers. The subsequent test-only candidate above adds attribute

  retention assertions and passed the same focused executable. ARM64 and x86_64

  NDK 28.2 compilation under `-Werror` and host ASan/UBSan passed for the new native

  implementation. This does not claim that an unregistered native module has run

  inside Android. The C10 C++ dependency is the published implementation at

  814437be7ca667f8bd6f95cf98cc949e2e14aaf7; standalone tests link platform zlib.


  The independent owner 055 diagnostics pass component/boundary counts, winding,

  degeneracy, duplicate, nonmanifold and definite-intersection checks on the small

  open/closed/disconnected fixtures without exhausting its budget. The GLB has

  24 actual triangles, correct bounds and -Z winding after glTF conversion, and

  passes Khronos with zero errors and zero warnings. These tests do not establish

  full-surface Hausdorff error or perceptual photographic error on real objects.


  Incoming 053/055 requests were read from Agent 100''s live index and consumed in

  parent-index sidecars and actual topology integration tests. The remaining

  source-owner adapters are concrete required integration work, not physical

  hardware excuses and not DONE. Result assetId stays empty until 009 registers a

  validated descendant. This receipt-only follow-up names the tested code; no

  release publication or integration into main is claimed. GitHub API requests

  returned Forbidden, so draft PR creation is unavailable; normal branch push and

  remote head verification work without extracting or replacing credentials.'
c16_evidence_scope: All changed non-doc/nonmanifest branch files match exact a18 blobs. This update changes only this
  receipt and the canonical staged-source manifest.
