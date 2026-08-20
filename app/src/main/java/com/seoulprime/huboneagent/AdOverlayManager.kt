package com.seoulprime.huboneagent

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 대기화면(웹뷰의 /pt, /pt/reserve — tablet_idle_overlay.js)이 아니라 덴트웹 고객용
 * 앱이 전면에 있을 때도 같은 대기 광고 슬라이드쇼를 보여주기 위한 네이티브 오버레이
 * — 실제 요청 사항: "덴트웹 화면에서도 같이 동작하기를 원함". 덴트웹은 우리 앱이
 * 아니라서 웹뷰의 JS가 닿지 않으므로, 같은 macai API(GET /api/tablet-ad-settings,
 * GET /api/files/tablet-ad/{id}/preview — 둘 다 로그인 불필요)를 이 네이티브 코드가
 * 직접 불러서 웹 버전(tablet_idle_overlay.js)과 최대한 같은 규칙(무조작 idle 후
 * 표시 / 터치 시 즉시 닫힘 / 안내사진 간격 삽입 / 혼잡모드 전용 세트)으로 재현한다.
 *
 * 터치 감지는 시스템 전역에서 일어나는 터치를 accessibility 서비스 없이 알아내야
 * 해서, FLAG_WATCH_OUTSIDE_TOUCH + 1x1 크기의 포커스 불가 오버레이 창이라는 안드로이드
 * 표준 기법(카카오톡 말풍선 등의 "바깥 터치 감지"와 동일)을 쓴다 — CommandPollService가
 * 이미 갖고 있는 SYSTEM_ALERT_WINDOW 권한을 그대로 재사용하므로 새 권한 요청이 없다.
 *
 * ⚠️ 실기기 검증 필요: 이 파일은 Android SDK가 없는 환경에서 작성돼 컴파일/실기기
 * 동작 확인을 못 했다. 특히 FLAG_WATCH_OUTSIDE_TOUCH가 기대대로 시스템 전역 터치를
 * 보고하는지는 실제 태블릿(제조사/OS 버전에 따라 오버레이 동작이 다를 수 있음)에서
 * 반드시 확인해야 한다.
 */
class AdOverlayManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)

    private var touchWatcherView: View? = null
    private var adOverlayView: ImageView? = null
    private var idleRunnable: Runnable? = null
    private var slideCycleRunnable: Runnable? = null

    private var slideUrls: List<String> = emptyList()
    private var slideIntervalMs: Long = 6_000L
    private var slideIndex = 0
    // 무동작 몇 ms 후 표시할지 — 데스크 "Kiosk 설정"에서 조절 가능(idle_ms). 처음
    // 켜졌을 때는 기본값(IDLE_MS)을 쓰고, 매번 showAdOverlay()에서 새로 받아온
    // 값으로 다음 주기부터 갱신한다(웹 버전 tablet_idle_overlay.js와 동일한 절충).
    private var cachedIdleMs: Long = IDLE_MS
    // 광고 좌/우 분리 터치 — 데스크 "Kiosk 설정"에서 켜고 끌 수 있음(ad_touch_split).
    // 끄면 어디를 눌러도 dismissAdAndGoToContact()만 실행(예전 동작).
    private var touchSplitEnabled: Boolean = true

    private var configScreenId: String = ""
    private var configBaseUrl: String = ""
    // 지금 덴트웹이 전면이라 idle 감시를 해야 하는 상태인지 — 웹뷰(/pt)가 전면일
    // 때는 JS 쪽(tablet_idle_overlay.js)이 이미 처리하므로 이 매니저는 손을 뗀다
    // (동시에 두 오버레이가 뜨는 걸 방지).
    private var armed = false

    /** CommandPollService가 매 폴링(3초)마다 호출한다. */
    fun onPollTick(screenId: String, baseUrl: String, externalDentweb: Boolean) {
        configScreenId = screenId
        configBaseUrl = baseUrl
        ensureTouchWatcher()
        if (externalDentweb) {
            if (!armed) {
                armed = true
                resetIdleTimer()
            }
        } else {
            armed = false
            cancelIdleTimer()
            hideAdOverlay()
        }
    }

    fun teardown() {
        armed = false
        cancelIdleTimer()
        hideAdOverlay()
        val v = touchWatcherView ?: return
        touchWatcherView = null
        try { windowManager?.removeView(v) } catch (_: Exception) { /* 무시 */ }
    }

    private fun ensureTouchWatcher() {
        if (touchWatcherView != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!Settings.canDrawOverlays(context)) return
        val wm = windowManager ?: return
        try {
            val view = View(context)
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            view.alpha = 0f
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (adOverlayView != null) {
                        // 정상적으로는 광고 창 자체의 클릭 리스너가 먼저 받아가지만
                        // (전체화면을 덮고 있어 더 위에 뜸), 기기/OEM에 따라 바깥터치
                        // 감시 창이 대신 받는 경우를 대비한 동일 동작 백업 — 분리 터치가
                        // 꺼져 있으면 이쪽도 똑같이 직전 화면(덴트웹)에 남는다.
                        if (touchSplitEnabled) dismissAdAndGoToContact() else dismissAdStayOnDentweb()
                    } else if (armed) {
                        resetIdleTimer()
                    }
                }
                false
            }
            wm.addView(view, params)
            touchWatcherView = view
        } catch (_: Exception) {
            // 일부 기기/런처가 오버레이 추가를 거부할 수 있다 — 다음 폴링에서 재시도.
        }
    }

    private fun resetIdleTimer() {
        cancelIdleTimer()
        val r = Runnable { showAdOverlay() }
        idleRunnable = r
        handler.postDelayed(r, cachedIdleMs)
    }

    private fun cancelIdleTimer() {
        idleRunnable?.let { handler.removeCallbacks(it) }
        idleRunnable = null
    }

    private fun showAdOverlay() {
        if (!armed) return
        val screen = configScreenId
        val base = configBaseUrl
        Thread {
            val cfg = fetchConfig(screen, base)
            handler.post {
                if (!armed) return@post // 그 사이 덴트웹에서 벗어났으면 취소
                if (cfg == null) return@post
                cachedIdleMs = cfg.idleMs // 다음 idle 대기부터 갱신된 값을 쓴다.
                touchSplitEnabled = cfg.touchSplit
                if (!cfg.enabled || cfg.slideUrls.isEmpty()) return@post
                slideUrls = cfg.slideUrls
                slideIntervalMs = cfg.slideIntervalMs
                slideIndex = 0
                displayAdOverlayWindow()
                renderCurrentSlide()
                scheduleNextSlide()
            }
        }.start()
    }

    private fun displayAdOverlayWindow() {
        if (adOverlayView != null) return
        val wm = windowManager ?: return
        try {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            )
            // 화면을 좌/우로 나눠서 오른쪽을 누르면 /pt(외국인 접수) 웹뷰로 전환하고,
            // 왼쪽을 누르면 광고만 걷고 원래 떠 있던 덴트웹 화면으로 돌아간다 — 실제
            // 요청 사항. OnClickListener는 터치 좌표를 안 주므로 OnTouchListener로
            // ACTION_DOWN에서 바로 판정한다(빠른 반응, 드래그/스와이프 구분 불필요).
            iv.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // 분리 터치를 꺼두면(실제 요청 사항: "끄면 직전 화면으로 돌아가는걸로")
                    // 어디를 눌러도 그냥 광고만 걷고 원래 떠 있던 화면(덴트웹)에 남는다 —
                    // 이 오버레이는 덴트웹이 전면일 때만 뜨므로 "직전 화면"은 항상 덴트웹.
                    if (!touchSplitEnabled) {
                        dismissAdStayOnDentweb()
                    } else {
                        val isRightHalf = event.x >= v.width / 2f
                        if (isRightHalf) dismissAdAndGoToContact() else dismissAdStayOnDentweb()
                    }
                }
                true
            }
            wm.addView(iv, params)
            adOverlayView = iv
        } catch (_: Exception) {
            // 다음 idle 주기에 재시도된다.
        }
    }

    // ⚠️ 아직 사진만 지원한다 — tablet_idle_overlay.js(웹 버전)는 사진/동영상을
    // 섞어서 보여줄 수 있게 됐지만(kinds 필드), 여기(덴트웹 화면 위 오버레이)는
    // BitmapFactory로 정지 이미지만 그린다. 목록에 동영상 file_id가 섞여 있으면
    // 그 슬라이드는 디코딩이 실패해 조용히 건너뛴다(크래시는 안 남) — 네이티브
    // 동영상 재생(MediaPlayer/VideoView)은 아직 구현하지 않음, 필요시 추가 작업.
    private fun renderCurrentSlide() {
        val iv = adOverlayView ?: return
        if (slideUrls.isEmpty()) return
        val url = slideUrls[slideIndex % slideUrls.size]
        Thread {
            val bmp = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 15_000
                }
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
            handler.post {
                if (adOverlayView === iv && bmp != null) iv.setImageBitmap(bmp)
            }
        }.start()
    }

    private fun scheduleNextSlide() {
        slideCycleRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (adOverlayView == null) return@Runnable
            slideIndex = (slideIndex + 1) % maxOf(1, slideUrls.size)
            renderCurrentSlide()
            scheduleNextSlide()
        }
        slideCycleRunnable = r
        handler.postDelayed(r, slideIntervalMs)
    }

    private fun hideAdOverlay() {
        slideCycleRunnable?.let { handler.removeCallbacks(it) }
        slideCycleRunnable = null
        val iv = adOverlayView ?: return
        adOverlayView = null
        try { windowManager?.removeView(iv) } catch (_: Exception) { /* 무시 */ }
    }

    // 광고를 걷고 웹뷰(/pt 접수 화면)를 앞으로 가져온다 — CommandPollService의
    // "open_contact" 명령 처리(bringMainActivityToFront)와 같은 방식.
    private fun dismissAdAndGoToContact() {
        hideAdOverlay()
        armed = false
        cancelIdleTimer()
        navigateToContact()
    }

    // 광고 왼쪽 터치 — 이미 덴트웹이 광고 뒤에 그대로 떠 있으므로 걷어내기만 하면
    // 된다(전환 불필요). 무조작 상태가 계속되면 다시 idle 카운트를 새로 건다.
    private fun dismissAdStayOnDentweb() {
        hideAdOverlay()
        if (armed) resetIdleTimer()
    }

    private fun navigateToContact() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(MainActivity.EXTRA_SCREEN_COMMAND, CommandPollState.SCREEN_CONTACT)
        }
        try {
            context.startActivity(intent)
            CommandPollState.currentScreen = CommandPollState.SCREEN_CONTACT
        } catch (_: Exception) {
            // 안드로이드 10+ 백그라운드 활동 시작 제한에 걸릴 수 있다 — 이 서비스는
            // 항상 오버레이 창(overlayView)을 하나 띄워두고 있어("보이는 창이 있음"
            // 예외 조건) 대개는 통과하지만, 실패해도 다음 무조작 idle 주기가 지나면
            // 광고가 다시 뜨면서 재시도되는 셈이라 별도 재시도 로직을 두지 않는다.
        }
    }

    private class Config(
        val enabled: Boolean,
        val slideUrls: List<String>,
        val slideIntervalMs: Long,
        val idleMs: Long,
        val touchSplit: Boolean,
    )

    // 일반 슬라이드를 정해진 순서대로 돌리다가, guideInterval장마다 안내사진을
    // 한 장씩 끼워넣는다 — tablet_idle_overlay.js::_buildSequence()와 동일 규칙.
    // 혼잡(busy) 모드일 때는 busy_slides를 그대로 단순 순환한다(안내사진 없음).
    private fun fetchConfig(screenId: String, baseUrl: String): Config? {
        return try {
            val base = baseUrl.trim().trimEnd('/')
            val screen = screenId.trim().ifBlank { "kiosk1" }
            if (base.isBlank()) return null
            val conn = (URL("$base/api/tablet-ad-settings?screen_id=${Uri.encode(screen)}")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 10_000
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val enabled = json.optBoolean("enabled", true)
            val busyMode = json.optBoolean("busy_mode", false)
            val guideFileId = json.optString("guide_file_id", "")
            val guideInterval = json.optInt("guide_interval", 3)
            val slideIntervalMs = json.optLong("slide_interval_ms", 6_000L)
            val idleMs = json.optLong("idle_ms", IDLE_MS)
            val touchSplit = json.optBoolean("ad_touch_split", true)
            val slidesKey = if (busyMode) "busy_slides" else "slides"
            val arr = json.optJSONArray(slidesKey) ?: JSONArray()
            val fileIds = mutableListOf<String>()
            for (i in 0 until arr.length()) fileIds.add(arr.getString(i))

            val ordered = if (!busyMode && guideFileId.isNotBlank() && guideInterval > 0) {
                val seq = mutableListOf<String>()
                fileIds.forEachIndexed { idx, fid ->
                    seq.add(fid)
                    if ((idx + 1) % guideInterval == 0) seq.add(guideFileId)
                }
                if (seq.isEmpty()) fileIds else seq
            } else {
                fileIds
            }

            val urls = ordered.map { "$base/api/files/tablet-ad/${Uri.encode(it)}/preview" }
            Config(enabled, urls, slideIntervalMs.coerceIn(1_000L, 60_000L), idleMs.coerceIn(3_000L, 300_000L), touchSplit)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val IDLE_MS = 20_000L
    }
}
