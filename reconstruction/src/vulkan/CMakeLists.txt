# Parent owner004 opts in; Vulkan remains disabled in shipping configuration.
add_library(rf_vulkan_depth STATIC vulkan_depth.cpp)
target_include_directories(rf_vulkan_depth PUBLIC ../../include)
target_compile_features(rf_vulkan_depth PUBLIC cxx_std_20)
target_link_libraries(rf_vulkan_depth PUBLIC realityforge)
if(ANDROID)
  target_link_libraries(rf_vulkan_depth PRIVATE vulkan)
else()
  find_path(RF_VULKAN_INCLUDE_DIR vulkan/vulkan.h
    HINTS "$ENV{ANDROID_HOME}/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
    REQUIRED)
  find_library(RF_VULKAN_LIBRARY NAMES vulkan libvulkan.so.1 REQUIRED)
  # NDK headers are a fallback, after host C/C++ headers so Android libc does not
  # replace the host standard headers when only Vulkan headers are needed.
  target_compile_options(rf_vulkan_depth PRIVATE -idirafter "${RF_VULKAN_INCLUDE_DIR}")
  target_link_libraries(rf_vulkan_depth PRIVATE "${RF_VULKAN_LIBRARY}")
endif()
