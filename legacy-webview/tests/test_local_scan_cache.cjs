const fs = require('fs');
const vm = require('vm');
const source = fs.readFileSync('/home/ubuntu/molan-music/molan-light-music/js/local.js', 'utf8');
let scanCalls = 0;
let failScan = false;
let saved = null;
const bridge = {
  scanLocalMusic() {
    scanCalls += 1;
    if (failScan) return JSON.stringify({ state: 'failed', message: '临时扫描失败', songs: [] });
    return JSON.stringify({
      state: 'ready', directory: '系统音乐库', songs: [{ id: '42', mediaId: '42', name: '缓存测试', artists: 'Molan', album: 'Local', duration: 180000, localUrl: 'content://media/42' }],
    });
  },
  getLocalMusicDirectory() { return '系统音乐库'; },
};
const context = {
  window: { NativeBridge: bridge },
  DL: {
    async cacheGet(key) { return key === 'local-scan:v1' ? saved : null; },
    async cachePut(key, data) { if (key === 'local-scan:v1') saved = data; },
    async cacheDel(key) { if (key === 'local-scan:v1') saved = null; },
  },
  console,
};
vm.createContext(context);
vm.runInContext(source, context);
const Local = context.window.Local;
(async () => {
  const first = await Local.scan();
  if (scanCalls !== 1 || first.cached || first.songs.length !== 1 || !saved?.scannedAt) throw new Error('First scan was not saved correctly');
  const second = await Local.scan();
  if (scanCalls !== 1 || !second.cached || second.songs[0].id !== '42') throw new Error('Cached scan still invoked native scanner');
  const refreshed = await Local.scan({ force: true });
  if (scanCalls !== 2 || refreshed.cached) throw new Error('Forced scan did not invoke native scanner');
  failScan = true;
  const retained = await Local.scan({ force: true });
  if (scanCalls !== 3 || !retained.stale || retained.songs[0]?.id !== '42') throw new Error('Failed refresh cleared the valid cached library');
  console.log('Local scan cache reused persisted results, rescanned only when forced, and retained results after a failed refresh');
})().catch(error => { console.error(error); process.exit(1); });
