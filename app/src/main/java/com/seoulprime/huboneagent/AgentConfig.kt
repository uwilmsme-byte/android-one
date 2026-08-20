package com.seoulprime.huboneagent

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.util.Properties

data class AgentConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val screenId: String = DEFAULT_SCREEN_ID,
    val autoLaunch: Boolean = true,
    val keepScreenOn: Boolean = true,
    // 덴트웹 네이티브 앱의 패키지명 — 실제 사용 중인 앱은 "고객용"(kr.co.DentWeb.DentWebCustomer)
    // 이다("Mobile" 쪽이 아님, 실기기에서 확인됨). 기본값으로 하드코딩해두되, 실기기마다
    // 다를 수 있으니 관리자 설정에서 여전히 바꿀 수 있게 둔다.
    val dentwebPackage: String = DEFAULT_DENTWEB_PACKAGE,
    // 관리자가 설정 화면에서 baseUrl을 명시적으로 저장했는지 여부.
    val manualBaseUrl: Boolean = false,
    // manualBaseUrl이 실제로 한 번이라도 정상 연결된 적이 있는지 — 두 가지 요구사항을
    // 동시에 만족시키기 위한 플래그다:
    //  1. "IP를 지정하고 저장하니 처음에 반짝하고 다시 예전 주소가 나옴" — 이미 정상 연결
    //     검증된 수동 주소는 일시적 접속 실패로도 자동검색이 조용히 덮어쓰면 안 된다.
    //  2. "가장 처음에 ip가 맞지 않아 접속이 안된다면 자동 스캔을 시도하는게 좋겠음" — 저장한
    //     주소가 처음부터 한 번도 연결된 적 없다면(오타 등) 자동검색이 도와줘야 한다.
    // 그래서 manualBaseUrl && verified일 때만 자동검색의 덮어쓰기를 막는다 — 검증 전에는
    // (아직 한 번도 성공 못한 수동 주소) 자동검색이 여전히 개입할 수 있다.
    val manualBaseUrlVerified: Boolean = false
) {
    fun save(context: Context) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        p.edit()
            .putString("baseUrl", baseUrl)
            .putString("screenId", screenId)
            .putBoolean("autoLaunch", autoLaunch)
            .putBoolean("keepScreenOn", keepScreenOn)
            .putString("dentwebPackage", dentwebPackage)
            .putBoolean("manualBaseUrl", manualBaseUrl)
            .putBoolean("manualBaseUrlVerified", manualBaseUrlVerified)
            .apply()
        saveDurableBackup()
    }

    // SharedPreferences는 앱 전용 저장소라 재설치(특히 "데이터 지우고 설치" 방식)하면
    // 통째로 날아간다 — 실제 겪은 문제: "재빌드/재설치할 때마다 태블릿 이름(screen_id)을
    // 매번 다시 설정해야 함". 앱 삭제와 무관하게 남아있는 공용 외부 저장소에도 같은 값을
    // 텍스트 파일로 백업해두고, load()에서 SharedPreferences가 비어있으면(새로 설치된
    // 직후) 이 파일에서 복구한다. "폴더 접근" 권한(MANAGE_EXTERNAL_STORAGE)이 없으면
    // 조용히 건너뛴다 — 이 백업은 있으면 좋은 보조 수단이지 필수 기능이 아니다.
    private fun saveDurableBackup() {
        try {
            val dir = File(Environment.getExternalStorageDirectory(), "HubOneAgent")
            if (!dir.exists() && !dir.mkdirs()) return
            val props = Properties().apply {
                setProperty("baseUrl", baseUrl)
                setProperty("screenId", screenId)
                setProperty("dentwebPackage", dentwebPackage)
                setProperty("manualBaseUrl", manualBaseUrl.toString())
                setProperty("manualBaseUrlVerified", manualBaseUrlVerified.toString())
            }
            File(dir, BACKUP_FILE_NAME).outputStream().use {
                props.store(it, "HUBONE Agent 설정 백업 — 앱 재설치와 무관하게 유지됨")
            }
        } catch (e: Exception) {
            Log.w("AgentConfig", "durable backup save failed: ${e.message}")
        }
    }

    companion object {
        private const val FILE = "hubone_agent_config"
        private const val BACKUP_FILE_NAME = "agent_config.properties"

        fun load(context: Context): AgentConfig {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            // screenId 키 자체가 없으면(=SharedPreferences가 비어있는 새 설치) 외부
            // 백업 파일에서 복구를 먼저 시도한다. 파일이 없거나 못 읽으면 그냥 기본값으로
            // 진행 — 처음 설치라면 원래도 기본값이 맞다.
            if (!p.contains("screenId")) {
                loadDurableBackup()?.let { restored ->
                    restored.save(context)
                    return restored
                }
            }
            return AgentConfig(
                baseUrl = p.getString("baseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
                screenId = p.getString("screenId", DEFAULT_SCREEN_ID) ?: DEFAULT_SCREEN_ID,
                autoLaunch = p.getBoolean("autoLaunch", true),
                keepScreenOn = p.getBoolean("keepScreenOn", true),
                dentwebPackage = p.getString("dentwebPackage", DEFAULT_DENTWEB_PACKAGE) ?: DEFAULT_DENTWEB_PACKAGE,
                manualBaseUrl = p.getBoolean("manualBaseUrl", false),
                manualBaseUrlVerified = p.getBoolean("manualBaseUrlVerified", false)
            )
        }

        private fun loadDurableBackup(): AgentConfig? {
            return try {
                val file = File(File(Environment.getExternalStorageDirectory(), "HubOneAgent"), BACKUP_FILE_NAME)
                if (!file.exists()) return null
                val props = Properties().apply { file.inputStream().use { load(it) } }
                AgentConfig(
                    baseUrl = props.getProperty("baseUrl", DEFAULT_BASE_URL),
                    screenId = props.getProperty("screenId", DEFAULT_SCREEN_ID),
                    dentwebPackage = props.getProperty("dentwebPackage", DEFAULT_DENTWEB_PACKAGE),
                    manualBaseUrl = props.getProperty("manualBaseUrl", "false").toBoolean(),
                    manualBaseUrlVerified = props.getProperty("manualBaseUrlVerified", "false").toBoolean()
                )
            } catch (e: Exception) {
                Log.w("AgentConfig", "durable backup load failed: ${e.message}")
                null
            }
        }

        const val DEFAULT_BASE_URL = "http://192.168.0.13:8001"
        const val DEFAULT_SCREEN_ID = "kiosk1"
        const val DEFAULT_DENTWEB_PACKAGE = "kr.co.DentWeb.DentWebCustomer"
    }
}
