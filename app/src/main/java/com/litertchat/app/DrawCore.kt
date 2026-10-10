package com.litertchat.app

import android.graphics.Bitmap
import com.litertchat.app.draw.SdCppEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 绘图引擎的**共享入口**：App 界面与「API 服务端」的绘图接口都从这里出图。
 *
 * 为什么要有这一层（与 [ChatCore] 同一套理由）：sd.cpp 的句柄原本是 DrawPage 的私有字段，
 * 页面/Activity 一销毁就被 free；而 API 服务要在 App 退到后台后继续能出图，所以句柄与参数
 * 必须挂在进程级单例上。DrawPage 加载完模型只是把句柄登记进来（[attach]），
 * 参数改动时把**已经解析好的**参数快照同步过来（[updateParams]），
 * 界面重建 / 换主题都不影响这里。
 *
 * 为什么不直接读界面控件：API 的请求线程不是主线程，而 EditText 只能在主线程读；
 * 而且 Activity 销毁后控件状态也不该再被依赖。所以这里存的是「值」不是「控件」。
 *
 * 串行化：一张图要跑几十秒到几分钟，本地引擎一次只能跑一个任务。
 * [generate] 走 [lock]，并且对外暴露 [busy]；界面出图时通过 [uiBusy] 反向上报，
 * 两边互相避让（界面用 DrawPage.isBusy()，API 用 busy/apiBusy 判断后返回 429）。
 */
object DrawCore {

    /** 出图参数快照（全部是「已经解析好的最终值」，不含任何 UI 依赖） */
    class Params(
        val steps: Int = 20,
        val cfg: Float = 7.0f,
        val width: Int = 512,
        val height: Int = 512,
        val negative: String = "",
        val sampler: SdCppEngine.Sampler = SdCppEngine.Sampler.EULER_A,
        val scheduler: SdCppEngine.Scheduler = SdCppEngine.Scheduler.DISCRETE,
        val loraPath: String? = null,
        val loraScale: Float = 1.0f,
    )

    class Result(val bitmap: Bitmap, val seed: Long)

    @Volatile private var handle = 0L

    /** 当前已加载的绘图模型名（/v1/models 与状态显示用） */
    @Volatile var modelName: String? = null
        private set

    /** 实际使用的后端（CPU / Vulkan0…），仅用于展示 */
    @Volatile var backend: String? = null
        private set

    /** 参数快照；界面每次改参数/加载模型都会同步过来 */
    @Volatile private var params = Params()

    /** API 正在出图 */
    @Volatile var busy = false
        private set

    /** 界面正在出图（由 DrawPage 上报）——API 侧据此避让，别和界面抢同一个句柄 */
    @Volatile var uiBusy = false

    @Volatile private var cancelFlag = false

    private val lock = Mutex()

    /** 登记句柄。**只放引用，不接管所有权**——句柄仍由 DrawPage free。 */
    fun attach(h: Long, name: String?, backendName: String? = null) {
        handle = h
        modelName = name
        backend = backendName
    }

    /** 参数快照同步（主线程调用） */
    fun updateParams(p: Params) {
        params = p
    }

    /** 只清引用，不 free（free 由句柄持有者 DrawPage 负责，且**必须先调这个**，否则 API 会拿到已释放的句柄） */
    fun release() {
        handle = 0L
        modelName = null
        backend = null
    }

    fun isReady(): Boolean = handle != 0L

    fun requestCancel() {
        cancelFlag = true
        val h = handle
        if (h != 0L) runCatching { SdCppEngine.nativeCancel(h) }
    }

    /**
     * 出图（供 API 使用）。没传的字段沿用界面上的当前设置。
     *
     * `n` 张图在**同一次持锁**里连着出：不然两张之间会出现一个空档，
     * 界面正好点「生成」就会插进来抢同一个句柄。
     *
     * 抢不到锁直接抛异常（上层回 429）——排队没有意义，一张图要跑几分钟。
     */
    suspend fun generate(
        prompt: String,
        n: Int = 1,
        seed: Long = -1L,
        steps: Int? = null,
        cfg: Float? = null,
        width: Int? = null,
        height: Int? = null,
        negative: String? = null,
        sampler: SdCppEngine.Sampler? = null,
        scheduler: SdCppEngine.Scheduler? = null,
        onStep: ((Int, Int) -> Unit)? = null,
    ): List<Result> {
        if (!lock.tryLock()) throw IllegalStateException("绘图引擎正忙")
        busy = true
        cancelFlag = false
        try {
            val p = params
            val h = handle
            if (h == 0L) throw IllegalStateException("未加载绘图模型")

            // sd.cpp 要求 64 的倍数；夹到 64~2048 防止手滑
            val w = (width ?: p.width).let { (it / 64) * 64 }.coerceIn(64, 2048)
            val ht = (height ?: p.height).let { (it / 64) * 64 }.coerceIn(64, 2048)
            val st = (steps ?: p.steps).coerceIn(1, 150)
            val cf = (cfg ?: p.cfg).coerceIn(1f, 30f)

            val out = ArrayList<Result>(n)
            for (i in 0 until n) {
                if (cancelFlag) break
                // 给了 seed 就让第 i 张 = seed+i，保证 n>1 时不是同一张图
                val useSeed = if (seed < 0) System.currentTimeMillis() % 1_000_000_000L else seed + i
                val bmp = withContext(Dispatchers.IO) {
                    SdCppEngine.render(
                        handle = h,
                        prompt = prompt,
                        negative = negative ?: p.negative,
                        loraPath = p.loraPath,
                        loraScale = p.loraScale,
                        width = w,
                        height = ht,
                        steps = st,
                        cfg = cf,
                        seed = useSeed,
                        sampler = sampler ?: p.sampler,
                        scheduler = scheduler ?: p.scheduler,
                        cb = { cur, total -> onStep?.invoke(cur, total) },
                    )
                } ?: throw IllegalStateException("生成失败（引擎返回空图）")
                out.add(Result(bmp, useSeed))
            }
            if (out.isEmpty()) throw IllegalStateException("已取消")
            return out
        } finally {
            busy = false
            lock.unlock()
        }
    }
}
