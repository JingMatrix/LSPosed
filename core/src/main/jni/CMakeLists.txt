cmake_minimum_required(VERSION 3.10)
project(core)

set(CMAKE_CXX_STANDARD 23)

add_subdirectory(${EXTERNAL_ROOT} external)

aux_source_directory(src SRC_LIST)
aux_source_directory(src/jni SRC_LIST)
aux_source_directory(src/xz SRC_LIST)

add_library(${PROJECT_NAME} STATIC ${SRC_LIST})

set(IGNORED_WARNINGS
	-Wno-c99-extensions
	-Wno-gnu-zero-variadic-macro-arguments
	-Wno-gnu-flexible-array-initializer
	-Wno-variadic-macros
	-Wno-zero-length-array)

target_include_directories(${PROJECT_NAME} PUBLIC include)
target_include_directories(${PROJECT_NAME} PRIVATE src ${EXTERNAL_ROOT}/xz-embedded/linux/include)
target_compile_options(${PROJECT_NAME} PRIVATE -Wpedantic ${IGNORED_WARNINGS})

target_link_libraries(${PROJECT_NAME} PUBLIC dobby_static lsplant_static xz_static log fmt-header-only)
target_link_libraries(${PROJECT_NAME} PRIVATE dex_builder_static)
