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
