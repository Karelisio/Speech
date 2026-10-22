cmake_minimum_required(VERSION 3.22)
project(murmure_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)

# Snapdragon 8 Elite (Oryon) : ARMv8.7, pas de SVE. On cible explicitement dotprod/fp16/i8mm.
set(MURMURE_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "")
set(MURMURE_OPT_FLAGS "-O3 -DNDEBUG -ffunction-sections -fdata-sections")
set(CMAKE_C_FLAGS_RELEASE   "${MURMURE_OPT_FLAGS}" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "${MURMURE_OPT_FLAGS}" CACHE STRING "" FORCE)
add_compile_options(-march=${MURMURE_ARM_ARCH})

# ggml / whisper.cpp : bibliothèques statiques, liées dans un unique .so JNI.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH ${MURMURE_ARM_ARCH} CACHE STRING "" FORCE)
# Pool de threads interne à ggml plutôt qu'OpenMP (pas de libomp.so à embarquer, pas de spin).
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/whisper.cpp whisper.cpp EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper android log)
# --exclude-libs,ALL : les symboles ggml restent privés à ce .so (évite les collisions
# avec la copie de ggml de llama.cpp qui sera chargée dans le même process).
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
