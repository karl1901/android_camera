# =============================================================================
# 拉取 libuvc / libusb 第三方源码（pinned 版本，源码不入 Git，由 CMake 直接编译）
# Windows 版（PowerShell 7 / pwsh），等价于 scripts/setup-native.sh
# 用法：pwsh scripts/setup-native.ps1
# 前置：已安装 Git 且 git 在 PATH 中
# =============================================================================

$ErrorActionPreference = 'Stop'

# 仓库根目录（脚本位于 scripts/ 下）
$RootDir = Split-Path -Parent $PSScriptRoot
$TpDir = Join-Path $RootDir 'app\src\main\cpp\third_party'
New-Item -ItemType Directory -Force -Path $TpDir | Out-Null
Set-Location -LiteralPath $TpDir

# 版本锁定（与 cmake/config 头里的版本保持一致，升级需同步修改并重新验证）
$LibusbTag = 'v1.0.27'   # 含 libusb_wrap_sys_device，支持 Android fd 包装
$LibuvcTag = 'v0.0.7'    # 含 uvc_wrap_sys_device，MJPEG 透传

# git 的进度/提示信息走 stderr，Stop 模式下被重定向（2>&1/CI）时会包装成
# NativeCommandError 误判失败；git 调用期间改为只认退出码
$PrevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    if (-not (Test-Path -LiteralPath (Join-Path $TpDir 'libusb'))) {
        Write-Host ">>> cloning libusb $LibusbTag"
        git clone --depth 1 --branch $LibusbTag 'https://github.com/libusb/libusb.git'
        if ($LASTEXITCODE -ne 0) { throw 'libusb 克隆失败' }
    } else {
        Write-Host '>>> libusb already exists, skip'
    }

    if (-not (Test-Path -LiteralPath (Join-Path $TpDir 'libuvc'))) {
        Write-Host ">>> cloning libuvc $LibuvcTag"
        git clone --depth 1 --branch $LibuvcTag 'https://github.com/libuvc/libuvc.git'
        if ($LASTEXITCODE -ne 0) { throw 'libuvc 克隆失败' }
    } else {
        Write-Host '>>> libuvc already exists, skip'
    }
} finally {
    $ErrorActionPreference = $PrevEAP
}

Write-Host ''
Write-Host "完成。第三方源码位于：$TpDir"
Write-Host '接下来在 Android Studio 中 Sync Project，或执行：pwsh scripts/build.ps1'
