package com.karelisio.speech.util

import java.io.File
import java.security.MessageDigest

/** [onChunk] est appelé à chaque Mo (ex. vérifier une annulation sur un fichier de plusieurs Go). */
fun sha256(f: File, onChunk: () -> Unit = {}): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            onChunk()
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
