/**
 * FANZA Drive保存アプリ用 Googleドライブ中継スクリプト
 *
 * 使い方は README.md の「Googleドライブ連携の設定」を参照してください。
 * ↓ 合言葉を自分だけが知っている文字列に変更してください（アプリの設定にも同じものを入力）
 */
const SECRET = 'ここを好きな合言葉に変更';

/** 保存先フォルダ名（マイドライブ直下に自動作成されます） */
const FOLDER_NAME = 'FANZA同人';

function doPost(e) {
  try {
    const req = JSON.parse(e.postData.contents);
    if (!SECRET || SECRET === 'ここを好きな合言葉に変更') return reply({ ok: false, error: 'GASの SECRET を変更してください' });
    if (req.key !== SECRET) return reply({ ok: false, error: '合言葉が一致しません' });

    const folder = getFolder();
    switch (req.action) {
      case 'ping':
        return reply({ ok: true, folder: folder.getName() });

      case 'exists': {
        const files = folder.getFilesByName(req.name);
        if (files.hasNext()) return reply({ ok: true, exists: true, size: files.next().getSize() });
        return reply({ ok: true, exists: false });
      }

      case 'start': {
        // ドライブの「再開可能アップロード」用URLを発行してアプリに返す
        const res = UrlFetchApp.fetch(
          'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true',
          {
            method: 'post',
            contentType: 'application/json; charset=UTF-8',
            payload: JSON.stringify({ name: req.name, parents: [folder.getId()], mimeType: req.mimeType }),
            headers: {
              Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
              'X-Upload-Content-Type': req.mimeType,
              'X-Upload-Content-Length': String(req.size),
            },
            muteHttpExceptions: true,
          }
        );
        const headers = res.getHeaders();
        const location = headers['Location'] || headers['location'];
        if (res.getResponseCode() !== 200 || !location) {
          return reply({ ok: false, error: 'Drive API ' + res.getResponseCode() + ': ' + res.getContentText().slice(0, 300) });
        }
        return reply({ ok: true, uploadUrl: location });
      }
    }
    return reply({ ok: false, error: '不明な操作: ' + req.action });
  } catch (err) {
    return reply({ ok: false, error: String(err) });
  }
}

function getFolder() {
  const it = DriveApp.getFoldersByName(FOLDER_NAME);
  return it.hasNext() ? it.next() : DriveApp.createFolder(FOLDER_NAME);
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

/** 初回の権限承認用。エディタでこの関数を選んで「実行」してください */
function authorize() {
  getFolder();
  UrlFetchApp.fetch('https://www.googleapis.com/drive/v3/about?fields=user', {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
  });
  Logger.log('準備OK: フォルダ「' + FOLDER_NAME + '」を使います');
}
