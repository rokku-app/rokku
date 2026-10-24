cmake_minimum_required(VERSION 3.22)
project("rokku_discord")

find_package(discord_partner_sdk REQUIRED CONFIG)

add_library(
        rokku_discord
        SHARED
        discord_bridge.cpp
)

target_include_directories(
        rokku_discord
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(
        rokku_discord
        discord_partner_sdk::discord_partner_sdk
        jnigraphics
        log
)

target_compile_features(
        rokku_discord
        PUBLIC
        cxx_std_17
)
