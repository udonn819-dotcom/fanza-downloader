// FANZA同人 一括保存（PC） — 裏方
// ・作品ページで「ダウンロード」が押される直前に、作品タイトルを「予告（hint）」として受け取る
// ・Chromeがダウンロードを始めるとき、そのタイトルをファイル名にして「ダウンロード/FANZA同人/」へ保存する
// ・ダウンロードが終わったら「保存済み」に記録し、次回の一括保存で飛ばす

const HINT_MAX_AGE = 2 * 60 * 1000;

async function getLocal(key, fallback) {
  const v = (await chrome.storage.local.get(key))[key];
  return v === undefined ? fallback : v;
}
async function getHints() {
  return ((await chrome.storage.session.get('hints')).hints || []).filter(h => Date.now() - h.at < HINT_MAX_AGE);
}

/** Windowsのファイル名に使えない記号を全角に置き換え、長すぎる名前を切り詰める */
function sanitize(title) {
  const map = { '\\': '＼', '/': '／', ':': '：', '*': '＊', '?': '？', '"': '”', '<': '＜', '>': '＞', '|': '｜' };
  let s = (title || '').replace(/[\\/:*?"<>|]/g, c => map[c]).replace(/[\u0000-\u001f]/g, ' ');
  s = s.replace(/\s+/g, ' ').trim().replace(/[. ]+$/, '');
  if (s.length > 120) s = s.slice(0, 120).trim();
  return s || 'FANZA作品';
}

async function addDone(title, cid) {
  const done = await getLocal('done', { titles: [], cids: [] });
  if (title && !done.titles.includes(title)) done.titles.push(title);
  if (cid && !done.cids.includes(cid)) done.cids.push(cid);
  await chrome.storage.local.set({ done });
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    switch (msg.type) {
      case 'hint': {
        const hints = await getHints();
        hints.push({ title: msg.title || '', cid: msg.cid || '', at: Date.now() });
        await chrome.storage.session.set({ hints });
        sendResponse({ ok: true });
        break;
      }
      case 'activeCount': {
        const ids = await getLocal('ids', {});
        const list = await chrome.downloads.search({ state: 'in_progress' });
        sendResponse({ count: list.filter(d => ids[d.id] && !d.paused).length });
        break;
      }
      case 'status': {
        const ids = await getLocal('ids', {});
        const done = await getLocal('done', { titles: [], cids: [] });
        const run = await getLocal('run', null);
        const items = Object.values(ids);
        sendResponse({
          doneTotal: done.titles.length,
          downloading: items.filter(i => i.status === 'downloading').length,
          completed: items.filter(i => i.status === 'complete').length,
          failed: items.filter(i => i.status === 'failed').map(i => i.title),
          run,
        });
        break;
      }
      case 'import': {
        // スマホアプリの「PCへ引き継ぐ」で書き出したファイルを取り込む
        const done = await getLocal('done', { titles: [], cids: [] });
        const t = new Set(done.titles), c = new Set(done.cids);
        (msg.data.done_titles || []).forEach(x => x && t.add(x));
        (msg.data.done_cids || []).forEach(x => x && c.add(x));
        await chrome.storage.local.set({ done: { titles: [...t], cids: [...c] } });
        sendResponse({ ok: true, titles: t.size });
        break;
      }
      case 'clearSession': {
        await chrome.storage.local.set({ ids: {}, run: null });
        sendResponse({ ok: true });
        break;
      }
      case 'resetDone': {
        await chrome.storage.local.set({ done: { titles: [], cids: [] }, ids: {}, run: null });
        sendResponse({ ok: true });
        break;
      }
      default:
        sendResponse({ ok: false });
    }
  })();
  return true; // 非同期で返事をする
});

// ダウンロード開始時: 予告されたタイトルでファイル名を決める
chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
  (async () => {
    const fromDmm = /dmm\.(co\.jp|com)/.test([item.url, item.finalUrl, item.referrer].join(' '));
    const hints = await getHints();
    if (!fromDmm || hints.length === 0) {
      suggest();
      return;
    }
    const hint = hints.shift();
    await chrome.storage.session.set({ hints });
    const ext = (item.filename.match(/\.([A-Za-z0-9]{1,5})$/) || [])[1] || 'zip';
    const ids = await getLocal('ids', {});
    ids[item.id] = { title: hint.title, cid: hint.cid, status: 'downloading' };
    await chrome.storage.local.set({ ids });
    suggest({ filename: 'FANZA同人/' + sanitize(hint.title) + '.' + ext, conflictAction: 'uniquify' });
  })();
  return true;
});

// ダウンロードの完了・失敗を記録
chrome.downloads.onChanged.addListener(async delta => {
  if (!delta.state && !delta.error) return;
  const ids = await getLocal('ids', {});
  const info = ids[delta.id];
  if (!info) return;
  const state = delta.state && delta.state.current;
  if (state === 'complete') {
    info.status = 'complete';
    await addDone(info.title, info.cid);
  } else if (state === 'interrupted') {
    info.status = 'failed';
  } else if (state === 'in_progress') {
    info.status = 'downloading'; // 「再開」された
  }
  ids[delta.id] = info;
  await chrome.storage.local.set({ ids });
});
