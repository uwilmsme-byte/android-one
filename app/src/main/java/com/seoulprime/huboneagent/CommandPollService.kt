package com.seoulprime.huboneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 화면전환 명령(deskchat/api/agent_commands.py) 폴링을 MainActivity 생명주기(onResume/
 * onPause)에서 완전히 분리해 포그라운드 서비스로 옮긴 것 — 실제 요청 사항: "접수, 혹은
 * 예약 화면을 열면 덴트웹 접수 화면에서 /pt, /pt/reservation으로 화면이 전환되도록".
 *
 * 예전에는 MainActivity가 onPause되면(=덴트웹 앱이 앞에 나와서 HUBONE Agent가 백그라운드로
 * 밀리면) 폴링 자체가 멈춰서, 상담원이 "접수 태블릿으로 보내기"를 눌러도 태블릿이 덴트웹
 * 화면에 그대로 머물러 있었다. 이 서비스는 MainActivity 표시 여부와 무관하게 계속 폴링하다가
 * open_contact/open_reservation 명령을 받으면 MainActivity를 강제로 앞으로 가져온다
 * (return_to_dentweb는 반대로 덴트웹 앱을 앞으로 가져온다 — 이건 기존에도 되던 것).
 */
class CommandPollService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastAppliedCommandId = 0
    private var pollRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 이미 폴링 중이면 재시작하지 않는다(중복 인스턴스 방지) — START_STICKY로 시스템이
        // 죽였다가 다시 살릴 때도 onCreate가 다시 불려 폴링이 재개된다.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "hubone_agent_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(channelId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(channelId, "HUBONE Agent 화면전환 대기", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("HUBONE Agent 실행 중")
            .setContentText("상담원 화면전환 명령을 대기하고 있습니다")
            .setSmallIcon(applicationInfo.icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun startPolling() {
        val runnable = object : Runnable {
            override fun run() {
                pollOnce()
                handler.postDelayed(this, 3_000)
            }
        }
        pollRunnable = runnable
        handler.postDelayed(runnable, 500)
    }

    private fun pollOnce() {
        val config = AgentConfig.load(this)
        val base = config.baseUrl.trim().trimEnd('/')
        val screen = config.screenId.trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID }
        if (base.isBlank()) return
        val currentScreen = CommandPollState.currentScreen
        Thread {
            try {
                val url = "$base/api/agent/command?screen_id=${Uri.encode(screen)}&current_screen=${Uri.encode(currentScreen)}"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return@Thread
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val commandId = json.optInt("command_id", 0)
                if (commandId == 0 || commandId == lastAppliedCommandId) return@Thread
                lastAppliedCommandId = commandId
                val command = json.optString("command", "")
                applyCommand(command)
            } catch (_: Exception) {
                // 다음 3초 주기에 조용히 재시도
            }
        }.start()
    }

    private fun applyCommand(command: String) {
        when (command) {
            "open_contact" -> bringMainActivityToFront(CommandPollState.SCREEN_CONTACT)
            "open_reservation" -> bringMainActivityToFront(CommandPollState.SCREEN_RESERVATION)
            "return_to_dentweb" -> launchDentWeb()
        }
    }

    // 덴트웹 앱이 지금 앞에 나와 있어도(=MainActivity가 백그라운드라 자체 폴링이 멈춰 있어도)
    // 이 서비스는 계속 살아있으므로, 명령을 받으면 MainActivity를 새 태스크 플래그로 강제로
    // 앞에 가져온다. MainActivity가 singleTask라 기존 인스턴스가 있으면 onNewIntent로,
    // 없으면 onCreate로 EXTRA_SCREEN_COMMAND를 받아 화면을 전환한다.
    private fun bringMainActivityToFront(screen: String) {
        CommandPollState.currentScreen = screen
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(MainActivity.EXTRA_SCREEN_COMMAND, screen)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // 일부 OEM/키오스크 정책이 백그라운드 서비스의 액티비티 기동을 막을 수 있다 —
            // 다음 폴링에서도 계속 재시도되므로(commandId가 이미 적용됐다고 기록하지 않으면)
            // 무한 재시도되지 않게 여기선 실패해도 lastAppliedCommandId는 이미 갱신된 채 둔다.
        }
    }

    private fun launchDentWeb() {
        CommandPollState.currentScreen = CommandPollState.SCREEN_DENTWEB
        val config = AgentConfig.load(this)
        val pkg = config.dentwebPackage.trim()
        if (pkg.isBlank()) return
        val intent = try {
            packageManager.getLaunchIntentForPackage(pkg)
        } catch (_: Exception) { null } ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) { /* 무시 — 다음 시도를 기다림 */ }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: android.content.Context) {
            val intent = Intent(context, CommandPollService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
