package com.karelisio.murmure.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Journal applicatif : logcat + tampon mémoire + fichier, exportable en un tap. */
object AppLog {
    private const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 1_000_000L

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "murmure.log")
        if (f.length() > MAX_FILE_BYTES) f.renameTo(File(dir, "murmure.old.log"))
        file = f
    }

    fun i(tag: String, msg: String) = add(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add(Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add(Log.ERROR, tag, msg, t)

    @Synchronized
    private fun add(level: Int, tag: String, msg: String, t: Throwable?) {
        Log.println(level, "Murmure/$tag", msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
        val lvl = when (level) { Log.ERROR -> "E"; Log.WARN -> "W"; else -> "I" }
        val line = "${fmt.format(Date())} $lvl/$tag: $msg" + (t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        runCatching { file?.appendText(line + "\n") }
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
