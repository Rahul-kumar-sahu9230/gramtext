package com.gramtext.app.data

import android.content.Context
import com.gramtext.app.AppLanguage
import com.gramtext.app.BuildConfig
import java.util.UUID

/** Small persistent settings. The client id is random and anonymous (counts unique devices). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("gramtext", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", null) ?: BuildConfig.API_BASE_URL
        set(value) = sp.edit().putString("server_url", value.trim().trimEnd('/')).apply()

    var autoRead: Boolean
        get() = sp.getBoolean("auto_read", true)
        set(value) = sp.edit().putBoolean("auto_read", value).apply()

    var slowSpeech: Boolean
        get() = sp.getBoolean("slow_speech", false)
        set(value) = sp.edit().putBoolean("slow_speech", value).apply()

    var liveMode: Boolean
        get() = sp.getBoolean("live_mode", true)
        set(value) = sp.edit().putBoolean("live_mode", value).apply()

    var showEngine: Boolean
        get() = sp.getBoolean("show_engine", false)
        set(value) = sp.edit().putBoolean("show_engine", value).apply()

    var language: AppLanguage
        get() = runCatching { AppLanguage.valueOf(sp.getString("language", null)!!) }
            .getOrDefault(AppLanguage.HINDI)
        set(value) = sp.edit().putString("language", value.name).apply()

    val clientId: String
        get() = sp.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("client_id", it).apply()
        }
}
