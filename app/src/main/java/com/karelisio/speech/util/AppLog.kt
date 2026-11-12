package com.karelisio.speech.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Journal applicatif : logcat + tampon mémoire + fichier, exportable en un tap. */
object AppLog {
    private const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val PREVIOUS_LINES = 150

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private var file: File? = null
    /** Écriture disque hors du thread appelant (souvent le thread principal, en pleine dictée). */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "speech-log").apply { isDaemon = true } }

    /** Fin du journal de la session précédente (un plantage redémarre l'app : sa trace est là). */
    private var previous: String = ""

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "speech.log")
        previous = runCatching { f.readLines().takeLast(PREVIOUS_LINES).joinToString("\n") }.getOrDefault("")
        if (f.length() > MAX_FILE_BYTES) f.renameTo(File(dir, "speech.old.log"))
        file = f
        runCatching { f.appendText("\n----- nouvelle session -----\n") }
        // Exception Kotlin non rattrapée : écrite de façon synchrone avant que le process meure.
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                f.appendText("${fmt.format(Date())} E/Crash: thread ${t.name} : ${Log.getStackTraceString(e)}\n")
            }
            default?.uncaughtException(t, e)
        }
    }

    /** Écrit tout de suite (avant un appel natif risqué : un plantage natif ne laisse pas d'autre trace). */
    @Synchronized
    fun mark(tag: String, msg: String) {
        add(Log.INFO, tag, msg, null)
        if (file == null) return
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        runCatching { done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    fun i(tag: String, msg: String) = add(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add(Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add(Log.ERROR, tag, msg, t)

    @Synchronized
    private fun add(level: Int, tag: String, msg: String, t: Throwable?) {
        Log.println(level, "Speech/$tag", msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
        val lvl = when (level) { Log.ERROR -> "E"; Log.WARN -> "W"; else -> "I" }
        val line = "${fmt.format(Date())} $lvl/$tag: $msg" + (t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        file?.let { f ->
            writer.execute {
                runCatching {
                    // Le process vit des jours (service d'accessibilité) : rotation au fil de l'eau.
                    if (f.length() > MAX_FILE_BYTES) f.renameTo(File(f.parentFile, "speech.old.log"))
                    f.appendText(line + "\n")
                }
            }
        }
    }

    @Synchronized
    fun dump(): String = buildString {
        if (previous.isNotBlank()) append("----- fin de la session précédente -----\n").append(previous).append("\n----- session actuelle -----\n")
        append(lines.joinToString("\n"))
    }
}
