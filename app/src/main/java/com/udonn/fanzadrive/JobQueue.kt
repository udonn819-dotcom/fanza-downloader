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

    /** 作品ごとの作業フォルダ（途中までダウンロードしたファイルなどを置く） */
    fun jobDir(c: Context, key: String): File = File(c.noBackupFilesDir, "jobs/" + sha1(key))

    /** 台帳に無い作業フォルダ（古いバージョンの残りなど）を削除して容量を空ける */
    fun cleanOrphans(c: Context) {
        val keep = all(c).keys.map { sha1(it) }.toSet()
        File(c.noBackupFilesDir, "jobs").listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
