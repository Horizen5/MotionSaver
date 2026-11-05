package com.motionsaver.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val fileName: String,
    val savedTo: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** 媒体库 content Uri，点开记录即可预览已下载的文件 */
    val previewUri: String = "",
    val mime: String = ""
)

/** 轻量持久化：SharedPreferences 存 JSON，不引 Room，减少构建环节与包体。 */
class HistoryStore(context: Context) {

    private val sp = context.getSharedPreferences("ms_history", Context.MODE_PRIVATE)

    fun load(): List<HistoryEntry> {
        val raw = sp.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    o.optString("n"),
                    o.optString("p"),
                    o.optString("d"),
                    o.optLong("t"),
                    o.optString("u"),
                    o.optString("m")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(entries: List<HistoryEntry>) {
        val all = (entries + load()).take(200)
        val arr = JSONArray()
        all.forEach { e ->
            arr.put(
                JSONObject()
                    .put("n", e.fileName)
                    .put("p", e.savedTo)
                    .put("d", e.detail)
                    .put("t", e.timestamp)
                    .put("u", e.previewUri)
                    .put("m", e.mime)
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() = sp.edit().remove(KEY).apply()

    private companion object { const val KEY = "list" }
}
