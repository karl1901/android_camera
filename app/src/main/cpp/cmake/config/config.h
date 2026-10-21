/*
 * libusb 交叉编译配置头（替代 autotools/CMake 生成的 config.h）
 * 面向 Android NDK（Linux usbfs 后端）
 */
#ifndef LIBUSB_ANDROID_CONFIG_H
#define LIBUSB_ANDROID_CONFIG_H

#define PACKAGE_NAME "libusb-1.0"
#define PACKAGE_VERSION "1.0.27"
#define PACKAGE_STRING "libusb-1.0 1.0.27"
#define PACKAGE_BUGREPORT ""
#define PACKAGE_URL ""

#define HAVE_EVENTFD 1
#define HAVE_TIMERFD 1
#define HAVE_NFDS_T 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_PIPE2 1
#define HAVE_ACCEPT4 1
#define HAVE_STRUCT_TIMESPEC 1
#define HAVE_NETLINK 1
#define HAVE_SYSLOG_H 1

/* Android 无 udev */
/* #undef USE_UDEV */

#endif /* LIBUSB_ANDROID_CONFIG_H */
