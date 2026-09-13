# Ponko

> 名字来自日语「ポンコツ」(ponkotsu)——破铜烂铁。脑子不中用，但可以随时换成最好的模型。

[English](README.en.md) · 中文

一个**完全离线**的 Android 本地 AI App：既能聊大语言模型，也能画图、给图片反推标签。

## 对话

支持两种模型格式：

| 格式 | 运行时 | 特性 |
|---|---|---|
| `.litertlm` | Google LiteRT-LM | CPU / GPU / NPU 后端、思考通道、多模态扩展 |
| `.gguf` | llama.cpp | CPU 多线程、会话内 KV 前缀复用（长对话不重复 prefill）|

- **模型管理**：从本地存储选择模型文件，复制到 App 私有目录后可反复选用；模型页可查看 / 删除
- **多对话**：新建 / 切换 / 删除对话，记录持久化在设备本地（`sessions.json`），互不干扰
- **思考分离**：思考过程与正文分开显示、可折叠；思考开关对两种格式都生效
- **Markdown 渲染**：标题、粗斜体、删除线、有序/无序列表、行内代码与代码块、引用、分隔线、表格、可点击链接
- **流式体验**：逐 token 输出；自动跟随滚动，手动上翻即暂停，右下角浮出回底按钮；生成中可随时中断、也可继续打字
- **重新生成**：一键重跑，用随机种子保证结果不重复

## 绘图

引擎是 [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)，读取 **GGUF 格式**的 Stable Diffusion 模型。

> sd.cpp 源码在 `app/src/main/cpp/sd/`，构建时由 NDK + CMake **现场编译**（不再手工塞预编译 `.so`），
> 所以改了 C++ 直接 `assembleRelease` 即可，不存在「源码改了但包内是旧库」的情况。

- **三种模式**：参数区分「文生图 / 图生图 / Tagger」三个子页
  - **文生图**：填提示词直接生成
  - **图生图**：从相册选一张参考图当底稿，配合提示词与「重绘强度」（0.05~0.99）改风格 / 换背景 / 精修；选图后自动把输出尺寸对齐参考图（64 的倍数）
  - **Tagger**：把图片反推成 Danbooru 风格标签（详见下节）
- **参数默认值**：每个参数页都有「设为默认值 / 恢复默认」（点击后需确认）；文生图与图生图**按 LoRA 开/关各存一套默认**，切换 LoRA 开关会自动套用对应那套
- **生成历史**：结果页保留本次运行最近 30 张，可翻看 / 单张删除 / 一键清空；**只存在内存里，关掉 App 即清空**
- **参数**：宽高（64 的倍数）、步数、CFG、种子、采样器、调度器都能调，**重启后自动记住**（提示词不保留）
- **LoRA**：App 内一键下载 LCM-LoRA（HuggingFace 官方 / hf-mirror 双源），可把 20 步压到 4~8 步；也可**导入本地 LoRA**（`.safetensors` 单文件），多份 LoRA 可在列表里点选切换
- **模型导入**：绘图模型直接选**单个 `.gguf` 文件**
- **对话页出图**：语言模型与绘图模型都加载时，直接说「画一张…」就会调用绘图模型生成
- 顶部「参数 / 结果」两个视图可左右滑动切换；结果页可把图片保存到相册

## 打标（Tagger）

- **完全离线**的图像反推标签：导入 WD14 类 ONNX 打标模型（`.onnx`）+ 配套标签表（`selected_tags.csv`），不内置、不下载
- 参数区「Tagger」页：选图 → 设阈值（默认 0.35）/ 最多标签数（默认 40）→ 输出标签
- 标签可一键**发送至文生图 / 图生图**（发送前弹确认框）；结果页也可把生成的图**发送至图生图**或**发送至 Tagger**
- 多份打标模型 / 标签表可在「模型」页列表里点选切换；「加载 / 卸载打标模型」按需加载，用完可卸载释放内存
- 后端跟随「模型」页的运行方式：CPU，或选 GPU 时走 NNAPI

## 多语言

界面文案全部资源化（`res/values` = 英文，`res/values-zh` = 中文），**跟随系统语言**自动切换，无需设置项。

## 构建

需要 JDK 17 + Android SDK（compileSdk 36）+ **NDK 27.3** + **CMake 3.31.6**。

```bash
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk（未签名）
```

签名与交付脚本见 `tools/`：

```powershell
# 用 keystore 签名（固定 v2-only）
powershell -File tools/sign-apk.ps1
# 一键：构建 + 签名 + 拷共享盘 + 上传网盘
powershell -File tools/build-release.ps1
```

- `minSdk 28` / `targetSdk 36`，**仅 arm64-v8a**（原生库限制，需真机，模拟器跑不了）
- 首次全量编译 sd.cpp + ggml 约 8~9 分钟，C++ 未改动时增量仅约 10 秒
- 主要依赖：`com.google.ai.edge.litertlm:litertlm-android:0.17.0`、`net.ladenthin:llama-android:5.1.0`、`com.microsoft.onnxruntime:onnxruntime-android:1.22.0`、`io.noties.markwon:*:4.6.2`
- CMake 现场产出：`libstable-diffusion.so`（sd.cpp 本体）、`libponko_sd.so`（JNI 桥）

## 模型从哪来

- `.litertlm`：HuggingFace 上的 `litert-community` / `google` 组织（Gemma 系列等）
- `.gguf`（对话）：HuggingFace 上任意 GGUF 量化模型；手机 CPU 上建议 **1B~3B、Q4 量化**
- `.gguf`（绘图）：stable-diffusion.cpp 格式的 SD1.5 系模型（如 Anything V5）
- **LoRA**：`lcm-lora-sdv1-5.safetensors`（约 130 MB），App 内可直接下载；本地 `.safetensors` 也可直接导入
- **打标模型**：WD14 Tagger 的 `.onnx` + 配套 `selected_tags.csv`（两者必须成对，如 `wd-v1-4-swinv2-tagger-v2`）

## 已知限制

- GGUF 上下文固定 4096 tokens，超出后依赖 llama.cpp 的 context shift 滑动窗口
- 对话用的 GGUF 目前仅走 CPU（所用绑定未包含 GPU 后端）
- 「关闭思考」依赖模型自带的对话模板，个别模型可能仍会输出思考内容
- 绘图是纯 CPU 推理：256×256 + LCM-LoRA 6 步约 1 分钟，512×512 明显更慢
- 绘图量化等级写死在模型文件里，App 只读取并显示，不会转换
- 绘图原生库用 `-march=armv8.2-a+dotprod+fp16` 编译，需要 2019 年后的 64 位 ARM 设备
- 打标仅支持 WD14 类（Danbooru 标签）ONNX 模型；`.onnx` 与 `selected_tags.csv` 必须配套导入
- 打标首次运行时才加载模型（常驻数百 MB 内存），可在「模型」页手动卸载

## 许可

- **代码**：[MIT](LICENSE)
- **美术资源**（应用图标、角色立绘、原始画稿）：版权归作者所有，保留所有权利，**不在 MIT 许可范围内** —— 详见 [NOTICE](NOTICE)
- **第三方组件**：按各自许可证分发，完整清单见 [NOTICE](NOTICE)

## 第三方组件

| 组件 | 许可证 | 分发方式 |
| --- | --- | --- |
| [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) | MIT | 源码随仓库分发（`app/src/main/cpp/sd/`） |
| [ggml](https://github.com/ggerganov/ggml)（另含 Intel / Codeplay / Arm / Mozilla 的贡献文件） | MIT / Apache-2.0 | 源码随仓库分发（`app/src/main/cpp/sd/ggml/`） |
| sd.cpp 附带第三方文件（stb / json.hpp / httplib / miniz / zip / darts_clone） | Public Domain / MIT / BSD-3-Clause | 源码随仓库分发（`app/src/main/cpp/sd/thirdparty/`） |
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | Apache-2.0 | Gradle 依赖 |
| [llama.cpp](https://github.com/ggerganov/llama.cpp)（经 java-llama.cpp 绑定） | MIT | Gradle 依赖 |
| [Markwon](https://github.com/noties/Markwon) | Apache-2.0 | Gradle 依赖 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | Gradle 依赖（打标 Tagger） |
| Kotlin / kotlin-reflect / kotlinx.coroutines / AndroidX | Apache-2.0 | Gradle 依赖 |
| [commonmark-java](https://github.com/commonmark/commonmark-java)（Markwon 引入） | BSD-2-Clause | Gradle 传递依赖 |
| [Jackson](https://github.com/FasterXML/jackson)（llama.cpp 绑定引入） | Apache-2.0 | Gradle 传递依赖 |
| [Gson](https://github.com/google/gson)（LiteRT-LM 引入） | Apache-2.0 | Gradle 传递依赖 |
| [SLF4J API](https://www.slf4j.org/)（llama.cpp 绑定引入） | MIT | Gradle 传递依赖 |
| FastDoubleParser / Schubfach（Jackson 打包内） | MIT / Boost-1.0 | 随 Jackson 打包（`META-INF/*-LICENSE`） |
| 注解类库（jspecify / checker-qual / error_prone_annotations / JetBrains annotations） | Apache-2.0 / MIT | Gradle 传递依赖（仅注解，无运行时代码） |
| 静态链入 LiteRT-LM 原生库（LiteRT / TFLite、XNNPACK、sentencepiece、HF tokenizers、re2、Abseil、cpuinfo、protobuf、flatbuffers、zlib） | Apache-2.0 / BSD-3-Clause / BSD-2-Clause / zlib | 编入 `liblitertlm_jni.so` |
| 静态链入 ONNX Runtime 原生库（XNNPACK、protobuf、ONNX、Abseil、flatbuffers、re2、cpuinfo 等） | Apache-2.0 / BSD-3-Clause / BSD-2-Clause | 编入 `libonnxruntime.so` |
| LLVM libc++ / libomp（来自 Android NDK） | Apache-2.0 with LLVM Exceptions | `app/src/main/jniLibs/` |

> stable-diffusion.cpp 源码为随仓库分发的副本，为减小体积剔除了本项目用不到的分词器词表（仅保留 CLIP）。
> 本项目**不含任何模型权重文件**；绘图模型（SD1.5 GGUF）、对话模型（`.litertlm` / `.gguf`）、LoRA 以及打标模型（`.onnx` + `selected_tags.csv`）均由使用者自行获取。
> ONNX Runtime 自身还打包了若干第三方组件，其声明见 [licenses/onnxruntime-ThirdPartyNotices.txt](licenses/onnxruntime-ThirdPartyNotices.txt)。
> commonmark / Jackson / Gson / SLF4J 等为**传递依赖**，由所选的 Markwon、llama.cpp 绑定、LiteRT-LM 自动引入。
> 第三方原生库里静态链入的组件（XNNPACK、protobuf、re2、cpuinfo、zlib 等）与各类许可证全文见 [licenses/](licenses/) 与 [NOTICE](NOTICE)。

## 致谢

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)（Google）
- [java-llama.cpp](https://github.com/kherud/java-llama.cpp) / llama.cpp
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)
- [ggml](https://github.com/ggerganov/ggml)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)（Microsoft）
- [Markwon](https://github.com/noties/Markwon)
- [commonmark-java](https://github.com/commonmark/commonmark-java)
