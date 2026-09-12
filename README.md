# Ponko

> 名字来自日语「ポンコツ」(ponkotsu)——破铜烂铁。脑子不中用，但可以随时换成最好的模型。

[English](README.en.md) · 中文

一个**完全离线**的 Android 本地 AI App：既能聊大语言模型，也能画图。

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

- **文生图**：绘图页顶部切「参数」/「结果」两个视图；填提示词 → 生成 → 结果页可保存到相册
- **生成历史**：结果页保留本次运行最近 30 张，可翻看 / 单张删除 / 一键清空；**只存在内存里，关掉 App 即清空**
- **参数**：宽高（64 的倍数）、步数、CFG、种子、采样器、调度器都能调，**重启后自动记住**（提示词不保留）
- **LCM-LoRA 加速**：App 内一键下载（HuggingFace 官方 / hf-mirror 双源），可把 20 步压到 4~8 步
- **对话页出图**：语言模型与绘图模型都加载时，直接说「画一张…」就会调用绘图模型生成

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
- 主要依赖：`com.google.ai.edge.litertlm:litertlm-android:0.17.0`、`net.ladenthin:llama-android:5.1.0`、`io.noties.markwon:*:4.6.2`
- CMake 现场产出：`libstable-diffusion.so`（sd.cpp 本体）、`libponko_sd.so`（JNI 桥）

## 模型从哪来

- `.litertlm`：HuggingFace 上的 `litert-community` / `google` 组织（Gemma 系列等）
- `.gguf`（对话）：HuggingFace 上任意 GGUF 量化模型；手机 CPU 上建议 **1B~3B、Q4 量化**
- `.gguf`（绘图）：stable-diffusion.cpp 格式的 SD1.5 系模型（如 Anything V5）
- **LoRA**：`lcm-lora-sdv1-5.safetensors`（约 130 MB），App 内可直接下载

## 已知限制

- GGUF 上下文固定 4096 tokens，超出后依赖 llama.cpp 的 context shift 滑动窗口
- 对话用的 GGUF 目前仅走 CPU（所用绑定未包含 GPU 后端）
- 「关闭思考」依赖模型自带的对话模板，个别模型可能仍会输出思考内容
- 绘图是纯 CPU 推理：256×256 + LCM-LoRA 6 步约 1 分钟，512×512 明显更慢
- 绘图量化等级写死在模型文件里，App 只读取并显示，不会转换
- 绘图原生库用 `-march=armv8.2-a+dotprod+fp16` 编译，需要 2019 年后的 64 位 ARM 设备

## 许可

- **代码**：[MIT](LICENSE)
- **美术资源**（应用图标、角色立绘、原始画稿）：版权归作者所有，保留所有权利，**不在 MIT 许可范围内** —— 详见 [NOTICE](NOTICE)
- **第三方组件**：按各自许可证分发，完整清单见 [NOTICE](NOTICE)

## 第三方组件

| 组件 | 许可证 | 分发方式 |
| --- | --- | --- |
| [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) | MIT | 源码随仓库分发（`app/src/main/cpp/sd/`） |
| [ggml](https://github.com/ggerganov/ggml) | MIT | 源码随仓库分发（`app/src/main/cpp/sd/ggml/`） |
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | Apache-2.0 | Gradle 依赖 |
| [llama.cpp](https://github.com/ggerganov/llama.cpp)（经 java-llama.cpp 绑定） | MIT | Gradle 依赖 |
| [Markwon](https://github.com/noties/Markwon) | Apache-2.0 | Gradle 依赖 |
| Kotlin / kotlinx.coroutines / AndroidX | Apache-2.0 | Gradle 依赖 |
| LLVM libc++ / libomp（来自 Android NDK） | Apache-2.0 with LLVM Exceptions | `app/src/main/jniLibs/` |

> stable-diffusion.cpp 源码为随仓库分发的副本，为减小体积剔除了本项目用不到的分词器词表（仅保留 CLIP）。
> 本项目**不含任何模型权重文件**；绘图模型（SD1.5 GGUF）、对话模型（`.litertlm` / `.gguf`）与 LoRA 均由使用者自行获取。

## 致谢

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)（Google）
- [java-llama.cpp](https://github.com/kherud/java-llama.cpp) / llama.cpp
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)
- [Markwon](https://github.com/noties/Markwon)
