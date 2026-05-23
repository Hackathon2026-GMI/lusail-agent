package com.lusail.stadium.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ── Brand colors ──
private val EmeraldGreen = Color(0xFF50C878)
private val EmeraldDark = Color(0xFF0A1F0A)
private val EmeraldBright = Color(0xFF00FF88)
private val GoldAccent = Color(0xFFD4AF37)
private val ShieldDark = Color(0xFF0D1520)

/**
 * Placeholder cryptographic verification screen with an emerald holographic seal
 * animation. Shows "Verifying..." → checkmark → countdown → auto-advance.
 *
 * This is a hackathon demo — no actual crypto verification occurs.
 * After the animation completes, onVerificationComplete() is called.
 *
 * @param onVerificationComplete Called when the animation finishes
 */
@Composable
fun CryptoShieldScreen(
    onVerificationComplete: () -> Unit
) {
    // Animation state machine: "verifying" → "verified" → "complete"
    var phase by remember { mutableStateOf("verifying") }
    var countdown by remember { mutableIntStateOf(3) }

    // Auto-advance the animation
    LaunchedEffect(Unit) {
        // 1.5s of verifying animation
        delay(1500)
        phase = "verified"

        // 3-second countdown (1s per tick)
        repeat(3) {
            delay(1000)
            countdown--
        }

        phase = "complete"
        delay(300) // brief hold on complete
        onVerificationComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        EmeraldDark.copy(alpha = 0.9f),
                        ShieldDark,
                        Color.Black
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // ── Emerald holographic seal ──
            HolographicSeal(phase = phase)

            Spacer(modifier = Modifier.height(32.dp))

            // ── Status text ──
            AnimatedVisibility(
                visible = phase == "verifying",
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Verifying Integrity",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Cryptographic seal verification in progress...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            AnimatedVisibility(
                visible = phase == "verified",
                enter = fadeIn() + scaleIn(),
                exit = fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Verified",
                            tint = EmeraldBright,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Seal Verified",
                            style = MaterialTheme.typography.titleLarge,
                            color = EmeraldBright,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Lusail Stadium Companion — Authenticated",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Countdown ring
                    Box(
                        modifier = Modifier.size(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { countdown / 3f },
                            color = EmeraldGreen,
                            strokeWidth = 3.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = "${countdown}s",
                            style = MaterialTheme.typography.headlineSmall,
                            color = EmeraldBright,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = phase == "complete",
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = "Launching Companion...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmeraldGreen.copy(alpha = 0.7f)
                )
            }
        }

        // ── Decorative fingerprint lines at bottom ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(0.15f)
        ) {
            // Pseudo-fingerprint scanning lines
            repeat(5) { i ->
                Box(
                    modifier = Modifier
                        .padding(top = (i * 8).dp)
                        .width(200.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    EmeraldGreen,
                                    Color.Transparent
                                )
                            )
                        )
                        .alpha(1f - (i * 0.2f))
                )
            }
        }
    }
}

/**
 * Holographic seal animation with rotating rings, shield icon, and glow.
 */
@Composable
private fun HolographicSeal(phase: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "seal_rotate")

    // Outer ring rotation
    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRotation"
    )

    // Inner ring counter-rotation
    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerRotation"
    )

    // Pulse for verified state
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Scale based on phase
    val sealScale = when (phase) {
        "verified" -> pulseScale
        "complete" -> 1.1f
        else -> 1f
    }

    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(sealScale),
        contentAlignment = Alignment.Center
    ) {
        // ── Outer holographic ring ──
        Box(
            modifier = Modifier
                .size(150.dp)
                .rotate(outerRotation)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            EmeraldBright,
                            EmeraldGreen.copy(alpha = 0.5f),
                            EmeraldBright,
                            GoldAccent,
                            EmeraldBright
                        )
                    ),
                    shape = CircleShape
                )
        )

        // ── Inner ring (counter-rotating) ──
        Box(
            modifier = Modifier
                .size(110.dp)
                .rotate(innerRotation)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            GoldAccent,
                            EmeraldGreen.copy(alpha = 0.6f),
                            GoldAccent,
                            EmeraldBright,
                            GoldAccent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // ── Center hexagon / shield area ──
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            EmeraldGreen.copy(alpha = 0.3f),
                            EmeraldDark.copy(alpha = 0.6f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Show shield or checkmark based on phase
            if (phase == "verified" || phase == "complete") {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Verified",
                    tint = EmeraldBright,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = "Crypto Shield",
                    tint = GoldAccent,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // ── Decorative dots around the outer ring ──
        // Fixed positions at 6 even points (cosmetic only — not exact trigonometry)
        val dotColor = if (phase == "verified" || phase == "complete")
            EmeraldBright else GoldAccent.copy(alpha = 0.7f)
        val dotSize = 6.dp

        // Top center
        Box(Modifier.offset(y = (-75).dp).size(dotSize).clip(CircleShape).background(dotColor))
        // Top right
        Box(Modifier.offset(x = 65.dp, y = (-37).dp).size(dotSize).clip(CircleShape).background(dotColor))
        // Bottom right
        Box(Modifier.offset(x = 65.dp, y = 37.dp).size(dotSize).clip(CircleShape).background(dotColor))
        // Bottom center
        Box(Modifier.offset(y = 75.dp).size(dotSize).clip(CircleShape).background(dotColor))
        // Bottom left
        Box(Modifier.offset(x = (-65).dp, y = 37.dp).size(dotSize).clip(CircleShape).background(dotColor))
        // Top left
        Box(Modifier.offset(x = (-65).dp, y = (-37).dp).size(dotSize).clip(CircleShape).background(dotColor))
    }
}

/**
 * Helper extension to convert Double to Dp using composition density context.
 * Note: Since we can't easily get density in a non-@Composable context,
 * we approximate 1dp ≈ 1px for the seal dots (cosmetic only).
 */
