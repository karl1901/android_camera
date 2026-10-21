# =============================================================================
# UVC 盲区辅助 APP —— Native 构建入口
#
# 设计：
#   - third_party/libusb（pinned v1.0.27）、third_party/libuvc（pinned v0.0.7）
#     由 scripts/setup-native.sh 拉取（不入 Git）
#   - 两个库都以“直接列举源码编译静态库”的方式构建，规避交叉编译时
#     pkg-config / find_package 找不到系统库的问题
#   - 若第三方源码不存在（例如刚 clone 仓库尚未执行脚本），则编译
#     “stub 桩库”：同名 JNI 接口全部返回错误码，Kotlin/Swift 上层与 APK
#     仍可正常编译运行，打开设备时会收到 native not available 错误
# =============================================================================
cmake_minimum_required(VERSION 3.22.1)
project(uvc_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Android 15+ 支持 16KB 内存页，Android 16/17 设备会校验 ELF LOAD 段对齐，
# 未对齐会弹“应用兼容性”警告（16 KB page size）。
# NDK r27 默认仍是 4KB 对齐，必须显式指定 16KB 最大页大小。
add_link_options("-Wl,-z,max-page-size=16384")

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(CMAKE_CONFIG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/cmake/config)

if(EXISTS ${THIRD_PARTY_DIR}/libuvc AND EXISTS ${THIRD_PARTY_DIR}/libusb)
    include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/build_libusb.cmake)
    include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/build_libuvc.cmake)
    set(UVC_HAVE_LIBUVC 1)
    message(STATUS "UVC: libuvc + libusb found, building native backend")
else()
    set(UVC_HAVE_LIBUVC 0)
    message(WARNING "UVC: third_party 缺失，正在编译 JNI stub 桩库。请执行 scripts/setup-native.sh 拉取 libusb/libuvc 源码")
endif()

add_library(uvc_native SHARED
    uvc_native.cpp
)

target_compile_definitions(uvc_native PRIVATE
    UVC_HAVE_LIBUVC=${UVC_HAVE_LIBUVC}
)

if(UVC_HAVE_LIBUVC)
    target_link_libraries(uvc_native PRIVATE
        uvc_static
        usb_static
        log
        android
    )
else()
    target_link_libraries(uvc_native PRIVATE
        log
        android
    )
endif()
