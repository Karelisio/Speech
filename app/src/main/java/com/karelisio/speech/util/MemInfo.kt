package com.karelisio.speech.util

import java.io.File

object MemInfo {
    /** RSS du process en Mo (inclut les modèles natifs chargés en mémoire). */
    fun rssMb(): Long = readStatusKb("VmRSS") / 1024

    private fun readStatusKb(key: String): Long = runCatching {
        File("/proc/self/status").useLines { seq ->
            seq.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()
        } ?: 0L
    }.getOrDefault(0L)
}
