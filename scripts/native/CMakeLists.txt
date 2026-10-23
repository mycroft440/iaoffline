cmake_minimum_required(VERSION 3.19)
project(iaoffline_cache_test C CXX)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
add_subdirectory(../../third_party/llama.cpp/ggml ggml)
add_executable(test-moe-kernel test-moe-kernel.cpp)
target_link_libraries(test-moe-kernel PRIVATE ggml ggml-cpu)
# Tests rely on assertions even when dependencies are built in Release mode.
target_compile_options(test-moe-kernel PRIVATE -UNDEBUG)
find_package(Python3 COMPONENTS Interpreter REQUIRED)
execute_process(COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/extract-moe-callback.py
    ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/llama.cpp/examples/llama.android/lib/src/main/cpp/ai_chat.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/production-moe.inc COMMAND_ERROR_IS_FATAL ANY)
add_executable(test-moe-router test-moe-router.cpp)
target_include_directories(test-moe-router PRIVATE ${CMAKE_CURRENT_BINARY_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/llama.cpp/ggml/src)
target_compile_options(test-moe-router PRIVATE -UNDEBUG)
target_link_libraries(test-moe-router PRIVATE ggml ggml-cpu)
