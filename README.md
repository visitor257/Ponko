# Ponko

> 名字来自日语「ポンコツ」(ponkotsu)——破铜烂铁。脑子不中用，但可以随时换成最好的模型。

[English](README.en.md) · 中文

一个**能够离线运行**的 Android 本地 AI App：聊大语言模型（**支持发图片、发文件与文档**，还能把手机当 API 服务器）、画图、给图片反推标签。

模型推理全部在本机完成：除了「手动下载 LoRA」，其余功能都不需要联网。

## 对话

支持两种模型格式：

| 格式 | 运行时 | 特性 |
|---|---|---|
| `.litertlm` | Google LiteRT-LM | CPU / GPU / NPU 后端、思考通道、多模态扩展 |
| `.gguf` | llama.cpp | CPU 多线程、会话内 KV 前缀复用（长对话不重复 prefill）|

- **模型管理**：从本地存储选择模型文件，复制到 App 私有目录后可反复选用；「模型/API」页顶部选运行方式（CPU / GPU），下面分「对话模型 / 绘图模型 / 打标（Tagger）」三个子页（左右滑动切换），其中「对话模型」与「绘图模型」页内各自再分「模型 / API」两个子页；各类模型均可查看 / 选择 / 删除
  - 运行方式（CPU / GPU）的作用范围：**只有三条路径带 GPU 代码**——**绘图**（Vulkan）、**打标**（NNAPI）、**对话 `.litertlm`**（LiteRT-LM 的 GPU，底层是 OpenCL / Vulkan）；**对话 GGUF 始终 CPU**（所用绑定未含 GPU 后端）。这三条 GPU 路径**目前都只在构建侧验证过，均未经真机实测**——理论上可用，实际能不能用取决于机型的驱动与显存；设备不支持或初始化失败会自动回退 CPU，绘图页状态行会显示实际使用的后端
- **多对话**：新建 / 切换 / 删除对话，记录持久化在设备本地（`sessions.json`），互不干扰
- **思考分离**：思考过程与正文分开显示、可折叠；思考开关对两种格式都生效
- **Markdown 渲染**：标题、粗斜体、删除线、有序/无序列表、行内代码与代码块、引用、分隔线、表格、可点击链接
- **流式体验**：逐 token 输出；自动跟随滚动，手动上翻即暂停，右下角浮出回底按钮；生成中可随时中断、也可继续打字
- **重新生成**：一键重跑，**会带上这一轮原来的图片与文件**（还原到输入框上方的附件条，可直接看到）；用随机种子保证结果不重复
- **图片输入（多模态）**：输入框左侧「＋」向上展开抽屉，可选「拍照 / 图库」，一次最多 4 张；图片直接显示在气泡里，**点击全屏查看（双指缩放 / 拖动 / 双击放大）**，**长按**弹出菜单：保存到相册 / 引用（放进输入栏待发）/ 发送至图生图 / 发送至 Tagger
  - `.litertlm`：需**多模态模型**（如 Gemma 3n）；App 会自动探测模型是否支持视觉输入，不支持时点「＋」直接提示
  - `.gguf`：需**视觉模型 + 配套 `mmproj` 文件**；在「模型/API」页·对话模型里导入并选择 mmproj，**必须选好 mmproj 再加载模型**
- **文件 / 文档输入**：同一个「＋」抽屉里的第三项「文件」，一次最多 2 个
  - **纯文本**：`.txt` / `.md` / `.json` / `.csv` / `.log` / `.xml` / 各类代码等（UTF-8 / GBK 自动识别）
  - **文档**：`.docx` / `.pptx` / `.xlsx` / `.odt` / `.ods` / `.odp` / `.epub` / `.rtf` / `.html` / **`.pdf`** —— 抽出正文（表格按制表符分列、段落分行、幻灯片 / 工作表 / 页数会标注在附件上）后按上下文预算截断
  - `.doc` / `.xls` / `.ppt`（97-2003 老二进制格式）会明确提示「请另存为 .docx / .xlsx / .pptx 或 PDF」
  - 正文**随那一条消息**发给模型，不进历史；气泡上显示文件名与字数（截断会标注）
- **对话参数**：「模型/API」页·对话模型里可调上下文长度、最大输出、温度、Top-K、Top-P、重复惩罚、思考预算、随机种子；**按模型分别保存**，输入即存；上下文长度与最大输出要**重新加载模型**才生效
- **看不见图的对话模型可以「借」打标模型看图**：对话模型本身不支持图片（没有多模态、也没配 mmproj）但已加载打标模型时，模型会自己调用打标模型（输出 `<tag>` 命令 → App 对图片打标 → 标签作为工具结果回传），再据此回答；阈值与标签数由模型自定，BGR/RGB 跟随绘图页 Tagger 的设置；自己能看图的模型不会走这条路
- **API 服务端（把手机当服务器）**：在「模型/API」页 · 对话模型 · 顶部「API」子页里一键开启，把**已加载**的本地模型开放成 **OpenAI 兼容**接口（`POST /v1/chat/completions` 流式/非流式、`GET /v1/models`、`GET /health`）；同一网络的手机 / 电脑 / 任何 OpenAI 客户端都能直接连。支持可选 API key、自定义端口、常驻通知与锁屏不断线（详见下节）。**绘图模型也能单独开放**成图像生成接口，它有一整套自己的设置（端口 / key / 开关），与对话这边互不影响

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
- **参数**：宽高（64 的倍数）、步数、CFG、种子、采样器、调度器都能调，**重启后自动记住**（提示词不保留）。可选采样器已与 sd.cpp 完全对齐（含 DPM++ 2M SDE / SDE B&T / LMS / Res Multistep 等）
- **深色模式**：「关于」页的「设置」分区里是三档滑块（**亮 / 跟随系统 / 暗**），重启后记住；切换**就地换色**、不重启 App，已加载的模型与会话都保留；页面底、卡片、输入框、按钮、对话框、状态栏与导航栏全部跟随
- **LoRA**：App 内一键下载 LCM-LoRA（HuggingFace 官方 / hf-mirror 双源），可把 20 步压到 4~8 步；也可**导入本地 LoRA**（`.safetensors` 单文件），多份 LoRA 可在列表里点选切换
- **模型导入**：两种方式——**单文件**（一个 `.gguf`；SD1.x/2、SDXL、SD3/3.5、Flux、Qwen-Image 的 all-in-one 版都走这条）与**多文件**（按家族补齐槽位：SD3/3.5、Flux、Qwen-Image、HiDream、视频等）。多文件时先选类型（家族）与主模型，再在「模型槽位」里逐个补配套文件；每个模型集的文件存在各自子目录里，列表里可点选 / 长按删除
  - **SDXL 只支持单文件**：上游 sd.cpp 要在「第二个文本编码器与主模型在同一个文件里」才能认出 SDXL，拆成 unet + CLIP-L/CLIP-G 多个文件的 SDXL 加载会直接失败——请用单文件 SDXL（有 2.5GB 级的量化单文件）
  - 多文件真正适用的是「官方本来就拆开分发」的家族：Flux、SD3.5、Qwen-Image 等；这类模型最小组合也在 8GB 以上，手机上基本跑不动（机制已支持，留给大内存设备 / 桌面端使用）
- **GPU 加速（可选，⚠️ 仅构建侧验证、未真机实测）**：在「模型/API」页把运行方式切到 **GPU**，绘图会使用设备的 Vulkan 后端（文生图 / 图生图都生效）；设备没有 Vulkan 或初始化失败会**自动回退 CPU**，实际用的是哪个后端会显示在绘图页状态行
- **对话页出图**：语言模型与绘图模型都加载时，直接说「画一张…」就会调用绘图模型生成
- **绘图接口（可选）**：把已加载的绘图模型开放成 OpenAI 兼容的 `/v1/images/generations`，供局域网内的客户端出图；默认不开放，开关在「模型/API」页 · 绘图模型 · 「API」子页（详见「API 服务端」一节）
- 顶部「参数 / 结果」两个视图可左右滑动切换；结果页可把图片保存到相册

## API 服务端（把手机当服务器）

把手机上**已经加载**的对话模型开放成 OpenAI 兼容接口，其它设备用现成的客户端就能调用——手机上跑推理，客户端只当界面。

> **对话与绘图是两套独立的东西**，各有各的端口、key、开关和统计（默认 8080 / 8081），
> 可以只开一个、也可以两个都开。区别在于服务对象、资源占用与安全暴露面完全不同——
> 出图一张要几十秒且吃满 CPU、通常只该在可信网络里开，而对话接口是给人天天连的。
> 两者**共用一个前台服务与一条常驻通知**（Android 的前台服务只是「保住进程」的机制，起两个只会多出一条通知）。

- **怎么开**：「模型/API」页 → 对话模型 → 上方「模型 / API」子页签切到 **API** → 点「启动服务」。**启动服务本身不要求先加载模型**：服务只是把 HTTP 端口架起来，没加载对话模型时对话接口返回 503（只影响那一个接口），加载之后立刻可用
- **接口**
  | 方法 | 路径 | 说明 |
  |---|---|---|
  | POST | `/v1/chat/completions` | 对话；`"stream": true` 走 SSE 流式（标准 `data: {...}` + `data: [DONE]`） |
  | GET | `/v1/models` | 当前模型（OpenAI 格式） |
  | GET | `/health` | 存活探测：模型名 / 是否就绪 / 是否忙 |
- **客户端怎么填**：Base URL = `http://<手机在局域网里的 IP>:8080/v1`（App 里会直接显示地址，点一下即复制），API Key 填你设置的（没设就随便填，客户端一般要求非空）
- **端口 / key**：端口默认 **8080**（可改，改完自动重启该服务生效）；API key **可选**——不设就是不校验
- **运行方式**：前台服务（`specialUse` 类型，避开 Android 15+ 对 dataSync 的 6 小时限制）+ 常驻通知 + WifiLock，App 退到后台、锁屏都继续服务；通知里可直接「停止」
- **一次一个请求**：本地模型跑不了并发，忙时返回 429（标准限流语义），客户端重试即可
- **上下文续跑**：客户端每轮会重发整段历史；服务端能识别「同一段历史 + 新问题」并复用已有会话（gguf 走 llama.cpp 的 KV 复用），不会每轮从头算
- **安全提示**：服务监听本机**所有**网络接口（含公网地址）。不设 key 时，任何能连到该地址的人都能调用你的模型和电量——**是否对公网开放请自行评估**（建议设 key，或只在可信网络里用）

### 绘图接口（`/v1/images/generations`）

把**已经加载**的绘图模型开放成一个 OpenAI 兼容的文生图接口，客户端传一句 prompt 就能让手机出图。
**它是独立的一套**：自己的端口（默认 **8081**）、自己的 key、自己的开关，与对话 API 互不影响。

- **怎么开**：「模型/API」页 → 绘图模型 → 上方「模型 / API」子页签切到 **API** → 点「启动服务」。不用跑到对话模型页去
- **默认关闭**：出图一张要几十秒到几分钟且吃满 CPU，不希望被同一个网络里的设备随便触发
- **客户端怎么填**：Base URL = `http://<手机IP>:8081/v1`（**注意端口与对话那边不同**，App 里两处都会各自显示地址、点一下即复制）
- **请求**：`{"prompt": "..."}` 最简即可。可选 `n`（1~4）、`size`（如 `"512x512"`）、`seed`、`steps`、`cfg_scale`、`negative_prompt`；**没传的参数沿用绘图页当前设置**（步数 / CFG / 尺寸 / 采样器 / 调度器 / LoRA），结果里的尺寸与 LoRA 也是这一套
- **返回**：`{"created": …, "data": [{"b64_json": "…"}]}`——只支持 `response_format=b64_json`，本机没有公网 URL 可以当图床，所以给不了 `url` 形式

## 打标（Tagger）

- **本机离线**的图像反推标签：导入 Danbooru 系 ONNX 打标模型（`.onnx`，如 WD14）+ 配套标签表（`selected_tags.csv`），不内置、不下载
- 参数区「Tagger」页：选图 → 设阈值（默认 0.35）/ 最多标签数（默认 40）/ **通道顺序**（默认 BGR）→ 输出标签
  - 「通道顺序」是给不同预处理模型留的开关：WD 系官方预处理是 BGR；少数重导出模型把 BGR 烘进了权重，这时切到 RGB 才对（颜色 / 发色识别错时可切换试试）
- 标签可一键**发送至文生图 / 图生图**（发送前弹确认框）；结果页也可把生成的图**发送至图生图**或**发送至 Tagger**
- 多份打标模型 / 标签表可在「模型/API」页列表里点选切换；「加载 / 卸载打标模型」按需加载，用完可卸载释放内存
- 加载后的打标模型还可以被**看不见图的对话模型当工具调用**（模型输出 `<tag>` 命令 → App 打标 → 标签回传 → 模型据此作答），详见「对话」一节
- 后端跟随「模型/API」页的运行方式：CPU，或选 GPU 时走 NNAPI

## 权限

拍照、选图、保存到相册都**不需要**申请存储读取权限；除下表列出的以外，App 不再声明其它权限。

| 用途 | 是否需要权限 | 说明 |
| --- | --- | --- |
| 拍照 | **不需要 `CAMERA`** | 通过 `ACTION_IMAGE_CAPTURE` 交给系统相机 App 去拍，拍照由相机应用完成，App 只接收结果。若声明了 `CAMERA` 却未获授权，反而会抛 `SecurityException`，所以是故意不声明的 |
| 选图（图库 / 文件） | **不需要存储读取权限** | 走系统文件选择器 `ACTION_GET_CONTENT`，只对你**亲自点中的那一个文件**拿到临时读取授权（`content://`），不是整个存储；因此也不需要 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` |
| 保存图片到相册 | **Android 10+ 不需要** | 通过 `MediaStore` 写入自己创建的媒体文件，属系统允许的免权限操作 |
| 保存图片 / 拍照 | **Android 9 及以下需要** | 这两项需要 `WRITE_EXTERNAL_STORAGE`（manifest 里以 `maxSdkVersion="28"` 声明，仅在 9 及以下生效），且**只在 Android 9 上首次保存/拍照时才弹窗申请**；Android 10 及以上永远不会请求它 |
| 下载 LoRA | `INTERNET`、`ACCESS_NETWORK_STATE` | 只用于「LoRA 加速」里手动下载 LCM-LoRA（HuggingFace / hf-mirror）。**不下载就不联网**，其余功能无需联网 |
| 模型 / 会话 / 图片存储 | **不需要权限** | 全部存在 App 私有目录（`filesDir/`），属应用沙箱 |
| **API 服务端**（可选功能，默认关闭） | `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`、`WAKE_LOCK`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE` | 只在开启「API 服务端」时用到：常驻前台服务（`specialUse`）+ 常驻通知 + WifiLock（灭屏不断线）；**不开这个功能就完全用不到**；拒绝通知授权也不影响服务本身，只是看不到常驻通知 |

- 相机、图库返回的 `content://` 授权是**临时的**，所以导入时会立刻复制一份到私有目录（这也是「选完模型要复制」的原因）
- `<queries>` 里的 `IMAGE_CAPTURE` 是 Android 11+ 的**包可见性**声明，不是权限
- 不申请位置、通讯录、麦克风等权限；**通知（`POST_NOTIFICATIONS`）与前台服务相关权限只在开启「API 服务端」时才会用到**

## 多语言

界面文案全部资源化（`res/values` = 英文，`res/values-zh` = 中文），**跟随系统语言**自动切换，无需设置项。

底部第四个标签是「关于」页，里面分两个可折叠分区：**「设置」**（主题三档滑块：亮 / 跟随系统 / 暗）与 **「关于」**（版本号与安装时间、应用简介、思考模式说明、许可与第三方组件清单、作者与项目地址）。

## 构建

需要 JDK 17 + Android SDK（compileSdk 36）+ **NDK 27.3** + **CMake 3.31.6**。

```bash
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk（未签名）
```

签名与交付脚本见 `tools/`：

```powershell
# 用 keystore 签名（v1 + v2 + v3 全方案）
# 口令不在脚本里：读项目根 keystore.properties（已 gitignore），或环境变量 PONKO_KS_PASS
powershell -ExecutionPolicy Bypass -File tools/sign-apk.ps1 -Apk app\build\outputs\apk\release\app-release-unsigned.apk
# 一键：构建 + 签名 + 拷共享盘 + 上传网盘
powershell -File tools/build-release.ps1
```

- `minSdk 28` / `targetSdk 36`，**仅 arm64-v8a**（原生库限制，需真机，模拟器跑不了）
- 首次全量编译 sd.cpp + ggml 约 8~9 分钟，C++ 未改动时增量仅约 10 秒
- 主要依赖：`com.google.ai.edge.litertlm:litertlm-android:0.17.0`、`net.ladenthin:llama-android:5.1.0`、`com.microsoft.onnxruntime:onnxruntime-android:1.22.0`、`com.tom-roush:pdfbox-android:2.0.27.0`、`io.noties.markwon:*:4.6.2`
- CMake 现场产出：`libstable-diffusion.so`（sd.cpp 本体）、`libponko_sd.so`（JNI 桥）
- Vulkan 后端（可选 GPU 绘图）不需要安装 Vulkan SDK：`glslc` 与 SPIRV 头文件都用 NDK 自带的；交叉编译时还需要一个**宿主编译器**来构建着色器生成工具（Windows 上是 MSVC，`tools/build-release.ps1` 会自动载入 `vcvars64` 环境）

## 模型从哪来

- `.litertlm`：HuggingFace 上的 `litert-community` / `google` 组织（Gemma 系列等）
- `.gguf`（对话）：HuggingFace 上任意 GGUF 量化模型；手机 CPU 上建议 **1B~3B、Q4 量化**
- `.gguf`（绘图）：stable-diffusion.cpp 格式的 SD1.5 系模型（如 Anything V5）
- **LoRA**：`lcm-lora-sdv1-5.safetensors`（约 130 MB），App 内可直接下载；本地 `.safetensors` 也可直接导入
- **打标模型**：Danbooru 系 tagger 的 `.onnx` + 配套标签表 CSV（两者必须成对；如 WD14 的 `wd-v1-4-swinv2-tagger-v2` 与 `selected_tags.csv`）

## 已知限制

- 对话上下文默认 4096 tokens（可在「模型/API」页调整；LiteRT-LM 侧不能超过模型自带的上限），超出后依赖 llama.cpp 的 context shift 滑动窗口
- 对话用的 GGUF 目前仅走 CPU（所用绑定未包含 GPU 后端）
- 「关闭思考」依赖模型自带的对话模板，个别模型可能仍会输出思考内容
- 绘图默认是纯 CPU 推理：256×256 + LCM-LoRA 6 步约 1 分钟，512×512 明显更慢；把运行方式切到 GPU 可尝试 Vulkan 加速，设备不支持会自动回退 CPU（实际后端显示在绘图页状态行）
- **GPU 支持目前只是「理论上可用」，未经真机验证**：只有绘图（Vulkan）、打标（NNAPI）、对话 `.litertlm` 这三条路径带 GPU 代码，三者都只在构建侧验证过，**未在任何手机上实测**；机型驱动不稳 / 显存不足时自动回退 CPU（绘图页状态行会显示实际后端）；对话 GGUF 没有任何 GPU 后端，只能 CPU
- 绘图量化等级写死在模型文件里，App 只读取并显示，不会转换
- 绘图原生库用 `-march=armv8.2-a+dotprod+fp16` 编译，需要 2019 年后的 64 位 ARM 设备
- 打标支持 Danbooru 系的 ONNX 打标模型（WD14 及其衍生版本等；标签表需为 `tag_id,name,category,count` 格式）；`.onnx` 与标签表 CSV 必须配套导入
- 打标结果离谱（例如发色 / 颜色认错）时先试切换「通道顺序」（BGR / RGB）：BGR 是 WD 系官方预处理，个别重导出模型已把 BGR 烘进权重、需要切 RGB；阈值与标签数也会明显影响结果
- 打标首次运行时才加载模型（常驻数百 MB 内存），可在「模型/API」页手动卸载
- 保存到相册 / 拍照在 **Android 9** 上需要存储权限（首次操作会弹窗）；拒绝授权则该操作失败（Android 10+ 不受影响）
- 图片输入的张数上限为 4 张、文件上限 2 个；`.gguf` 视觉模型必须搭配对应的 `mmproj`，且需在加载模型前选好
- 文件输入支持纯文本与常见文档（`.docx` / `.pptx` / `.xlsx` / `.odt` / `.ods` / `.odp` / `.epub` / `.rtf` / `.html` / `.pdf`）；`.doc` / `.xls` / `.ppt` 老二进制格式需先另存；扫描版 PDF（图片型）抽不出文字；二进制文件直接拒收；单个文件大小上限（纯文本 4 MB、文档 48 MB）；正文按上下文预算截断（默认约为上下文的 1/4）
- API 服务端：**对话与绘图两套完全独立**（各自端口 / key / 开关，默认 8080 与 8081），共用一个前台服务与一条通知；**启动服务本身不要求先加载模型**（未加载时对应接口返回 503）；接口只服务已加载的模型；同时只服务一个请求；端口 / key 改动会自动重启对应服务；**不设 key 时完全不校验**，公网暴露风险自负
- 绘图接口只做**文生图**，没有图生图接口；只支持 `response_format=b64_json`；请求一旦开始无法中途取消（会跑完当前这张，客户端断开也一样）；界面正在出图时接口返回 429、API 出图时界面会提示等待（同一个绘图引擎，不能并发）
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
| [PDFBox-Android](https://github.com/TomRoush/PdfBox-Android) | Apache-2.0 | Gradle 依赖（PDF 文本抽取） |
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
