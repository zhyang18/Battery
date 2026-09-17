cmake_minimum_required(VERSION 3.22.1)

project("battery_sampler")

add_library(
    battery_sampler
    SHARED
    power_reader.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    battery_sampler
    ${log-lib}
)

# 支持 16 KB 页面大小对齐（满足 Android 15+ 及 Google Play 要求）
target_link_options(
    battery_sampler
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
