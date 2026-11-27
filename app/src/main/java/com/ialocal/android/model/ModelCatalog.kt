package com.ialocal.android.model

import com.ialocal.android.inference.EngineProfile
import java.io.File

sealed interface ModelAvailability {
    data class Ready(val profile: EngineProfile) : ModelAvailability
    data class Candidate(val architecture: String) : ModelAvailability
}

data class CatalogModel(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
    val license: String,
    val availability: ModelAvailability
)

data class InstalledModel(
    val name: String,
    val sha256: String,
    val file: File,
    val profile: EngineProfile?
)

object ModelCatalog {
    val recommended = CatalogModel(
        name = "Liquid AI LFM3-1.7B",
        bytes = 1_099_627_808L,
        sha256 = "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4",
        url = "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/" +
            "cd0c74c575b5c416f3011b5b4c298077f26ca8c2/LFM3-1.7B-Q4_K_M.gguf",
        license = "LFM Open License 1.0",
        availability = ModelAvailability.Ready(EngineProfile.LFM3)
    )

    private val alternatives = listOf(
        CatalogModel(
            name = "Qwen3.5-2B · Q4_K_M",
            bytes = 1_280_835_840L,
            sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
            url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/" +
                "f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf",
            license = "Apache 2.0",
            availability = ModelAvailability.Ready(EngineProfile.QWEN35)
        ),
        CatalogModel(
            name = "Nanbeige 4.2 3B",
            bytes = 2_684_023_968L,
            sha256 = "b92d2e35c9876b0c4bc671996360204f4149d0546c23d5954d5eb3b106c81f24",
            url = "https://huggingface.co/42ailab/Nanbeige4.2-3B-GGUF/resolve/" +
                "362f75f134fcb874ec20e751d391d4e96d813b80/Nanbeige_Nanbeige4.2-3B-Q4_K_M.gguf",
            license = "Apache 2.0",
            availability = ModelAvailability.Ready(EngineProfile.NANBEIGE)
        )
    )

    val all = listOf(recommended) + alternatives
    val candidates = all.filter { it.availability is ModelAvailability.Candidate }

    internal fun downloadable(sha256: String): CatalogModel =
        all.firstOrNull { it.sha256 == sha256 && it.availability is ModelAvailability.Ready }
            ?: throw ModelException("Ese modelo aún requiere una versión compatible del motor.")
}
