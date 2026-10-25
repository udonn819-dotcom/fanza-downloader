package com.udonn.fanzadrive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 保存の結果（完了・失敗）の記録。「保存状況」画面に表示する */
object JobLog {
    private const val NAME = "job_log"
    private const val MAX = 300

    data class Entry(val ok: Boolean, val title: String, val message: String?, val time: Long)

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun add(c: Context, ok: Boolean, title: String, message: String?) {
        val arr = runCatching { JSONArray(sp(c).getString("log", "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("ok", ok).put("t", title).put("m", message.orEmpty()).put("at", System.currentTimeMillis()))
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - MAX) until arr.length()) trimmed.put(arr.get(i))
        sp(c).edit().putString("log", trimmed.toString()).commit()
    }

    @Synchronized
    fun all(c: Context): List<Entry> {
        val arr = runCatching { JSONArray(sp(c).getString("log", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.optBoolean("ok"), o.optString("t"), o.optString("m").ifBlank { null }, o.optLong("at"))
        }
    }

    @Synchronized
    fun clear(c: Context) {
        sp(c).edit().remove("log").commit()
    }
}
