const $ = id => document.getElementById(id);

async function activeTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab;
}

async function refresh() {
  const s = await chrome.runtime.sendMessage({ type: 'status' });
  let text = `保存済み（記録）: ${s.doneTotal}件\n今回: ダウンロード中 ${s.downloading}件 ・ 完了 ${s.completed}件 ・ 失敗 ${s.failed.length}件`;
  if (s.run) text += `\n\n${s.run.running ? '⏬ ' : ''}${s.run.text}`;
  if (s.failed.length) text += `\n\n失敗（Chromeのダウンロード画面で「再開」できます）:\n- ` + s.failed.slice(0, 10).join('\n- ');
  $('status').textContent = text;
}

function showMessage(text) {
  $('message').textContent = text;
}

/** 開いているタブで拡張機能の処理を動かす（拡張機能を入れる前から開いていたタブでも動くように、必要ならその場で読み込む） */
async function sendToTab(tab, msg) {
  let res = await chrome.tabs.sendMessage(tab.id, msg).catch(() => null);
  if (!res) {
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ['content.js'] }).catch(() => null);
    res = await chrome.tabs.sendMessage(tab.id, msg).catch(() => null);
  }
  return res;
}

$('start').onclick = async () => {
  showMessage('');
  const tab = await activeTab();
  const maxParallel = Number($('parallel').value);
  chrome.storage.local.set({ parallel: maxParallel });
  if (!tab || !/^https:\/\/[^/]*dmm\.co\.jp\//.test(tab.url || '')) {
    showMessage('FANZA同人の「購入済み作品」ページを開いた状態で押してください。');
    return;
  }
  const res = await sendToTab(tab, { type: 'start', maxParallel });
  if (!res) {
    showMessage('このページでは動かせませんでした。ページを再読み込み（F5）してから、もう一度押してください。');
  } else if (!res.ok) {
    showMessage('このページに作品一覧が見つかりませんでした。FANZA同人の「購入済み作品」ページ（左に購入作品が並んでいる画面）で押してください。');
  } else {
    showMessage(`開始しました（作品 ${res.count || ''}件）。このまま待つと自動で進みます。`);
  }
  refresh();
};

$('stop').onclick = async () => {
  const tab = await activeTab();
  if (tab) await chrome.tabs.sendMessage(tab.id, { type: 'stop' }).catch(() => null);
  showMessage('停止しました。');
  refresh();
};

$('downloads').onclick = () => chrome.tabs.create({ url: 'chrome://downloads/' });

$('importFile').onchange = async e => {
  const file = e.target.files[0];
  if (!file) return;
  try {
    const data = JSON.parse(await file.text());
    const res = await chrome.runtime.sendMessage({ type: 'import', data });
    $('importResult').textContent = `読み込みました。保存済みとして記録: ${res.titles}件（スマホで保存済みの作品は飛ばします）`;
  } catch (err) {
    $('importResult').textContent = '読み込めませんでした: ' + err.message;
  }
  refresh();
};

$('clear').onclick = async () => {
  await chrome.runtime.sendMessage({ type: 'clearSession' });
  refresh();
};

$('reset').onclick = async () => {
  if (!confirm('保存済みの記録をすべて消します。次の一括保存では、全作品をもう一度ダウンロードします。よろしいですか？')) return;
  await chrome.runtime.sendMessage({ type: 'resetDone' });
  refresh();
};

chrome.storage.local.get('parallel').then(v => { if (v.parallel) $('parallel').value = String(v.parallel); });
refresh();
setInterval(refresh, 1000);
