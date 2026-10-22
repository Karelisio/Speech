package com.karelisio.speech

import com.karelisio.speech.models.EngineKind
import com.karelisio.speech.models.ModelCatalog
import com.karelisio.speech.stt.WhisperEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {
    @Test fun bundledCatalogParses() {
        val models = ModelCatalog.parse(File("src/main/assets/models.json").readText())
        assertTrue(models.any { it.engine == EngineKind.PARAKEET })
        assertEquals(1, models.count { it.engine == EngineKind.VAD })
        assertTrue(models.count { it.engine == EngineKind.WHISPER } >= 2)
        assertEquals(models.size, models.map { it.id }.toSet().size)
        models.forEach { m ->
            m.files.forEach {
                if (it.url == null) {
                    assertTrue("révision non épinglée : ${m.id}", m.revision.matches(Regex("[0-9a-f]{40}")))
                    assertTrue(m.url(it).startsWith("https://huggingface.co/"))
                } else {
                    assertTrue(m.url(it).startsWith("https://"))
                }
                assertTrue("taille manquante : ${it.path}", it.size > 0)
                assertTrue("sha256 manquant : ${it.path}", it.sha256?.matches(Regex("[0-9a-f]{64}")) == true)
            }
        }
    }

    @Test fun dynamicAudioCtx() {
        assertEquals(576, WhisperEngine.dynamicAudioCtx(16000 * 10)) // 10 s → 500 + 32 → 576
        assertEquals(1500, WhisperEngine.dynamicAudioCtx(16000 * 40))
    }
}
