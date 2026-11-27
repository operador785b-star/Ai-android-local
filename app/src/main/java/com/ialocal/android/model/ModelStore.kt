package com.ialocal.android.model

import android.content.Context
import android.net.Uri
import com.ialocal.android.inference.EngineProfile
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
        val catalog = ModelCatalog.all.firstOrNull { it.sha256 == sha256 }
        val name = catalog?.name ?: names.getString(sha256, null) ?: "GGUF · ${sha256.take(8)}"
        val profileKey = "profile-$sha256"
        val profile = when (val availability = catalog?.availability) {
            is ModelAvailability.Ready -> availability.profile.takeIf { file.length() == catalog.bytes }
            is ModelAvailability.Candidate -> null
            null -> names.getString(profileKey, null)?.let { saved ->
                EngineProfile.entries.firstOrNull { it.name == saved }
            } ?: EngineProfile.inspect(file)?.also { names.edit().putString(profileKey, it.name).apply() }
        }
        InstalledModel(name, sha256, file, profile)
    }.sortedWith(compareBy({ it.sha256 != recommended.sha256 }, { it.name }))

    suspend fun download(sha256: String, onProgress: (Long, Long?) -> Unit): InstalledModel {
        val model = ModelCatalog.downloadable(sha256)
        val expectedProfile = (model.availability as ModelAvailability.Ready).profile
        return install(
            label = model.name, suppliedHash = model.sha256, expectedBytes = model.bytes,
            expectedProfile = expectedProfile, onProgress = onProgress
        ) { useInput ->
            val connection = openDownload(URL(model.url))
            try {
                val length = connection.contentLengthLong
                if (length > 0 && length != model.bytes) {
                    throw ModelException("El tamaño anunciado por el servidor no coincide con el catálogo.")
                }
                connection.inputStream.use(useInput)
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun import(
        uri: Uri, label: String, suppliedHash: String, onProgress: (Long, Long?) -> Unit
    ): InstalledModel = install(label, suppliedHash, null, null, onProgress) { useInput ->
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ModelException("No se pudo leer el archivo elegido.")
        input.use(useInput)
    }

    fun delete(model: InstalledModel) {
        if (!model.file.delete() && model.file.exists()) throw ModelException("No se pudo eliminar el modelo.")
        names.edit().remove(model.sha256).remove("profile-${model.sha256}").apply()
    }

    private suspend fun install(
        label: String,
        suppliedHash: String,
        expectedBytes: Long?,
        expectedProfile: EngineProfile?,
        onProgress: (Long, Long?) -> Unit,
        read: ((InputStream) -> Unit) -> Unit
    ): InstalledModel = withContext(Dispatchers.IO) {
        val sha256 = GgufTransfer.normalizedHash(suppliedHash)
        if (ModelCatalog.candidates.any { it.sha256 == sha256 }) {
            throw ModelException("Este candidato aún requiere una versión compatible del motor.")
        }
        installed().firstOrNull { it.sha256 == sha256 && it.profile != null }?.let { return@withContext it }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw ModelException("No se pudo crear la carpeta de modelos.")
        }
        val required = (expectedBytes ?: 0) + 64 * 1024 * 1024
        if (directory.usableSpace < required) throw ModelException("Libera espacio en el almacenamiento.")
        val name = label.trim().take(52).ifBlank { "GGUF · ${sha256.take(8)}" }
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
            val profile = EngineProfile.inspect(partial)
                ?: throw ModelException("El formato de este GGUF no es compatible con el motor instalado.")
            if (expectedProfile != null && expectedProfile != profile) {
                throw ModelException("El perfil del GGUF no coincide con el catálogo.")
            }
            ensureActive()
            Files.move(partial.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            names.edit().putString(sha256, name).putString("profile-$sha256", profile.name).apply()
            InstalledModel(if (sha256 == recommended.sha256) recommended.name else name, sha256, target, profile)
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
