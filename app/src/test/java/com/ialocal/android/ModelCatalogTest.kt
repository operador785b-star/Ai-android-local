package com.ialocal.android

import com.ialocal.android.inference.EngineProfile
import com.ialocal.android.model.ModelAvailability
import com.ialocal.android.model.ModelCatalog
import com.ialocal.android.model.ModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelCatalogTest {
    @Test fun onlyTheSupportedCatalogEntryCanReachDownload() {
        val qwen = ModelCatalog.recommended
        assertEquals(qwen, ModelCatalog.downloadable(qwen.sha256))
        assertEquals(ModelAvailability.Ready(EngineProfile.QWEN35), qwen.availability)
        for (candidate in ModelCatalog.candidates) {
            assertThrows(ModelException::class.java) { ModelCatalog.downloadable(candidate.sha256) }
        }
    }

    @Test fun candidatesDescribeDistinctEngineArchitectures() {
        assertEquals(listOf("lfm3", "nanbeige"), ModelCatalog.candidates.map {
            (it.availability as ModelAvailability.Candidate).architecture
        })
    }
}
