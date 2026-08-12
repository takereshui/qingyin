/* NCM = NeteaseCloudMusicApi (login/daily/user) */
/* API = ChKSz API (music/search/playlist/lyric) */
const CACHE = 'molan-audio-cache';

function apiBase() {
  return Store.isBackup() ? 'https://api.chksz.top' : 'https://api.chksz.com';
}

function httpsFix(u) {
  if (!u) return '';
  return u.replace(/^http:\/\//i, 'https://');
}

// Netease CDN supports ?param=WxH to shrink image server-side.
// Cards use 300, list rows use 100. Avoid huge originals.
function coverUrl(u, size = 300) {
  if (!u) return '';
  u = httpsFix(u);
  if (u.includes('?param=')) return u;
  return u + '?param=' + size + 'y' + size;
}

const NCM = (() => {
  async function req(path, params = {}, acceptedCodes = []) {
    const base = Store.getNcmcUrl();
    const cookie = Store.getCookie();
    const q = new URLSearchParams(params);
    if (cookie) q.set('cookie', cookie);
    q.set('timestamp', Date.now());
    const url = `${base}${path}?${q}`;
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 15000);
    try {
      const res = await fetch(url, { signal: ctrl.signal });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const j = await res.json();
      if (j.code && j.code !== 200 && j.code !== 803 && !acceptedCodes.includes(j.code)) throw new Error(j.msg || `API ${j.code}`);
      if (j.code === 301) throw new Error('需要登录');
      if (j.code === 803 && j.cookie) {
        const c = j.cookie.replace(/;\s*Max-Age=\d+/g, '').replace(/;\s*Expires=[^;]+/g, '');
        Store.setCookie(c);
      }
      return j;
    } catch (e) {
      if (e.name === 'AbortError') throw new Error('请求超时');
      throw e;
    } finally { clearTimeout(t); }
  }

  function normSongList(arr) {
    if (!Array.isArray(arr)) return [];
    return arr.map(s => {
      if (!s || !s.id) return null;
      const ar = s.ar || [];
      return {
        id: String(s.id), name: s.name || '',
        artists: ar.map(a=>a.name).filter(Boolean).join(' / ') || s.artists || '',
        album: s.al?.name || s.album || '',
        picUrl: coverUrl(s.al?.picUrl || s.picUrl || '', 200),
        duration: Number(s.dt || s.duration || s.durationms || 0),
      };
    }).filter(Boolean);
  }

  return {
    async qrKey() { const j = await req('/login/qr/key'); return j.data?.unikey || j.unikey; },
    async qrCreate(key) { const j = await req('/login/qr/create', { key, qrimg: 'true' }); return { qrurl: j.data?.qrurl || j.qrurl, qrimg: j.data?.qrimg || j.qrimg }; },
    async qrCheck(key) { const j = await req('/login/qr/check', { key }, [800, 801, 802, 803]); return { code: j.code, cookie: j.cookie || '' }; },
    async userAccount() { const j = await req('/user/account'); return j.profile || j.data?.profile || null; },
    async userPlaylist(uid, limit = 50, { stale = false } = {}) {
      const ck = `upl:v2:${uid}:${limit}`;
      const cached = await DL.cacheGet(ck, { allowStale: stale }); if (cached) return cached;
      const j = await req('/user/playlist', { uid: String(uid), limit });
      const r = Array.isArray(j.playlist) ? j.playlist : j.data?.playlist || [];
      DL.cachePut(ck, r, 6 * 60 * 60 * 1000); return r;
    },
    async refreshUserPlaylist(uid, limit = 50) {
      const ck = `upl:v2:${uid}:${limit}`;
      const j = await req('/user/playlist', { uid: String(uid), limit });
      const r = Array.isArray(j.playlist) ? j.playlist : j.data?.playlist || [];
      DL.cachePut(ck, r, 6 * 60 * 60 * 1000); return r;
    },
    async dailySongs() {
      const cached = await DL.cacheGet('daily'); if (cached) return cached;
      const j = await req('/recommend/songs');
      const r = normSongList(j.data?.dailySongs || j.dailySongs || []);
      DL.cachePut('daily', r, 600000); return r;
    },
    async search(keyword, limit = 30, offset = 0) {
      const ck = `srch:${keyword}:${limit}:${offset}`;
      const cached = await DL.cacheGet(ck); if (cached) return cached;
      const j = await req('/cloudsearch', { keywords: keyword, limit, offset });
      const r = normSongList(j.data?.songs || j.songs || []);
      DL.cachePut(ck, r, 300000); return r;
    },
    async playlist(id, { stale = false } = {}) {
      const ck = `pl:v2:${id}`;
      const cached = await DL.cacheGet(ck, { allowStale: stale }); if (cached) return cached;
      const j = await req('/playlist/detail', { id: String(id) });
      const d = j.data || j.playlist || {};
      const r = { id: String(d.id || id), name: d.name || '', coverImgUrl: coverUrl(d.coverImgUrl || '', 300), trackCount: d.trackCount || 0, creator: d.creator?.nickname || '', tracks: normSongList(d.tracks || []) };
      DL.cachePut(ck, r, 12 * 60 * 60 * 1000); return r;
    },
    async refreshPlaylist(id) {
      const ck = `pl:v2:${id}`;
      const j = await req('/playlist/detail', { id: String(id) });
      const d = j.data || j.playlist || {};
      const r = { id: String(d.id || id), name: d.name || '', coverImgUrl: coverUrl(d.coverImgUrl || '', 300), trackCount: d.trackCount || 0, creator: d.creator?.nickname || '', tracks: normSongList(d.tracks || []) };
      DL.cachePut(ck, r, 12 * 60 * 60 * 1000); return r;
    },
    async musicUrl(id, level) { const j = await req('/song/url/v1', { id: String(id), level: level || Store.getQuality() }); const d = (j.data || [])[0] || j; return d.url || ''; },
    async lyric(id) { const j = await req('/lyric', { id: String(id) }); const d = j.data || j.lrc || {}; return { lrc: d.lrc?.lyric || d.lyric || '', tlyric: d.tlyric?.lyric || '' }; },
    async personalized(limit = 12) { const j = await req('/personalized', { limit }); return (j.result || j.data?.result || []).map(p => ({ id: String(p.id), name: p.name || '', coverImgUrl: coverUrl(p.picUrl || p.coverImgUrl || '', 300), trackCount: p.trackCount || p.playCount || 0, creator: p.copywriter || '' })); },
    async recommendResources() { const j = await req('/recommend/resource'); return (j.recommend || j.data?.recommend || []).map(p => ({ id: String(p.id), name: p.name || '', coverImgUrl: coverUrl(p.picUrl || p.coverImgUrl || '', 300), trackCount: p.trackCount || 0, creator: p.copywriter || '' })); },
    async toplist() { const j = await req('/toplist/detail'); const lists = j.list || j.data?.list || []; return lists.map(p => ({ id: String(p.id), name: p.name || '', coverImgUrl: coverUrl(p.coverImgUrl || p.picUrl || '', 300), trackCount: p.trackCount || 0, creator: p.updateFrequency || '' })); },
    async topPlaylists(cat = '全部', limit = 20) { const j = await req('/top/playlist', { cat, limit, order: 'hot' }); return (j.playlists || j.data?.playlists || []).map(p => ({ id: String(p.id), name: p.name || '', coverImgUrl: coverUrl(p.coverImgUrl || '', 300), trackCount: p.trackCount || 0, creator: p.creator?.nickname || p.description || '' })); },
    async newAlbums(limit = 12) { const j = await req('/album/newest', { limit }); const list = j.albums || j.data?.albums || []; return list.slice(0, limit).map(a => ({ id: String(a.id), name: a.name || '', coverImgUrl: coverUrl(a.picUrl || a.blurPicUrl || '', 300), artist: (a.artists || []).map(x => x.name).join(' / '), publishTime: a.publishTime || 0 })); },
    async topArtists(limit = 12) { const j = await req('/top/artists', { limit }); const list = j.artists || j.data?.artists || []; return list.map(a => ({ id: String(a.id), name: a.name || '', picUrl: coverUrl(a.picUrl || '', 300), albumSize: a.albumSize || 0, musicSize: a.musicSize || 0 })); },
    async album(id) { const j = await req('/album', { id: String(id) }); const a = j.album || j.data?.album || {}; return { id: String(a.id || id), name: a.name || '', coverImgUrl: coverUrl(a.picUrl || '', 500), artist: (a.artists || []).map(x => x.name).join(' / '), description: a.description || '', songs: normSongList(j.songs || j.data?.songs || []) }; },
    async artist(id) { const j = await req('/artist/detail', { id: String(id) }); const d = j.data || j; const a = d.artist || d.data?.artist || {}; return { id: String(a.id || id), name: a.name || '', picUrl: coverUrl(a.cover || a.picUrl || '', 500), briefDesc: a.briefDesc || '', musicSize: a.musicSize || 0, albumSize: a.albumSize || 0 }; },
    async artistTopSongs(id) { const j = await req('/artist/top/song', { id: String(id) }); return normSongList(j.songs || j.data?.songs || []); },
    async likeList(uid) { const j = await req('/likelist', { uid: String(uid) }); return (j.ids || j.data?.ids || []).map(String); },
    async like(id, like = true) { return req('/like', { id: String(id), like: like ? 'true' : 'false' }); },
    async userRecord(uid, type = 0) { const j = await req('/user/record', { uid: String(uid), type: String(type) }); return normSongList(j.allData || j.weekData || j.data?.allData || []); },
    async cacheSize() { try { const c = await caches.open(CACHE); const keys = await c.keys(); let t = 0; for (const k of keys) { const r = await c.match(k); if (r) t += (await r.clone().blob()).size; } return t; } catch { return 0; } },
    async cacheEvict(maxBytes) {
      try { const c = await caches.open(CACHE); const keys = await c.keys(); keys.sort((a,b)=>(parseInt(new URL(a.url).searchParams.get('_t')||'0',10)-parseInt(new URL(b.url).searchParams.get('_t')||'0',10))); let t=0,rem=0; for(const k of keys){const r=await c.match(k);if(r)t+=(await r.clone().blob()).size} for(const k of keys){if(t-rem<=maxBytes)break;const r=await c.match(k);if(r){const s=(await r.clone().blob()).size;await c.delete(k);rem+=s}} return {total:t,removed:rem}; } catch { return {total:0,removed:0}; }
    },
    async cacheClear() { try { await caches.delete(CACHE); } catch {} },
    async cacheAudio(url, id) {
      try { if (Store.getCacheSize() <= 0) return; const c = await caches.open(CACHE); const maxB = Store.getCacheSize() * 1024 * 1024; const keys = await c.keys(); let t = 0; for (const k of keys) { const r = await c.match(k); if (r) t += (await r.clone().blob()).size; } const res = await fetch(url + `&_t=${Date.now()}`); if (!res.ok) return; const clone = res.clone(); const blob = await clone.blob(); if (t + blob.size > maxB) await this.cacheEvict(maxB - blob.size); await c.put(url, res); } catch {}
    },
    async getCachedAudio(url) { try { const c = await caches.open(CACHE); return await c.match(url); } catch { return null; } },
  };
})();

const API = (() => {
  function key() { return Store.isBackup() ? '' : Store.getApiKey(); }
  async function req(path, params = {}) {
    const base = apiBase();
    const q = new URLSearchParams(params);
    const k = key();
    if (k) q.set('apikey', k);
    const url = `${base}${path}?${q}`;
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 20000);
    try {
      const res = await fetch(url, { signal: ctrl.signal });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const j = await res.json();
      if (j.code && j.code !== 200) throw new Error(j.msg || 'API 错误');
      return j;
    } catch (e) {
      if (e.name === 'AbortError') throw new Error('请求超时');
      throw e;
    } finally { clearTimeout(t); }
  }
  const QQ_QUALITY = { standard: '128k', higher: '128k', exhigh: '320k', lossless: 'flac', hires: 'hires' };
  function qqQuality(level) { return QQ_QUALITY[level] || '320k'; }
  function normSong(raw) {
    if (!raw || !raw.id) return null;
    let artists = '';
    if (Array.isArray(raw.ar)) artists = raw.ar.map(a=>a.name).filter(Boolean).join(' / ');
    else if (Array.isArray(raw.artists)) artists = raw.artists.map(a=>typeof a==='string'?a:a.name).join(' / ');
    else artists = raw.artists || raw.artist || '';
    return { id: String(raw.id), name: raw.name || '', artists, album: raw.album || raw.al?.name || '', picUrl: coverUrl(raw.picUrl || raw.al?.picUrl || raw.coverImgUrl || '', 200), duration: Number(raw.dt || raw.duration || raw.durationms || 0) };
  }
  return {
    async search(keyword, limit = 30, offset = 0) {
      const ck = `apich:${keyword}:${limit}:${offset}`;
      const cached = await DL.cacheGet(ck); if (cached) return cached;
      const j = await req('/api/163_search', { keyword, limit, offset });
      const list = Array.isArray(j.data) ? j.data : j.data?.songs || [];
      const r = list.map(normSong).filter(Boolean);
      DL.cachePut(ck, r, 300000); return r;
    },
    async playlist(id) {
      const ck = `apipl:${id}`;
      const cached = await DL.cacheGet(ck); if (cached) return cached;
      const j = await req('/api/163_playlist', { id: String(id) });
      const d = j.data || {}; const tracks = d.tracks || d.songs || [];
      const r = { id: String(d.id || id), name: d.name || '', coverImgUrl: coverUrl(d.coverImgUrl || '', 300), trackCount: d.trackCount || tracks.length, creator: d.creator?.nickname || '', tracks: tracks.map(normSong).filter(Boolean) };
      DL.cachePut(ck, r, 600000); return r;
    },
    async music(id, level) { const j = await req('/api/163_music', { id: String(id), level: level || Store.getQuality(), type: 'json' }); const d = j.data || {}; return { id: String(d.id || id), url: d.url || '', br: d.br, level: d.level, name: d.name, artist: d.artist, album: d.album, picUrl: d.picUrl }; },
    async lyric(id) { const j = await req('/api/163_lyric', { id: String(id) }); const d = j.data || {}; return { lrc: d.lrc || d.lyric || '', tlyric: d.tlyric || '' }; },
    async qqMusic(mid, level) {
      if (!mid) throw new Error('QQ 歌曲 MID 缺失');
      const j = await req('/api/qq_music', { mid: String(mid), size: qqQuality(level), type: 'json' });
      const d = j.data || j || {};
      return { mid: d.mid || mid, url: d.url || '', lrc: d.lrc || '', name: d.name || '', artist: d.singer || d.artist || '', album: d.album || '', picUrl: d.cover || d.picUrl || '', bitrate: d.bitrate || d.format || qqQuality(level) };
    },
    parsePlaylistId(input) { if (!input) return ''; const s = String(input).trim(); if (/^\d+$/.test(s)) return s; const m = s.match(/[?&]id=(\d+)/) || s.match(/playlist\/(\d+)/) || s.match(/(\d{6,})/); return m ? m[1] : ''; },
    parseLrc(text, translatedText = '') {
      const parseOne = source => {
        const result = { offset: 0, lines: [] };
        if (!source) return result;
        const rows = String(source).replace(/([^\]\n])\[/g, '$1\n[').split(/\r?\n/);
        rows.forEach(raw => {
          const offset = raw.match(/^\[offset:([+-]?\d+)\]/i);
          if (offset) { result.offset = Number(offset[1]) || 0; return; }
          // Drop standard metadata tags but keep timestamped lyric content.
          if (/^\[(ar|ti|al|by|re|ve|length|kana|language):/i.test(raw)) return;
          const tags = [];
          const tagRE = /(?:\[|<)(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?(?:\]|>)/g;
          let match;
          while ((match = tagRE.exec(raw)) !== null) {
            const fraction = match[3] ? Number(`0.${match[3]}`) : 0;
            const seconds = Number(match[1]) * 60 + Number(match[2]) + fraction;
            if (Number.isFinite(seconds)) tags.push(seconds);
          }
          const lyric = raw.replace(tagRE, '').replace(/^\s+|\s+$/g, '');
          if (!tags.length || !lyric) return;
          tags.forEach(time => result.lines.push({ time: Math.max(0, time + result.offset / 1000), text: lyric }));
        });
        const unique = new Map();
        result.lines.sort((a, b) => a.time - b.time).forEach(line => {
          const key = Math.round(line.time * 1000);
          const old = unique.get(key);
          if (!old || old.text.length < line.text.length) unique.set(key, line);
        });
        result.lines = [...unique.values()].sort((a, b) => a.time - b.time);
        return result;
      };
      const main = parseOne(text);
      const translated = parseOne(translatedText);
      if (!main.lines.length) return [];
      const translations = translated.lines;
      let pointer = 0;
      return main.lines.map(line => {
        while (pointer + 1 < translations.length && translations[pointer + 1].time <= line.time) pointer++;
        const current = translations[pointer];
        const next = translations[pointer + 1];
        const candidate = [current, next].filter(Boolean).sort((a, b) => Math.abs(a.time - line.time) - Math.abs(b.time - line.time))[0];
        const translation = candidate && Math.abs(candidate.time - line.time) <= 0.32 ? candidate.text : '';
        return { ...line, translation };
      });
    },
  };
})();
window.NCM = NCM;
window.API = API;