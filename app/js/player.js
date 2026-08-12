const Player = (() => {
  const audio = () => document.getElementById('audio');
  let queue = [], index = -1, lyrics = [], lyricIdx = -1, mode = 'loop', seeking = false, pendingSeekRatio = null;
  let lastLead = '', lyricRequestId = 0, lastNativeMediaSync = 0, lastNativeMediaKey = '', localSeekBackup = null;
  let nativeLocalActive = false, nativeLocalPlaying = false, nativeLocalDuration = 0, nativeLocalPosition = 0;
  const listeners = new Set();
  function emit(evt, data) { listeners.forEach(fn => { try { fn(evt, data); } catch {} }); }
  function emitLead(payload) {
    const key = payload.show
      ? `${payload.nextIdx}|${payload.instrumental ? 'i' : payload.dots}`
      : 'off';
    if (key === lastLead) return;
    lastLead = key;
    emit('leadIn', payload);
  }
  function on(fn) { listeners.add(fn); return () => listeners.delete(fn); }
  function syncNativeMedia(force = false) {
    if (nativeLocalActive) return; // Native MediaPlayer owns local-media session updates.
    const bridge = typeof window !== 'undefined' ? window.NativeBridge : null;
    const song = current(), a = audio();
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
  function current() { return index >= 0 ? queue[index] : null; }
  function nativeBridge() { return typeof window !== 'undefined' ? window.NativeBridge : null; }
  function stopNativeLocal() {
    const bridge = nativeBridge();
    if (nativeLocalActive && bridge?.stopLocalMusic) { try { bridge.stopLocalMusic(); } catch {} }
    nativeLocalActive = false; nativeLocalPlaying = false; nativeLocalDuration = 0; nativeLocalPosition = 0;
  }
  function setQueue(list, startId) {
    queue = (list || []).slice();
    if (!queue.length) { index = -1; return; }
    if (startId != null) {
      const i = queue.findIndex(s => String(s.id) === String(startId));
      index = i >= 0 ? i : 0;
    } else index = 0;
  }

  async function playAt(i) {
    if (i < 0 || i >= queue.length) return;
    index = i;
    const song = queue[index];
    stopNativeLocal();
    emit('loading', song);
    emit('song', song);
    syncNativeMedia(true);
    Store.pushHistory(song);
    const a = audio();
    try {
      // 1) Android MediaPlayer owns scanned local music. It reads content:// directly,
      // avoiding WebView Range requests entirely.
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
      // 2) Browser-owned local File objects, for non-native fallback contexts only.
      if (song._localUrl || song._localFile) {
        if (!song._localUrl && song._localFile) song._localUrl = URL.createObjectURL(song._localFile);
        song._playbackQuality = '本地文件 · 原始音质';
        a.src = song._localUrl;
        a.load();
        await a.play();
        emit('play', song);
        void loadLyric(song);
        return;
      }
      // 3) Downloaded / cached blob from IndexedDB
      const saved = await DL.get(song.id);
      if (saved && saved.blob) {
        song._playbackQuality = saved.qualityLabel || '已下载文件 · 原始音质';
        a.src = URL.createObjectURL(saved.blob);
        a.load();
        await a.play();
        emit('play', song);
        void loadLyric(song);
        return;
      }
      // 4) Online (ChKSz first, fallback NCM)
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
          try {
            const info = await API.music(song.id, quality);
            url = info.url || '';
            if (info.picUrl && !song.picUrl) song.picUrl = info.picUrl;
          } catch {}
        }
        if (!url) {
          try { url = await NCM.musicUrl(song.id, quality) || ''; } catch {}
        }
      }
      if (!url) throw new Error('无播放地址');
      song._playbackQuality = song._playbackQuality || Store.getQualityLabel(quality);
      // try cache first
      const cached = await NCM.getCachedAudio(url);
      if (cached) {
        a.src = URL.createObjectURL(await cached.blob());
      } else {
        a.src = url;
      }
      a.load();
      await a.play();
      // cache in background
      NCM.cacheAudio(url, song.id).catch(() => {});
      emit('play', song);
      void loadLyric(song);
    } catch (e) {
      emit('error', e.message || String(e));
      setTimeout(() => next(true), 800);
    }
  }

  async function loadLyric(song, { force = false } = {}) {
    const requestId = ++lyricRequestId;
    lyrics = []; lyricIdx = -1; lastLead = ''; emit('lyric', []); emitLead({ show: false });
    if (!song) return;
    const isNativeLocal = !!song._nativeLocal || String(song.id || '').startsWith('local-');
    try {
      let l = null;
      if (song._localLrc) {
        l = { lrc: song._localLrc, tlyric: '' };
        emit('lyricStatus', { state: 'embedded', message: '正在使用文件内嵌歌词' });
      }
      if (!l && isNativeLocal) {
        emit('lyricStatus', { state: 'matching', message: '正在匹配本地音乐歌词…' });
        if (window.LocalLyrics) {
          const matched = await LocalLyrics.resolve(song, { force });
          if (requestId !== lyricRequestId) return;
          if (matched.state === 'matched') {
            l = { lrc: matched.lrc, tlyric: matched.tlyric || '' };
            emit('lyricStatus', { state: 'matched', message: `已匹配 · ${matched.match.name} — ${matched.match.artists}`, match: matched.match, cached: matched.cached });
          } else {
            emit('lyricStatus', { state: matched.state || 'miss', message: matched.message || '未匹配到歌词' });
          }
        } else {
          emit('lyricStatus', { state: 'miss', message: '歌词匹配模块未加载' });
        }
      }
      if (!l && !isNativeLocal) {
        const cached = !force ? await DL.getLyric(song.id) : null;
        if (cached) {
          if (cached.missing) {
            emit('lyricStatus', { state: 'miss', message: '暂无歌词（已缓存，稍后可手动刷新）' });
            l = { lrc: '', tlyric: '' };
          } else if (cached.lrc) {
            l = cached;
            emit('lyricStatus', { state: 'cached', message: '正在使用缓存歌词' });
          }
        }
        if (!l) {
          l = { lrc: '', tlyric: '' };
          const isQQ = song.source === 'qq' || !!song.qqMid || String(song.id || '').startsWith('qq:');
          if (isQQ) {
            l.lrc = song._qqLrc || '';
            if (!l.lrc && (Store.getApiKey() || Store.isBackup())) {
              try { const r = await API.qqMusic(song.qqMid || String(song.id).replace(/^qq:/, ''), Store.getQuality()); l.lrc = r.lrc || ''; song._qqLrc = l.lrc; } catch {}
            }
          } else {
            if (Store.getApiKey() || Store.isBackup()) {
              try { const r = await API.lyric(song.id); l.lrc = r.lrc; l.tlyric = r.tlyric; } catch {}
            }
            if (!l.lrc) {
              try { const r = await NCM.lyric(song.id); l.lrc = r.lrc; l.tlyric = r.tlyric; } catch {}
            }
          }
          DL.putLyric(song.id, l).catch(() => {});
          emit('lyricStatus', { state: l.lrc ? 'matched' : 'miss', message: l.lrc ? '歌词已加载' : '暂无歌词' });
        }
      }
      if (requestId !== lyricRequestId) return;
      lyrics = API.parseLrc(l?.lrc || '', l?.tlyric || '');
      emit('lyric', lyrics);
    } catch (e) {
      if (requestId === lyricRequestId) {
        emit('lyricStatus', { state: 'failed', message: '歌词加载失败' });
        emit('lyric', []);
      }
    }
  }

  function refreshCurrentLyric() {
    const song = current();
    if (song) return loadLyric(song, { force: true });
  }

  function playSong(song, list) {
    if (list && list.length) { setQueue(list, song.id); } else {
      const i = queue.findIndex(s => String(s.id) === String(song.id));
      i >= 0 ? index = i : (queue = [song], index = 0);
    }
    return playAt(index);
  }

  function toggle() {
    if (nativeLocalActive) {
      const bridge = nativeBridge();
      try { nativeLocalPlaying ? bridge?.pauseLocalMusic?.() : bridge?.resumeLocalMusic?.(); } catch {}
      return;
    }
    const a = audio();
    if (!a.src) { if (queue.length) return playAt(index >= 0 ? index : 0); return; }
    if (a.paused) a.play().then(() => emit('play', current())).catch(()=>{});
    else a.pause(), emit('pause', current());
  }

  function next(auto) {
    if (!queue.length) return;
    if (mode === 'single' && auto) return playAt(index);
    if (mode === 'shuffle') {
      if (queue.length === 1) return playAt(0);
      let n = index; while (n === index) n = Math.floor(Math.random()*queue.length);
      return playAt(n);
    }
    return playAt((index + 1) % queue.length);
  }

  function prev() {
    if (!queue.length) return;
    if (nativeLocalActive) {
      if (nativeLocalPosition > 3000) { try { nativeBridge()?.seekLocalMusic?.(0); } catch {} return; }
      return playAt((index - 1 + queue.length) % queue.length);
    }
    const a = audio();
    if (a.currentTime > 3) { a.currentTime = 0; return; }
    return playAt((index - 1 + queue.length) % queue.length);
  }

  function cycleMode() {
    const order = ['loop','single','shuffle'];
    mode = order[(order.indexOf(mode)+1)%order.length];
    Store.setMode(mode); emit('mode', mode); return mode;
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
    try { a.currentTime = target; emitCurrentTime(); return true; }
    catch { pendingSeekRatio = ratio; return false; }
  }
  function recoverLocalSeek() {
    const a = audio(), backup = localSeekBackup;
    localSeekBackup = null;
    if (!backup || !current()?._nativeLocal || backup.songId !== String(current()?.id || '') || !backup.src) return false;
    try {
      a.pause(); a.removeAttribute('src'); a.load();
      a.addEventListener('loadedmetadata', () => {
        try { a.currentTime = backup.time; if (backup.playing) a.play().catch(() => {}); } catch {}
      }, { once: true });
      a.src = backup.src; a.load(); emit('seekRecovery', '本地音源定位未被系统支持，已恢复原播放位置');
      return true;
    } catch { return false; }
  }
  function seek(ratio) {
    const a = audio();
    pendingSeekRatio = Math.max(0, Math.min(1, Number(ratio) || 0));
    return applyPendingSeek();
  }

  function publishPlaybackTime(cur, dur) {
    const safeCur = Math.max(0, Number(cur) || 0), safeDur = Math.max(0, Number(dur) || 0);
    emit('time', { cur: safeCur, dur: safeDur, ratio: safeDur ? safeCur / safeDur : 0, curText: fmt(safeCur), durText: fmt(safeDur) });
    if (!lyrics.length) return;
    const i = lyricIndexAt(safeCur);
    if (i !== lyricIdx) { lyricIdx = i; emit('lyricIndex', i); }
    const nextIdx = i + 1 < lyrics.length ? i + 1 : -1;
    if (nextIdx < 0) { emitLead({ show: false }); return; }
    const nextT = lyrics[nextIdx].time, prevT = nextIdx > 0 ? lyrics[nextIdx - 1].time : 0;
    const gap = nextT - prevT, until = nextT - safeCur, MIN_GAP = 2.5, LEAD = 2.4, INSTR = 8;
    if (gap >= MIN_GAP && until > 0 && until <= LEAD) {
      emitLead({ show: true, dots: Math.max(0, Math.min(3, 3 - Math.floor((until / LEAD) * 3))), nextIdx, until, instrumental: false });
    } else if (gap >= INSTR && until > LEAD) emitLead({ show: true, dots: 0, nextIdx, until, instrumental: true });
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
    if (type === 'error') {
      try { nativeBridge()?.stopLocalMusic?.(); } catch {}
      nativeLocalActive = false; nativeLocalPlaying = false;
      emit('error', event.message || '原生本地播放器错误');
    }
  }
  function fmt(t) {
    if (!isFinite(t) || t < 0) return '0:00';
    return `${Math.floor(t/60)}:${String(Math.floor(t%60)).padStart(2,'0')}`;
  }

  function lyricIndexAt(currentTime) {
    let low = 0, high = lyrics.length - 1, answer = -1;
    const target = currentTime + 0.08;
    while (low <= high) {
      const mid = (low + high) >> 1;
      if (lyrics[mid].time <= target) { answer = mid; low = mid + 1; }
      else high = mid - 1;
    }
    return answer;
  }

  function bind() {
    mode = Store.getMode() || 'loop';
    const a = audio();
    a.addEventListener('loadedmetadata', () => { applyPendingSeek(); emitCurrentTime(); syncNativeMedia(true); });
    a.addEventListener('durationchange', () => { applyPendingSeek(); syncNativeMedia(true); });
    a.addEventListener('progress', () => { applyPendingSeek(); });
    a.addEventListener('canplay', () => { applyPendingSeek(); });
    a.addEventListener('seeked', () => { localSeekBackup = null; emitCurrentTime(); syncNativeMedia(true); });
    a.addEventListener('timeupdate', () => {
      if (seeking || nativeLocalActive) return;
      publishPlaybackTime(a.currentTime || 0, a.duration || 0);
      syncNativeMedia(false);
    });
    a.addEventListener('ended', () => next(true));
    a.addEventListener('play', () => { emit('play', current()); syncNativeMedia(true); });
    a.addEventListener('pause', () => { emit('pause', current()); syncNativeMedia(true); });
    a.addEventListener('error', () => { if (!nativeLocalActive && !recoverLocalSeek()) emit('error', '音频加载失败'); });
    window.addEventListener('nativeLocalPlayer', event => handleNativeLocalEvent(event.detail));
  }

  function setSeeking(v) { seeking = !!v; }
  function setMode(m) { mode = m || 'loop'; }

  return {
    on, bind, playSong, setQueue, playAt, toggle, next, prev,
    cycleMode, setMode, seek, setSeeking, fmt, refreshCurrentLyric,
    get queue() { return queue; }, get index() { return index; }, get current() { return current(); },
    get mode() { return mode; }, get lyrics() { return lyrics; },
    get paused() { if (nativeLocalActive) return !nativeLocalPlaying; const a = audio(); return !a.src || a.paused; },
  };
})();
window.Player = Player;