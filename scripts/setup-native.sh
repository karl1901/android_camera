#!/usr/bin/env bash
# =============================================================================
# 拉取 libuvc / libusb 第三方源码（pinned 版本，源码不入 Git，由 CMake 直接编译）
# 用法：bash scripts/setup-native.sh
# =============================================================================
set -euo pipefail

# 切到仓库根目录
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TP_DIR="${ROOT_DIR}/app/src/main/cpp/third_party"
mkdir -p "${TP_DIR}"
cd "${TP_DIR}"

# 版本锁定（与 cmake/config 头里的版本保持一致，升级需同步修改并重新验证）
LIBUSB_TAG="v1.0.27"   # 含 libusb_wrap_sys_device，支持 Android fd 包装
LIBUVC_TAG="v0.0.7"    # 含 uvc_wrap_sys_device，MJPEG 透传

if [ ! -d "libusb" ]; then
  echo ">>> cloning libusb ${LIBUSB_TAG}"
  git clone --depth 1 --branch "${LIBUSB_TAG}" https://github.com/libusb/libusb.git
else
  echo ">>> libusb already exists, skip"
fi

if [ ! -d "libuvc" ]; then
  echo ">>> cloning libuvc ${LIBUVC_TAG}"
  git clone --depth 1 --branch "${LIBUVC_TAG}" https://github.com/libuvc/libuvc.git
else
  echo ">>> libuvc already exists, skip"
fi

echo ""
echo "完成。第三方源码位于：${TP_DIR}"
echo "接下来在 Android Studio 中 Sync Project，或执行：./gradlew :app:assembleDebug"
