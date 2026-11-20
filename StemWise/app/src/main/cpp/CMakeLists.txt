cmake_minimum_required(VERSION 3.22.1)
project(stemwise_platform_integration LANGUAGES C CXX)

# Fixed external tools keep their existing private session group and network binding.
add_library(stemwise_owned_tool SHARED integration/OwnedToolProcess.cpp)
target_compile_features(stemwise_owned_tool PRIVATE cxx_std_17)
target_link_options(stemwise_owned_tool PRIVATE "-Wl,-z,max-page-size=16384")

add_library(stemwise_wifi_binding SHARED network/WifiProcessBinding.c)
target_compile_features(stemwise_wifi_binding PRIVATE c_std_11)
target_compile_options(stemwise_wifi_binding PRIVATE -Wall -Wextra -Werror)
target_link_options(stemwise_wifi_binding PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(stemwise_wifi_binding PRIVATE android)
