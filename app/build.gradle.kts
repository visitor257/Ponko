// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
import java.util.Properties

plugins {
    id("com.android.application")
}

// ============================================================================
// 主体身份（真实姓名 / 联系邮箱）—— 只存在于本机，绝不进仓库
//
// 真实值放在仓库根目录的 `hw_secret.properties`（已列入 .gitignore）：
//     developer_name=<你的实名，与 AGC 后台一字不差>
//     contact_email=<你的工作邮箱>
//
// 仓库里一律只有占位符，所以公开仓库 / 任何 fork 都拿不到真实身份信息；
// 而本机构建出的 APK 里注入的是真实值（华为审核第 4、6 条要求）。
// 缺文件时不报错、退回占位符 —— 保证别人 clone 下来也能构建（只是要自己填）。
// ============================================================================
val hwSecret: Properties = Properties().apply {
    val f = rootProject.file("hw_secret.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hwDevName: String = hwSecret.getProperty("developer_name") ?: "__待填_开发者实名__"
val hwContactEmail: String = hwSecret.getProperty("contact_email") ?: "__待填_联系邮箱__"

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
        // 版本号保持 1.7.2-hw（用户 2026-10-17 指定）：本次是同一版本被驳回后的整改重提，
        // 不另开版本号。⚠ 若 AGC 以「versionCode 已被占用」拒收，只需把这里 +1 重建即可。
        versionName = "1.7.2-hw"
        ndk {
            // LiteRT-LM 与 llama.cpp 的 native 都只跑 arm64 真机，去掉 x86_64 可减小 APK
            abiFilters += "arm64-v8a"
        }

        // 关于页 / 隐私政策里显示的开发者名与邮箱。
        // 值来自本机 hw_secret.properties（不入库），此处不写死任何真实信息。
        // 这两个键与语言无关（人名、邮箱在中英文界面下相同），故只放默认资源。
        resValue("string", "hw_developer_name", hwDevName)
        resValue("string", "hw_contact_email", hwContactEmail)
    }

    buildFeatures {
        // 允许 defaultConfig 里用 resValue(...) 生成字符串资源（新版 AGP 默认关闭）
        resValues = true
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

// ============================================================================
// 隐私政策：仓库里的 privacy.html 是**模板**（只含占位符），
// 构建时把本机真实身份填进去，生成 app/src/main/assets/privacy.html（不入库）。
//
// 为什么不让真实版直接入库：第 4 条要求"应用内隐私政策须与提交的链接内容一致"，
// 所以应用内必须放真实版；但真实姓名/邮箱一旦 push 到公开仓库就无法撤回。
// 于是拆成「模板入库 + 真实版构建时生成」。
// ============================================================================
val genPrivacyAssets by tasks.registering {
    val tpl = rootProject.file("privacy.html")
    val outDir = layout.projectDirectory.dir("src/main/assets")
    val out = outDir.file("privacy.html")
    val name = hwDevName
    val mail = hwContactEmail
    inputs.file(tpl)
    inputs.property("developerName", name)
    inputs.property("contactEmail", mail)
    outputs.file(out)
    doLast {
        out.asFile.parentFile?.mkdirs()
        out.asFile.writeText(
            tpl.readText()
                .replace("__待填_开发者实名__", name)
                .replace("__待填_联系邮箱__", mail)
        )
    }
}

tasks.named("preBuild") { dependsOn(genPrivacyAssets) }

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
