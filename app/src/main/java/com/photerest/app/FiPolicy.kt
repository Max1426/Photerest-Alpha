package com.photerest.app

import android.content.Context
import java.io.IOException

/** Pure policy shared by settings, photo entry and request boundary. Never grants server authorization. */
internal fun fiOnlineReason(enabled: Boolean, google: Boolean, authorized: Boolean): String? = when {
    !enabled -> "φ 智能尚未开启"
    !google -> "Google 扩展尚未开启"
    !authorized -> "联网搜图仍在测试，仅向已授权设备开放。"
    else -> null
}

internal class FiUnavailable(message: String) : IOException(message)
internal object FiAccess {
    @Volatile private var app: Context? = null
    fun initialize(context: Context) { app = context.applicationContext }
    fun requireOnline() {
        val prefs = app?.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        fiOnlineReason(prefs?.getBoolean("fi_enabled", false) == true,
            prefs?.getBoolean("fi_google_enabled", true) != false, SearchAuthorization.ready)?.let { throw FiUnavailable(it) }
    }
}
