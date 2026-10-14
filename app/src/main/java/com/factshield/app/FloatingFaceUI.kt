package com.factshield.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.*

// ─────────────────────────────────────────────────────────────
//  State enum
// ─────────────────────────────────────────────────────────────

enum class BallState { IDLE, SCANNING, GREEN, YELLOW, RED }

// ─────────────────────────────────────────────────────────────
//  Color palette
// ─────────────────────────────────────────────────────────────

private val ColorIdle     = Color(0xFF1E293B)
private val ColorScanning = Color(0xFF0EA5E9)
private val ColorGreen    = Color(0xFF10B981)
private val ColorYellow   = Color(0xFFF59E0B)
private val ColorRed      = Color(0xFFEF4444)

// ─────────────────────────────────────────────────────────────
//  Root composable
// ─────────────────────────────────────────────────────────────

@Composable
fun FactShieldOverlay(
    state: BallState,
    result: FactCheckResult?,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    var showPopup by remember { mutableStateOf(false) }

    LaunchedEffect(showPopup) {
        if (showPopup) { delay(6_000); showPopup = false }
    }
    LaunchedEffect(state) {
        if (state == BallState.IDLE || state == BallState.SCANNING) showPopup = false
    }

    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.wrapContentSize()
    ) {
        FactShieldBall(
            state = state,
            modifier = Modifier
                .size(60.dp)
                .shadow(elevation = 16.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .pointerInput(state) {
                    detectTapGestures(
                        onTap = {
                            when (state) {
                                BallState.IDLE, BallState.SCANNING -> onTap()
                                else -> showPopup = !showPopup
                            }
                        },
                        onLongPress = { showPopup = false; onLongPress() }
                    )
                }
        )

        Spacer(modifier = Modifier.height(8.dp))

        AnimatedVisibility(
            visible = showPopup && result != null,
            enter = fadeIn(tween(300)) + scaleIn(
                initialScale = 0.82f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
                transformOrigin = TransformOrigin(1f, 0f)
            ),
            exit = fadeOut(tween(200)) + scaleOut(
                targetScale = 0.82f, animationSpec = tween(200),
                transformOrigin = TransformOrigin(1f, 0f)
            )
        ) {
            result?.let { FactPopupCard(result = it, onDismiss = { showPopup = false }) }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Animated Canvas Ball
// ─────────────────────────────────────────────────────────────

@Composable
fun FactShieldBall(state: BallState, modifier: Modifier = Modifier) {

    val targetColor = when (state) {
        BallState.IDLE     -> ColorIdle
        BallState.SCANNING -> ColorScanning
        BallState.GREEN    -> ColorGreen
        BallState.YELLOW   -> ColorYellow
        BallState.RED      -> ColorRed
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 500),
        label = "ballColor"
    )

    val inf = rememberInfiniteTransition(label = "ball")

    val gazeT by inf.animateFloat(
        initialValue = 0f, targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "gazeT"
    )

    val blinkProgress by inf.animateFloat(
        initialValue = 0f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4000
                0f at 0; 0f at 3500; 1f at 3650; 0f at 3800; 0f at 4000
            }
        ),
        label = "blink"
    )

    val orbitalAngle by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "orbital"
    )

    val ringPulse by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing)),
        label = "ringPulse"
    )

    val eyeScanX by inf.animateFloat(
        initialValue = -7f, targetValue = 7f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "eyeScan"
    )

    val glowAlpha by inf.animateFloat(
        initialValue = 0.25f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "glow"
    )

    val pulseScale by inf.animateFloat(
        initialValue = 1f, targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label = "pulse"
    )

    val bobOffset by inf.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "bob"
    )

    val gazeX = sin(gazeT * 1.3f) * 4.5f
    val gazeY = sin(gazeT * 0.8f) * 2.5f

    Canvas(modifier = modifier) {
        clipRect {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r  = size.minDimension / 2f * 0.97f

            if (state != BallState.IDLE && state != BallState.SCANNING) {
                drawCircle(
                    color = animatedColor.copy(alpha = glowAlpha * 0.55f),
                    radius = r * 1.25f, center = Offset(cx, cy)
                )
            }

            val bgRadius = if (state == BallState.RED) r * pulseScale else r
            drawCircle(color = animatedColor, radius = bgRadius, center = Offset(cx, cy))
            drawCircle(
                color = Color.Black.copy(alpha = 0.22f),
                radius = bgRadius * 0.86f, center = Offset(cx, cy)
            )

            if (state == BallState.SCANNING) {
                for (i in 0..1) {
                    val phase = (ringPulse + i * 0.5f) % 1f
                    val ringR = bgRadius * 0.55f + bgRadius * 0.55f * phase
                    val alpha = (1f - phase) * 0.45f
                    drawCircle(
                        color = Color.Cyan.copy(alpha = alpha),
                        radius = ringR, center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                rotate(orbitalAngle, pivot = Offset(cx, cy)) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Cyan, Color.Cyan.copy(alpha = 0f))),
                        startAngle = 0f, sweepAngle = 240f, useCenter = false,
                        topLeft = Offset(cx - r + 3f, cy - r + 3f),
                        size = Size((r - 3f) * 2f, (r - 3f) * 2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                rotate(-orbitalAngle * 0.7f, pivot = Offset(cx, cy)) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Color(0xFF818CF8), Color(0xFF818CF8).copy(alpha = 0f))
                        ),
                        startAngle = 90f, sweepAngle = 180f, useCenter = false,
                        topLeft = Offset(cx - r * 0.72f, cy - r * 0.72f),
                        size = Size(r * 1.44f, r * 1.44f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            when (state) {
                BallState.IDLE     -> drawIdleFace(cx, cy, r, blinkProgress, gazeX, gazeY)
                BallState.SCANNING -> drawScanningFace(cx, cy, r, eyeScanX)
                BallState.GREEN    -> drawGreenFace(cx, cy + bobOffset, r)
                BallState.YELLOW   -> drawYellowFace(cx, cy, r)
                BallState.RED      -> drawRedFace(cx, cy, r, pulseScale)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Face drawing helpers
// ─────────────────────────────────────────────────────────────

private fun DrawScope.drawIdleFace(
    cx: Float, cy: Float, r: Float,
    blinkProgress: Float, gazeX: Float, gazeY: Float
) {
    val eyeBaseY   = cy - r * 0.16f
    val eyeR       = r * 0.115f
    val pupilR     = eyeR * 0.52f
    val eyeOffsetX = r * 0.28f

    drawCircle(color = Color.White, radius = eyeR, center = Offset(cx - eyeOffsetX, eyeBaseY))
    drawCircle(color = Color.White, radius = eyeR, center = Offset(cx + eyeOffsetX, eyeBaseY))

    if (blinkProgress > 0f) {
        val h = eyeR * 2f * blinkProgress
        for (sign in listOf(-1f, 1f)) {
            val ex = cx + sign * eyeOffsetX
            drawOval(color = ColorIdle,
                topLeft = Offset(ex - eyeR, eyeBaseY - eyeR),
                size = Size(eyeR * 2f, h))
        }
    }

    val maxDrift = eyeR * 0.38f
    val px = gazeX.coerceIn(-maxDrift, maxDrift)
    val py = gazeY.coerceIn(-maxDrift, maxDrift)
    drawCircle(color = Color(0xFF1E293B), radius = pupilR, center = Offset(cx - eyeOffsetX + px, eyeBaseY + py))
    drawCircle(color = Color(0xFF1E293B), radius = pupilR, center = Offset(cx + eyeOffsetX + px, eyeBaseY + py))

    val hlR = pupilR * 0.32f
    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = hlR,
        center = Offset(cx - eyeOffsetX + px + pupilR * 0.3f, eyeBaseY + py - pupilR * 0.3f))
    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = hlR,
        center = Offset(cx + eyeOffsetX + px + pupilR * 0.3f, eyeBaseY + py - pupilR * 0.3f))

    drawArc(
        color = Color.White.copy(alpha = 0.8f),
        startAngle = 10f, sweepAngle = 160f, useCenter = false,
        topLeft = Offset(cx - r * 0.24f, cy + r * 0.10f),
        size = Size(r * 0.48f, r * 0.22f),
        style = Stroke(width = 2.2f.dp.toPx(), cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawScanningFace(cx: Float, cy: Float, r: Float, eyeScanX: Float) {
    val eyeBaseY   = cy - r * 0.16f
    val eyeOffsetX = r * 0.28f
    val eyeR       = r * 0.105f

    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = eyeR,
        center = Offset(cx - eyeOffsetX, eyeBaseY))
    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = eyeR,
        center = Offset(cx + eyeOffsetX, eyeBaseY))

    val clamp = eyeR * 0.4f
    val px = eyeScanX.coerceIn(-clamp, clamp)
    drawCircle(color = Color.Cyan, radius = eyeR * 0.55f,
        center = Offset(cx - eyeOffsetX + px, eyeBaseY))
    drawCircle(color = Color.Cyan, radius = eyeR * 0.55f,
        center = Offset(cx + eyeOffsetX + px, eyeBaseY))
    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = eyeR * 0.18f,
        center = Offset(cx - eyeOffsetX + px + eyeR * 0.2f, eyeBaseY - eyeR * 0.2f))
    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = eyeR * 0.18f,
        center = Offset(cx + eyeOffsetX + px + eyeR * 0.2f, eyeBaseY - eyeR * 0.2f))

    drawLine(
        color = Color.White.copy(alpha = 0.75f),
        start = Offset(cx - r * 0.16f, cy + r * 0.28f),
        end   = Offset(cx + r * 0.16f, cy + r * 0.28f),
        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
    )
}

private fun DrawScope.drawGreenFace(cx: Float, cy: Float, r: Float) {
    val eyeY       = cy - r * 0.18f
    val eyeOffsetX = r * 0.27f
    for (sign in listOf(-1f, 1f)) {
        drawArc(
            color = Color.White,
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(cx + sign * eyeOffsetX - r * 0.10f, eyeY - r * 0.09f),
            size = Size(r * 0.20f, r * 0.18f),
            style = Stroke(width = 2.2f.dp.toPx(), cap = StrokeCap.Round)
        )
    }
    drawArc(
        color = Color.White,
        startAngle = 10f, sweepAngle = 160f, useCenter = false,
        topLeft = Offset(cx - r * 0.28f, cy + r * 0.12f),
        size = Size(r * 0.56f, r * 0.28f),
        style = Stroke(width = 2.8f.dp.toPx(), cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawYellowFace(cx: Float, cy: Float, r: Float) {
    val eyeY       = cy - r * 0.18f
    val eyeOffsetX = r * 0.28f
    val eyeR       = r * 0.095f

    drawLine(color = Color.White,
        start = Offset(cx - eyeOffsetX - r * 0.10f, eyeY - r * 0.24f),
        end   = Offset(cx - eyeOffsetX + r * 0.10f, eyeY - r * 0.33f),
        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color = Color.White,
        start = Offset(cx + eyeOffsetX - r * 0.10f, eyeY - r * 0.22f),
        end   = Offset(cx + eyeOffsetX + r * 0.10f, eyeY - r * 0.22f),
        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)

    drawCircle(color = Color.White, radius = eyeR, center = Offset(cx - eyeOffsetX, eyeY))
    drawCircle(color = Color.White, radius = eyeR, center = Offset(cx + eyeOffsetX, eyeY))

    val mY = cy + r * 0.26f
    val pts = listOf(
        Offset(cx - r * 0.22f, mY), Offset(cx - r * 0.07f, mY - r * 0.08f),
        Offset(cx + r * 0.07f, mY + r * 0.04f), Offset(cx + r * 0.22f, mY - r * 0.04f)
    )
    for (i in 0 until pts.size - 1) {
        drawLine(color = Color.White, start = pts[i], end = pts[i + 1],
            strokeWidth = 2.2f.dp.toPx(), cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawRedFace(cx: Float, cy: Float, r: Float, pulseScale: Float) {
    val eyeY       = cy - r * 0.18f
    val eyeOffsetX = r * 0.28f
    val eyeR       = r * 0.13f * pulseScale

    drawLine(color = Color.White,
        start = Offset(cx - eyeOffsetX - r * 0.10f, eyeY - r * 0.30f),
        end   = Offset(cx - eyeOffsetX + r * 0.10f, eyeY - r * 0.20f),
        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color = Color.White,
        start = Offset(cx + eyeOffsetX - r * 0.10f, eyeY - r * 0.20f),
        end   = Offset(cx + eyeOffsetX + r * 0.10f, eyeY - r * 0.30f),
        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)

    for (sign in listOf(-1f, 1f)) {
        val ex = cx + sign * eyeOffsetX
        drawCircle(color = Color.White, radius = eyeR, center = Offset(ex, eyeY))
        drawCircle(color = ColorRed.copy(alpha = 0.9f), radius = eyeR * 0.55f, center = Offset(ex, eyeY))
        drawCircle(color = Color.Black, radius = eyeR * 0.22f, center = Offset(ex, eyeY))
    }

    drawOval(color = Color.White,
        topLeft = Offset(cx - r * 0.13f, cy + r * 0.14f),
        size    = Size(r * 0.26f, r * 0.22f))
    drawOval(color = Color(0xFF7F1D1D),
        topLeft = Offset(cx - r * 0.09f, cy + r * 0.17f),
        size    = Size(r * 0.18f, r * 0.15f))
}

// ─────────────────────────────────────────────────────────────
//  Toast Popup Card
// ─────────────────────────────────────────────────────────────

@Composable
fun FactPopupCard(result: FactCheckResult, onDismiss: () -> Unit) {
    val (bgColor, statusLabel) = when (result.status) {
        FactStatus.GREEN  -> Pair(Color(0xFF064E3B), "LIKELY RELIABLE")
        FactStatus.YELLOW -> Pair(Color(0xFF451A03), "NEEDS VERIFICATION")
        FactStatus.RED    -> Pair(Color(0xFF450A0A), "POTENTIAL FAKE")
    }
    val accentColor = when (result.status) {
        FactStatus.GREEN  -> ColorGreen
        FactStatus.YELLOW -> ColorYellow
        FactStatus.RED    -> ColorRed
    }

    Surface(
        modifier = Modifier
            .width(265.dp)
            .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = accentColor) }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusLabel, color = accentColor, fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(accentColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = "${result.confidence}%", color = accentColor,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = result.shortVerdict, color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, lineHeight = 17.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis
            )

            if (result.sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "SOURCES:", color = Color(0xFF64748B),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    result.sources.take(2).forEach { source ->
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B))
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "\uD83C\uDF10 $source",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tap card to dismiss", color = Color(0xFF475569),
                fontSize = 9.sp, modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
