# Ponko

> The name comes from the Japanese 「ポンコツ」(ponkotsu) - "scrap heap". Not the sharpest brain, but you can swap in the best model whenever you like.

English · [中文](README.md)

An **offline-capable** Android app for local AI: chat with LLMs (**image and file input included**), generate images, and tag them.

All inference runs on-device: apart from the optional in-app LoRA download, no feature needs the network.

## Chat

Two model formats are supported:

| Format | Runtime | Highlights |
|---|---|---|
| `.litertlm` | Google LiteRT-LM | CPU / GPU / NPU backends, thinking channel, multimodal extension |
| `.gguf` | llama.cpp | Multi-threaded CPU, in-session KV prefix reuse (no re-prefill on long chats) |

- **Model management**: pick a model file from local storage; it is copied into the app's private directory and can be reused anytime. The Models page has a run-mode selector (CPU / GPU) on top and three sub-pages below (**Chat model / Draw model / Tagger model**, swipe to switch), where each kind of model can be viewed, selected or deleted.
  - What the run mode covers: **only three paths carry GPU code** - **drawing** (Vulkan), **tagging** (NNAPI) and **`.litertlm` chat** (LiteRT-LM GPU, i.e. OpenCL / Vulkan underneath); **GGUF chat always runs on CPU** (the binding has no GPU backend). All three GPU paths have **only been verified at build time and never on a real device** - they are expected to work in theory, but whether they do depends on the phone's drivers and VRAM; if the device does not support it or init fails, the app falls back to CPU automatically and the drawing status line shows the backend actually in use
- **Multiple conversations**: create / switch / delete chats. History is persisted on-device (`sessions.json`).
- **Separate thinking**: reasoning and answer are shown apart and collapsible; the thinking toggle works for both formats.
- **Markdown rendering**: headings, bold/italic, strikethrough, ordered/unordered lists, inline code and code blocks, quotes, dividers, tables, tappable links.
- **Streaming UX**: token-by-token output; auto-follow scrolling that pauses the moment you scroll up, with a floating "back to bottom" button; interrupt anytime, and keep typing while generating.
- **Regenerate**: one tap to rerun, and it **carries the images and files of that turn along** (restored to the attachment strip above the input box, so you can see them); a random seed keeps results from repeating.
- **Image input (multimodal)**: the "+" button left of the input box opens an upward drawer with **Take photo / Gallery**, up to 4 images per message. Images appear inline in the bubble: **tap to view fullscreen (pinch-zoom / pan / double-tap)** and **long-press** for a menu: Save to gallery / Quote (put it into the input bar) / Send to Image-to-Image / Send to Tagger
  - `.litertlm`: needs a **multimodal model** (e.g. Gemma 3n). The app probes the model and tells you right away if it has no vision input
  - `.gguf`: needs a **vision model plus its matching `mmproj` file**. Import and select the mmproj on the Models page, Chat model card, and **pick the mmproj before loading the model**
- **File input (text-like files)**: the third item in the same "+" drawer, **File**, up to 2 per message; supports `.txt` / `.md` / `.json` / `.csv` / `.log` / `.xml` / source code and similar text files (UTF-8 / GBK auto-detected; binary files are rejected, PDF is not supported yet). The text is trimmed to the context budget and sent **with that one message only**, never added to the history; the file name shows up in the bubble
- **Chat parameters**: on the Models page, Chat model card, you can tune context size, max output, temperature, Top-K, Top-P, repeat penalty, thinking budget and random seed; **saved per model**, as you type. Context size and max output take effect **after reloading the model**
- **Text-only chat models can borrow the tagger to "see" an image**: when the chat model cannot see images itself (no multimodal, no mmproj) but a tagger model is loaded, the model calls the tagger on its own (it emits a `<tag>` command, the app tags the image, the tags come back as a tool result) and then answers based on them; threshold and tag count are chosen by the model, BGR/RGB follows the Tagger setting on the drawing page. Models that can see images never take this path

## Drawing

The engine is [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp), reading **GGUF** Stable Diffusion models.

> The sd.cpp source lives in `app/src/main/cpp/sd/` and is compiled **on the fly** by NDK + CMake during
> the build (no prebuilt `.so` checked in), so after editing C++ you just run `assembleRelease` -
> there is no way to ship a stale native library by accident.

- **Three modes**: the parameters area is split into **Text-to-Image / Image-to-Image / Tagger** sub-pages
  - **Text to image**: enter a prompt and generate
  - **Image to image**: pick a reference photo from your gallery as the base, then use the prompt and the "denoise strength" (0.05-0.99) to restyle / change background / refine; the output size auto-aligns to the reference (multiple of 64)
  - **Tagger**: reverse an image into Danbooru-style tags (see below)
- **Parameter defaults**: every parameter page has "Set as default" / "Restore defaults" (each asks for confirmation); Text-to-Image and Image-to-Image keep **separate default sets for LoRA on/off**, and toggling the LoRA switch applies the matching set
- **Generation history**: the Result view keeps the last 30 images of this run - browse, delete one, or clear all. **In-memory only; gone when the app is closed.**
- **Parameters**: width/height (multiples of 64), steps, CFG, seed, sampler, scheduler - all adjustable and **remembered across restarts** (prompts are not).
- **LoRA**: one-tap in-app download of LCM-LoRA (official HuggingFace / hf-mirror), cutting 20 steps down to 4-8. You can also **import a local LoRA** (a single `.safetensors`), and switch between multiple LoRAs from the list.
- **Model import**: the drawing model is imported as a **single `.gguf` file**.
- **GPU acceleration (optional; build-time-verified only, not tested on a real device)**: switch the run mode to **GPU** on the Models page and drawing uses the device's Vulkan backend (both text-to-image and image-to-image); if the device has no Vulkan or init fails it **falls back to CPU automatically**, and the backend actually in use is shown in the drawing status line
- **Draw from chat**: when both a chat model and a draw model are loaded, just say "draw me ..." and the draw model is invoked.
- The "Parameters" / "Result" views can be swiped left/right; save images to the gallery from the Result view.

## Tagging (Tagger)

- **On-device** image-to-tags: import a Danbooru-style ONNX tagger (`.onnx`, e.g. WD14) plus its matching tag list (`selected_tags.csv`) - nothing is bundled or downloaded
- The "Tagger" page: pick an image → set threshold (default 0.35) / max tags (default 40) / **channel order** (BGR by default) → get tags
  - "Channel order" exists for different preprocessing: the official WD-family pipeline is BGR; a few re-exported models baked BGR into their weights, so those need RGB (switch it if colours / hair colour come out wrong)
- Send the tags to **Text-to-Image / Image-to-Image** (with a confirmation prompt); from the Result view you can also send a generated image to **Image-to-Image** or to **Tagger**
- Multiple taggers / tag lists can be switched from the list on the Models page; "Load / Unload tagger" loads on demand and frees memory when you are done
- A loaded tagger can also be called as a **tool by chat models that cannot see images** (the model emits a `<tag>` command, the app tags the image, the tags are sent back and the model answers from them) - see the Chat section
- Backend follows the Models page run mode: CPU, or NNAPI when GPU is selected

## Permissions

The app declares **only three permissions** and none of them is sensitive: taking a photo, picking an image and saving to the gallery require **no storage access permission at all**.

| What | Permission | Why |
| --- | --- | --- |
| Take photo | **No `CAMERA`** | Uses `ACTION_IMAGE_CAPTURE`, delegating to the system camera app: the photo is taken there and only the result comes back. Declaring `CAMERA` without holding the grant would actually make this throw `SecurityException`, so it is intentionally left undeclared |
| Pick an image (gallery / files) | **No storage read permission** | Uses the system file picker `ACTION_GET_CONTENT`: the app only receives a temporary read grant for the **single file the user taps** (`content://`), not for storage as a whole - hence no `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` |
| Save an image to the gallery | **Not needed on Android 10+** | Writes through `MediaStore` into the app's own media entry, which the platform allows without a permission |
| Save an image / take a photo | **Needed on Android 9 and below** | These need `WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion="28"`, so it only applies on Android 9 or older), and the app **only prompts on Android 9, at the first save/photo**; on Android 10+ it is never requested |
| Downloading LoRA | `INTERNET`, `ACCESS_NETWORK_STATE` | Used only by the manual LCM-LoRA download (HuggingFace / hf-mirror) on the LoRA card. **No download, no network** - everything else needs no network |
| Models / chats / images | **No permission** | Everything lives in the app's private directory (`filesDir/`), the app sandbox |

- The `content://` grant returned by the camera or gallery is **temporary**, so imported files are copied into the private directory right away (this is why picking a model copies it)
- The `IMAGE_CAPTURE` entry under `<queries>` is an Android 11+ package-visibility declaration, not a permission
- No location, contacts, microphone, notification or background-execution permission is requested

## Languages

All UI strings are externalized (`res/values` = English, `res/values-zh` = Chinese) and **follow the system language** automatically - no setting needed.

The fourth tab is an **About** page: version and install time, app intro, notes on the thinking mode, license and third-party component list, author and project link.

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
- The first full compile of sd.cpp + ggml takes about 8-9 minutes; with C++ unchanged, incremental builds take ~10 seconds
- Main dependencies: `com.google.ai.edge.litertlm:litertlm-android:0.17.0`, `net.ladenthin:llama-android:5.1.0`, `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`, `io.noties.markwon:*:4.6.2`
- Built by CMake: `libstable-diffusion.so` (sd.cpp itself), `libponko_sd.so` (JNI bridge)
- The Vulkan backend (optional GPU drawing) needs **no Vulkan SDK**: `glslc` and the SPIRV headers come from the NDK, plus a **host compiler** to build the shader generator (MSVC on Windows - `tools/build-release.ps1` loads the `vcvars64` environment for you)

## Where to get models

- `.litertlm`: the `litert-community` / `google` organizations on HuggingFace (Gemma family, etc.)
- `.gguf` (chat): any GGUF quantized model on HuggingFace; on a phone CPU, **1B-3B at Q4** is recommended
- `.gguf` (drawing): SD1.5-family models in stable-diffusion.cpp format (e.g. Anything V5)
- **LoRA**: `lcm-lora-sdv1-5.safetensors` (~130 MB), downloadable in-app; a local `.safetensors` can also be imported directly
- **Tagger**: the `.onnx` of a Danbooru-style tagger plus its matching tag-list CSV (the two must go together; e.g. WD14's `wd-v1-4-swinv2-tagger-v2` and `selected_tags.csv`)

## Known limitations

- Chat context defaults to 4096 tokens (adjustable on the Models page; on the LiteRT-LM side it cannot exceed the limit built into the model); beyond that it relies on llama.cpp's context-shift sliding window
- The GGUF chat path currently uses CPU only (the binding used does not include GPU backends)
- "Disable thinking" depends on the model's own chat template; some models may still emit reasoning
- Drawing is CPU-only by default: 256×256 with LCM-LoRA at 6 steps takes about a minute; 512×512 is noticeably slower. Switch the run mode to GPU to try Vulkan acceleration; devices without it fall back to CPU (the backend in use is shown in the drawing status line)
- **GPU support is "theoretical" for now and NOT verified on a real device**: only drawing (Vulkan), tagging (NNAPI) and `.litertlm` chat carry GPU code, and all three have only been verified at build time - none of them has been tested on an actual phone. When the driver is unstable or the VRAM is too small the app falls back to CPU automatically (the drawing status line shows the backend in use); GGUF chat has no GPU backend at all and is CPU-only
- The quantization level is baked into the model file; the app only reads and displays it, never converts
- The drawing native library is built with `-march=armv8.2-a+dotprod+fp16`, requiring a 64-bit ARM device from 2019 or later
- Tagging supports Danbooru-style ONNX taggers (WD14 and its derivatives; the tag list must be in `tag_id,name,category,count` format); the `.onnx` and the tag-list CSV must be imported as a matching pair
- If tagging looks wrong (e.g. wrong colours or hair colour), try switching the **channel order** (BGR / RGB): BGR is the official WD-family preprocessing, but a few re-exported models already baked BGR into their weights and need RGB; threshold and tag count also change the output a lot
- The tagger model is loaded lazily on first use (it holds hundreds of MB of memory) and can be unloaded manually from the Models page
- On **Android 9**, saving to the gallery / taking a photo requires the storage permission (prompted on first use); denying it makes those actions fail (Android 10+ is unaffected)
- Image input is capped at 4 images and 2 files per message; a `.gguf` vision model must be paired with its matching `mmproj`, selected before the model is loaded
- File input accepts text-like files only: PDF is not supported yet (the plan is to convert pages to images and use the multimodal path), binary files are rejected; the text is trimmed to roughly 1200 tokens
- When the context does not fit, older turns and file text are trimmed automatically; if it still does not fit, the app tells you what to do: start a new chat, send a shorter file, or drop some old turns

## License

- **Code**: [MIT](LICENSE)
- **Artwork** (app icon, character portrait, original drawing): © the author, all rights reserved, **not covered by the MIT license** - see [NOTICE](NOTICE)
- **Third-party components**: distributed under their own licenses - see [NOTICE](NOTICE) for the full list

## Third-party components

| Component | License | Distributed as |
| --- | --- | --- |
| [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) | MIT | source in this repo (`app/src/main/cpp/sd/`) |
| [ggml](https://github.com/ggerganov/ggml) (also carries contributed files from Intel / Codeplay / Arm / Mozilla) | MIT / Apache-2.0 | source in this repo (`app/src/main/cpp/sd/ggml/`) |
| [Vulkan-Hpp](https://github.com/KhronosGroup/Vulkan-Hpp) / [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) | Apache-2.0 OR MIT | source in this repo (`app/src/main/cpp/thirdparty/include/`, needed by the Vulkan backend) |
| Third-party files bundled with sd.cpp (stb / json.hpp / httplib / miniz / zip / darts_clone) | Public Domain / MIT / BSD-3-Clause | source in this repo (`app/src/main/cpp/sd/thirdparty/`) |
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | Apache-2.0 | Gradle dependency |
| [llama.cpp](https://github.com/ggerganov/llama.cpp) (via java-llama.cpp binding) | MIT | Gradle dependency |
| [Markwon](https://github.com/noties/Markwon) | Apache-2.0 | Gradle dependency |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | Gradle dependency (Tagger) |
| Kotlin / kotlin-reflect / kotlinx.coroutines / AndroidX | Apache-2.0 | Gradle dependency |
| [commonmark-java](https://github.com/commonmark/commonmark-java) (via Markwon) | BSD-2-Clause | Gradle transitive dependency |
| [Jackson](https://github.com/FasterXML/jackson) (via the llama.cpp binding) | Apache-2.0 | Gradle transitive dependency |
| [Gson](https://github.com/google/gson) (via LiteRT-LM) | Apache-2.0 | Gradle transitive dependency |
| [SLF4J API](https://www.slf4j.org/) (via the llama.cpp binding) | MIT | Gradle transitive dependency |
| FastDoubleParser / Schubfach (bundled in Jackson) | MIT / Boost-1.0 | shipped inside Jackson (`META-INF/*-LICENSE`) |
| Annotation-only libs (jspecify / checker-qual / error_prone_annotations / JetBrains annotations) | Apache-2.0 / MIT | Gradle transitive dependency (annotations only, no runtime code) |
| Statically linked into LiteRT-LM's native library (LiteRT / TFLite, XNNPACK, sentencepiece, HF tokenizers, re2, Abseil, cpuinfo, protobuf, flatbuffers, zlib) | Apache-2.0 / BSD-3-Clause / BSD-2-Clause / zlib | built into `liblitertlm_jni.so` |
| Statically linked into ONNX Runtime's native library (XNNPACK, protobuf, ONNX, Abseil, flatbuffers, re2, cpuinfo, ...) | Apache-2.0 / BSD-3-Clause / BSD-2-Clause | built into `libonnxruntime.so` |
| LLVM libc++ / libomp (from the Android NDK) | Apache-2.0 with LLVM Exceptions | `app/src/main/jniLibs/` |

> The bundled stable-diffusion.cpp source has unused tokenizer vocabularies trimmed (CLIP only) to keep the repo small.
> This project contains **no model weights**; the drawing model (SD1.5 GGUF), chat models (`.litertlm` / `.gguf`), LoRA and the tagging assets (`.onnx` + `selected_tags.csv`) are supplied by the user.
> ONNX Runtime bundles additional third-party components; see [licenses/onnxruntime-ThirdPartyNotices.txt](licenses/onnxruntime-ThirdPartyNotices.txt).
> commonmark / Jackson / Gson / SLF4J are **transitive dependencies** pulled in automatically by Markwon, the llama.cpp binding, and LiteRT-LM.
> Components statically linked into the third-party native libraries (XNNPACK, protobuf, re2, cpuinfo, zlib, ...) and the full license texts are in [licenses/](licenses/) and [NOTICE](NOTICE).

## Credits

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google)
- [java-llama.cpp](https://github.com/kherud/java-llama.cpp) / llama.cpp
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)
- [ggml](https://github.com/ggerganov/ggml)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) (Microsoft)
- [Markwon](https://github.com/noties/Markwon)
- [commonmark-java](https://github.com/commonmark/commonmark-java)
