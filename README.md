# Ponko

> 名字来自日语「ポンコツ」(ponkotsu)——破铜烂铁。脑子不中用，但可以随时换成最好的模型。

[English](README.en.md) · 中文

一个**能够离线运行**的 Android 本地 AI App：聊大语言模型（**支持发图片、发文件**）、画图、给图片反推标签。

模型推理全部在本机完成：除了「手动下载 LoRA」，其余功能都不需要联网。

## 对话

支持两种模型格式：

| 格式 | 运行时 | 特性 |
|---|---|---|
| `.litertlm` | Google LiteRT-LM | CPU / GPU / NPU 后端、思考通道、多模态扩展 |
| `.gguf` | llama.cpp | CPU 多线程、会话内 KV 前缀复用（长对话不重复 prefill）|

- **模型管理**：从本地存储选择模型文件，复制到 App 私有目录后可反复选用；「模型」页顶部选运行方式（CPU / GPU），下面分「对话模型 / 绘图模型 / 打标模型」三个子页（左右滑动切换），各类模型均可查看 / 选择 / 删除
  - 运行方式（CPU / GPU）的作用范围：**只有三条路径带 GPU 代码**——**绘图**（Vulkan）、**打标**（NNAPI）、**对话 `.litertlm`**（LiteRT-LM 的 GPU，底层是 OpenCL / Vulkan）；**对话 GGUF 始终 CPU**（所用绑定未含 GPU 后端）。这三条 GPU 路径**目前都只在构建侧验证过，均未经真机实测**——理论上可用，实际能不能用取决于机型的驱动与显存；设备不支持或初始化失败会自动回退 CPU，绘图页状态行会显示实际使用的后端
- **多对话**：新建 / 切换 / 删除对话，记录持久化在设备本地（`sessions.json`），互不干扰
- **思考分离**：思考过程与正文分开显示、可折叠；思考开关对两种格式都生效
- **Markdown 渲染**：标题、粗斜体、删除线、有序/无序列表、行内代码与代码块、引用、分隔线、表格、可点击链接
- **流式体验**：逐 token 输出；自动跟随滚动，手动上翻即暂停，右下角浮出回底按钮；生成中可随时中断、也可继续打字
- **重新生成**：一键重跑，**会带上这一轮原来的图片与文件**（还原到输入框上方的附件条，可直接看到）；用随机种子保证结果不重复
- **图片输入（多模态）**：输入框左侧「＋」向上展开抽屉，可选「拍照 / 图库」，一次最多 4 张；图片直接显示在气泡里，**点击全屏查看（双指缩放 / 拖动 / 双击放大）**，**长按**弹出菜单：保存到相册 / 引用（放进输入栏待发）/ 发送至图生图 / 发送至 Tagger
  - `.litertlm`：需**多模态模型**（如 Gemma 3n）；App 会自动探测模型是否支持视觉输入，不支持时点「＋」直接提示
  - `.gguf`：需**视觉模型 + 配套 `mmproj` 文件**；在「模型」页·对话模型卡片里导入并选择 mmproj，**必须选好 mmproj 再加载模型**
- **文件输入（纯文本类）**：同一个「＋」抽屉里的第三项「文件」，一次最多 2 个；支持 `.txt` / `.md` / `.json` / `.csv` / `.log` / `.xml` / 各类代码等文本文件（UTF-8 / GBK 自动识别；二进制文件直接拒收，PDF 暂不支持）；正文按上下文预算自动截断后**随那一条消息**发给模型，不进历史；气泡上显示文件名
- **对话参数**：「模型」页·对话模型里可调上下文长度、最大输出、温度、Top-K、Top-P、重复惩罚、思考预算、随机种子；**按模型分别保存**，输入即存；上下文长度与最大输出要**重新加载模型**才生效
- **看不见图的对话模型可以「借」打标模型看图**：对话模型本身不支持图片（没有多模态、也没配 mmproj）但已加载打标模型时，模型会自己调用打标模型（输出 `<tag>` 命令 → App 对图片打标 → 标签作为工具结果回传），再据此回答；阈值与标签数由模型自定，BGR/RGB 跟随绘图页 Tagger 的设置；自己能看图的模型不会走这条路

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
- **模型导入**：两种方式——**单文件**（一个 `.gguf`；SD1.x/2、SDXL、SD3/3.5、Flux、Qwen-Image 的 all-in-one 版都走这条）与**多文件**（按家族补齐槽位：SD3/3.5、Flux、Qwen-Image、HiDream、视频等）。多文件时先选类型（家族）与主模型，再在「模型槽位」里逐个补配套文件；每个模型集的文件存在各自子目录里，列表里可点选 / 长按删除
  - **SDXL 只支持单文件**：上游 sd.cpp 要在「第二个文本编码器与主模型在同一个文件里」才能认出 SDXL，拆成 unet + CLIP-L/CLIP-G 多个文件的 SDXL 加载会直接失败——请用单文件 SDXL（有 2.5GB 级的量化单文件）
  - 多文件真正适用的是「官方本来就拆开分发」的家族：Flux、SD3.5、Qwen-Image 等；这类模型最小组合也在 8GB 以上，手机上基本跑不动（机制已支持，留给大内存设备 / 桌面端使用）
- **GPU 加速（可选，⚠️ 仅构建侧验证、未真机实测）**：在「模型」页把运行方式切到 **GPU**，绘图会使用设备的 Vulkan 后端（文生图 / 图生图都生效）；设备没有 Vulkan 或初始化失败会**自动回退 CPU**，实际用的是哪个后端会显示在绘图页状态行
- **对话页出图**：语言模型与绘图模型都加载时，直接说「画一张…」就会调用绘图模型生成
- 顶部「参数 / 结果」两个视图可左右滑动切换；结果页可把图片保存到相册

## 打标（Tagger）

- **本机离线**的图像反推标签：导入 Danbooru 系 ONNX 打标模型（`.onnx`，如 WD14）+ 配套标签表（`selected_tags.csv`），不内置、不下载
- 参数区「Tagger」页：选图 → 设阈值（默认 0.35）/ 最多标签数（默认 40）/ **通道顺序**（默认 BGR）→ 输出标签
  - 「通道顺序」是给不同预处理模型留的开关：WD 系官方预处理是 BGR；少数重导出模型把 BGR 烘进了权重，这时切到 RGB 才对（颜色 / 发色识别错时可切换试试）
- 标签可一键**发送至文生图 / 图生图**（发送前弹确认框）；结果页也可把生成的图**发送至图生图**或**发送至 Tagger**
- 多份打标模型 / 标签表可在「模型」页列表里点选切换；「加载 / 卸载打标模型」按需加载，用完可卸载释放内存
- 加载后的打标模型还可以被**看不见图的对话模型当工具调用**（模型输出 `<tag>` 命令 → App 打标 → 标签回传 → 模型据此作答），详见「对话」一节
- 后端跟随「模型」页的运行方式：CPU，或选 GPU 时走 NNAPI

## 权限

App **只声明 3 个权限**，且都不是敏感权限：拍照、选图、保存到相册都**不需要**申请存储读取权限。

| 用途 | 是否需要权限 | 说明 |
| --- | --- | --- |
| 拍照 | **不需要 `CAMERA`** | 通过 `ACTION_IMAGE_CAPTURE` 交给系统相机 App 去拍，拍照由相机应用完成，App 只接收结果。若声明了 `CAMERA` 却未获授权，反而会抛 `SecurityException`，所以是故意不声明的 |
| 选图（图库 / 文件） | **不需要存储读取权限** | 走系统文件选择器 `ACTION_GET_CONTENT`，只对你**亲自点中的那一个文件**拿到临时读取授权（`content://`），不是整个存储；因此也不需要 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` |
| 保存图片到相册 | **Android 10+ 不需要** | 通过 `MediaStore` 写入自己创建的媒体文件，属系统允许的免权限操作 |
| 保存图片 / 拍照 | **Android 9 及以下需要** | 这两项需要 `WRITE_EXTERNAL_STORAGE`（manifest 里以 `maxSdkVersion="28"` 声明，仅在 9 及以下生效），且**只在 Android 9 上首次保存/拍照时才弹窗申请**；Android 10 及以上永远不会请求它 |
| 下载 LoRA | `INTERNET`、`ACCESS_NETWORK_STATE` | 只用于「LoRA 加速」里手动下载 LCM-LoRA（HuggingFace / hf-mirror）。**不下载就不联网**，其余功能无需联网 |
| 模型 / 会话 / 图片存储 | **不需要权限** | 全部存在 App 私有目录（`filesDir/`），属应用沙箱 |

- 相机、图库返回的 `content://` 授权是**临时的**，所以导入时会立刻复制一份到私有目录（这也是「选完模型要复制」的原因）
- `<queries>` 里的 `IMAGE_CAPTURE` 是 Android 11+ 的**包可见性**声明，不是权限
- 不申请位置、通讯录、麦克风、通知、后台运行等任何权限

## 多语言

界面文案全部资源化（`res/values` = 英文，`res/values-zh` = 中文），**跟随系统语言**自动切换，无需设置项。

底部第四个标签是「关于」页：版本号与安装时间、应用简介、思考模式说明、许可与第三方组件清单、作者与项目地址。

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
- Vulkan 后端（可选 GPU 绘图）不需要安装 Vulkan SDK：`glslc` 与 SPIRV 头文件都用 NDK 自带的；交叉编译时还需要一个**宿主编译器**来构建着色器生成工具（Windows 上是 MSVC，`tools/build-release.ps1` 会自动载入 `vcvars64` 环境）

## 模型从哪来

- `.litertlm`：HuggingFace 上的 `litert-community` / `google` 组织（Gemma 系列等）
- `.gguf`（对话）：HuggingFace 上任意 GGUF 量化模型；手机 CPU 上建议 **1B~3B、Q4 量化**
- `.gguf`（绘图）：stable-diffusion.cpp 格式的 SD1.5 系模型（如 Anything V5）
- **LoRA**：`lcm-lora-sdv1-5.safetensors`（约 130 MB），App 内可直接下载；本地 `.safetensors` 也可直接导入
- **打标模型**：Danbooru 系 tagger 的 `.onnx` + 配套标签表 CSV（两者必须成对；如 WD14 的 `wd-v1-4-swinv2-tagger-v2` 与 `selected_tags.csv`）

## 已知限制

- 对话上下文默认 4096 tokens（可在「模型」页调整；LiteRT-LM 侧不能超过模型自带的上限），超出后依赖 llama.cpp 的 context shift 滑动窗口
- 对话用的 GGUF 目前仅走 CPU（所用绑定未包含 GPU 后端）
- 「关闭思考」依赖模型自带的对话模板，个别模型可能仍会输出思考内容
- 绘图默认是纯 CPU 推理：256×256 + LCM-LoRA 6 步约 1 分钟，512×512 明显更慢；把运行方式切到 GPU 可尝试 Vulkan 加速，设备不支持会自动回退 CPU（实际后端显示在绘图页状态行）
- **GPU 支持目前只是「理论上可用」，未经真机验证**：只有绘图（Vulkan）、打标（NNAPI）、对话 `.litertlm` 这三条路径带 GPU 代码，三者都只在构建侧验证过，**未在任何手机上实测**；机型驱动不稳 / 显存不足时自动回退 CPU（绘图页状态行会显示实际后端）；对话 GGUF 没有任何 GPU 后端，只能 CPU
- 绘图量化等级写死在模型文件里，App 只读取并显示，不会转换
- 绘图原生库用 `-march=armv8.2-a+dotprod+fp16` 编译，需要 2019 年后的 64 位 ARM 设备
- 打标支持 Danbooru 系的 ONNX 打标模型（WD14 及其衍生版本等；标签表需为 `tag_id,name,category,count` 格式）；`.onnx` 与标签表 CSV 必须配套导入
- 打标结果离谱（例如发色 / 颜色认错）时先试切换「通道顺序」（BGR / RGB）：BGR 是 WD 系官方预处理，个别重导出模型已把 BGR 烘进权重、需要切 RGB；阈值与标签数也会明显影响结果
- 打标首次运行时才加载模型（常驻数百 MB 内存），可在「模型」页手动卸载
- 保存到相册 / 拍照在 **Android 9** 上需要存储权限（首次操作会弹窗）；拒绝授权则该操作失败（Android 10+ 不受影响）
- 图片输入的张数上限为 4 张、文件上限 2 个；`.gguf` 视觉模型必须搭配对应的 `mmproj`，且需在加载模型前选好
- 文件输入只支持纯文本类文件：PDF 暂不支持（后续计划转成图片走多模态），二进制文件直接拒收；正文默认按约 1200 tokens 截断
- 上下文放不下时会自动裁剪更早的对话与文件正文；仍然放不下会给出提示：开个新会话、换短一点的文件、少带几轮旧对话

## 许可

- **代码**：[MIT](LICENSE)
- **美术资源**（应用图标、角色立绘、原始画稿）：版权归作者所有，保留所有权利，**不在 MIT 许可范围内** —— 详见 [NOTICE](NOTICE)
- **第三方组件**：按各自许可证分发，完整清单见 [NOTICE](NOTICE)

## 第三方组件

| 组件 | 许可证 | 分发方式 |
| --- | --- | --- |
| [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) | MIT | 源码随仓库分发（`app/src/main/cpp/sd/`） |
| [ggml](https://github.com/ggerganov/ggml)（另含 Intel / Codeplay / Arm / Mozilla 的贡献文件） | MIT / Apache-2.0 | 源码随仓库分发（`app/src/main/cpp/sd/ggml/`） |
| [Vulkan-Hpp](https://github.com/KhronosGroup/Vulkan-Hpp) / [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) | Apache-2.0 OR MIT | 源码随仓库分发（`app/src/main/cpp/thirdparty/include/`，Vulkan 后端编译需要） |
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
