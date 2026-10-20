cmake_minimum_required(VERSION 3.22)
project(RealityForge VERSION 0.19.0 LANGUAGES CXX)
find_package(ZLIB REQUIRED)
option(RF_SANITIZE "Enable host address and undefined behavior sanitizers" OFF)
option(RF_EXPERIMENTAL_VULKAN "Build the opt-in Vulkan cost-volume validation backend" OFF)
option(RF_REQUIRE_CAMPAIGN_031_060 "Require all reviewed campaign native providers and tests" OFF)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
add_library(realityforge SHARED
    reconstruction/src/core.cpp
    reconstruction/src/pnp.cpp
    reconstruction/src/multiview.cpp
    reconstruction/src/bundle_adjustment.cpp
    reconstruction/src/dense.cpp
    reconstruction/src/surface.cpp
    reconstruction/src/texture.cpp
    reconstruction/src/photometric.cpp
    reconstruction/src/cancellation.cpp
    reconstruction/src/depth_checkpoint.cpp
)
# Provider branches can land independently. The explicit final integration gate
# below requires every declared provider; standalone foundation remains buildable.
set(RF_CAMPAIGN_SOURCES
    reconstruction/src/intrinsics.cpp
    reconstruction/src/loop_closure.cpp
    reconstruction/src/depth_tiles.cpp
    reconstruction/src/depth_refinement.cpp
    reconstruction/src/matching_cost.cpp
    reconstruction/src/depth_consistency.cpp
    reconstruction/src/depth_cache_manifest.cpp
    reconstruction/src/progress.cpp
    reconstruction/src/surface_fusion.cpp
    reconstruction/src/surface_evidence.cpp
    reconstruction/src/mesh_topology.cpp
    reconstruction/src/mesh_cleanup.cpp
    reconstruction/src/texture_visibility.cpp
    reconstruction/src/texture_png.cpp
    reconstruction/src/uv_atlas.cpp
    reconstruction/src/texture_blend.cpp
)
foreach(source IN LISTS RF_CAMPAIGN_SOURCES)
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${source}")
        target_sources(realityforge PRIVATE ${source})
    elseif(RF_REQUIRE_CAMPAIGN_031_060)
        message(FATAL_ERROR "Missing reviewed campaign provider: ${source}")
    endif()
endforeach()
target_include_directories(realityforge PUBLIC reconstruction/include)
target_link_libraries(realityforge PRIVATE ZLIB::ZLIB)
target_compile_options(realityforge PRIVATE -Wall -Wextra -Wpedantic)
if(RF_EXPERIMENTAL_VULKAN)
    add_subdirectory(reconstruction/src/vulkan)
endif()
if(ANDROID)
    target_sources(realityforge PRIVATE reconstruction/src/jni.cpp)
    target_link_options(realityforge PRIVATE -Wl,-z,max-page-size=16384)
else()
    if(RF_SANITIZE AND CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(realityforge PUBLIC -fsanitize=address,undefined,float-cast-overflow -fno-omit-frame-pointer)
        target_link_options(realityforge PUBLIC -fsanitize=address,undefined,float-cast-overflow)
    endif()
    enable_testing()
    add_executable(rf_native_tests reconstruction/tests/core_test.cpp)
    target_link_libraries(rf_native_tests PRIVATE realityforge)
    add_test(NAME native_core COMMAND rf_native_tests)
    add_executable(rf_multiview_tests reconstruction/tests/multiview_test.cpp)
    target_link_libraries(rf_multiview_tests PRIVATE realityforge)
    add_test(NAME native_multiview COMMAND rf_multiview_tests)
    add_executable(rf_bundle_tests reconstruction/tests/bundle_adjustment_test.cpp)
    target_link_libraries(rf_bundle_tests PRIVATE realityforge)
    add_test(NAME native_bundle_adjustment COMMAND rf_bundle_tests)
    add_executable(rf_dense_tests reconstruction/tests/dense_test.cpp)
    target_link_libraries(rf_dense_tests PRIVATE realityforge)
    add_test(NAME native_dense_mvs COMMAND rf_dense_tests)
    add_executable(rf_surface_tests reconstruction/tests/surface_test.cpp)
    target_link_libraries(rf_surface_tests PRIVATE realityforge)
    add_test(NAME native_surface_mesh COMMAND rf_surface_tests)
    add_executable(rf_texture_tests reconstruction/tests/texture_test.cpp)
    target_link_libraries(rf_texture_tests PRIVATE realityforge)
    add_test(NAME native_texture_atlas COMMAND rf_texture_tests)
    add_executable(rf_photometric_tests reconstruction/tests/photometric_test.cpp)
    target_link_libraries(rf_photometric_tests PRIVATE realityforge)
    add_test(NAME native_photometric COMMAND rf_photometric_tests)
    add_executable(rf_stereo_warp_tests reconstruction/tests/stereo_warp_test.cpp)
    target_link_libraries(rf_stereo_warp_tests PRIVATE realityforge)
    add_test(NAME native_stereo_warp COMMAND rf_stereo_warp_tests)
    add_executable(rf_dense_selection_tests reconstruction/tests/dense_selection_test.cpp)
    target_link_libraries(rf_dense_selection_tests PRIVATE realityforge)
    add_test(NAME native_dense_selection COMMAND rf_dense_selection_tests)
    add_executable(rf_cancellation_tests reconstruction/tests/cancellation_test.cpp)
    target_link_libraries(rf_cancellation_tests PRIVATE realityforge)
    add_test(NAME native_cancellation COMMAND rf_cancellation_tests)
    add_executable(rf_depth_checkpoint_tests reconstruction/tests/depth_checkpoint_test.cpp)
    target_link_libraries(rf_depth_checkpoint_tests PRIVATE realityforge)
    add_test(NAME native_depth_checkpoint COMMAND rf_depth_checkpoint_tests)
    # Explicit registrations: no test source is globbed into the shipping library.
    # Release test binaries must retain assertions, including in legacy fixtures.
    function(rf_add_campaign_test target source)
        if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${source}")
            if(RF_REQUIRE_CAMPAIGN_031_060)
                message(FATAL_ERROR "Missing reviewed campaign test: ${source}")
            endif()
            return()
        endif()
        add_executable(${target} ${source})
        target_link_libraries(${target} PRIVATE realityforge)
        target_compile_options(${target} PRIVATE -UNDEBUG)
        add_test(NAME ${target} COMMAND ${target})
    endfunction()
    rf_add_campaign_test(rf_bundle_problem_bridge_tests reconstruction/tests/agents/003_bundle_problem_bridge_test.cpp)
    rf_add_campaign_test(rf_initializer_degeneracy_tests reconstruction/tests/initializer_degeneracy_test.cpp)
    rf_add_campaign_test(rf_pnp_robustness_tests reconstruction/tests/pnp_robustness_test.cpp)
    rf_add_campaign_test(rf_triangulation_conditioning_tests reconstruction/tests/triangulation_conditioning_test.cpp)
    rf_add_campaign_test(rf_intrinsics_tests reconstruction/tests/intrinsics_test.cpp)
    rf_add_campaign_test(rf_loop_closure_tests reconstruction/tests/loop_closure_test.cpp)
    rf_add_campaign_test(rf_depth_tiles_tests reconstruction/tests/depth_tiles_test.cpp)
    rf_add_campaign_test(rf_robust_local_objective_tests reconstruction/tests/agent_046_robust_local.cpp)
    rf_add_campaign_test(rf_depth_refinement_tests reconstruction/tests/depth_refinement_test.cpp)
    rf_add_campaign_test(rf_matching_cost_tests reconstruction/tests/matching_cost_test.cpp)
    rf_add_campaign_test(rf_depth_consistency_tests reconstruction/tests/depth_consistency_test.cpp)
    rf_add_campaign_test(rf_depth_cache_manifest_tests reconstruction/tests/depth_cache_manifest_test.cpp)
    rf_add_campaign_test(rf_progress_tests reconstruction/tests/progress_test.cpp)
    rf_add_campaign_test(rf_surface_fusion_tests reconstruction/tests/surface_fusion_test.cpp)
    rf_add_campaign_test(rf_surface_evidence_tests reconstruction/tests/surface_evidence_test.cpp)
    rf_add_campaign_test(rf_mesh_topology_tests reconstruction/tests/mesh_topology_test.cpp)
    rf_add_campaign_test(rf_mesh_cleanup_tests reconstruction/tests/mesh_cleanup_test.cpp)
    rf_add_campaign_test(rf_texture_visibility_tests reconstruction/tests/texture_visibility_test.cpp)
    rf_add_campaign_test(rf_uv_atlas_tests reconstruction/tests/uv_atlas_test.cpp)
    rf_add_campaign_test(rf_texture_blend_tests reconstruction/tests/texture_blend_test.cpp)
    rf_add_campaign_test(rf_photometric_adversarial_tests reconstruction/tests/photometric_adversarial_test.cpp)
    rf_add_campaign_test(rf_texture_c_abi_failure_tests reconstruction/tests/agent_057_c_abi_failure.cpp)
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/reconstruction/tests/texture_png_test.cpp")
    add_executable(rf_texture_png_tests reconstruction/tests/texture_png_test.cpp)
    target_link_libraries(rf_texture_png_tests PRIVATE realityforge ZLIB::ZLIB)
    target_compile_options(rf_texture_png_tests PRIVATE -UNDEBUG)
    add_test(NAME rf_texture_png_tests COMMAND rf_texture_png_tests
        ${CMAKE_CURRENT_BINARY_DIR}/agent057.png ${CMAKE_CURRENT_BINARY_DIR}/agent057-reference.png)
    elseif(RF_REQUIRE_CAMPAIGN_031_060)
        message(FATAL_ERROR "Missing reviewed PNG streaming test")
    endif()
    if(UNIX AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/reconstruction/tests/agent_046_tile_process.cpp" AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/reconstruction/tests/agent_050_process_recovery.cpp")
        # Compile the instrumented publisher into these process-kill executables.
        # The actual shared solver resolves its checkpoint calls to the executable
        # for deterministic stop points; shipping objects have no stop hooks.
        add_executable(rf_tile_process_tests reconstruction/tests/agent_046_tile_process.cpp
            reconstruction/src/depth_checkpoint.cpp)
        target_compile_definitions(rf_tile_process_tests PRIVATE RF_CHECKPOINT_TEST_HOOKS)
        target_compile_options(rf_tile_process_tests PRIVATE -UNDEBUG)
        target_link_libraries(rf_tile_process_tests PRIVATE realityforge)
        target_link_options(rf_tile_process_tests PRIVATE -Wl,--export-dynamic)
        add_test(NAME rf_tile_process_tests COMMAND rf_tile_process_tests)
        set_tests_properties(rf_tile_process_tests PROPERTIES TIMEOUT 120)
        add_executable(rf_checkpoint_process_tests reconstruction/tests/agent_050_process_recovery.cpp
            reconstruction/src/depth_checkpoint.cpp reconstruction/src/cancellation.cpp)
        target_include_directories(rf_checkpoint_process_tests PRIVATE reconstruction/include)
        target_compile_definitions(rf_checkpoint_process_tests PRIVATE RF_CHECKPOINT_TEST_HOOKS)
        target_compile_options(rf_checkpoint_process_tests PRIVATE -UNDEBUG)
        if(RF_SANITIZE AND CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
            target_compile_options(rf_checkpoint_process_tests PRIVATE -fsanitize=address,undefined,float-cast-overflow -fno-omit-frame-pointer)
            target_link_options(rf_checkpoint_process_tests PRIVATE -fsanitize=address,undefined,float-cast-overflow)
        endif()
        add_test(NAME rf_checkpoint_process_tests COMMAND rf_checkpoint_process_tests)
        set_tests_properties(rf_checkpoint_process_tests PROPERTIES TIMEOUT 120)
    elseif(UNIX AND RF_REQUIRE_CAMPAIGN_031_060)
        message(FATAL_ERROR "Missing reviewed process-kill checkpoint tests")
    endif()
    if(RF_EXPERIMENTAL_VULKAN)
        add_executable(rf_vulkan_contract_tests reconstruction/tests/vulkan_depth_contract_test.cpp)
        target_link_libraries(rf_vulkan_contract_tests PRIVATE rf_vulkan_depth)
        target_compile_options(rf_vulkan_contract_tests PRIVATE -UNDEBUG)
        add_test(NAME rf_vulkan_contract_tests COMMAND rf_vulkan_contract_tests)
    endif()
    foreach(target rf_native_tests rf_multiview_tests rf_bundle_tests rf_dense_tests rf_surface_tests
            rf_texture_tests rf_photometric_tests rf_stereo_warp_tests rf_dense_selection_tests
            rf_cancellation_tests rf_depth_checkpoint_tests)
        target_compile_options(${target} PRIVATE -UNDEBUG)
    endforeach()
endif()
