package com.lusail.stadium.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.JsonObject

// ── Brand colors ──
private val LusailGreen = Color(0xFF228B22)
private val LusailGreenBright = Color(0xFF2ECC40)
private val LusailDark = Color(0xFF0A1F0A)
private val SurfaceDark = Color(0xFF121212)

/**
 * Full-screen WebView that loads a form URL and auto-fills fields
 * using prefill values from the model-generated bubble payload.
 *
 * @param url The form URL to load
 * @param prefill Key→value map of field names to values from the user profile
 * @param formTitle Human-readable form name for the title bar
 * @param onBack Called when the user presses back
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FormFillScreen(
    url: String,
    prefill: Map<String, String>,
    formTitle: String = "Form",
    onBack: () -> Unit
) {
    var pageLoaded by remember { mutableStateOf(false) }
    var fillCompleted by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Build the JavaScript that fills form fields
    val fillScript = buildFillScript(prefill)

    Scaffold(
        topBar = {
            FormFillTopBar(
                title = formTitle,
                filled = fillCompleted,
                onBack = onBack
            )
        },
        containerColor = LusailDark
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                pageLoaded = true
                                // Inject the fill script after page loads
                                view?.evaluateJavascript(fillScript) { result ->
                                    fillCompleted = true
                                }
                            }
                        }

                        webChromeClient = WebChromeClient()

                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Filled confirmation banner
            AnimatedVisibility(
                visible = fillCompleted,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    color = LusailGreen.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Filled",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Auto-filled from your profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormFillTopBar(
    title: String,
    filled: Boolean,
    onBack: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                if (filled) {
                    Text(
                        text = "Fields pre-filled — review and submit",
                        style = MaterialTheme.typography.labelSmall,
                        color = LusailGreenBright
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = SurfaceDark
        )
    )
}

/**
 * Build JavaScript that fills form fields based on profile data.
 * Handles: text inputs, email inputs, phone inputs, textareas, and select dropdowns.
 * Matches by: aria-label, name attribute, placeholder, surrounding label text.
 */
private fun buildFillScript(prefill: Map<String, String>): String {
    // Escape values for safe JS embedding
    val entries = prefill.entries.joinToString(",\n") { (key, value) ->
        val escapedKey = key.replace("\\", "\\\\").replace("'", "\\'")
        val escapedValue = value.replace("\\", "\\\\").replace("'", "\\'")
        "    '$escapedKey': '$escapedValue'"
    }
    val profileJson = "{\n$entries\n}"

    return """
(function() {
    var profile = $profileJson;
    var filled = 0;

    // Helper: set input value and trigger React/change events
    function setValue(el, value) {
        if (!el || el.value === value) return false;
        
        // Focus first (some frameworks validate on focus)
        try { el.focus(); } catch(e) {}
        
        // Set the value
        var nativeSetter = Object.getOwnPropertyDescriptor(
            window.HTMLInputElement.prototype, 'value'
        );
        if (nativeSetter && nativeSetter.set) {
            nativeSetter.set.call(el, value);
        } else {
            el.value = value;
        }
        
        // Dispatch events so React/Angular/Google Forms detect the change
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        el.dispatchEvent(new Event('blur', { bubbles: true }));
        
        // Also try React's internal event system
        try {
            var tracker = el._valueTracker;
            if (tracker) {
                tracker.setValue(el.value || value);
            }
        } catch(e) {}
        
        return true;
    }

    // ── Match and fill ──

    // 1. Email inputs
    var emails = document.querySelectorAll('input[type="email"]');
    for (var i = 0; i < emails.length; i++) {
        if (profile.email && setValue(emails[i], profile.email)) filled++;
    }

    // 2. Phone / tel inputs
    var phones = document.querySelectorAll('input[type="tel"], input[name*="phone"], input[id*="phone"], input[aria-label*="phone" i]');
    for (var i = 0; i < phones.length; i++) {
        if (profile.phone && setValue(phones[i], profile.phone)) filled++;
    }

    // 3. All text inputs — match by label/name/aria-label
    var textInputs = document.querySelectorAll('input[type="text"], input:not([type])');
    for (var i = 0; i < textInputs.length; i++) {
        var el = textInputs[i];
        var attrs = (el.getAttribute('aria-label') || '').toLowerCase() +
                    (el.name || '').toLowerCase() +
                    (el.placeholder || '').toLowerCase() +
                    (el.id || '').toLowerCase();
        
        // Try to get the label text too
        try {
            var label = el.closest('label');
            if (!label) {
                var id = el.id;
                if (id) label = document.querySelector('label[for="' + id + '"]');
            }
            if (label) attrs += ' ' + label.textContent.toLowerCase();
        } catch(e) {}

        for (var key in profile) {
            if (key === 'email' || key === 'phone') continue;
            if (attrs.indexOf(key.replace('_', ' ')) !== -1 ||
                attrs.indexOf(key.replace('_', '')) !== -1) {
                if (setValue(el, profile[key])) {
                    filled++;
                    break;
                }
            }
        }
    }

    // 4. Textareas — fill with notes or the last unmatched value
    var textareas = document.querySelectorAll('textarea');
    for (var i = 0; i < textareas.length; i++) {
        var ta = textareas[i];
        var taAttrs = (ta.getAttribute('aria-label') || '').toLowerCase() +
                      (ta.name || '').toLowerCase() +
                      (ta.placeholder || '').toLowerCase();
        if (taAttrs.indexOf('note') !== -1 || taAttrs.indexOf('comment') !== -1 ||
            taAttrs.indexOf('message') !== -1 || taAttrs.indexOf('address') !== -1) {
            if (profile.notes && setValue(ta, profile.notes)) filled++;
        }
    }

    // 5. Select dropdowns — try to select matching options
    var selects = document.querySelectorAll('select');
    for (var i = 0; i < selects.length; i++) {
        var sel = selects[i];
        var selName = (sel.name || sel.id || '').toLowerCase();
        for (var key in profile) {
            if (selName.indexOf(key.replace('_', ' ')) !== -1) {
                var target = profile[key].toLowerCase();
                for (var j = 0; j < sel.options.length; j++) {
                    if (sel.options[j].text.toLowerCase().indexOf(target) !== -1) {
                        sel.selectedIndex = j;
                        sel.dispatchEvent(new Event('change', { bubbles: true }));
                        filled++;
                        break;
                    }
                }
            }
        }
    }

    console.log('[LusailFormFill] Filled ' + filled + ' fields from profile');
    return JSON.stringify({ filled: filled });
})();
    """.trimIndent()
}
