const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const apiSource = fs.readFileSync('/home/ubuntu/molan-music/molan-light-music/js/api.js', 'utf8');
let requested = '';
const context = {
  console,
  URLSearchParams,
  AbortController,
  setTimeout,
  clearTimeout,
  Store: {
    isBackup: () => false,
    getApiKey: () => 'test-key',
    getQuality: () => 'exhigh',
    getNcmcUrl: () => 'https://example.invalid',
    getCookie: () => '',
  },
  DL: { cacheGet: async () => null, cachePut: async () => {} },
  fetch: async (url) => {
    requested = String(url);
    return { ok: true, json: async () => ({ code: 200, mid: '0039MnYb0qxYhV', url: 'https://cdn.example/song.flac', lrc: '[00:00.00]测试', cover: 'https://img.example/cover.jpg', bitrate: 'flac' }) };
  },
};
context.window = context;
vm.createContext(context);
vm.runInContext(apiSource, context);

(async () => {
  const qq = await context.API.qqMusic('0039MnYb0qxYhV', 'lossless');
  assert.strictEqual(qq.url, 'https://cdn.example/song.flac');
  assert.strictEqual(qq.lrc, '[00:00.00]测试');
  assert.match(requested, /\/api\/qq_music\?/);
  assert.match(requested, /mid=0039MnYb0qxYhV/);
  assert.match(requested, /size=flac/);
  assert.match(requested, /apikey=test-key/);

  const app = fs.readFileSync('/home/ubuntu/molan-music/molan-light-music/js/app.js', 'utf8');
  const player = fs.readFileSync('/home/ubuntu/molan-music/molan-light-music/js/player.js', 'utf8');
  const downloads = fs.readFileSync('/home/ubuntu/molan-music/molan-light-music/js/downloads.js', 'utf8');
  const native = fs.readFileSync('/home/ubuntu/goapk/java/QQMusicSession.java', 'utf8');
  assert.match(app, /qpl:v1:/);
  assert.match(app, /qpldetail:v1:/);
  assert.match(app, /sourceLabel/);
  assert.match(app, /qqQrCreate/);
  assert.match(app, /qqPlaylistDetail/);
  assert.match(player, /API\.qqMusic/);
  assert.match(downloads, /API\.qqMusic/);
  assert.match(native, /AndroidKeyStore/);
  assert.match(native, /qqQrCreate|createQr/);
  assert.match(native, /qqMyPlaylists|myPlaylists/);
  console.log('PASS: QQ ChKSz mapping, source cache isolation, player/download adaptation, and native session contract');
})().catch(err => { console.error(err); process.exit(1); });
