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

见 [`本轮更新说明_第三轮.md`](本轮更新说明_第三轮.md)：

- 布局切换不再摘挂 TextureView，修复主界面/设置页切换布局的原生 SIGABRT；
- 重写实时降噪/夜视增强：2×2 块级时域累积 + 边缘/运动保护 + 夜视 LUT，
  1080p 强档实测 18–24ms，不再拖影/冻结；
- 回看单路裁剪、全屏裁剪、暂停切画面强制重绘，修复“按钮变了画面没变”；
- 高德 3D 地图升级 10.0.600，修复退出回看时 AMap GLThread 原生崩溃；
- GPS WGS-84→GCJ-02、按录像分段筛选、网络定位兜底与跳点过滤；
- 相册缓存/并行预载/自适应列数，切 Tab 不再重复扫描；看图器居中适配；
- 播放器双击播放/暂停、单击显隐工具栏；设置页按设备 Tab、开机自启开关；
- 预警改用 ToneGenerator 警报流并新增“测试蜂鸣”；
- 深色/科技蓝加入极淡星光颗粒背景，卡片玻璃拟态与选中渐变统一；
- 启动时生成并落盘设备性能画像，按性能调度降噪/预警/合成帧率/相册列数。

历史改动见 [`本轮更新说明_第二轮.md`](本轮更新说明_第二轮.md)。

## 已知限制

MJPEG-only（YUY2-only 摄像头不出图）；4K60 未接真机验证；INT8 人/车模型未内置
（预警为运动检测兜底）；高德 Key 仅免费测试配额（3D 地图 SDK 10.0.600）；USB 权限不能跨重启保留，重启后
自动弹授权；总录像合成为 1280×720@15fps。详见更新说明第 5 节。
