# Parent owner004 opts in; Vulkan remains disabled in shipping configuration.
add_library(rf_vulkan_depth STATIC vulkan_depth.cpp)
target_include_directories(rf_vulkan_depth PUBLIC ../../include)
target_compile_features(rf_vulkan_depth PUBLIC cxx_std_20)
target_link_libraries(rf_vulkan_depth PUBLIC realityforge ${CMAKE_DL_LIBS})
if(ANDROID)
  target_link_libraries(rf_vulkan_depth PRIVATE vulkan)
endif()
