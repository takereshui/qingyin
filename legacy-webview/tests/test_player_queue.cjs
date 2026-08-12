const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const source = fs.readFileSync('/home/ubuntu/molan-music/molan-light-music/js/player.js', 'utf8');
const audio = {
  src: '', currentTime: 0, duration: 0, paused: true,
  seekable: { length: 0 },
  pause() { this.paused = true; }, play() { this.paused = false; return Promise.resolve(); },
  removeAttribute() { this.src = ''; }, load() {}, addEventListener() {},
};
let savedMode = 'loop';
const context = {
  console, setTimeout: () => 1, clearTimeout: () => {},
  document: { getElementById: () => audio },
  Store: {
    getMode: () => savedMode, setMode: value => { savedMode = value; }, pushHistory: () => {},
    getQuality: () => 'exhigh', getQualityLabel: () => '超清', getApiKey: () => '', isBackup: () => false,
  },
  // Keep source resolution pending; queue index changes occur before asynchronous media resolution.
  DL: { get: () => new Promise(() => {}) },
  NCM: {}, API: {}, URL, Blob,
  addEventListener() {},
};
context.window = context;
vm.createContext(context);
vm.runInContext(source, context);

const songs = [
  { id: 'a', name: 'A', artists: '甲' },
  { id: 'b', name: 'B', artists: '乙' },
  { id: 'c', name: 'C', artists: '丙' },
];
const player = context.Player;
player.setQueue(songs, 'b');
assert.strictEqual(player.index, 1);
assert.strictEqual(player.current.id, 'b');
assert.strictEqual(player.queue.length, 3);

player.setMode('loop');
player.next();
assert.strictEqual(player.index, 2, '列表循环下一首应前进');
player.next();
assert.strictEqual(player.index, 0, '列表循环末尾应回到首曲');
player.prev();
assert.strictEqual(player.index, 2, '列表循环上一首应回到末曲');

player.setMode('single');
player.next(true);
assert.strictEqual(player.index, 2, '单曲循环自动结束时应留在当前曲');
player.next(false);
assert.strictEqual(player.index, 0, '单曲循环手动下一首仍应前进');

player.setMode('shuffle');
const beforeShuffle = player.index;
player.next();
assert.notStrictEqual(player.index, beforeShuffle, '随机播放下一首不应重复当前曲');
const shuffleCurrent = player.index;
player.prev();
assert.strictEqual(player.index, beforeShuffle, '随机播放上一首应按随机历史回退');
player.next();
assert.strictEqual(player.index, shuffleCurrent, '随机播放下一首应恢复历史中的下一曲');

const removeIndex = player.index === 0 ? 1 : 0;
assert.strictEqual(player.removeAt(removeIndex), true);
assert.strictEqual(player.queue.length, 2, '移除队列曲目后应更新长度');
player.clearQueue();
assert.strictEqual(player.queue.length, 1, '清空队列应保留正在播放的歌曲');
assert.strictEqual(player.current.id, songs[shuffleCurrent].id);

// Race regression: an obsolete play() promise may reject after a new track calls load().
// It must be ignored rather than surfaced as a player error or an automatic skip.
let rejectFirstPlay;
let playCount = 0;
const raceAudio = {
  src: '', currentTime: 0, duration: 0, paused: true,
  seekable: { length: 0 },
  pause() { this.paused = true; },
  play() {
    this.paused = false; playCount += 1;
    if (playCount === 1) return new Promise((resolve, reject) => { rejectFirstPlay = reject; });
    return Promise.resolve();
  },
  removeAttribute() { this.src = ''; }, load() {}, addEventListener() {},
};
const raceEvents = [];
const raceContext = {
  console, setTimeout: () => 1, clearTimeout: () => {},
  document: { getElementById: () => raceAudio },
  Store: { getMode: () => 'loop', setMode: () => {}, pushHistory: () => {}, getQuality: () => 'exhigh', getQualityLabel: () => '超清', getApiKey: () => '', isBackup: () => false },
  DL: { get: async () => ({ blob: new Blob(['audio']) }) },
  NCM: {}, API: {}, URL, Blob, addEventListener() {},
};
raceContext.window = raceContext;
vm.createContext(raceContext);
vm.runInContext(source, raceContext);
const racePlayer = raceContext.Player;
racePlayer.on((type, payload) => { if (type === 'error') raceEvents.push(payload); });
racePlayer.setQueue(songs, 'a');
(async () => {
  const first = racePlayer.playAt(0);
  for (let i = 0; i < 4 && typeof rejectFirstPlay !== 'function'; i++) await Promise.resolve();
  assert.strictEqual(typeof rejectFirstPlay, 'function', '首次音源应已进入待定 play 状态');
  const second = racePlayer.playAt(1);
  for (let i = 0; i < 3; i++) await Promise.resolve();
  rejectFirstPlay(new Error('The play() request was interrupted by a new load request.'));
  await Promise.all([first, second]);
  assert.strictEqual(racePlayer.current.id, 'b');
  assert.deepStrictEqual(raceEvents, [], '陈旧的 play 中断不得显示为播放错误');
  console.log('PASS: player queue order, single-loop, shuffle history, removal, clear behavior, and load-race cancellation');
})().catch(error => { console.error(error); process.exit(1); });
