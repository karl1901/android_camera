pluginManagement {
    repositories {
        mavenLocal()
        // 阿里云 public：聚合 central / jcenter 等（Kotlin、协程等）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 阿里云 google 镜像：AGP 插件、AAPT2（com.android.tools.build）只在 google 仓库，
        // 国内直连 dl.google.com 会出现 "SSL peer shut down incorrectly"，必须走镜像
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        // 阿里云 public：聚合 central（Kotlin、协程、DataStore 等）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 阿里云 google 镜像：AAPT2、AndroidX、Material 等 google 仓库内容，
        // 必须排在官方 google() 前面，规避国内 TLS 握手被中断
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}

rootProject.name = "UvcBlindCam"
include(":app")