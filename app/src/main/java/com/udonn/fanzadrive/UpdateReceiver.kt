package com.udonn.fanzadrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * アプリをアップデートした直後に呼ばれる。
 * アプリを開かなくても、保存待ちの作品の処理を自動で再開する。
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try {
                DownloadWorker.restoreQueue(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
