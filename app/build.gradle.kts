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
        // 正式包名。**main 分支用这个**；beta 分支把这里改成 "com.litertchat.app.beta"
        // （独立包名，可以和正式版同时装在一台手机上）。
        //
        // namespace 保持 com.litertchat.app 不变 —— 那只是 R 类与源码的包名，和"装成哪个
        // 应用"无关，改了要动所有 Kotlin 文件的 package 声明。两条线的源码是同一份，只靠
        // 这里和 versionCode 区分。
        //
        // beta 包名的代价（正式版没有）：filesDir 按 applicationId 隔离，模型要重新导入；
        // 插件按隐式 Action 发现宿主，两个宿主都装会去 bind 同一个 Service；磁盘占两份。
        applicationId = "com.litertchat.app"
        minSdk = 28
        targetSdk = 36
        // 上一个对外正式版是 2.1.3 / versionCode 32。
        // 33~36 这几个号被 beta 线用掉了（包名不同，系统不比较，本来互不干扰），
        // 为免以后翻记录时把两条线的号看混，正式线从这里接着往上走：
        // 37 = 2.2.0 正式版：= 对外 beta.1 的全部内容
        //      （采样缓存 EasyCache/UCache、CLIP skip、进度行剩余时间与单位设置、
        //        加载失败带上 sd.cpp 原因、修掉「加载进度被当成采样步」与「预览帧擦掉耗时/ETA」）
        //      + 摘掉模型导入菜单里那条跑不了的「视频（Wan / LTX-V 等）」
        // 38 = 2.2.1：视频生成（generate_video + 帧落盘）、JSON 插件机制（含内置视频页）、
        //      视频模型导入入口加回来（现在真的有出片管道了）
        versionCode = 38
        versionName = "2.2.1"
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
    // 插件 SDK（AIDL 接口 + Parcelable + 基类 Service）。宿主与插件共用同一份定义，
    // 所以宿主也依赖它 —— 不是单向依赖。
    implementation(project(":ponko-plugin-sdk"))
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
