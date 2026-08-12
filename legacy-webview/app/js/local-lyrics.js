const LocalLyrics = (() => {
  const MATCH_TTL = 30 * 24 * 60 * 60 * 1000;
  const MISS_TTL = 7 * 24 * 60 * 60 * 1000;

  function normalize(value) {
    return String(value || '')
      .normalize('NFKC').toLowerCase()
      .replace(/\.(mp3|flac|wav|m4a|aac|ogg|opus|wma)$/i, '')
      .replace(/^\s*(?:\d{1,3}[._\-\s])+/i, '')
      .replace(/[（(\[].*?[）)\]]/g, ' ')
      .replace(/\b(feat\.?|ft\.?|with|official|lyrics?|audio|video|remaster(?:ed)?|live|demo|hq|flac|lossless|\d{2,4}k)\b/gi, ' ')
      .replace(/(伴奏|纯音乐|原唱|无损|高音质|现场版|完整版|翻唱)/g, ' ')
      .replace(/[·•、/\\|_~`'"“”‘’.,，。!?！？:：;；\-—–\s]+/g, '')
      .trim();
  }

  function editSimilarity(a, b) {
    a = normalize(a); b = normalize(b);
    if (!a || !b) return 0;
    if (a === b) return 1;
    if (a.includes(b) || b.includes(a)) return Math.min(a.length, b.length) / Math.max(a.length, b.length) * 0.92;
    const prev = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
      let diagonal = prev[0]; prev[0] = i;
      for (let j = 1; j <= b.length; j++) {
        const old = prev[j];
        prev[j] = Math.min(prev[j] + 1, prev[j - 1] + 1, diagonal + (a[i - 1] === b[j - 1] ? 0 : 1));
        diagonal = old;
      }
    }
    return 1 - prev[b.length] / Math.max(a.length, b.length);
  }

  function titleScore(local, remote) {
    const a = normalize(local), b = normalize(remote);
    if (!a || !b) return 0;
    if (a === b) return 1;
    const simple = editSimilarity(a, b);
    const localCore = a.replace(/(live|demo|remix|伴奏|instrumental|dj)/g, '');
    const remoteCore = b.replace(/(live|demo|remix|伴奏|instrumental|dj)/g, '');
    return Math.max(simple, editSimilarity(localCore, remoteCore));
  }

  function artistScore(local, remote) {
    const a = normalize(local), b = normalize(remote);
    if (!a || a === normalize('未知歌手') || !b) return 0.45;
    if (a === b || a.includes(b) || b.includes(a)) return 1;
    const partsA = String(local).split(/[\/、,，&]/).map(normalize).filter(Boolean);
    const partsB = String(remote).split(/[\/、,，&]/).map(normalize).filter(Boolean);
    if (partsA.some(x => partsB.some(y => x === y || x.includes(y) || y.includes(x)))) return 0.9;
    return editSimilarity(a, b);
  }

  function durationScore(localDuration, remoteDuration) {
    const a = Number(localDuration) || 0, b = Number(remoteDuration) || 0;
    if (!a || !b) return 0.5;
    const diff = Math.abs(a - b), ratio = diff / Math.max(a, b);
    if (diff <= 1500 || ratio <= 0.006) return 1;
    if (diff <= 4500 || ratio <= 0.018) return 0.82;
    if (diff <= 9000 || ratio <= 0.04) return 0.53;
    if (diff <= 15000 || ratio <= 0.07) return 0.2;
    return 0;
  }

  function score(local, candidate) {
    const title = titleScore(local.name || local.fileName, candidate.name);
    const artist = artistScore(local.artists, candidate.artists);
    const album = local.album ? editSimilarity(local.album, candidate.album) : 0.48;
    const duration = durationScore(local.duration, candidate.duration);
    const total = title * 0.57 + artist * 0.25 + album * 0.10 + duration * 0.08;
    return { total, title, artist, album, duration };
  }

  function cacheKey(song) {
    return 'local-lyric-match:' + [song.id, song.name, song.artists, song.album, song.duration].map(x => encodeURIComponent(String(x || ''))).join(':');
  }

  function usableSong(song) {
    const title = normalize(song?.name || song?.fileName);
    return !!title && title !== normalize('未知歌曲');
  }

  async function searchCandidates(song) {
    const title = String(song.name || song.fileName || '').replace(/\.[a-z0-9]{2,5}$/i, '').trim();
    const artist = String(song.artists || '').replace(/未知歌手/g, '').trim();
    const album = String(song.album || '').trim();
    const queries = [...new Set([artist && title ? `${artist} ${title}` : '', album && title ? `${title} ${album}` : '', title].filter(Boolean))];
    const all = new Map();
    for (const keyword of queries) {
      let songs = [];
      try { songs = await NCM.search(keyword, 12); } catch {}
      if (!songs.length && (Store.getApiKey() || Store.isBackup())) {
        try { songs = await API.search(keyword, 12); } catch {}
      }
      songs.forEach(item => all.set(String(item.id), item));
      if (all.size >= 20) break;
    }
    return [...all.values()];
  }

  async function fetchLyrics(id) {
    let lyric = null;
    try { lyric = await NCM.lyric(id); } catch {}
    if ((!lyric || !lyric.lrc) && (Store.getApiKey() || Store.isBackup())) {
      try { lyric = await API.lyric(id); } catch {}
    }
    return lyric && lyric.lrc ? lyric : null;
  }

  async function resolve(song, { force = false } = {}) {
    if (!usableSong(song)) return { state: 'skipped', message: '歌曲信息不足，无法匹配歌词' };
    const key = cacheKey(song);
    if (!force) {
      const cached = await DL.cacheGet(key);
      if (cached) {
        if (!cached.matched) return { state: 'miss', message: '未匹配到可信歌词' };
        const saved = await DL.getLyric('ncm-local:' + cached.id);
        if (saved?.lrc) return { state: 'matched', match: cached, lrc: saved.lrc, tlyric: saved.tlyric || '', cached: true };
      }
    }

    const candidates = await searchCandidates(song);
    if (!candidates.length) {
      await DL.cachePut(key, { matched: false }, MISS_TTL);
      return { state: 'miss', message: '没有找到歌词候选歌曲' };
    }
    const ranked = candidates.map(item => ({ item, metrics: score(song, item) })).sort((a, b) => b.metrics.total - a.metrics.total);
    const best = ranked[0], runnerUp = ranked[1];
    const hasArtist = normalize(song.artists) && normalize(song.artists) !== normalize('未知歌手');
    const threshold = hasArtist ? 0.76 : 0.85;
    const margin = runnerUp ? best.metrics.total - runnerUp.metrics.total : 1;
    const exactIdentity = best.metrics.title >= 0.96 && best.metrics.artist >= 0.82;
    if (!best || best.metrics.total < threshold || best.metrics.title < 0.74 || (!exactIdentity && margin < 0.055)) {
      await DL.cachePut(key, { matched: false, top: best?.metrics?.total || 0, margin: Number(margin.toFixed(3)) }, MISS_TTL);
      return { state: 'miss', message: '未找到足够匹配的歌词版本', candidates: ranked.slice(0, 3) };
    }
    const lyric = await fetchLyrics(best.item.id);
    if (!lyric) {
      await DL.cachePut(key, { matched: false, top: best.metrics.total }, MISS_TTL);
      return { state: 'miss', message: '已匹配歌曲，但该版本暂无歌词', match: best.item };
    }
    const match = {
      matched: true, id: String(best.item.id), name: best.item.name, artists: best.item.artists,
      album: best.item.album || '', score: Number(best.metrics.total.toFixed(3)), margin: Number(margin.toFixed(3)), ts: Date.now(),
    };
    await Promise.all([
      DL.putLyric('ncm-local:' + match.id, lyric),
      DL.cachePut(key, match, MATCH_TTL),
    ]);
    return { state: 'matched', match, lrc: lyric.lrc, tlyric: lyric.tlyric || '', cached: false };
  }

  return { resolve, score, normalize };
})();
window.LocalLyrics = LocalLyrics;
