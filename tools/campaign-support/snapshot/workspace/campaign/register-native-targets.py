from pathlib import Path
p=Path('/workspace/agents/004/CMakeLists.txt')
s=p.read_text()
old='add_library(realityforge SHARED reconstruction/src/core.cpp reconstruction/src/pnp.cpp reconstruction/src/multiview.cpp reconstruction/src/bundle_adjustment.cpp reconstruction/src/dense.cpp reconstruction/src/surface.cpp reconstruction/src/texture.cpp reconstruction/src/photometric.cpp reconstruction/src/cancellation.cpp reconstruction/src/depth_checkpoint.cpp)'
sources=['core','pnp','multiview','bundle_adjustment','dense','surface','texture','photometric','cancellation','depth_checkpoint','intrinsics','loop_closure','depth_tiles','depth_refinement','matching_cost','depth_consistency','depth_cache_manifest','progress','surface_fusion','surface_evidence','mesh_topology','mesh_cleanup','texture_visibility','texture_png','uv_atlas','texture_blend']
new='add_library(realityforge SHARED\n'+''.join('    reconstruction/src/'+x+'.cpp\n' for x in sources)+')'
assert old in s;s=s.replace(old,new)
s=s.replace('option(RF_SANITIZE "Enable host address and undefined behavior sanitizers" OFF)','option(RF_SANITIZE "Enable host address and undefined behavior sanitizers" OFF)\noption(RF_EXPERIMENTAL_VULKAN "Build the opt-in Vulkan cost-volume validation backend" OFF)')
s=s.replace('target_compile_options(realityforge PRIVATE -Wall -Wextra -Wpedantic)','target_compile_options(realityforge PRIVATE -Wall -Wextra -Wpedantic)\nif(RF_EXPERIMENTAL_VULKAN)\n    add_subdirectory(reconstruction/src/vulkan)\nendif()')
s=s.replace('-fsanitize=address,undefined','-fsanitize=address,undefined,float-cast-overflow')
idx=s.rfind('endif()')
add='''    # Explicit registrations: no test source is globbed into the shipping library.
    # Release test binaries must retain assertions, including in legacy fixtures.
    function(rf_add_campaign_test target source)
        add_executable(${target} ${source})
        target_link_libraries(${target} PRIVATE realityforge)
        target_compile_options(${target} PRIVATE -UNDEBUG)
        add_test(NAME ${target} COMMAND ${target})
    endfunction()
'''
for target,source in [('initializer_degeneracy','initializer_degeneracy'),('pnp_robustness','pnp_robustness'),('triangulation_conditioning','triangulation_conditioning'),('intrinsics','intrinsics'),('loop_closure','loop_closure'),('depth_tiles','depth_tiles'),('depth_refinement','depth_refinement'),('matching_cost','matching_cost'),('depth_consistency','depth_consistency'),('depth_cache_manifest','depth_cache_manifest'),('progress','progress'),('surface_fusion','surface_fusion'),('surface_evidence','surface_evidence'),('mesh_topology','mesh_topology'),('mesh_cleanup','mesh_cleanup'),('texture_visibility','texture_visibility'),('uv_atlas','uv_atlas'),('texture_blend','texture_blend'),('photometric_adversarial','photometric_adversarial')]:
 add+=f'    rf_add_campaign_test(rf_{target}_tests reconstruction/tests/{source}_test.cpp)\n'
add+='''    rf_add_campaign_test(rf_texture_c_abi_failure_tests reconstruction/tests/agent_057_c_abi_failure.cpp)
    add_executable(rf_texture_png_tests reconstruction/tests/texture_png_test.cpp)
    target_link_libraries(rf_texture_png_tests PRIVATE realityforge ZLIB::ZLIB)
    target_compile_options(rf_texture_png_tests PRIVATE -UNDEBUG)
    add_test(NAME rf_texture_png_tests COMMAND rf_texture_png_tests
        ${CMAKE_CURRENT_BINARY_DIR}/agent057.png ${CMAKE_CURRENT_BINARY_DIR}/agent057-reference.png)
    if(UNIX)
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
        add_test(NAME rf_checkpoint_process_tests COMMAND rf_checkpoint_process_tests)
        set_tests_properties(rf_checkpoint_process_tests PROPERTIES TIMEOUT 120)
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
'''
s=s[:idx]+add+s[idx:]
p.write_text(s)
