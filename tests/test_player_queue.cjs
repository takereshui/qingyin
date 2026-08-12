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

console.log('PASS: player queue order, single-loop, shuffle history, removal, and clear behavior');
