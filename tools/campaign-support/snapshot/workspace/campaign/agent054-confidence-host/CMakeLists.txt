cmake_minimum_required(VERSION 3.22)
project(agent054_confidence LANGUAGES CXX)
enable_testing()
add_executable(agent054_confidence
  /workspace/agents/054/reconstruction/tests/surface_evidence_test.cpp
  /workspace/agents/054/reconstruction/src/cancellation.cpp
  /workspace/agents/054/reconstruction/src/core.cpp)
target_compile_features(agent054_confidence PRIVATE cxx_std_20)
target_include_directories(agent054_confidence PRIVATE /workspace/agents/054/reconstruction/include)
target_compile_options(agent054_confidence PRIVATE -Wall -Wextra -Wpedantic -Werror)
option(AGENT054_SANITIZE "Run synthetic evidence with sanitizers" OFF)
if(AGENT054_SANITIZE)
  target_compile_options(agent054_confidence PRIVATE -fsanitize=address,undefined,float-cast-overflow -fno-omit-frame-pointer)
  target_link_options(agent054_confidence PRIVATE -fsanitize=address,undefined,float-cast-overflow)
endif()
add_test(NAME agent054_confidence COMMAND agent054_confidence)
set_tests_properties(agent054_confidence PROPERTIES TIMEOUT 30)
