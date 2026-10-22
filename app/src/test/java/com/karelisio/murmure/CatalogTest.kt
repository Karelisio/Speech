package com.karelisio.murmure

import com.karelisio.murmure.models.EngineKind
import com.karelisio.murmure.models.ModelCatalog
import com.karelisio.murmure.stt.WhisperEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {
    @Test fun bundledCatalogParses() {
        val models = ModelCatalog.parse(File("src/main/assets/models.json").readText())
        assertTrue(models.any { it.engine == EngineKind.PARAKEET })
        assertTrue(models.count { it.engine == EngineKind.WHISPER } >= 3)
        assertEquals(models.size, models.map { it.id }.toSet().size)
        models.forEach { m -> m.files.forEach { assertTrue(m.url(it).startsWith("https://huggingface.co/")) } }
    }

    @Test fun dynamicAudioCtx() {
        assertEquals(576, WhisperEngine.dynamicAudioCtx(16000 * 10)) // 10 s → 500 + 32 → 576
        assertEquals(1500, WhisperEngine.dynamicAudioCtx(16000 * 40))
    }
}
