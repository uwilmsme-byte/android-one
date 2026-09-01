package com.seoulprime.huboneagent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.Dialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity(), LifecycleOwner {
    // CameraX가 미리보기/촬영 바인딩을 이 Activity의 실제 생명주기(onStart/onResume/
    // onPause/onStop)에 맞춰 자동으로 시작·정지하도록 하기 위한 최소 LifecycleOwner
    // 구현 — 이 Activity는 android.app.Activity라 androidx의 자동 디스패치가 없다.
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private lateinit var config: AgentConfig
    private lateinit var webView: WebView
    private lateinit var errorView: LinearLayout
    private var lastLoadedUrl = ""
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var discoveryStarted = false
    private var pendingPermissionRequest: PermissionRequest? = null
    private var consultPopup: Dialog? = null
    private var consultPopupWebView: WebView? = null

    // 실시간 통역 마이크 — 웹뷰가 http(HTTPS 아님)로 페이지를 열 경우 Chromium의 보안
    // 컨텍스트 정책 때문에 getUserMedia() 자체가 막힐 수 있다(권한을 다 승인해도 안됨).
    // 그래서 웹 API 대신 네이티브 MediaRecorder로 녹음하고, 결과를 앱이 직접 서버에
    // 업로드하는 JS 브릿지(HubOneAudio)를 같이 제공한다 — 페이지 쪽은 이 브릿지가
    // 있으면 우선 쓰고, 없으면(일반 브라우저) 기존 웹 getUserMedia 경로로 자동 폴백한다.
    private var nativeMediaRecorder: MediaRecorder? = null
    private var nativeRecordingFile: File? = null
    private var pendingNativeAudioStart = false
    private var pendingNativeAutoRequest: NativeAutoRequest? = null
    private var nativeAutoRequest: NativeAutoRequest? = null
    private val nativeVadHandler = Handler(Looper.getMainLooper())
    private var nativeVadRunnable: Runnable? = null
    private val consultVadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
    private var consultVadWebSocket: WebSocket? = null
    private var consultVadAudioRecord: AudioRecord? = null
    private var consultVadAudioThread: Thread? = null
    @Volatile private var consultVadConnecting = false
    @Volatile private var consultVadRunning = false
    @Volatile private var consultVadStopping = false

    private data class NativeAutoRequest(
        val sessionId: String,
        val language: String,
        val mode: String,
    )

    // 신분증 촬영 화면(foreign_contact_intake.html)의 네이티브 카메라 미리보기 —
    // 위 마이크와 동일한 이유(http 보안 컨텍스트)로 웹 getUserMedia(video)가 막히므로,
    // CameraX 미리보기를 WebView 위에 페이지가 알려주는 위치·크기에 맞춰 겹쳐 그린다.
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraOverlayContainer: FrameLayout? = null
    private var cameraPreviewView: PreviewView? = null
    private var cameraGuideOverlay: CameraGuideOverlayView? = null
    private var cameraSwitchButton: Button? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    // CAMERA 런타임 권한을 아직 못 받은 상태에서 startPreview()가 호출된 경우, 승인
    // 결과가 온 뒤 같은 위치로 다시 시작할 수 있도록 요청 당시의 rect를 잠깐 들고 있는다.
    private var pendingCameraPreviewRect: FloatArray? = null

    // 상담원 화면(HUBONE deskchat)이 접수(/pt)·예약(/pt/reserve) 중 어느 화면을 보여줄지
    // 원격으로 지시하는 경로. 서버 재시작/앱 재시작과 무관하게 항상 접수 화면이 기본이다.
    private var currentScreenPath = SCREEN_PATH_CONTACT

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        showSystemNavigation()
        config = AgentConfig.load(this)
        applyScreenPolicy()
        // "wake"/open_contact/open_reservation 명령으로 불려나온 경우에만 화면을 깨운다
        // — 실제 겪은 문제: 이 플래그를 계속 켜둔 채로 두면(이전 구현) MainActivity 창이
        // 살아있는 한 sleep으로 화면을 꺼도 시스템이 곧바로 다시 켜버려서 "절전 누르면
        // 꺼졌다가 도로 켜짐" 버그가 났다. wakeScreenTransiently()가 필요한 순간에만
        // 켰다가 짧게 켠 뒤 스스로 꺼서, 다음 sleep 명령을 방해하지 않는다. config가
        // 로드된 뒤에 불러야 한다(wakeScreenTransiently가 applyScreenPolicy를 다시
        // 호출하는데, config가 lateinit이라 그 전에 부르면 초기화 예외가 난다).
        if (intent?.getStringExtra(EXTRA_SCREEN_COMMAND) != null) {
            wakeScreenTransiently()
        }
        if (intent?.getBooleanExtra(EXTRA_THEN_LAUNCH_DENTWEB, false) == true) {
            launchDentWebAfterResume()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(AudioBridge(), "HubOneAudio")
            addJavascriptInterface(CameraBridge(), "HubOneCamera")
            addJavascriptInterface(NavBridge(), "HubOneNav")
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
                        val needsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        if (!needsAudio && !needsCamera) {
                            request.deny()
                            return@runOnUiThread
                        }
                        val missing = buildList {
                            if (needsAudio && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (needsCamera && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                add(Manifest.permission.CAMERA)
                            }
                        }
                        if (missing.isEmpty()) {
                            request.grant(request.resources)
                        } else {
                            pendingPermissionRequest = request
                            ActivityCompat.requestPermissions(
                                this@MainActivity,
                                missing.toTypedArray(),
                                RECORD_AUDIO_PERMISSION_REQUEST
                            )
                        }
                    }
                }
            }
        }
        errorView = buildErrorView()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(buildCameraOverlay(), FrameLayout.LayoutParams(0, 0))
        root.addView(errorView, FrameLayout.LayoutParams(-1, -1))
        // 몰입 모드(SYSTEM_UI_FLAG_IMMERSIVE_STICKY)에서는 화면 맨 위 가장자리를 누르면
        // 시스템이 "숨겨진 상태바 다시 보이기" 제스처로 먼저 가로채서, 트리거가 정확히
        // 위쪽 가장자리에 붙어있으면 5초 누르기가 시작조차 안 될 수 있다(실제 겪은 문제:
        // "5초간 누르면 된다는데 안 됨"). 위쪽 가장자리에서 살짝 띄우고 히트박스도 키운다.
        root.addView(
            buildCornerTrigger(),
            FrameLayout.LayoutParams(260, 260, Gravity.TOP or Gravity.END).apply { topMargin = 160 }
        )
        setContentView(root)
        if (needsPermissionSetup()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        if (intent?.getBooleanExtra(EXTRA_SCREEN_POPUP, false) == true) {
            applyRequestedOrientation(intent.getStringExtra(EXTRA_SCREEN_ORIENTATION))
            loadConfiguredPage()
            showConsultPopup(intent.getStringExtra(EXTRA_SCREEN_PATH).orEmpty())
        } else {
            applyScreenExtra(intent)
            loadConfiguredPage()
        }
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
        // singleTask라 이미 떠 있는 상태에서 명령이 오면 onCreate가 아니라 여기로
        // 들어온다 — wake/open_contact/open_reservation 명령이면 여기서도 화면을 깨워야
        // 한다(실제 겪은 문제와 동일한 이유로, 필요할 때만 짧게).
        if (newIntent.getStringExtra(EXTRA_SCREEN_COMMAND) != null) {
            wakeScreenTransiently()
        }
        if (newIntent.getBooleanExtra(EXTRA_THEN_LAUNCH_DENTWEB, false)) {
            launchDentWebAfterResume()
        }
        if (::webView.isInitialized) {
            if (newIntent.getBooleanExtra(EXTRA_SCREEN_POPUP, false)) {
                applyRequestedOrientation(newIntent.getStringExtra(EXTRA_SCREEN_ORIENTATION))
                showConsultPopup(newIntent.getStringExtra(EXTRA_SCREEN_PATH).orEmpty())
            } else {
                applyScreenExtra(newIntent)
                loadConfiguredPage()
            }
        }
    }

    // "덴트웹" 명령이 왔을 때 화면이 잠겨있으면(잠금화면 위에 덴트웹을 바로 띄울 방법이
    // 없음 — 남의 앱이라 우리처럼 setShowWhenLocked를 걸 수 없다), CommandPollService가
    // 이 액티비티부터 잠금화면 위로 띄운 다음(wakeScreenTransiently) 여기서 이어서
    // 덴트웹을 실행한다 — 실제 겪은 문제: "잠금화면 상태에서 덴트웹 눌러도 잠금화면
    // 해제는 안됨". 우리 창이 실제로 잠금화면 위에 떠서 화면이 인터랙티브해진 뒤에
    // 실행해야 하므로 약간의 지연을 둔다.
    private fun launchDentWebAfterResume() {
        Handler(Looper.getMainLooper()).postDelayed({
            val pkg = AgentConfig.load(this).dentwebPackage.trim()
            if (pkg.isBlank()) return@postDelayed
            val launchIntent = try { packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
            if (launchIntent == null) return@postDelayed
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(launchIntent)
                CommandPollState.currentScreen = CommandPollState.SCREEN_DENTWEB
            } catch (_: Exception) {
                // 실패해도 최소한 우리 화면(/pt)은 잠금화면 위에 떠 있으니 조작은 가능하다.
            }
        }, 400)
    }

    // 화면을 깨우고 잠금을 실제로 해제한다 — 실제 겪은 문제 두 가지를 순서대로 고친
    // 결과다: (1) 이 플래그를 계속 켜둔 채로 두면 sleep(lockNow())과 충돌해서 절전이
    // 곧바로 풀렸다 → 일정 시간 뒤 자동으로 끄도록 고쳤더니, (2) "잠깐 보여주기"만 하고
    // 진짜 잠금해제는 안 한 채로 그 타이머가 꺼버려서 몇 초 뒤 잠금화면이 다시 올라와
    // "화면 나왔다가 대기화면으로 넘어감" 버그가 났다. requestDismissKeyguard()로 실제
    // 잠금해제까지 하고, 그 콜백이 끝난 뒤에만 정리한다 — 고정 타이머로 추측하지 않는다.
    private fun wakeScreenTransiently() {
        // CommandPollService.sleepDevice()가 절전 직전에 FLAG_KEEP_SCREEN_ON을 꺼뒀을 수
        // 있다("화면 항상 켜짐" 설정이 절전과 충돌하던 문제) — 다시 깨어나는 시점에
        // config대로 복원한다.
        applyScreenPolicy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguard = getSystemService(KeyguardManager::class.java)
            // 태블릿에 PIN/패턴 등 보안 잠금이 없는 키오스크 구성을 전제로 한다 — 그
            // 경우 사용자 조작 없이 바로 해제된다. 보안 잠금이 있으면 시스템이 알아서
            // 잠금해제 UI를 띄운다(우리가 대신 뚫을 방법은 없다 — 정상적인 보안 동작).
            keyguard?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { clearTransientWakeFlags() }
                override fun onDismissError() { clearTransientWakeFlags() }
                override fun onDismissCancelled() { clearTransientWakeFlags() }
            })
        } else {
            // API 26 미만은 requestDismissKeyguard가 없다 — 5초 뒤 정리(이 앱 minSdk가
            // 29라 사실상 도달하지 않는 경로).
            Handler(Looper.getMainLooper()).postDelayed({ clearTransientWakeFlags() }, 5_000)
        }
    }

    private fun clearTransientWakeFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun applyScreenExtra(source: Intent?) {
        val pagePath = source?.getStringExtra(EXTRA_SCREEN_PATH)?.trim().orEmpty()
        if (pagePath.isNotBlank() && pagePath.startsWith("/") && !pagePath.startsWith("//") && !pagePath.split("/").contains("..")) {
            currentScreenPath = pagePath
        }
        if (pagePath.isBlank()) {
            when (source?.getStringExtra(EXTRA_SCREEN_COMMAND)) {
                CommandPollState.SCREEN_RESERVATION -> currentScreenPath = SCREEN_PATH_RESERVATION
                CommandPollState.SCREEN_CONSENT -> currentScreenPath = SCREEN_PATH_CONSENT
                CommandPollState.SCREEN_CONTACT -> currentScreenPath = SCREEN_PATH_CONTACT
            }
        }
        applyRequestedOrientation(source?.getStringExtra(EXTRA_SCREEN_ORIENTATION))
    }

    private fun applyRequestedOrientation(value: String?) {
        when (value?.trim()?.lowercase(Locale.ROOT)) {
            "portrait" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "sensor" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
            "unspecified" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            // 명령 없이 사용자가 수동으로 이 화면에 돌아온 경우(예: 덴트웹에서 스와이프/뒤로가기)
            // 도 여기서 바로 잡아준다 — CommandPollService는 이 반대 방향을 추측하지 않고
            // MainActivity가 실제로 보여주는 화면을 기준으로 여기서 정확히 갱신한다.
        CommandPollState.currentScreen = when (currentScreenPath.substringBefore('?')) {
                SCREEN_PATH_RESERVATION -> CommandPollState.SCREEN_RESERVATION
                SCREEN_PATH_CONSENT -> CommandPollState.SCREEN_CONSENT
                else -> CommandPollState.SCREEN_CONTACT
            }
            val latest = AgentConfig.load(this)
            if (latest.baseUrl != config.baseUrl || latest.screenId != config.screenId) {
                config = latest
                applyScreenPolicy()
                loadConfiguredPage()
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    // 아래 세 개는 카메라 미리보기용 LifecycleOwner 디스패치 전용이다 — CameraX가
    // bindToLifecycle(this, ...)로 바인딩해두면 이 이벤트에 맞춰 프리뷰를 자동으로
    // 정지·재개하므로, 페이지를 벗어나거나 앱이 백그라운드로 가도 카메라를 계속
    // 붙잡고 있지 않는다(직접 unbindAll을 호출할 필요가 없다).
    override fun onPause() {
        super.onPause()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onStop() {
        super.onStop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        stopConsultServerVad()
        super.onDestroy()
        if (activeInstance === this) activeInstance = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
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

    // 신분증 촬영 화면의 .document-guide 컨테이너 자리에 정확히 겹쳐질 컨테이너 —
    // 안에 CameraX PreviewView(실제 영상)와 CameraGuideOverlayView(어두운 반투명
    // 배경 + 주황 테두리 가이드, 기존 웹 CSS ::after 가이드와 동일한 모양)를 쌓는다.
    // 페이지가 준비될 때까지는 크기 0으로 숨겨둔다.
    private fun buildCameraOverlay(): FrameLayout {
        val container = FrameLayout(this).apply {
            visibility = View.GONE
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val radius = 12f * resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
        val preview = PreviewView(this).apply {
            // PERFORMANCE 모드는 SurfaceView로 렌더링되는데, SurfaceView는 별도
            // 하드웨어 레이어로 합성되어 일부 기기(특히 이 안드로이드 원 태블릿)에서
            // View.scaleX 같은 일반 View 트랜스폼이 화면에 반영되지 않는다 — 전면
            // 카메라 좌우반전(scaleX=-1f)이 안 먹히던 실제 원인. COMPATIBLE 모드는
            // TextureView로 렌더링되어 일반 View 트랜스폼 파이프라인을 그대로 탄다.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val guide = CameraGuideOverlayView(this)
        val switchButton = Button(this).apply {
            text = "↻"
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            contentDescription = "Switch camera"
            setOnClickListener { switchCameraInternal() }
        }
        container.addView(preview, FrameLayout.LayoutParams(-1, -1))
        container.addView(guide, FrameLayout.LayoutParams(-1, -1))
        container.addView(switchButton, FrameLayout.LayoutParams(
            (52 * resources.displayMetrics.density).toInt(),
            (52 * resources.displayMetrics.density).toInt(),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (8 * resources.displayMetrics.density).toInt()
            rightMargin = (8 * resources.displayMetrics.density).toInt()
        })
        cameraOverlayContainer = container
        cameraPreviewView = preview
        cameraGuideOverlay = guide
        cameraSwitchButton = switchButton
        return container
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
            CommandPollState.currentScreen = when (currentScreenPath.substringBefore('?')) {
            SCREEN_PATH_RESERVATION -> CommandPollState.SCREEN_RESERVATION
            SCREEN_PATH_CONSENT -> CommandPollState.SCREEN_CONSENT
            else -> CommandPollState.SCREEN_CONTACT
        }
        val base = config.baseUrl.trim().trimEnd('/')
        val screen = config.screenId.trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID }
        val separator = if (currentScreenPath.contains("?")) "&" else "?"
        val target = "$base$currentScreenPath${separator}screen_id=${Uri.encode(screen)}"
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

    // 통역상담은 접수 메인 화면을 덮어쓰지 않고 별도 앱 팝업에서 실행한다. 팝업 WebView에도
    // HubOneAudio를 주입하므로 HTTP WebView에서 웹 마이크가 막혀도 네이티브 VAD가 동작한다.
    private fun showConsultPopup(pagePath: String) {
        if (pagePath.isBlank() || !pagePath.startsWith("/") || pagePath.startsWith("//") || pagePath.split("/").contains("..")) return
        val base = config.baseUrl.trim().trimEnd('/')
        val screen = config.screenId.trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID }
        val separator = if (pagePath.contains("?")) "&" else "?"
        val target = "$base$pagePath${separator}screen_id=${Uri.encode(screen)}"
        if (!isAllowed(Uri.parse(target))) return

        consultPopup?.dismiss()
        val dialog = Dialog(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val popupWeb = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(AudioBridge(), "HubOneAudio")
            addJavascriptInterface(NavBridge(), "HubOneNav")
            webViewClient = SafeWebViewClient()
        }
        val close = Button(this).apply {
            text = "닫기"
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(popupWeb, FrameLayout.LayoutParams(-1, -1))
        root.addView(close, FrameLayout.LayoutParams(112, 68, Gravity.TOP or Gravity.END).apply { topMargin = 12; rightMargin = 12 })
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (consultPopupWebView === popupWeb) consultPopupWebView = null
            if (consultPopup === dialog) consultPopup = null
            if (nativeAutoRequest?.mode in setOf("consult_kiosk", "consult_single")) stopNativeAutoRecording()
            popupWeb.destroy()
        }
        consultPopup = dialog
        consultPopupWebView = popupWeb
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), (resources.displayMetrics.heightPixels * 0.90f).toInt())
        popupWeb.loadUrl(target)
        popupWeb.requestFocus(View.FOCUS_DOWN)
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

    private fun needsPermissionSetup(): Boolean {
        val cameraMissing = checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        val audioMissing = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        val usageMissing = !hasUsageAccess()
        val overlayMissing = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)
        return cameraMissing || audioMissing || usageMissing || overlayMissing
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        return appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun applyScreenPolicy() {
        if (config.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // CommandPollService.sleepDevice()가 lockNow() 직전에 호출한다 — "화면 항상 켜짐"
    // 설정(config.keepScreenOn, 기본 true)이 FLAG_KEEP_SCREEN_ON으로 켜져 있으면
    // 절전(lockNow)과 정면 충돌해서 화면이 곧바로 다시 켜졌다(실제 겪은 문제: "절전
    // 눌러도 잠금화면이 꺼졌다가 다시 켜짐"). 다시 깨어날 때는 wakeScreenTransiently()가
    // applyScreenPolicy()를 다시 호출해 config대로 복원한다.
    fun clearKeepScreenOnForSleep() {
        try { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) { /* 무시 */ }
    }

    private fun showSystemNavigation() {
        // 홈 위젯으로 곧바로 이동할 수 있도록 Android 하단 내비게이션은 항상 보인다.
        // 상담 웹 화면의 세로 공간을 최대한 유지하기 위해 상단 상태바만 숨긴다.
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // CommandPollService의 focused 보고에 쓰인다 — 허브원 보드 탭 상태 표시용.
        CommandPollState.windowFocused = hasFocus
        if (hasFocus) showSystemNavigation()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
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
        if (requestCode == CAMERA_PREVIEW_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            val rect = pendingCameraPreviewRect
            pendingCameraPreviewRect = null
            if (granted && rect != null) {
                startCameraPreviewInternal(rect[0], rect[1], rect[2], rect[3])
            } else if (!granted) {
                notifyJsCameraEvent("preview_error", "permission_denied")
            }
            return
        }
        if (requestCode != RECORD_AUDIO_PERMISSION_REQUEST) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        // 이 요청 코드는 두 경로가 같이 쓴다 — 웹 getUserMedia(PermissionRequest)와
        // 네이티브 HubOneAudio.startRecording(). 둘 중 실제로 대기 중이던 쪽만 처리한다.
        val webRequest = pendingPermissionRequest
        pendingPermissionRequest = null
        if (webRequest != null) {
            if (granted) webRequest.grant(webRequest.resources) else webRequest.deny()
        }

        if (pendingNativeAudioStart) {
            pendingNativeAudioStart = false
            val autoRequest = pendingNativeAutoRequest
            pendingNativeAutoRequest = null
            if (granted) {
                if (autoRequest != null) {
                    if (isConsultServerVadMode(autoRequest.mode)) startConsultServerVad(autoRequest)
                    else startNativeAutoRecordingInternal(autoRequest)
                } else startNativeRecordingInternal()
            } else {
                notifyJsAudioEvent("start_error", "permission_denied")
            }
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

        // 통역상담 kiosk는 PCM만 원내 서버로 보내고 서버 Silero가 발화 경계를
        // 판정한다. 서버 연결이 실패한 경우에만 아래의 기존 MediaRecorder 진폭 VAD로
        // 자동 폴백한다. 다른 예약/접수 모드는 기존 동작을 그대로 유지한다.
        @JavascriptInterface
        fun startAutoRecording(sessionId: String, language: String, mode: String) {
            runOnUiThread {
                val request = NativeAutoRequest(sessionId, language, mode)
                if (isConsultServerVadMode(request.mode) && (consultVadConnecting || consultVadRunning)) {
                    if (nativeAutoRequest == request) return@runOnUiThread
                    // 재사용된 팝업이 patient_view와 단일 마이크 상담 사이를 이동하면
                    // 이전 WebSocket의 capture_mode를 그대로 쓰지 않도록 교체한다.
                    stopNativeAutoRecording()
                }
                nativeAutoRequest = request
                if (!isConsultServerVadMode(request.mode)) resetNativeVadCalibration()
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingNativeAudioStart = true
                    pendingNativeAutoRequest = request
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        RECORD_AUDIO_PERMISSION_REQUEST
                    )
                    return@runOnUiThread
                }
                if (isConsultServerVadMode(request.mode)) startConsultServerVad(request)
                else startNativeAutoRecordingInternal(request)
            }
        }

        // mode: "reservation"(/pt/reserve, 기존 동작 — sessionId 사용) |
        // "contact"(/pt, 신규 — screenId를 sessionId 자리에 넘겨받는다). 페이지 쪽
        // (foreign_reservation_intake.html / foreign_contact_intake.html) 둘 다
        // 이 세 번째 인자를 명시적으로 넘긴다.
        @JavascriptInterface
        fun stopAndUpload(sessionId: String, language: String, mode: String) {
            android.util.Log.d("HubOneVoice", "stopAndUpload called sessionId=$sessionId mode=$mode")
            runOnUiThread {
                val request = NativeAutoRequest(sessionId, language, mode)
                if (isConsultServerVadMode(mode) && (consultVadConnecting || consultVadRunning)) {
                    val sent = consultVadWebSocket?.send(JSONObject().put("type", "finish").toString()) == true
                    if (!sent) fallbackToLocalVad(request, "manual_finish_send_failed")
                } else {
                    stopNativeRecordingAndUpload(request, false)
                }
            }
        }

        @JavascriptInterface
        fun resetAutoRecording() {
            if (!consultVadRunning) return
            consultVadWebSocket?.send(JSONObject().put("type", "reset").toString())
        }

        @JavascriptInterface
        fun stopAutoRecording() {
            runOnUiThread {
                stopNativeAutoRecording()
                notifyJsAudioEvent("idle", "native_single_stopped")
            }
        }
    }

    // 페이지(foreign_contact_intake.html의 "내국인이신가요?" 버튼)가 window.HubOneNav로
    // 호출하는 네이티브 브릿지 — 태블릿 자체를 덴트웹 고객용 앱으로 전환한다.
    // CommandPollService.launchDentWeb()(서버가 미는 return_to_dentweb 명령용)과 로직은
    // 같지만, 이쪽은 환자가 웹뷰에서 직접 누른 즉시 반응이라 서버 왕복이 필요 없다.
    private inner class NavBridge {
        @JavascriptInterface
        fun closeConsult() {
            runOnUiThread { consultPopup?.dismiss() }
        }

        @JavascriptInterface
        fun launchDentWeb() {
            runOnUiThread {
                val pkg = config.dentwebPackage.trim()
                if (pkg.isBlank()) {
                    notifyJsNavEvent("error", "덴트웹 앱 패키지명이 설정되지 않았습니다.")
                    return@runOnUiThread
                }
                val intent = try {
                    packageManager.getLaunchIntentForPackage(pkg)
                } catch (_: Exception) { null }
                if (intent == null) {
                    notifyJsNavEvent("error", "덴트웹 앱을 찾을 수 없습니다: $pkg")
                    return@runOnUiThread
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                try {
                    startActivity(intent)
                    CommandPollState.currentScreen = CommandPollState.SCREEN_DENTWEB
                    notifyJsNavEvent("launched", "")
                } catch (e: Exception) {
                    notifyJsNavEvent("error", "덴트웹 앱 실행 실패: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
                }
            }
        }
    }

    private fun notifyJsNavEvent(status: String, message: String) {
        val js = "window.__hubOneNavEvent && window.__hubOneNavEvent(${JSONObject.quote(status)}, ${JSONObject.quote(message)});"
        webView.evaluateJavascript(js, null)
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

    @SuppressLint("MissingPermission")
    private fun startConsultServerVad(request: NativeAutoRequest) {
        if (consultVadConnecting || consultVadRunning) return
        nativeAutoRequest = request
        consultVadStopping = false
        consultVadConnecting = true
        val base = config.baseUrl.trim().trimEnd('/')
        val wsBase = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://${base.substringAfter("://")}"
            base.startsWith("http://", ignoreCase = true) -> "ws://${base.substringAfter("://")}"
            else -> "ws://$base"
        }
        val screen = URLEncoder.encode(request.sessionId, Charsets.UTF_8.name())
        val language = URLEncoder.encode(request.language, Charsets.UTF_8.name())
        val captureMode = if (request.mode == "consult_single") "consult_single" else "patient_view"
        val url = "$wsBase/api/consult/kiosk/vad-stream?screen_id=$screen&language=$language&capture_mode=$captureMode"
        val wsRequest = Request.Builder().url(url).build()
        android.util.Log.i("HubOneVoice", "server Silero VAD connecting url=$url")
        consultVadWebSocket = consultVadClient.newWebSocket(wsRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                android.util.Log.i("HubOneVoice", "server Silero VAD websocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val payload = try { JSONObject(text) } catch (_: Exception) { return }
                when (payload.optString("type")) {
                    "ready" -> runOnUiThread {
                        consultVadConnecting = false
                        if (!consultVadRunning) startConsultPcmCapture(request)
                        else notifyJsAudioEvent("started", "server_silero_ready")
                    }
                    "finish_hint" -> runOnUiThread {
                        notifyJsAudioEvent(if (payload.optBoolean("active")) "finish_hint" else "finish_hint_clear", "")
                    }
                    "segment_result" -> runOnUiThread {
                        notifyJsAudioEvent("finish_hint_clear", "")
                        val ok = payload.optBoolean("ok")
                        val error = payload.optString("error")
                        // 빈 구간/중복은 정상적인 VAD 결과다. 스트림을 끊지 않고 다음
                        // 발화를 계속 기다리도록 업로드 완료와 listening 재개를 알린다.
                        if (ok || error == "empty_transcript" || error == "empty_segment" || error == "segment_too_short" || error == "consult_on_hold") {
                            notifyJsAudioEvent("uploaded", error)
                            notifyJsAudioEvent("started", "server_silero_ready")
                        } else if (error == "not_patient_turn") {
                            notifyJsAudioEvent("uploaded", error)
                        } else {
                            notifyJsAudioEvent("upload_error", error.ifBlank { "server_vad_segment_failed" })
                            fallbackToLocalVad(request, error.ifBlank { "server_vad_segment_failed" })
                        }
                    }
                    "paused" -> runOnUiThread { notifyJsAudioEvent("finish_hint_clear", "") }
                    "error" -> runOnUiThread {
                        fallbackToLocalVad(request, payload.optString("error", "server_vad_error"))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.w("HubOneVoice", "server Silero VAD failed: ${t.message}", t)
                runOnUiThread {
                    if (!consultVadStopping && nativeAutoRequest == request) {
                        fallbackToLocalVad(request, t.message ?: "server_vad_disconnected")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    if (!consultVadStopping && nativeAutoRequest == request) {
                        fallbackToLocalVad(request, "server_vad_closed_$code")
                    }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startConsultPcmCapture(request: NativeAutoRequest) {
        if (consultVadRunning || nativeAutoRequest != request) return
        val minBuffer = AudioRecord.getMinBufferSize(
            CONSULT_PCM_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            fallbackToLocalVad(request, "audio_record_buffer_error")
            return
        }
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CONSULT_PCM_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, CONSULT_PCM_CHUNK_BYTES * 4))
                .build()
        } catch (e: Exception) {
            fallbackToLocalVad(request, e.message ?: "audio_record_create_failed")
            return
        }
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            try { recorder.release() } catch (_: Exception) { }
            fallbackToLocalVad(request, e.message ?: "audio_record_start_failed")
            return
        }
        consultVadAudioRecord = recorder
        consultVadRunning = true
        consultVadConnecting = false
        notifyJsAudioEvent("started", "server_silero_ready")
        consultVadAudioThread = Thread {
            val buffer = ByteArray(CONSULT_PCM_CHUNK_BYTES)
            try {
                while (consultVadRunning && nativeAutoRequest == request) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    var peak = 0
                    var i = 0
                    while (i + 1 < count) {
                        val sample = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)
                        peak = maxOf(peak, abs(sample.toShort().toInt()))
                        i += 2
                    }
                    runOnUiThread { notifyJsAudioLevel(peak) }
                    if (consultVadWebSocket?.send(buffer.toByteString(0, count)) != true) {
                        throw IllegalStateException("server_vad_send_failed")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("HubOneVoice", "PCM stream stopped: ${e.message}", e)
                runOnUiThread {
                    if (!consultVadStopping && nativeAutoRequest == request) {
                        fallbackToLocalVad(request, e.message ?: "pcm_stream_failed")
                    }
                }
            } finally {
                try { recorder.stop() } catch (_: Exception) { }
                try { recorder.release() } catch (_: Exception) { }
                if (consultVadAudioRecord === recorder) consultVadAudioRecord = null
            }
        }.apply {
            name = "HubOneConsultPcm"
            isDaemon = true
            start()
        }
    }

    private fun stopConsultServerVad() {
        consultVadStopping = true
        consultVadConnecting = false
        consultVadRunning = false
        try { consultVadAudioRecord?.stop() } catch (_: Exception) { }
        consultVadAudioThread?.interrupt()
        consultVadAudioThread = null
        consultVadAudioRecord = null
        try { consultVadWebSocket?.close(1000, "client_stop") } catch (_: Exception) { }
        consultVadWebSocket = null
    }

    private fun fallbackToLocalVad(request: NativeAutoRequest, reason: String) {
        if (nativeAutoRequest != request) return
        if (request.mode == "consult_single") {
            android.util.Log.e("HubOneVoice", "native single mic server VAD unavailable: $reason")
            stopConsultServerVad()
            notifyJsAudioEvent("finish_hint_clear", "")
            notifyJsAudioEvent("start_error", reason)
            return
        }
        android.util.Log.w("HubOneVoice", "falling back to local amplitude VAD: $reason")
        stopConsultServerVad()
        resetNativeVadCalibration()
        notifyJsAudioEvent("finish_hint_clear", "")
        nativeVadHandler.postDelayed({
            if (nativeAutoRequest == request && nativeMediaRecorder == null) {
                startNativeAutoRecordingInternal(request)
            }
        }, NATIVE_VAD_RESTART_MS)
    }

    // 고정 진폭 임계값(NATIVE_VAD_AMPLITUDE_THRESHOLD) 하나로는 태블릿-환자 거리와
    // 진료실 소음 수준이 제각각인 걸 감당 못 한다 — 가까우면 숨소리도 발화로 잡고,
    // 멀거나 시끄러우면 아예 못 잡는다(실사용 지적: gain 필요해보임). 세션 시작
    // 시점의 무음 구간 진폭 평균("바닥")을 재서 그 위로 동적 임계값을 잡는다 — 매
    // 발화 사이클마다(discardNativeRecording/stopNativeRecordingAndUpload가 자체
    // 재시작) 리셋되면 안 되므로, 진짜 세션 시작 지점(AudioBridge.startAutoRecording)
    // 에서만 리셋하고 그 사이 내부 재시작들은 이전 보정값을 계속 쓴다.
    private var nativeVadThreshold = NATIVE_VAD_AMPLITUDE_THRESHOLD
    private var nativeVadNoiseSum = 0L
    private var nativeVadNoiseCount = 0
    private var nativeVadCalibrationDone = false

    private fun resetNativeVadCalibration() {
        nativeVadThreshold = NATIVE_VAD_AMPLITUDE_THRESHOLD
        nativeVadNoiseSum = 0L
        nativeVadNoiseCount = 0
        nativeVadCalibrationDone = false
    }

    private fun startNativeAutoRecordingInternal(request: NativeAutoRequest) {
        nativeAutoRequest = request
        stopNativeVadMonitor()
        startNativeRecordingInternal()
        if (nativeMediaRecorder == null) return

        val startedAt = System.currentTimeMillis()
        var voicedFrames = 0
        var hadSpeech = false
        var silenceSince = 0L
        val monitor = object : Runnable {
            override fun run() {
                val recorder = nativeMediaRecorder
                if (recorder == null || nativeAutoRequest != request) return
                val elapsed = System.currentTimeMillis() - startedAt
                val amplitude = try { recorder.maxAmplitude } catch (_: Exception) { 0 }
                // 웹뷰(패널) 쪽 시그널 바가 실제 입력 레벨을 전혀 못 받고 있었다(실사용
                // 지적) — VAD 판정용으로 이미 80ms마다 샘플링하던 진폭을 그대로 JS에도
                // 흘려보낸다. maxAmplitude는 이 호출 이후로 리셋되므로 VAD 판정 로직보다
                // 먼저 읽어야 한다(이미 위에서 읽어둠).
                notifyJsAudioLevel(amplitude)
                // 세션당 한 번만 보정한다 — 이 발화 사이클이 짧게 끝나 표본이 부족하면
                // (voicedFrames >= 2로 hadSpeech 처리 안 됐을 때) 다음 사이클(재시작)에서
                // nativeVadCalibrationDone이 여전히 false라 계속 표본을 모은다.
                if (!nativeVadCalibrationDone) {
                    // 정적 임계값을 상한 필터로 써서, 이미 말하는 중인 표본이 바닥
                    // 평균에 섞이는 걸 막는다(브라우저 폴백 경로의 _startVadMonitor와
                    // 동일한 방식).
                    if (amplitude < NATIVE_VAD_AMPLITUDE_THRESHOLD) {
                        nativeVadNoiseSum += amplitude
                        nativeVadNoiseCount += 1
                    }
                    if (nativeVadNoiseCount >= 6 || elapsed >= 800L) {
                        val floor = if (nativeVadNoiseCount > 0) (nativeVadNoiseSum / nativeVadNoiseCount) else (NATIVE_VAD_AMPLITUDE_THRESHOLD / 3).toLong()
                        // 조용한 방에서는 바닥 소음이 낮아서 floor*2.4가 원래 고정값(900)
                        // 보다도 낮게 나와, 오히려 더 민감해져 숨소리/손 닿는 소리 같은
                        // 잡음에도 녹음이 시작되는 문제가 실사용으로 확인됐다(17번 중
                        // 16번이 빈 전사) — 시끄러운 방에서는 threshold를 "올려서" 덜
                        // 민감하게 만드는 용도로만 쓰고, 원래 고정값보다 낮아지는 건
                        // 막는다(조용한 방/원거리 케이스는 이 값만으로는 못 돕고 실제
                        // 게인 증폭이 따로 필요함 — 별도 작업).
                        nativeVadThreshold = (floor * 2.4).toInt().coerceIn(NATIVE_VAD_AMPLITUDE_THRESHOLD, NATIVE_VAD_AMPLITUDE_THRESHOLD * 4)
                        nativeVadCalibrationDone = true
                        android.util.Log.d("HubOneVoice", "VAD threshold calibrated floor=$floor threshold=$nativeVadThreshold")
                    }
                }
                if (amplitude >= nativeVadThreshold) {
                    voicedFrames += 1
                    silenceSince = 0L
                    // 스파이크 하나(순간적 잡음)로 바로 발화 처리되는 걸 막기 위해
                    // 연속 프레임 요구치를 2→3으로 늘린다(80ms 폴링 기준 최소
                    // ~240ms 지속돼야 발화로 인정).
                    if (voicedFrames >= 3) hadSpeech = true
                } else if (hadSpeech) {
                    if (silenceSince == 0L) silenceSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - silenceSince >= NATIVE_VAD_SILENCE_MS) {
                        stopNativeRecordingAndUpload(request, true)
                        return
                    }
                }
                // 최대 발화 시간은 무음 분기 안에 두면 안 된다. 에어컨/음악처럼
                // 배경 소음이 계속 동적 임계값보다 크면 silenceSince가 계속 0으로
                // 리셋되어, 기존에는 이 제한이 사실상 영원히 실행되지 않았다.
                // 보정된 배경 소음 임계값은 발화 시작/종료 판정에 그대로 쓰되, 한
                // 녹음 조각의 상한은 진폭과 무관하게 항상 적용한다.
                if (hadSpeech && elapsed >= NATIVE_VAD_MAX_SPEECH_MS) {
                    stopNativeRecordingAndUpload(request, true)
                    return
                }
                if (!hadSpeech && elapsed >= NATIVE_VAD_IDLE_MS) {
                    discardNativeRecording("idle")
                    return
                }
                nativeVadHandler.postDelayed(this, NATIVE_VAD_POLL_MS)
            }
        }
        nativeVadRunnable = monitor
        nativeVadHandler.postDelayed(monitor, NATIVE_VAD_POLL_MS)
    }

    private fun stopNativeVadMonitor() {
        nativeVadRunnable?.let { nativeVadHandler.removeCallbacks(it) }
        nativeVadRunnable = null
    }

    private fun stopNativeAutoRecording() {
        nativeAutoRequest = null
        stopConsultServerVad()
        stopNativeVadMonitor()
        val recorder = nativeMediaRecorder
        val file = nativeRecordingFile
        nativeMediaRecorder = null
        nativeRecordingFile = null
        try { recorder?.stop() } catch (_: Exception) { /* already stopped */ }
        try { recorder?.release() } catch (_: Exception) { /* no-op */ }
        try { file?.delete() } catch (_: Exception) { /* no-op */ }
    }

    private fun discardNativeRecording(event: String) {
        stopNativeVadMonitor()
        val recorder = nativeMediaRecorder
        val file = nativeRecordingFile
        nativeMediaRecorder = null
        nativeRecordingFile = null
        try { recorder?.stop() } catch (_: Exception) { /* no voiced audio is expected */ }
        try { recorder?.release() } catch (_: Exception) { /* no-op */ }
        try { file?.delete() } catch (_: Exception) { /* no-op */ }
        notifyJsAudioEvent(event, "")
        nativeAutoRequest?.let { request ->
            nativeVadHandler.postDelayed({ if (nativeAutoRequest == request) startNativeAutoRecordingInternal(request) }, NATIVE_VAD_RESTART_MS)
        }
    }

    private fun stopNativeRecordingAndUpload(request: NativeAutoRequest, restartAfterUpload: Boolean) {
        stopNativeVadMonitor()
        val recorder = nativeMediaRecorder
        val file = nativeRecordingFile
        nativeMediaRecorder = null
        nativeRecordingFile = null
        if (recorder == null || file == null) {
            notifyJsAudioEvent("upload_error", "not_recording")
            return
        }
        try {
            recorder.stop()
            recorder.release()
        } catch (e: Exception) {
            notifyJsAudioEvent("upload_error", e.message ?: "stop_failed")
            return
        }
        Thread {
            val (ok, message) = uploadVoiceFile(request.sessionId, request.language, file, request.mode)
            file.delete()
            runOnUiThread {
                notifyJsAudioEvent(if (ok) "uploaded" else "upload_error", message)
                if (restartAfterUpload && nativeAutoRequest == request) {
                    nativeVadHandler.postDelayed({
                        if (nativeAutoRequest == request) startNativeAutoRecordingInternal(request)
                    }, NATIVE_VAD_RESTART_MS)
                }
            }
        }.start()
    }

    private fun notifyJsAudioEvent(status: String, message: String) {
        val js = "window.__hubOneVoiceEvent && window.__hubOneVoiceEvent(${JSONObject.quote(status)}, ${JSONObject.quote(message)});"
        webView.evaluateJavascript(js, null)
        consultPopupWebView?.evaluateJavascript(js, null)
    }

    private fun isConsultServerVadMode(mode: String): Boolean =
        mode == "consult_kiosk" || mode == "consult_single"

    // 태블릿 통역(patient_view.html) 시그널 바용 — startAutoRecording()의 VAD 폴링 중
    // 샘플링한 진폭을 그대로 전달한다. 상태 전환용 __hubOneVoiceEvent와 분리된 별도
    // 콜백으로 둬서, 80ms마다 오는 이 값이 상태 머신 로직과 섞이지 않게 한다.
    private fun notifyJsAudioLevel(amplitude: Int) {
        val js = "window.__hubOneAudioLevel && window.__hubOneAudioLevel($amplitude);"
        webView.evaluateJavascript(js, null)
        consultPopupWebView?.evaluateJavascript(js, null)
    }

    // 페이지(foreign_contact_intake.html)가 window.HubOneCamera로 호출하는 네이티브
    // 카메라 미리보기 브릿지. startPreview(x, y, width, height)는 페이지 안의
    // .document-guide 영역(CSS px, viewport 기준)을 알려주면 그 자리에 실시간 미리보기를
    // 겹쳐 그리고, capture()로 촬영하면 JPEG를 base64 data URL로 콜백 전달한다.
    // 결과는 window.__hubOneCameraEvent(status, payload) JS 콜백으로 비동기 통지한다
    // (status: "preview_started"|"preview_error"|"captured"|"capture_error").
    private inner class CameraBridge {
        @JavascriptInterface
        fun startPreview(x: Float, y: Float, width: Float, height: Float) {
            runOnUiThread {
                // 신분증 촬영은 태블릿 전면 카메라를 기본으로 연다.
                cameraFacing = CameraSelector.LENS_FACING_FRONT
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingCameraPreviewRect = floatArrayOf(x, y, width, height)
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.CAMERA),
                        CAMERA_PREVIEW_PERMISSION_REQUEST
                    )
                    return@runOnUiThread
                }
                startCameraPreviewInternal(x, y, width, height)
            }
        }

        @JavascriptInterface
        fun updatePreviewRect(x: Float, y: Float, width: Float, height: Float) {
            runOnUiThread { positionCameraOverlay(x, y, width, height) }
        }

        @JavascriptInterface
        fun stopPreview() {
            runOnUiThread { stopCameraPreviewInternal() }
        }

        @JavascriptInterface
        fun capture() {
            runOnUiThread { captureDocumentPhotoInternal() }
        }

        @JavascriptInterface
        fun switchCamera() {
            runOnUiThread { switchCameraInternal() }
        }
    }

    private fun switchCameraInternal() {
        cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val container = cameraOverlayContainer ?: return
        val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return
        val density = resources.displayMetrics.density
        startCameraPreviewInternal(
            lp.leftMargin / density, lp.topMargin / density,
            lp.width / density, lp.height / density
        )
    }

    // x/y/width/height는 페이지가 getBoundingClientRect()로 알려주는 CSS px다 — 뷰포트가
    // width=device-width, initial-scale=1(확대축소 없음)이므로 density를 곱하면 그대로
    // 기기 픽셀 좌표가 된다.
    private fun positionCameraOverlay(x: Float, y: Float, width: Float, height: Float) {
        val container = cameraOverlayContainer ?: return
        val density = resources.displayMetrics.density
        val lp = (container.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
        lp.width = (width * density).toInt().coerceAtLeast(1)
        lp.height = (height * density).toInt().coerceAtLeast(1)
        lp.leftMargin = (x * density).toInt()
        lp.topMargin = (y * density).toInt()
        lp.gravity = Gravity.TOP or Gravity.START
        container.layoutParams = lp
        cameraGuideOverlay?.invalidate()
    }

    private fun startCameraPreviewInternal(x: Float, y: Float, width: Float, height: Float) {
        val container = cameraOverlayContainer ?: return
        val preview = cameraPreviewView ?: return
        positionCameraOverlay(x, y, width, height)
        // 전면 카메라 라이브 프리뷰를 거울처럼 반전해봤지만, 반전된 화면으로는
        // 오히려 실제 카메라 방향과 안 맞아 신분증에 맞추기 더 어렵다는 피드백으로
        // 되돌렸다(preview.scaleX 반전 제거). 촬영 후 확인화면만 웹 쪽에서 반전한다.
        preview.scaleX = 1f
        container.visibility = View.VISIBLE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()
                val previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }
                val captureUseCase = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val preferredSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()
                val fallbackSelector = if (cameraFacing == CameraSelector.LENS_FACING_FRONT)
                    CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val selector = if (provider.hasCamera(preferredSelector)) preferredSelector else fallbackSelector
                val camera: Camera = provider.bindToLifecycle(this, selector, previewUseCase, captureUseCase)
                imageCapture = captureUseCase
                // 전면 카메라는 신분증 전체를 안내선 안에 채우려면 너무 가까이 대야 해서
                // 초점이 안 맞는 문제가 실제로 있었다 — 프리뷰를 2배 줌해서 같은 화면 채움
                // 정도를 유지하면서 카메라와의 거리는 초점이 맞는 범위로 더 벌릴 수 있게
                // 한다. 후면 카메라는 이미 초점 거리가 넉넉해 이 문제가 없고, 오히려 화각이
                // 좁아져 안내선 안에 다 못 담는 문제가 생기므로 1배(줌 없음)를 유지한다
                // (실제 지적 사항). 기기가 지원하는 최대 줌보다 크게 요청하면 조용히
                // 실패하므로 상한을 맞춘다.
                if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                    try {
                        val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                        if (maxZoom > 1f) camera.cameraControl.setZoomRatio(minOf(2f, maxZoom))
                    } catch (_: Exception) {
                        // 줌 미지원 기기 — 촬영 자체는 그대로 진행한다.
                    }
                }
                notifyJsCameraEvent(
                    "preview_started",
                    if (cameraFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"
                )
            } catch (e: Exception) {
                container.visibility = View.GONE
                notifyJsCameraEvent("preview_error", e.message ?: "camera_bind_failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraPreviewInternal() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
            // no-op — 이미 unbind된 상태 등은 무시해도 안전하다.
        }
        imageCapture = null
        cameraOverlayContainer?.visibility = View.GONE
    }

    private fun captureDocumentPhotoInternal() {
        val capture = imageCapture
        if (capture == null) {
            notifyJsCameraEvent("capture_error", "not_started")
            return
        }
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    // 촬영 순간의 실제 선택 방향을 사진과 함께 넘긴다. WebView 쪽에서
                    // 이전 preview 이벤트 상태에 의존하지 않고 환자용 미리보기를 확실히
                    // 좌우반전할 수 있다.
                    val payload = JSONObject().apply {
                        put("image", imageProxyToJpegDataUrl(image))
                        put("facing", if (cameraFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back")
                    }.toString()
                    notifyJsCameraEvent("captured", payload)
                } catch (e: Exception) {
                    notifyJsCameraEvent("capture_error", e.message ?: "encode_failed")
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                notifyJsCameraEvent("capture_error", exception.message ?: "capture_failed")
            }
        })
    }

    // ImageCapture 기본 출력은 JPEG이므로 planes[0]을 그대로 디코딩하면 된다. 센서
    // 방향(rotationDegrees)만큼 회전시키고, 업로드 크기를 페이지 쪽 기존 웹 캡처
    // 경로(_takeDocumentPhoto의 canvas maxWidth)와 맞춰 1600px로 다운스케일한다.
    private fun imageProxyToJpegDataUrl(image: ImageProxy): String {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("decode_failed")
        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        val maxWidth = 1600
        if (bitmap.width > maxWidth) {
            val scale = maxWidth.toFloat() / bitmap.width
            bitmap = Bitmap.createScaledBitmap(bitmap, maxWidth, (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    private fun notifyJsCameraEvent(status: String, payload: String) {
        val js = "window.__hubOneCameraEvent && window.__hubOneCameraEvent(${JSONObject.quote(status)}, ${JSONObject.quote(payload)});"
        webView.evaluateJavascript(js, null)
    }

    // .document-guide와 동일한 가이드 모양(어두운 반투명 배경 + 12%/7% inset의 주황
    // 테두리)을 네이티브로 그린다 — 기존 웹 CSS(.document-guide::after)의 값을 그대로
    // 옮겨왔다. 네이티브 프리뷰가 WebView 위에 얹히면서 그 CSS는 가려지므로 대신 이걸 쓴다.
    private class CameraGuideOverlayView(context: android.content.Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#ff9800")
            strokeWidth = 3f * density
        }
        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((0.22f * 255).toInt(), 0, 0, 0)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val insetX = width * 0.07f
            val insetY = height * 0.12f
            val holeRect = RectF(insetX, insetY, width - insetX, height - insetY)
            val radius = 12f * density
            val full = Path().apply { addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW) }
            val hole = Path().apply { addRoundRect(holeRect, radius, radius, Path.Direction.CW) }
            full.op(hole, Path.Op.DIFFERENCE)
            canvas.drawPath(full, scrimPaint)
            canvas.drawRoundRect(holeRect, radius, radius, borderPaint)
        }
    }

    // 세션/화면 API에 직접 멀티파트 업로드한다 — deskchat/api/foreign_reservation.py와
    // patients.py의 POST .../voice/transcribe와 동일한 필드(audio, language)를 맞춘다.
    // mode="reservation"이면 sessionIdOrScreenId는 예약 세션 URL 경로(/pt/reserve 기존
    // 동작), mode="contact"이면 접수 태블릿 화면(screen_id) form 필드로 보낸다(/pt 신규).
    private fun uploadVoiceFile(sessionIdOrScreenId: String, language: String, file: File, mode: String): Pair<Boolean, String> {
        var conn: HttpURLConnection? = null
        // HttpURLConnection의 connectTimeout/readTimeout은 연결 수립과 응답 대기만
        // 커버하고, 업로드(쓰기) 구간 자체가 멈추는 경우는 막아주지 않는다 — 실제 겪은
        // 문제: 마이크 녹음 종료 후 "상담원에게 전달 중..."에서 화면이 영원히 멈춤
        // (네트워크가 뚝 끊기지 않고 그냥 정체되면 write()가 무한 대기할 수 있음).
        // 별도 워치독으로 일정 시간 뒤 연결을 강제로 끊어 예외를 발생시켜서 항상
        // uploaded/upload_error 콜백이 나가도록 한다.
        val watchdogHandler = Handler(Looper.getMainLooper())
        val watchdog = Runnable {
            android.util.Log.w("HubOneVoice", "upload watchdog fired — forcing disconnect")
            try { conn?.disconnect() } catch (_: Exception) { /* 무시 */ }
        }
        return try {
            // URL 생성(base가 비어있거나 형식이 잘못됐으면 MalformedURLException)도 반드시
            // 이 try 안에서 해야 한다 — 밖에서 하면 예외가 이 함수를 통째로 빠져나가
            // 호출부(Thread{}.start())에서 안 잡히고 콜백이 영영 안 나가는 채로 스레드가
            // 조용히 죽는다(실제 있었던 회귀 — watchdog 추가하면서 실수로 밖으로 뺐었음).
            val boundary = "----HubOneBoundary${System.currentTimeMillis()}"
            val base = config.baseUrl.trim().trimEnd('/')
            val isContact = mode == "contact"
            val isConsultKiosk = mode == "consult_kiosk"
            val url = if (isConsultKiosk)
                URL("$base/api/consult/kiosk/transcribe")
            else if (isContact)
                URL("$base/api/patients/foreign-intake/voice/transcribe")
            else
                URL("$base/api/patients/foreign-reservation/session/${Uri.encode(sessionIdOrScreenId)}/voice/transcribe")
            android.util.Log.d("HubOneVoice", "upload starting url=$url fileSize=${file.length()}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            watchdogHandler.postDelayed(watchdog, UPLOAD_WATCHDOG_MS)
            conn.outputStream.use { out ->
                fun writeText(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                fun writeField(name: String, value: String) {
                    writeText("--$boundary\r\n")
                    writeText("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    writeText("$value\r\n")
                }
                writeField("language", language)
                if (isContact || isConsultKiosk) writeField("screen_id", sessionIdOrScreenId)
                writeText("--$boundary\r\n")
                writeText("Content-Disposition: form-data; name=\"audio\"; filename=\"voice.m4a\"\r\n")
                writeText("Content-Type: audio/mp4\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                writeText("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            android.util.Log.d("HubOneVoice", "upload response code=$code")
            if (code in 200..299) Pair(true, "") else Pair(false, "http_$code")
        } catch (e: Exception) {
            android.util.Log.e("HubOneVoice", "upload failed: ${e.javaClass.simpleName}: ${e.message}", e)
            Pair(false, e.message ?: "upload_exception")
        } finally {
            watchdogHandler.removeCallbacks(watchdog)
            try { conn?.disconnect() } catch (_: Exception) { /* 무시 */ }
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
        private const val SCREEN_PATH_CONSENT = "/pt/consent"
        private const val RECORD_AUDIO_PERMISSION_REQUEST = 20
        private const val CAMERA_PREVIEW_PERMISSION_REQUEST = 21
        // 음성 업로드 워치독 — connectTimeout/readTimeout이 커버 못 하는 "쓰기 중 정체"
        // 상황에서도 이 시간 안에는 반드시 강제로 끊어서 uploaded/upload_error 콜백이
        // 나가도록 한다(실제 겪은 문제: "상담원에게 전달 중..."에서 무한 대기).
        private const val UPLOAD_WATCHDOG_MS = 45_000L
        private const val NATIVE_VAD_POLL_MS = 80L
        private const val NATIVE_VAD_SILENCE_MS = 850L
        private const val NATIVE_VAD_IDLE_MS = 30_000L
        private const val NATIVE_VAD_MAX_SPEECH_MS = 20_000L
        private const val NATIVE_VAD_RESTART_MS = 180L
        private const val NATIVE_VAD_AMPLITUDE_THRESHOLD = 900
        private const val CONSULT_PCM_SAMPLE_RATE = 16_000
        private const val CONSULT_PCM_CHUNK_BYTES = 3_200  // 100ms, mono PCM16

        // CommandPollService가 MainActivity를 강제로 앞에 가져올 때(덴트웹 등에서 복귀)
        // 어느 화면을 띄울지 실어 보내는 Intent extra 키 — CommandPollState.SCREEN_CONTACT/
        // SCREEN_RESERVATION 값을 그대로 담는다.
        const val EXTRA_SCREEN_COMMAND = "screen_command"
        const val EXTRA_SCREEN_PATH = "screen_path"
        const val EXTRA_SCREEN_ORIENTATION = "screen_orientation"
        const val EXTRA_SCREEN_POPUP = "screen_popup"
        // 잠금화면 상태에서 "덴트웹" 명령이 왔을 때, 이 액티비티가 잠금화면 위로 뜬 뒤
        // 이어서 덴트웹을 실행하라는 표시 — 실제 겪은 문제: "잠금화면 상태에서 덴트웹
        // 눌러도 잠금화면 해제는 안됨"(남의 앱은 우리처럼 잠금화면 위에 못 뜬다).
        const val EXTRA_THEN_LAUNCH_DENTWEB = "then_launch_dentweb"

        // sleep 직전에 FLAG_KEEP_SCREEN_ON을 꺼야 하는 CommandPollService, 그리고
        // 잠금화면 상태에서 덴트웹으로 이어줘야 하는 launchDentWeb() 둘 다 살아있는
        // MainActivity 창에 접근해야 해서 둔다 — 같은 프로세스 안이라 SharedPreferences나
        // IPC 없이 static 참조로 충분하다(CommandPollState와 동일한 전제).
        @Volatile
        var activeInstance: MainActivity? = null
    }
}
