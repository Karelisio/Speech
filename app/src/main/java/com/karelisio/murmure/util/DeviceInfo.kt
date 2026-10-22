package com.karelisio.murmure.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.karelisio.murmure.BuildConfig
import java.io.File

object DeviceInfo {
    /** Fréquence max (kHz) de chaque cœur, dans l'ordre des CPU. */
    fun cpuMaxFreqsKHz(): List<Long> {
        val n = Runtime.getRuntime().availableProcessors()
        return (0 until n).map { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrDefault(0L)
        }
    }

    /**
     * Cœurs « performance » : ceux dont la fréquence max atteint au moins 75 % de la plus haute.
     * Sur Snapdragon 8 Elite (2 × 4,32 GHz + 6 × 3,53 GHz), les 8 cœurs Oryon sont retenus.
     */
    fun performanceCoreCount(): Int {
        val freqs = cpuMaxFreqsKHz()
        val max = freqs.maxOrNull() ?: 0L
        if (max <= 0L) return Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        return freqs.count { it >= max * 3 / 4 }.coerceAtLeast(1)
    }

    fun totalRamMb(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    fun summary(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val freqs = cpuMaxFreqsKHz().groupingBy { it }.eachCount().entries
            .sortedByDescending { it.key }
            .joinToString(" + ") { "${it.value}×%.2f GHz".format(it.key / 1e6) }
        return buildString {
            appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("SoC : $soc — CPU : $freqs — cœurs perf : ${performanceCoreCount()}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) — RAM : ${totalRamMb(context)} Mo")
            append("Murmure ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
        }
    }
}
