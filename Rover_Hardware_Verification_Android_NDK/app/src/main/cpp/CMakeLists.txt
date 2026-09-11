cmake_minimum_required(VERSION 3.22.1)
project("rover_vector_native")

add_library(rover_vector_native SHARED native-lib.cpp)

# Link standard Android NDK libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    rover_vector_native
    ${log-lib}
    ${android-lib}
)
