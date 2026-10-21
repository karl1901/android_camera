// 顶层 build 文件：只声明插件版本，不在此应用插件
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
