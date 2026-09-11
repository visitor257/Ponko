package com.litertchat.app.draw;

import android.content.Context;

import io.aatricks.llmedge.DiffusionWorkerMode;
import io.aatricks.llmedge.ExecutionConfig;
import io.aatricks.llmedge.HangRecoveryPolicy;
import io.aatricks.llmedge.LLMEdgeConfig;
import io.aatricks.llmedge.ImageRuntimeConfig;
import io.aatricks.llmedge.RuntimeCacheConfig;
import io.aatricks.llmedge.SpeechRuntimeConfig;
import io.aatricks.llmedge.TextRuntimeConfig;
import io.aatricks.llmedge.VisionRuntimeConfig;
import io.aatricks.llmedge.WorkerWatchdogConfig;
import io.aatricks.llmedge.image.ImageClient;
import io.aatricks.llmedge.model.ModelRegistry;
import io.aatricks.llmedge.text.runtime.SmolLM;

import kotlinx.coroutines.CoroutineScope;

/**
 * 用 Java 构造 llmedge 的配置。
 *
 * 背景：llmedge 0.4.7.x 把 ImageRuntimeConfig 标成了 Kotlin internal，
 * Kotlin 代码无法引用；但字节码层面它是 public，Java 可以正常构造。
 * 我们需要它来设置两个关键项：
 *   - useVulkan = false/true        → 纯 CPU 或尝试 GPU（由模型页「运行方式」决定）
 *   - workerMode = ISOLATED_PROCESS → 在 :llmedge_sd 子进程跑，崩了不连累主 App
 */
public final class LlmedgeConfigFactory {

    private LlmedgeConfigFactory() {
    }

    /** 独立进程的绘图客户端；useGpu=true 时尝试 Vulkan（失败会回退 CPU）。 */
    public static ImageClient cpuIsolatedClient(Context context, CoroutineScope scope, boolean useGpu) {
        ImageRuntimeConfig image = new ImageRuntimeConfig(
                new RuntimeCacheConfig(1, 4096L),          // cache
                false,                                      // preferPerformanceMode
                useGpu,                                     // useVulkan
                DiffusionWorkerMode.ISOLATED_PROCESS,       // workerMode ← 独立进程
                defaultWatchdog(),                          // watchdog
                HangRecoveryPolicy.RETRY_CPU_THEN_FAIL,     // hangRecoveryPolicy
                true                                        // persistBackendVerdicts
        );

        LLMEdgeConfig config = new LLMEdgeConfig(
                new ExecutionConfig(4),                     // execution（线程数不用，绘图走自己的）
                new ModelRegistry(),                        // models
                defaultText(),
                new SpeechRuntimeConfig(new RuntimeCacheConfig(1, 1024L)),
                image,
                new VisionRuntimeConfig(new RuntimeCacheConfig(1, 2048L), false, 4, 4, true)
        );

        return ImageClient.create(context, scope, config);
    }

    private static WorkerWatchdogConfig defaultWatchdog() {
        return new WorkerWatchdogConfig(
                true,          // enabled
                2000L,         // cpuSampleIntervalMs
                1800000L,      // resolvingStallTimeoutMs
                90000L,        // loadingStallTimeoutMs
                90000L,        // generatingStallTimeoutMs
                300000L,       // stepStallTimeoutMs
                1800000L,      // hardWallTimeoutMs
                5000L,         // cancelKillGraceMs
                0.02           // flatCpuThreshold
        );
    }

    private static TextRuntimeConfig defaultText() {
        return new TextRuntimeConfig(
                new RuntimeCacheConfig(2, 2048L),  // cache
                false,      // useVulkan
                4,          // promptThreads
                4,          // generationThreads
                512,        // batchSize
                1,          // streamBatchSize
                null,       // contextSize
                0.1f,       // minP
                0.8f,       // temperature
                true,       // useMmap
                false,      // useMlock
                true,       // useFlashAttention
                SmolLM.KvCacheType.DEFAULT,   // kvCacheTypeK
                SmolLM.KvCacheType.DEFAULT,   // kvCacheTypeV
                0           // nUbatch
        );
    }
}
