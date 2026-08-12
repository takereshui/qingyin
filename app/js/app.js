(() => {
  const $ = (s, root = document) => root.querySelector(s);
  const $$ = (s, root = document) => [...root.querySelectorAll(s)];
  const state = {
    route: 'home', home: { daily: [], playlists: [] }, discover: {}, local: [], localFolders: [], localIndex: null, localLoaded: false, localSnapshot: null,
    downloads: [], search: [], myPlaylists: [], qqProfile: null, qqPlaylists: [], playlistCache: new Map(), libraryLoaded: false, history: Store.getHistory?.() || [], detailStack: [],
  };
  let toastTimer = null, qrLoginStop = null, sheetCleanup = null, playerView = 'disc';
  const FALLBACK_COVER = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="120" height="120"><rect fill="#eceef1" width="120" height="120"/><text x="50%" y="56%" fill="#e83a3a" font-size="38" text-anchor="middle">♪</text></svg>');

  function escapeHTML(v) { return String(v ?? '').replace(/[&<>'"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[c])); }
  function fmtTime(ms) { const s = Math.floor((Number(ms) || 0) / 1000); return s ? `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}` : ''; }
  function toast(text) { const el = $('#toast'); el.textContent = text; el.classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('show'), 2400); }
  function setImage(img, url) {
    if (!img) return; img.src = FALLBACK_COVER; if (!url) return;
    const cls = img.className || '', id = img.id || '';
    const size = id === 'player-cover' ? 640 : (id === 'mini-cover' || /track-cover|library-item/.test(cls) ? 120 : (/cover-card|rank-card|artist-card/.test(cls) ? 280 : 180));
    DL.imageCache(url, size).then(v => { if (img.isConnected) img.src = v || DL.coverUrl(url, size) || FALLBACK_COVER; }).catch(() => { img.src = DL.coverUrl(url, size) || FALLBACK_COVER; });
  }
  const lazyCoverObserver = typeof IntersectionObserver === 'function' ? new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (!entry.isIntersecting) return;
      const img = entry.target; lazyCoverObserver.unobserve(img);
      setImage(img, img.dataset.cover || ''); delete img.dataset.cover;
    });
  }, { rootMargin: '240px 0px' }) : null;
  function setLazyImage(img, url) {
    if (!img) return;
    img.src = FALLBACK_COVER; if (!url) return;
    img.loading = 'lazy'; img.decoding = 'async'; img.dataset.cover = url;
    if (lazyCoverObserver) lazyCoverObserver.observe(img); else setImage(img, url);
  }
  function defer(task, timeout = 600) {
    if (typeof requestIdleCallback === 'function') requestIdleCallback(task, { timeout });
    else setTimeout(task, timeout);
  }
  function applyTheme(theme = Store.getTheme()) {
    const dark = theme === 'dark'; document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#1c1116' : '#74142f');
  }
  function isLoggedIn() { return !!Store.getCookie(); }
  function nativeQQ(method, ...args) {
    const bridge = window.NativeBridge;
    if (!bridge || typeof bridge[method] !== 'function') return { state: 'unsupported', message: '当前运行环境不支持 QQ 登录' };
    try { const raw = bridge[method](...args); return typeof raw === 'string' ? JSON.parse(raw) : (raw || {}); }
    catch (error) { return { state: 'failed', message: error?.message || 'QQ 原生服务调用失败' }; }
  }
  function qqLoggedIn() { return !!state.qqProfile?.id; }

  function showRoute(route, { push = true } = {}) {
    const name = route === 'detail' ? 'detail' : route;
    if (push && state.route !== name && state.route !== 'detail') state.detailStack = [];
    state.route = name;
    $$('.view').forEach(v => v.classList.toggle('active', v.id === `view-${name}`));
    $$('.nav-item').forEach(btn => btn.classList.toggle('active', btn.dataset.route === name));
    if (name === 'home') loadHome();
    if (name === 'discover') loadDiscover();
    if (name === 'local') loadLocal();
    if (name === 'downloads') renderDownloads();
    if (name === 'library') loadLibrary();
    $('#main-scroll').scrollTo({ top: 0, behavior: 'instant' });
  }

  function trackMeta(song) { return [song.artists || '未知歌手', song.album || '', fmtTime(song.duration)].filter(Boolean).join(' · '); }
  function renderTracks(container, songs, { empty, local = false, context } = {}) {
    container.innerHTML = '';
    if (!songs?.length) { if (empty) empty.classList.remove('hidden'); return; }
    if (empty) empty.classList.add('hidden');
    const playContext = context || songs, fragment = document.createDocumentFragment();
    songs.forEach((song, index) => {
      const row = document.createElement('article');
      row.className = 'track-item' + (String(Player.current?.id) === String(song.id) ? ' playing' : '');
      row.dataset.id = song.id;
      row.innerHTML = `<span class="track-index">${index + 1}</span><img class="track-cover" alt="封面"><div class="track-copy"><strong class="track-name"></strong><p class="track-meta"></p></div><span class="track-local-badge hidden">本地</span><button class="track-action" aria-label="更多">⋮</button>`;
      row.querySelector('.track-name').textContent = song.name || '未知歌曲';
      row.querySelector('.track-meta').textContent = local ? `${song.artists || '未知歌手'} · ${song.folder || '本地音乐'}` : trackMeta(song);
      if (song._localMatch) { const badge = row.querySelector('.track-local-badge'); badge.textContent = '本地可播'; badge.classList.remove('hidden'); row.classList.add('local-ready'); }
      setImage(row.querySelector('img'), song.picUrl);
      row.addEventListener('click', event => { if (!event.target.closest('.track-action')) play(song, playContext); });
      row.querySelector('.track-action').addEventListener('click', event => { event.stopPropagation(); openTrackActions(song, playContext); });
      fragment.appendChild(row);
    });
    container.appendChild(fragment);
  }

  async function play(song, list) {
    try { await Player.playSong(song, list); $('#mini-player').classList.remove('hidden'); }
    catch { toast('歌曲暂时无法播放'); }
  }

  function openTrackActions(song, context) {
    const local = !!song._nativeLocal || !!song._localMatch;
    const favourite = Store.isFav(song.id);
    openSheet(song.name || '歌曲操作', `
      <div class="sheet-list">
        <button data-sheet-play><strong>立即播放</strong><span>▶</span></button>
        <button data-sheet-fav><strong>${favourite ? '取消收藏' : '收藏歌曲'}</strong><span>${favourite ? '♥' : '♡'}</span></button>
        ${local ? '<button data-sheet-rematch><strong>重新匹配歌词</strong><span>⌕</span></button>' : '<button data-sheet-download><strong>下载到本地</strong><span>⇩</span></button>'}
      </div>`);
    $('[data-sheet-play]')?.addEventListener('click', () => { closeSheet(); play(song, context); });
    $('[data-sheet-fav]')?.addEventListener('click', () => { const on = Store.toggleFav(song); toast(on ? '已收藏' : '已取消收藏'); closeSheet(); loadLibrary(); });
    $('[data-sheet-download]')?.addEventListener('click', async () => { closeSheet(); await download(song); });
    $('[data-sheet-rematch]')?.addEventListener('click', async () => { closeSheet(); await play(song, context); await Player.refreshCurrentLyric(); openPlayer(); });
  }

  async function download(song) {
    try {
      const result = await DL.downloadSong(song);
      if (result?.state === 'permission_required') toast(result.message || '请允许权限后重试');
      else toast('已交给系统下载');
      renderDownloads();
    } catch (e) { toast(e.message || '创建下载任务失败'); }
  }

  function card(item, kind, onClick) {
    const el = document.createElement('button');
    el.className = kind === 'grid' ? 'cover-card' : 'cover-card';
    el.innerHTML = '<img alt="封面"><strong></strong><small></small>';
    setImage(el.querySelector('img'), item.coverImgUrl || item.picUrl);
    el.querySelector('strong').textContent = item.name || '未命名';
    el.querySelector('small').textContent = item.creator || item.artist || '';
    el.addEventListener('click', onClick);
    return el;
  }
  function renderCards(container, list, action) { container.innerHTML = ''; (list || []).forEach(item => container.appendChild(card(item, 'row', () => action(item)))); }

  async function loadHome() {
    $('#home-greeting').textContent = isLoggedIn() ? '为你准备了今天的好音乐' : '登录后，开启你的专属推荐';
    const result = await MusicFeatures.home();
    state.home.daily = result.daily?.length ? result.daily : [];
    state.home.playlists = result.recommended?.length ? result.recommended : result.personalized || [];
    renderTracks($('#home-daily-list'), state.home.daily, { empty: $('#home-daily-empty') });
    renderCards($('#home-playlists'), state.home.playlists, item => openPlaylist(item.id));
  }

  async function loadDiscover() {
    if ($('#discover-content').dataset.loaded === '1') return;
    const d = await MusicFeatures.discover(); state.discover = d;
    const ranks = $('#discover-toplists'); ranks.innerHTML = '';
    d.toplists.slice(0, 8).forEach(item => {
      const el = document.createElement('button'); el.className = 'rank-card'; el.innerHTML = '<img alt="封面"><span><strong></strong><small></small></span>';
      setImage(el.querySelector('img'), item.coverImgUrl); el.querySelector('strong').textContent = item.name; el.querySelector('small').textContent = item.creator || '网易云榜单'; el.addEventListener('click', () => openPlaylist(item.id)); ranks.appendChild(el);
    });
    const grid = $('#discover-playlists'); grid.innerHTML = ''; d.playlists.forEach(item => { const el = card(item, 'grid', () => openPlaylist(item.id)); grid.appendChild(el); });
    renderCards($('#discover-albums'), d.albums, item => openAlbum(item.id));
    const artists = $('#discover-artists'); artists.innerHTML = ''; d.artists.forEach(item => { const el = document.createElement('button'); el.className = 'artist-card'; el.innerHTML = '<img alt="歌手"><span></span>'; setImage(el.querySelector('img'), item.picUrl); el.querySelector('span').textContent = item.name; el.addEventListener('click', () => openArtist(item.id)); artists.appendChild(el); });
    const hint = '暂未获取到内容，请在“我的 → 设置”中检查 NCMC 服务地址';
    if (!d.toplists.length) ranks.innerHTML = `<p class="inline-empty">${hint}</p>`;
    if (!d.playlists.length) grid.innerHTML = `<p class="inline-empty">${hint}</p>`;
    if (!d.albums.length) $('#discover-albums').innerHTML = `<p class="inline-empty">${hint}</p>`;
    if (!d.artists.length) artists.innerHTML = `<p class="inline-empty">${hint}</p>`;
    $('#discover-content').dataset.loaded = '1';
  }

  async function search() {
    const keyword = $('#input-search').value.trim(); if (!keyword) return toast('请输入要搜索的内容');
    $('#search-result').classList.remove('hidden'); $('#search-empty').textContent = '搜索中…';
    let list = []; try { list = await NCM.search(keyword, 50); } catch {} if (!list.length && (Store.getApiKey() || Store.isBackup())) try { list = await API.search(keyword, 50); } catch {}
    state.search = list; renderTracks($('#search-list'), list, { empty: $('#search-empty') }); if (!list.length) $('#search-empty').textContent = '未找到相关歌曲';
  }

  function localScanStamp(scannedAt) {
    if (!scannedAt) return '';
    const date = new Date(scannedAt); if (Number.isNaN(date.getTime())) return '';
    return ` · 上次扫描 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  }
  function renderLocalResult(result) {
    state.local = result.songs || []; state.localFolders = result.folders || []; state.localIndex = Local.createMatchIndex(state.local); state.localLoaded = result.state === 'ready';
    if (state.localLoaded) state.localSnapshot = { ...result, songs: state.local, folders: state.localFolders };
    $('#local-summary').textContent = `${result.directory || Local.getDirectoryLabel()}${localScanStamp(result.scannedAt)}`; $('#local-count').textContent = `${state.local.length} 首`;
    renderCustomFolders(); renderTracks($('#local-list'), state.local, { empty: $('#local-empty'), local: true });
    if (result.state === 'permission_required' && !state.local.length) $('#local-empty').textContent = result.message || '请允许音乐和音频权限后重新扫描';
    else if (!state.local.length) $('#local-empty').textContent = result.state === 'unsupported' ? result.message : '没有发现可播放的本地音乐';
  }
  async function loadLocal({ force = false } = {}) {
    if (!force && state.localLoaded) {
      renderLocalResult(state.localSnapshot || { state: 'ready', songs: state.local, folders: state.localFolders, directory: Local.getDirectoryLabel() });
      return;
    }
    const button = $('#btn-local-scan');
    if (force) { button.disabled = true; button.textContent = '扫描中'; }
    else { $('#local-empty').textContent = '正在读取已扫描的本地音乐…'; $('#local-empty').classList.remove('hidden'); }
    try {
      const result = await Local.scan({ force }); renderLocalResult(result);
      if (result.cached) toast('已显示上次扫描的本地音乐');
      if (result.stale) toast('本次扫描未完成，继续显示上次结果');
      if (result.truncated) toast('自定义目录歌曲过多，已显示前 8000 首');
    } finally {
      if (force) { button.disabled = false; button.textContent = '扫描'; }
    }
  }
  async function hydrateLocalCache() {
    const cached = await Local.getCachedScan();
    if (!cached || cached.state !== 'ready') return;
    state.local = cached.songs || []; state.localFolders = cached.folders || []; state.localIndex = Local.createMatchIndex(state.local);
    state.localLoaded = true; state.localSnapshot = { ...cached, songs: state.local, folders: state.localFolders };
  }
  function renderCustomFolders() {
    const box = $('#custom-folders'); box.innerHTML = ''; $('#custom-folders-empty').classList.toggle('hidden', !!state.localFolders.length);
    state.localFolders.forEach(folder => { const chip = document.createElement('span'); chip.className = 'folder-chip'; chip.innerHTML = '<strong></strong><button aria-label="移除文件夹">×</button>'; chip.querySelector('strong').textContent = folder.name || '自定义文件夹'; chip.querySelector('button').addEventListener('click', () => { if (Local.removeCustomFolder(folder.uri)) { toast('已移除文件夹，正在更新本地音乐'); state.localLoaded = false; loadLocal({ force: true }); } }); box.appendChild(chip); });
  }

  async function renderDownloads() {
    try { state.downloads = await DL.refreshSystemDownloads(); } catch { state.downloads = await DL.getAll(); }
    const box = $('#download-list'); box.innerHTML = '';
    state.downloads.forEach(song => {
      const row = document.createElement('article'); const status = song.downloadState || (song.systemDownload ? 'queued' : 'completed');
      row.className = `download-item ${status}`; row.innerHTML = '<img class="track-cover" alt="封面"><div class="download-copy"><strong></strong><small></small><div class="download-progress"><i></i></div></div><div class="download-actions"><button class="play">▶</button><button class="remove">×</button></div>';
      setImage(row.querySelector('img'), song.picUrl); row.querySelector('strong').textContent = song.name || '未知歌曲'; const total = Number(song.totalBytes || song.blobSize || 0), bytes = Number(song.downloadedBytes || total || 0); const label = ({ queued: '等待下载', downloading: '下载中', paused: '已暂停', completed: '已完成', failed: '下载失败', missing: '文件不存在' })[status] || status;
      row.querySelector('small').textContent = `${label}${total ? ` · ${DL.formatBytes(total)}` : ''}`; row.querySelector('i').style.width = total ? `${Math.min(100, Math.round(bytes / total * 100))}%` : (status === 'completed' ? '100%' : '0');
      row.querySelector('.play').disabled = status !== 'completed'; row.querySelector('.play').addEventListener('click', async () => { const url = await DL.playDownloaded(song.id); if (!url) return toast('离线文件无法读取'); play({ ...song, _localUrl: url }, [{ ...song, _localUrl: url }]); }); row.querySelector('.remove').addEventListener('click', async () => { await DL.remove(song.id); renderDownloads(); }); box.appendChild(row);
    }); $('#download-empty').classList.toggle('hidden', !!state.downloads.length);
  }

  function playlistListsEqual(a, b) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((item, index) => String(item.id) === String(b[index]?.id) && Number(item.trackCount || 0) === Number(b[index]?.trackCount || 0) && item.name === b[index]?.name);
  }
  function renderMyPlaylists(list) {
    const box = $('#my-playlists'), fragment = document.createDocumentFragment(); box.innerHTML = '';
    (list || []).forEach(item => {
      const row = document.createElement('button'); row.className = 'library-item';       row.innerHTML = '<img alt="歌单"><span><strong></strong><small></small></span>';
      setLazyImage(row.querySelector('img'), item.coverImgUrl); row.querySelector('strong').textContent = item.name;
      row.querySelector('small').textContent = `${item.sourceLabel || (item.source === 'qq' ? 'QQ音乐' : '网易云音乐')} · ${item.trackCount || 0} 首`;

      row.addEventListener('click', () => openPlaylist(item.id)); fragment.appendChild(row);
    });
    box.appendChild(fragment); $('#my-playlists-empty').classList.toggle('hidden', !!list?.length);
  }
  async function loadQQPlaylists({ force = false } = {}) {
    const account = nativeQQ('qqAccount');
    state.qqProfile = account.loggedIn ? account.profile : null;
    if (!state.qqProfile?.id) { state.qqPlaylists = []; return []; }
    const cacheKey = `qpl:v1:${state.qqProfile.id}:60`;
    if (!force) {
      const cached = await DL.cacheGet(cacheKey, { allowStale: true });
      if (Array.isArray(cached)) { state.qqPlaylists = cached; return cached; }
    }
    const result = nativeQQ('qqMyPlaylists');
    if (result.state === 'ready' && Array.isArray(result.playlists)) {
      state.qqPlaylists = result.playlists; await DL.cachePut(cacheKey, result.playlists, 12 * 60 * 60 * 1000); return result.playlists;
    }
    return state.qqPlaylists || [];
  }
  function normalizeNcmPlaylists(list) { return (list || []).map(item => ({ ...item, id: String(item.id).startsWith('ncm:') ? item.id : `ncm:${item.id}`, rawId: String(item.id).replace(/^ncm:/, ''), source: 'ncm', sourceLabel: '网易云音乐' })); }
  async function loadLibrary({ force = false } = {}) {
    const user = Store.getUser?.(), ncmLogged = isLoggedIn();
    const refreshButton = $('#btn-library-refresh');
    if (force) { refreshButton.disabled = true; refreshButton.textContent = '刷新中'; }
    try {
      let ncmPlaylists = [];
      if (ncmLogged) {
        const uid = String(user?.userId || user?.uid || 0), cacheKey = `upl:v2:${uid}:60`;
        if (state.libraryUid === uid && state.libraryLoaded && !force) ncmPlaylists = state.ncmPlaylists || [];
        else if (!force) {
          const cached = await DL.cacheGet(cacheKey, { allowStale: true });
          if (Array.isArray(cached)) ncmPlaylists = normalizeNcmPlaylists(cached);
          else ncmPlaylists = normalizeNcmPlaylists(await NCM.refreshUserPlaylist(uid, 60));
        } else ncmPlaylists = normalizeNcmPlaylists(await NCM.refreshUserPlaylist(uid, 60));
        state.libraryUid = uid; state.ncmPlaylists = ncmPlaylists;
      }
      const qqPlaylists = await loadQQPlaylists({ force });
      state.myPlaylists = [...ncmPlaylists, ...qqPlaylists]; state.libraryLoaded = true;
      const connected = [ncmLogged && '网易云音乐', qqLoggedIn() && 'QQ音乐'].filter(Boolean);
      const primary = user?.nickname || state.qqProfile?.name || '登录音乐账号';
      $('#profile-name').textContent = primary;
      $('#profile-desc').textContent = connected.length ? `已连接 ${connected.join(' · ')} · 歌单本地缓存` : '登录网易云或 QQ 音乐同步歌单';
      $('#profile-avatar').textContent = (user?.nickname || state.qqProfile?.name || '♪').slice(0, 1);
      renderMyPlaylists(state.myPlaylists);
      if (!state.myPlaylists.length) $('#my-playlists-empty').textContent = connected.length ? '暂无已保存的歌单，请点击刷新' : '登录后查看网易云或 QQ 音乐歌单';
    } catch {
      if (!state.myPlaylists.length) renderMyPlaylists([]);
    } finally { if (force) { refreshButton.disabled = false; refreshButton.textContent = '刷新'; } }
  }

  async function openPlaylist(id) {
    const key = String(id), fromMemory = state.playlistCache.get(key);
    if (fromMemory) { showDetail({ type: 'playlist', data: fromMemory }); return; }
    const isQQ = key.startsWith('qq:');
    const cacheKey = isQQ ? `qpldetail:v1:${key}` : `pl:v2:${key}`;
    const cached = await DL.cacheGet(cacheKey, { allowStale: true });
    if (cached) {
      state.playlistCache.set(key, cached); showDetail({ type: 'playlist', data: cached });
      return;
    }
    try {
      const pl = isQQ ? nativeQQ('qqPlaylistDetail', key) : await MusicFeatures.playlist(key.replace(/^ncm:/, ''));
      const detail = isQQ ? (pl.state === 'ready' ? pl.playlist : null) : pl;
      if (!detail) throw new Error(pl.message || 'QQ 歌单读取失败');
      await DL.cachePut(cacheKey, detail, 12 * 60 * 60 * 1000); state.playlistCache.set(key, detail); showDetail({ type: 'playlist', data: detail });
    } catch { toast(isQQ ? '无法载入 QQ 歌单' : '无法载入歌单'); }
  }
  async function openAlbum(id) { try { const album = await MusicFeatures.album(id); showDetail({ type: 'album', data: { ...album, tracks: album.songs } }); } catch { toast('无法载入专辑'); } }
  async function openArtist(id) { try { const artist = await MusicFeatures.artist(id); showDetail({ type: 'artist', data: { ...artist.profile, tracks: artist.songs } }); } catch { toast('无法载入歌手信息'); } }
  function showDetail(detail) {
    state.detailStack.push(state.route); state.route = 'detail'; $$('.view').forEach(v => v.classList.toggle('active', v.id === 'view-detail')); $$('.nav-item').forEach(b => b.classList.remove('active'));
    const d = detail.data, rawTracks = d.tracks || [], box = $('#detail-content'); let resolvedTracks = rawTracks;
    box.innerHTML = `<div class="detail-hero"><img alt="封面"><div><h1></h1><p></p><div class="detail-actions"><button data-play-all>播放全部</button><button data-download-all>下载全部</button></div></div></div><div id="detail-tracks" class="track-list"></div><p id="detail-empty" class="placeholder"></p>`;
    setImage($('img', box), d.coverImgUrl || d.picUrl); $('h1', box).textContent = d.name || '详情'; const summary = $('p', box), trackBox = $('#detail-tracks'), empty = $('#detail-empty');
    summary.textContent = d.creator || d.artist || d.briefDesc || d.description || '';
    const renderDetailTracks = tracks => {
      $('[data-detail-more]', box)?.remove();
      const initial = 80, shown = Math.min(initial, tracks.length); renderTracks(trackBox, tracks.slice(0, shown), { empty, context: tracks });
      if (tracks.length > shown) {
        const more = document.createElement('button'); more.className = 'detail-load-more'; more.dataset.detailMore = '1'; more.textContent = `加载余下 ${tracks.length - shown} 首歌曲`;
        more.addEventListener('click', () => { renderTracks(trackBox, tracks, { empty, context: tracks }); more.remove(); }); trackBox.after(more);
      }
    };
    renderDetailTracks(rawTracks);
    const resolveLocal = () => {
      const tracks = Local.preferLocal(rawTracks, state.localIndex), localCount = tracks.filter(song => song._localMatch).length;
      resolvedTracks = tracks; summary.textContent = [d.creator || d.artist || d.briefDesc || d.description || '', localCount ? `${localCount} 首本地可播` : ''].filter(Boolean).join(' · ');
      renderDetailTracks(tracks);
    };
    $('[data-play-all]', box).addEventListener('click', () => resolvedTracks[0] && play(resolvedTracks[0], resolvedTracks));
    $('[data-download-all]', box).addEventListener('click', async () => { for (const song of resolvedTracks.slice(0, 50)) await DL.downloadSong(song); toast(`已创建 ${Math.min(50, resolvedTracks.length)} 个下载任务`); });
    if (rawTracks.length > 60) defer(resolveLocal, 900); else resolveLocal(); $('#main-scroll').scrollTo(0, 0);
  }
  function backFromDetail() { const route = state.detailStack.pop() || 'home'; showRoute(route, { push: false }); }

  function setPlayerView(view) {
    playerView = view === 'lyrics' ? 'lyrics' : 'disc';
    const full = $('#player-full'); full.classList.toggle('lyrics-mode', playerView === 'lyrics');
    $('#btn-player-view').textContent = playerView === 'lyrics' ? '唱' : '词';
    $('#btn-player-view').setAttribute('aria-label', playerView === 'lyrics' ? '切换到黑胶' : '切换到纯歌词');
    $('#now-playing').setAttribute('aria-label', playerView === 'lyrics' ? '当前为纯歌词模式' : '切换到纯歌词');
  }
  function togglePlayerView() { setPlayerView(playerView === 'disc' ? 'lyrics' : 'disc'); }
  function openPlayer() { setPlayerView('disc'); $('#player-full').classList.remove('hidden'); }
  function closePlayer() { $('#player-full').classList.add('hidden'); }
  function recoverLyricsIfEmpty() {
    if (!Player.current || Player.lyrics?.length) return;
    Player.refreshCurrentLyric();
    toast('正在重新恢复歌词');
  }
  function handleAppBack() {
    if (!$('#sheet').classList.contains('hidden')) { closeSheet(); return; }
    if (!$('#player-full').classList.contains('hidden')) { closePlayer(); return; }
    if (!$('#view-detail').classList.contains('hidden') && $('#view-detail').classList.contains('active')) { backFromDetail(); return; }
    if (!$('#search-result').classList.contains('hidden')) { $('#search-result').classList.add('hidden'); return; }
    if (state.route !== 'home') { showRoute('home'); return; }
    toast('已在首页');
  }
  function renderLyrics(lines) {
    const box = $('#lyrics'); box.innerHTML = '<div class="lyrics-inner"></div>';
    const inner = $('.lyrics-inner', box);
    if (!lines?.length) { inner.innerHTML = '<p class="active">暂无歌词</p>'; return; }
    lines.forEach((line, i) => {
      const p = document.createElement('p'); p.dataset.index = i;
      const main = document.createElement('span'); main.className = 'lyric-main'; main.textContent = line.text;
      p.appendChild(main);
      if (line.translation && line.translation !== line.text) {
        const sub = document.createElement('span'); sub.className = 'translation'; sub.textContent = line.translation;
        p.appendChild(sub);
      }
      inner.appendChild(p);
    });
  }
  function updateLyric(index) { const inner = $('.lyrics-inner'); if (!inner) return; const all = $$('p', inner); if (!all.length) return; const active = index < 0 ? 0 : index; all.forEach((p, i) => p.classList.toggle('active', i === active && index >= 0)); const line = all[active]; const viewport = $('#lyrics'); const y = viewport.clientHeight * .48 - (line.offsetTop + line.offsetHeight / 2); inner.style.transform = `translateY(${y}px)`; }

  function updatePlayer(song) {
    if (!song) return;
    $('#mini-name').textContent = song.name || '未知歌曲'; $('#mini-artist').textContent = song.artists || '未知歌手';
    $('#player-name').textContent = song.name || '未知歌曲'; $('#player-artist').textContent = song.artists || '—';
    setImage($('#mini-cover'), song.picUrl); setImage($('#player-cover'), song.picUrl);
    $('#player-bg').style.backgroundImage = song.picUrl ? `url(${DL.coverUrl(song.picUrl, 640)})` : '';
    $('#lyric-status').textContent = `音质 · ${song._playbackQuality || (song._nativeLocal ? '本地文件 · 原始音质' : Store.getQualityLabel())}`;
    const favorite = Store.isFav(song.id); $$('.player-favorite').forEach(btn => { btn.textContent = favorite ? '♥' : '♡'; btn.classList.toggle('is-favorite', favorite); });
    $('#mini-player').classList.remove('hidden'); $$('.track-item').forEach(row => row.classList.toggle('playing', row.dataset.id === String(song.id)));
  }
  function toggleFavorite() {
    const song = Player.current; if (!song) return;
    const on = Store.toggleFav(song); $$('.player-favorite').forEach(btn => { btn.textContent = on ? '♥' : '♡'; btn.classList.toggle('is-favorite', on); });
    toast(on ? '已收藏' : '已取消收藏');
  }
  function updatePlayState(playing) {
    $('#btn-mini-play').textContent = playing ? 'Ⅱ' : '▶';
    $('#btn-player-toggle').textContent = playing ? 'Ⅱ' : '▶';
    $('#player-full').classList.toggle('is-playing', !!playing);
  }
  function modeMeta(value = Player.mode) {
    return ({ loop: { icon: '↻', label: '列表循环' }, single: { icon: '①', label: '单曲循环' }, shuffle: { icon: '⇝', label: '随机播放' } })[value] || { icon: '↻', label: '列表循环' };
  }
  function updateModeControl(value = Player.mode) {
    const meta = modeMeta(value), button = $('#btn-player-mode');
    if (!button) return;
    button.textContent = meta.icon; button.setAttribute('aria-label', meta.label); button.title = meta.label;
  }
  function openQueueSheet() {
    const render = () => {
      const queue = Player.queue, active = Player.index, meta = modeMeta();
      const rows = queue.map((song, rowIndex) => {
        const isActive = rowIndex === active;
        return `<li class="queue-row${isActive ? ' active' : ''}" data-queue-index="${rowIndex}"><button class="queue-play" data-queue-play="${rowIndex}" aria-label="播放 ${escapeHTML(song.name || '未知歌曲')}"><span class="queue-index">${isActive ? '♪' : rowIndex + 1}</span><img src="${FALLBACK_COVER}" data-queue-cover="${rowIndex}" alt="封面"><span class="queue-copy"><strong>${escapeHTML(song.name || '未知歌曲')}</strong><small>${escapeHTML([song.artists || '未知歌手', song.album || ''].filter(Boolean).join(' · '))}</small></span></button>${queue.length > 1 ? `<button class="queue-remove" data-queue-remove="${rowIndex}" aria-label="从队列移除 ${escapeHTML(song.name || '未知歌曲')}">×</button>` : ''}</li>`;
      }).join('');
      $('#sheet-content').innerHTML = `<div class="queue-sheet"><div class="queue-toolbar"><span>${queue.length} 首 · ${meta.label}</span><div><button id="btn-queue-mode" class="queue-tool" aria-label="切换播放模式">${meta.icon}</button><button id="btn-queue-clear" class="queue-clear"${queue.length <= 1 ? ' disabled' : ''}>清空</button></div></div><ol id="queue-list" class="queue-list">${rows || '<li class="queue-empty">播放队列为空</li>'}</ol></div>`;
      $$('#queue-list [data-queue-cover]').forEach(img => { const song = queue[Number(img.dataset.queueCover)]; if (song) setImage(img, song.picUrl); });
      $('#btn-queue-mode')?.addEventListener('click', () => { const next = Player.cycleMode(); toast(`已切换为${modeMeta(next).label}`); render(); });
      $('#btn-queue-clear')?.addEventListener('click', () => { Player.clearQueue(); toast('已保留当前歌曲'); render(); });
      $('#queue-list')?.addEventListener('click', event => {
        const playButton = event.target.closest('[data-queue-play]');
        const removeButton = event.target.closest('[data-queue-remove]');
        if (playButton) { const target = Number(playButton.dataset.queuePlay); Player.playAt(target); closeSheet(); return; }
        if (removeButton) { const target = Number(removeButton.dataset.queueRemove); const removed = Player.removeAt(target); if (removed) { toast('已从播放队列移除'); render(); } }
      });
    };
    openSheet('当前播放', ''); render();
    const unsubscribe = Player.on(type => { if (type === 'queue' || type === 'mode') render(); });
    sheetCleanup = unsubscribe;
  }

  function openSheet(title, html) {
    if (sheetCleanup) { try { sheetCleanup(); } catch {} sheetCleanup = null; }
    $('#sheet-title').textContent = title; $('#sheet-content').innerHTML = html; $('#sheet').classList.remove('hidden');
  }
  function closeSheet() {
    if (qrLoginStop) { qrLoginStop(); qrLoginStop = null; }
    if (sheetCleanup) { try { sheetCleanup(); } catch {} sheetCleanup = null; }
    $('#sheet').classList.add('hidden');
  }
  function openLogin() {
    closeSheet();
    openSheet('登录网易云账号', `<div class="qr-login">
      <div class="qr-login-copy"><strong>扫码登录</strong><span>使用网易云音乐 App 扫描二维码</span></div>
      <div class="qr-frame"><img id="login-qr-image" alt="网易云登录二维码" /><span id="login-qr-placeholder">正在生成二维码…</span></div>
      <p id="login-qr-status" class="qr-status">正在创建安全登录二维码</p>
      <div class="qr-actions"><button id="btn-login-qr-refresh" class="button-outline">刷新二维码</button><button id="btn-login-cookie-toggle" class="button-text">使用 Cookie 登录</button></div>
      <div id="login-cookie-form" class="sheet-form hidden"><label>MUSIC_U Cookie<input id="login-cookie" type="password" placeholder="粘贴 MUSIC_U=..." /></label><label>昵称（可选）<input id="login-name" placeholder="用于显示" /></label><button id="btn-login-save" class="button-primary">保存并登录</button></div>
    </div>`);
    let timer = null, key = '';
    const status = text => { const node = $('#login-qr-status'); if (node) node.textContent = text; };
    const stop = () => { if (timer) { clearInterval(timer); timer = null; } };
    qrLoginStop = stop;
    const finish = async () => {
      stop();
      try { const profile = await NCM.userAccount(); if (profile) Store.setUser(profile); } catch {}
      state.libraryLoaded = false; closeSheet(); loadLibrary(); loadHome(); toast('登录成功，已同步网易云账号');
    };
    const check = async () => {
      if (!key) return;
      try {
        const result = await NCM.qrCheck(key);
        if (result.code === 803) { if (result.cookie) Store.setCookie(result.cookie); await finish(); }
        else if (result.code === 802) status('已扫码，请在网易云音乐 App 中确认');
        else if (result.code === 800) { stop(); status('二维码已过期，请点击刷新二维码'); }
        else status('等待网易云音乐 App 扫码');
      } catch { status('登录服务暂不可用，请刷新或使用 Cookie 登录'); }
    };
    const create = async () => {
      stop(); key = ''; const image = $('#login-qr-image'), placeholder = $('#login-qr-placeholder');
      if (image) image.removeAttribute('src'); if (placeholder) placeholder.textContent = '正在生成二维码…'; status('正在创建安全登录二维码');
      try {
        key = await NCM.qrKey(); const qr = await NCM.qrCreate(key);
        if (!qr.qrimg) throw new Error('二维码图片不可用');
        if (image) image.src = qr.qrimg; if (placeholder) placeholder.classList.add('hidden');
        status('请使用网易云音乐 App 扫码'); await check(); timer = setInterval(check, 1800);
      } catch { status('二维码获取失败，请检查 NCMC 服务地址'); if (placeholder) placeholder.textContent = '二维码暂不可用'; }
    };
    $('#btn-login-qr-refresh').addEventListener('click', create);
    $('#btn-login-cookie-toggle').addEventListener('click', () => $('#login-cookie-form').classList.toggle('hidden'));
    $('#btn-login-save').addEventListener('click', async () => {
      const cookie = $('#login-cookie').value.trim(); if (!cookie) return toast('请输入 MUSIC_U Cookie');
      Store.setCookie(cookie); await finish();
    });
    create();
  }
  function openQQLogin() {
    closeSheet();
    openSheet('登录 QQ 音乐', `<div class="qr-login qq-login"><div class="qr-login-copy"><strong>扫码登录</strong><span>使用 QQ App 扫描二维码并确认</span></div><div class="qr-frame"><img id="qq-login-qr-image" alt="QQ 音乐登录二维码" /><span id="qq-login-qr-placeholder">正在生成二维码…</span></div><p id="qq-login-qr-status" class="qr-status">正在创建 QQ 登录二维码</p><div class="qr-actions"><button id="btn-qq-login-refresh" class="button-outline">刷新二维码</button><button id="btn-qq-login-logout" class="button-text hidden">退出 QQ 登录</button></div><p class="qr-login-note">登录会话仅加密保存在本机，用于读取你的 QQ 歌单，不会上传到任何自建服务。</p></div>`);
    let timer = null, sessionId = '';
    const status = text => { const node = $('#qq-login-qr-status'); if (node) node.textContent = text; };
    const stop = () => { if (timer) { clearInterval(timer); timer = null; } };
    qrLoginStop = stop;
    const resultAccount = nativeQQ('qqAccount');
    if (resultAccount.loggedIn) {
      state.qqProfile = resultAccount.profile;
      $('#btn-qq-login-logout')?.classList.remove('hidden'); status(`已登录 ${state.qqProfile?.name || 'QQ 音乐账号'}`);
    }
    const finish = async profile => {
      stop(); state.qqProfile = profile || nativeQQ('qqAccount').profile || null; state.libraryLoaded = false;
      closeSheet(); await loadLibrary({ force: true }); loadHome(); toast('QQ 音乐登录成功，已合并歌单');
    };
    const check = async () => {
      if (!sessionId) return;
      const result = nativeQQ('qqQrCheck', sessionId);
      if (result.state === 'success') return finish(result.profile);
      if (result.state === 'scanned') status('已扫码，请在 QQ App 中确认登录');
      else if (result.state === 'authorized') status(result.message || 'QQ 已确认，正在建立 QQ 音乐会话…');
      else if (result.state === 'refused') { stop(); status(result.message || '已在 QQ 中取消登录，请刷新二维码重试'); }
      else if (result.state === 'expired') { stop(); status('二维码已过期，请点击刷新二维码'); }
      else if (result.state === 'failed') { stop(); status(result.message || `QQ 登录失败（${result.stage || 'unknown'}），请刷新二维码`); }
      else status('等待 QQ App 扫码');
    };
    const create = () => {
      stop(); sessionId = ''; const image = $('#qq-login-qr-image'), placeholder = $('#qq-login-qr-placeholder');
      if (image) image.removeAttribute('src'); if (placeholder) { placeholder.classList.remove('hidden'); placeholder.textContent = '正在生成二维码…'; } status('正在创建 QQ 登录二维码');
      setTimeout(() => {
        const result = nativeQQ('qqQrCreate');
        if (result.state !== 'ready' || !result.sessionId || !result.qrImage) { status(result.message || 'QQ 二维码获取失败'); if (placeholder) placeholder.textContent = '二维码暂不可用'; return; }
        sessionId = result.sessionId; if (image) image.src = result.qrImage; if (placeholder) placeholder.classList.add('hidden'); status('请使用 QQ App 扫码');
        check(); timer = setInterval(check, 1800);
      }, 30);
    };
    $('#btn-qq-login-refresh').addEventListener('click', create);
    $('#btn-qq-login-logout').addEventListener('click', async () => { nativeQQ('qqLogout'); state.qqProfile = null; state.qqPlaylists = []; state.libraryLoaded = false; closeSheet(); await loadLibrary(); toast('已退出 QQ 音乐'); });
    create();
  }
  function openSupport() {
    openSheet('赞赏支持', `<div class="donate-sheet"><p>感谢你愿意支持轻音。所有赞赏均为自愿，不影响任何功能。</p><div class="donate-codes"><figure><img src="assets/donate/wechat.jpg" alt="微信赞赏二维码" /><figcaption>微信赞赏</figcaption></figure><figure><img src="assets/donate/alipay.jpg" alt="支付宝赞赏二维码" /><figcaption>支付宝赞赏</figcaption></figure></div><button id="btn-donate-close" class="button-text">完成</button></div>`);
    $('#btn-donate-close').addEventListener('click', closeSheet);
  }
  function openSettings() {
    const isDark = Store.getTheme() === 'dark', quality = Store.getQuality();
    const qualityOptions = Store.getQualityOptions().map(item => `<option value="${item.value}"${item.value === quality ? ' selected' : ''}>${item.label}</option>`).join('');
    openSheet('设置', `<div class="sheet-form"><label>播放与下载音质<select id="setting-quality">${qualityOptions}</select><small>对后续在线播放与新建下载同时生效；本地文件保持原始音质</small></label><label>NCMC 服务地址<input id="setting-ncm" value="${escapeHTML(Store.getNcmcUrl())}" /></label><label>ChKSz API Key（备用）<input id="setting-key" type="password" value="${escapeHTML(Store.getApiKey())}" /></label><label>缓存上限（MB）<input id="setting-cache" type="number" value="${Store.getCacheSize()}" /></label><button id="btn-settings-save" class="button-primary">保存设置</button></div><div class="settings-theme-row"><span><strong>深色模式</strong><small>夜间使用更舒适</small></span><button id="btn-settings-theme" class="theme-switch${isDark ? ' is-dark' : ''}" aria-pressed="${isDark}"><i></i></button></div><button id="btn-settings-support" class="button-text">赞赏支持</button>`);
    $('#btn-settings-save').addEventListener('click', () => { Store.setQuality($('#setting-quality').value); Store.setNcmcUrl($('#setting-ncm').value.trim()); Store.setApiKey($('#setting-key').value.trim()); Store.setCacheSize(Number($('#setting-cache').value) || 0); closeSheet(); toast(`设置已保存 · ${Store.getQualityLabel()}`); });
    $('#btn-settings-theme').addEventListener('click', () => { const theme = Store.toggleTheme(); applyTheme(theme); const dark = theme === 'dark'; $('#btn-settings-theme').classList.toggle('is-dark', dark); $('#btn-settings-theme').setAttribute('aria-pressed', String(dark)); toast(dark ? '已开启深色模式' : '已切换为明亮模式'); });
    $('#btn-settings-support').addEventListener('click', openSupport);
  }


  function bind() {
    $$('.nav-item').forEach(btn => btn.addEventListener('click', () => showRoute(btn.dataset.route)));
    $$('[data-route]').forEach(btn => { if (!btn.classList.contains('nav-item')) btn.addEventListener('click', () => showRoute(btn.dataset.route)); });
    $('#btn-home').addEventListener('click', () => showRoute('home')); $('#btn-open-search').addEventListener('click', () => showRoute('discover'));
    $('#btn-account').addEventListener('click', () => showRoute('library')); $('#btn-home-daily').addEventListener('click', () => state.home.daily[0] ? play(state.home.daily[0], state.home.daily) : openLogin()); $('#btn-refresh-home').addEventListener('click', loadHome);
    $('#btn-quick-local').addEventListener('click', () => showRoute('local')); $('#btn-quick-download').addEventListener('click', () => showRoute('downloads'));
    $('#btn-discover-refresh').addEventListener('click', () => { $('#discover-content').dataset.loaded = ''; loadDiscover(); }); $('#btn-search').addEventListener('click', search); $('#input-search').addEventListener('keydown', e => { if (e.key === 'Enter') search(); }); $('#btn-close-search').addEventListener('click', () => $('#search-result').classList.add('hidden'));
    $('#btn-local-scan').addEventListener('click', () => loadLocal({ force: true })); $('#btn-add-folder').addEventListener('click', () => { if (!Local.chooseCustomFolder()) toast('当前版本不支持选择文件夹'); }); window.addEventListener('nativeMediaPermission', e => { if (e.detail?.granted) { state.localLoaded = false; setTimeout(() => loadLocal({ force: true }), 150); } }); window.addEventListener('nativeCustomMusicTree', e => { toast(e.detail?.added ? '已添加文件夹，正在扫描' : '未添加文件夹'); if (e.detail?.added) { state.localLoaded = false; setTimeout(() => loadLocal({ force: true }), 150); } });
    window.addEventListener('nativeMediaControl', event => {
      const action = String(event.detail?.action || '');
      if (action === 'play' && Player.paused) Player.toggle();
      else if (action === 'pause' && !Player.paused) Player.toggle();
      else if (action === 'next') Player.next();
      else if (action === 'previous') Player.prev();
      else if (action.startsWith('seek:')) {
        const duration = Number(document.getElementById('audio')?.duration) || 0;
        const position = Number(action.slice(5)) || 0;
        if (duration > 0) Player.seek(Math.max(0, Math.min(1, position / (duration * 1000))));
      }
    });
    $('#btn-refresh-downloads').addEventListener('click', renderDownloads); $('#btn-login').addEventListener('click', openLogin); $('#btn-login-qq').addEventListener('click', openQQLogin); $('#btn-account').addEventListener('click', () => { if (!isLoggedIn() && !qqLoggedIn()) openLogin(); }); $('#btn-library-settings').addEventListener('click', openSettings); $('#btn-library-refresh').addEventListener('click', () => loadLibrary({ force: true })); $('#btn-library-fav').addEventListener('click', () => { const list = Store.getFavs(); showDetail({ type: 'fav', data: { name: '我喜欢的音乐', coverImgUrl: '', tracks: list } }); }); $('#btn-library-history').addEventListener('click', () => { const list = Store.getHistory?.() || []; showDetail({ type: 'history', data: { name: '最近播放', coverImgUrl: '', tracks: list } }); });
    window.addEventListener('nativeAppBack', handleAppBack); $('#btn-detail-back').addEventListener('click', backFromDetail); $('#btn-mini-open').addEventListener('click', openPlayer); $('#btn-mini-play').addEventListener('click', () => Player.toggle()); $('#btn-mini-next').addEventListener('click', () => Player.next()); $('#btn-player-close').addEventListener('click', closePlayer); $('#btn-player-view').addEventListener('click', togglePlayerView); $('#now-playing').addEventListener('click', togglePlayerView); $('#btn-player-toggle').addEventListener('click', () => Player.toggle()); $('#btn-player-prev').addEventListener('click', () => Player.prev()); $('#btn-player-next').addEventListener('click', () => Player.next()); $('#btn-player-mode').addEventListener('click', () => { const next = Player.cycleMode(); updateModeControl(next); toast(`已切换为${modeMeta(next).label}`); }); $('#btn-player-fav').addEventListener('click', toggleFavorite); $('#btn-player-fav-secondary').addEventListener('click', toggleFavorite); $('#btn-lyric-rematch').addEventListener('click', () => { Player.refreshCurrentLyric(); toast('正在重新匹配歌词'); }); $('#btn-player-queue').addEventListener('click', openQueueSheet); $('#lyrics').addEventListener('click', recoverLyricsIfEmpty);

    const seekBar = $('#player-seek'); const finishSeek = event => { Player.seek(Number(event.target.value) / 1000); Player.setSeeking(false); };
    seekBar.addEventListener('pointerdown', () => Player.setSeeking(true)); seekBar.addEventListener('pointercancel', finishSeek); seekBar.addEventListener('pointerup', finishSeek); seekBar.addEventListener('input', event => Player.seek(Number(event.target.value) / 1000)); seekBar.addEventListener('change', finishSeek); $('#btn-sheet-close').addEventListener('click', closeSheet); $('#sheet').addEventListener('click', e => { if (e.target === $('#sheet')) closeSheet(); });
    Player.on((type, payload) => { if (type === 'song') updatePlayer(payload); if (type === 'play') updatePlayState(true); if (type === 'pause') updatePlayState(false); if (type === 'mode') updateModeControl(payload); if (type === 'time') { $('#mini-progress').style.width = `${Math.round(payload.ratio * 100)}%`; $('#player-seek').value = Math.round(payload.ratio * 1000); $('#time-current').textContent = payload.curText; $('#time-total').textContent = payload.durText; } if (type === 'lyric') renderLyrics(payload); if (type === 'lyricIndex') updateLyric(payload); if (type === 'lyricStatus') $('#lyric-status').textContent = payload.message || ''; if (type === 'seekRecovery') toast(payload); if (type === 'error') toast(payload || '播放失败'); });
  }

  function init() { applyTheme(); Player.bind(); bind(); updatePlayState(false); updateModeControl(Player.mode); void hydrateLocalCache(); loadHome(); }
  document.addEventListener('DOMContentLoaded', init);
})();
