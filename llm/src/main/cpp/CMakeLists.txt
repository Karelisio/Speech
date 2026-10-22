cmake_minimum_required(VERSION 3.22)
project(speech_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)

# Mêmes choix que pour whisper.cpp (voir app/src/main/cpp/CMakeLists.txt).
set(SPEECH_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "")
set(SPEECH_OPT_FLAGS "-O3 -DNDEBUG -ffunction-sections -fdata-sections")
set(CMAKE_C_FLAGS_RELEASE   "${SPEECH_OPT_FLAGS}" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "${SPEECH_OPT_FLAGS}" CACHE STRING "" FORCE)
add_compile_options(-march=${SPEECH_ARM_ARCH})

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH ${SPEECH_ARM_ARCH} CACHE STRING "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Réarrangement des poids Q4_0 pour les instructions i8mm (raison du choix Q4_0).
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/llama.cpp llama.cpp EXCLUDE_FROM_ALL)

add_library(llama_jni SHARED llama_jni.cpp)
target_link_libraries(llama_jni PRIVATE llama android log)
target_link_options(llama_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
