# C4: explicit agent 031–060 native registration

This receiving-owner followup registers the reviewed native implementations and host regression targets without importing temporary combined-test histories into the build-foundation branch.

`CMakeLists.txt` names every campaign source and test explicitly. Standalone foundation builds remain compatible while provider branches land. Final integration must configure with `-DRF_REQUIRE_CAMPAIGN_031_060=ON`; a missing declared provider/test is then a fatal configure error. The gate is required on the final combined host and Android source, rather than interpreting absent test targets as passes.

The host build retains assertions in Release tests. Process-kill fixtures compile the checkpoint publisher with `RF_CHECKPOINT_TEST_HOOKS` into the test executables; the tile-solver fixture exports its symbols so the real shared solver reaches those exact stop points. Shipping objects have no stop hooks. The production PNG target links the verified platform `ZLIB::ZLIB` dependency; the PNG regression links it directly and receives separate compressed/reference output paths.

`RF_EXPERIMENTAL_VULKAN` remains OFF by default. Enabling it registers the actual optional backend and its contract test. Software-driver execution is not physical GPU qualification. `RF_SANITIZE` includes address, undefined-behavior, and float-cast-overflow instrumentation for the host shared library and consumers.

Validation of the registration itself: standalone configure passes; strict campaign configure rejects missing provider sources; the combined all-provider configure passes; all 34 explicitly registered host tests pass with assertions enabled. Final source/APK/runtime evidence is recorded separately after the final provider freeze.
