package com.seoulprime.huboneagent

import android.content.Context

data class AgentConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val screenId: String = DEFAULT_SCREEN_ID,
    val autoLaunch: Boolean = true,
    val keepScreenOn: Boolean = true,
    // 덴트웹 네이티브 앱의 패키지명 — 실제 사용 중인 앱은 "고객용"(kr.co.DentWeb.DentWebCustomer)
    // 이다("Mobile" 쪽이 아님, 실기기에서 확인됨). 기본값으로 하드코딩해두되, 실기기마다
    // 다를 수 있으니 관리자 설정에서 여전히 바꿀 수 있게 둔다.
    val dentwebPackage: String = DEFAULT_DENTWEB_PACKAGE
) {
    fun save(context: Context) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        p.edit()
            .putString("baseUrl", baseUrl)
            .putString("screenId", screenId)
            .putBoolean("autoLaunch", autoLaunch)
            .putBoolean("keepScreenOn", keepScreenOn)
            .putString("dentwebPackage", dentwebPackage)
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
                keepScreenOn = p.getBoolean("keepScreenOn", true),
                dentwebPackage = p.getString("dentwebPackage", DEFAULT_DENTWEB_PACKAGE) ?: DEFAULT_DENTWEB_PACKAGE
            )
        }

        const val DEFAULT_BASE_URL = "http://192.168.0.100:8001"
        const val DEFAULT_SCREEN_ID = "kiosk1"
        const val DEFAULT_DENTWEB_PACKAGE = "kr.co.DentWeb.DentWebCustomer"
    }
}
