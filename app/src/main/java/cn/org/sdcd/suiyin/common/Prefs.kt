package cn.org.sdcd.suiyin.common

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "bt_remote_prefs"
    private lateinit var sp: SharedPreferences

    const val KEY_ROLE = "role"
    const val KEY_TARGET_DEVICE = "target_device"
    const val KEY_AUTO_START = "auto_start"
    const val KEY_KEEP_ALIVE = "keep_alive"
    const val KEY_SMS_SYNC = "sms_sync"
    const val KEY_MEDIA_SYNC = "media_sync"

    const val ROLE_HOST = "host"
    const val ROLE_SLAVE = "slave"

    fun init(context: Context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    fun getString(key: String, default: String = ""): String = sp.getString(key, default) ?: default
    fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()

    fun getBoolean(key: String, default: Boolean = true): Boolean = sp.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()

    fun getRole(): String = getString(KEY_ROLE, "")
    fun setRole(role: String) = putString(KEY_ROLE, role)
    fun hasRole(): Boolean = getRole().isNotEmpty()
}
