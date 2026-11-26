package com.ialocal.android.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.net.ssl.HttpsURLConnection

class ModelStore(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "models")
    private val names = context.getSharedPreferences("model-names", Context.MODE_PRIVATE)
    private val recommended = ModelCatalog.recommended

    fun discardPartialFiles() {
        directory.listFiles { file -> file.extension == "partial" }?.forEach(File::delete)
    }

    fun installed(): List<InstalledModel> = directory.listFiles { file ->
        file.isFile && file.extension == "gguf" && file.nameWithoutExtension.matches(Regex("[a-f0-9]{64}"))
    }.orEmpty().mapNotNull { file ->
        val sha256 = file.nameWithoutExtension
        if (sha256 == recommended.sha256 && file.length() != recommended.bytes) return@mapNotNull null
        val name = if (sha256 == recommended.sha256) recommended.name else
            names.getString(sha256, null) ?: "Qwen3.5 · ${sha256.take(8)}"
        InstalledModel(name, sha256, file)
    }.sortedWith(compareBy({ it.sha256 != recommended.sha256 }, { it.name }))

    suspend fun download(onProgress: (Long, Long?) -> Unit): InstalledModel = install(
        label = recommended.name, suppliedHash = recommended.sha256, expectedBytes = recommended.bytes,
        onProgress = onProgress
    ) { useInput ->
        val connection = openDownload(URL(recommended.url))
        try {
            connection.inputStream.use(useInput)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun import(
        uri: Uri, label: String, suppliedHash: String, onProgress: (Long, Long?) -> Unit
    ): InstalledModel = install(label, suppliedHash, null, onProgress) { useInput ->
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ModelException("No se pudo leer el archivo elegido.")
        input.use(useInput)
    }

    fun delete(model: InstalledModel) {
        if (!model.file.delete() && model.file.exists()) throw ModelException("No se pudo eliminar el modelo.")
        names.edit().remove(model.sha256).apply()
    }

    private suspend fun install(
        label: String,
        suppliedHash: String,
        expectedBytes: Long?,
        onProgress: (Long, Long?) -> Unit,
        read: ((InputStream) -> Unit) -> Unit
    ): InstalledModel = withContext(Dispatchers.IO) {
        val sha256 = GgufTransfer.normalizedHash(suppliedHash)
        installed().firstOrNull { it.sha256 == sha256 }?.let { return@withContext it }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw ModelException("No se pudo crear la carpeta de modelos.")
        }
        val required = (expectedBytes ?: 0) + 64 * 1024 * 1024
        if (directory.usableSpace < required) throw ModelException("Libera espacio en el almacenamiento.")
        val name = label.trim().take(52).ifBlank { "Qwen3.5 · ${sha256.take(8)}" }
        val target = File(directory, "$sha256.gguf")
        val partial = File.createTempFile("model-", ".partial", directory)
        try {
            var lastUpdate = 0L
            read { input ->
                FileOutputStream(partial).use { output ->
                    GgufTransfer.copyVerified(input, output, sha256, expectedBytes) { bytes ->
                        ensureActive()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 150) {
                            if (directory.usableSpace < 32 * 1024 * 1024) {
                                throw ModelException("Se agotó el espacio disponible para el modelo.")
                            }
                            onProgress(bytes, expectedBytes)
                            lastUpdate = now
                        }
                    }
                    output.fd.sync()
                }
            }
            ensureActive()
            Files.move(partial.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            names.edit().putString(sha256, name).apply()
            InstalledModel(if (sha256 == recommended.sha256) recommended.name else name, sha256, target)
        } finally {
            partial.delete()
        }
    }

    private fun openDownload(initial: URL): HttpsURLConnection {
        var url = initial
        repeat(8) {
            if (url.protocol != "https") throw ModelException("La descarga requiere una conexión HTTPS.")
            val connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                when (val status = connection.responseCode) {
                    200 -> return connection
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw ModelException("El enlace de descarga es inválido.")
                        url = URL(url, location)
                    }
                    else -> throw ModelException("El servidor devolvió un error HTTP $status.")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw ModelException("El servidor redirigió demasiadas veces la descarga.")
    }
}
