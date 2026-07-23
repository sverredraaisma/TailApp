cmake_minimum_required(VERSION 3.22.1)

project(tailapp_audio LANGUAGES CXX)

# Oboe ships as a prefab AAR; `prefab = true` in build.gradle.kts is what makes
# this find_package resolve.
find_package(oboe REQUIRED CONFIG)

add_library(tailapp_audio SHARED
        oboe_capture.cpp)

target_compile_features(tailapp_audio PRIVATE cxx_std_17)
target_compile_options(tailapp_audio PRIVATE -Wall -Wextra -Ofast)

target_link_libraries(tailapp_audio PRIVATE
        oboe::oboe
        log)
