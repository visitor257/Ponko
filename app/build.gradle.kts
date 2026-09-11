plugins {
    id("com.android.application")
}

android {
    namespace = "com.litertchat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.litertchat.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            // LiteRT-LM 与 llama.cpp 的 native 都只跑 arm64 真机，去掉 x86_64 可减小 APK
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // 自编的 libstable-diffusion.so（+ libponko_sd.so / libomp.so）依赖它
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    // Google LiteRT-LM Android API (loads & runs .litertlm models)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")
    // Coroutines (the streaming sendMessageAsync returns a Flow)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // GGUF (llama.cpp) local model runtime — full llama.cpp API incl. KV-cache reuse
    // (net.ladenthin:llama-android AAR requires minSdk 28, native = arm64-v8a)
    implementation("net.ladenthin:llama-android:5.1.0")
    // Markdown rendering for AI replies (bold/lists/headings/code/tables/links)
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    // 绘图（文生图）：自编的 stable-diffusion.cpp（arm64-v8a）。native 库直接放在
    // app/src/main/jniLibs/arm64-v8a/：libstable-diffusion.so + libponko_sd.so(JNI 桥) + libomp.so
    // 相比现成 AAR，这里能直接控制线程数 / 采样器 / 调度器 / LoRA / 量化类型 / 取消。
}
