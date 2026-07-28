cmake_minimum_required(VERSION 3.22.1)

project(tailapp_audio LANGUAGES CXX)

# Oboe ships as a prefab AAR; `prefab = true` in build.gradle.kts is what makes
# this find_package resolve.
find_package(oboe REQUIRED CONFIG)

add_library(tailapp_audio SHARED
        oboe_capture.cpp)

target_compile_features(tailapp_audio PRIVATE cxx_std_17)

# -O2, not -Ofast. -Ofast implies -ffast-math, and with it -ffinite-math-only,
# under which the capture path's NaN/Inf guards are undefined behaviour — the
# compiler is free to fold them away. Nothing here is float-throughput bound
# anyway: it is a memcpy into a lock-free ring buffer from an Oboe callback.
target_compile_options(tailapp_audio PRIVATE -Wall -Wextra -O2)

target_link_libraries(tailapp_audio PRIVATE
        oboe::oboe
        log)

# Android 15 ships devices with 16 KB memory pages; a shared library linked with
# the older 4 KB max page size fails to load there, at runtime, with no build-time
# signal. NDK r27+ does this by default, but stating it keeps the ABI correct even
# if the build ever runs against an older toolchain.
target_link_options(tailapp_audio PRIVATE "-Wl,-z,max-page-size=16384")
