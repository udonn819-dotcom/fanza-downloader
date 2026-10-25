package com.udonn.fanzadrive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_PROGRESS = "progress"
    const val CH_RESULT = "result"

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "保存処理中", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "保存結果", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** 処理中に出し続ける通知（全員で共通の1つ） */
    const val SUMMARY_ID = 1000

    /** 全部終わったとき・一時停止したときの通知 */
    private const val FINISHED_ID = 1001

    /** 件数は「完了・失敗の数」と「今台帳に残っている数」から毎回計算するので、ずれない */
    private data class Counts(val done: Int, val failed: Int, val remaining: Int) {
        val total get() = done + failed + remaining
        val text get() = "完了 ${done}件 ・ 失敗 ${failed}件 ・ 残り ${remaining}件"
    }

    private fun counts(c: Context) =
        Counts(Prefs.stat(c, Prefs.ST_DONE), Prefs.stat(c, Prefs.ST_FAILED), JobQueue.size(c))

    /** 「全体で何件・完了・失敗・残り」だけを表示する処理中の通知 */
    fun summary(c: Context): Notification {
        createChannels(c)
        val n = counts(c)
        val total = n.total
        return NotificationCompat.Builder(c, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FANZA保存中  ${n.done + n.failed} / ${total}件")
            .setContentText(n.text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(maxOf(total, 1), n.done + n.failed, false)
            .setContentIntent(openApp(c))
            .build()
    }

    /** 全部終わったときのまとめ通知 */
    fun finished(c: Context) {
        val n = counts(c)
        if (n.total == 0) return
        val extra = if (n.failed > 0) "\n失敗した作品は「保存状況」で理由を確認できます" else ""
        post(c, "保存が終わりました（全${n.total}件）", "完了 ${n.done}件 ・ 失敗 ${n.failed}件$extra", n.failed == 0)
    }

    fun paused(c: Context) {
        val n = counts(c)
        post(c, "一時停止中", n.text + "\n「保存状況」→「再開」で続きから再開します", true)
    }

    fun clearFinished(c: Context) = NotificationManagerCompat.from(c).cancel(FINISHED_ID)

    private fun post(c: Context, title: String, text: String, success: Boolean) {
        createChannels(c)
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(c, CH_RESULT)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(c))
            .build()
        NotificationManagerCompat.from(c).notify(FINISHED_ID, n)
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
