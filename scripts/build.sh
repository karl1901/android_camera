#!/usr/bin/env bash
# =============================================================================
# 本机一键构建脚本：
# 让 /Users/karl/Documents/java/gradle/8.14.3/gradle-8.14.3 生效，
# JDK 固定 17（不使用 AS 自带的 JBR25）
# 用法：
#   bash scripts/build.sh              # 等价于 assembleDebug
#   bash scripts/build.sh clean        # 清理
#   bash scripts/build.sh installDebug # 安装到已连接设备
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- 本机路径（按你提供的环境） ----
export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
LOCAL_GRADLE="/Users/karl/Documents/java/gradle/8.14.3/gradle-8.14.3/bin/gradle"

# SDK 路径通过 local.properties 注入（sdk.dir=/Users/karl/Library/Android/sdk）
if [ ! -f "${ROOT_DIR}/local.properties" ]; then
  cp "${ROOT_DIR}/local.properties.example" "${ROOT_DIR}/local.properties"
fi

if [ "$#" -eq 0 ]; then
  TASK="assembleDebug"
else
  TASK="$1"
  shift
fi

cd "${ROOT_DIR}"
if [ -x "${LOCAL_GRADLE}" ]; then
  echo ">>> 使用本地 Gradle: ${LOCAL_GRADLE}"
  exec "${LOCAL_GRADLE}" "${TASK}" "$@"
else
  echo ">>> 本地 Gradle 不存在，回退 wrapper（首次会自动下载 8.14.3）"
  exec "${ROOT_DIR}/gradlew" "${TASK}" "$@"
fi
