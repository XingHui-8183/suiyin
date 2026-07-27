package com.tool.btremote.lsposed

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed Hook:绕过 TETHER_PRIVILEGED / WRITE_SETTINGS 权限检查,
 * 让本应用在不签名系统签名 / 非 Device Owner 的情况下也能控制热点开关。
 *
 * 仅对本应用进程生效,不影响其它应用。
 *
 * 启用条件:设备已装 LSPosed/EdXposed,并在 LSPosed 管理器中启用本模块、勾选作用域包含本应用包名。
 */
class HotspotHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "BTRemote-HotspotHook"
        const val TARGET_PACKAGE = "com.tool.btremote"

        private const val TETHER_PRIVILEGED = "android.permission.TETHER_PRIVILEGED"
        private const val WRITE_SETTINGS = "android.permission.WRITE_SETTINGS"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) {
            return
        }

        Log.d(TAG, "Loaded package: ${lpparam.packageName}")

        try {
            hookContextCheckPermission(lpparam)
            Log.d(TAG, "Hook Context.checkPermission success")
        } catch (e: Exception) {
            Log.e(TAG, "Hook Context.checkPermission failed", e)
        }

        try {
            hookContextEnforceCallingPermission(lpparam)
            Log.d(TAG, "Hook Context.enforceCallingPermission success")
        } catch (e: Exception) {
            Log.e(TAG, "Hook Context.enforceCallingPermission failed", e)
        }

        try {
            hookTetheringManager(lpparam)
            Log.d(TAG, "Hook TetheringManager success")
        } catch (e: Exception) {
            Log.e(TAG, "Hook TetheringManager failed", e)
        }

        try {
            hookWifiManager(lpparam)
            Log.d(TAG, "Hook WifiManager success")
        } catch (e: Exception) {
            Log.e(TAG, "Hook WifiManager failed", e)
        }
    }

    /**
     * Hook Context.checkPermission / checkCallingPermission / checkSelfPermission,
     * 当本应用检查 TETHER_PRIVILEGED / WRITE_SETTINGS 时直接返回 PERMISSION_GRANTED。
     */
    private fun hookContextCheckPermission(lpparam: XC_LoadPackage.LoadPackageParam) {
        val contextClass = XposedHelpers.findClass("android.content.Context", lpparam.classLoader)

        val grant = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val perm = param.args.getOrNull(0) as? String ?: return
                if (perm == TETHER_PRIVILEGED || perm == WRITE_SETTINGS) {
                    param.result = android.content.pm.PackageManager.PERMISSION_GRANTED
                }
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(contextClass, "checkPermission",
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, grant)
        }
        runCatching {
            XposedHelpers.findAndHookMethod(contextClass, "checkCallingPermission",
                String::class.java, grant)
        }
        runCatching {
            XposedHelpers.findAndHookMethod(contextClass, "checkSelfPermission",
                String::class.java, grant)
        }
    }

    /**
     * Hook Context.enforceCallingPermission / enforcePermission,
     * 当本应用被检查 TETHER_PRIVILEGED 时直接放行(不抛 SecurityException)。
     */
    private fun hookContextEnforceCallingPermission(lpparam: XC_LoadPackage.LoadPackageParam) {
        val contextClass = XposedHelpers.findClass("android.content.Context", lpparam.classLoader)

        val pass = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val perm = param.args.getOrNull(0) as? String ?: return
                if (perm == TETHER_PRIVILEGED || perm == WRITE_SETTINGS) {
                    param.result = null // 阻止原方法抛异常
                }
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(contextClass, "enforceCallingPermission",
                String::class.java, String::class.java, pass)
        }
        runCatching {
            XposedHelpers.findAndHookMethod(contextClass, "enforcePermission",
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, pass)
        }
    }

    private fun hookTetheringManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val tetheringManagerClass = runCatching {
            XposedHelpers.findClass("android.net.TetheringManager", lpparam.classLoader)
        }.getOrNull() ?: return

        // 仅作日志/调用记录,实际权限绕过在 Context hook 已完成
        runCatching {
            XposedHelpers.findAndHookMethod(
                tetheringManagerClass,
                "startTethering",
                Int::class.java,
                java.util.concurrent.Executor::class.java,
                Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Log.d(TAG, "startTethering called: type=${param.args[0]}")
                    }
                }
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                tetheringManagerClass,
                "stopTethering",
                Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Log.d(TAG, "stopTethering called: type=${param.args[0]}")
                    }
                }
            )
        }
    }

    private fun hookWifiManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val wifiManagerClass = runCatching {
            XposedHelpers.findClass("android.net.wifi.WifiManager", lpparam.classLoader)
        }.getOrNull() ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                wifiManagerClass,
                "isWifiApEnabled",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Log.d(TAG, "isWifiApEnabled: ${param.result}")
                    }
                }
            )
        }

        // 旧 API:setWifiApEnabled 在 Android 7+ 已 deprecated 但部分 ROM 仍可用
        runCatching {
            XposedHelpers.findAndHookMethod(
                wifiManagerClass,
                "setWifiApEnabled",
                Any::class.java,
                Boolean::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Log.d(TAG, "setWifiApEnabled: ${param.args[1]}")
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Log.d(TAG, "setWifiApEnabled result: ${param.result}")
                    }
                }
            )
        }

        // Android 11+:startTetheredHotspot / stopTetheredHotspot,需 TETHER_PRIVILEGED
        runCatching {
            XposedHelpers.findAndHookMethod(
                wifiManagerClass,
                "startTetheredHotspot",
                Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Log.d(TAG, "startTetheredHotspot called")
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Log.d(TAG, "startTetheredHotspot result: ${param.result}")
                    }
                }
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                wifiManagerClass,
                "stopTetheredHotspot",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Log.d(TAG, "stopTetheredHotspot called")
                    }
                }
            )
        }
    }
}
