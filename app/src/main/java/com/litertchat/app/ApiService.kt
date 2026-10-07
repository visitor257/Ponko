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
 * 跑「对话 API」的前台服务：App 退到后台、锁屏后仍能继续响应请求。
 *
 * 为什么必须是前台服务：普通后台服务在 Android 8+ 活不过几分钟；而 API 服务器要长期在线。
 * 类型用 **specialUse**：`dataSync` / `mediaProcessing` 在 Android 15+（targetSdk 35+）
 * 有「24 小时内最多 6 小时」的超时限制，到点会被系统强制停掉——常驻服务不能用那两类。
 *
 * 服务本身只负责：起 [ApiServer]、发常驻通知、拿 WifiLock、把地址暴露给界面。
 * 真正的推理在 [ChatCore]（进程级单例），所以 App 界面里加载好的模型这里直接能用。
 */
class ApiService : Service() {

    companion object {
        const val ACTION_START = "com.litertchat.app.action.API_START"
        const val ACTION_STOP = "com.litertchat.app.action.API_STOP"
        private const val CHANNEL_ID = "ponko_api"
        private const val NOTIF_ID = 4101
        private const val PREFS = "ponko"
        const val KEY_PORT = "apiPort"
        const val KEY_TOKEN = "apiKey"
        const val DEF_PORT = 8080

        /** 服务是否在跑（界面用；同进程直接读） */
        @Volatile var running = false
            private set

        /** 当前对外地址，如 http://192.168.1.5:8080 */
        @Volatile var address: String? = null
            private set

        /** 处理过的请求数 / 最近一次请求（界面显示） */
        @Volatile var served = 0
            private set
        @Volatile var lastRequest: String? = null
            private set

        /** 当前正在跑的服务器实例（同进程，界面直接读它的实时统计） */
        @Volatile internal var activeServer: ApiServer? = null

        /** 内部：把 ApiServer 的统计同步出来给界面看 */
        internal fun publish(server: ApiServer) {
            served = server.served
            lastRequest = server.lastRequest
        }

        /** 界面刷新用：拉一次实时统计（请求数 + 最近一条） */
        fun statsSnapshot(): Pair<Int, String?> {
            activeServer?.let { publish(it) }
            return served to lastRequest
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, ApiService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
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

    private var server: ApiServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ensureChannel()
                val port = prefs().getInt(KEY_PORT, DEF_PORT)
                goForeground(port)
                if (server == null) {
                    val s = ApiServer(port, prefs().getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }) { msg ->
                        // 服务器自己的运行日志（启动失败之类）
                        lastRequest = msg
                    }
                    if (s.start()) {
                        server = s
                        activeServer = s
                        address = "http://${localIp()}:$port"
                    } else {
                        address = null
                        shutdown()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                acquireWifiLock()
                running = true
                // 统计数字要刷新，交给界面轮询（避免再起一个定时线程）
                server?.let { publish(it) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        server?.let {
            publish(it)
            it.stop()
        }
        server = null
        activeServer = null
        running = false
        address = null
        releaseWifiLock()
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    /** 刷新通知内容（地址 / 请求数），界面切开关时也会调用 */
    fun refreshNotification(port: Int) {
        if (!running) return
        runCatching { nm().notify(NOTIF_ID, buildNotification(port)) }
    }

    // ==================== 通知 ====================

    private fun nm(): NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

    private fun buildNotification(port: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ApiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ip = localIp()
        val hasKey = !prefs().getString(KEY_TOKEN, null).isNullOrBlank()
        return Notification.Builder(this, if (Build.VERSION.SDK_INT >= 26) CHANNEL_ID else null)
            .setContentTitle(getString(R.string.s_391))
            .setContentText(getString(R.string.s_395, ip, port, if (hasKey) getString(R.string.s_398) else getString(R.string.s_399)))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.s_396), stop).build()
            )
            .build()
    }

    private fun goForeground(port: Int) {
        val n = buildNotification(port)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
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
