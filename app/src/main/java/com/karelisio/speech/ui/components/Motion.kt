package com.karelisio.speech.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Désactive les animations infinies (captures d'écran de test, et à terme « réduire les animations »). */
val LocalReduceMotion = staticCompositionLocalOf { false }
