# =============================================================================
# 直接编译 libuvc v0.0.7 为静态库
# - 不编译 JPEG 转换（frame-mjpeg.c 不入列）：MJPEG 原样透传给 Java 层，
#   由 Android BitmapFactory 解码，避免引入 libjpeg-turbo
# - libuvc_config.h 由本工程 cmake/config/libuvc 提供（替代其 .in 生成）
# 注意：v0.0.7 的源文件清单以其官方 CMakeLists 为准
#   ctrl.c / ctrl-gen.c / device.c / diag.c / frame.c / init.c / stream.c / misc.c
# =============================================================================
set(LIBUVC_ROOT ${THIRD_PARTY_DIR}/libuvc)

set(LIBUVC_SOURCES
    ${LIBUVC_ROOT}/src/ctrl.c
    ${LIBUVC_ROOT}/src/ctrl-gen.c
    ${LIBUVC_ROOT}/src/device.c
    ${LIBUVC_ROOT}/src/diag.c
    ${LIBUVC_ROOT}/src/frame.c
    ${LIBUVC_ROOT}/src/init.c
    ${LIBUVC_ROOT}/src/stream.c
    ${LIBUVC_ROOT}/src/misc.c
)

add_library(uvc_static STATIC ${LIBUVC_SOURCES})

target_include_directories(uvc_static PUBLIC
    ${LIBUVC_ROOT}/include        # libuvc/libuvc.h
    ${CMAKE_CONFIG_DIR}           # libuvc/libuvc_config.h（见 cmake/config/libuvc/）
)

# 不定义 LIBUVC_HAS_JPEG：MJPEG 直接透传，frame-mjpeg.c 不参与编译
target_compile_definitions(uvc_static PRIVATE
    _GNU_SOURCE=1
)

target_link_libraries(uvc_static PUBLIC usb_static)

set_target_properties(uvc_static PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)
