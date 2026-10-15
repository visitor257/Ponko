// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
plugins {
    id("com.android.application")
}

android {
    namespace = "com.litertchat.app"
    compileSdk = 36
    // 自编 stable-diffusion.cpp 所用的 NDK（须与 jniLibs 里的 libomp.so 同源）
    ndkVersion = "27.3.13750724"

    defaultConfig {
        // hw 分支：包名加 .hw 后缀，让上架版与主线版能**同机共存、数据互不影响**
        // （Android 以 applicationId 区分 App；filesDir / 偏好 / 会话库都随它走）。
        // 界面上显示的名字仍是 "Ponko"（AndroidManifest 的 android:label），两版一致。
        // ⚠ 华为 AGC 首次发布后 applicationId 不可更改，这里定下即永久。
        applicationId = "com.litertchat.app.hw"
        minSdk = 28
        targetSdk = 36
        versionCode = 15
        // hw 分支：华为应用市场专用版本，版本名带 -hw 后缀以示区分（见 .workbuddy/memory）
        versionName = "1.7.2-hw"
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

    // 自编 stable-diffusion.cpp：源码在 src/main/cpp（含内联 ggml），
    // 每次 assembleRelease 由 NDK 现场编译，产物直接进 APK，不再手工往 jniLibs 拷贝。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
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
    implementation("androidx.viewpager:viewpager:1.0.0")
    // 打标（Tagger）：ONNX Runtime，运行用户导入的 WD14 类 .onnx 模型（arm64-v8a）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // PDF 文本抽取（对话发送 PDF）：Tom Roush 的 PDFBox Android 移植
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // 绘图（文生图）：自编的 stable-diffusion.cpp（arm64-v8a），源码在 src/main/cpp，
    // 由 NDK/CMake 现场编译出 libstable-diffusion.so + libponko_sd.so（JNI 桥）。
    // 相比现成 AAR，这里能直接控制线程数 / 采样器 / 调度器 / LoRA / 量化类型 / 取消。
}
