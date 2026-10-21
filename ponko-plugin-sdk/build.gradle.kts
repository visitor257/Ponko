// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// Ponko 插件 SDK —— 供插件 APK 依赖的 AAR。
//
// 这个 module 只含「接口约定」：AIDL、Parcelable、常量、基类 Service。
// 不含任何模型推理代码，也不依赖 app 的任何实现 —— 插件作者只需要这一个 AAR。
plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    // 发布 AAR，供 **Ponko-EX**（独立插件项目）依赖。
    // 不这么做的后果：插件项目就得复制一份 SDK 源码，两边必然分叉。
    id("maven-publish")
}

android {
    namespace = "com.litertchat.plugin"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        // SDK 是纯接口 + 基类，没有任何原生代码，所以不需要 abiFilters
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ⚠ AGP 9.x 里 AIDL **默认是关的**（不像早期版本默认开）——
    // 不开的话 .aidl 完全不参与编译，Kotlin 侧会报 "Unresolved reference 'IPonkoPlugin'"。
    buildFeatures {
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // maven-publish 需要显式声明发布哪个变体（AGP 9 要求）
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

/**
 * 发布到本地 maven 仓库（ASCII 路径 —— AGP 拒绝中文路径）。
 *
 * Ponko-EX 项目通过 `maven { url = uri("file:///C:/ponko-maven") }` 消费它。
 * 发布命令：`gradle :ponko-plugin-sdk:publishReleasePublicationToLocalRepoRepository`
 *
 * ⚠ 改 AIDL / Parcelable 后**必须重新发布**，否则 Ponko-EX 用的还是旧接口 ——
 * 症状是运行时 `NoSuchMethodError` 或 Parcel 读写错位，非常难查。
 * 版本号改 API_VERSION 时也要同步改 [VERSION]。
 */
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.litertchat.ponko"
            artifactId = "plugin-sdk"
            version = "1.0.0"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "localRepo"
            url = uri("file:///C:/ponko-maven")
        }
    }
}

dependencies {
    // 基类 Service 里用了 androidx 的注释做 API 检查；不引入 androidx 是为了让插件
    // 作者的依赖树尽量干净 —— 所以这里一个依赖都不加。
}
