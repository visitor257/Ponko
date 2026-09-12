# Ponko

> The name comes from the Japanese 「ポンコツ」(ponkotsu) — "scrap heap". Not the sharpest brain, but you can swap in the best model whenever you like.

English · [中文](README.md)

A **fully offline** Android app for local AI: chat with LLMs and generate images.

## Chat

Two model formats are supported:

| Format | Runtime | Highlights |
|---|---|---|
| `.litertlm` | Google LiteRT-LM | CPU / GPU / NPU backends, thinking channel, multimodal extension |
| `.gguf` | llama.cpp | Multi-threaded CPU, in-session KV prefix reuse (no re-prefill on long chats) |

- **Model management**: pick a model file from local storage; it is copied into the app's private directory and can be reused anytime. View / delete models from the Models page.
- **Multiple conversations**: create / switch / delete chats. History is persisted on-device (`sessions.json`).
- **Separate thinking**: reasoning and answer are shown apart and collapsible; the thinking toggle works for both formats.
- **Markdown rendering**: headings, bold/italic, strikethrough, ordered/unordered lists, inline code and code blocks, quotes, dividers, tables, tappable links.
- **Streaming UX**: token-by-token output; auto-follow scrolling that pauses the moment you scroll up, with a floating "back to bottom" button; interrupt anytime, and keep typing while generating.
- **Regenerate**: one tap to rerun, with a random seed so results differ.

## Drawing

The engine is [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp), reading **GGUF** Stable Diffusion models.

> The sd.cpp source lives in `app/src/main/cpp/sd/` and is compiled **on the fly** by NDK + CMake during
> the build (no prebuilt `.so` checked in), so after editing C++ you just run `assembleRelease` —
> there is no way to ship a stale native library by accident.

- **Text to image**: the Draw page has two views, "Parameters" and "Result". Enter a prompt → generate → save to gallery from the Result view.
- **Generation history**: the Result view keeps the last 30 images of this run — browse, delete one, or clear all. **In-memory only; gone when the app is closed.**
- **Parameters**: width/height (multiples of 64), steps, CFG, seed, sampler, scheduler — all adjustable and **remembered across restarts** (prompts are not).
- **LCM-LoRA acceleration**: one-tap download in-app (official HuggingFace / hf-mirror), cutting 20 steps down to 4–8.
- **Draw from chat**: when both a chat model and a draw model are loaded, just say "draw me …" and the draw model is invoked.

## Languages

All UI strings are externalized (`res/values` = English, `res/values-zh` = Chinese) and **follow the system language** automatically — no setting needed.

## Building

Requires JDK 17 + Android SDK (compileSdk 36) + **NDK 27.3** + **CMake 3.31.6**.

```bash
gradle assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk (unsigned)
```

Signing and delivery scripts are in `tools/`:

```powershell
# Sign with the keystore (always v2-only)
powershell -File tools/sign-apk.ps1
# All-in-one: build + sign + copy to share + upload
powershell -File tools/build-release.ps1
```

- `minSdk 28` / `targetSdk 36`, **arm64-v8a only** (native library constraint; a real device is required, emulators won't run it)
- The first full compile of sd.cpp + ggml takes about 8–9 minutes; with C++ unchanged, incremental builds take ~10 seconds
- Main dependencies: `com.google.ai.edge.litertlm:litertlm-android:0.17.0`, `net.ladenthin:llama-android:5.1.0`, `io.noties.markwon:*:4.6.2`
- Built by CMake: `libstable-diffusion.so` (sd.cpp itself), `libponko_sd.so` (JNI bridge)

## Where to get models

- `.litertlm`: the `litert-community` / `google` organizations on HuggingFace (Gemma family, etc.)
- `.gguf` (chat): any GGUF quantized model on HuggingFace; on a phone CPU, **1B–3B at Q4** is recommended
- `.gguf` (drawing): SD1.5-family models in stable-diffusion.cpp format (e.g. Anything V5)
- **LoRA**: `lcm-lora-sdv1-5.safetensors` (~130 MB), downloadable in-app

## Known limitations

- GGUF context is fixed at 4096 tokens; beyond that it relies on llama.cpp's context-shift sliding window
- The GGUF chat path currently uses CPU only (the binding used does not include GPU backends)
- "Disable thinking" depends on the model's own chat template; some models may still emit reasoning
- Drawing runs on CPU only: 256×256 with LCM-LoRA at 6 steps takes about a minute; 512×512 is noticeably slower
- The quantization level is baked into the model file; the app only reads and displays it, never converts
- The drawing native library is built with `-march=armv8.2-a+dotprod+fp16`, requiring a 64-bit ARM device from 2019 or later

## License

- **Code**: [MIT](LICENSE)
- **Artwork** (app icon, character portrait, original drawing): © the author, all rights reserved, **not covered by the MIT license** — see [NOTICE](NOTICE)

## Credits

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google)
- [java-llama.cpp](https://github.com/kherud/java-llama.cpp) / llama.cpp
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)
- [Markwon](https://github.com/noties/Markwon)
