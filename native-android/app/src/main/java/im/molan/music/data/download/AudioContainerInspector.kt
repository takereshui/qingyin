package im.molan.music.data.download

import java.io.File

/**
 * 通过音频文件头确认实际容器，不信任下载 URL、MIME 文本或任务历史中的文件扩展名。
 * 该组件不依赖 Android，可用 JVM 单元测试覆盖常见容器和未知字节路径。
 */
internal object AudioContainerInspector {
    /** 当前内置标签写入器已验证可安全读写的容器。 */
    val tagWritableExtensions = setOf("mp3", "flac", "m4a", "mp4", "ogg", "wav")

    fun detect(file: File): String? = runCatching {
        val header = ByteArray(128)
        val count = file.inputStream().use { it.read(header) }
        detect(header, count)
    }.getOrNull()

    internal fun detect(header: ByteArray, count: Int = header.size): String? {
        if (count < 4) return null
        fun startsWith(vararg bytes: Int): Boolean = bytes.indices.all { index ->
            index < count && (header[index].toInt() and 0xFF) == bytes[index]
        }
        return when {
            startsWith(0x49, 0x44, 0x33) -> "mp3" // ID3
            // ADTS AAC 同样以 0xFFF 开头；必须在通用 MPEG 帧同步字之前识别。
            count >= 2 && (header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xF6) == 0xF0 -> "aac"
            count >= 2 && (header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            startsWith(0x66, 0x4C, 0x61, 0x43) -> "flac" // fLaC
            startsWith(0x4F, 0x67, 0x67, 0x53) -> "ogg" // Ogg / Opus
            startsWith(0x52, 0x49, 0x46, 0x46) && count >= 12 && String(header, 8, 4, Charsets.US_ASCII) == "WAVE" -> "wav"
            count >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" -> {
                val brand = String(header, 8, minOf(16, count - 8), Charsets.US_ASCII)
                if (brand.contains("M4A", ignoreCase = true)) "m4a" else "mp4"
            }
            else -> null
        }
    }

    fun normalizeExtension(file: File, extension: String): File {
        if (file.extension.lowercase() == extension) return file
        val target = File(file.parentFile, "${file.nameWithoutExtension}.$extension")
        if (target.exists()) target.delete()
        return if (file.renameTo(target)) target else file
    }
}
