package com.seoulprime.huboneagent

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.WindowManager
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
 *
 * command_token ACK 프로토콜(서버가 FIFO 대기열 + ACK로 명령을 관리하도록 바뀜)과
 * UsageStats 기반 실제 전면 앱 감지는 병행 개발되던 hubone_agent/(macai 저장소 안의
 * 실험 버전)에서 검증된 설계를 그대로 이식한 것이다 — 실제 겪은 문제: ACK를 안 보내면
 * 서버 대기열이 첫 명령에서 막혀서 그 다음 명령부터 영원히 전달되지 않았다.
 */
class CommandPollService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastAppliedCommandId = 0
    private var lastCommandToken = ""
    private var lastCommandResult: Pair<Boolean, String>? = null
    private var pollRunnable: Runnable? = null

    // UsageStats 조회는 비용이 있어 캐싱한다(3초 폴링마다 매번 새로 안 함).
    private var lastForegroundCheckAt = 0L
    private var lastForegroundDentwebState: Boolean? = null

    // 안드로이드 10+ "백그라운드 활동 시작 제한" 대응 — 실제 겪은 문제: 서비스에서
    // startActivity()가 조용히 무시돼서(예외도 안 남) 명령은 성공으로 찍히는데 실제 화면은
    // 안 바뀌었다. "보이는 창이 있는 앱"은 이 제한의 예외 대상이라, 사용자가 관리자
    // 설정에서 "다른 앱 위에 표시"를 허용하면 눈에 안 보이는 1x1 오버레이 창을 계속
    // 띄워둬서 이 예외 조건을 만족시킨다.
    private var overlayView: View? = null

    // 덴트웹 고객용 앱이 전면일 때도 대기 광고 슬라이드쇼를 보여주는 네이티브 오버레이
    // — 실제 요청 사항: "덴트웹 화면에서도 같이 동작하기를 원함" (AdOverlayManager 참고).
    private lateinit var adOverlayManager: AdOverlayManager

    override fun onCreate() {
        super.onCreate()
        adOverlayManager = AdOverlayManager(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        ensureOverlayKeepAlive()
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
        removeOverlayKeepAlive()
        adOverlayManager.teardown()
        super.onDestroy()
    }

    // 권한을 이 앱 실행 도중에 새로 허용했을 수도 있으니(설정화면 다녀온 뒤) 매 폴링마다
    // 아직 오버레이가 없으면 다시 시도한다 — 이미 떠 있으면 아무 것도 안 한다.
    private fun ensureOverlayKeepAlive() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            val windowManager = getSystemService(WindowManager::class.java) ?: return
            val view = View(this)
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            view.alpha = 0f
            windowManager.addView(view, params)
            overlayView = view
        } catch (_: Exception) {
            // 일부 기기/런처가 오버레이 추가를 거부할 수 있다 — 다음 폴링에서 재시도.
        }
    }

    private fun removeOverlayKeepAlive() {
        val view = overlayView ?: return
        overlayView = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(view)
        } catch (_: Exception) { /* 무시 */ }
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
        ensureOverlayKeepAlive()
        val config = AgentConfig.load(this)
        val base = config.baseUrl.trim().trimEnd('/')
        val screen = config.screenId.trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID }
        if (base.isBlank()) return

        val detected = detectDentwebForeground(config.dentwebPackage.trim())
        // 실제 전면 앱을 확인할 권한(사용정보 접근)이 없으면(detected == null) 우리가 마지막으로
        // 내린 명령 기준으로 추정한다 — 최소한의 폴백, 권한을 허용하면 훨씬 정확해진다.
        val externalDentweb = detected ?: (CommandPollState.currentScreen == CommandPollState.SCREEN_DENTWEB)
        // 실제 겪을 뻔한 버그: UsageStats가 "덴트웹이 진짜 전면"이라고 확인해줘도(수동으로
        // 연 경우 등, 우리가 낸 명령과 무관하게) currentScreen을 안 맞춰주면 보드에는 여전히
        // 예전 화면(contact/reservation)으로 보였다 — externalDentweb을 기준으로 재조정한다.
        // 반대 방향(덴트웹인 줄 알았는데 사용자가 수동으로 이 앱에 복귀한 경우)은 여기서
        // 추측하지 않는다 — MainActivity.onResume()이 실제 currentScreenPath로 정확히
        // 갱신해준다(다음 폴링 주기에 반영됨).
        if (externalDentweb) {
            CommandPollState.currentScreen = CommandPollState.SCREEN_DENTWEB
        }
        // 덴트웹이 전면일 때도 대기 광고 슬라이드쇼가 보이도록 — 웹뷰(/pt)가 전면일 때는
        // JS 쪽(tablet_idle_overlay.js)이 이미 처리하므로 이 매니저는 손을 뗀다.
        adOverlayManager.onPollTick(screen, base, externalDentweb)
        val currentScreen = CommandPollState.currentScreen
        val focused = !externalDentweb && CommandPollState.windowFocused
        val statusMessage = if (detected == null) "전면 앱 확인 권한이 없어 덴트웹 앱 상태를 추정치로만 보고합니다."
            else CommandPollState.lastStatusMessage

        Thread {
            try {
                val query = "screen_id=${Uri.encode(screen)}&current_screen=${Uri.encode(currentScreen)}" +
                    "&focused=$focused&external_dentweb=$externalDentweb&status_message=${Uri.encode(statusMessage)}"
                val conn = URL("$base/api/agent/command?$query").openConnection() as HttpURLConnection
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
                val commandToken = json.optString("command_token", "")
                val command = json.optString("command", "")
                if (command.isBlank()) return@Thread

                // command_token은 서버가 FIFO 대기열 + ACK로 명령을 관리하는 식별자다 —
                // 같은 토큰을 다시 받으면(네트워크 문제 등으로 우리 ACK가 서버에 안 갔다는 뜻)
                // 재실행하지 않고 이미 낸 결과만 다시 ACK한다. 이걸 안 하면 서버 대기열이
                // 첫 명령에서 막혀 다음 명령부터 영영 전달되지 않는다(실제 겪을 뻔한 문제).
                if (commandToken.isNotBlank() && commandToken == lastCommandToken) {
                    lastCommandResult?.let { (ok, message) -> reportCommandResult(screen, commandToken, command, ok, message) }
                    return@Thread
                }
                if (commandToken.isBlank() && commandId != 0 && commandId == lastAppliedCommandId) return@Thread

                lastAppliedCommandId = commandId
                lastCommandToken = commandToken
                val result = applyCommand(command)
                lastCommandResult = result
                CommandPollState.lastStatusMessage = result.second
                if (commandToken.isNotBlank()) reportCommandResult(screen, commandToken, command, result.first, result.second)
            } catch (_: Exception) {
                // 다음 3초 주기에 조용히 재시도
            }
        }.start()
    }

    private fun applyCommand(command: String): Pair<Boolean, String> {
        // sleep을 뺀 나머지 모든 명령은 화면을 먼저 깨운다 — open_contact/wake는
        // MainActivity의 setShowWhenLocked/setTurnScreenOn으로 어차피 켜지지만,
        // return_to_dentweb은 우리 앱이 아닌 남의 앱을 실행하는 거라 그 앱이 스스로
        // 화면을 켜준다는 보장이 없다(실제 지적 사항: "덴트웹이나 외국인 접수만
        // 눌러도 꺼진 상태면 자동으로 켜지겠죠?" — 모든 명령에서 똑같이 보장하려면
        // 여기서 공통으로 깨워야 한다).
        if (command != "sleep") wakeScreenBriefly()
        return when (command) {
            "open_contact" -> bringMainActivityToFront(CommandPollState.SCREEN_CONTACT)
            "open_reservation" -> bringMainActivityToFront(CommandPollState.SCREEN_RESERVATION)
            "return_to_dentweb" -> launchDentWeb()
            "sleep" -> sleepDevice()
            "wake" -> bringMainActivityToFront(CommandPollState.SCREEN_CONTACT)
            else -> false to "알 수 없는 명령입니다: $command"
        }
    }

    // WAKE_LOCK 권한(이미 선언돼 있음)만으로 되는 화면 깨우기 — SCREEN_BRIGHT_WAKE_LOCK +
    // ACQUIRE_CAUSES_WAKEUP은 최신 API에서 deprecated이지만, "서비스에서 화면을 즉시
    // 켠다"는 목적에 맞는 대체 API가 따로 없어 그대로 쓴다. 10초 뒤 자동 해제되도록
    // 타임아웃을 줘서(acquire(ms)) 혹시 release()를 놓쳐도 화면이 계속 켜진 채로
    // 남는 배터리 문제를 방지한다.
    @Suppress("DEPRECATION")
    private fun wakeScreenBriefly() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "HuBoneAgent:CommandWake"
            )
            wakeLock.acquire(10_000)
            wakeLock.release()
        } catch (_: Exception) {
            // 화면 깨우기는 부가 기능이라 실패해도 명령 자체(예: 덴트웹 전환)는 계속 진행한다.
        }
    }

    // 절전(sleep) — 기기 관리자(HubOneDeviceAdminReceiver)가 활성화돼 있어야 한다.
    // 관리자 설정에서 "절전 명령 허용" 버튼으로 한 번 켜두면 된다.
    private fun sleepDevice(): Pair<Boolean, String> {
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
            ?: return false to "DevicePolicyManager를 가져올 수 없습니다."
        val admin = android.content.ComponentName(this, HubOneDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            return false to "기기 관리자 권한이 없습니다 — 태블릿 관리자 설정에서 \"절전 명령 허용\"을 먼저 켜주세요."
        }
        return try {
            dpm.lockNow()
            true to "절전 모드로 전환했습니다."
        } catch (e: Exception) {
            false to "절전 전환 실패: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }

    // 덴트웹 앱이 지금 앞에 나와 있어도(=MainActivity가 백그라운드라 자체 폴링이 멈춰 있어도)
    // 이 서비스는 계속 살아있으므로, 명령을 받으면 MainActivity를 새 태스크 플래그로 강제로
    // 앞에 가져온다. MainActivity가 singleTask라 기존 인스턴스가 있으면 onNewIntent로,
    // 없으면 onCreate로 EXTRA_SCREEN_COMMAND를 받아 화면을 전환한다.
    private fun bringMainActivityToFront(screen: String): Pair<Boolean, String> {
        // 실제 겪은 버그: 여기서 CommandPollState.currentScreen을 먼저 바꿔버리면, 아래
        // startActivity()가 실제로는 실패해도(백그라운드 활동 시작 제한 등) 보드에는
        // "성공한 것처럼" 상태가 찍혀서 원인을 알 수 없었다 — 성공했을 때만 갱신한다.
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(MainActivity.EXTRA_SCREEN_COMMAND, screen)
        }
        return try {
            startActivity(intent)
            CommandPollState.currentScreen = screen
            true to "Android One 화면으로 포커스를 전환했습니다."
        } catch (e: Exception) {
            // 안드로이드 10+ "백그라운드 활동 시작 제한"에 걸리면 여기서 SecurityException 등이
            // 날 수 있다 — 이 메시지가 보드에 그대로 뜨니 실제 원인을 여기서 확인 가능하다.
            // 다음 폴링에서도 새 명령이 오면 계속 재시도된다.
            false to "Android One 포커스 전환 실패: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }

    private fun launchDentWeb(): Pair<Boolean, String> {
        val config = AgentConfig.load(this)
        val pkg = config.dentwebPackage.trim()
        if (pkg.isBlank()) {
            return false to "덴트웹 앱 패키지명이 설정되지 않았습니다."
        }
        val intent = try {
            packageManager.getLaunchIntentForPackage(pkg)
        } catch (_: Exception) { null }
        if (intent == null) {
            return false to "덴트웹 앱을 찾을 수 없습니다: $pkg"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        return try {
            startActivity(intent)
            CommandPollState.currentScreen = CommandPollState.SCREEN_DENTWEB
            true to "덴트웹 앱으로 포커스를 전환했습니다."
        } catch (e: Exception) {
            // 안드로이드 10+ "백그라운드 활동 시작 제한"에 걸리면 여기서 SecurityException 등이
            // 날 수 있다 — 이 메시지가 보드에 그대로 뜨니 실제 원인을 여기서 확인 가능하다.
            false to "덴트웹 앱 실행 실패: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }

    private fun reportCommandResult(screenId: String, commandToken: String, command: String, ok: Boolean, message: String) {
        val config = AgentConfig.load(this)
        val base = config.baseUrl.trim().trimEnd('/')
        Thread {
            try {
                val payload = JSONObject()
                    .put("screen_id", screenId)
                    .put("command_token", commandToken)
                    .put("command", command)
                    .put("ok", ok)
                    .put("message", message)
                    .toString()
                val conn = (URL("$base/api/agent/command/result").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
                // 다음 폴링에서 같은 token을 다시 받아 ACK를 재시도한다.
            }
        }.start()
    }

    // 사용정보 접근(PACKAGE_USAGE_STATS)을 관리자 설정에서 허용해뒀으면, 가장 최근에
    // 전면으로 온 앱이 덴트웹인지 실제로 확인한다. 권한이 없으면 null을 반환해 호출부가
    // 우리가 마지막으로 내린 명령 기준의 추정치로 폴백하게 한다.
    private fun detectDentwebForeground(dentwebPkg: String): Boolean? {
        val now = System.currentTimeMillis()
        if (now - lastForegroundCheckAt < FOREGROUND_CHECK_CACHE_MS) return lastForegroundDentwebState
        lastForegroundCheckAt = now
        if (dentwebPkg.isBlank()) {
            lastForegroundDentwebState = null
            return null
        }

        val appOps = getSystemService(AppOpsManager::class.java)
        val usageAccessAllowed = appOps?.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
        if (!usageAccessAllowed) {
            lastForegroundDentwebState = null
            return null
        }

        val usageStats = getSystemService(UsageStatsManager::class.java) ?: return null
        val events = usageStats.queryEvents(now - FOREGROUND_EVENT_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latestPackage = event.packageName
        }
        lastForegroundDentwebState = latestPackage?.let { it == dentwebPkg }
        return lastForegroundDentwebState
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val FOREGROUND_CHECK_CACHE_MS = 5_000L
        private const val FOREGROUND_EVENT_LOOKBACK_MS = 12 * 60 * 60 * 1_000L

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
