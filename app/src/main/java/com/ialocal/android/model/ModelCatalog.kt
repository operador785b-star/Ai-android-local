package com.ialocal.android.model

import java.io.File

data class CatalogModel(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val url: String
)

data class InstalledModel(val name: String, val sha256: String, val file: File)

object ModelCatalog {
    val recommended = CatalogModel(
        name = "Qwen3.5-2B · Q4_K_M",
        bytes = 1_280_835_840L,
        sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
        url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/" +
            "f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf"
    )
}
