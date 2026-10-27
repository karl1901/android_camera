# 项目初始化与构建说明（阶段 1）

UVC 盲区辅助 APP（鼎微 TS20 / 安卓13 / 双路 AHD→UVC MJPEG 摄像头）。

## 1. 环境版本（硬性约束，禁止改动）

| 项 | 版本 |
|---|---|
| Android Studio | SDK 17（注意：部分新版 AS 自带的是 JBR25，需手动切换到 17） |
| Gradle | **8.14.3**（wrapper 已锁定） |
| Gradle JDK | **JBR17（JDK 17 LTS）**，禁止 21/25+（Gradle 8.14.3 不支持 JDK25，会直接崩溃） |
| AGP | **8.13.2**（compileSdk 37 的最低稳定版本，8.11.x 不识别 android-37） |
| Kotlin | 2.1.21（jvmTarget 17） |
| compileSdk | 37 |
| minSdk | 21（安卓 5.0+ 后装车机） |
| targetSdk | **33**（停在 33，规避 14~17 限制） |
| ABI | armeabi-v7a + arm64-v8a |
| Java/Kotlin 字节码 | **source/target = 17、jvmTarget = 17**（minSdk21 由 D8 脱糖，注意不要在 minSdk21 上直接使用 java.time 等新 API，需用时开启 coreLibraryDesugaring） |
| 16KB 页对齐 | CMake 已加 `-Wl,-z,max-page-size=16384`；DataStore 1.1.7+ 已对齐（Android 15+ 强制校验，Android 17 会弹兼容性警告） |
| NDK | 27.2.12479018（r27c） |
| CMake | 3.22.1（SDK Manager 内置） |
| UI | 原生 Kotlin + XML ViewBinding（不用 Compose / Flutter / AAOS） |

## 2. 本机环境路径

**当前开发机：Windows（PowerShell 7 / pwsh）**

| 项 | 路径 |
|---|---|
| JDK 17 | `D:\karl_word\java\jdk\17` |
| Android SDK | `C:\Users\karl\AppData\Local\Android\Sdk` |
| Gradle 8.14.3 | `D:\karl_word\java\gradle\8.14.3\gradle-8.14.3` |

旧 macOS 开发机路径（保留备查）：JDK `/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`、
SDK `/Users/karl/Library/Android/sdk`、Gradle `/Users/karl/Documents/java/gradle/8.14.3/gradle-8.14.3`。

## 3. 首次拉取后的初始化步骤

```powershell
# 1) 拉取 libusb v1.0.27 / libuvc v0.0.7 源码（不入 Git）
pwsh scripts/setup-native.ps1

# 2) 复制本机 SDK 路径配置（已被 .gitignore 忽略）
Copy-Item local.properties.example local.properties
# 按需修改 sdk.dir（Windows 写法：sdk.dir=C\:\\Users\\karl\\AppData\\Local\\Android\\Sdk）
```

macOS 下对应命令为 `bash scripts/setup-native.sh`、`cp local.properties.example local.properties`。

然后在 Android Studio 中打开项目：

1. **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK 必须选择 17**：
   - 下拉选择本机 JDK 17（Windows：`D:\karl_word\java\jdk\17`；
     macOS：`/Library/Java/JavaVirtualMachines/jdk-17.jdk`），
     或在下拉中点 Download JDK → JetBrains Runtime 17
   - 该工程已实测：AS 内置 JBR25 会导致 Gradle 崩溃（`JavaVersion.parse 25.0.3` 报错）
2. Use Gradle from 可选 Specified location（`D:\karl_word\java\gradle\8.14.3\gradle-8.14.3`），
   `.idea/gradle.xml` 已按此配置
3. SDK Manager 安装：**NDK (Side by side) 27.2.12479018**、**CMake 3.22.1**、
   **Android SDK Platform 37（revision 2）**（Sync 时接受 license 可自动安装；
   也可用 `sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"` 命令行安装）
4. Sync Project，执行 `pwsh scripts/build.ps1`

命令行构建（路径已固化在脚本中，推荐直接用脚本）：

```powershell
# 脚本内固定：
#   JDK    D:\karl_word\java\jdk\17
#   Gradle D:\karl_word\java\gradle\8.14.3\gradle-8.14.3（不存在时回退 wrapper）
#   SDK    C:\Users\karl\AppData\Local\Android\Sdk（local.properties 已配置）
pwsh scripts/build.ps1                 # assembleDebug
pwsh scripts/build.ps1 installDebug    # 安装到车机/设备
pwsh scripts/build.ps1 clean           # 清理
```

macOS 对应 `bash scripts/build.sh [任务]`。

等价的手工命令（Windows PowerShell）：

```powershell
$env:JAVA_HOME = 'D:\karl_word\java\jdk\17'
& 'D:\karl_word\java\gradle\8.14.3\gradle-8.14.3\bin\gradle.bat' :app:assembleDebug
```

> 注意：`local.properties` 已被 `.gitignore` 忽略，换机时从 `local.properties.example`
> 复制并修改 `sdk.dir`；`scripts/build.ps1`（Windows）/ `scripts/build.sh`（macOS）
> 中路径为当前开发机配置，换机需同步修改。

> 未执行 `setup-native` 脚本时工程同样可以编译：CMake 会构建 JNI stub 桩库，
> 设备打开接口返回错误码 `-100`，便于先调试 UI/权限链路。

## 4. 目录结构

```
android_camera/
├── settings.gradle.kts
├── build.gradle.kts                 # 顶层插件声明
├── gradle/libs.versions.toml        # 版本目录（统一依赖版本）
├── gradle/wrapper/                  # Gradle 8.14.3 wrapper
├── scripts/
│   ├── setup-native.ps1 / .sh       # 拉取 libuvc/libusb 源码（Windows/macOS）
│   └── build.ps1 / build.sh         # 固定 JDK17 + 本地 Gradle 的一键构建
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml      # USB/前台服务/存储权限守卫
        ├── cpp/
        │   ├── CMakeLists.txt       # 有源码编译真库，无源码降级 stub
        │   ├── uvc_native.cpp       # JNI 桥（fd 打开、MJPEG 帧回调）
        │   ├── cmake/
        │   │   ├── build_libusb.cmake
        │   │   ├── build_libuvc.cmake
        │   │   └── config/          # 交叉编译 config.h / uvc_config.h
        │   └── third_party/         # gitignore（脚本拉取）
        ├── res/                     # layout / xml / values / drawable
        └── java/com/wheeltech/blindcam/
            ├── UvcApp.kt            # Application 手工依赖容器
            ├── native/              # NativeUvc（JNI 声明，仅 Domain 可调）
            ├── usb/                 # usb-monitor：枚举/热插拔/权限/连接
            ├── domain/              # UvcCameraManager、UvcCamera、帧池
            ├── render/              # MJPEG 解码 + TextureView 渲染（预留 GL）
            ├── recorder/            # CircularRecorder 循环录像（阶段6）
            ├── service/             # UvcForegroundService 前台保活（阶段7）
            ├── data/                # DataStore 设置（阶段8）
            ├── ui/main/             # Activity + ViewModel + StateFlow
            └── util/                # 日志等
```

## 5. 数据流（约束）

```
libuvc streaming 线程
  → JNI onFrame(byte[], w, h, format)   // 禁止耗时
  → UvcCameraManager 分发
      ├─ render：MJPEG 解码 Bitmap → TextureView（镜像/翻转）
      └─ recorder：MJPEG 分片封装（阶段6，预留 MediaCodec H.264）
```

byte[] 走 `FramePool` 复用；UI 层不允许直接调用 NativeUvc / 持有 USB 句柄。

## 6. 车机专属坑点预案

| 坑点 | 处理 |
|---|---|
| SELinux enforcing 拦截 USB | `nativeOpenFd` 返回失败，UI 提示 `adb shell setenforce 0` 排查 |
| 内核无 UVC 驱动 | 打开失败提示设备不支持 |
| 内核已绑 uvcvideo 驱动 | native 打开时 `libusb_set_auto_detach_kernel_driver` |
| 存储权限碎片化 | 私有目录优先；≤28 旧权限 / 29~32 legacy / ≥33 READ_MEDIA_VIDEO（清单已守卫） |
| 魔改 AOSP 行为差异 | 所有 USB/native 调用均有 try-catch 与错误码 |
| 国内网络 `SSL peer shut down incorrectly`（AAPT2 拉取失败） | `settings.gradle.kts` 已配置阿里云 `public` + `google` 镜像并排在官方源前面；AAPT2（com.android.tools.build）只在 google 仓库，public 镜像不含，必须配 google 镜像 |
| 点“申请权限”无弹窗、立即返回拒绝（结果 intent extras 为空） | **UVC 设备必须先授予 CAMERA 权限**。系统对 USB Video Class 设备做保护：无 CAMERA 时 `UsbManager.requestPermission` 不弹 `UsbPermissionActivity`、直接拒绝。Manifest 已声明 CAMERA + `camera.external` feature，MainActivity 进入即申请 CAMERA，授予后自动衔接 USB 授权弹窗（官方 External USB cameras 文档：直接访问 USB 的 webcam 应用必须和普通相机应用一样持有 CAMERA 权限） |

## 7. 后续开发顺序

2. USB 枚举与权限联调 → 3. 单路预览 Demo → 4. 双路分屏 →
5. 镜像/翻转 → 6. 循环录像（MJPEG→H.264 MP4，总录像分段覆盖）→
7. 前台保活 Service → 8. 设置页 → 9. 四屏/畸变矫正/OpenGL 全景
