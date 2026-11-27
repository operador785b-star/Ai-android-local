package com.ialocal.android.inference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class InferenceJournal(context: Context) {
    private val prefs = context.getSharedPreferences("inference-journal", Context.MODE_PRIVATE)

    fun start(model: String, settings: InferenceSettings): Long {
        val id = System.currentTimeMillis()
        write((read() + JSONObject()
            .put("id", id).put("modelo", model)
            .put("contexto", settings.contextTokens).put("hilos", settings.threads)
            .put("resultado", "EN_CURSO")).takeLast(16))
        return id
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
                .put("codigo_motor", metrics.code)
                .put("tokens_por_segundo", metrics.tokensPerSecond ?: JSONObject.NULL)
        }
        write(records)
    }

    fun recover() {
        val entries = read()
        val pending = entries.filter { it.optString("resultado") == "EN_CURSO" }
        if (pending.isNotEmpty()) {
            pending.forEach { it.put("resultado", "INTERRUMPIDA") }
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
