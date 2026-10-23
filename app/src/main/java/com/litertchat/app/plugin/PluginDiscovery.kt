// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.litertchat.app.R
import com.litertchat.plugin.PluginConst

/**
 * 扫描手机上装了哪些 Ponko 插件，并把「为什么连不上」查清楚。
 *
 * 靠 `queryIntentServices(ACTION_PLUGIN)` 找到插件 —— 插件在自己的 Service 上挂这个
 * action，系统就会把它列出来。
 *
 * ## ⚠ 会让「插件列表永远空」的坑
 *
 * 宿主的 Manifest 必须声明 `<queries>`（Android 11+ 包可见性）。漏了不会报错，
 * `queryIntentServices` 就是**静默返回空列表**。
 *
 * ## ⚠ 会让「列表有、但 bind 返回 false」的坑
 *
 * 扫描**不等于**能连上。以下四种情况扫描都看得到，`bindService` 却会失败：
 *
 * | 原因 | 扫描能否看到 | bind 表现 |
 * |---|---|---|
 * | 包/组件被停用（`MATCH_DISABLED_COMPONENTS` 才会被返回） | ✅ | 返回 **false** |
 * | 包处于 **stopped** 状态（装完从没启动过） | ✅ | 返回 **false** |
 * | 签名不一致（`signature` 权限不成立） | ✅ | 抛 `SecurityException` |
 * | 宿主没持有 `BIND_PLUGIN` 权限 | ✅ | 抛 `SecurityException` |
 *
 * 返回 **false 意味着系统根本没解析到这个服务**，跟权限无关 —— 权限问题是抛异常。
 * 所以 [diagnose] 的重点就是回答「为什么解析不到」。
 */
object PluginDiscovery {

    /**
     * `ApplicationInfo.FLAG_SUSPENDED` 的值（API 24 起）。
     *
     * 不直接用 `ApplicationInfo.FLAG_SUSPENDED` 是因为它在高版本才暴露成公开常量，
     * 而这个值从引入起就没变过 —— 写死比加版本判断更简单，也更好读。
     */
    private const val FLAG_SUSPENDED = 1 shl 30

    /**
     * 扫到的插件（尚未绑定，所以还没有 [com.litertchat.plugin.PluginManifest]）。
     *
     * @param packageName 用它在 [PluginRegistry] 里记「已启用」，也是 bind 的目标
     * @param serviceClass 插件 Service 的完整类名；解析不到时为空。
     *                     bind 时用它拼显式 ComponentName —— 官方推荐做法，
     *                     能排除隐式 intent 的解析差异
     * @param label 从包信息里读到的应用名，作为绑定前的临时显示名
     */
    data class Found(
        val packageName: String,
        val serviceClass: String,
        val label: String,
        val versionName: String,
        val enabled: Boolean,
        /** 签名算法读不到时为空 */
        val signerShort: String,
        /** 包是否处于启用状态（`ApplicationInfo.enabled`）。false = 被用户/系统停用 */
        val installed: Boolean,
        /** 用 flags=0 能否解析到 Service。false = 组件被禁，bind 一定失败 */
        val resolvable: Boolean,
        /** 包处于 stopped 状态：装完从未启动过，其他应用 bind 不到它 */
        val stopped: Boolean,
        /** 插件自带的启动入口（用来「点开一次」清掉 stopped）；没有则 null */
        val hasLauncher: Boolean,
        /**
         * 插件声明的**功能按钮白名单**（`ponko.actions` meta-data）。
         *
         * - `null` = 没声明 → 显示宿主的全部按钮（老插件行为，完全不受影响）
         * - 空列表 = 声明了但一个都不要 → **一个按钮都不画**（插件明确要求）
         * - 非空 = 只画列表里的那些，其余全部隐藏
         *
         * 为什么走 manifest 而不是 AIDL：这只是显示偏好，不需要跨进程协议。
         * 加字段就得升 SDK、所有插件重编，代价和收益完全不成比例。
         */
        val actionWhitelist: List<String>?,

        /**
         * 插件自己写的「关于」文档（纯文本）。
         *
         * 来源是插件的 manifest，两种写法都支持（service 优先，其次
         * `<application>`）：
         *
         * ```xml
         * <!-- ① 短文本直接写 -->
         * <meta-data android:name="ponko.about" android:value="…" />
         * <!-- ② 长文档放 res/raw/about.txt -->
         * <meta-data android:name="ponko.about" android:resource="@raw/about" />
         * ```
         *
         * `null` = 没声明 → 卡片上那个「关于」弹窗显示"无"。
         *
         * ## 为什么走 manifest 而不是 AIDL
         *
         * 两个理由，第二个更重要：
         *
         * 1. 这是**静态文本**，跟着 APK 版本走，不需要跨进程协议；
         *    加 AIDL 方法就得升 SDK、所有插件重编
         * 2. **插件连不上时也要能看关于**。卡片上最需要"这是什么、谁做的"
         *    的时刻，恰恰是插件处于 stopped / 连接失败的时候 ——
         *    走 AIDL 的话那时候只能显示"无"
         */
        val about: String?,
    )

    fun scan(c: Context): List<Found> {
        val pm = c.packageManager
        val intent = Intent(PluginConst.ACTION_PLUGIN)

        // 关键：这里**不加** MATCH_DISABLED_COMPONENTS / MATCH_ALL。
        //
        // 加了之后列表里会出现「看起来在、其实 bind 不到」的条目 —— 用户点启用就报
        // 「bindService 返回 false」，却完全看不出原因。现在改成：
        //   · 主扫描用 flags=0 —— 只列真正能连的
        //   · 再用 MATCH_DISABLED_COMPONENTS 补扫一遍 —— 找出被停用的，
        //     放进列表但标成「已停用」，让用户知道"它在这，只是被关了"
        val normal = resolve(pm, intent, 0)
        val withDisabled = resolve(pm, intent, PackageManager.MATCH_DISABLED_COMPONENTS)

        // 被停用的：补扫里有、主扫描里没有
        val normalPkgs = normal.mapNotNull { it.serviceInfo?.packageName }.toSet()

        val all = LinkedHashMap<String, String>()   // pkg -> serviceClass
        withDisabled.forEach { ri ->
            val si = ri.serviceInfo ?: return@forEach
            all.putIfAbsent(si.packageName, si.name)
        }
        normal.forEach { ri ->
            val si = ri.serviceInfo ?: return@forEach
            all[si.packageName] = si.name     // 能正常解析的以主扫描为准
        }

        return all.mapNotNull { (pkg, cls) ->
            describe(c, pkg, cls, resolvable = pkg in normalPkgs)
        }.sortedBy { it.label.lowercase() }
    }

    private fun resolve(pm: PackageManager, intent: Intent, extraFlags: Int): List<ResolveInfo> =
        try {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, extraFlags)
        } catch (t: Throwable) {
            emptyList()
        }

    private fun describe(c: Context, pkg: String, serviceClass: String, resolvable: Boolean): Found? {
        val pm = c.packageManager
        return try {
            val info = pm.getPackageInfo(pkg, 0)
            val ai = info.applicationInfo ?: return null
            Found(
                packageName = pkg,
                serviceClass = serviceClass,
                label = ai.loadLabel(pm).toString().ifEmpty { pkg },
                versionName = info.versionName ?: "?",
                enabled = PluginRegistry.isEnabled(c, pkg),
                signerShort = signerShort(pm, pkg),
                // 两个状态分开报：包被停用和组件被禁是不同原因、不同解法。
                // 合成一个字段会让 UI 说不出到底是哪种，用户只能瞎试
                installed = ai.enabled,
                resolvable = resolvable,
                stopped = (ai.flags and ApplicationInfo.FLAG_STOPPED) != 0,
                hasLauncher = hasLauncher(pm, pkg),
                actionWhitelist = actionsWhitelistOf(pm, pkg, serviceClass),
                about = aboutOf(c, pkg, serviceClass),
            )
        } catch (t: Throwable) {
            // 包在扫描和读取之间被卸载了（竞态），跳过即可
            null
        }
    }

    /**
     * 读插件声明的 `ponko.actions` 白名单。
     *
     * ## 两个位置都读
     *
     * 插件通常写在 `<service>` 里（它本来就在 Service 上挂
     * `ACTION_PLUGIN`），但写 `<application>` 下也一样合理 ——
     * 两处都支持，**service 优先**（那是最贴近插件本体的位置）。
     *
     * ## 三种返回值，含义严格区分
     *
     * | meta-data | 返回 | 效果 |
     * |---|---|---|
     * | 没写 | `null` | 显示全部按钮（老插件不受影响） |
     * | `android:value=""` | 空列表 | 一个按钮都不画 |
     * | `"reasoning,stop"` | 两个 id | 只画这两个 |
     *
     * 空白项会被丢掉（`"reasoning, ,stop"` 里那个空串不是 id）。
     * 认不出的 id 由调用方过滤并打日志 —— 这里只管"解析"。
     */
    private fun actionsWhitelistOf(
        pm: PackageManager,
        pkg: String,
        serviceClass: String,
    ): List<String>? {
        val key = "ponko.actions"

        fun fromService(): String? = try {
            @Suppress("DEPRECATION")
            pm.getServiceInfo(
                android.content.ComponentName(pkg, serviceClass),
                PackageManager.GET_META_DATA
            ).metaData?.getString(key)
        } catch (t: Throwable) {
            null
        }

        fun fromApp(): String? = try {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                .metaData?.getString(key)
        } catch (t: Throwable) {
            null
        }

        // 注意 `?: return null` 和 `?.` 的区别：这里必须用 `?:` 判断
        // "到底有没有声明"，因为空字符串也是有效的声明（= 一个都不要）
        val raw = fromService() ?: fromApp() ?: return null
        return raw.split(',', ';', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 读插件自己写的「关于」文档。
     *
     * ## 两种写法
     *
     * ```xml
     * <meta-data android:name="ponko.about" android:value="一句话介绍" />
     * <meta-data android:name="ponko.about" android:resource="@raw/about" />
     * ```
     *
     * 短文本用 `value`，长的用 `resource`（放在 `res/raw/about.txt`）——
     * 把几百字塞进 manifest 属性里既难写也难转义，`resource` 才是长文档该走的路。
     *
     * ## 为什么两处都读
     *
     * 和 `ponko.actions` 一样：写在 `<service>` 里最贴近插件本体，
     * 写在 `<application>` 下也合理。**service 优先。**
     *
     * ## 返回值
     *
     * `null` = 没声明 / 读不出来。UI 那边显示"无"，
     * **不弹错误** —— 缺一个关于文档不是故障。
     */
    private fun aboutOf(c: Context, pkg: String, serviceClass: String): String? {
        val key = "ponko.about"
        val pm = c.packageManager

        fun bundleOf(): android.os.Bundle? {
            fun fromService(): android.os.Bundle? = try {
                @Suppress("DEPRECATION")
                pm.getServiceInfo(
                    android.content.ComponentName(pkg, serviceClass),
                    PackageManager.GET_META_DATA
                ).metaData
            } catch (t: Throwable) {
                null
            }

            fun fromApp(): android.os.Bundle? = try {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData
            } catch (t: Throwable) {
                null
            }

            // 同样用 `?:`：空串也是有效声明（"有关于，但内容为空" → 显示无）
            return fromService()?.takeIf { it.containsKey(key) }
                ?: fromApp()?.takeIf { it.containsKey(key) }
        }

        val md = bundleOf() ?: return null

        // ① android:resource —— 读 res/raw 里的文件
        //
        // meta-data 用 resource 写法时，值以 int（资源 id）存在 bundle 里。
        // 不能直接 getString，那样拿到的是资源 id 的十进制字符串，会显示成
        // "2131689472" 这种东西。
        run {
            val resId = md.getInt(key, 0)
            if (resId != 0) {
                val text = try {
                    c.createPackageContext(pkg, 0).resources
                        .openRawResource(resId)
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (t: Throwable) {
                    android.util.Log.w(
                        "PonkoPluginDiscovery",
                        "[$pkg] 读 ponko.about 的 resource 失败：${t.javaClass.simpleName}"
                    )
                    null
                }
                if (text != null) return text.trim().ifEmpty { null }
            }
        }

        // ② android:value —— 内联文本
        val inline = try {
            md.getString(key)
        } catch (t: Throwable) {
            null
        }
        return inline?.trim()?.ifEmpty { null }
    }

    /** 插件有没有可启动的激活组件 —— 有它才能清掉 stopped 状态 */
    private fun hasLauncher(pm: PackageManager, pkg: String): Boolean =
        activatorClassOf(pm, pkg) != null

    /**
     * 找插件的**激活组件**类名；没有返回 null。
     *
     * 两条路，优先第一条：
     *
     * 1. **`ACTION_ACTIVATOR`** —— 插件继承 [com.litertchat.plugin.PonkoActivatorActivity]
     *    并声明这个 action。**没有 `CATEGORY_LAUNCHER`，所以桌面不显示图标**，
     *    启动时也不画界面。这是推荐做法（"像系统组件一样隐藏"）。
     * 2. **`CATEGORY_LAUNCHER`** —— 兼容老插件（它们只有能看见的说明页）。
     *    桌面会有图标，但至少能连上。
     */
    private fun activatorClassOf(pm: PackageManager, pkg: String): String? {
        // ① 无界面的激活组件（首选）
        val byAction = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(
                Intent(com.litertchat.plugin.PonkoActivation.ACTION_ACTIVATOR)
                    .setPackage(pkg), 0
            ).firstOrNull()?.activityInfo?.name
        } catch (t: Throwable) {
            null
        }
        if (!byAction.isNullOrEmpty()) return byAction

        // ② 退回桌面图标（老插件）
        return try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(pkg), 0
            ).firstOrNull()?.activityInfo?.name
        } catch (t: Throwable) {
            null
        }
    }

    /** 对外版本：传给 [com.litertchat.plugin.PonkoActivation.buildIntent] 用 */
    fun launcherClassOf(c: Context, pkg: String): String? =
        activatorClassOf(c.packageManager, pkg)

    /**
     * 故意**不提供**「普通启动插件」的方法。
     *
     * 宿主启动插件只有一个目的：清 stopped 标记。那就必须带
     * [com.litertchat.plugin.PonkoActivation.EXTRA_ACTIVATE_ONLY]，
     * 让插件立刻 finish —— 见 [com.litertchat.plugin.PonkoActivation.buildIntent]。
     *
     * 留一个能"正常打开插件界面"的入口会诱使人误用：用户点一下结果被扔进
     * 一个不相干的说明页，还得自己按返回。
     */

    // ==================== 诊断 ====================

    /**
     * 一次绑定的"体检结果"。
     *
     * 每一项都对应 `bindService` 失败的一个**真实**原因 —— 这些不是我猜的，
     * 是按 AOSP `ActiveServices.retrieveServiceLocked()` 里 `return null` 的路径倒推的：
     *
     * ```java
     * // ① 显式 component 时走这里；拿不到 ServiceInfo 就 return null
     * r = getServiceInfo(service.getComponent(), flags, userId);
     * // ② 可见性/跨用户过滤，同样 return null
     * if (filterAppAccess(r.packageName, callingUid, userId)) return null;
     * ```
     *
     * 两条路径**打同一句日志**（`Unable to start service ...: not found`），
     * 而普通应用读不到 logcat —— 所以只能靠 [explicitProbe] 把它们分开。
     */
    data class Diag(
        val holdsPermission: Boolean,
        val selfSigner: String,
        val pluginSigner: String,
        /** 隐式（action + package）能否解析到 Service */
        val resolvable: Boolean,
        /**
         * **显式** `getServiceInfo(ComponentName)` 是否拿得到。
         *
         * 这是区分两类失败的关键：
         * · `resolvable=true` 但这里 `false` → 组件名对不上，或跨用户（分身/工作资料）
         * · 两个都 `true` 但 bind 仍失败 → 只看可见性了，靠 QUERY_ALL_PACKAGES 兜
         */
        val explicitProbe: Boolean,
        val serviceClass: String,
        val packageEnabled: Boolean,
        val componentEnabled: Boolean,
        val stopped: Boolean,
        /** `FLAG_SUSPENDED`：被系统「暂停」，组件一律不可用 */
        val suspended: Boolean,
        val hasLauncher: Boolean,
        /** 插件所在的用户 ID（uid / 100000） */
        val pluginUserId: Int,
        /** 宿主所在的用户 ID */
        val selfUserId: Int,
        /** 插件进程名；与包名不同说明它跑在独立进程 */
        val processName: String,
    ) {
        /** 签名是否一致（不一致 → SecurityException） */
        val signerMatch: Boolean
            get() = selfSigner.isNotEmpty() && pluginSigner.isNotEmpty() && selfSigner == pluginSigner

        /** 插件装在别的用户空间（应用分身 / 工作资料）—— 跨用户一律 bind 不到 */
        val crossUser: Boolean
            get() = pluginUserId != selfUserId

        fun headline(c: Context): String = when {
            !holdsPermission ->
                c.getString(R.string.s_711)
            !signerMatch && pluginSigner.isNotEmpty() ->
                c.getString(R.string.s_712, selfSigner, pluginSigner)
            !packageEnabled ->
                c.getString(R.string.s_713)
            !componentEnabled ->
                c.getString(R.string.s_714)
            suspended ->
                c.getString(R.string.s_715)
            crossUser ->
                c.getString(R.string.s_716)
            stopped ->
                if (hasLauncher) c.getString(R.string.s_717)
                else c.getString(R.string.s_718)
            !resolvable ->
                c.getString(R.string.s_719)
            !explicitProbe ->
                c.getString(R.string.s_720)
            else ->
                c.getString(R.string.s_721)
        }

        /** 给开发者看的一行，用户复制给作者用 */
        fun detailLine(c: Context): String = buildString {
            append(c.getString(R.string.s_722)).append(if (resolvable) "OK" else c.getString(R.string.s_587))
            append(c.getString(R.string.s_723)) .append(if (explicitProbe) "OK" else c.getString(R.string.s_587))
            append(c.getString(R.string.s_724)).append(if (packageEnabled) c.getString(R.string.s_731) else c.getString(R.string.s_732))
            append(c.getString(R.string.s_725)).append(if (componentEnabled) c.getString(R.string.s_731) else c.getString(R.string.s_734))
            append(c.getString(R.string.s_726)).append(if (stopped) c.getString(R.string.s_735) else c.getString(R.string.s_736))
            append(c.getString(R.string.s_727)).append(if (suspended) c.getString(R.string.s_735) else c.getString(R.string.s_736))
            append(c.getString(R.string.s_728)).append(selfUserId).append("/").append(pluginUserId)
        }
    }

    fun diagnose(c: Context, pkg: String): Diag {
        val pm = c.packageManager
        var serviceClass = ""
        var resolvable = false
        var pkgEnabled = false
        var compEnabled = false
        var stopped = false
        var suspended = false
        var launcher = false
        var pluginUid = -1
        var processName = ""

        try {
            val i = Intent(PluginConst.ACTION_PLUGIN).setPackage(pkg)
            @Suppress("DEPRECATION")
            val ri = pm.queryIntentServices(i, 0).firstOrNull()
            if (ri?.serviceInfo != null) {
                resolvable = true
                serviceClass = ri.serviceInfo.name
                processName = ri.serviceInfo.processName ?: ""
            }
            val info = pm.getPackageInfo(pkg, 0)
            val ai = info.applicationInfo
            pkgEnabled = ai?.enabled == true
            stopped = ai != null && (ai.flags and ApplicationInfo.FLAG_STOPPED) != 0
            suspended = ai != null && (ai.flags and FLAG_SUSPENDED) != 0
            pluginUid = ai?.uid ?: -1
            launcher = hasLauncher(pm, pkg)
        } catch (t: Throwable) {
            // 包不存在 / 被卸载
        }

        // 显式探测：这一步能把「找不到组件」和「可见性/跨用户过滤」分开。
        // 用 GET_META_DATA 让系统把 ServiceInfo 真正组装出来；返回 null 就是找不到
        var explicitProbe = false
        if (serviceClass.isNotEmpty()) {
            explicitProbe = try {
                @Suppress("DEPRECATION")
                pm.getServiceInfo(ComponentName(pkg, serviceClass), 0) != null
            } catch (t: Throwable) {
                false
            }
        }

        // 组件级开关。没有显式设置时是 DEFAULT，按"跟随包"处理
        if (serviceClass.isNotEmpty()) {
            compEnabled = try {
                when (pm.getComponentEnabledSetting(ComponentName(pkg, serviceClass))) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                    else -> true
                }
            } catch (t: Throwable) {
                true
            }
        }

        return Diag(
            holdsPermission = holdsBindPermission(c),
            selfSigner = selfSignerShort(c),
            pluginSigner = signerShort(pm, pkg),
            resolvable = resolvable,
            explicitProbe = explicitProbe,
            serviceClass = serviceClass,
            packageEnabled = pkgEnabled,
            componentEnabled = compEnabled,
            stopped = stopped,
            suspended = suspended,
            hasLauncher = launcher,
            // uid 的高位就是用户 ID：多用户/分身下是 10xxxxx、11xxxxx 这种
            pluginUserId = if (pluginUid > 0) pluginUid / 100000 else -1,
            selfUserId = android.os.Process.myUid() / 100000,
            processName = processName,
        )
    }


    // ==================== 包信息小工具 ====================

    /**
     * 取签名摘要前 8 位，给用户一个「这是不是同一个人做的」的粗判断。
     *
     * ⚠ 这**不是**安全校验 —— 真正的防护是 signature 权限（系统层）。
     * 这里只是让用户能一眼看出插件换了人。
     */
    fun signerShort(pm: PackageManager, pkg: String): String = try {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        val bytes = sigs?.firstOrNull()?.toByteArray() ?: return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        digest.joinToString("") { "%02x".format(it) }.take(8)
    } catch (t: Throwable) {
        ""
    }

    /**
     * 取**宿主自己**的签名摘要前 8 位。
     *
     * 专门用来和插件对比：签名不一致这件事只有把两个指纹摆在一起才看得出来 ——
     * 否则用户只会看到一句 "SecurityException"，完全无从下手。
     */
    fun selfSignerShort(c: Context): String = try {
        signerShort(c.packageManager, c.packageName)
    } catch (t: Throwable) {
        ""
    }

    /**
     * 宿主当前是否真的持有 [PluginConst.PERMISSION_BIND_PLUGIN]。
     *
     * 这是排查 `SecurityException` 的第一现场：如果这里返回 false，
     * 说明问题在宿主的权限授权上（跟插件无关），装再多插件也没用。
     */
    fun holdsBindPermission(c: Context): Boolean = try {
        c.checkSelfPermission(PluginConst.PERMISSION_BIND_PLUGIN) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }
}
