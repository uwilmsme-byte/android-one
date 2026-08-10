package com.seoulprime.huboneagent

import android.app.Activity
import android.app.AppOpsManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : Activity() {
    private lateinit var baseUrl: EditText
    private lateinit var screenId: EditText
    private lateinit var dentwebPackage: EditText
    private lateinit var cameraPermissionButton: Button
    private lateinit var microphonePermissionButton: Button
    private lateinit var usagePermissionButton: Button
    private lateinit var overlayPermissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val config = AgentConfig.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }
        root.addView(TextView(this).apply { text = "HUBONE Agent 관리자 설정"; textSize = 22f })
        // 실제 겪은 문제: 최신 빌드를 설치했다고 생각했는데 옛날 APK가 그대로 깔려있는 걸
        // 구분할 방법이 없었다 — 버전을 화면에 바로 보여줘서 adb 없이도 확인 가능하게 한다.
        root.addView(TextView(this).apply {
            val versionLabel = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }
            text = "버전: $versionLabel"
            textSize = 12f
            setPadding(0, 2, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "\${HUBONE_BASE_URL}/pt?screen_id=\${SCREEN_ID} · 기본 포트 8001 자동 검색 지원"
            setPadding(0, 8, 0, 12)
        })
        baseUrl = addField(root, "HUBONE_BASE_URL", config.baseUrl)
        screenId = addField(root, "SCREEN_ID", config.screenId)
        root.addView(TextView(this).apply {
            text = "덴트웹 앱 패키지명 — 기본값은 고객용 앱(kr.co.DentWeb.DentWebCustomer). 실기기 사정에 따라 다르면 adb shell pm list packages 등으로 확인 후 바꿔서 입력. 비워두면 \"덴트웹으로 전환\" 명령을 무시합니다."
            setPadding(0, 12, 0, 4)
            textSize = 13f
        })
        dentwebPackage = addField(root, "DENTWEB_PACKAGE (예: com.example.dentweb)", config.dentwebPackage)

        // 사용정보 접근(PACKAGE_USAGE_STATS)은 일반 권한창으로 못 받는 특수 권한이라
        // 시스템 설정 화면으로 직접 보내야 한다 — 허용하면 보드 탭에 덴트웹 앱이 실제
        // 전면인지 정확히 표시되고(추정치가 아니라), 허용 안 해도 앱은 정상 동작한다
        // (마지막으로 내린 명령 기준 추정치로 자동 폴백).
        val permissionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        fun permissionButton(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 13f
                minHeight = 100
                setOnClickListener { onClick() }
            }
        }
        cameraPermissionButton = permissionButton("카메라\n허용") { requestRuntimePermission(Manifest.permission.CAMERA) }
        microphonePermissionButton = permissionButton("마이크\n허용") { requestRuntimePermission(Manifest.permission.RECORD_AUDIO) }
        usagePermissionButton = permissionButton("전면 앱 확인\n허용") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this@SettingsActivity, "목록에서 HUBONE Agent의 사용 기록 접근을 허용하세요.", Toast.LENGTH_LONG).show()
        }
        overlayPermissionButton = permissionButton("화면 위 표시\n허용") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this@SettingsActivity, "목록에서 HUBONE Agent를 켜주세요.", Toast.LENGTH_LONG).show()
        }
        listOf(cameraPermissionButton, microphonePermissionButton, usagePermissionButton, overlayPermissionButton).forEach { button ->
            permissionRow.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        }
        root.addView(permissionRow)
        root.addView(TextView(this).apply {
            text = "허용하면 허브원 보드 탭에서 덴트웹 환자용 앱이 실제 전면인지 정확히 표시합니다. 허용 안 해도 동작은 하되 추정치로만 표시됩니다."
            textSize = 12f
            setPadding(0, 4, 0, 4)
        })

        // 안드로이드 10+ "백그라운드 활동 시작 제한" 때문에 화면전환 명령이 조용히 씹힐 수
        // 있다(실제 겪은 문제: 상태는 성공으로 찍히는데 태블릿 화면은 안 바뀜). "다른 앱
        // 위에 표시" 허용은 공장초기화·기기소유자 지정 없이 설정 토글 하나로 끝나는 가벼운
        // 대응책 — 허용하면 앱이 안 보이는 1x1 오버레이 창을 계속 띄워둬서 "보이는 창이
        // 있는 앱" 예외 조건을 만족시킨다.
        root.addView(TextView(this).apply {
            text = "허용하면 화면전환 명령(접수/예약/덴트웹 전환)이 백그라운드 상태에서도 더 안정적으로 적용됩니다. 앱을 완전히 재시작해야 반영됩니다."
            textSize = 12f
            setPadding(0, 4, 0, 8)
        })

        // 태블릿 터치 조작 기준 — 버튼이 작아서 누르기 힘들다는 실제 지적 사항. 폭을 채우는
        // 2열 그리드로 바꾸고, 최소 높이·글자크기·여백을 태블릿 터치에 맞게 키웠다.
        val buttonsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        fun buttonRow() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        fun addButton(row: LinearLayout, label: String, onClick: () -> Unit) {
            row.addView(Button(this).apply {
                text = label
                textSize = 17f
                minHeight = 130
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (row.childCount > 0) 12 else 0
            })
        }
        val row1 = buttonRow()
        addButton(row1, "연결 테스트") { testConnection() }
        addButton(row1, "자동 검색") { discoverServer() }
        buttonsGrid.addView(row1)
        val row2 = buttonRow()
        addButton(row2, "현재 URL 열기") { save(); openMain() }
        addButton(row2, "앱 재시작") { save(); openMain() }
        buttonsGrid.addView(row2)
        val row3 = buttonRow()
        addButton(row3, "기본값 복원") { restoreDefaults() }
        addButton(row3, "저장") { save(); finish() }
        buttonsGrid.addView(row3)
        root.addView(buttonsGrid)
        setContentView(root)
        refreshPermissionButtons()
    }

    override fun onResume() {
        super.onResume()
        if (::cameraPermissionButton.isInitialized) refreshPermissionButtons()
    }

    private fun requestRuntimePermission(permission: String) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "이미 허용된 권한입니다.", Toast.LENGTH_SHORT).show()
        } else {
            requestPermissions(arrayOf(permission), RUNTIME_PERMISSION_REQUEST)
        }
    }

    private fun refreshPermissionButtons() {
        cameraPermissionButton.text = runtimePermissionLabel("카메라", Manifest.permission.CAMERA)
        microphonePermissionButton.text = runtimePermissionLabel("마이크", Manifest.permission.RECORD_AUDIO)
        usagePermissionButton.text = if (hasUsageAccess()) "전면 앱 확인\n✓ 허용됨" else "전면 앱 확인\n허용"
        overlayPermissionButton.text = if (Settings.canDrawOverlays(this)) "화면 위 표시\n✓ 허용됨" else "화면 위 표시\n허용"
    }

    private fun runtimePermissionLabel(name: String, permission: String): String {
        return if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "$name\n✓ 허용됨" else "$name\n허용"
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        return appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RUNTIME_PERMISSION_REQUEST) {
            refreshPermissionButtons()
            Toast.makeText(this, if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) "권한이 허용되었습니다." else "권한이 허용되지 않았습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addField(root: LinearLayout, hint: String, value: String): EditText {
        return EditText(this).also {
            it.hint = hint
            it.setText(value)
            it.isSingleLine = true
            it.textSize = 17f
            it.minHeight = 100
            it.setPadding(20, 20, 20, 20)
            root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8; bottomMargin = 8 })
        }
    }

    private fun currentUrl(): String {
        val base = baseUrl.text.toString().trim().trimEnd('/')
        val screen = screenId.text.toString().trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID }
        return "$base/pt?screen_id=${Uri.encode(screen)}"
    }

    private fun save() {
        // 입력된 baseUrl이 기본값(DEFAULT_BASE_URL)과 다르면 "관리자가 직접 지정한 주소"로
        // 보고 잠근다 — MainActivity의 자동검색이 연결 실패 시에도 이 값을 함부로 덮어쓰지
        // 못하게 한다(실제 겪은 문제: 저장해도 곧 예전 주소로 조용히 되돌아가던 버그). 기본값
        // 그대로 쓰는 경우엔 잠그지 않아 자동검색이 계속 편의를 제공할 수 있게 둔다.
        //
        // 다만 방금 저장한 값이 바뀐 것이면(직전 저장값과 다르면) 아직 한 번도 연결 검증이
        // 안 된 새 주소이므로 manualBaseUrlVerified를 false로 되돌린다 — 이 주소가 처음부터
        // 틀렸을 경우(오타 등) 자동검색이 여전히 도와줄 수 있게 하기 위함("가장 처음에 ip가
        // 맞지 않아 접속이 안된다면 자동 스캔을 시도하는게 좋겠음"). MainActivity가 이 주소로
        // 정상 접속에 성공하는 순간 verified=true로 바뀌면서 잠긴다.
        val previous = AgentConfig.load(this)
        val enteredBaseUrl = baseUrl.text.toString().trim()
        val stillSameManualUrl = enteredBaseUrl == previous.baseUrl
        AgentConfig(
            baseUrl = enteredBaseUrl,
            screenId = screenId.text.toString().trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID },
            dentwebPackage = dentwebPackage.text.toString().trim(),
            manualBaseUrl = enteredBaseUrl.isNotBlank() && enteredBaseUrl != AgentConfig.DEFAULT_BASE_URL,
            manualBaseUrlVerified = stillSameManualUrl && previous.manualBaseUrlVerified
        ).save(this)
        Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun restoreDefaults() {
        baseUrl.setText(AgentConfig.DEFAULT_BASE_URL)
        screenId.setText(AgentConfig.DEFAULT_SCREEN_ID)
        dentwebPackage.setText(AgentConfig.DEFAULT_DENTWEB_PACKAGE)
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun testConnection() {
        val target = currentUrl()
        Thread {
            val result = try {
                val connection = URL(target).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                val code = connection.responseCode
                connection.disconnect()
                "연결 응답: HTTP $code"
            } catch (_: Exception) {
                "서버에 연결할 수 없습니다"
            }
            runOnUiThread { Toast.makeText(this, result, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun discoverServer() {
        val seed = baseUrl.text.toString().trim()
        Thread {
            val found = ServerDiscovery.discover(seed)
            runOnUiThread {
                if (found.isNullOrBlank()) {
                    Toast.makeText(this, "같은 네트워크에서 HUBONE 서버를 찾지 못했습니다.", Toast.LENGTH_LONG).show()
                } else {
                    baseUrl.setText(found)
                    Toast.makeText(this, "HUBONE 서버를 찾았습니다.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        private const val RUNTIME_PERMISSION_REQUEST = 71
    }
}
