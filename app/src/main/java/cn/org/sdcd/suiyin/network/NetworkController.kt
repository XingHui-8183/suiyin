package cn.org.sdcd.suiyin.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

object NetworkController {

    private const val TAG = "NetworkController"
    private const val TETHERING_WIFI = 0

    /** 热点控制结果,带详细错误信息便于主机端展示 */
    data class Result(val success: Boolean, val error: String = "")

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()
            process.waitFor()
            result != null && result.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    suspend fun executeRootCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root command failed: $command", e)
            false
        }
    }

    fun isWifiEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.isWifiEnabled
    }

    /** 同步检查 WiFi 状态(供热点流程内部使用,避免 suspend) */
    private fun isWifiEnabledSync(context: Context): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun setWifiEnabled(enable: Boolean): Boolean {
        return executeRootCommand("svc wifi ${if (enable) "enable" else "disable"}")
    }

    fun isMobileDataEnabled(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            val method = cm?.javaClass?.getMethod("getMobileDataEnabled")
            method?.invoke(cm) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setMobileDataEnabled(enable: Boolean): Boolean {
        return executeRootCommand("svc data ${if (enable) "enable" else "disable"}")
    }

    suspend fun isHotspotEnabled(context: Context): Boolean = withContext(Dispatchers.IO) {
        isHotspotEnabledSync(context)
    }

    /** 同步版本,供内部流程使用 */
    private fun isHotspotEnabledSync(context: Context): Boolean {
        // 优先用 WifiManager.getWifiApState 反射 —— 这是最可靠的方式
        // 返回值: 11=DISABLED, 12=ENABLING, 13=ENABLED, 14=DISABLING, 15=FAILED
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getMethod("getWifiApState")
            val state = method.invoke(wm) as? Int ?: 11
            if (state == 13) return true  // WIFI_AP_STATE_ENABLED
            if (state == 12) return false // ENABLING 也算未开启
        } catch (_: Exception) {}

        // 回退:用 settings global wifi_ap_state
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings get global wifi_ap_state"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()?.trim()
            process.waitFor()
            if (result == "13") return true
        } catch (_: Exception) {}

        // 再回退:检查 softap_on
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings get global softap_on"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()?.trim()
            process.waitFor()
            if (result == "1") return true
        } catch (_: Exception) {}

        return false
    }

    suspend fun setHotspotEnabled(context: Context, enable: Boolean): Result {
        return if (enable) enableHotspot(context) else disableHotspot(context)
    }

    /**
     * 开启热点。按安全性从高到低尝试多种方法。
     *
     * 重要说明:
     * - service call 方式在 MIUI 上可能触发系统崩溃,已移除
     * - 优先用 cmd wifi start-softap 带参数(root 执行),这是 MIUI 上验证过的有效方式
     * - 不主动关闭 WiFi,双网卡设备可同时工作
     */
    private suspend fun enableHotspot(context: Context): Result = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        // 如果已经是开的,直接返回成功
        if (isHotspotEnabledSync(context)) {
            logD("Hotspot already on")
            return@withContext Result(true)
        }

        val hasRoot = isRootAvailableSync()
        if (!hasRoot) {
            errors.add("未获取 root 权限,MIUI 上必须 root 才能开热点")
            return@withContext Result(false, errors.joinToString("; "))
        }

        // 获取热点配置(SSID、密码、加密方式)
        val hotspotConfig = getSavedHotspotConfig(context)
        logD("Hotspot config: ssid=${hotspotConfig.ssid}, encryption=${hotspotConfig.encryption}")

        // 不主动关闭 WiFi:双网卡设备 WiFi 和热点可同时工作,
        // 单网卡设备系统会自动处理互斥,无需我们手动关

        // 优先级 1:cmd wifi start-softap 带参数 (MIUI/Android 13+ 验证有效)
        // 命令格式: start-softap <ssid> <encryption> <passphrase>
        // encryption: open/wpa2/wpa3/wpa2_wpa3
        run {
            val ssid = hotspotConfig.ssid
            val encryption = hotspotConfig.encryption
            val passphrase = hotspotConfig.passphrase
            val cmd = if (encryption == "open" || passphrase.isEmpty()) {
                "cmd wifi start-softap \"$ssid\" open"
            } else {
                "cmd wifi start-softap \"$ssid\" $encryption \"$passphrase\""
            }
            logD("Trying: $cmd")
            val r = execRootDetailed(cmd)
            logD("cmd wifi start-softap: out=${r.stdout} err=${r.stderr}")
            if (r.success || r.stderr.isEmpty()) {
                Thread.sleep(3000)
                if (isHotspotEnabled(context)) {
                    logD("Enable hotspot (cmd wifi start-softap with params) success")
                    return@withContext Result(true)
                }
            }
            errors.add("cmd wifi start-softap(带参数): ${if (r.stderr.isNotEmpty()) r.stderr else "exit 0 但热点未开"}")
        }

        // 优先级 2:startTetheredHotspot (标准 API,appops 授权后可能可用)
        run {
            val packageName = context.packageName
            execRootDetailed("appops set $packageName android:tethering allow")
            execRootDetailed("appops set $packageName WRITE_SETTINGS allow")
            execRootDetailed("appops set $packageName WRITE_SECURE_SETTINGS allow")
            Thread.sleep(500)

            val r = startTetheredHotspotViaWifiManager(context)
            if (r.success) {
                Thread.sleep(3000)
                if (isHotspotEnabled(context)) {
                    logD("Enable hotspot (startTetheredHotspot after appops) success")
                    return@withContext Result(true)
                }
                errors.add("startTetheredHotspot(appops授权后): 调用了但热点未开")
            } else {
                errors.add("startTetheredHotspot(appops授权后): ${r.error}")
            }
        }

        // 先获取 WiFi 接口名(MIUI 可能不是 wlan0)
        val iface = detectWifiInterface()
        logD("Detected WiFi iface: $iface")

        // 优先级 3:cmd connectivity start-tethering
        run {
            val r = execRootDetailed("cmd connectivity start-tethering $iface")
            logD("cmd connectivity start-tethering: out=${r.stdout} err=${r.stderr}")
            if (r.success) {
                Thread.sleep(3000)
                if (isHotspotEnabled(context)) {
                    logD("Enable hotspot (cmd connectivity) success")
                    return@withContext Result(true)
                }
            }
            errors.add("cmd connectivity: ${if (r.stderr.isNotEmpty()) r.stderr else "exit 0 但热点未开"}")
        }

        // 优先级 4:旧版 setWifiApEnabled 反射(Android 10 及以下,MIUI 可能保留)
        run {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val method = wm.javaClass.getMethod("setWifiApEnabled",
                    android.net.wifi.WifiConfiguration::class.java, Boolean::class.java)
                method.invoke(wm, null, true)
                Thread.sleep(3000)
                if (isHotspotEnabled(context)) {
                    logD("Enable hotspot (setWifiApEnabled) success")
                    return@withContext Result(true)
                }
                errors.add("setWifiApEnabled: 调用了但热点未开")
            } catch (e: SecurityException) {
                errors.add("setWifiApEnabled: 缺少权限 ${e.message}")
            } catch (e: NoSuchMethodException) {
                errors.add("setWifiApEnabled: 方法不存在(Android 11+ 已移除)")
            } catch (e: Exception) {
                errors.add("setWifiApEnabled: ${e.message}")
            }
        }

        // 优先级 5:通过 settings global 间接控制
        run {
            execRootDetailed("settings put global tether_supported 1")
            execRootDetailed("settings put global softap_on 1")
            execRootDetailed("settings put global wifi_ap_state 12")
            execRootDetailed("settings put global wifi_ap_on 1")
            execRootDetailed("settings put global hotspot_on 1")
            Thread.sleep(3000)
            if (isHotspotEnabled(context)) {
                logD("Enable hotspot (settings global) success")
                return@withContext Result(true)
            }
            errors.add("settings global 修改无效")
        }

        // 优先级 6:content call 方式(MIUI SettingsProvider 可能支持)
        run {
            execRootDetailed(
                "content call --uri content://settings/global --method PUT --arg softap_on --extra value:s:1"
            )
            execRootDetailed(
                "content call --uri content://settings/global --method PUT --arg wifi_ap_state --extra value:s:12"
            )
            Thread.sleep(3000)
            if (isHotspotEnabled(context)) {
                logD("Enable hotspot (content call) success")
                return@withContext Result(true)
            }
            errors.add("content call 修改无效")
        }

        // 优先级 7:尝试 MIUI 的 WifiManager 扩展方法
        run {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val methods = wm.javaClass.methods.map { it.name }.toSet()
                logD("WifiManager methods with softap/tether/ap: ${methods.filter { it.contains("softap", true) || it.contains("tether", true) || it.contains("ap", true) }}")

                if ("startSoftAp" in methods) {
                    val method = wm.javaClass.getMethod("startSoftAp")
                    method.invoke(wm)
                    Thread.sleep(3000)
                    if (isHotspotEnabled(context)) {
                        logD("Enable hotspot (startSoftAp) success")
                        return@withContext Result(true)
                    }
                    errors.add("startSoftAp: 调用了但热点未开")
                } else {
                    errors.add("startSoftAp 方法不存在")
                }
            } catch (e: Exception) {
                errors.add("MIUI WifiManager 扩展: ${e.message}")
            }
        }

        // 优先级 8:发送 MIUI 热点广播
        run {
            val r = execRootDetailed(
                "am broadcast -a miui.intent.action.WIFI_AP_STATE_CHANGED --ei state 12"
            )
            logD("broadcast WIFI_AP_STATE_CHANGED: ${r.stdout} ${r.stderr}")
            Thread.sleep(2000)
            if (isHotspotEnabled(context)) {
                logD("Enable hotspot (broadcast) success")
                return@withContext Result(true)
            }
            errors.add("MIUI 广播无效")
        }

        logD("Enable hotspot all methods failed: $errors")
        val errorSummary = if (errors.isEmpty()) "未知原因" else errors.joinToString("; ")
        Result(false, errorSummary)
    }

    /** 热点配置信息 */
    private data class HotspotConfig(
        val ssid: String,
        val encryption: String,
        val passphrase: String
    )

    /**
     * 获取已保存的热点配置。
     * 优先从 WifiManager 反射获取 SoftApConfiguration,失败则用默认值。
     */
    private fun getSavedHotspotConfig(context: Context): HotspotConfig {
        val defaultSsid = "BTRemoteHotspot"
        val defaultPass = "12345678"
        val defaultEncryption = "wpa2"

        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // 尝试通过反射获取 SoftApConfiguration
            val method = try {
                wm.javaClass.getMethod("getSoftApConfiguration")
            } catch (_: Exception) {
                null
            }
            if (method != null) {
                val config = method.invoke(wm)
                if (config != null) {
                    val configClass = config.javaClass
                    val ssid = try {
                        configClass.getMethod("getSsid").invoke(config) as? String
                    } catch (_: Exception) { null }
                    val passphrase = try {
                        configClass.getMethod("getPassphrase").invoke(config) as? String
                    } catch (_: Exception) { null }
                    val securityType = try {
                        configClass.getMethod("getSecurityType").invoke(config) as? Int
                    } catch (_: Exception) { null }

                    val encryption = when (securityType) {
                        0 -> "open"
                        1 -> "wpa2"
                        2 -> "wpa3"
                        3 -> "wpa2_wpa3"
                        else -> "wpa2"
                    }

                    if (!ssid.isNullOrEmpty()) {
                        return HotspotConfig(ssid, encryption, passphrase ?: defaultPass)
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: 从 settings 读取热点配置(MIUI 可能存在这些 key)
        run {
            val ssid = execRootDetailed("settings get global wifi_ap_ssid").stdout.trim()
            val pass = execRootDetailed("settings get global wifi_ap_password").stdout.trim()
            if (ssid.isNotEmpty() && ssid != "null") {
                return HotspotConfig(ssid, defaultEncryption, if (pass.isNotEmpty() && pass != "null") pass else defaultPass)
            }
        }

        return HotspotConfig(defaultSsid, defaultEncryption, defaultPass)
    }

    private fun logD(msg: String) {
        Log.d(TAG, msg)
    }

    /** 检测 WiFi 接口名(MIUI 可能不是 wlan0) */
    private fun detectWifiInterface(): String {
        val candidates = listOf("wlan0", "wlan1", "wlan2", "p2p0", "ap0")
        for (iface in candidates) {
            val r = execRootDetailed("ip link show $iface")
            if (r.success && r.stdout.contains(iface)) return iface
        }
        // 尝试用 wpa_supplicant 接口
        val r = execRootDetailed("getprop wifi.interface")
        if (r.success && r.stdout.isNotEmpty()) return r.stdout.trim()
        return "wlan0"
    }

    private suspend fun disableHotspot(context: Context): Result = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        // 已经是关的,直接成功
        if (!isHotspotEnabled(context)) {
            return@withContext Result(true)
        }

        // 优先级 1:WifiManager.stopTetheredHotspot
        run {
            val r = stopTetheredHotspotViaWifiManager(context)
            if (r.success) {
                Thread.sleep(2000)
                if (!isHotspotEnabled(context)) {
                    logD("Disable hotspot (stopTetheredHotspot) success")
                    return@withContext Result(true)
                }
            }
            errors.add("stopTetheredHotspot: ${r.error}")
        }

        // 优先级 2:TetheringManager.stopTethering
        run {
            val r = stopTetheringViaTetheringManager(context)
            if (r.success) {
                Thread.sleep(2000)
                if (!isHotspotEnabled(context)) {
                    logD("Disable hotspot (TetheringManager) success")
                    return@withContext Result(true)
                }
            }
            errors.add("TetheringManager.stopTethering: ${r.error}")
        }

        // 优先级 3:Root cmd 命令(安全,不会导致系统崩溃)
        if (isRootAvailableSync()) {
            val iface = detectWifiInterface()
            run {
                val r = execRootDetailed("cmd wifi stop-softap")
                logD("cmd wifi stop-softap: ${r.stdout} ${r.stderr}")
                Thread.sleep(2000)
                if (!isHotspotEnabled(context)) {
                    logD("Disable hotspot (cmd wifi stop-softap) success")
                    return@withContext Result(true)
                }
            }
            run {
                val r = execRootDetailed("cmd connectivity stop-tethering $iface")
                logD("cmd connectivity stop-tethering: ${r.stdout} ${r.stderr}")
                Thread.sleep(2000)
                if (!isHotspotEnabled(context)) {
                    logD("Disable hotspot (cmd connectivity stop) success")
                    return@withContext Result(true)
                }
            }
            run {
                execRootDetailed("settings put global wifi_ap_state 11")
                execRootDetailed("settings put global softap_on 0")
                Thread.sleep(2000)
                if (!isHotspotEnabled(context)) {
                    logD("Disable hotspot (settings global) success")
                    return@withContext Result(true)
                }
            }
        }

        val errorSummary = if (errors.isEmpty()) "未知原因" else errors.joinToString("; ")
        Result(false, errorSummary)
    }

    /**
     * 通过 WifiManager.startTetheredHotspot 反射调用,Android 11+ 推荐。
     * 参数应为 SoftApConfiguration(null 表示使用默认配置)。
     */
    private fun startTetheredHotspotViaWifiManager(context: Context): Result {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // Android 11+ 的方法签名:startTetheredHotspot(SoftApConfiguration)
            // 先尝试带参数版本
            val method = try {
                // 尝试加载 SoftApConfiguration 类
                val softApConfigClass = Class.forName("android.net.wifi.SoftApConfiguration")
                wm.javaClass.getMethod("startTetheredHotspot", softApConfigClass)
            } catch (_: Exception) {
                // 回退到无参版本(旧 API)
                wm.javaClass.getMethod("startTetheredHotspot")
            }

            val result = if (method.parameterTypes.isEmpty()) {
                method.invoke(wm)
            } else {
                method.invoke(wm, null)
            }
            if (result as? Boolean == true) Result(true) else Result(false, "startTetheredHotspot 返回 false")
        } catch (e: SecurityException) {
            Log.w(TAG, "startTetheredHotspot SecurityException: ${e.message}")
            Result(false, "缺少权限: ${e.message}")
        } catch (e: NoSuchMethodException) {
            Result(false, "方法不存在(Android 版本过低?)")
        } catch (e: Exception) {
            Log.w(TAG, "startTetheredHotspot failed: ${e.message}")
            Result(false, e.message ?: "反射调用失败")
        }
    }

    private fun stopTetheredHotspotViaWifiManager(context: Context): Result {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getMethod("stopTetheredHotspot")
            method.invoke(wm)
            Result(true)
        } catch (e: SecurityException) {
            Result(false, "缺少权限: ${e.message}")
        } catch (e: Exception) {
            Result(false, e.message ?: "反射调用失败")
        }
    }

    /**
     * 通过 TetheringManager.startTethering 调用。
     * Android 13+ 优先用 Context.TETHERING_SERVICE 获取 TetheringManager 实例。
     */
    private fun startTetheringViaTetheringManager(context: Context): Result {
        return try {
            // 用字符串常量 "tethering" 获取 TetheringManager (API 29+)
            val tetheringManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.applicationContext.getSystemService("tethering")
            } else {
                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                val getTm = cm?.javaClass?.getMethod("getTetheringManager")
                getTm?.invoke(cm)
            } ?: return Result(false, "无法获取 TetheringManager")

            val latch = CountDownLatch(1)
            var started = false
            var failReason = ""

            val executor = Executor { cmd -> Thread(cmd).start() }
            val callbackClass = try {
                Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            } catch (_: Exception) {
                return Result(false, "StartTetheringCallback 类不存在")
            }

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
                java.lang.reflect.InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onTetheringStarted" -> started = true
                        "onTetheringFailed" -> failReason = "onTetheringFailed 被回调(权限不足或设备不支持)"
                    }
                    latch.countDown()
                    null
                }
            )

            val types = arrayOf(Int::class.java, Executor::class.java, callbackClass)
            val startMethod = tetheringManager.javaClass.getMethod("startTethering", *types)
            startMethod.invoke(tetheringManager, TETHERING_WIFI, executor, proxy)

            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (started) Result(true) else Result(false, failReason.ifEmpty { "5 秒内无回调" })
        } catch (e: SecurityException) {
            Result(false, "缺少权限: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "startTetheringViaTetheringManager failed", e)
            Result(false, e.message ?: "反射调用失败")
        }
    }

    private fun stopTetheringViaTetheringManager(context: Context): Result {
        return try {
            val tetheringManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.applicationContext.getSystemService("tethering")
            } else {
                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                val getTm = cm?.javaClass?.getMethod("getTetheringManager")
                getTm?.invoke(cm)
            } ?: return Result(false, "无法获取 TetheringManager")

            val stopMethod = try {
                tetheringManager.javaClass.getMethod("stopTethering", Int::class.java)
            } catch (_: Exception) {
                tetheringManager.javaClass.getMethod("stopTethering")
            }

            if (stopMethod.parameterTypes.isEmpty()) {
                stopMethod.invoke(tetheringManager)
            } else {
                stopMethod.invoke(tetheringManager, TETHERING_WIFI)
            }
            Result(true)
        } catch (e: Exception) {
            Result(false, e.message ?: "反射调用失败")
        }
    }

    /**
     * 同步执行 root 命令,返回详细结果。
     * 尝试多种 su 路径,捕获 stdout+stderr 便于诊断。
     */
    private data class RootResult(val success: Boolean, val stdout: String, val stderr: String)

    private fun execRootDetailed(command: String): RootResult {
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "su")
        val lastError = StringBuilder()
        for (suPath in suPaths) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", command))
                val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
                val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    return RootResult(true, stdout, stderr)
                }
                lastError.append("[$suPath] exit=$exitCode stderr=$stderr ")
            } catch (e: Exception) {
                lastError.append("[$suPath] ${e.message} ")
            }
        }
        Log.w(TAG, "Root command failed: $command - $lastError")
        return RootResult(false, "", lastError.toString())
    }

    /** 同步检查 root 是否可用(供热点流程内部使用) */
    private fun isRootAvailableSync(): Boolean {
        val r = execRootDetailed("id")
        return r.success && r.stdout.contains("uid=0")
    }

    /** 同步执行 root 命令,返回是否成功(exit code == 0) */
    private fun execRootSync(command: String): Boolean {
        return execRootDetailed(command).success
    }
}
