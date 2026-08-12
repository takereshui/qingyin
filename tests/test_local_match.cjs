const fs = require('fs');
const vm = require('vm');
const source = fs.readFileSync('/home/ubuntu/molan-music/molan-light-music/js/local.js', 'utf8');
const context = { window: {}, console };
vm.createContext(context);
vm.runInContext(source, context);
const Local = context.window.Local;
const locals = [
  { id: 'local-1', mediaId: '11', name: 'Die For You（为你而战）', artists: 'Grabbitz', album: 'VALORANT', duration: 212000, _localUrl: 'https://appassets.androidplatform.net/__goapk_media/11' },
  { id: 'local-2', mediaId: '12', name: 'Die For You', artists: 'Other Artist', album: 'Different', duration: 171000, _localUrl: 'https://appassets.androidplatform.net/__goapk_media/12' },
];
const remote = [{ id: 'ncm-1', name: 'Die For You（为你而战）', artists: 'VALORANT / Grabbitz', album: 'VALORANT', duration: 212300 }];
const matched = Local.preferLocal(remote, Local.createMatchIndex(locals));
if (!matched[0]._localMatch || matched[0]._localMediaId !== '11' || !matched[0]._localUrl) {
  throw new Error('Local match did not select the expected high-confidence media item');
}
console.log('Local matching chose the expected media file with score', matched[0]._localMatchScore);
