# APlayer 性能对照研究笔记

研究日期：2026-08-14

## 公开仓库与分支

- 仓库：https://github.com/rRemix/APlayer
- 研究分支：`compose`
- 本地只读副本：`/home/ubuntu/APlayer-reference`

## 已验证的实现要点

| APlayer 做法 | 源码证据 | 对轻音的启示 |
|---|---|---|
| 以 MediaStore 为主数据源，仓库在工作线程查询并用最小基础投影构造歌曲实体。 | [`SongRepository.kt`](https://github.com/rRemix/APlayer/blob/compose/app/src/main/java/remix/myplayer/repo/SongRepository.kt)，`getSongs()` 与 `makeSongCursor()` | 轻音应把 MediaStore 作为常态路径，避免为普通本地库额外调用逐文件 `MediaMetadataRetriever`。 |
| 将最小文件大小、黑名单、删除项放入 MediaStore selection，减少无效候选进入应用层。 | [`AbstractRepository.kt`](https://github.com/rRemix/APlayer/blob/compose/app/src/main/java/remix/myplayer/repo/AbstractRepository.kt) | 轻音的目录或媒体库筛选应尽可能下推到查询层；尤其应避免枚举后再做大批过滤。 |
| 使用 `ContentObserver` 监听 MediaStore 改动，并以 800ms 防抖合并多次变化后再发出刷新事件。 | [`MediaStoreObserver.kt`](https://github.com/rRemix/APlayer/blob/compose/app/src/main/java/remix/myplayer/service/MediaStoreObserver.kt) | 轻音当前以启动、设置恢复、下载状态变化主动整扫为主；应改为事件驱动、可合并的增量刷新。 |
| 提供 Baseline Profile 与 Macrobenchmark，基准明确要求实体机、冷启动与系统 trace。 | [`baselineprofile`](https://github.com/rRemix/APlayer/tree/compose/baselineprofile) | 轻音不能仅凭体感调优；应建立扫描/滚动宏基准和 Baseline Profile，持续控制回归。 |
| 使用 TagLib for Android 处理标签编辑。 | [README](https://github.com/rRemix/APlayer) | 若轻音需要可靠地写入下载文件的 ID3/FLAC 标签与封面，应采用专门标签库；这与“扫描快”是不同问题。 |

## 初步判断

APlayer 的优势不是 Rust，而是：**系统媒体库优先、筛选下推、事件防抖、工作线程读取、完整的性能基准与基线编译**。这些策略都可直接迁移到轻音 Kotlin/Compose 架构中。

## 后续需要映射的轻音问题

1. 当前 SAF 自定义目录在每次扫描时完整递归枚举，缓存不跳过枚举。
2. 当前扫描完成后存在索引未命中时的全库模糊匹配回退。
3. 当前播放器每 500ms 发布快照，根界面订阅后降低了 Compose 的帧时间余量。
4. 当前本地/下载封面首次显示时会逐项调用 `MediaMetadataRetriever`。

以上仅为研究记录，不包含业务源码修改。
