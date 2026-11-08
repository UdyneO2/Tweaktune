cmake_minimum_required(VERSION 3.22.1)
project(tweakcore C)
set(CMAKE_C_STANDARD 11)
add_library(tweakcore SHARED tweak_core.c jni_bridge.c)
# Android 15: dukung page size 16 KB
target_link_options(tweakcore PRIVATE "-Wl,-z,max-page-size=16384")
