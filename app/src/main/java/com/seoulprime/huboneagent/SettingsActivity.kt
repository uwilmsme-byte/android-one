package com.seoulprime.huboneagent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val config = AgentConfig.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
        }
        root.addView(TextView(this).apply { text = "HUBONE Agent 관리자 설정"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "\${HUBONE_BASE_URL}/pt?screen_id=\${SCREEN_ID}"
            setPadding(0, 8, 0, 12)
        })
        baseUrl = addField(root, "HUBONE_BASE_URL", config.baseUrl)
        screenId = addField(root, "SCREEN_ID", config.screenId)

        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(Button(this).apply { text = "연결 테스트"; setOnClickListener { testConnection() } })
        buttons.addView(Button(this).apply { text = "현재 URL 열기"; setOnClickListener { save(); openMain() } })
        buttons.addView(Button(this).apply { text = "앱 재시작"; setOnClickListener { save(); openMain() } })
        buttons.addView(Button(this).apply { text = "기본값 복원"; setOnClickListener { restoreDefaults() } })
        buttons.addView(Button(this).apply { text = "저장"; setOnClickListener { save(); finish() } })
        root.addView(buttons)
        setContentView(root)
    }

    private fun addField(root: LinearLayout, hint: String, value: String): EditText {
        return EditText(this).also {
            it.hint = hint
            it.setText(value)
            it.singleLine = true
            root.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun currentUrl(): String {
        val base = baseUrl.text.toString().trim().trimEnd('/')
        val screen = screenId.text.toString().trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID }
        return "$base/pt?screen_id=${Uri.encode(screen)}"
    }

    private fun save() {
        AgentConfig(
            baseUrl = baseUrl.text.toString().trim(),
            screenId = screenId.text.toString().trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID }
        ).save(this)
        Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun restoreDefaults() {
        baseUrl.setText(AgentConfig.DEFAULT_BASE_URL)
        screenId.setText(AgentConfig.DEFAULT_SCREEN_ID)
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
}
