package com.kgycoder.lmarena

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kgycoder.lmarena.bridge.SpeechBridge
import com.kgycoder.lmarena.bridge.TtsBridge
import com.kgycoder.lmarena.engine.AutomationEngine
import com.kgycoder.lmarena.work.WorkAccessibilityService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), AutomationEngine.UiHost {

    private lateinit var uiWebView: WebView
    private lateinit var automationWebView: WebView
    private lateinit var automationOverlay: FrameLayout
    private lateinit var automationOverlayTitle: TextView
    private lateinit var bootProgress: ProgressBar
    private var speechBridge: SpeechBridge? = null
    private var ttsBridge: TtsBridge? = null

    private val requestMic = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    private val requestNotif = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        uiWebView = findViewById(R.id.uiWebView)
        automationWebView = findViewById(R.id.automationWebView)
        automationOverlay = findViewById(R.id.automationOverlay)
        automationOverlayTitle = findViewById(R.id.automationOverlayTitle)
        bootProgress = findViewById(R.id.bootProgress)
        findViewById<Button>(R.id.automationOverlayMinimize).setOnClickListener { onHideAutomation() }

        requestRuntimePermissions()
        AutomationEngine.attach(applicationContext, automationWebView, this)
        setupAutomationWebView()
        setupUiWebView()

        val svcIntent = Intent(this, AutomationForegroundService::class.java)
        ContextCompat.startForegroundService(this, svcIntent)

        waitForServerThenLoadUi()

        lifecycleScope.launch {
            AutomationEngine.bootstrap()
        }
    }

    override fun onResume() {
        super.onResume()
        maybePromptAccessibility()
    }

    override fun onDestroy() {
        AutomationEngine.detachUi(this)
        speechBridge?.release()
        ttsBridge?.release()
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════
    // WebView 설정
    // ══════════════════════════════════════════════════════════════
    private fun setupAutomationWebView() {
        val ws = automationWebView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.javaScriptCanOpenWindowsAutomatically = true
        ws.setSupportMultipleWindows(true)
        ws.mediaPlaybackRequiresUserGesture = false
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // 일부 사이트가 "; wv" 토큰으로 WebView를 구분해 기능을 제한하는 경우가 있어 제거한다
        // (실제 UI 요소는 동일 사이트의 일반 모바일 브라우저와 같아야 자동화 선택자가 그대로 맞는다).
        ws.userAgentString = ws.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(automationWebView, true)

        automationWebView.addJavascriptInterface(AutomationEngine.bridge, "LmaArena")
        automationWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // AutomationEngine이 각 네비게이션 뒤에 직접 arena_lib.js를 주입하지만,
                // 예기치 못한 리다이렉트/새로고침에 대비한 이중 안전망.
            }
        }
        automationWebView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                // Google 로그인 등 window.open() 팝업: 별도 액티비티에서 보여준다
                // (Windows판이 팝업 창만 사용자에게 보여주던 것과 같은 역할).
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.settings.userAgentString = automationWebView.settings.userAgentString
                popup.webViewClient = WebViewClient()
                PopupHost.pendingWebView = popup
                startActivity(Intent(this@MainActivity, PopupWebViewActivity::class.java))
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popup
                resultMsg?.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                // JARVIS 음성모드의 getUserMedia() 사전 점검(마이크 권한 확인용, 실제 인식은
                // 네이티브 SpeechRecognizer가 담당)이 통과하도록 허용한다.
                request ?: return
                val hasMic = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }
        automationWebView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
    }

    private fun setupUiWebView() {
        val ws = uiWebView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false

        speechBridge = SpeechBridge(applicationContext) { uiWebView }
        ttsBridge = TtsBridge(applicationContext) { uiWebView }
        uiWebView.addJavascriptInterface(speechBridge!!, "AndroidSpeech")
        uiWebView.addJavascriptInterface(ttsBridge!!, "AndroidTts")

        uiWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                bootProgress.visibility = android.view.View.GONE
            }
        }
        uiWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val hasMic = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasMic) request.grant(request.resources) else request.deny()
            }
        }
    }

    private fun waitForServerThenLoadUi() {
        val handler = Handler(Looper.getMainLooper())
        val url = "http://127.0.0.1:${AutomationForegroundService.PORT}/"
        fun attempt(triesLeft: Int) {
            if (AutomationForegroundService.isRunning) {
                uiWebView.loadUrl(url)
            } else if (triesLeft > 0) {
                handler.postDelayed({ attempt(triesLeft - 1) }, 150)
            } else {
                uiWebView.loadUrl(url) // 마지막으로 한 번 더 시도 (서버가 곧 뜨는 경우가 대부분)
            }
        }
        attempt(40)
    }

    // ══════════════════════════════════════════════════════════════
    // 권한
    // ══════════════════════════════════════════════════════════════
    private fun requestRuntimePermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestIgnoreBatteryOptimizations()
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        } catch (_: Exception) {
            // 일부 OEM은 이 액션을 지원하지 않는다 - 설정 앱에서 수동으로 안내한다(README 참고).
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun maybePromptAccessibility() {
        if (!::uiWebView.isInitialized) return
        try {
            if (AutomationEngine.settings.workMode && WorkAccessibilityService.instance == null && !isAccessibilityServiceEnabled()) {
                AlertDialog.Builder(this)
                    .setTitle("Work 모드 접근성 권한 필요")
                    .setMessage("Work 모드가 기기 화면을 조작하려면 설정 > 접근성에서 'LM Arena' 서비스를 켜야 합니다.")
                    .setPositiveButton("설정 열기") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("나중에", null)
                    .show()
            }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════
    // AutomationEngine.UiHost
    // ══════════════════════════════════════════════════════════════
    override fun onRevealAutomation(reason: String) {
        runOnUiThread {
            automationOverlayTitle.text = reason
            automationOverlay.visibility = android.view.View.VISIBLE
        }
    }

    override fun onHideAutomation() {
        runOnUiThread {
            automationOverlay.visibility = android.view.View.GONE
        }
    }

    override fun onStateChanged() {
        // 상태는 로컬 서버를 통해 uiWebView(app.js)가 직접 폴링하므로 별도 처리가 필요 없다.
    }
}
