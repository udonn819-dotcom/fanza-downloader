// FANZA同人 一括保存（PC） — 購入済みページで動く部分
// 左の作品一覧を上から順に選び、右側に出る「ダウンロードする」を押していく。
// 押す直前に作品タイトルを裏方へ伝え、ファイル名に使ってもらう。
(() => {
  // 作品には必ず「コミック」「CG」「ボイス」などの分類ラベルが付いているので、それを目印にする
  const CAT = /^(コミック|CG|ボイス|ゲーム|動画|音声|ASMR|写真集?|小説|ノベル|その他|ツール|イラスト|マンガ|アニメ|単話)(・AI)?$/;
  const txt = el => ((el && (el.innerText || el.textContent)) || '').trim();
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  function isLibrary() {
    if (location.href.indexOf('mylibrary') >= 0) return true;
    return [...document.querySelectorAll('body *')].some(e => e.children.length === 0 && /^購入済み(作品|商品)/.test(txt(e)));
  }

  // 画面左側の作品一覧にある分類ラベル（一番多い横位置のものだけ＝右側のおすすめ欄を除外）
  function leftLabels() {
    const LEFT = window.innerWidth * 0.4;
    const all = [...document.querySelectorAll('body *')].filter(e => {
      if (e.children.length !== 0 || !CAT.test(txt(e))) return false;
      const r = e.getBoundingClientRect();
      return r.width > 0 && r.left < LEFT;
    });
    const count = {};
    let best = null;
    all.forEach(e => {
      const x = Math.round(e.getBoundingClientRect().left / 8);
      count[x] = (count[x] || 0) + 1;
      if (best === null || count[x] > count[best]) best = x;
    });
    return all.filter(e => Math.abs(Math.round(e.getBoundingClientRect().left / 8) - best) <= 1);
  }

  // ラベルから親をたどり、ラベルを1つだけ含む一番大きな枠＝1作品
  function findItems() {
    const labels = leftLabels(), out = [];
    labels.forEach(l => {
      let el = l;
      while (el.parentElement && el.parentElement !== document.body) {
        const p = el.parentElement;
        let n = 0;
        for (let k = 0; k < labels.length && n < 2; k++) if (p.contains(labels[k])) n++;
        if (n >= 2 || p.getBoundingClientRect().width > window.innerWidth * 0.5) break;
        el = p;
      }
      if (!out.includes(el)) out.push(el);
    });
    return out;
  }

  function itemTitle(item) {
    const lines = txt(item).split('\n').map(s => s.trim()).filter(s => s && !CAT.test(s) && !/^\d{4}年/.test(s));
    return lines.reduce((best, s) => (s.length > best.length ? s : best), '');
  }

  function cidOf(el) {
    const m = (el.outerHTML || '').match(/(?:^|[^A-Za-z0-9_])(d_[a-z0-9]*\d[a-z0-9]*)/);
    return m ? m[1] : '';
  }

  // 一覧より右側にある「ダウンロード〜」のボタン・リンク
  function findDownloads(listRight) {
    const res = [];
    document.querySelectorAll('a, button, [role="button"], input[type="button"], input[type="submit"], div, span, p').forEach(e => {
      const r = e.getBoundingClientRect();
      if (r.width === 0 || r.height === 0 || r.left < listRight) return;
      const t = (txt(e) || e.value || e.title || '').replace(/\s+/g, '');
      const h = e.getAttribute('href') || '';
      if (!(/^ダウンロード/.test(t) || (e.tagName === 'A' && /download/i.test(h)))) return;
      if (t.length > 40 || /(アプリ|ビューア|ビューワ|ソフト|方法|ヘルプ|について)/.test(t)) return;
      const c = e.closest('a, button, [role="button"]') || e;
      if (!res.includes(c)) res.push(c);
    });
    return res.filter(e => !res.some(o => o !== e && e.contains(o)));
  }
  const key = list => list.map(e => e.outerHTML).join('|');

  let running = false, stopReq = false;

  async function setRun(run) {
    await chrome.storage.local.set({ run });
  }

  async function run(maxParallel) {
    running = true;
    stopReq = false;
    let checked = 0, skipped = 0, started = 0;
    try {
      const done = (await chrome.storage.local.get('done')).done || { titles: [], cids: [] };
      const doneT = new Set(done.titles), doneC = new Set(done.cids);
      await setRun({ running: true, text: '作品一覧を読み込み中…' });

      // 下までスクロールして全作品を読み込ませる
      let items = findItems(), prev = -1, tries = 0;
      while (items.length !== prev && tries < 40 && !stopReq) {
        prev = items.length;
        tries++;
        if (items.length) items[items.length - 1].scrollIntoView();
        window.scrollTo(0, document.body.scrollHeight);
        await sleep(1500);
        items = findItems();
      }
      let listRight = 0;
      items.forEach(it => (listRight = Math.max(listRight, it.getBoundingClientRect().right)));

      for (let i = 0; i < items.length && !stopReq; i++) {
        const item = items[i], title = itemTitle(item), cid = cidOf(item);
        checked++;
        if ((title && (doneT.has(title) || doneT.has(title + ' (1)'))) || (cid && doneC.has(cid))) {
          skipped++;
          continue;
        }
        // 同時ダウンロード数が上限なら空くまで待つ
        while (!stopReq) {
          const { count } = await chrome.runtime.sendMessage({ type: 'activeCount' });
          if (count < maxParallel) break;
          await setRun({ running: true, text: `ダウンロードの空き待ち（${i + 1}/${items.length}）` });
          await sleep(2000);
        }
        if (stopReq) break;
        await setRun({ running: true, text: `${i + 1}/${items.length} を確認中: ${title}` });

        const before = key(findDownloads(listRight));
        item.scrollIntoView({ block: 'center' });
        const target = item.querySelector('a[href]:not([href^="javascript"])') || item.querySelector('a,[role="button"]') || item;
        target.click();
        // 右側の表示が切り替わるのを最大8秒待つ
        let dls = [];
        for (let w = 0; w < 16; w++) {
          await sleep(500);
          dls = findDownloads(listRight);
          if (dls.length && key(dls) !== before) break;
        }
        for (let d = 0; d < dls.length; d++) {
          const t = dls.length > 1 ? `${title} (${d + 1})` : title;
          await chrome.runtime.sendMessage({ type: 'hint', title: t, cid });
          dls[d].click();
          started++;
          await sleep(2500);
        }
      }
    } catch (err) {
      console.log('FANZA一括保存 エラー', err);
    }
    running = false;
    await setRun({
      running: false,
      text: `${stopReq ? '停止しました' : '確認完了'}: ${checked}件を確認、${started}件のダウンロードを開始（保存済み${skipped}件はスキップ）`,
    });
  }

  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (msg.type === 'start') {
      const ok = isLibrary() && findItems().length > 0;
      if (ok && !running) run(msg.maxParallel || 3);
      sendResponse({ ok, running: true });
    } else if (msg.type === 'stop') {
      stopReq = true;
      sendResponse({ ok: true });
    }
    return false;
  });

  // 手動で「ダウンロードする」を押したときも、直前に選んだ作品のタイトルでファイル名を付ける
  let lastTitle = '', lastCid = '';
  document.addEventListener('click', e => {
    if (running) return;
    const item = findItems().find(it => it.contains(e.target));
    if (item) {
      lastTitle = itemTitle(item);
      lastCid = cidOf(item);
      return;
    }
    const btn = e.target.closest('a, button, [role="button"], div');
    if (btn && /^ダウンロード/.test((txt(btn) || '').replace(/\s+/g, '')) && lastTitle) {
      chrome.runtime.sendMessage({ type: 'hint', title: lastTitle, cid: lastCid });
    }
  }, true);
})();
