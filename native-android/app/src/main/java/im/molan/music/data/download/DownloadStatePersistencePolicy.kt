package im.molan.music.data.download

/**
 * 控制下载进度写盘频率。
 *
 * UI 进度仍可按下载器的粒度刷新；只有状态切换会立即持久化。纯进度刷新最多每个间隔
 * 写入一次完整任务快照，应用意外退出时则由 `.part` 文件长度恢复最新进度。
 */
internal class DownloadStatePersistencePolicy(
    private val minimumProgressPersistIntervalMs: Long = DEFAULT_PROGRESS_PERSIST_INTERVAL_MS,
) {
    init {
        require(minimumProgressPersistIntervalMs > 0L) { "进度持久化间隔必须大于 0" }
    }

    private var lastProgressPersistAtMs: Long? = null

    fun shouldPersistProgress(nowMs: Long): Boolean {
        require(nowMs >= 0L) { "时钟值必须非负" }
        val previous = lastProgressPersistAtMs
        if (previous == null || nowMs - previous >= minimumProgressPersistIntervalMs) {
            lastProgressPersistAtMs = nowMs
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_PROGRESS_PERSIST_INTERVAL_MS = 2_000L
    }
}
