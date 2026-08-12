const MusicFeatures = (() => {
  async function safe(call, fallback) {
    try { return await call(); } catch { return fallback; }
  }

  function hasSession() { return !!Store.getCookie(); }

  async function home() {
    const [daily, recommended, personalized] = await Promise.all([
      hasSession() ? safe(() => NCM.dailySongs(), []) : Promise.resolve([]),
      hasSession() ? safe(() => NCM.recommendResources(), []) : Promise.resolve([]),
      safe(() => NCM.personalized(12), []),
    ]);
    return { daily, recommended, personalized };
  }

  async function discover() {
    const [toplists, playlists, albums, artists] = await Promise.all([
      safe(() => NCM.toplist(), []), safe(() => NCM.topPlaylists('全部', 18), []),
      safe(() => NCM.newAlbums(12), []), safe(() => NCM.topArtists(12), []),
    ]);
    return { toplists, playlists, albums, artists };
  }


  async function playlist(id) { return NCM.playlist(id); }
  async function album(id) { return NCM.album(id); }
  async function artist(id) {
    const [profile, songs] = await Promise.all([safe(() => NCM.artist(id), null), safe(() => NCM.artistTopSongs(id), [])]);
    return { profile, songs };
  }

  return { hasSession, home, discover, playlist, album, artist };
})();
window.MusicFeatures = MusicFeatures;
