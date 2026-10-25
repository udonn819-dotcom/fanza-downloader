package com.udonn.fanzadrive

import android.content.Context

/** アプリの設定と「保存済み作品」の履歴を端末内に記録する */
object Prefs {
    private const val NAME = "fanza_prefs"
    private const val KEY_GAS_URL = "gas_url"
    private const val KEY_GAS_KEY = "gas_key"
    private const val KEY_SAVE_LOCAL = "save_local"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_AUTO_SCAN = "auto_scan"
    private const val KEY_DONE = "done_keys"
    private const val KEY_MODE = "drive_mode"
    private const val KEY_ACCOUNT = "google_account"
    private const val KEY_FOLDER_ID = "drive_folder_id"

    const val MODE_API = "api"
    const val MODE_GAS = "gas"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun gasUrl(c: Context): String = sp(c).getString(KEY_GAS_URL, "")!!.trim()
    fun gasKey(c: Context): String = sp(c).getString(KEY_GAS_KEY, "")!!.trim()
    fun saveLocal(c: Context): Boolean = sp(c).getBoolean(KEY_SAVE_LOCAL, true)
    fun wifiOnly(c: Context): Boolean = sp(c).getBoolean(KEY_WIFI_ONLY, false)
    fun autoScan(c: Context): Boolean = sp(c).getBoolean(KEY_AUTO_SCAN, false)
    /** Googleドライブへの保存方式（API＝アプリから直接 / GAS＝中継スクリプト経由） */
    fun driveMode(c: Context): String = sp(c).getString(KEY_MODE, MODE_API)!!
    fun setDriveMode(c: Context, mode: String) = sp(c).edit().putString(KEY_MODE, mode).apply()

    /** API方式でログインしたGoogleアカウント（メールアドレス） */
    fun googleAccount(c: Context): String = sp(c).getString(KEY_ACCOUNT, "")!!
    fun setGoogleAccount(c: Context, email: String) =
        sp(c).edit().putString(KEY_ACCOUNT, email).remove(KEY_FOLDER_ID).apply()

    fun driveFolderId(c: Context): String = sp(c).getString(KEY_FOLDER_ID, "")!!
    fun setDriveFolderId(c: Context, id: String) = sp(c).edit().putString(KEY_FOLDER_ID, id).apply()

    fun driveEnabled(c: Context): Boolean = when (driveMode(c)) {
        MODE_API -> googleAccount(c).isNotBlank()
        else -> gasUrl(c).startsWith("https://")
    }

    fun save(c: Context, gasUrl: String, gasKey: String, saveLocal: Boolean, wifiOnly: Boolean, autoScan: Boolean) {
        sp(c).edit()
            .putString(KEY_GAS_URL, gasUrl.trim())
            .putString(KEY_GAS_KEY, gasKey.trim())
            .putBoolean(KEY_SAVE_LOCAL, saveLocal)
            .putBoolean(KEY_WIFI_ONLY, wifiOnly)
            .putBoolean(KEY_AUTO_SCAN, autoScan)
            .apply()
    }

    /** 作品ID（無ければURL）単位で「保存済み」を記録し、二重保存を防ぐ */
    @Synchronized
    fun isDone(c: Context, key: String): Boolean = sp(c).getStringSet(KEY_DONE, emptySet())!!.contains(key)

    @Synchronized
    fun markDone(c: Context, key: String) {
        val set = HashSet(sp(c).getStringSet(KEY_DONE, emptySet())!!)
        set.add(key)
        sp(c).edit().putStringSet(KEY_DONE, set).apply()
    }

    fun doneCount(c: Context): Int = sp(c).getStringSet(KEY_DONE, emptySet())!!.size

    fun clearDone(c: Context) {
        sp(c).edit().remove(KEY_DONE).apply()
    }
}
