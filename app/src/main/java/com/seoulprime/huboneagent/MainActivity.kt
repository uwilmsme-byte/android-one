package com.seoulprime.huboneagent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
        loadConfiguredPage()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
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
        val base = config.baseUrl.trim().trimEnd('/')
        val screen = config.screenId.trim().ifBlank { AgentConfig.DEFAULT_SCREEN_ID }
        val target = "$base/pt?screen_id=${Uri.encode(screen)}"
        val uri = Uri.parse(target)
        if (!isAllowed(uri)) {
            showError()
            startAutoDiscovery()
            return
        }
        lastLoadedUrl = target
        errorView.visibility = View.GONE
        webView.loadUrl(target)
    }

    private fun isAllowed(uri: Uri): Boolean {
        val base = Uri.parse(config.baseUrl.trim())
        val baseHost = base.host?.lowercase(Locale.ROOT)
        return !base.scheme.isNullOrBlank() && !baseHost.isNullOrBlank() &&
            uri.host?.lowercase(Locale.ROOT) == baseHost
    }

    private fun showError() { errorView.visibility = View.VISIBLE }

    private fun startAutoDiscovery() {
        if (discoveryStarted) return
        discoveryStarted = true
        Thread {
            val discovered = ServerDiscovery.discover(config.baseUrl)
            runOnUiThread {
                discoveryStarted = false
                if (!discovered.isNullOrBlank()) {
                    config = config.copy(baseUrl = discovered)
                    config.save(this)
                    loadConfiguredPage()
                }
            }
        }.start()
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

    private inner class SafeWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return !isAllowed(request.url)
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
    }
}
