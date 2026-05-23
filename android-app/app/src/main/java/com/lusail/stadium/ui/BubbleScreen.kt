package com.lusail.stadium.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lusail.stadium.models.Bubble
import com.lusail.stadium.models.BubbleResponse
import com.lusail.stadium.models.InferenceStats
import com.lusail.stadium.models.QrValidationResult
import kotlinx.coroutines.delay
import kotlin.math.sin

// ── Brand colors ──
private val LusailGreen = Color(0xFF228B22)
private val LusailGreenBright = Color(0xFF2ECC40)
private val LusailDark = Color(0xFF0A1F0A)
private val LusailGold = Color(0xFFD4AF37)
private val SurfaceDark = Color(0xFF121212)
private val SurfaceVariant = Color(0xFF1E1E2E)

/**
 * Main bubble screen showing floating interactive bubbles with scan context bar.
 *
 * @param scanContextText Human-readable summary shown in the top bar
 * @param bubbleResponse Response from inference backend (null = loading)
 * @param isLoading Whether inference is in progress
 * @param error Error message to display (null = no error)
 * @param isValidating Whether Mr_QR validation is in progress
 * @param qrValidationResult Result from Mr_QR validation (null = not validated yet)
 * @param onBubbleTap Called when a user taps a bubble
 * @param onScanAgain Called when user taps "Scan Again"
 * @param onProceedAnyway Called when user chooses to ignore a SUSPICIOUS/INVALID verdict
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(
    scanContextText: String,
    bubbleResponse: BubbleResponse?,
    isLoading: Boolean,
    error: String?,
    isValidating: Boolean = false,
    qrValidationResult: QrValidationResult? = null,
    onBubbleTap: (Bubble) -> Unit,
    onScanAgain: () -> Unit,
    onProceedAnyway: () -> Unit = {}
) {
    val stats by InferenceStats.stats.collectAsState()

    Scaffold(
        topBar = {
            // Scan context bar
            ScanContextBar(
                text = scanContextText,
                isScanning = isLoading,
                stats = stats,
                onScanAgain = onScanAgain
            )
        },
        containerColor = LusailDark
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(LusailDark, Color(0xFF0D2818), LusailDark)
                    )
                )
        ) {
            when {
                isValidating -> ValidatingState()
                qrValidationResult != null && !qrValidationResult.isValid ->
                    ValidationVerdictState(
                        result = qrValidationResult,
                        onScanAgain = onScanAgain,
                        onProceedAnyway = onProceedAnyway
                    )
                isLoading -> LoadingState()
                error != null -> ErrorState(error = error, onRetry = onScanAgain)
                bubbleResponse != null -> BubblesContent(
                    narration = bubbleResponse.narration,
                    bubbles = bubbleResponse.bubbles,
                    onBubbleTap = onBubbleTap
                )
                else -> EmptyState(onScanAgain = onScanAgain)
            }
        }
    }
}

@Composable
private fun ScanContextBar(
    text: String,
    isScanning: Boolean,
    stats: InferenceStats,
    onScanAgain: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark.copy(alpha = 0.95f),
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (isScanning) Icons.Filled.QrCodeScanner
                    else Icons.Filled.QrCode,
                    contentDescription = "Scan",
                    tint = LusailGreenBright,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text.ifEmpty { "Lusail Stadium" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (!isScanning) {
                    TextButton(onClick = onScanAgain) {
                        Text("Scan Again", color = LusailGreenBright, fontSize = 12.sp)
                    }
                }
            }

            // Live inference stats row
            AnimatedVisibility(visible = stats.isInferring || stats.responseTimeMs > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatChip("${stats.responseTimeMs}ms", "Response")
                    StatChip("${stats.tokensGenerated} tok", "Tokens")
                    StatChip(String.format("%.1f t/s", stats.tokensPerSec), "Speed")
                }
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = LusailGreenBright,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 9.sp
        )
    }
}

@Composable
private fun ValidatingState() {
    val infiniteTransition = rememberInfiniteTransition(label = "validating")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "validatingAlpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "validatingScale"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(scale)
                .alpha(alpha)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(LusailGold.copy(alpha = 0.3f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = "Validating",
                tint = LusailGold,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Validating QR...",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "AI security check via Mr_QR",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.4f)
        )
    }
}

/**
 * Show the Mr_QR validation verdict when a QR payload is flagged SUSPICIOUS or INVALID.
 * The user can rescan or choose to proceed anyway.
 */
@Composable
private fun ValidationVerdictState(
    result: QrValidationResult,
    onScanAgain: () -> Unit,
    onProceedAnyway: () -> Unit
) {
    val isSuspicious = result.isSuspicious
    val verdictColor = if (isSuspicious) LusailGold else Color(0xFFFF6B6B)
    val verdictIcon = if (isSuspicious) Icons.Filled.Warning else Icons.Filled.GppBad
    val verdictTitle = if (isSuspicious) "Suspicious QR Detected" else "Invalid QR Detected"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Verdict badge
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(verdictColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = verdictIcon,
                contentDescription = "Verdict",
                tint = verdictColor,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Verdict label
        Surface(
            color = verdictColor.copy(alpha = 0.2f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = result.verdict,
                style = MaterialTheme.typography.labelMedium,
                color = verdictColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = verdictTitle,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Scanned payload
        Surface(
            color = SurfaceDark.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Scanned Payload",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.payload,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 3
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // AI explanation
        Surface(
            color = SurfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "AI Analysis",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    lineHeight = 20.sp
                )
            }
        }

        // Response time badge
        if (result.responseTimeMs > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Validated in ${result.responseTimeMs}ms via DeepSeek-V4-Pro",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.3f)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onScanAgain,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White.copy(alpha = 0.7f)
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.1f))
                    )
                )
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan Again", fontSize = 13.sp)
            }

            Button(
                onClick = onProceedAnyway,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = verdictColor.copy(alpha = 0.3f)
                )
            ) {
                Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Continue Anyway", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LoadingState() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(scale)
                .alpha(alpha)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(LusailGreenBright.copy(alpha = 0.3f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = LusailGreenBright,
                strokeWidth = 2.dp,
                modifier = Modifier.size(60.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Error,
            contentDescription = "Error",
            tint = Color(0xFFFF6B6B),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Connection Lost",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = LusailGreen)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun EmptyState(onScanAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.QrCodeScanner,
            contentDescription = "Scan",
            tint = LusailGreen.copy(alpha = 0.3f),
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Tap 'Scan Again' to start",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun BubblesContent(
    narration: String,
    bubbles: List<Bubble>,
    onBubbleTap: (Bubble) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Narration text
        AnimatedVisibility(
            visible = narration.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceVariant.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = narration,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Floating bubbles
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (bubbles.isEmpty()) {
                Text(
                    text = "No suggestions available",
                    color = Color.White.copy(alpha = 0.3f)
                )
            } else {
                FloatingBubbles(
                    bubbles = bubbles,
                    onBubbleTap = onBubbleTap
                )
            }
        }
    }
}

@Composable
private fun FloatingBubbles(
    bubbles: List<Bubble>,
    onBubbleTap: (Bubble) -> Unit
) {
    // Staggered entrance and floating animation for each bubble
    bubbles.forEachIndexed { index, bubble ->
        val density = LocalDensity.current

        // Staggered entrance
        val entranceDelay = index * 300L
        var visible by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            delay(entranceDelay)
            visible = true
        }

        // Continuous floating animation (bounce)
        val infiniteTransition = rememberInfiniteTransition(label = "bubble_bounce_$index")
        val floatOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 2000 + (index * 400),
                    easing = EaseInOutCubic
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "floatOffset_$index"
        )

        // Staggered horizontal positions
        val horizontalOffsets = listOf(-60.dp, 0.dp, 60.dp)
        val baseOffsetX = horizontalOffsets.getOrElse(index % horizontalOffsets.size) { 0.dp }
        val floatY = with(density) { (floatOffset * 16f - 8f).toDp() }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(600, delayMillis = entranceDelay.toInt())) +
                    slideInVertically(
                        animationSpec = tween(600, delayMillis = entranceDelay.toInt()),
                        initialOffsetY = { it }
                    )
        ) {
            BubbleItem(
                bubble = bubble,
                offsetY = floatY,
                offsetX = baseOffsetX,
                delay = index,
                onTap = { onBubbleTap(bubble) }
            )
        }
    }
}

@Composable
private fun BubbleItem(
    bubble: Bubble,
    offsetY: Dp,
    offsetX: Dp,
    delay: Int,
    onTap: () -> Unit
) {
    // Determine icon from bubble type
    val icon = getIconForBubble(bubble)
    val color = Color(android.graphics.Color.parseColor(bubble.color))

    // Glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "glow_$delay")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha_$delay"
    )

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(x = offsetX, y = offsetY)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap
                )
        ) {
            // Glow ring
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = glowAlpha)),
                contentAlignment = Alignment.Center
            ) {
                // Bubble body
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    color.copy(alpha = 0.9f),
                                    color.copy(alpha = 0.5f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = bubble.label,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Label
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = bubble.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * Map bubble type/icon string to a Material icon.
 */
private fun getIconForBubble(bubble: Bubble): ImageVector {
    // Try icon string first
    return when (bubble.icon.lowercase()) {
        "directions_walk", "navigation", "navigate", "directions" -> Icons.Filled.DirectionsWalk
        "restaurant", "food", "dining", "fastfood" -> Icons.Filled.Restaurant
        "local_offer", "ticket", "confirmation_number" -> Icons.Filled.ConfirmationNumber
        "sports_soccer", "sports", "stadium" -> Icons.Filled.SportsSoccer
        "info", "help" -> Icons.Filled.Info
        "qr_code", "qr_code_scanner", "scan" -> Icons.Filled.QrCodeScanner
        "shopping_cart", "shopping", "store" -> Icons.Filled.ShoppingCart
        "wc", "restroom" -> Icons.Filled.Wc
        "first_aid", "medical", "local_hospital" -> Icons.Filled.LocalHospital
        "chat", "message", "support" -> Icons.Filled.Chat
        "map", "place" -> Icons.Filled.Map
        "person", "profile", "account_circle" -> Icons.Filled.Person
        "wifi", "signal" -> Icons.Filled.Wifi
        "parking", "local_parking" -> Icons.Filled.LocalParking
        else -> {
            // Fall back to bubble type
            when (bubble.type.lowercase()) {
                "navigate" -> Icons.Filled.DirectionsWalk
                "comm" -> Icons.Filled.Chat
                "info" -> Icons.Filled.Info
                "scan" -> Icons.Filled.QrCodeScanner
                else -> Icons.Filled.Circle
            }
        }
    }
}
