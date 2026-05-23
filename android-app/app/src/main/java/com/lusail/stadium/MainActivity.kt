package com.lusail.stadium

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.lusail.stadium.bridge.InferenceBridgeService
import com.lusail.stadium.bridge.JsBridge
import com.lusail.stadium.models.*
import com.lusail.stadium.network.InferenceClient
import com.lusail.stadium.network.QrValidator
import com.lusail.stadium.ui.BubbleScreen
import com.lusail.stadium.ui.CryptoShieldScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Main entry point for the Lusail Stadium Matchday Companion.
 *
 * Supports:
 * - Deep linking via open.lusail.qa:// and https://open.lusail.qa
 * - WebView with JavaScript bridge to local inference backend
 * - Native Compose fallback UI when WebView is unavailable
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val WEB_URL = "https://open.lusail.qa"
        private const val DEEP_LINK_SCHEME = "open.lusail.qa"

        /**
         * GMI Cloud API key for QR validation via Mr_QR / DeepSeek-V4-Pro.
         * For the hackathon demo, set this to your GMI API key.
         * In production, this would come from a secure keystore or backend proxy.
         */
        private const val GMI_API_KEY = "" // ← SET YOUR GMI API KEY HERE
    }

    private var jsBridge: JsBridge? = null
    private var scanContextData: ScanContext? = null
    private var webView: WebView? = null
    private var userProfile: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Parse any deep link data from the intent
        parseIncomingIntent(intent)

        // Load user profile from assets for personalized model responses
        userProfile = try {
            assets.open("user_profile.md").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "User profile not found in assets, running without personalization")
            ""
        }

        // Start the foreground service for the inference bridge
        startInferenceBridgeService()

        setContent {
            val scope = rememberCoroutineScope()

            // App state
            var useWebView by remember { mutableStateOf(true) }
            var webViewError by remember { mutableStateOf(false) }
            var showCryptoShield by remember { mutableStateOf(true) } // Start with verification
            var bubbleResponse by remember { mutableStateOf<BubbleResponse?>(null) }
            var isLoading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var scanSummary by remember { mutableStateOf(buildScanSummary()) }

            // ── Mr_QR validation state ──
            var qrValidationResult by remember { mutableStateOf<QrValidationResult?>(null) }
            var isValidating by remember { mutableStateOf(false) }
            var validationSkipped by remember { mutableStateOf(false) }

            // Local helper: proceed from validation to local bubble inference
            suspend fun doInference(ctx: ScanContext) {
                isLoading = true
                error = null
                val client = InferenceClient(userProfile = userProfile)
                client.infer(ctx).fold(
                    onSuccess = { response ->
                        bubbleResponse = response
                        isLoading = false
                    },
                    onFailure = { e ->
                        error = e.message ?: "Inference failed"
                        isLoading = false
                    }
                )
            }

            // Validate QR payload, then infer bubbles
            LaunchedEffect(scanContextData) {
                scanContextData?.let { ctx ->
                    // Reset all state for new scan
                    isLoading = true
                    error = null
                    bubbleResponse = null
                    qrValidationResult = null
                    validationSkipped = false

                    val payload = ctx.scan.payload

                    // Step 1: QR security validation via Mr_QR (GMI Cloud)
                    if (GMI_API_KEY.isNotEmpty() && !validationSkipped) {
                        isValidating = true
                        val validator = QrValidator(apiKey = GMI_API_KEY)
                        validator.validate(payload).fold(
                            onSuccess = { result ->
                                qrValidationResult = result
                                isValidating = false

                                if (result.isValid) {
                                    // Payload is clean — proceed to bubble inference
                                    doInference(ctx)
                                } else {
                                    // SUSPICIOUS or INVALID — stop and show verdict
                                    isLoading = false
                                }
                            },
                            onFailure = { e ->
                                // Validation API failed — skip validation, proceed anyway
                                Log.w(TAG, "QR validation failed, proceeding: ${e.message}")
                                qrValidationResult = null
                                isValidating = false
                                validationSkipped = true
                                doInference(ctx)
                            }
                        )
                    } else {
                        // No API key configured — skip validation, go straight to inference
                        isValidating = false
                        doInference(ctx)
                    }
                }
            }

            if (showCryptoShield) {
                // Holographic verification screen
                CryptoShieldScreen(
                    onVerificationComplete = {
                        showCryptoShield = false
                    }
                )
            } else if (useWebView && !webViewError) {
                // WebView with JS bridge
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A1F0A)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webView = this
                                setupWebView(this, scope)
                                loadUrl(WEB_URL)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                // Native Compose fallback
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A1F0A)
                ) {
                    BubbleScreen(
                        scanContextText = scanSummary,
                        bubbleResponse = bubbleResponse,
                        isLoading = isLoading,
                        error = error,
                        isValidating = isValidating,
                        qrValidationResult = qrValidationResult,
                        onBubbleTap = { bubble ->
                            Log.d(TAG, "Bubble tapped: ${bubble.label} (${bubble.type})")
                            handleBubbleAction(bubble)
                        },
                        onScanAgain = {
                            // Reset validation state and re-run flow
                            qrValidationResult = null
                            validationSkipped = false
                            bubbleResponse = null
                            scanContextData?.let { ctx ->
                                scope.launch {
                                    isLoading = true
                                    error = null
                                    val payload = ctx.scan.payload
                                    if (GMI_API_KEY.isNotEmpty()) {
                                        isValidating = true
                                        val validator = QrValidator(apiKey = GMI_API_KEY)
                                        validator.validate(payload).fold(
                                            onSuccess = { result ->
                                                qrValidationResult = result
                                                isValidating = false
                                                if (result.isValid) {
                                                    doInference(ctx)
                                                } else {
                                                    isLoading = false
                                                }
                                            },
                                            onFailure = { e ->
                                                Log.w(TAG, "QR validation failed, proceeding: ${e.message}")
                                                qrValidationResult = null
                                                isValidating = false
                                                doInference(ctx)
                                            }
                                        )
                                    } else {
                                        isValidating = false
                                        doInference(ctx)
                                    }
                                }
                            }
                        },
                        onProceedAnyway = {
                            // User chose to ignore SUSPICIOUS/INVALID verdict
                            validationSkipped = true
                            qrValidationResult = null
                            scanContextData?.let { ctx ->
                                scope.launch {
                                    doInference(ctx)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseIncomingIntent(intent)
    }

    /**
     * Parse deep link data from the intent.
     * Supports: open.lusail.qa scheme and https:\/\/open.lusail.qa
     */
    private fun parseIncomingIntent(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null) {
            Log.d(TAG, "Deep link received: $data")
            val parsed = parseDeepLinkParams(data)
            scanContextData = parsed
        } else {
            // Create a default/demo scan context
            scanContextData = createDemoScanContext()
        }

        // If no scan context was set, create a demo one
        if (scanContextData == null) {
            scanContextData = createDemoScanContext()
        }
    }

    /**
     * Extract ScanContext from deep link URL parameters.
     * Example: open.lusail.qa://scan?zone=A4&gate=12&section=205&match_id=QAT2026-042
     */
    private fun parseDeepLinkParams(uri: Uri): ScanContext {
        val scanType = uri.getQueryParameter("type") ?: "qatar_qr"
        val payload = uri.getQueryParameter("payload") ?: uri.toString()
        val zone = uri.getQueryParameter("zone") ?: ""
        val gate = uri.getQueryParameter("gate") ?: ""
        val section = uri.getQueryParameter("section") ?: ""
        val ticketId = uri.getQueryParameter("ticket_id") ?: ""
        val userName = uri.getQueryParameter("name") ?: "Guest"
        val matchId = uri.getQueryParameter("match_id") ?: "QAT2026-DEMO"
        val homeTeam = uri.getQueryParameter("home") ?: ""
        val awayTeam = uri.getQueryParameter("away") ?: ""
        val phase = uri.getQueryParameter("phase") ?: "pre_match"

        return ScanContext(
            scan = ScanData(
                payload = payload,
                type = scanType,
                zone = zone,
                gate = gate,
                section = section,
                confidence = 0.98f
            ),
            match = MatchData(
                matchId = matchId,
                homeTeam = homeTeam,
                awayTeam = awayTeam,
                phase = phase
            ),
            user = UserData(
                ticketId = ticketId,
                userName = userName,
                ticketTier = uri.getQueryParameter("tier") ?: "standard"
            ),
            deviceId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "",
            scanTimestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                java.util.Locale.US
            ).format(java.util.Date())
        )
    }

    /**
     * Create a demo scan context for hackathon purposes.
     */
    private fun createDemoScanContext(): ScanContext {
        return ScanContext(
            scan = ScanData(
                payload = "lusail://demo/scan/001",
                type = "qatar_qr",
                zone = "A4",
                gate = "12",
                section = "205",
                confidence = 0.99f
            ),
            match = MatchData(
                matchId = "QAT2026-042",
                homeTeam = "Qatar",
                awayTeam = "Japan",
                kickoffTime = "2026-06-15T19:00:00Z",
                phase = "pre_match",
                venue = "Lusail Iconic Stadium",
                occupancy = 0.85f
            ),
            user = UserData(
                ticketId = "TKT-LUSAIL-00042",
                userName = "Demo Fan",
                ticketTier = "premium",
                language = "en"
            ),
            deviceId = "demo-pixel-9-pro",
            scanTimestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                java.util.Locale.US
            ).format(java.util.Date())
        )
    }

    /**
     * Build a human-readable summary of the current scan context.
     */
    private fun buildScanSummary(): String {
        val ctx = scanContextData ?: return "Lusail Stadium"
        val parts = mutableListOf<String>()
        if (ctx.match.homeTeam.isNotEmpty()) {
            parts.add("${ctx.match.homeTeam} vs ${ctx.match.awayTeam}")
        }
        if (ctx.scan.zone.isNotEmpty()) {
            parts.add("Zone ${ctx.scan.zone}")
        }
        if (ctx.scan.gate.isNotEmpty()) {
            parts.add("Gate ${ctx.scan.gate}")
        }
        if (ctx.scan.section.isNotEmpty()) {
            parts.add("Section ${ctx.scan.section}")
        }
        if (ctx.user.userName != "Guest") {
            parts.add(ctx.user.userName)
        }
        return parts.joinToString(" · ")
    }

    /**
     * Configure WebView with the JS bridge and settings.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView, scope: kotlinx.coroutines.CoroutineScope) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Allow cleartext for localhost only (needed for inference bridge)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Create and add JS bridge
        val bridge = JsBridge(webView, userProfile = userProfile)
        jsBridge = bridge
        webView.addJavascriptInterface(bridge, "LusailBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "WebView page started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "WebView page finished: $url")
                // Inject JS API bridge into the page
                bridge.injectJsApi()

                // Also push the current scan context to the page
                scanContextData?.let { ctx ->
                    val json = Json { encodeDefaults = true }
                    val contextJson = json.encodeToString(ScanContext.serializer(), ctx)
                    val escapedJson = contextJson
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                    val js = """
                        (function() {
                            if (window.LusailBridge && window.LusailBridge.isAvailable()) {
                                window._lusailScanContext = JSON.parse('$escapedJson');
                                console.log('[LusailBridge] Scan context injected');
                            }
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "WebView error: ${error?.description}")
                // Don't immediately fall back — only on main frame errors
                if (request?.isForMainFrame == true) {
                    // Let the error accumulate; fallback decision is in the Compose UI
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d(TAG, "WebView console: ${it.message()}")
                }
                return true
            }
        }
    }

    /**
     * Handle bubble action dispatch.
     */
    private fun handleBubbleAction(bubble: Bubble) {
        when (bubble.type.lowercase()) {
            "navigate" -> {
                // In a real app: launch maps/AR navigation
                Log.d(TAG, "Navigate: ${bubble.route}")
            }
            "comm" -> {
                // In a real app: open chat / concierge
                Log.d(TAG, "Comm: ${bubble.payload}")
            }
            "info" -> {
                // In a real app: show detail screen
                Log.d(TAG, "Info: ${bubble.payload}")
            }
            "scan" -> {
                // In a real app: launch camera for another scan
                Log.d(TAG, "Scan again: ${bubble.payload}")
            }
        }
    }

    /**
     * Start the foreground service for the inference bridge.
     */
    private fun startInferenceBridgeService() {
        val intent = Intent(this, InferenceBridgeService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        jsBridge?.destroy()
        webView?.destroy()
        super.onDestroy()
    }
}
