package com.juju.tv.remote

/**
 * 内嵌手机扫码遥控单页应用 (`/`) 与 WebDAV/自救源配网应用 (`/webdav?t=<TOKEN>`)
 *
 * 完整保留并增强原版三大局域网实战工程机制：
 * 1. `_pauseAware()`：监听 `visibilitychange`，手机锁屏或切后台时立即暂停一切轮询，防止电视端 `CLOSE_WAIT` 耗尽；
 * 2. 首页轨道严格串行拉取 (`for (const r of rails) await loadRail(r)`) + 搜索 350ms 防抖；
 * 3. 明文 HTTP (`http://192.168.x.x`) 下的 `document.execCommand('copy')` 剪贴板降级与防刷新风暴保护。
 */
object WebRemoteAssets {

    fun renderManifestJson(): String = """
        {
          "name": "聚聚 · 电视遥控",
          "short_name": "聚聚遥控",
          "start_url": "/",
          "display": "standalone",
          "background_color": "#0B0E14",
          "theme_color": "#FF6B35"
        }
    """.trimIndent()

    fun renderRemoteControlHtml(deviceName: String = "坚果 N1 Air 第四代 · 聚聚 TV"): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<title>聚聚 · 手机遥控 ($deviceName)</title>
<link rel="manifest" href="/manifest.json">
<style>
:root {
  --bg: #0b0e14;
  --card: rgba(24, 31, 46, 0.86);
  --border: rgba(148, 163, 184, 0.16);
  --accent: #ff6b35;
  --accent2: #38bdf8;
  --text: #f8fafc;
  --muted: #94a3b8;
}
* { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
body {
  background: radial-gradient(circle at 20% 0%, #1e293b 0%, #0b0e14 65%);
  color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  min-height: 100vh;
  padding-bottom: 148px;
}
header {
  position: sticky; top: 0; z-index: 30;
  backdrop-filter: blur(14px);
  background: rgba(11, 14, 20, 0.85);
  border-bottom: 1px solid var(--border);
  padding: 12px 16px;
}
.top-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.brand { display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 17px; }
.brand-badge {
  background: linear-gradient(135deg, var(--accent), #f97316);
  color: #fff; font-size: 11px; padding: 2px 7px; border-radius: 99px;
}
.search-bar {
  display: flex; gap: 8px;
}
.search-bar input {
  flex: 1; background: rgba(15, 23, 42, 0.9); border: 1px solid var(--border);
  color: var(--text); border-radius: 10px; padding: 9px 12px; font-size: 14px; outline: none;
}
.btn {
  background: var(--accent); color: #fff; border: none; border-radius: 10px;
  padding: 8px 13px; font-size: 13px; font-weight: 600; cursor: pointer;
}
.btn-ghost {
  background: rgba(56, 189, 248, 0.14); color: var(--accent2);
  border: 1px solid rgba(56, 189, 248, 0.35);
}
main { padding: 14px 16px; }
.rail { margin-bottom: 20px; }
.rail-title { font-size: 16px; font-weight: 700; margin-bottom: 10px; display: flex; justify-content: space-between; align-items: center; }
.rail-sub { font-size: 12px; color: var(--muted); font-weight: 400; }
.grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px;
}
.card {
  background: var(--card); border: 1px solid var(--border); border-radius: 12px;
  overflow: hidden; cursor: pointer; transition: transform 0.15s;
}
.card:active { transform: scale(0.96); }
.card-cover {
  width: 100%; aspect-ratio: 5/7; background: #1e293b; object-fit: cover; display: block;
}
.card-body { padding: 7px 8px; }
.card-title { font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.card-meta { font-size: 11px; color: var(--muted); margin-top: 3px; display: flex; justify-content: space-between; }
.pill-row { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.pill {
  padding: 5px 11px; border-radius: 99px; font-size: 12px; background: var(--card);
  border: 1px solid var(--border); color: var(--muted); cursor: pointer;
}
.pill.active { background: var(--accent); color: #fff; border-color: var(--accent); }
.panel-box {
  background: var(--card); border: 1px solid var(--border); border-radius: 14px;
  padding: 14px; margin-bottom: 14px;
}
.now-bar {
  position: fixed; bottom: 58px; left: 10px; right: 10px; z-index: 40;
  background: rgba(15, 23, 42, 0.95); backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 107, 53, 0.4); border-radius: 14px;
  padding: 10px 12px; display: flex; flex-direction: column; gap: 8px;
}
.now-top { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.now-controls { display: flex; gap: 6px; justify-content: space-between; }
.now-controls button {
  flex: 1; background: rgba(255,255,255,0.08); color: #fff; border: 1px solid var(--border);
  border-radius: 8px; padding: 7px 0; font-size: 12px; font-weight: 600;
}
nav.bottom-nav {
  position: fixed; bottom: 0; left: 0; right: 0; height: 54px; z-index: 45;
  background: rgba(11, 14, 20, 0.96); border-top: 1px solid var(--border);
  display: grid; grid-template-columns: repeat(5, 1fr);
}
nav.bottom-nav button {
  background: none; border: none; color: var(--muted); font-size: 12px; font-weight: 600;
}
nav.bottom-nav button.active { color: var(--accent); }
.hidden { display: none !important; }
textarea.input-box, input.input-box {
  width: 100%; background: #0f172a; border: 1px solid var(--border); color: #fff;
  border-radius: 8px; padding: 8px 10px; margin-top: 6px; margin-bottom: 10px; font-size: 13px;
}
</style>
</head>
<body>
<header>
  <div class="top-row">
    <div class="brand">
      <span>聚聚 · 遥控</span>
      <span class="brand-badge" id="mem-badge">2GB 极速模式</span>
    </div>
    <div style="display:flex;gap:6px;">
      <button class="btn btn-ghost" onclick="openWebDavConfig()">WebDAV同步</button>
      <button class="btn btn-ghost" onclick="switchTab('sources')">自救源/诊断</button>
    </div>
  </div>
  <div class="search-bar">
    <input id="q-input" type="search" placeholder="输入剧名、主角、题材实时搜剧..." oninput="onSearchInput(this.value)">
    <button class="btn" onclick="triggerSearchNow()">搜剧</button>
  </div>
</header>

<main>
  <!-- 视图 1: 首页轨道 -->
  <section id="v-home"></section>

  <!-- 视图 2: 手机专属多维找剧筛选 (#v-browse) -->
  <section id="v-browse" class="hidden">
    <div class="panel-box">
      <div style="font-size:13px;font-weight:700;margin-bottom:6px;">剧种筛选</div>
      <div class="pill-row" id="filter-types"></div>
      <div style="font-size:13px;font-weight:700;margin-bottom:6px;">题材标签</div>
      <div class="pill-row" id="filter-genres"></div>
    </div>
    <div class="grid" id="browse-grid"></div>
  </section>

  <!-- 视图 3: 榜单与新剧日历 (#v-rank) -->
  <section id="v-rank" class="hidden">
    <div class="pill-row" id="rank-tabs"></div>
    <div class="grid" id="rank-grid"></div>
  </section>

  <!-- 视图 4: 追剧收藏与观看历史 (#v-follow) -->
  <section id="v-follow" class="hidden">
    <div class="rail-title">我的追剧收藏</div>
    <div class="grid" id="follow-grid" style="margin-bottom:18px;"></div>
    <div class="rail-title">本机观看历史</div>
    <div class="grid" id="history-grid"></div>
  </section>

  <!-- 视图 5: 去中心化自救源 & 2GB 内存诊断 (#v-sources) -->
  <section id="v-sources" class="hidden">
    <div class="panel-box">
      <div class="rail-title">🛠️ 去中心化自救：添加自定义 JSON / CMS / M3U 站源</div>
      <p style="font-size:12px;color:var(--muted);margin-bottom:8px;">
        当第三方站源域名变更或停服时，可在此直接填入自定义 JSON、苹果 MacCMS V10 接口或 M3U8 列表地址，电视立即热挂载生效！
      </p>
      <input id="src-name" class="input-box" placeholder="站源名称（如：私人NAS剧库 / MacCMS 源）">
      <input id="src-url" class="input-box" placeholder="接口地址 https://.../api.php/provide/vod/ 或 .m3u8">
      <textarea id="src-raw" class="input-box" rows="3" placeholder="可选：直接粘贴 JSON / M3U 列表全文（留空则从上方 URL 拉取）"></textarea>
      <button class="btn" onclick="addCustomSource()">保存到电视并立即挂载</button>
      <div id="custom-src-list" style="margin-top:12px;font-size:12px;"></div>
    </div>

    <div class="panel-box">
      <div class="rail-title">🌐 内置站源域名热替换（防域名失联）</div>
      <input id="override-source-id" class="input-box" value="hongguo" placeholder="站源ID: hongguo / huangju / huangdou">
      <input id="override-domain-url" class="input-box" placeholder="新 API 域名（留空恢复默认）">
      <button class="btn btn-ghost" onclick="saveDomainOverride()">更新内置源域名</button>
    </div>

    <div class="panel-box">
      <div class="rail-title">📊 坚果 N1 Air (2GB RAM) 实时诊断与解码设置</div>
      <pre id="diag-box" style="font-size:11px;color:#38bdf8;white-space:pre-wrap;line-height:1.45;margin-bottom:10px;">正在读取电视诊断信息...</pre>
      <div style="display:flex;gap:8px;flex-wrap:wrap;">
        <button class="btn btn-ghost" onclick="copyDiagnostics()">一键复制诊断信息</button>
        <button class="btn btn-ghost" onclick="switchDecoder('auto')">切至：零拷贝直出</button>
        <button class="btn btn-ghost" onclick="switchDecoder('compatible')">切至：兼容模式</button>
        <button class="btn btn-ghost" onclick="resetHongguoDevice()">刷新片库并重置熔断</button>
      </div>
    </div>
  </section>
</main>

<!-- 底部实时播控条 -->
<div class="now-bar">
  <div class="now-top">
    <span id="now-title">📺 电视空闲中 · 点击任意剧集立即投屏点播</span>
    <span id="now-progress" style="color:var(--accent2);font-size:12px;">00:00 / 00:00</span>
  </div>
  <div class="now-controls">
    <button onclick="sendCtrl('prev')">上一集</button>
    <button onclick="sendCtrl('toggle')">播放/暂停</button>
    <button onclick="sendCtrl('next')">下一集</button>
    <button onclick="sendCtrl('seek_back')">-10s</button>
    <button onclick="sendCtrl('seek_fwd')">+10s</button>
    <button onclick="sendCtrl('exit')">退出播放</button>
  </div>
</div>

<nav class="bottom-nav">
  <button id="tab-home" class="active" onclick="switchTab('home')">首页</button>
  <button id="tab-browse" onclick="switchTab('browse')">找剧</button>
  <button id="tab-rank" onclick="switchTab('rank')">榜单</button>
  <button id="tab-follow" onclick="switchTab('follow')">追剧/历史</button>
  <button id="tab-sources" onclick="switchTab('sources')">自救/设置</button>
</nav>

<script>
let isPagePaused = false;
let helloTimer = null;
let stateTimer = null;
let searchDebounceTimer = null;
let currentBrowseType = '全部';
let currentBrowseGenre = '全部';

// 1. 防手机锁屏导致电视 CLOSE_WAIT 耗尽 (_pauseAware)
function _pauseAware() {
  document.addEventListener('visibilitychange', function() {
    if (document.hidden) {
      isPagePaused = true;
      stopPolling();
    } else {
      isPagePaused = false;
      fetch('/api/hello').then(r => {
        if (!r.ok) reloadSoon('回到前台后连不上电视');
        else startPolling();
      }).catch(() => reloadSoon('回到前台后连不上电视'));
    }
  });
}

function reloadSoon(reason) {
  const now = Date.now();
  const raw = sessionStorage.getItem('juju_reload_ts') || '0';
  if (now - parseInt(raw, 10) > 15000) {
    sessionStorage.setItem('juju_reload_ts', String(now));
    location.reload();
  }
}

function startPolling() {
  stopPolling();
  pollHello();
  pollState();
  helloTimer = setInterval(pollHello, 4000);
  stateTimer = setInterval(pollState, 1500);
}

function stopPolling() {
  if (helloTimer) { clearInterval(helloTimer); helloTimer = null; }
  if (stateTimer) { clearInterval(stateTimer); stateTimer = null; }
}

function pollHello() {
  if (isPagePaused) return;
  fetch('/api/hello').then(r => r.json()).then(d => {
    if (d && d.watermarkLabel) {
      document.getElementById('mem-badge').textContent = '内存: ' + d.availRamMb + 'MB · ' + d.watermark;
    }
  }).catch(() => {});
}

function pollState() {
  if (isPagePaused) return;
  fetch('/api/state').then(r => r.json()).then(s => {
    const titleEl = document.getElementById('now-title');
    const progEl = document.getElementById('now-progress');
    if (s && s.dramaTitle) {
      const statusTag = s.isPaused ? '[已暂停]' : '[正在播放]';
      titleEl.textContent = statusTag + ' ' + s.dramaTitle + ' · 第' + s.episodeIndex + '集';
      progEl.textContent = fmtTime(s.positionMs) + ' / ' + fmtTime(s.durationMs);
    } else {
      titleEl.textContent = '📺 电视待机中 · 点击任意剧集立即在电视播放';
      progEl.textContent = '--:-- / --:--';
    }
  }).catch(() => {});
}

function fmtTime(ms) {
  const sec = Math.max(0, Math.floor((ms || 0) / 1000));
  const m = String(Math.floor(sec / 60)).padStart(2, '0');
  const s = String(sec % 60).padStart(2, '0');
  return m + ':' + s;
}

// 2. 首页轨道严格串行拉取（防止并发请求打满 2GB 电视 CPU）
async function loadHomeSerially() {
  const container = document.getElementById('v-home');
  container.innerHTML = '<div style="color:#94a3b8;font-size:13px;">正在串行加载推荐轨道...</div>';
  try {
    const homeResp = await fetch('/api/home').then(r => r.json());
    const railKeys = (homeResp && homeResp.railKeys) || ['hot', 'category:漫剧', 'category:真人剧', 'category:AI 剧', 'rest'];
    container.innerHTML = '';
    for (let i = 0; i < railKeys.length; i++) {
      if (isPagePaused) break;
      const railData = await fetch('/api/rail?key=' + encodeURIComponent(railKeys[i])).then(r => r.json());
      if (railData && railData.items && railData.items.length > 0) {
        const sec = document.createElement('div');
        sec.className = 'rail';
        sec.innerHTML = '<div class="rail-title"><span>' + esc(railData.title) + '</span><span class="rail-sub">' + esc(railData.subtitle || '') + '</span></div>' +
          '<div class="grid">' + railData.items.map(renderCardHtml).join('') + '</div>';
        container.appendChild(sec);
      }
    }
  } catch (e) {
    container.innerHTML = '<div style="color:#ef4444;">加载失败，请检查网络连接</div>';
  }
}

function renderCardHtml(item) {
  return '<div class="card" onclick="playOnTv(\'' + esc(item.id) + '\', 1)">' +
    '<img class="card-cover" loading="lazy" src="/api/cover?url=' + encodeURIComponent(item.coverUrl || '') + '" alt="">' +
    '<div class="card-body">' +
      '<div class="card-title">' + esc(item.title) + '</div>' +
      '<div class="card-meta"><span>' + esc(item.category || '短剧') + '</span><span>' + esc(item.latestEpisodeText || '') + '</span></div>' +
    '</div>' +
  '</div>';
}

function playOnTv(dramaId, epIndex) {
  fetch('/api/play', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({ id: dramaId, index: epIndex || 1 })
  }).then(r => r.json()).then(() => {
    pollState();
  });
}

function sendCtrl(action) {
  fetch('/api/control', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({ action: action })
  }).then(() => pollState());
}

// 3. 350ms 防抖搜索
function onSearchInput(val) {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer);
  searchDebounceTimer = setTimeout(() => {
    if (val.trim().length > 0) triggerSearchNow();
    else loadHomeSerially();
  }, 350);
}

function triggerSearchNow() {
  const q = document.getElementById('q-input').value.trim();
  if (!q) { loadHomeSerially(); return; }
  switchTab('home');
  const container = document.getElementById('v-home');
  fetch('/api/search?q=' + encodeURIComponent(q)).then(r => r.json()).then(d => {
    const list = (d && d.items) || [];
    container.innerHTML = '<div class="rail"><div class="rail-title"><span>🔍 搜索结果 (' + list.length + ')</span></div>' +
      '<div class="grid">' + list.map(renderCardHtml).join('') + '</div></div>';
  });
}

function switchTab(tab) {
  ['home', 'browse', 'rank', 'follow', 'sources'].forEach(t => {
    document.getElementById('v-' + t).classList.toggle('hidden', t !== tab);
    document.getElementById('tab-' + t).classList.toggle('active', t === tab);
  });
  if (tab === 'browse') initBrowseView();
  if (tab === 'rank') initRankView('热播榜');
  if (tab === 'follow') initFollowView();
  if (tab === 'sources') initSourcesAndDiag();
}

function initBrowseView() {
  fetch('/api/browse-panel').then(r => r.json()).then(p => {
    document.getElementById('filter-types').innerHTML = (p.types || []).map(t =>
      '<span class="pill ' + (t === currentBrowseType ? 'active' : '') + '" onclick="setBrowseFilter(\'' + t + '\', null)">' + t + '</span>'
    ).join('');
    document.getElementById('filter-genres').innerHTML = (p.genres || []).map(g =>
      '<span class="pill ' + (g === currentBrowseGenre ? 'active' : '') + '" onclick="setBrowseFilter(null, \'' + g + '\')">' + g + '</span>'
    ).join('');
    loadBrowseResults();
  });
}

function setBrowseFilter(type, genre) {
  if (type !== null) currentBrowseType = type;
  if (genre !== null) currentBrowseGenre = genre;
  initBrowseView();
}

function loadBrowseResults() {
  fetch('/api/browse?type=' + encodeURIComponent(currentBrowseType) + '&genre=' + encodeURIComponent(currentBrowseGenre))
    .then(r => r.json()).then(d => {
      document.getElementById('browse-grid').innerHTML = ((d && d.items) || []).map(renderCardHtml).join('');
    });
}

function initRankView(sub) {
  fetch('/api/rank-subs').then(r => r.json()).then(d => {
    const subs = (d && d.subs) || ['热播榜', '新剧榜', '飙升榜', '必看榜'];
    document.getElementById('rank-tabs').innerHTML = subs.map(s =>
      '<span class="pill ' + (s === sub ? 'active' : '') + '" onclick="initRankView(\'' + s + '\')">' + s + '</span>'
    ).join('');
  });
  fetch('/api/rank?sub=' + encodeURIComponent(sub)).then(r => r.json()).then(d => {
    document.getElementById('rank-grid').innerHTML = ((d && d.items) || []).map(renderCardHtml).join('');
  });
}

function initFollowView() {
  fetch('/api/follow').then(r => r.json()).then(d => {
    document.getElementById('follow-grid').innerHTML = ((d && d.items) || []).map(f =>
      renderCardHtml({id: f.dramaId, title: f.title, coverUrl: f.coverUrl, category: '追剧', latestEpisodeText: '看到第' + f.lastEpisodeIndex + '集'})
    ).join('') || '<div style="color:#94a3b8;font-size:12px;">暂无追剧记录</div>';
  });
  fetch('/api/history').then(r => r.json()).then(d => {
    document.getElementById('history-grid').innerHTML = ((d && d.items) || []).map(h =>
      renderCardHtml({id: h.dramaId, title: h.title, coverUrl: h.coverUrl, category: '第' + h.episodeIndex + '集', latestEpisodeText: h.progressPercent + '%'})
    ).join('') || '<div style="color:#94a3b8;font-size:12px;">暂无观看历史</div>';
  });
}

function initSourcesAndDiag() {
  fetch('/api/diagnostics').then(r => r.json()).then(d => {
    document.getElementById('diag-box').textContent = JSON.stringify(d, null, 2);
  });
  fetch('/api/sources').then(r => r.json()).then(d => {
    const list = (d && d.customSources) || [];
    document.getElementById('custom-src-list').innerHTML = list.map(s =>
      '<div style="padding:6px 0;border-bottom:1px solid rgba(255,255,255,0.08);display:flex;justify-content:space-between;align-items:center;">' +
      '<span>✅ ' + esc(s.name) + ' (' + esc(s.url) + ')</span>' +
      '<button class="btn btn-ghost" style="padding:3px 8px;font-size:11px;" onclick="deleteCustomSource(\'' + esc(s.id) + '\')">删除</button></div>'
    ).join('');
  });
}

function deleteCustomSource(id) {
  fetch('/api/sources?id=' + encodeURIComponent(id), { method: 'DELETE' }).then(() => {
    initSourcesAndDiag();
  });
}

function saveDomainOverride() {
  const srcId = document.getElementById('override-source-id').value.trim();
  const domainUrl = document.getElementById('override-domain-url').value.trim();
  if (!srcId) return;
  const map = {};
  map[srcId] = domainUrl;
  fetch('/api/settings', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({ sourceDomainOverrides: map })
  }).then(r => r.json()).then(() => {
    alert('已更新站源域名配置');
    initSourcesAndDiag();
  });
}

function addCustomSource() {
  const name = document.getElementById('src-name').value.trim();
  const url = document.getElementById('src-url').value.trim();
  const rawEl = document.getElementById('src-raw');
  const raw = rawEl ? rawEl.value.trim() : '';
  if (!name || (!url && !raw)) { alert('请填写站源名称与 URL（或直接粘贴 JSON/M3U 内容）'); return; }
  const useM3u = raw.startsWith('#EXTM3U') || url.endsWith('.m3u8') || url.endsWith('.m3u');
  fetch('/api/sources', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({ name: name, url: url || ('local://' + Date.now()), type: useM3u ? 'm3u' : 'json', rawContent: raw })
  }).then(r => r.json()).then(d => {
    alert(d.message || '已挂载到电视！');
    if (rawEl) rawEl.value = '';
    initSourcesAndDiag();
  });
}

function switchDecoder(mode) {
  fetch('/api/settings', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({ decoderMode: mode })
  }).then(r => r.json()).then(() => {
    alert('已切换电视解码档位为：' + mode);
    initSourcesAndDiag();
  });
}

function resetHongguoDevice() {
  fetch('/api/hongguo/reset-device', { method: 'POST' }).then(r => r.json()).then(d => {
    alert('已重置虚拟设备身份：' + (d.deviceId || 'OK'));
  });
}

// 4. 明文 HTTP 剪贴板降级 (document.execCommand('copy'))
function copyDiagnostics() {
  const text = document.getElementById('diag-box').textContent;
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(() => alert('诊断信息已复制'));
    return;
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.left = '-9999px';
  document.body.appendChild(ta);
  ta.focus();
  ta.select();
  try {
    document.execCommand('copy');
    alert('诊断信息已复制（HTTP 兼容模式）');
  } catch (e) {
    alert('复制失败，请长按文本框手动复制');
  }
  document.body.removeChild(ta);
}

function openWebDavConfig() {
  fetch('/api/webdav-session', { method: 'POST' }).then(r => r.json()).then(d => {
    if (d && d.token) {
      location.href = '/webdav?t=' + encodeURIComponent(d.token);
    }
  });
}

function esc(s) {
  return String(s || '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

_pauseAware();
startPolling();
loadHomeSerially();
</script>
</body>
</html>
    """.trimIndent()

    /**
     * `/webdav?t=<TOKEN>` 扫码配置同步单页应用
     * 支持配置坚果云、群晖、OpenList，点击保存后当场验证连接，成功后 1200ms 跳回 `/`
     */
    fun renderWebDavConfigHtml(token: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>聚聚 · 配置同步 (WebDAV)</title>
<style>
body {
  background: #0b0e14; color: #f8fafc;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  padding: 20px 16px; max-width: 480px; margin: 0 auto;
}
.card {
  background: #151a24; border: 1px solid rgba(148,163,184,0.2);
  border-radius: 16px; padding: 18px;
}
h1 { font-size: 19px; margin-bottom: 6px; }
p.sub { font-size: 12px; color: #94a3b8; margin-bottom: 16px; line-height: 1.5; }
label { display: block; font-size: 13px; font-weight: 600; margin-top: 12px; margin-bottom: 5px; }
input {
  width: 100%; box-sizing: border-box; background: #0b0e14; color: #fff;
  border: 1px solid rgba(148,163,184,0.28); border-radius: 10px; padding: 10px 12px; font-size: 14px;
}
button {
  width: 100%; margin-top: 18px; background: #ff6b35; color: #fff;
  border: none; border-radius: 10px; padding: 12px; font-size: 15px; font-weight: 700; cursor: pointer;
}
.status { margin-top: 12px; font-size: 13px; line-height: 1.4; }
a { color: #38bdf8; text-decoration: none; }
</style>
</head>
<body>
<div class="card">
  <h1>☁️ 聚聚 · WebDAV 播放记录同步</h1>
  <p class="sub">
    支持坚果云、群晖 NAS、OpenList / Alist。多台设备填入同一账号与同步空间即可自动合并追剧与观看进度。<br>
    <a href="https://jackherex.github.io/miniflix/webdav.html" target="_blank">📖 查看坚果云 / 群晖 / OpenList 图文配置教程</a>
  </p>

  <label>服务器地址</label>
  <input id="serverUrl" value="https://dav.jianguoyun.com/dav/" placeholder="https://dav.jianguoyun.com/dav/">

  <label>账号</label>
  <input id="username" placeholder="坚果云邮箱或 NAS 用户名">

  <label>密码 / 应用密码（已设密码时留空表示不修改）</label>
  <input id="password" type="password" placeholder="坚果云请填第三方应用密码">

  <label>同步空间（1~24 位字母/数字/下划线/减号）</label>
  <input id="space" value="default" placeholder="default">

  <button onclick="saveWebDav()">保存到电视</button>
  <div id="status-msg" class="status"></div>
</div>

<script>
const TOKEN = "${token.replace("\"", "")}";

fetch('/api/webdav?t=' + encodeURIComponent(TOKEN)).then(r => r.json()).then(d => {
  if (d && d.serverUrl) document.getElementById('serverUrl').value = d.serverUrl;
  if (d && d.username) document.getElementById('username').value = d.username;
  if (d && d.space) document.getElementById('space').value = d.space;
}).catch(() => {});

function saveWebDav() {
  const msgEl = document.getElementById('status-msg');
  msgEl.style.color = '#38bdf8';
  msgEl.textContent = '⏳ 正在保存到电视并验证连接...';

  fetch('/api/webdav?t=' + encodeURIComponent(TOKEN), {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({
      serverUrl: document.getElementById('serverUrl').value.trim(),
      username: document.getElementById('username').value.trim(),
      password: document.getElementById('password').value,
      space: document.getElementById('space').value.trim() || 'default'
    })
  }).then(r => r.json()).then(res => {
    if (res && res.ok) {
      msgEl.style.color = '#22c55e';
      msgEl.textContent = '✅ 连接成功，正在回到控制页…';
      setTimeout(() => { location.href = '/'; }, 1200);
    } else {
      msgEl.style.color = '#ef4444';
      msgEl.textContent = '❌ ' + ((res && res.message) || '验证失败，请检查地址或应用密码');
    }
  }).catch(e => {
    msgEl.style.color = '#ef4444';
    msgEl.textContent = '❌ 网络错误：' + e;
  });
}
</script>
</body>
</html>
    """.trimIndent()
}
