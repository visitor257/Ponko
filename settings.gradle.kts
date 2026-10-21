// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "LiteRT-Chat"
include(":app")
// 插件 SDK：插件 APK 依赖这个 AAR，包含 AIDL 接口、Parcelable 与基类 Service。
// 与 :app 分开是为了让插件作者只依赖一个 AAR，不必把整个 Ponko 工程拖进去。
include(":ponko-plugin-sdk")
// 示例插件（Echo）已迁出到独立项目 **Ponko-EX**（2026-10-21）。
// 理由：插件不该跟宿主一起发版；Ponko-EX 只依赖 SDK 的 AAR，
// 以后接 VoxCPM2 会在那边另开一条线。
//   Ponko-EX 位置：WorkBuddy\智能Agent\Ponko-EX（构建沙箱 C:\PonkoExBuild）
