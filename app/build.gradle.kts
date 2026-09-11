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
            // sd.cpp 依赖 libc++_shared.so；llmedge 的 AAR 不自带，由 jniLibs 提供一份
            pickFirsts += "**/libc++_shared.so"
            // llmedge 里我们只用 libsdcpp.so（stable-diffusion.cpp，直接读 GGUF）+ libggufreader/libomp；
            // 其余 llama.cpp 变体(7×18-28MB)、whisper、bark 用不到，排掉以控制体积
            excludes += listOf(
                "**/libsmollm*.so",
                "**/libbark_jni.so",
                "**/libwhisper_jni.so",
                "**/libllama*.so",
            )
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
    // 文生图 / 图生图（GGUF）：llmedge 打包了 stable-diffusion.cpp 的 Android 运行时，
    // 直接读 .gguf 绘图模型；Maven 现成 AAR，无需 NDK/CMake。
    // 注意：其 manifest 声明 minSdk 30（Vulkan 后端要求），我们纯 CPU 跑，
    // 通过 tools:overrideLibrary 绕过声明限制以兼容 Android 9+
    implementation("io.github.aatricks:llmedge:0.4.7.2") {
        // 只需它的绘图能力：RAG/OCR/语音/云端下载相关依赖全部剔除，否则白白胖 ~35MB
        exclude(group = "io.gitlab.shubham0204") // sentence-embeddings（拖进 ONNX Runtime）
        exclude(group = "com.google.mlkit")      // text-recognition / image-labeling + native
        exclude(group = "com.tom-roush")         // pdfbox-android（RAG 解析 PDF）
        exclude(group = "io.ktor")               // HF 下载客户端
    }
}
