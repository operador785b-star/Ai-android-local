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
        bytes = 1_312_164_352L,
        sha256 = "9a3d0aa6c920f096441139b6a254542da855223fdad06855ea00c6c087fd713d",
        url = "https://huggingface.co/gguf/Qwen3.5-2B-GGUF/resolve/" +
            "47f5192dcee31b32db0ba0acc04cccef89cb4b2c/qwen3.5-2b-Q4_K_M.gguf"
    )
}
