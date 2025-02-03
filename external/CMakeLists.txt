project(external)

if (ANDROID_ABI STREQUAL "riscv64")
	set(ANDROID_PLATFORM "android-35")
endif ()

macro(SET_OPTION option value)
    set(${option} ${value} CACHE INTERNAL "" FORCE)
endmacro()

SET_OPTION(Plugin.SymbolResolver OFF)
SET_OPTION(FMT_INSTALL OFF)

set(XZ_SOURCES
	xz_crc32.c
	xz_crc64.c
	# xz_dec_bcj.c
	xz_dec_lzma2.c
	xz_dec_stream.c)
set(XZ_INCLUDES xz-embedded/linux/include/linux xz-embedded/userspace)
list(TRANSFORM XZ_SOURCES PREPEND xz-embedded/linux/lib/xz/)
add_library(xz_static STATIC ${XZ_SOURCES})
target_compile_options(xz_static PRIVATE -DXZ_USE_CRC64)
target_include_directories(xz_static PRIVATE ${XZ_INCLUDES})

OPTION(LSPLANT_BUILD_SHARED OFF)
if (ANDROID_ABI STREQUAL "riscv64")
	add_subdirectory(riscv64-inline-hook)
else ()
	add_subdirectory(dobby)
endif ()
add_subdirectory(fmt)
add_subdirectory(lsplant/lsplant/src/main/jni)
target_compile_definitions(fmt-header-only INTERFACE FMT_USE_LOCALE=0 FMT_USE_FLOAT=0 FMT_USE_DOUBLE=0 FMT_USE_LONG_DOUBLE=0 FMT_USE_BITINT=0)
