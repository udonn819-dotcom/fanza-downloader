package com.udonn.fanzadrive

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Googleアカウントでログインし、Googleドライブを操作するための「通行証（アクセストークン）」を得る。
 * 権限は drive.file（このアプリが作ったファイル・フォルダだけ扱える）に限定している。
 */
object GoogleAuth {
    private val SCOPES = listOf(Scope("https://www.googleapis.com/auth/drive.file"))

    fun request(account: String?): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES)
            .apply { if (!account.isNullOrBlank()) setAccount(Account(account, "com.google")) }
            .build()

    /** バックグラウンドからトークンを取得（一度ログイン済みなら画面は出ない） */
    fun accessToken(c: Context): String {
        val result = try {
            Tasks.await(Identity.getAuthorizationClient(c).authorize(request(Prefs.googleAccount(c))), 60, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw translate(e.cause ?: e)
        } catch (e: Exception) {
            throw IOException("Googleログイン確認に失敗: ${e.message}")
        }
        if (result.hasResolution()) {
            throw PermanentException("Googleの再ログインが必要です。アプリの「設定」→「Googleでログイン」を押してください")
        }
        return result.accessToken ?: throw IOException("アクセストークンを取得できませんでした")
    }

    fun translate(e: Throwable): IOException {
        if (e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
            return PermanentException("Google Cloudの設定が見つかりません。READMEの手順（パッケージ名・SHA-1の登録、Drive APIの有効化）を確認してください")
        }
        return IOException("Googleログインに失敗: ${e.message}")
    }

    /** ログイン直後に、どのアカウントでログインしたかを調べる */
    fun fetchEmail(result: AuthorizationResult): String {
        val token = result.accessToken ?: throw IOException("アクセストークンがありません")
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)")
            .header("Authorization", "Bearer $token")
            .build()
        Http.plainClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(
                    if (body.contains("accessNotConfigured") || body.contains("SERVICE_DISABLED"))
                        "Google Cloudで「Google Drive API」が有効になっていません"
                    else "HTTP ${resp.code}"
                )
            }
            return JSONObject(body).getJSONObject("user").getString("emailAddress")
        }
    }
}
