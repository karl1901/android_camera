# =============================================================================
# Windows 本机一键构建脚本（PowerShell 7 / pwsh）
# 固定 JDK 17 与本地 Gradle 8.14.3，不使用 Android Studio 自带的高版本 JBR
# 用法：
#   pwsh scripts/build.ps1                 # 等价于 assembleDebug
#   pwsh scripts/build.ps1 clean           # 清理
#   pwsh scripts/build.ps1 installDebug    # 安装到已连接的车机/设备
#   pwsh scripts/build.ps1 assembleRelease # 任意 Gradle 任务，参数透传
# =============================================================================
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'

# 仓库根目录（脚本位于 scripts/ 下）
$RootDir = Split-Path -Parent $PSScriptRoot

# ---- 本机路径（Windows 开发机，换机器时同步修改这里） ----
$JavaHomeDir = 'D:\karl_word\java\jdk\17'
$LocalGradle = 'D:\karl_word\java\gradle\8.14.3\gradle-8.14.3\bin\gradle.bat'

# 校验 JDK 17
if (-not (Test-Path -LiteralPath (Join-Path $JavaHomeDir 'bin\java.exe'))) {
    throw "未找到 JDK17：$JavaHomeDir，请检查 scripts/build.ps1 中的路径配置"
}
$env:JAVA_HOME = $JavaHomeDir
# 保证本次构建优先使用该 JDK 的 java
$env:PATH = "$JavaHomeDir\bin;$env:PATH"

# SDK 路径通过 local.properties 注入（sdk.dir=C:\Users\karl\AppData\Local\Android\Sdk）
$LocalProps = Join-Path $RootDir 'local.properties'
if (-not (Test-Path -LiteralPath $LocalProps)) {
    Copy-Item -LiteralPath (Join-Path $RootDir 'local.properties.example') -Destination $LocalProps
    Write-Host ">>> 已从 local.properties.example 生成 local.properties，请按需确认 sdk.dir" -ForegroundColor Yellow
}

# 默认任务
if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @('assembleDebug')
}

Set-Location -LiteralPath $RootDir

# native 命令（gradle/gradlew）可能通过 stderr 输出警告（如 SDK XML version 警告）。
# 调用 native 期间不能使用 Stop，否则 stderr 被重定向（2>&1 / CI）时会被包装成
# NativeCommandError 直接终止脚本；改为只认退出码。
$PrevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    if (Test-Path -LiteralPath $LocalGradle) {
        Write-Host ">>> 使用本地 Gradle: $LocalGradle" -ForegroundColor Green
        Write-Host ">>> JAVA_HOME: $JavaHomeDir" -ForegroundColor Green
        & $LocalGradle @GradleArgs
        $code = $LASTEXITCODE
        if ($code -ne 0) { throw "Gradle 构建失败，退出码 $code" }
    } else {
        Write-Host ">>> 本地 Gradle 不存在，回退 wrapper（首次会自动下载 8.14.3）" -ForegroundColor Yellow
        & (Join-Path $RootDir 'gradlew.bat') @GradleArgs
        $code = $LASTEXITCODE
        if ($code -ne 0) { throw "Gradle wrapper 构建失败，退出码 $code" }
    }
} finally {
    $ErrorActionPreference = $PrevEAP
}
