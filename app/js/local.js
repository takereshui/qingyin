const Local = (() => {
  function native() { return typeof window !== 'undefined' ? window.NativeBridge : null; }
  function parseNative(raw, fallback = {}) {
    try { return typeof raw === 'string' ? JSON.parse(raw) : (raw || fallback); }
    catch { return fallback; }
  }
  const SCAN_CACHE_KEY = 'local-scan:v1';
  const SCAN_CACHE_VERSION = 1;
  function hasNativeScanner() { return !!native() && typeof native().scanLocalMusic === 'function'; }
  function hasCustomFolderBridge() {
    return !!native()
      && typeof native().chooseCustomMusicFolder === 'function'
      && typeof native().scanCustomMusicFolders === 'function';
  }

  function normalize(song, source = 'system') {
    const localUrl = song?.localUrl || song?._localUrl || '';
    if (!song || !localUrl || !song.id) return null;
    const mediaId = String(song.mediaId || song.id);
    const nativeUri = song.nativeUri || (source === 'system' && /^\d+$/.test(mediaId) ? `media:${mediaId}` : '');
    return {
      id: String(song.id), mediaId, name: song.name || '未知歌曲',
      artists: song.artists || '未知歌手', album: song.album || '',
      duration: Number(song.duration) || 0, fileSize: Number(song.fileSize) || 0,
      folder: song.folder || (source === 'custom' ? '自定义文件夹' : '本地音乐'),
      fileName: song.fileName || '', picUrl: song.picUrl || '', nativeUri: String(nativeUri), _localUrl: localUrl,
      _nativeLocal: true, _localSource: source,
    };
  }

  function getDirectoryLabel() {
    try {
      if (native() && typeof native().getLocalMusicDirectory === 'function') return native().getLocalMusicDirectory();
    } catch {}
    return '系统音乐库';
  }

  function getCustomFolders() {
    if (!hasCustomFolderBridge() || typeof native().getCustomMusicFolders !== 'function') return [];
    const payload = parseNative(native().getCustomMusicFolders(), { folders: [] });
    return Array.isArray(payload.folders) ? payload.folders.filter(x => x && x.uri) : [];
  }

  function chooseCustomFolder() {
    if (!hasCustomFolderBridge()) return false;
    try { native().chooseCustomMusicFolder(); return true; } catch { return false; }
  }

  function removeCustomFolder(uri) {
    if (!hasCustomFolderBridge() || !uri) return false;
    try { return !!native().removeCustomMusicFolder(String(uri)); } catch { return false; }
  }

  async function getCachedScan() {
    try {
      const cached = await DL.cacheGet(SCAN_CACHE_KEY, { allowStale: true });
      if (!cached || cached.version !== SCAN_CACHE_VERSION || !Array.isArray(cached.songs)) return null;
      const songs = cached.songs.map(song => normalize(song, song?._localSource || 'system')).filter(Boolean);
      return { ...cached, songs, cached: true };
    } catch { return null; }
  }
  async function saveScan(result) {
    const record = {
      version: SCAN_CACHE_VERSION, state: result.state || 'ready', systemState: result.systemState || 'ready',
      customState: result.customState || 'ready', message: result.message || '', directory: result.directory || '',
      songs: result.songs || [], folders: result.folders || [], truncated: !!result.truncated, scannedAt: Date.now(),
    };
    try { await DL.cachePut(SCAN_CACHE_KEY, record, Infinity); } catch {}
    return record;
  }
  async function clearCachedScan() { try { await DL.cacheDel(SCAN_CACHE_KEY); } catch {} }
  async function scan({ force = false } = {}) {
    const previous = force ? await getCachedScan() : null;
    if (!force) {
      const cached = await getCachedScan();
      if (cached) return cached;
    }
    if (!hasNativeScanner()) {
      return previous || { state: 'unsupported', message: '当前版本未启用系统音乐扫描，请安装新版 APK', songs: [], folders: [] };
    }
    const system = parseNative(native().scanLocalMusic(), { state: 'failed', songs: [] });
    const customBridge = hasCustomFolderBridge();
    let custom = { state: 'ready', songs: [] };
    if (customBridge) custom = parseNative(native().scanCustomMusicFolders(), { state: 'failed', songs: [] });
    const seen = new Set(), songs = [];
    (Array.isArray(system.songs) ? system.songs : []).map(s => normalize(s, 'system')).filter(Boolean).forEach(song => {
      if (!seen.has(song.id)) { seen.add(song.id); songs.push(song); }
    });
    (Array.isArray(custom.songs) ? custom.songs : []).map(s => normalize(s, 'custom')).filter(Boolean).forEach(song => {
      if (!seen.has(song.id)) { seen.add(song.id); songs.push(song); }
    });
    songs.sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-CN'));
    const permissionRequired = system.state === 'permission_required';
    const hasSuccessfulSource = system.state === 'ready' || (customBridge && custom.state === 'ready' && songs.length > 0);
    const failed = !hasSuccessfulSource && (system.state === 'failed' || (customBridge && custom.state === 'failed'));
    const result = {
      state: failed ? 'failed' : (permissionRequired ? 'permission_required' : 'ready'),
      systemState: system.state || 'ready', customState: custom.state || 'ready',
      message: system.message || custom.message || '', directory: system.directory || getDirectoryLabel(),
      songs, folders: getCustomFolders(), truncated: !!custom.truncated,
    };
    // Never replace or hide a valid library cache after an incomplete permission or bridge failure.
    if (result.state === 'ready') { await saveScan(result); return result; }
    return previous ? { ...previous, stale: true, message: result.message || '本次扫描未完成，正在显示上次结果' } : result;
  }

  function groupByFolder(songs) {
    const groups = new Map();
    (songs || []).forEach(song => {
      const key = `${song._localSource === 'custom' ? '自定义 · ' : ''}${song.folder || '本地音乐'}`;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(song);
    });
    return [...groups.entries()].map(([name, items]) => ({ name, count: items.length }));
  }

  function normalized(value) {
    return String(value || '').toLowerCase()
      .replace(/[（(\[][^）)\]]*[）)\]]/g, ' ')
      .replace(/\b(feat|ft|remix|live|version|伴奏|翻唱)\b/gi, ' ')
      .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  }
  function tokens(value) { return new Set(normalized(value).split(/\s+/).filter(x => x && x.length > 1)); }
  function overlap(a, b) { let count = 0; a.forEach(x => { if (b.has(x)) count++; }); return count; }
  function createMatchIndex(songs) {
    const byName = new Map();
    (songs || []).forEach(song => {
      const key = normalized(song.name); if (!key) return;
      if (!byName.has(key)) byName.set(key, []);
      byName.get(key).push(song);
    });
    return { byName, all: songs || [] };
  }
  function matchRemoteSong(remote, index) {
    if (!remote || !index) return null;
    const title = normalized(remote.name); if (!title) return null;
    // Exact-title hits cover the usual case. For uncommon metadata variations,
    // only compare title-compatible files instead of walking every local song.
    const exact = index.byName?.get(title) || [];
    const candidates = exact.length ? exact : (index.all || []).filter(song => {
      const localTitle = normalized(song.name);
      return localTitle && (localTitle.includes(title) || title.includes(localTitle));
    });
    let best = null, bestScore = 0;
    const remoteArtist = tokens(remote.artists), remoteAlbum = normalized(remote.album), remoteDuration = Number(remote.duration) || 0;
    candidates.forEach(local => {
      const localTitle = normalized(local.name), localArtist = tokens(local.artists), localAlbum = normalized(local.album);
      let score = localTitle === title ? 62 : (localTitle.includes(title) || title.includes(localTitle) ? 38 : 0);
      if (!score) return;
      const artistOverlap = overlap(remoteArtist, localArtist);
      if (artistOverlap) score += Math.min(24, 12 + artistOverlap * 6);
      else if (remoteArtist.size) score -= 16;
      if (remoteAlbum && localAlbum && (remoteAlbum === localAlbum || remoteAlbum.includes(localAlbum) || localAlbum.includes(remoteAlbum))) score += 8;
      const localDuration = Number(local.duration) || 0;
      if (remoteDuration && localDuration) {
        const diff = Math.abs(remoteDuration - localDuration);
        if (diff <= 1800) score += 16;
        else if (diff <= 4500) score += 7;
        else if (diff > 12000) score -= 24;
      }
      if (score > bestScore) { bestScore = score; best = local; }
    });
    return best && bestScore >= 66 ? { song: best, score: bestScore } : null;
  }
  function preferLocal(remoteSongs, index) {
    return (remoteSongs || []).map(song => {
      const match = matchRemoteSong(song, index);
      return match ? { ...song, _localUrl: match.song._localUrl, _nativeLocal: true, nativeUri: match.song.nativeUri || '', _localMatch: true, _localMatchScore: match.score, _localMediaId: match.song.mediaId || match.song.id, _localFolder: match.song.folder || '' } : song;
    });
  }

  return { hasNativeScanner, hasCustomFolderBridge, scan, getCachedScan, clearCachedScan, getDirectoryLabel, getCustomFolders, chooseCustomFolder, removeCustomFolder, groupByFolder, createMatchIndex, matchRemoteSong, preferLocal };
})();
window.Local = Local;
