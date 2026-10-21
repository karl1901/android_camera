# =============================================================================
# 直接编译 libusb v1.0.27（Linux/Android usbfs 后端）为静态库
# 不走 libusb 自带的 autotools/CMake，避免交叉编译配置检测
# =============================================================================
set(LIBUSB_ROOT ${THIRD_PARTY_DIR}/libusb)

# 核心源码（与 libusb v1.0.27 CMakeLists 中 Linux 后端清单一致）
set(LIBUSB_SOURCES
    ${LIBUSB_ROOT}/libusb/core.c
    ${LIBUSB_ROOT}/libusb/descriptor.c
    ${LIBUSB_ROOT}/libusb/hotplug.c
    ${LIBUSB_ROOT}/libusb/io.c
    ${LIBUSB_ROOT}/libusb/sync.c
    ${LIBUSB_ROOT}/libusb/strerror.c
    ${LIBUSB_ROOT}/libusb/os/events_posix.c
    ${LIBUSB_ROOT}/libusb/os/threads_posix.c
    ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
    ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
    # 注意：不编译 linux_udev.c —— Android 没有 udev，热插拔由 Java 层
    # USB Device Attached/Detached 广播负责
)

add_library(usb_static STATIC ${LIBUSB_SOURCES})

target_include_directories(usb_static PUBLIC
    ${LIBUSB_ROOT}/libusb        # libusb.h / libusbi.h
    ${LIBUSB_ROOT}               # 兼容 "libusb/xxx.h" 内部引用
    ${CMAKE_CONFIG_DIR}          # 我们提供的 config.h
)

# 兼容部分代码使用 <libusb-1.0/libusb.h> 的安装后路径写法
set(LIBUSB_COMPAT_DIR ${CMAKE_BINARY_DIR}/libusb-compat/libusb-1.0)
file(MAKE_DIRECTORY ${LIBUSB_COMPAT_DIR})
file(WRITE ${LIBUSB_COMPAT_DIR}/libusb.h
    "/* 转发头：兼容 <libusb-1.0/libusb.h> 写法 */\n#include <libusb.h>\n")
target_include_directories(usb_static PUBLIC ${CMAKE_BINARY_DIR}/libusb-compat)

target_compile_definitions(usb_static PUBLIC
    HAVE_CONFIG_H=1
    _GNU_SOURCE=1
    # v1.0.27 平台层选择：PLATFORM_POSIX 决定 events_posix/threads_posix；
    # 后端只靠“编译哪些 os/*.c”决定，不存在 OS_LINUX 宏
    PLATFORM_POSIX=1
    HAVE_EVENTFD=1
    HAVE_TIMERFD=1
    HAVE_NFDS_T=1
    HAVE_CLOCK_GETTIME=1
    HAVE_PIPE2=1
    HAVE_ACCEPT4=1
    HAVE_STRUCT_TIMESPEC=1
    # 启用 libusb 日志框架（否则 usbi_dbg/usbi_warn 全部被编译为空），
    # 并让日志走 Android __android_log_write（标签 "libusb"）
    ENABLE_LOGGING=1
    USE_SYSTEM_LOGGING_FACILITY=1
    # 不定义 HAVE_LIBUDEV（Android 无 udev）；热插拔由 Java 广播负责
    "DEFAULT_VISIBILITY=__attribute__((visibility(\"default\")))"
)

set_target_properties(usb_static PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)
