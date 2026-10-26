package com.udonn.fanzadrive

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 保存待ちの作品の一覧を端末に記録しておく台帳。
 *
 * - 同じ作品が台帳にあれば二重に予約しない（重複の自動除外）
 * - 一時停止中やアプリのアップデート後でも、台帳から予約し直して続きから再開できる
 * - 作業途中のファイルは作品ごとのフォルダに残るので、ダウンロードは途中から再開できる
 */
object JobQueue {
    private const val NAME = "job_queue"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun contains(c: Context, key: String): Boolean = sp(c).contains(key)

    @Synchronized
    fun put(c: Context, key: String, job: JSONObject) {
        sp(c).edit().putString(key, job.toString()).commit()
    }

    @Synchronized
    fun remove(c: Context, key: String) {
        sp(c).edit().remove(key).commit()
        jobDir(c, key).deleteRecursively()
    }

    @Synchronized
    fun all(c: Context): Map<String, JSONObject> =
        sp(c).all.mapNotNull { (k, v) -> runCatching { k to JSONObject(v as String) }.getOrNull() }.toMap()

    fun size(c: Context): Int = sp(c).all.size

    /** 保存待ちをすべて取り消す（途中のファイルも削除） */
    @Synchronized
    fun clearAll(c: Context) {
        sp(c).all.keys.forEach { jobDir(c, it).deleteRecursively() }
        sp(c).edit().clear().commit()
    }

    // ---- 失敗したが途中までのデータを残してある作品（押し直すと続きから再開） ----
    private const val KEPT = "job_kept"
    private const val KEEP_DAYS = 14L

    private fun kept(c: Context) = c.getSharedPreferences(KEPT, Context.MODE_PRIVATE)

    /** 保存待ちからは外すが、途中のデータは残しておく */
    @Synchronized
    fun keep(c: Context, key: String, title: String?, cid: String?) {
        sp(c).edit().remove(key).commit()
        val info = JSONObject().put("title", title.orEmpty()).put("cid", cid.orEmpty()).put("time", System.currentTimeMillis())
        kept(c).edit().putString(key, info.toString()).commit()
    }

    /** 同じ作品の残しておいたデータがあれば、その名前を返す */
    @Synchronized
    fun findKept(c: Context, cid: String?, title: String?): String? =
        kept(c).all.entries.firstOrNull { (_, v) ->
            val o = runCatching { JSONObject(v as String) }.getOrNull() ?: return@firstOrNull false
            (!cid.isNullOrBlank() && o.optString("cid") == cid) || (!title.isNullOrBlank() && o.optString("title") == title)
        }?.key

    @Synchronized
    fun dropKept(c: Context, key: String) {
        kept(c).edit().remove(key).commit()
    }

    fun keptCount(c: Context): Int = kept(c).all.size

    fun keptBytes(c: Context): Long = kept(c).all.keys.sumOf { k ->
        jobDir(c, k).listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** 残しておいたデータをすべて削除 */
    @Synchronized
    fun clearKept(c: Context) {
        kept(c).all.keys.forEach { jobDir(c, it).deleteRecursively() }
        kept(c).edit().clear().commit()
    }

    /** 古くなった（14日以上前の）残しデータを削除 */
    @Synchronized
    private fun dropOldKept(c: Context) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000
        kept(c).all.forEach { (k, v) ->
            val time = runCatching { JSONObject(v as String).optLong("time") }.getOrDefault(0L)
            if (time < limit) {
                jobDir(c, k).deleteRecursively()
                kept(c).edit().remove(k).commit()
            }
        }
    }

    /** 作品ごとの作業フォルダ（途中までダウンロードしたファイルなどを置く） */
    fun jobDir(c: Context, key: String): File = File(c.noBackupFilesDir, "jobs/" + sha1(key))

    /** 台帳に無い作業フォルダ（古いバージョンの残りなど）を削除して容量を空ける */
    fun cleanOrphans(c: Context) {
        dropOldKept(c)
        val keep = (all(c).keys + kept(c).all.keys).map { sha1(it) }.toSet()
        File(c.noBackupFilesDir, "jobs").listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
