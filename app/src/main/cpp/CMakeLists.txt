cmake_minimum_required(VERSION 3.22.1)
project(parallax CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenCV comes from the org.opencv:opencv AAR via Prefab.
find_package(OpenCV REQUIRED CONFIG)

add_library(parallax SHARED
    core/features.cpp
    core/geometry.cpp
    core/ba.cpp
    core/sfm.cpp
    core/dense.cpp
    core/io.cpp
    live.cpp
    probe.cpp
    jni_bridge.cpp)

target_include_directories(parallax PRIVATE core)
# Optimise even in debug builds: reconstruction is unusably slow at -O0. No -ffast-math: the
# geometry relies on IEEE semantics (NaN/inf checks), and no -march beyond the ABI baseline.
target_compile_options(parallax PRIVATE -O3 -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)
target_link_libraries(parallax OpenCV::opencv_java4 android jnigraphics log)
target_link_options(parallax PRIVATE "-Wl,-z,max-page-size=16384")
