package com.lusail.stadium.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.lusail.stadium.models.ScanContext
import com.lusail.stadium.network.InferenceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * WebView JavaScript bridge that allows the web UI (open.lusail.qa) to call
 * the local inference backend. Solves the CORS problem: the web page cannot
 * directly fetch localhost:8080, but the native bridge can.
 *
 * Exposed to JavaScript as: window.LusailBridge.infer(scanContextJson, callbackId)
 */
class JsBridge(
    private val webView: WebView,
    private val userProfile: String = ""
) {
    companion object {
        private const val TAG = "LusailBridge"
        private const val JS_NAMESPACE = "LusailBridge"
        private const val INFERENCE_TIMEOUT_MS = 30_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val inferenceClient = InferenceClient(userProfile = userProfile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Called from JavaScript: window.LusailBridge.infer(jsonScanContext, callbackId)
     *
     * @param jsonScanContext Serialized ScanContext JSON string
     * @param callbackId Unique ID for the JS callback function
     */
    @JavascriptInterface
    fun infer(jsonScanContext: String, callbackId: String) {
        Log.d(TAG, "infer() called from JS — callbackId=$callbackId")

        scope.launch {
            try {
                // Parse the scan context from JS
                val scanContext = json.decodeFromString(
                    ScanContext.serializer(),
                    jsonScanContext
                )

                Log.d(TAG, "Parsed ScanContext: zone=${scanContext.scan.zone}, match=${scanContext.match.homeTeam} vs ${scanContext.match.awayTeam}")

                // Call the inference backend with timeout
                val result = withTimeout(INFERENCE_TIMEOUT_MS) {
                    inferenceClient.infer(scanContext)
                }

                result.fold(
                    onSuccess = { bubbleResponse ->
                        val bubbleJson = json.encodeToString(
                            com.lusail.stadium.models.BubbleResponse.serializer(),
                            bubbleResponse
                        )
                        val escapedJson = escapeJsonForJs(bubbleJson)
                        executeJs("_lusailBridgeCallback('$callbackId', null, $escapedJson)")
                        Log.d(TAG, "Inference successful: ${bubbleResponse.bubbles.size} bubbles")
                    },
                    onFailure = { error ->
                        val errorMsg = error.message ?: "Inference failed"
                        val escapedError = escapeJsonForJs(errorMsg)
                        executeJs("_lusailBridgeCallback('$callbackId', '$escapedError', null)")
                        Log.e(TAG, "Inference failed: $errorMsg", error)
                    }
                )
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val escapedError = escapeJsonForJs("Inference timed out after ${INFERENCE_TIMEOUT_MS}ms")
                executeJs("_lusailBridgeCallback('$callbackId', '$escapedError', null)")
                Log.e(TAG, "Inference timed out")
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Bridge error"
                val escapedError = escapeJsonForJs(errorMsg)
                executeJs("_lusailBridgeCallback('$callbackId', '$escapedError', null)")
                Log.e(TAG, "Bridge error: $errorMsg", e)
            }
        }
    }

    /**
     * Inject the LusailBridge JavaScript API into the WebView.
     * Called after the page loads. This sets up window.LusailBridge and
     * the callback dispatch mechanism.
     */
    fun injectJsApi() {
        val jsCode = """
(function() {
    if (window.LusailBridge) return; // Already injected

    // Callback registry: maps callbackId -> function(error, result)
    window._lusailCallbacks = {};

    // Called by native bridge when inference completes
    window._lusailBridgeCallback = function(callbackId, error, result) {
        var cb = window._lusailCallbacks[callbackId];
        if (cb) {
            try {
                cb(error || null, result ? JSON.parse(result) : null);
            } catch(e) {
                console.error('[LusailBridge] Callback error:', e);
            }
            delete window._lusailCallbacks[callbackId];
        }
    };

    // Public API
    window.LusailBridge = {
        /**
         * Send scan context to the local inference backend.
         * @param {object} scanContext - ScanContext object
         * @param {function} callback - function(error, bubbleResponse)
         */
        infer: function(scanContext, callback) {
            var callbackId = 'cb_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
            window._lusailCallbacks[callbackId] = callback;

            var contextJson = JSON.stringify(scanContext);

            try {
                // Call the native @JavascriptInterface method
                ${JS_NAMESPACE}.infer(contextJson, callbackId);
            } catch(e) {
                console.error('[LusailBridge] Native call failed:', e);
                callback('Native bridge unavailable: ' + e.message, null);
                delete window._lusailCallbacks[callbackId];
            }
        },

        /**
         * Check if the native bridge is available.
         * @returns {boolean}
         */
        isAvailable: function() {
            return typeof ${JS_NAMESPACE} !== 'undefined' &&
                   typeof ${JS_NAMESPACE}.infer === 'function';
        }
    };

    console.log('[LusailBridge] API injected — window.LusailBridge ready');
})();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(jsCode, null)
        }
    }

    /**
     * Execute JavaScript in the WebView.
     */
    private fun executeJs(js: String) {
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * Escape a string for safe embedding in JavaScript string literal.
     */
    private fun escapeJsonForJs(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
    }

    /**
     * Clean up resources when the bridge is destroyed.
     */
    fun destroy() {
        scope.coroutineContext[Job]?.cancel()
    }
}
