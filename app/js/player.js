const Player = (() => {
  const audio = () => document.getElementById('audio');
  const MODES = ['loop', 'single', 'shuffle'];
  let queue = [], index = -1, lyrics = [], lyricIdx = -1, mode = 'loop', seeking = false, pendingSeekRatio = null;
  let lastLead = '', lyricRequestId = 0, lastNativeMediaSync = 0, lastNativeMediaKey = '', localSeekBackup = null;
  let nativeLocalActive = false, nativeLocalPlaying = false, nativeLocalDuration = 0, nativeLocalPosition = 0;
  let shuffleHistory = [], shuffleHistoryIndex = -1;
  const listeners = new Set();

  function emit(evt, data) { listeners.forEach(fn => { try { fn(evt, data); } catch {} }); }
  function emitQueue(reason = 'update') { emit('queue', { queue: queue.slice(), index, mode, reason }); }
  function emitLead(payload) {
    const key = payload.show ? `${payload.nextIdx}|${payload.instrumental ? 'i' : payload.dots}` : 'off';
    if (key === lastLead) return;
    lastLead = key;
    emit('leadIn', payload);
  }
  function on(fn) { listeners.add(fn); return () => listeners.delete(fn); }
  function current() { return index >= 0 && index < queue.length ? queue[index] : null; }
  function nativeBridge() { return typeof window !== 'undefined' ? window.NativeBridge : null; }
  function isSameSong(a, b) { return String(a?.id || '') === String(b?.id || ''); }

  function syncNativeMedia(force = false) {
    if (nativeLocalActive) return;
    const bridge = nativeBridge(), song = current(), a = audio();
    if (!bridge || typeof bridge.updateMediaSession !== 'function' || !song) return;
    const position = Math.max(0, Math.round((Number(a.currentTime) || 0) * 1000));
    const duration = Math.max(0, Math.round((Number(a.duration) || Number(song.duration) || 0) * 1000));
    const playing = !!a.src && !a.paused;
    const key = `${song.id}|${song.name || ''}|${song.artists || ''}|${playing}|${position}|${duration}`;
    const now = Date.now();
    if (!force && key === lastNativeMediaKey) return;
    if (!force && now - lastNativeMediaSync < 900 && key.split('|').slice(0, 4).join('|') === lastNativeMediaKey.split('|').slice(0, 4).join('|')) return;
    lastNativeMediaSync = now; lastNativeMediaKey = key;
    try { bridge.updateMediaSession(String(song.name || '轻音'), String(song.artists || ''), playing, position, duration); } catch {}
  }

  function stopNativeLocal() {
    const bridge = nativeBridge();
    if (nativeLocalActive && bridge?.stopLocalMusic) { try { bridge.stopLocalMusic(); } catch {} }
    nativeLocalActive = false; nativeLocalPlaying = false; nativeLocalDuration = 0; nativeLocalPosition = 0;
  }

  function randomIndex(exclude) {
    if (queue.length <= 1) return 0;
    const candidates = [];
    for (let i = 0; i < queue.length; i++) if (i !== exclude) candidates.push(i);
    return candidates[Math.floor(Math.random() * candidates.length)] ?? 0;
  }
  function resetShuffleHistory(keepCurrent = true) {
    shuffleHistory = keepCurrent && index >= 0 ? [index] : [];
    shuffleHistoryIndex = shuffleHistory.length - 1;
  }
  function recordShuffleIndex(target) {
    if (mode !== 'shuffle' || target < 0) return;
    if (shuffleHistory[shuffleHistoryIndex] === target) return;
    shuffleHistory = shuffleHistory.slice(0, shuffleHistoryIndex + 1);
    shuffleHistory.push(target);
    shuffleHistoryIndex = shuffleHistory.length - 1;
  }
  function shuffleNextIndex() {
    if (!queue.length) return -1;
    if (shuffleHistoryIndex >= 0 && shuffleHistoryIndex < shuffleHistory.length - 1) return shuffleHistory[++shuffleHistoryIndex];
    const target = randomIndex(index);
    shuffleHistory = shuffleHistory.slice(0, shuffleHistoryIndex + 1);
    shuffleHistory.push(target);
    shuffleHistoryIndex = shuffleHistory.length - 1;
    return target;
  }
  function shufflePrevIndex() {
    if (shuffleHistoryIndex > 0) return shuffleHistory[--shuffleHistoryIndex];
    return index;
  }

  function setQueue(list, startId) {
    const startKey = startId == null ? '' : String(startId);
    queue = (list || []).filter(Boolean).slice();
    if (!queue.length) {
      index = -1; resetShuffleHistory(false); emitQueue('set'); return;
    }
    const found = startKey ? queue.findIndex(song => String(song.id) === startKey) : 0;
    index = found >= 0 ? found : 0;
    resetShuffleHistory(true);
    emitQueue('set');
  }

  async function playAt(target, { fromShuffleHistory = false } = {}) {
    if (target < 0 || target >= queue.length) return;
    index = target;
    if (mode === 'shuffle' && !fromShuffleHistory) recordShuffleIndex(target);
    const song = queue[index];
    stopNativeLocal();
    emit('loading', song);
    emit('song', song);
    emitQueue('switch');
    syncNativeMedia(true);
    Store.pushHistory(song);
    const a = audio();
    try {
      if (song._nativeLocal) {
        const bridge = nativeBridge();
        if (!song.nativeUri || !bridge?.playLocalMusic) throw new Error('本地音乐信息已过期，请在本地页重新扫描一次');
        nativeLocalActive = true; nativeLocalPlaying = false; nativeLocalDuration = Number(song.duration) || 0;
        try { a.pause(); a.removeAttribute('src'); a.load(); } catch {}
        song._playbackQuality = '本地文件 · 原始音质';
        bridge.playLocalMusic(String(song.id), String(song.nativeUri), String(song.name || ''), String(song.artists || ''));
        void loadLyric(song);
        return;
      }
      if (song._localUrl || song._localFile) {
        if (!song._localUrl && song._localFile) song._localUrl = URL.createObjectURL(song._localFile);
        song._playbackQuality = '本地文件 · 原始音质';
        a.src = song._localUrl; a.load(); await a.play(); emit('play', song); void loadLyric(song); return;
      }
      const saved = await DL.get(song.id);
      if (saved && saved.blob) {
        song._playbackQuality = saved.qualityLabel || '已下载文件 · 原始音质';
        a.src = URL.createObjectURL(saved.blob); a.load(); await a.play(); emit('play', song); void loadLyric(song); return;
      }
      const quality = Store.getQuality();
      let url = '';
      const isQQ = song.source === 'qq' || !!song.qqMid || String(song.id || '').startsWith('qq:');
      if (isQQ) {
        if (!Store.getApiKey() && !Store.isBackup()) throw new Error('请先在设置中配置 ChKSz API Key 以播放 QQ 音乐');
        const info = await API.qqMusic(song.qqMid || String(song.id).replace(/^qq:/, ''), quality);
        url = info.url || '';
        song._qqLrc = info.lrc || song._qqLrc || '';
        if (info.picUrl) song.picUrl = info.picUrl;
        if (info.name && !song.name) song.name = info.name;
        if (info.artist && !song.artists) song.artists = info.artist;
        song._playbackQuality = `QQ · ${info.bitrate || Store.getQualityLabel(quality)}`;
      } else {
        if (Store.getApiKey() || Store.isBackup()) {
          try { const info = await API.music(song.id, quality); url = info.url || ''; if (info.picUrl && !song.picUrl) song.picUrl = info.picUrl; } catch {}
        }
        if (!url) { try { url = await NCM.musicUrl(song.id, quality) || ''; } catch {} }
      }
      if (!url) throw new Error('无播放地址');
      song._playbackQuality = song._playbackQuality || Store.getQualityLabel(quality);
      const cached = await NCM.getCachedAudio(url);
      a.src = cached ? URL.createObjectURL(await cached.blob()) : url;
      a.load(); await a.play();
      NCM.cacheAudio(url, song.id).catch(() => {});
      emit('play', song); void loadLyric(song);
    } catch (e) {
      emit('error', e.message || String(e));
      setTimeout(() => next(true, { forceAdvance: true }), 800);
    }
  }

  async function loadLyric(song, { force = false } = {}) {
    const requestId = ++lyricRequestId;
    lyrics = []; lyricIdx = -1; lastLead = ''; emit('lyric', []); emitLead({ show: false });
    if (!song) return;
    const isNativeLocal = !!song._nativeLocal || String(song.id || '').startsWith('local-');
    try {
      let l = null;
      if (song._localLrc) { l = { lrc: song._localLrc, tlyric: '' }; emit('lyricStatus', { state: 'embedded', message: '正在使用文件内嵌歌词' }); }
      if (!l && isNativeLocal) {
        emit('lyricStatus', { state: 'matching', message: '正在匹配本地音乐歌词…' });
        if (window.LocalLyrics) {
          const matched = await LocalLyrics.resolve(song, { force });
          if (requestId !== lyricRequestId) return;
          if (matched.state === 'matched') { l = { lrc: matched.lrc, tlyric: matched.tlyric || '' }; emit('lyricStatus', { state: 'matched', message: `已匹配 · ${matched.match.name} — ${matched.match.artists}`, match: matched.match, cached: matched.cached }); }
          else emit('lyricStatus', { state: matched.state || 'miss', message: matched.message || '未匹配到歌词' });
        } else emit('lyricStatus', { state: 'miss', message: '歌词匹配模块未加载' });
      }
      if (!l && !isNativeLocal) {
        const cached = !force ? await DL.getLyric(song.id) : null;
        if (cached) {
          if (cached.missing) { emit('lyricStatus', { state: 'miss', message: '暂无歌词（已缓存，稍后可手动刷新）' }); l = { lrc: '', tlyric: '' }; }
          else if (cached.lrc) { l = cached; emit('lyricStatus', { state: 'cached', message: '正在使用缓存歌词' }); }
        }
        if (!l) {
          l = { lrc: '', tlyric: '' };
          const isQQ = song.source === 'qq' || !!song.qqMid || String(song.id || '').startsWith('qq:');
          if (isQQ) {
            l.lrc = song._qqLrc || '';
            if (!l.lrc && (Store.getApiKey() || Store.isBackup())) { try { const r = await API.qqMusic(song.qqMid || String(song.id).replace(/^qq:/, ''), Store.getQuality()); l.lrc = r.lrc || ''; song._qqLrc = l.lrc; } catch {} }
          } else {
            if (Store.getApiKey() || Store.isBackup()) { try { const r = await API.lyric(song.id); l.lrc = r.lrc; l.tlyric = r.tlyric; } catch {} }
            if (!l.lrc) { try { const r = await NCM.lyric(song.id); l.lrc = r.lrc; l.tlyric = r.tlyric; } catch {} }
          }
          DL.putLyric(song.id, l).catch(() => {});
          emit('lyricStatus', { state: l.lrc ? 'matched' : 'miss', message: l.lrc ? '歌词已加载' : '暂无歌词' });
        }
      }
      if (requestId !== lyricRequestId) return;
      lyrics = API.parseLrc(l?.lrc || '', l?.tlyric || ''); emit('lyric', lyrics);
    } catch (e) {
      if (requestId === lyricRequestId) { emit('lyricStatus', { state: 'failed', message: '歌词加载失败' }); emit('lyric', []); }
    }
  }

  function refreshCurrentLyric() { const song = current(); if (song) return loadLyric(song, { force: true }); }
  function playSong(song, list) {
    if (list && list.length) setQueue(list, song.id);
    else {
      const i = queue.findIndex(item => isSameSong(item, song));
      if (i >= 0) index = i;
      else { queue = [song]; index = 0; resetShuffleHistory(true); emitQueue('set'); }
    }
    return playAt(index);
  }
  function toggle() {
    if (nativeLocalActive) { try { nativeLocalPlaying ? nativeBridge()?.pauseLocalMusic?.() : nativeBridge()?.resumeLocalMusic?.(); } catch {} return; }
    const a = audio();
    if (!a.src) { if (queue.length) return playAt(index >= 0 ? index : 0); return; }
    if (a.paused) a.play().then(() => emit('play', current())).catch(() => {});
    else { a.pause(); emit('pause', current()); }
  }
  function next(auto = false, { forceAdvance = false } = {}) {
    if (!queue.length) return;
    if (mode === 'single' && auto && !forceAdvance) return playAt(index);
    if (mode === 'shuffle') return playAt(shuffleNextIndex(), { fromShuffleHistory: true });
    return playAt((index + 1) % queue.length);
  }
  function prev() {
    if (!queue.length) return;
    if (nativeLocalActive && nativeLocalPosition > 3000) { try { nativeBridge()?.seekLocalMusic?.(0); } catch {} return; }
    if (!nativeLocalActive && (Number(audio().currentTime) || 0) > 3) { audio().currentTime = 0; return; }
    if (mode === 'shuffle') return playAt(shufflePrevIndex(), { fromShuffleHistory: true });
    return playAt((index - 1 + queue.length) % queue.length);
  }
  function setMode(value, { silent = false } = {}) {
    mode = MODES.includes(value) ? value : 'loop';
    Store.setMode(mode);
    resetShuffleHistory(true);
    if (!silent) emit('mode', mode);
    emitQueue('mode');
    return mode;
  }
  function cycleMode() { return setMode(MODES[(MODES.indexOf(mode) + 1) % MODES.length]); }
  function removeAt(target) {
    if (target < 0 || target >= queue.length || queue.length <= 1) return false;
    const removingCurrent = target === index;
    queue.splice(target, 1);
    if (target < index) index -= 1;
    else if (removingCurrent && index >= queue.length) index = 0;
    resetShuffleHistory(true); emitQueue('remove');
    if (removingCurrent) void playAt(index);
    return true;
  }
  function clearQueue() {
    const song = current();
    queue = song ? [song] : []; index = song ? 0 : -1;
    resetShuffleHistory(true); emitQueue('clear');
  }

  function emitCurrentTime() {
    const a = audio(), cur = Number(a.currentTime) || 0, dur = Number(a.duration) || 0;
    emit('time', { cur, dur, ratio: dur > 0 && isFinite(dur) ? cur / dur : 0, curText: fmt(cur), durText: fmt(dur) });
  }
  function isTargetSeekable(a, target) {
    if (!a.seekable || !a.seekable.length) return !current()?._nativeLocal;
    for (let i = 0; i < a.seekable.length; i++) if (target >= a.seekable.start(i) && target <= a.seekable.end(i) + .15) return true;
    return !current()?._nativeLocal;
  }
  function applyPendingSeek() {
    const ratio = pendingSeekRatio;
    if (ratio == null) return false;
    if (nativeLocalActive) {
      if (nativeLocalDuration <= 0) return false;
      const target = Math.round(Math.max(0, Math.min(1, ratio)) * nativeLocalDuration);
      pendingSeekRatio = null;
      try { nativeBridge()?.seekLocalMusic?.(target); return true; } catch { pendingSeekRatio = ratio; return false; }
    }
    const a = audio();
    if (!Number.isFinite(a.duration) || a.duration <= 0) return false;
    const target = Math.max(0, Math.min(1, ratio)) * a.duration;
    if (!isTargetSeekable(a, target)) return false;
    if (current()?._nativeLocal) localSeekBackup = { src: a.currentSrc || a.src, time: Number(a.currentTime) || 0, playing: !a.paused, songId: String(current()?.id || '') };
    pendingSeekRatio = null;
    try { a.currentTime = target; emitCurrentTime(); return true; } catch { pendingSeekRatio = ratio; return false; }
  }
  function recoverLocalSeek() {
    const a = audio(), backup = localSeekBackup;
    localSeekBackup = null;
    if (!backup || !current()?._nativeLocal || backup.songId !== String(current()?.id || '') || !backup.src) return false;
    try {
      a.pause(); a.removeAttribute('src'); a.load();
      a.addEventListener('loadedmetadata', () => { try { a.currentTime = backup.time; if (backup.playing) a.play().catch(() => {}); } catch {} }, { once: true });
      a.src = backup.src; a.load(); emit('seekRecovery', '本地音源定位未被系统支持，已恢复原播放位置'); return true;
    } catch { return false; }
  }
  function seek(ratio) { pendingSeekRatio = Math.max(0, Math.min(1, Number(ratio) || 0)); return applyPendingSeek(); }

  function publishPlaybackTime(cur, dur) {
    const safeCur = Math.max(0, Number(cur) || 0), safeDur = Math.max(0, Number(dur) || 0);
    emit('time', { cur: safeCur, dur: safeDur, ratio: safeDur ? safeCur / safeDur : 0, curText: fmt(safeCur), durText: fmt(safeDur) });
    if (!lyrics.length) return;
    const lyricIndex = lyricIndexAt(safeCur);
    if (lyricIndex !== lyricIdx) { lyricIdx = lyricIndex; emit('lyricIndex', lyricIndex); }
    const nextIdx = lyricIndex + 1 < lyrics.length ? lyricIndex + 1 : -1;
    if (nextIdx < 0) { emitLead({ show: false }); return; }
    const nextT = lyrics[nextIdx].time, prevT = nextIdx > 0 ? lyrics[nextIdx - 1].time : 0;
    const gap = nextT - prevT, until = nextT - safeCur, MIN_GAP = 2.5, LEAD = 2.4, INSTR = 8;
    if (gap >= MIN_GAP && until > 0 && until <= LEAD) emitLead({ show: true, dots: Math.max(0, Math.min(3, 3 - Math.floor((until / LEAD) * 3))), nextIdx, until, instrumental: false });
    else if (gap >= INSTR && until > LEAD) emitLead({ show: true, dots: 0, nextIdx, until, instrumental: true });
    else emitLead({ show: false });
  }
  function handleNativeLocalEvent(detail) {
    const event = detail || {}, song = current();
    if (!nativeLocalActive || !song || String(event.songId || '') !== String(song.id || '')) return;
    const type = String(event.type || ''), position = Math.max(0, Number(event.position) || 0), duration = Math.max(0, Number(event.duration) || nativeLocalDuration || 0);
    nativeLocalPosition = position; nativeLocalDuration = duration || nativeLocalDuration;
    const positionSec = position / 1000, durationSec = nativeLocalDuration / 1000;
    if (type === 'prepared') { applyPendingSeek(); publishPlaybackTime(positionSec, durationSec); return; }
    if (type === 'play') { nativeLocalPlaying = true; emit('play', song); publishPlaybackTime(positionSec, durationSec); return; }
    if (type === 'pause') { nativeLocalPlaying = false; emit('pause', song); publishPlaybackTime(positionSec, durationSec); return; }
    if (type === 'time' || type === 'seeked') { publishPlaybackTime(positionSec, durationSec); return; }
    if (type === 'ended') { nativeLocalActive = false; nativeLocalPlaying = false; next(true); return; }
    if (type === 'error') { try { nativeBridge()?.stopLocalMusic?.(); } catch {} nativeLocalActive = false; nativeLocalPlaying = false; emit('error', event.message || '原生本地播放器错误'); }
  }
  function fmt(t) { if (!isFinite(t) || t < 0) return '0:00'; return `${Math.floor(t / 60)}:${String(Math.floor(t % 60)).padStart(2, '0')}`; }
  function lyricIndexAt(currentTime) {
    let low = 0, high = lyrics.length - 1, answer = -1;
    const target = currentTime + 0.08;
    while (low <= high) { const mid = (low + high) >> 1; if (lyrics[mid].time <= target) { answer = mid; low = mid + 1; } else high = mid - 1; }
    return answer;
  }
  function bind() {
    mode = MODES.includes(Store.getMode()) ? Store.getMode() : 'loop';
    const a = audio();
    a.addEventListener('loadedmetadata', () => { applyPendingSeek(); emitCurrentTime(); syncNativeMedia(true); });
    a.addEventListener('durationchange', () => { applyPendingSeek(); syncNativeMedia(true); });
    a.addEventListener('progress', () => { applyPendingSeek(); });
    a.addEventListener('canplay', () => { applyPendingSeek(); });
    a.addEventListener('seeked', () => { localSeekBackup = null; emitCurrentTime(); syncNativeMedia(true); });
    a.addEventListener('timeupdate', () => { if (seeking || nativeLocalActive) return; publishPlaybackTime(a.currentTime || 0, a.duration || 0); syncNativeMedia(false); });
    a.addEventListener('ended', () => next(true));
    a.addEventListener('play', () => { emit('play', current()); syncNativeMedia(true); });
    a.addEventListener('pause', () => { emit('pause', current()); syncNativeMedia(true); });
    a.addEventListener('error', () => { if (!nativeLocalActive && !recoverLocalSeek()) emit('error', '音频加载失败'); });
    window.addEventListener('nativeLocalPlayer', event => handleNativeLocalEvent(event.detail));
  }
  function setSeeking(value) { seeking = !!value; }

  return {
    on, bind, playSong, setQueue, playAt, toggle, next, prev, cycleMode, setMode, removeAt, clearQueue, seek, setSeeking, fmt, refreshCurrentLyric,
    get queue() { return queue; }, get index() { return index; }, get current() { return current(); }, get mode() { return mode; }, get lyrics() { return lyrics; },
    get paused() { if (nativeLocalActive) return !nativeLocalPlaying; const a = audio(); return !a.src || a.paused; },
  };
})();
window.Player = Player;
