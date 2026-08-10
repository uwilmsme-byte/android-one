package com.seoulprime.huboneagent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var config: AgentConfig
    private lateinit var webView: WebView
    private lateinit var errorView: LinearLayout
    private var lastLoadedUrl = ""
    private var touchCount = 0
    private var lastTouchAt = 0L
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var discoveryStarted = false
    private var pendingPermissionRequest: PermissionRequest? = null

    // 실시간 통역 마이크 — 웹뷰가 http(HTTPS 아님)로 페이지를 열 경우 Chromium의 보안
    // 컨텍스트 정책 때문에 getUserMedia() 자체가 막힐 수 있다(권한을 다 승인해도 안됨).
    // 그래서 웹 API 대신 네이티브 MediaRecorder로 녹음하고, 결과를 앱이 직접 서버에
    // 업로드하는 JS 브릿지(HubOneAudio)를 같이 제공한다 — 페이지 쪽은 이 브릿지가
    // 있으면 우선 쓰고, 없으면(일반 브라우저) 기존 웹 getUserMedia 경로로 자동 폴백한다.
    private var nativeMediaRecorder: MediaRecorder? = null
    private var nativeRecordingFile: File? = null
    private var pendingNativeAudioStart = false

    // 상담원 화면(HUBONE deskchat)이 접수(/pt)·예약(/pt/reserve) 중 어느 화면을 보여줄지
    // 원격으로 지시하는 경로. 서버 재시작/앱 재시작과 무관하게 항상 접수 화면이 기본이다.
    private var currentScreenPath = SCREEN_PATH_CONTACT

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enterImmersiveMode()
        config = AgentConfig.load(this)
        applyScreenPolicy()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(AudioBridge(), "HubOneAudio")
            webViewClient = SafeWebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST)
                        true
                    } catch (_: Exception) {
                        fileCallback = null
                        false
                    }
                }

                // 예약 태블릿(/pt/reserve)의 실시간 통역 마이크 기능이 getUserMedia()를
                // 호출하면 여기로 들어온다 — 이걸 오버라이드하지 않으면 웹뷰가 항상
                // 거부해서 "이 기기에서는 마이크를 사용할 수 없습니다"만 뜬다(실제 겪은
                // 문제). RECORD_AUDIO가 이미 승인돼 있으면 바로 grant, 아니면 시스템
                // 런타임 권한을 먼저 요청한 뒤 결과에 따라 grant/deny한다.
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        val needsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        if (!needsAudio) {
                            request.deny()
                            return@runOnUiThread
                        }
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            request.grant(request.resources)
                        } else {
                            pendingPermissionRequest = request
                            ActivityCompat.requestPermissions(
                                this@MainActivity,
                                arrayOf(Manifest.permission.RECORD_AUDIO),
                                RECORD_AUDIO_PERMISSION_REQUEST
                            )
                        }
                    }
                }
            }
        }
        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val now = System.currentTimeMillis()
                touchCount = if (now - lastTouchAt < 1_500) touchCount + 1 else 1
                lastTouchAt = now
                if (touchCount >= 5) {
                    touchCount = 0
                    openAdmin()
                }
            }
            false
        }

        errorView = buildErrorView()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(errorView, FrameLayout.LayoutParams(-1, -1))
        root.addView(buildCornerTrigger(), FrameLayout.LayoutParams(140, 140, Gravity.TOP or Gravity.END))
        setContentView(root)
        applyScreenExtra(intent)
        loadConfiguredPage()
        // 화면전환 명령 폴링은 이 Activity의 생명주기와 분리된 포그라운드 서비스가 전담한다
        // (실제 요청 사항: "접수, 혹은 예약 화면을 열면 덴트웹 접수 화면에서 /pt,
        // /pt/reservation으로 화면이 전환되도록" — 예전엔 이 Activity가 백그라운드로
        // 밀리면(=덴트웹이 앞에 있으면) 폴링 자체가 멈춰서 명령을 받을 수 없었다).
        CommandPollService.start(this)
    }

    // CommandPollService가 명령을 받아 이 Activity를 강제로 앞에 가져올 때(덴트웹 화면
    // 등에서 돌아올 때) singleTask라 onCreate가 아니라 여기로 들어온다.
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        if (::webView.isInitialized) {
            applyScreenExtra(newIntent)
            loadConfiguredPage()
        }
    }

    private fun applyScreenExtra(source: Intent?) {
        when (source?.getStringExtra(EXTRA_SCREEN_COMMAND)) {
            CommandPollState.SCREEN_RESERVATION -> currentScreenPath = SCREEN_PATH_RESERVATION
            CommandPollState.SCREEN_CONTACT -> currentScreenPath = SCREEN_PATH_CONTACT
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            // 명령 없이 사용자가 수동으로 이 화면에 돌아온 경우(예: 덴트웹에서 스와이프/뒤로가기)
            // 도 여기서 바로 잡아준다 — CommandPollService는 이 반대 방향을 추측하지 않고
            // MainActivity가 실제로 보여주는 화면을 기준으로 여기서 정확히 갱신한다.
            CommandPollState.currentScreen = if (currentScreenPath == SCREEN_PATH_RESERVATION)
                CommandPollState.SCREEN_RESERVATION else CommandPollState.SCREEN_CONTACT
            val latest = AgentConfig.load(this)
            if (latest.baseUrl != config.baseUrl || latest.screenId != config.screenId) {
                config = latest
                applyScreenPolicy()
                loadConfiguredPage()
            }
        }
    }

    private fun buildErrorView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.WHITE)
        addView(TextView(this@MainActivity).apply {
            text = "서버에 연결할 수 없습니다"
            textSize = 22f
            setTextColor(Color.DKGRAY)
        })
        addView(Button(this@MainActivity).apply {
            text = "다시 시도"
            setOnClickListener { loadConfiguredPage() }
        })
    }

    private fun buildCornerTrigger() = View(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.postDelayed({ if (view.isPressed) openAdmin() }, 5_000)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.isPressed = false
            }
            true
        }
    }

    private fun loadConfiguredPage() {
        config = AgentConfig.load(this)
        applyScreenPolicy()
        // 이 Activity가 실제로 화면을 그리는 순간을 "현재 화면 상태"의 기준으로 삼는다 —
        // CommandPollService가 명령을 보낼 때도 미리 갱신해두지만, 런처 아이콘으로 수동
        // 실행한 경우 등도 여기서 함께 반영된다(허브원 보드 탭의 상태 표시용).
        CommandPollState.currentScreen = if (currentScreenPath == SCREEN_PATH_RESERVATION)
            CommandPollState.SCREEN_RESERVATION else CommandPollState.SCREEN_CONTACT
        val base = config.baseUrl.trim().trimEnd('/')
        val screen = config.screenId.trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID }
        val target = "$base$currentScreenPath?screen_id=${Uri.encode(screen)}"
        val uri = Uri.parse(target)
        if (!isAllowed(uri)) {
            showError()
            startAutoDiscovery()
            return
        }
        lastLoadedUrl = target
        errorView.visibility = View.GONE
        webView.loadUrl(target)
        // 명령 수신 후 이 화면이 실제 입력 대상이 되도록 포커스를 되돌린다 — URL만 바꾸면
        // 방금까지 떠 있던 다른 화면(덴트웹 등)에 포커스가 남아 터치가 안 먹는 경우가 있다.
        window.decorView.requestFocus()
        webView.requestFocus(View.FOCUS_DOWN)
    }

    private fun isAllowed(uri: Uri): Boolean {
        val base = Uri.parse(config.baseUrl.trim())
        val baseHost = base.host?.lowercase(Locale.ROOT)
        return !base.scheme.isNullOrBlank() && !baseHost.isNullOrBlank() &&
            uri.host?.lowercase(Locale.ROOT) == baseHost
    }

    private fun showError() { errorView.visibility = View.VISIBLE }

    private fun startAutoDiscovery() {
        // 이미 정상 연결이 검증된 수동 지정 주소는 일시적 접속 실패로도 자동검색이 조용히
        // 덮어쓰지 않는다(실제 겪은 문제: "IP를 지정하고 저장하니 처음에 반짝하고 다시
        // 예전 주소가 나옴"). 반대로 아직 한 번도 연결에 성공하지 못한 주소(오타 등)라면
        // 자동검색이 여전히 도와준다("가장 처음에 ip가 맞지 않아 접속이 안된다면 자동
        // 스캔을 시도하는게 좋겠음") — AgentConfig.manualBaseUrlVerified 참고.
        if (config.manualBaseUrl && config.manualBaseUrlVerified) return
        if (discoveryStarted) return
        discoveryStarted = true
        Thread {
            val discovered = ServerDiscovery.discover(config.baseUrl)
            runOnUiThread {
                discoveryStarted = false
                if (!discovered.isNullOrBlank()) {
                    // discover()가 지금 설정된 주소를 그대로 재확인만 한 경우(같은 값)라면
                    // 수동 잠금을 풀 이유가 없다 — 실제로 다른 서버로 전환됐을 때만 잠금을
                    // 해제한다(ServerDiscovery.probe() 버그 수정 후에도 안전하게 유지).
                    val switchedToDifferentServer = discovered != config.baseUrl
                    config = config.copy(
                        baseUrl = discovered,
                        manualBaseUrl = if (switchedToDifferentServer) false else config.manualBaseUrl,
                        manualBaseUrlVerified = if (switchedToDifferentServer) false else true
                    )
                    config.save(this)
                    loadConfiguredPage()
                }
            }
        }.start()
    }

    // 현재 config.baseUrl로 페이지가 정상 로드됐다는 뜻 — 수동 지정 주소였다면 이제부터
    // "검증됨"으로 잠가서 이후 일시적 접속 실패에도 자동검색이 덮어쓰지 못하게 한다.
    private fun markBaseUrlVerifiedIfNeeded() {
        if (config.manualBaseUrl && !config.manualBaseUrlVerified) {
            config = config.copy(manualBaseUrlVerified = true)
            config.save(this)
        }
    }

    private fun openAdmin() { startActivity(Intent(this, SettingsActivity::class.java)) }

    private fun applyScreenPolicy() {
        if (config.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // CommandPollService의 focused 보고에 쓰인다 — 허브원 보드 탭 상태 표시용.
        CommandPollState.windowFocused = hasFocus
        if (hasFocus) enterImmersiveMode()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
    }

    @Deprecated("Android activity result API kept for Android 10 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RECORD_AUDIO_PERMISSION_REQUEST) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

        // 이 요청 코드는 두 경로가 같이 쓴다 — 웹 getUserMedia(PermissionRequest)와
        // 네이티브 HubOneAudio.startRecording(). 둘 중 실제로 대기 중이던 쪽만 처리한다.
        val webRequest = pendingPermissionRequest
        pendingPermissionRequest = null
        if (webRequest != null) {
            if (granted) webRequest.grant(webRequest.resources) else webRequest.deny()
        }

        if (pendingNativeAudioStart) {
            pendingNativeAudioStart = false
            if (granted) startNativeRecordingInternal() else notifyJsAudioEvent("start_error", "permission_denied")
        }
    }

    // 페이지(foreign_reservation_intake.html)가 window.HubOneAudio로 호출하는 네이티브
    // 녹음 브릿지. startRecording()으로 시작하고 stopAndUpload()로 정지+서버 업로드까지
    // 앱이 직접 처리한다 — 결과는 window.__hubOneVoiceEvent(status, message) JS 콜백으로
    // 비동기 통지한다(status: "started"|"start_error"|"uploaded"|"upload_error").
    private inner class AudioBridge {
        @JavascriptInterface
        fun startRecording() {
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingNativeAudioStart = true
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        RECORD_AUDIO_PERMISSION_REQUEST
                    )
                    return@runOnUiThread
                }
                startNativeRecordingInternal()
            }
        }

        @JavascriptInterface
        fun stopAndUpload(sessionId: String, language: String) {
            runOnUiThread {
                val recorder = nativeMediaRecorder
                val file = nativeRecordingFile
                nativeMediaRecorder = null
                nativeRecordingFile = null
                if (recorder == null || file == null) {
                    notifyJsAudioEvent("upload_error", "not_recording")
                    return@runOnUiThread
                }
                try {
                    recorder.stop()
                    recorder.release()
                } catch (e: Exception) {
                    notifyJsAudioEvent("upload_error", e.message ?: "stop_failed")
                    return@runOnUiThread
                }
                Thread {
                    val (ok, message) = uploadVoiceFile(sessionId, language, file)
                    file.delete()
                    runOnUiThread { notifyJsAudioEvent(if (ok) "uploaded" else "upload_error", message) }
                }.start()
            }
        }
    }

    private fun startNativeRecordingInternal() {
        try {
            val file = File(cacheDir, "hubone_voice_${System.currentTimeMillis()}.m4a")
            nativeRecordingFile = file
            nativeMediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            notifyJsAudioEvent("started", "")
        } catch (e: Exception) {
            nativeMediaRecorder = null
            nativeRecordingFile = null
            notifyJsAudioEvent("start_error", e.message ?: "recorder_error")
        }
    }

    private fun notifyJsAudioEvent(status: String, message: String) {
        val js = "window.__hubOneVoiceEvent && window.__hubOneVoiceEvent(${JSONObject.quote(status)}, ${JSONObject.quote(message)});"
        webView.evaluateJavascript(js, null)
    }

    // 세션 API에 직접 멀티파트 업로드한다 — deskchat/api/foreign_reservation.py의
    // POST .../voice/transcribe와 동일한 필드(audio, language)를 그대로 맞춘다.
    private fun uploadVoiceFile(sessionId: String, language: String, file: File): Pair<Boolean, String> {
        return try {
            val boundary = "----HubOneBoundary${System.currentTimeMillis()}"
            val base = config.baseUrl.trim().trimEnd('/')
            val url = URL("$base/api/patients/foreign-reservation/session/${Uri.encode(sessionId)}/voice/transcribe")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            conn.outputStream.use { out ->
                fun writeText(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                writeText("--$boundary\r\n")
                writeText("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                writeText("$language\r\n")
                writeText("--$boundary\r\n")
                writeText("Content-Disposition: form-data; name=\"audio\"; filename=\"voice.m4a\"\r\n")
                writeText("Content-Type: audio/mp4\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                writeText("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Pair(true, "") else Pair(false, "http_$code")
        } catch (e: Exception) {
            Pair(false, e.message ?: "upload_exception")
        }
    }

    private inner class SafeWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return !isAllowed(request.url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            markBaseUrlVerifiedIfNeeded()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame) {
                showError()
                startAutoDiscovery()
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                showError()
                startAutoDiscovery()
            }
        }
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 401
        private const val SCREEN_PATH_CONTACT = "/pt"
        private const val SCREEN_PATH_RESERVATION = "/pt/reserve"
        private const val RECORD_AUDIO_PERMISSION_REQUEST = 20

        // CommandPollService가 MainActivity를 강제로 앞에 가져올 때(덴트웹 등에서 복귀)
        // 어느 화면을 띄울지 실어 보내는 Intent extra 키 — CommandPollState.SCREEN_CONTACT/
        // SCREEN_RESERVATION 값을 그대로 담는다.
        const val EXTRA_SCREEN_COMMAND = "screen_command"
    }
}
