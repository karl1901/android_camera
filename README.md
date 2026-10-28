# 盲区辅助（Android 车载 UVC 多路摄像头）

包名：`com.wheeltech.blindcam`

面向后装安卓车机 / 手机的 USB UVC 盲区辅助 APP：多路 MJPEG 摄像头预览、USB 授权与
热插拔、每路独立镜像/旋转/降噪、单路 MP4 录像与全局合成录像、循环录像、GPS 轨迹、
内置相册/看图器/播放器、盲区运动预警、浅色/深色/科技蓝主题。

## 构建

```powershell
# Windows：固定 JDK17 + Gradle 8.14.3（见 scripts/build.ps1）
pwsh scripts/build.ps1                 # assembleDebug
pwsh scripts/build.ps1 installDebug    # 安装到已连接设备
pwsh scripts/build.ps1 :app:testDebugUnitTest
```

环境与版本约束（compileSdk 37 / targetSdk 33 / NDK 27.2 / CMake 3.22.1 / ABI
armeabi-v7a + arm64-v8a / 原生 libuvc）详见 [`docs/PROJECT_SETUP.md`](docs/PROJECT_SETUP.md)。

## 本轮改动

见 [`本轮更新说明_第六轮.md`](本轮更新说明_第六轮.md)：

- 摄像头热插拔按接入路数自动 1/2/3/4 屏，拔出自动降屏；
- 修复四路总录像同毫秒缓存重名导致的 0 字节损坏 MP4；
- 回看全部画面与主页宫格统一（位置、顺序、center-crop）；
- 回看切换画面改为与主页一致的下拉菜单；
- 1/2/3/4 路总录像及回看实机验证通过。

历史改动见 [`本轮更新说明_第五轮.md`](本轮更新说明_第五轮.md)。

## 已知限制

MJPEG-only（YUY2-only 摄像头不出图）；4K60 未接真机验证；INT8 人/车模型未内置
（预警为运动检测兜底）；高德 Key 仅免费测试配额（3D 地图 SDK 10.0.600）；USB 权限不能跨重启保留，重启后
自动弹授权；新总录像按独立分路存储；旧版合成录像选中单路时只能裁剪查看。

