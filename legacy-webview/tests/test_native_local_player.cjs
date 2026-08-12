const fs = require('fs');
const vm = require('vm');
const events = new Map();
const audioEvents = new Map();
const bridgeCalls = [];
const audio = {
  src: '', currentSrc: '', currentTime: 0, duration: 0, paused: true, seekable: { length: 0 },
  pause() { this.paused = true; }, removeAttribute() { this.src = ''; this.currentSrc = ''; }, load() {},
  play() { this.paused = false; return Promise.resolve(); },
  addEventListener(type, fn) { (audioEvents.get(type) || audioEvents.set(type, []).get(type)).push(fn); },
};
const context = {
  console, setTimeout, clearTimeout,
  window: {
    NativeBridge: {
      playLocalMusic(...args) { bridgeCalls.push(['playLocalMusic', ...args]); },
      pauseLocalMusic() { bridgeCalls.push(['pauseLocalMusic']); },
      resumeLocalMusic() { bridgeCalls.push(['resumeLocalMusic']); },
      seekLocalMusic(position) { bridgeCalls.push(['seekLocalMusic', position]); },
      stopLocalMusic() { bridgeCalls.push(['stopLocalMusic']); },
    },
    addEventListener(type, fn) { (events.get(type) || events.set(type, []).get(type)).push(fn); },
  },
  document: { getElementById(id) { return id === 'audio' ? audio : null; } },
  Store: { getMode: () => 'loop', setMode() {}, pushHistory() {}, getApiKey: () => '', isBackup: () => false, getQuality: () => 'exhigh', getQualityLabel: () => '极高 · 320kbps' },
  DL: { get: async () => null },
  API: { parseLrc: () => [] },
  NCM: {},
  URL: { createObjectURL: () => 'blob:test' },
};
vm.createContext(context);
vm.runInContext(fs.readFileSync('/home/ubuntu/molan-music/molan-light-music/js/player.js', 'utf8'), context);
const Player = context.window.Player;
const seen = [];
Player.on((type, payload) => seen.push([type, payload]));
Player.bind();
const song = { id: 'local-media:7', mediaId: '7', nativeUri: 'media:7', _nativeLocal: true, name: '本地测试', artists: '测试歌手', duration: 180000 };
Player.playSong(song, [song]);
if (bridgeCalls[0]?.[0] !== 'playLocalMusic' || bridgeCalls[0][2] !== 'media:7') throw new Error('Native local player was not selected');
if (audio.src) throw new Error('WebView audio src must stay empty for native local playback');
function emitNative(detail) { for (const fn of events.get('nativeLocalPlayer') || []) fn({ detail }); }
emitNative({ type: 'prepared', songId: song.id, position: 0, duration: 180000 });
emitNative({ type: 'play', songId: song.id, position: 0, duration: 180000 });
emitNative({ type: 'time', songId: song.id, position: 90000, duration: 180000 });
const time = seen.findLast(item => item[0] === 'time')?.[1];
if (!time || time.cur !== 90 || time.dur !== 180 || time.ratio !== 0.5) throw new Error('Native milliseconds were not converted to UI seconds');
Player.seek(0.5);
if (!bridgeCalls.some(item => item[0] === 'seekLocalMusic' && item[1] === 90000)) throw new Error('Seek was not forwarded to native MediaPlayer');
Player.toggle();
if (!bridgeCalls.some(item => item[0] === 'pauseLocalMusic')) throw new Error('Pause was not forwarded to native MediaPlayer');
emitNative({ type: 'pause', songId: song.id, position: 90000, duration: 180000 });
Player.toggle();
if (!bridgeCalls.some(item => item[0] === 'resumeLocalMusic')) throw new Error('Resume was not forwarded to native MediaPlayer');
console.log('Native local player bridge test passed: direct native play, seek, timeline conversion, and controls verified');
