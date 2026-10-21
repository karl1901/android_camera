/*
 * libuvc 配置头（替代 libuvc v0.0.7 的 include/libuvc/libuvc_config.h.in 生成）
 * 通过 cmake/config 加入包含路径，使 <libuvc/libuvc_config.h> 可解析
 */
#ifndef LIBUVC_CONFIG_H
#define LIBUVC_CONFIG_H

#define LIBUVC_VERSION_MAJOR 0
#define LIBUVC_VERSION_MINOR 0
#define LIBUVC_VERSION_PATCH 7
#define LIBUVC_VERSION_STR "0.0.7"
#define LIBUVC_VERSION_INT \
  ((0 << 16) | (0 << 8) | (7))

#define LIBUVC_VERSION_GTE(major, minor, patch) \
  (LIBUVC_VERSION_INT >= (((major) << 16) | ((minor) << 8) | (patch)))

/* MJPEG 透传方案，不依赖 libjpeg/turbojpeg */
/* #undef LIBUVC_HAS_JPEG */

#endif // !LIBUVC_CONFIG_H
