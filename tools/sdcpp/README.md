# 自编 stable-diffusion.cpp（Ponko 绘图后端）

Ponko 的绘图能力**不再依赖第三方 AAR**（llmedge），而是自己用 NDK 编译
[leejet/stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) 的 arm64 版本。

产出三个 so，放在 `app/src/main/jniLibs/arm64-v8a/`：

| 文件 | 说明 | 大小 |
|---|---|---|
| `libstable-diffusion.so` | sd.cpp + ggml 本体（-O2 编译） | ~11 MB |
| `libponko_sd.so` | 薄 JNI 桥（`sdcpp_jni.cpp`） | ~50 KB |
| `libomp.so` | OpenMP 运行时（NDK 提供） | ~1.2 MB |
| `libc++_shared.so` | C++ 运行时 | ~1.7 MB |

> 为什么自编？llmedge 的封装**把加载参数写死了**（`ImageRuntimeRequestPlanner` 硬编码
> `q8_0`、采样器不暴露、线程数改不了），绘图只能用默认采样器，LCM-LoRA 效果打折。
> 自编后可完全控制：线程数 / 采样器 / 调度器 / LoRA / 量化类型 / 取消。

---

## 前置

- Android NDK `27.3.13750724`
- CMake `3.31.6`（`ninja.exe` 就在它的 `bin/` 里）
- 磁盘 ≥ 6 GB、内存 ≥ 8 GB

```powershell
sdkmanager --install "ndk;27.3.13750724" "cmake;3.31.6"
```

## 拉源码（GitHub 直连不通时用 codeload）

```powershell
# sd.cpp
Invoke-WebRequest "https://codeload.github.com/leejet/stable-diffusion.cpp/zip/refs/heads/master" -OutFile sd.zip
# ggml 子模块（版本要对齐，用 GitHub API 查 sd.cpp 的 ggml submodule sha）
#   GET /repos/leejet/stable-diffusion.cpp/contents/ggml  ->  .sha
Invoke-WebRequest "https://codeload.github.com/leejet/ggml/zip/<SHA>" -OutFile ggml.zip
```

把 ggml 解压内容放进 `stable-diffusion.cpp-master/ggml/`。

## 关键：精简 `src/tokenizers/vocab/vocab.cpp`

**这一步必须做**，否则单个 TU 要塞进约 250 MB 词表（`umt5.hpp` 一个就 44 MB），
编译器会 `LLVM ERROR: out of memory`。

SD1.5 只需要 CLIP tokenizer，把 `tools/sdcpp/vocab.cpp`（本仓库里的精简版）覆盖过去即可。

## 配置与编译

```powershell
$root  = "<sd.cpp 源码根>"
$build = "<构建目录>"
$ndk   = "C:\Android\ndk\27.3.13750724"
$cmake = "C:\Android\cmake\3.31.6\bin\cmake.exe"
$ninja = "C:\Android\cmake\3.31.6\bin\ninja.exe"

& $cmake -S $root -B $build -G Ninja -DCMAKE_MAKE_PROGRAM="$ninja" `
  -DCMAKE_TOOLCHAIN_FILE="$ndk\build\cmake\android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 `
  -DCMAKE_BUILD_TYPE=Release `
  -DSD_BUILD_EXAMPLES=OFF -DSD_WEBP=OFF -DSD_WEBM=OFF -DSD_BUILD_SHARED_LIBS=ON `
  -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG" -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG" `
  -DCMAKE_CXX_FLAGS="-g0 -march=armv8.2-a+dotprod+fp16" `
  -DCMAKE_C_FLAGS="-g0 -march=armv8.2-a+dotprod+fp16"

# 去掉 NDK toolchain 默认塞进来的 -g（8GB 机器上省内存）
$nf = "$build\build.ninja"
$c = [System.IO.File]::ReadAllText($nf) -replace ' -g ', ' -g0 '
[System.IO.File]::WriteAllText($nf, $c, (New-Object System.Text.UTF8Encoding $false))

& $cmake --build $build -j 2      # 内存紧就 -j 1
```

产出：`$build\bin\libstable-diffusion.so`

## 编 JNI 桥

```powershell
& "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe" `
  --target=aarch64-none-linux-android28 `
  --sysroot="$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot" `
  -shared -fPIC -O2 -std=gnu++17 -fvisibility=hidden `
  -o libponko_sd.so tools\sdcpp\sdcpp_jni.cpp `
  -I"$root\include" -L"$build\bin" -lstable-diffusion -llog -landroid
```

## 拷进项目

```powershell
Copy-Item "$build\bin\libstable-diffusion.so" app\src\main\jniLibs\arm64-v8a\
Copy-Item libponko_sd.so                       app\src\main\jniLibs\arm64-v8a\
Copy-Item "$ndk\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\18\lib\linux\aarch64\libomp.so" app\src\main\jniLibs\arm64-v8a\
```

## 性能相关的三个坑（都踩过，实测 125s → 116s → 明显更快）

1. **`-O2` 覆盖了默认的 `-O3`**
   CMake 拼出的 FLAGS 会变成 `-O3 -DNDEBUG -O2 -DNDEBUG`，**gcc/clang 最后一个 `-O` 生效** →
   实际按 `-O2` 编。**别传 `-O2`**（或显式传 `-O3`）。

2. **没有 `-march`**
   NDK 默认只给基线 NEON，ggml 的 `#ifdef __ARM_FEATURE_DOTPROD` 不成立，
   于是用不上 `SDOT`（int8 点积）。**Q8_0 全是 int8 矩阵乘**，缺 dotprod 明显慢。
   加 `-march=armv8.2-a+dotprod+fp16`，可用下面命令验证：
   ```powershell
   llvm-objdump.exe -d libstable-diffusion.so | Select-String sdot   # 应该有几百处
   ```
   ⚠️ 代价：要求 **ARMv8.2-A**（2019 年后的中高端 ARM）。更老的设备会 SIGILL，
   要让 `minSdk` 以下的老机器也能跑，就得改回基线并接受变慢。

3. **FlashAttention 默认是关的**
   `sd_ctx_params_init()` 把 `flash_attn` 和 `diffusion_flash_attn` **都**设为 false，
   后者作用于 UNet（算力主体）。两个都要显式打开，且它只改内存访问模式、不改结果。

## 排错备忘

- `LLVM ERROR: out of memory` → 词表没精简 / 并行太高（`-j 1`）/ 优化级别太高（临时 `-O0`）
- `页面文件太小 (0x5AF)` → Windows commit limit，同上
- `libomp.so` 找不到 → 上面那条 Copy-Item，NDK 里在 `lib/clang/18/lib/linux/aarch64/`
