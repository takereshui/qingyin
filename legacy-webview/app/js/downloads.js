const DL = (() => {
  const DB = 'molan-downloads';
  const VERSION = 8;
  const STORE = 'songs';
  const LOCAL_STORE = 'localSongs';
  const LYRIC_STORE = 'lyrics';
  const CACHE_STORE = 'datacache';
  const HANDLE_STORE = 'handles';
  const SCAN_STORE = 'scandirs';

  function openDB() {
    return new Promise((resolve, reject) => {
      const r = indexedDB.open(DB, VERSION);
      r.onupgradeneeded = (e) => {
        const db = e.target.result;
        if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: 'id' });
        if (!db.objectStoreNames.contains(LOCAL_STORE)) db.createObjectStore(LOCAL_STORE, { keyPath: 'id' });
        if (!db.objectStoreNames.contains(LYRIC_STORE)) db.createObjectStore(LYRIC_STORE, { keyPath: 'id' });
        if (!db.objectStoreNames.contains(CACHE_STORE)) db.createObjectStore(CACHE_STORE, { keyPath: 'key' });
        if (!db.objectStoreNames.contains(HANDLE_STORE)) db.createObjectStore(HANDLE_STORE, { keyPath: 'key' });
        if (!db.objectStoreNames.contains(SCAN_STORE)) db.createObjectStore(SCAN_STORE, { keyPath: 'id' });
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  }

  function hasNativeBridge() {
    return typeof window !== 'undefined'
      && !!window.NativeBridge
      && typeof window.NativeBridge.downloadUrl === 'function'
      && typeof window.NativeBridge.getDownloadStatus === 'function';
  }

  function nativeJSON(method, ...args) {
    if (!hasNativeBridge() || typeof window.NativeBridge[method] !== 'function') {
      return { state: 'unsupported' };
    }
    try {
      const raw = window.NativeBridge[method](...args);
      return typeof raw === 'string' ? JSON.parse(raw) : (raw || { state: 'unknown' });
    } catch (error) {
      return { state: 'failed', message: error && error.message ? error.message : '原生服务调用失败' };
    }
  }

  function safeFileName(song) {
    const raw = String((song && song.name) || ('song-' + ((song && song.id) || Date.now())));
    const cleaned = raw.replace(/[\\/:*?"<>|\u0000-\u001F]/g, '_').trim().slice(0, 80) || 'song';
    return /\.mp3$/i.test(cleaned) ? cleaned : cleaned + '.mp3';
  }

  function formatBytes(size) {
    const bytes = Number(size) || 0;
    if (!bytes) return '大小未知';
    if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024)) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  }

  // ---- File System Access API (desktop browser only) ----
  async function pickDlDir() {
    if (!window.showDirectoryPicker) return { handle: null, name: '', unsupported: true };
    try {
      const handle = await window.showDirectoryPicker({ mode: 'readwrite' });
      await saveDlHandle(handle);
      return { handle, name: handle.name };
    } catch (e) {
      if (e && e.name === 'AbortError') return { handle: null, name: '', cancelled: true };
      return { handle: null, name: '', error: e.message };
    }
  }

  async function saveDlHandle(handle) {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction(HANDLE_STORE, 'readwrite');
      t.objectStore(HANDLE_STORE).put({ key: 'dir', handle, name: handle.name, ts: Date.now() });
      t.oncomplete = () => { db.close(); resolve(); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  async function getDlHandle() {
    const db = await openDB();
    return new Promise(resolve => {
      const t = db.transaction(HANDLE_STORE, 'readonly');
      t.objectStore(HANDLE_STORE).get('dir').onsuccess = e => {
        const v = e.target.result;
        db.close();
        resolve(v ? { handle: v.handle, name: v.name } : null);
      };
      t.onerror = () => { db.close(); resolve(null); };
    });
  }

  async function clearDlHandle() {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction(HANDLE_STORE, 'readwrite');
      t.objectStore(HANDLE_STORE).delete('dir');
      t.oncomplete = () => { db.close(); resolve(); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  async function writeToFs(song, blob, dirHandle) {
    const fname = safeFileName(song);
    try {
      const fh = await dirHandle.getFileHandle(fname, { create: true });
      const w = await fh.createWritable();
      await w.write(blob);
      await w.close();
      return fname;
    } catch (e) { return null; }
  }

  // ---- Download records ----
  async function get(id) {
    const db = await openDB();
    return new Promise(resolve => {
      const t = db.transaction(STORE, 'readonly');
      t.objectStore(STORE).get(String(id)).onsuccess = e => { db.close(); resolve(e.target.result || null); };
      t.onerror = () => { db.close(); resolve(null); };
    });
  }

  async function getAll() {
    const db = await openDB();
    return new Promise(resolve => {
      const t = db.transaction(STORE, 'readonly');
      t.objectStore(STORE).getAll().onsuccess = e => { db.close(); resolve(e.target.result || []); };
      t.onerror = () => { db.close(); resolve([]); };
    });
  }

  async function put(song) {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction(STORE, 'readwrite');
      t.objectStore(STORE).put(song);
      t.oncomplete = () => { db.close(); resolve(); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  async function updateNativeEntry(entry) {
    if (!entry || !entry.systemDownload || !entry.downloadId || !hasNativeBridge()) return entry;
    const status = nativeJSON('getDownloadStatus', String(entry.downloadId));
    const changed = Object.assign({}, entry, {
      downloadState: status.state || 'unknown',
      bytesDownloaded: Number(status.bytes) || 0,
      totalBytes: Number(status.total) || 0,
      downloadReason: Number(status.reason) || 0,
      playbackUrl: status.playbackUrl || '',
      checkedAt: Date.now(),
    });
    if (changed.downloadState === 'completed' && !changed.completedAt) changed.completedAt = Date.now();
    if (changed.downloadState === 'failed') changed.error = status.message || '系统下载失败';
    await put(changed);
    return changed;
  }

  async function refreshSystemDownloads() {
    const list = await getAll();
    if (!hasNativeBridge()) return list;
    const refreshed = [];
    for (const entry of list) {
      if (entry && entry.systemDownload && entry.downloadId) {
        try { refreshed.push(await updateNativeEntry(entry)); } catch { refreshed.push(entry); }
      } else {
        refreshed.push(entry);
      }
    }
    return refreshed;
  }

  async function remove(id) {
    const entry = await get(String(id));
    if (entry && entry.systemDownload && entry.downloadId && hasNativeBridge()) {
      try { window.NativeBridge.removeDownload(String(entry.downloadId)); } catch {}
    }
    if (entry && entry.fsPath && !entry.systemDownload) {
      const dh = await getDlHandle();
      if (dh && dh.handle) {
        try { await dh.handle.removeEntry(entry.fsPath.split('/').pop()); } catch {}
      }
    }
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction(STORE, 'readwrite');
      t.objectStore(STORE).delete(String(id));
      t.oncomplete = () => { db.close(); resolve(); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  async function removeMany(ids) {
    for (const id of ids) {
      try { await remove(id); } catch {}
    }
  }

  async function isDownloaded(id) {
    const entry = await get(id);
    if (!entry) return false;
    if (!entry.systemDownload) return true;
    const fresh = await updateNativeEntry(entry);
    return fresh.downloadState === 'completed';
  }

  async function totalSize() {
    const all = await getAll();
    return all.reduce((sum, entry) => sum + (entry.blobSize || entry.totalBytes || 0), 0);
  }

  async function resolveSongUrl(song, quality = Store.getQuality()) {
    if (song && song._url) return song._url;
    const id = String(song && song.id);
    const isQQ = song?.source === 'qq' || !!song?.qqMid || id.startsWith('qq:');
    let url = '';
    if (isQQ) {
      if (!Store.getApiKey() && !Store.isBackup()) throw new Error('请先配置 ChKSz API Key 以下载 QQ 音乐');
      const info = await API.qqMusic(song.qqMid || id.replace(/^qq:/, ''), quality);
      if (info?.picUrl && !song.picUrl) song.picUrl = info.picUrl;
      if (info?.lrc) song._qqLrc = info.lrc;
      song._playbackQuality = `QQ · ${info?.bitrate || Store.getQualityLabel(quality)}`;
      url = info?.url || '';
    } else {
      if (Store.getApiKey() || Store.isBackup()) {
        try { const info = await API.music(id, quality); url = info && info.url; } catch {}
      }
      if (!url) {
        try { url = await NCM.musicUrl(id, quality); } catch {}
      }
    }
    return url || '';
  }

  async function downloadSong(song, onProgress) {
    const id = String(song.id);
    const existing = await get(id);
    if (existing) {
      if (existing.systemDownload) return updateNativeEntry(existing);
      return existing;
    }

    const quality = Store.getQuality();
    const url = await resolveSongUrl(song, quality);
    if (!url) throw new Error('无播放地址');

    // goapk: let Android DownloadManager fetch the CDN URL itself. This avoids CORS
    // and the file is saved in the public Music/Molan Light Music directory.
    if (hasNativeBridge()) {
      const started = nativeJSON('downloadUrl', url, safeFileName(song));
      if (started.state === 'permission_required') throw new Error(started.message || '请允许存储权限后重试');
      if (started.state !== 'queued' || !started.downloadId) throw new Error(started.message || '系统下载任务创建失败');
      const entry = {
        id, name: song.name, artists: song.artists, album: song.album, picUrl: song.picUrl,
        systemDownload: true, downloadId: String(started.downloadId),
        downloadState: 'queued', bytesDownloaded: 0, totalBytes: 0,
        fsPath: started.directory || 'Music/Molan Light Music',
        fileName: started.fileName || safeFileName(song), quality, qualityLabel: Store.getQualityLabel(quality), downloadedAt: Date.now(),
      };
      await put(entry);
      if (onProgress) onProgress(0);
      return entry;
    }

    // Browser fallback: only works with a CORS-enabled audio source.
    let res;
    try { res = await fetch(url); } catch { throw new Error('浏览器无法读取音频数据（CDN 未授权跨域）'); }
    if (!res.ok || !res.body) throw new Error('下载失败');
    const contentLength = parseInt(res.headers.get('content-length') || '0', 10);
    const reader = res.body.getReader();
    const chunks = [];
    let received = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      received += value.length;
      if (onProgress && contentLength) onProgress(received / contentLength);
    }
    const blob = new Blob(chunks, { type: res.headers.get('content-type') || 'audio/mpeg' });
    let fsPath = '';
    const dh = await getDlHandle();
    if (dh && dh.handle) {
      const fname = await writeToFs(song, blob, dh.handle);
      if (fname) fsPath = dh.name + '/' + fname;
    }
    const entry = {
      id, name: song.name, artists: song.artists, album: song.album, picUrl: song.picUrl,
      blob, blobSize: blob.size, downloadedAt: Date.now(), fsPath,
      downloadState: 'completed', quality, qualityLabel: Store.getQualityLabel(quality),
    };
    await put(entry);
    if (onProgress) onProgress(1);
    return entry;
  }

  async function batchDownload(songs, onProgress) {
    const results = [];
    const total = songs.length || 1;
    let completed = 0;
    for (const song of songs) {
      try {
        const entry = await downloadSong(song, p => {
          if (onProgress) onProgress((completed + p) / total);
        });
        results.push(entry);
      } catch (e) {
        results.push({ id: song.id, error: e.message || '下载失败' });
      }
      completed++;
      if (onProgress) onProgress(completed / total);
    }
    return results;
  }

  async function playDownloaded(id) {
    let entry = await get(id);
    if (!entry) return null;
    if (entry.systemDownload) {
      entry = await updateNativeEntry(entry);
      return entry.downloadState === 'completed' ? entry.playbackUrl : null;
    }
    if (entry.fsPath) {
      const dh = await getDlHandle();
      if (dh && dh.handle) {
        try {
          const fh = await dh.handle.getFileHandle(entry.fsPath.split('/').pop());
          return URL.createObjectURL(await fh.getFile());
        } catch {}
      }
    }
    return entry.blob ? URL.createObjectURL(entry.blob) : null;
  }

  // ---- Persistent local music library ----
  async function putLocal(song) {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction(LOCAL_STORE, 'readwrite');
      t.objectStore(LOCAL_STORE).put(song);
      t.oncomplete = () => { db.close(); resolve(song); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  async function getAllLocal() {
    const db = await openDB();
    return new Promise(resolve => {
      const t = db.transaction(LOCAL_STORE, 'readonly');
      t.objectStore(LOCAL_STORE).getAll().onsuccess = e => { db.close(); resolve(e.target.result || []); };
      t.onerror = () => { db.close(); resolve([]); };
    });
  }

  async function removeLocal(id) {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction(LOCAL_STORE, 'readwrite');
      t.objectStore(LOCAL_STORE).delete(String(id));
      t.oncomplete = () => { db.close(); resolve(); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  // ---- Lyrics and persistent data cache ----
  const LYRIC_VERSION = 2;
  const LYRIC_TTL = 90 * 24 * 60 * 60 * 1000;
  const LYRIC_MISS_TTL = 7 * 24 * 60 * 60 * 1000;
  const COVER_TTL = 14 * 24 * 60 * 60 * 1000;
  const COVER_MAX_BYTES = 3 * 1024 * 1024;
  const coverObjectUrls = new Map();

  async function getLyric(id) {
    const db = await openDB();
    return new Promise(resolve => {
      const t = db.transaction(LYRIC_STORE, 'readonly');
      t.objectStore(LYRIC_STORE).get(String(id)).onsuccess = e => {
        const item = e.target.result || null; db.close();
        if (!item || item.version !== LYRIC_VERSION || (item.exp && Date.now() >= item.exp)) return resolve(null);
        resolve(item);
      };
      t.onerror = () => { db.close(); resolve(null); };
    });
  }

  async function putLyric(id, data, ttl = null) {
    const missing = !data?.lrc;
    const duration = ttl == null ? (missing ? LYRIC_MISS_TTL : LYRIC_TTL) : ttl;
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction(LYRIC_STORE, 'readwrite');
      t.objectStore(LYRIC_STORE).put({
        id: String(id), lrc: data?.lrc || '', tlyric: data?.tlyric || '', missing,
        version: LYRIC_VERSION, ts: Date.now(), exp: Date.now() + duration,
      });
      t.oncomplete = () => { db.close(); resolve(); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  async function cacheGet(key, { allowStale = false } = {}) {
    const db = await openDB();
    return new Promise(resolve => {
      const t = db.transaction(CACHE_STORE, 'readonly');
      t.objectStore(CACHE_STORE).get(key).onsuccess = e => {
        const v = e.target.result;
        db.close();
        const fresh = !!(v && v.exp && Date.now() < v.exp);
        resolve(v && (fresh || allowStale) ? v.data : null);
      };
      t.onerror = () => { db.close(); resolve(null); };
    });
  }

  async function cachePut(key, data, ttl = 600000) {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction(CACHE_STORE, 'readwrite');
      t.objectStore(CACHE_STORE).put({ key, data, exp: ttl === Infinity ? Number.MAX_SAFE_INTEGER : Date.now() + ttl });
      t.oncomplete = () => { db.close(); resolve(); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  async function cacheDel(key) {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction(CACHE_STORE, 'readwrite');
      t.objectStore(CACHE_STORE).delete(key);
      t.oncomplete = () => { db.close(); resolve(); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  function coverUrl(url, size = 280) {
    if (!url || /^data:/i.test(url)) return url || '';
    const target = Math.max(80, Math.min(720, Number(size) || 280));
    try {
      const normalized = String(url).replace(/^http:\/\//i, 'https://');
      const parsed = new URL(normalized);
      if (/music\.126\.net|netease/i.test(parsed.hostname) || parsed.searchParams.has('param')) {
        parsed.searchParams.set('param', `${target}y${target}`);
      }
      return parsed.toString();
    } catch { return String(url); }
  }

  async function imageCache(url, size = 280) {
    const source = coverUrl(url, size);
    if (!source) return source;
    const key = `cover:v2:${source}`;
    const inMemory = coverObjectUrls.get(key);
    if (inMemory) return inMemory;
    try {
      const cached = await cacheGet(key);
      if (cached?.blob instanceof Blob) {
        const objectUrl = URL.createObjectURL(cached.blob);
        coverObjectUrls.set(key, objectUrl);
        return objectUrl;
      }
      const res = await fetch(source, { referrerPolicy: 'no-referrer' });
      if (!res.ok) return source;
      const blob = await res.blob();
      if (!blob.size || blob.size > COVER_MAX_BYTES) return source;
      const objectUrl = URL.createObjectURL(blob);
      coverObjectUrls.set(key, objectUrl);
      await cachePut(key, { blob, mime: blob.type, size: blob.size, source }, COVER_TTL);
      return objectUrl;
    } catch { return source; }
  }

  async function cacheClearAll() {
    const db = await openDB();
    return new Promise((resolve, reject) => {
      const t = db.transaction([CACHE_STORE, HANDLE_STORE], 'readwrite');
      t.objectStore(CACHE_STORE).clear();
      t.objectStore(HANDLE_STORE).clear();
      t.oncomplete = () => { db.close(); resolve(); };
      t.onerror = () => { db.close(); reject(t.error); };
    });
  }

  return {
    get, getAll, put, remove, removeMany, isDownloaded, totalSize,
    downloadSong, batchDownload, playDownloaded, refreshSystemDownloads,
    getLyric, putLyric, cacheGet, cachePut, cacheDel, cacheClearAll, imageCache, coverUrl,
    putLocal, getAllLocal, removeLocal, formatBytes,
    pickDlDir, getDlHandle, clearDlHandle, db: openDB,
    hasNativeBridge, getNativeDownloadDirectory: () => hasNativeBridge() && window.NativeBridge.getDownloadDirectory ? window.NativeBridge.getDownloadDirectory() : '',
  };
})();
window.DL = DL;
