package cn.org.sdcd.suiyin.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 系统级权限工具:WRITE_SETTINGS / 电池优化 等
 *
 * WRITE_SETTINGS 不能用普通的 requestPermissions 申请,必须跳到系统设置页让用户手动开启。
 */
object PermissionHelper {

    /** 应用是否拥有修改系统设置权限 */
    fun canWriteSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    /**
     * 跳转到"修改系统设置"权限页。Android 6+ 必须用户手动授予。
     * 返回 true 表示成功发起跳转,false 表示无法跳转(应回退到普通设置页)。
     */
    fun requestWriteSettings(context: Context): Boolean {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
