package com.ialocal.android.inference

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

class InferenceJournal(private val context: Context) {
    private val prefs = context.getSharedPreferences("inference-journal", Context.MODE_PRIVATE)

    fun start(model: String, settings: InferenceSettings, modelBytes: Long? = null): Long {
        val id = System.currentTimeMillis()
        write((read() + JSONObject()
            .put("id", id).put("modelo", model)
            .put("modelo_bytes", modelBytes)
            .put("contexto", settings.contextTokens).put("hilos", settings.threads)
            .put("etapa", InferencePhase.LOADING_MODEL.name)
            .put("resultado", "EN_CURSO")).takeLast(16))
        return id
    }

    fun updatePhase(id: Long, phase: InferencePhase) {
        val records = read()
        val record = records.firstOrNull { it.optLong("id") == id } ?: return
        record.put("etapa", phase.name)
        write(records)
    }

    fun finish(id: Long, outcome: String, metrics: InferenceMetrics?) {
        val records = read()
        val record = records.firstOrNull { it.optLong("id") == id } ?: return
        record.put("resultado", outcome)
        if (metrics != null) {
            record.put("tokens_entrada", metrics.promptTokens)
                .put("tokens_salida", metrics.outputTokens)
                .put("total_ms", metrics.totalMs)
                .put("primer_fragmento_ms", metrics.firstPieceMs.takeIf { it >= 0 } ?: JSONObject.NULL)
                .put("pico_rss_proceso_kib", metrics.processPeakRssKiB)
                .put("contexto_motor", metrics.effectiveContext.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("codigo_motor", metrics.code)
                .put("tokens_por_segundo", metrics.tokensPerSecond ?: JSONObject.NULL)
        }
        write(records)
    }

    fun recover() {
        val entries = read()
        val pending = entries.filter { it.optString("resultado") == "EN_CURSO" }
        if (pending.isNotEmpty()) {
            val lastExit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    context.getSystemService(ActivityManager::class.java)
                        ?.getHistoricalProcessExitReasons(null, 0, 8)
                        ?.firstOrNull { it.processName == context.packageName }
                }.getOrNull()
            } else null
            for (record in pending) {
                record.put("resultado", "INTERRUMPIDA")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    lastExit != null && lastExit.timestamp >= record.optLong("id")) {
                    record.put("codigo_salida_android", lastExit.reason)
                    val reason = when (lastExit.reason) {
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "MEMORIA_INSUFICIENTE"
                        ApplicationExitInfo.REASON_CRASH -> "FALLO_DE_APLICACION"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "FALLO_NATIVO"
                        ApplicationExitInfo.REASON_ANR -> "APLICACION_SIN_RESPUESTA"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "CIERRE_SOLICITADO"
                        ApplicationExitInfo.REASON_SIGNALED -> "PROCESO_TERMINADO_POR_SEÑAL"
                        else -> "OTRO"
                    }
                    record.put("motivo_salida_android", reason)
                }
            }
            write(entries)
        }
    }

    fun report(): String {
        val entries = read()
        return if (entries.isEmpty()) "" else JSONArray(entries).toString(2)
    }

    private fun read(): List<JSONObject> {
        val saved = prefs.getString("records", null) ?: return emptyList()
        val array = runCatching { JSONArray(saved) }.getOrElse { return emptyList() }
        return List(array.length()) { array.optJSONObject(it) }.filterNotNull()
    }

    private fun write(records: List<JSONObject>) {
        prefs.edit().putString("records", JSONArray(records).toString()).commit()
    }
}
