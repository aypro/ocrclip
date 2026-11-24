cmake_minimum_required(VERSION 3.22)
project(ocrclip_llm C CXX)

# The model runner has to be optimised in debug builds too: at -O0 a translation takes minutes, not seconds.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

# llama.cpp as a static library inside libllm.so. The ARM target is what every Retroid with Android 11+ has
# (Cortex-A55 and up); i8mm would only be on newer chips and made no difference on the Nova.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp llama.cpp EXCLUDE_FROM_ALL)

add_library(llm SHARED llm.cpp)
target_link_libraries(llm llama log)
