package com.litertchat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * API 服务：把手机上的本地模型开放成 **OpenAI 兼容**接口，App 退到后台、锁屏后继续响应请求。
 *
 * **对话与绘图是两套互相独立的东西**，本服务同时托管它们：
 *
 * | | 对话 API | 绘图 API |
 * |---|---|---|
 * | 端口 | `apiPort`（默认 8080） | `drawApiPort`（默认 8081） |
 * | key  | `apiKey` | `drawApiKey` |
 * | 开关 | `apiEnabled` | `drawApiEnabled` |
 * | 路由 | `/v1/chat/completions` | `/v1/images/generations` |
 *
 * 各有各的端口、key、开关和统计，可以只开一个、也可以两个都开。
 * 之所以不挤在一个端口上：两者的服务对象、资源占用和安全暴露面完全不同——
 * 出图一张要几十秒且吃满 CPU、通常只该在可信网络里开，而对话接口是给人天天连的；
 * 硬绑在一起就等于不让人分开配。
 *
 * 但**只用一个前台服务 + 一条通知**：Android 的前台服务是「保住进程不被杀」的机制，
 * 起两个只会得到两条常驻通知，还得各自处理权限与 WifiLock，没有任何收益。
 * 通知里会把在跑的地址都列出来。
 *
 * 为什么类型必须是 **specialUse**：`dataSync` / `mediaProcessing` 在 Android 15+（targetSdk 35+）
 * 有「24 小时内最多 6 小时」的超时限制，到点会被系统强制停掉——常驻服务不能用那两类。
 *
 * 真正的推理在 [ChatCore] / [DrawCore]（进程级单例），所以 App 里加载好的模型这里直接能用。
 */
class ApiService : Service() {

    companion object {
        const val ACTION_START = "com.litertchat.app.action.API_START"
        const val ACTION_STOP = "com.litertchat.app.action.API_STOP"

        private const val CHANNEL_ID = "ponko_api"
        private const val NOTIF_ID = 4101
        private const val PREFS = "ponko"

        // ---- 对话 API ----
        const val KEY_ENABLED = "apiEnabled"
        const val KEY_PORT = "apiPort"
        const val KEY_TOKEN = "apiKey"
        const val DEF_PORT = 8080

        // ---- 绘图 API（独立；默认端口与对话错开，免得撞车）----
        const val KEY_DRAW_ENABLED = "drawApiEnabled"
        const val KEY_DRAW_PORT = "drawApiPort"
        const val KEY_DRAW_TOKEN = "drawApiKey"
        const val DEF_DRAW_PORT = 8081

        @Volatile var chatRunning = false
            private set
        @Volatile var drawRunning = false
            private set

        /** 任一在跑 */
        val running: Boolean get() = chatRunning || drawRunning

        @Volatile var chatAddress: String? = null
            private set
        @Volatile var drawAddress: String? = null
            private set

        @Volatile var chatServed = 0
            private set
        @Volatile var chatLast: String? = null
            private set
        @Volatile var drawServed = 0
            private set
        @Volatile var drawLast: String? = null
            private set

        /** 当前在跑的服务器实例（同进程，界面直接读实时统计） */
        @Volatile internal var chatServer: ApiServer? = null
        @Volatile internal var drawServer: ApiServer? = null

        // ---------- 按 role 取偏好的键名 / 默认值 ----------

        fun keyEnabled(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) KEY_ENABLED else KEY_DRAW_ENABLED

        fun keyPort(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) KEY_PORT else KEY_DRAW_PORT

        fun keyToken(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) KEY_TOKEN else KEY_DRAW_TOKEN

        fun defaultPort(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) DEF_PORT else DEF_DRAW_PORT

        fun isRunning(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) chatRunning else drawRunning

        fun addressOf(role: ApiServer.Role): String? =
            if (role == ApiServer.Role.CHAT) chatAddress else drawAddress

        /** 拉一次实时统计：请求数 + 最近一条 */
        fun statsSnapshot(role: ApiServer.Role): Pair<Int, String?> {
            publish()
            return if (role == ApiServer.Role.CHAT) chatServed to chatLast else drawServed to drawLast
        }

        private fun publish() {
            chatServer?.let { chatServed = it.served; chatLast = it.lastRequest }
            drawServer?.let { drawServed = it.served; drawLast = it.lastRequest }
        }

        private fun serverOf(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) chatServer else drawServer

        private fun setServer(role: ApiServer.Role, s: ApiServer?) {
            if (role == ApiServer.Role.CHAT) chatServer = s else drawServer = s
        }

        private fun setRunning(role: ApiServer.Role, v: Boolean) {
            if (role == ApiServer.Role.CHAT) chatRunning = v else drawRunning = v
        }

        private fun setAddress(role: ApiServer.Role, v: String?) {
            if (role == ApiServer.Role.CHAT) chatAddress = v else drawAddress = v
        }

        /**
         * 界面改完开关 / 端口 / key 后调用：按当前偏好把两个服务器的启停对齐。
         *
         * 端口或 key 变了会**自动重启那一个**（而不是让用户自己记得重启），
         * 这样「改了端口立刻就生效」，少一个坑。
         */
        fun sync(ctx: Context) {
            val i = Intent(ctx, ApiService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stopAll(ctx: Context) {
            ctx.startService(Intent(ctx, ApiService::class.java).setAction(ACTION_STOP))
        }

        /** 本机在局域网里的 IPv4（拿不到就返回 127.0.0.1） */
        fun localIp(): String = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress ?: "127.0.0.1"
        } catch (_: Throwable) {
            "127.0.0.1"
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务必须尽快 startForeground（可能是被 startForegroundService 拉起的），
        // 所以先转前台、再去对齐服务器
        ensureChannel()
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownAll()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                reconcile()
                if (!running) {
                    // 一个都不需要跑 → 收摊（顺带把通知撤掉）
                    shutdownAll()
                    stopSelf()
                    return START_NOT_STICKY
                }
                acquireWifiLock()
                refreshNotification()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownAll()
        super.onDestroy()
    }

    // ==================== 启停对齐 ====================

    private fun reconcile() {
        reconcileOne(ApiServer.Role.CHAT)
        reconcileOne(ApiServer.Role.DRAW)
    }

    private fun reconcileOne(role: ApiServer.Role) {
        val enabled = prefs().getBoolean(keyEnabled(role), false)
        val port = prefs().getInt(keyPort(role), defaultPort(role)).coerceIn(1024, 65535)
        val key = prefs().getString(keyToken(role), null)?.takeIf { it.isNotBlank() }
        val existing = serverOf(role)

        // 已按当前配置在跑 → 不动
        if (enabled && existing != null && existing.port == port && existing.apiKey == key) return

        // 配置变了或要关闭 → 先停掉旧实例
        if (existing != null) {
            publish()
            existing.stop()
            setServer(role, null)
            setRunning(role, false)
            setAddress(role, null)
        }
        if (!enabled) return

        val srv = ApiServer(port, key, role) { msg ->
            if (role == ApiServer.Role.CHAT) chatLast = msg else drawLast = msg
        }
        if (srv.start()) {
            setServer(role, srv)
            setRunning(role, true)
            setAddress(role, "http://${localIp()}:$port")
        } else {
            // 端口被占用之类：保持"未运行"，界面会显示成未运行、日志里有原因
            setServer(role, null)
            setRunning(role, false)
            setAddress(role, null)
        }
    }

    private fun shutdownAll() {
        for (role in listOf(ApiServer.Role.CHAT, ApiServer.Role.DRAW)) {
            serverOf(role)?.let {
                publish()
                it.stop()
            }
            setServer(role, null)
            setRunning(role, false)
            setAddress(role, null)
        }
        releaseWifiLock()
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    // ==================== 通知 ====================

    private fun nm(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.s_393),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.s_394)
            setShowBadge(false)
        }
        nm().createNotificationChannel(ch)
    }

    /** 通知正文：把在跑的地址都列出来；一个都没起来就说「启动中」或「启动失败」 */
    private fun notificationText(): String {
        val parts = ArrayList<String>()
        if (chatRunning) parts.add(getString(R.string.s_431, chatAddress ?: ""))
        if (drawRunning) parts.add(getString(R.string.s_432, drawAddress ?: ""))
        // 有哪一个「开着却没起来」（端口被占用等）→ 补一句，否则用户只能看到少了一行
        val failed = listOf(ApiServer.Role.CHAT, ApiServer.Role.DRAW).count {
            prefs().getBoolean(keyEnabled(it), false) && !isRunning(it)
        }
        if (parts.isEmpty()) {
            // 一个都没起来：要么还在起，要么全失败了——不能让用户对着「正在启动…」干等
            return if (failed > 0) getString(R.string.s_435) else getString(R.string.s_434)
        }
        val txt = parts.joinToString("  ·  ")
        return if (failed > 0) txt + "  ·  " + getString(R.string.s_435) else txt
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ApiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, if (Build.VERSION.SDK_INT >= 26) CHANNEL_ID else null)
            .setContentTitle(getString(R.string.s_391))
            .setContentText(notificationText())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.s_396), stop).build()
            )
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** 刷新通知内容（状态变化时调用） */
    fun refreshNotification() {
        runCatching { nm().notify(NOTIF_ID, buildNotification()) }
    }

    // ==================== WifiLock ====================

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ponko:api").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
