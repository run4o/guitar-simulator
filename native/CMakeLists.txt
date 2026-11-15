cmake_minimum_required(VERSION 3.28)
project(guitar_engine VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_WINDOWS_EXPORT_ALL_SYMBOLS ON)

add_library(guitar_engine SHARED
    src/guitar_engine.cpp
)
target_include_directories(guitar_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)

# Match the Java-side expectation for the DLL name: guitar_engine.dll
set_target_properties(guitar_engine PROPERTIES
    OUTPUT_NAME "guitar_engine"
    PREFIX ""
)

if(MSVC)
    target_compile_options(guitar_engine PRIVATE /W4 /permissive-)
else()
    target_compile_options(guitar_engine PRIVATE -Wall -Wextra -O2)
endif()

# miniaudio uses WASAPI on Windows; GCC does not process MSVC #pragma comment(lib)
if(WIN32 AND NOT MSVC)
    target_link_libraries(guitar_engine PRIVATE ole32 winmm ws2_32 uuid ksuser)
    # Fully static GCC runtime so the DLL is self-contained and portable
    # (no libstdc++/libgcc/libwinpthread dependency when packaged with jpackage)
    target_link_options(guitar_engine PRIVATE -static-libgcc -static-libstdc++ -static)
endif()
