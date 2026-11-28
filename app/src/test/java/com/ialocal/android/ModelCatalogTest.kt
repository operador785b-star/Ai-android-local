package com.ialocal.android

import com.ialocal.android.inference.EngineProfile
import com.ialocal.android.inference.Exchange
import com.ialocal.android.model.ModelAvailability
import com.ialocal.android.model.ModelCatalog
import com.ialocal.android.model.ModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test fun routesVerifiedFilesAndRejectsUnknownDownloads() {
        assertEquals(EngineProfile.LFM3, (ModelCatalog.recommended.availability as ModelAvailability.Ready).profile)
        assertEquals(ModelCatalog.recommended, ModelCatalog.all.first())
        assertEquals(EngineProfile.entries.toSet(), ModelCatalog.all.map {
            assertEquals(it, ModelCatalog.downloadable(it.sha256))
            (it.availability as ModelAvailability.Ready).profile
        }.toSet())
        assertThrows(ModelException::class.java) { ModelCatalog.downloadable("0".repeat(64)) }
    }

    @Test fun formatsCurrentAndHistoricalAssistantTurnsForTheSelectedProfile() {
        val turns = listOf(Exchange("Hola", "Saludos"))
        val lfm = EngineProfile.LFM3.prompts(turns, "Continúa")[0].decodeToString()
        val nan = EngineProfile.NANBEIGE.prompts(turns, "Continúa")[0].decodeToString()
        assertTrue(lfm.contains("assistant\n<think></think>Saludos"))
        assertTrue(lfm.endsWith("assistant\n<think></think>"))
        assertTrue(nan.contains("assistant\n<think>\n\n</think>\n\nSaludos"))
    }
}
