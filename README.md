# Ponko

> 名字来自日语「ポンコツ」(ponkotsu)——破铜烂铁。脑子不中用，但可以随时换成最好的模型。

一个**完全离线**的 Android 本地大模型聊天 App，支持两种模型格式。

| 格式 | 运行时 | 特性 |
|---|---|---|
| `.litertlm` | Google LiteRT-LM | CPU / GPU / NPU 后端、思考通道、多模态扩展 |
| `.gguf` | llama.cpp | CPU 多线程、会话内 KV 前缀复用（长对话不重复 prefill）|

## 功能

- **模型管理**：从本地存储选择模型文件，复制到 App 私有目录后可反复选用；模型页可查看 / 删除
- **多对话**：新建 / 切换 / 删除对话，记录持久化在设备本地（`sessions.json`），互不干扰
- **思考分离**：思考过程与正文分开显示、可折叠；思考开关对两种格式都生效
- **Markdown 渲染**：标题、粗斜体、删除线、有序/无序列表、行内代码与代码块、引用、分隔线、表格、可点击链接
- **流式体验**：逐 token 输出；自动跟随滚动，手动上翻即暂停，右下角浮出回底按钮；生成中可随时中断
- **纯本地**：推理与数据全部留在设备上，无任何网络请求

## 构建

需要 JDK 17 + Android SDK（compileSdk 36）。

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- `minSdk 28` / `targetSdk 36`，**仅 arm64-v8a**（原生库限制，需真机，模拟器跑不了）
- 主要依赖：`com.google.ai.edge.litertlm:litertlm-android:0.17.0`、`net.ladenthin:llama-android:5.1.0`、`io.noties.markwon:*:4.6.2`

## 模型从哪来

- `.litertlm`：HuggingFace 上的 `litert-community` / `google` 组织（Gemma 系列等）
- `.gguf`：HuggingFace 上任意 GGUF 量化模型；手机 CPU 上建议 **1B~3B、Q4 量化**

## 已知限制

- GGUF 上下文固定 4096 tokens，超出后依赖 llama.cpp 的 context shift 滑动窗口
- GGUF 目前仅走 CPU（所用绑定未包含 GPU 后端）
- 「关闭思考」依赖模型自带的对话模板，个别模型可能仍会输出思考内容

## 许可

- **代码**：[MIT](LICENSE)
- **美术资源**（应用图标、角色立绘、原始画稿）：版权归作者所有，保留所有权利，**不在 MIT 许可范围内** —— 详见 [NOTICE](NOTICE)

## 致谢

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)（Google）
- [java-llama.cpp](https://github.com/kherud/java-llama.cpp) / llama.cpp
- [Markwon](https://github.com/noties/Markwon)
