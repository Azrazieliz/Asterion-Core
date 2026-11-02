cmake_minimum_required(VERSION 3.22.1)
project(qwen_runtime LANGUAGES C CXX)

include(FetchContent)

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_MUSA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
# Cross-compiling with Android NDK roots hides the Ubuntu host-only
# SPIR-V headers CMake config; these are build-time shader-generator headers.
# Keep the override strictly conditional on the actual installed package.
if(ANDROID AND EXISTS "/usr/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake")
    set("SPIRV-Headers_DIR" "/usr/share/cmake/SPIRV-Headers" CACHE PATH "Host SPIR-V headers package" FORCE)
endif()
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_WEBGPU OFF CACHE BOOL "" FORCE)
set(GGML_OPENVINO OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
set(GGML_ZDNN OFF CACHE BOOL "" FORCE)
set(GGML_ZENDNN OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# Arm's KleidiAI uses runtime-dispatched AArch64 kernels and preserves
# baseline ARMv8 compatibility. It accelerates CPU-side GGUF quantized matmuls
# on Android without assuming an Adreno/Vulkan driver or removing CPU fallback.
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH armv8-a CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/5266f24da75dc449bd56cbed7addb9c8e4a6a73e.tar.gz
    URL_HASH SHA256=2DE0D87EDA4696E9F6BBD771D4C623267F4E95856CCE6F99793F91522F993E43
)
FetchContent_MakeAvailable(llama_cpp)

# NDK ships Vulkan C headers, not the C++ vulkan.hpp wrapper used by
# pinned ggml-vulkan. Use only the shader-build host's public Vulkan-Hpp
# headers when cross-compiling. The final Android binary has no host deps.
if(ANDROID AND TARGET ggml-vulkan AND EXISTS "/usr/include/vulkan/vulkan.hpp")
    # CMake drops /usr/include as an implicit cross-compiler system path.
    # Stage ONLY the Vulkan headers into a real build-local include directory.
    set(ASTERION_VULKAN_HEADERS "${CMAKE_CURRENT_BINARY_DIR}/vulkan-hpp-headers")
    file(COPY "/usr/include/vulkan" DESTINATION "${ASTERION_VULKAN_HEADERS}")
    target_include_directories(ggml-vulkan BEFORE PRIVATE "${ASTERION_VULKAN_HEADERS}")
endif()

add_library(qwen_runtime SHARED qwen_runtime_jni.cpp)
target_compile_definitions(qwen_runtime PRIVATE LLAMA_CPP_PINNED_REVISION="5266f24da75dc449bd56cbed7addb9c8e4a6a73e")
target_include_directories(qwen_runtime PRIVATE ${llama_cpp_SOURCE_DIR}/include ${llama_cpp_SOURCE_DIR}/tools/mtmd)
target_link_libraries(qwen_runtime PRIVATE mtmd llama log android)