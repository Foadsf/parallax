# Builds the reconstruction core for the host and a synthetic end-to-end test.
#   cmake -S tools/host-test -B build/host-test -DOpenCV_DIR=<opencv>/lib/cmake/opencv4
#   cmake --build build/host-test -j && build/host-test/synth_test /tmp/parallax-synth
cmake_minimum_required(VERSION 3.22)
project(parallax_host_test CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(OpenCV REQUIRED COMPONENTS core imgproc imgcodecs features2d flann calib3d OPTIONAL_COMPONENTS objdetect)
set(CORE ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/core)
add_library(parallax_core STATIC
  ${CORE}/features.cpp ${CORE}/geometry.cpp ${CORE}/ba.cpp ${CORE}/sfm.cpp ${CORE}/dense.cpp ${CORE}/io.cpp)
target_include_directories(parallax_core PUBLIC ${CORE})
target_link_libraries(parallax_core PUBLIC ${OpenCV_LIBS})
target_compile_options(parallax_core PRIVATE -Wall -Wextra)

add_executable(synth_test synth_test.cpp)
target_link_libraries(synth_test PRIVATE parallax_core)

# ArUco moved into objdetect in OpenCV 4.7; older system packages (e.g. Ubuntu 24.04's 4.6) lack it.
find_file(PARALLAX_ARUCO_HEADER opencv2/objdetect/aruco_detector.hpp PATHS ${OpenCV_INCLUDE_DIRS} NO_DEFAULT_PATH)
if(PARALLAX_ARUCO_HEADER)
  add_executable(make_marker make_marker.cpp)
  target_link_libraries(make_marker PRIVATE ${OpenCV_LIBS})
endif()
