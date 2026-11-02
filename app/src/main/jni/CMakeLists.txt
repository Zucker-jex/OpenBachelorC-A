cmake_minimum_required(VERSION 3.22.1)
project(obc C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# libfrida-core.a  (frida 17.9.1, android arm64, from frida-core-devkit)
#
# 261 MB unstripped archive -> must use --gc-sections + --exclude-libs,ALL,
# otherwise the resulting .so explodes in size.
# ---------------------------------------------------------------------------
add_library(frida-core STATIC IMPORTED)
set_target_properties(frida-core PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libfrida-core.a")

add_library(obc SHARED
        obc_jni.c)

target_include_directories(obc PRIVATE
        "${CMAKE_SOURCE_DIR}/include")

target_compile_options(obc PRIVATE
        -ffunction-sections
        -fdata-sections
        -fvisibility=hidden
        -Wno-unused-parameter)

target_link_options(obc PRIVATE
        "-Wl,--gc-sections"
        "-Wl,--exclude-libs,ALL"
        # Do not let the 261 MB archive's debug sections ride along in the .so;
        # without this the shipped library is ~50 MB instead of ~15 MB.
        "-Wl,--strip-debug"
        "-Wl,--allow-multiple-definition"
        "-Wl,-z,max-page-size=16384")

target_link_libraries(obc
        frida-core
        android
        log
        dl
        m
        z)
