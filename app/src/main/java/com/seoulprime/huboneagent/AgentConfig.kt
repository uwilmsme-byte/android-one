package com.seoulprime.huboneagent

import android.content.Context

data class AgentConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val screenId: String = DEFAULT_SCREEN_ID,
    val autoLaunch: Boolean = true,
    val keepScreenOn: Boolean = true
) {
    fun save(context: Context) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        p.edit()
            .putString("baseUrl", baseUrl)
            .putString("screenId", screenId)
            .putBoolean("autoLaunch", autoLaunch)
            .putBoolean("keepScreenOn", keepScreenOn)
            .apply()
    }

    companion object {
        private const val FILE = "hubone_agent_config"

        fun load(context: Context): AgentConfig {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return AgentConfig(
                baseUrl = p.getString("baseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
                screenId = p.getString("screenId", DEFAULT_SCREEN_ID) ?: DEFAULT_SCREEN_ID,
                autoLaunch = p.getBoolean("autoLaunch", true),
                keepScreenOn = p.getBoolean("keepScreenOn", true)
            )
        }

        const val DEFAULT_BASE_URL = "http://192.168.0.100:8000"
        const val DEFAULT_SCREEN_ID = "kiosk1"
    }
}
