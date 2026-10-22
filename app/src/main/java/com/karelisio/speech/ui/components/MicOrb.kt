package com.karelisio.speech.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.karelisio.speech.ui.theme.RecordOrange
import com.karelisio.speech.ui.theme.RecordRed
import kotlinx.coroutines.delay

enum class OrbState { IDLE, RECORDING, PROCESSING, DISABLED }

/** Gros bouton micro : halo qui respire au repos, anneaux qui suivent la voix pendant l'enregistrement. */
@Composable
fun MicOrb(state: OrbState, level: Float, onClick: () -> Unit, size: Dp = 160.dp) {
    val scheme = MaterialTheme.colorScheme
    val recording = state == OrbState.RECORDING
    val base = if (recording) listOf(RecordRed, RecordOrange) else listOf(scheme.primary, scheme.tertiary)
    val haloColor = if (recording) RecordRed else scheme.primary

    val (breath, ripple) = if (LocalReduceMotion.current) {
        0.6f to 0.4f
    } else {
        val t = rememberInfiniteTransition(label = "orb")
        val b by t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(if (recording) 900 else 2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath",
        )
        val r by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ripple")
        b to r
    }
    val voice by animateFloatAsState(if (recording) level else 0f, tween(90), label = "voice")
    val press by animateFloatAsState(if (state == OrbState.PROCESSING) 0.94f else 1f, label = "press")

    Box(Modifier.size(size * 1.6f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.toPx() / 2
            val maxR = this.size.minDimension / 2
            if (state != OrbState.DISABLED) {
                // Halo doux qui respire.
                drawCircle(
                    Brush.radialGradient(
                        listOf(haloColor.copy(alpha = 0.28f), Color.Transparent),
                        radius = r + (maxR - r) * (0.55f + 0.45f * breath),
                    ),
                    radius = maxR,
                )
            }
            if (recording) {
                // Anneau qui s'éloigne en continu + anneau qui suit le niveau de la voix.
                drawCircle(haloColor.copy(alpha = 0.35f * (1 - ripple)), radius = r + (maxR - r) * ripple)
                drawCircle(haloColor.copy(alpha = 0.30f), radius = r + (maxR - r) * voice.coerceIn(0f, 1f))
            }
        }
        Box(
            Modifier
                .size(size)
                .scale(press)
                .clip(CircleShape)
                .background(
                    if (state == OrbState.DISABLED) Brush.linearGradient(listOf(scheme.surfaceVariant, scheme.surfaceVariant))
                    else Brush.linearGradient(base),
                )
                .clickable(enabled = state == OrbState.IDLE || state == OrbState.RECORDING, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                OrbState.PROCESSING -> CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp, modifier = Modifier.size(size * 0.32f))
                else -> Icon(
                    if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = if (recording) "Arrêter" else "Dicter",
                    tint = if (state == OrbState.DISABLED) scheme.onSurfaceVariant else Color.White,
                    modifier = Modifier.size(size * 0.38f),
                )
            }
        }
    }
}

/** Forme d'onde défilante tirée du niveau sonore (une barre toutes les 60 ms). */
@Composable
fun LiveWaveform(active: Boolean, level: Float, bars: Int = 32, height: Dp = 40.dp) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(bars) { add(0f) } } }
    val current by rememberUpdatedState(level)
    val frozen = LocalReduceMotion.current
    LaunchedEffect(active) {
        if (frozen) {
            // Aperçu statique : une forme d'onde plausible.
            for (i in history.indices) history[i] = if (active) (0.25f + 0.6f * kotlin.math.abs(kotlin.math.sin(i * 0.7f))) else 0f
            return@LaunchedEffect
        }
        if (!active) {
            for (i in history.indices) history[i] = 0f
            return@LaunchedEffect
        }
        while (true) {
            history.removeAt(0)
            history.add(current)
            delay(60)
        }
    }
    val color = if (active) RecordRed else MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        history.forEach { v ->
            Box(
                Modifier
                    .width(4.dp)
                    .height(height * (0.1f + 0.9f * v.coerceIn(0f, 1f)))
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/** Carte d'en-tête en dégradé. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val s = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(s.primaryContainer, s.tertiaryContainer))),
    ) { content() }
}
