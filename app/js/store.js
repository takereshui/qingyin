const Store = (() => {
  const KEYS = {
    apiKey: 'molan_apikey', quality: 'molan_quality0',
    ncmcUrl: 'molan_ncmc_url', cookie: 'molan_cookie', cookieName: 'molan_cookie_name',
    user: 'molan_user', loginType: 'molan_login_type',
    favs: 'molan_favs', history: 'molan_history', mode: 'molan_mode',
    cacheSize: 'molan_cache_size', theme: 'molan_theme', backup: 'molan_backup',
  };
  const DEFAULT_NCMC = 'https://music.mcseekeri.com';
  const DEFAULT_QUALITY = 'exhigh';
  const QUALITY_OPTIONS = [
    { value: 'standard', label: '标准 · 128kbps' },
    { value: 'higher', label: '较高 · 192kbps' },
    { value: 'exhigh', label: '极高 · 320kbps' },
    { value: 'lossless', label: '无损 · FLAC' },
    { value: 'hires', label: 'Hi-Res · 高解析' },
  ];
  const DEFAULT_CACHE = 256;

  function g(key, fallback) { try { const value = localStorage.getItem(key); return value === null ? fallback : value; } catch { return fallback; } }
  function s(key, value) { try { localStorage.setItem(key, value); } catch {} }
  function gj(key, fallback) { try { const value = localStorage.getItem(key); return value ? JSON.parse(value) : fallback; } catch { return fallback; } }
  function sj(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch {} }

  return {
    getApiKey() { return g(KEYS.apiKey, ''); },
    setApiKey(value) { s(KEYS.apiKey, (value || '').trim()); },
    getQuality() { const value = g(KEYS.quality, DEFAULT_QUALITY); return QUALITY_OPTIONS.some(item => item.value === value) ? value : DEFAULT_QUALITY; },
    setQuality(value) { s(KEYS.quality, QUALITY_OPTIONS.some(item => item.value === value) ? value : DEFAULT_QUALITY); },
    getQualityOptions() { return QUALITY_OPTIONS.map(item => ({ ...item })); },
    getQualityLabel(value = this.getQuality()) { return (QUALITY_OPTIONS.find(item => item.value === value) || QUALITY_OPTIONS[2]).label; },
    getNcmcUrl() { return g(KEYS.ncmcUrl, DEFAULT_NCMC); },
    setNcmcUrl(value) { s(KEYS.ncmcUrl, (value || DEFAULT_NCMC).trim().replace(/\/+$/, '')); },
    getCookie() { return g(KEYS.cookie, ''); },
    setCookie(value) { s(KEYS.cookie, (value || '').trim()); },
    getCookieName() { return g(KEYS.cookieName, ''); },
    setCookieName(value) { s(KEYS.cookieName, (value || '').trim()); },
    getUser() { return gj(KEYS.user, null); },
    setUser(value) { sj(KEYS.user, value); },
    getLoginType() { return g(KEYS.loginType, ''); },
    setLoginType(value) { s(KEYS.loginType, value || ''); },
    isLoggedIn() { return !!this.getCookie() || !!this.getApiKey(); },
    getMode() { return g(KEYS.mode, 'loop'); },
    setMode(value) { s(KEYS.mode, value); },
    getCacheSize() { return parseInt(g(KEYS.cacheSize, DEFAULT_CACHE), 10) || DEFAULT_CACHE; },
    setCacheSize(value) { s(KEYS.cacheSize, String(Math.max(0, parseInt(value, 10) || 0))); },
    getTheme() { return g(KEYS.theme, 'light'); },
    setTheme(value) { s(KEYS.theme, value === 'light' ? 'light' : 'dark'); },
    toggleTheme() { const theme = this.getTheme() === 'dark' ? 'light' : 'dark'; this.setTheme(theme); return theme; },
    isBackup() { return g(KEYS.backup, '1') === '1'; },
    setBackup(value) { s(KEYS.backup, value ? '1' : '0'); },
    getFavs() { return gj(KEYS.favs, []); },
    setFavs(list) { sj(KEYS.favs, list); },
    isFav(id) { return this.getFavs().some(song => String(song.id) === String(id)); },
    toggleFav(song) {
      const list = this.getFavs();
      const index = list.findIndex(item => String(item.id) === String(song.id));
      if (index >= 0) list.splice(index, 1);
      else list.unshift({ id: song.id, name: song.name, artists: song.artists, album: song.album, picUrl: song.picUrl });
      this.setFavs(list); return index < 0;
    },
    getHistory() { return gj(KEYS.history, []); },
    pushHistory(song) {
      const list = gj(KEYS.history, []).filter(item => String(item.id) !== String(song.id));
      list.unshift({ id: song.id, name: song.name, artists: song.artists, album: song.album, picUrl: song.picUrl });
      sj(KEYS.history, list.slice(0, 100));
    },
    logout() { this.setCookie(''); this.setCookieName(''); this.setUser(null); this.setLoginType(''); },
  };
})();
window.Store = Store;
