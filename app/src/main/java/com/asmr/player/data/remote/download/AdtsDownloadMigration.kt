package com.asmr.player.data.remote.download

import android.util.Log
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 修复历史下载的 OtomeKoe 音频。
 *
 * 旧版本把 HLS 的 TS 分片解出的裸 ADTS 流直接存成 `.aac`：裸流没有容器采样表，
 * ExoPlayer 拿不到可定位的 SeekMap，进度条不可拖、点歌词不跳转。
 * 现在下载时已经转封装为 MP4(M4A)，这里再把遗留的裸 `.aac` 就地转封装一次，
 * 让老专辑无需重新下载也能正常定位。
 */
internal object AdtsDownloadMigration {

    private const val LEGACY_EXTENSION = "aac"
    private const val MUXED_EXTENSION = "m4a"
    private const val HEADER_PROBE_SIZE = 12

    /**
     * 扫描 [rootDir]（文件目录或 SAF 文档树）下的遗留 `.aac`，把裸 ADTS 转封装为同目录同名 `.m4a`。
     * 已经转封装过（内容本身是 MP4）的文件仅改名，无法识别的文件保持原样。
     *
     * @return 迁移成功的文件数
     */
    suspend fun migrate(
        rootDir: String,
        storage: DownloadStorageGateway,
        stagingDir: File,
    ): Int = withContext(Dispatchers.IO) {
        if (rootDir.isBlank()) return@withContext 0
        val entries = runCatching { storage.walk(rootDir) }.getOrDefault(emptyList())
        val legacyEntries = entries.filter { entry ->
            !entry.isDirectory &&
                entry.displayName.substringAfterLast('.', "").equals(LEGACY_EXTENSION, ignoreCase = true)
        }
        if (legacyEntries.isEmpty()) return@withContext 0
        val existingFiles = entries.asSequence()
            .filter { entry -> !entry.isDirectory }
            .associateBy { entry -> entry.relativePath }
        stagingDir.mkdirs()

        var migrated = 0
        legacyEntries.forEach { entry ->
            val baseName = entry.displayName.substringBeforeLast('.')
            val parentRelative = entry.relativePath.substringBeforeLast('/', "")
            val targetName = "$baseName.$MUXED_EXTENSION"
            val targetRelative = if (parentRelative.isBlank()) targetName else "$parentRelative/$targetName"
            val existingTarget = existingFiles.entries
                .firstOrNull { (relativePath, _) -> relativePath.equals(targetRelative, ignoreCase = true) }
                ?.value
            if (existingTarget != null) {
                // 同目录已有同名 m4a（新下载或上次迁移的产物）：旧裸流无法定位，直接清掉避免重复音轨。
                if (existingTarget.sizeBytes > 0L && storage.delete(entry.reference)) migrated++
                return@forEach
            }
            val header = runCatching {
                storage.openInput(entry.reference).use { input ->
                    ByteArray(HEADER_PROBE_SIZE).also { buffer -> input.read(buffer) }
                }
            }.getOrNull() ?: return@forEach
            val ok = when {
                isAdtsHeader(header) -> migrateAdts(entry, targetName, parentRelative, rootDir, storage, stagingDir)
                isMp4Header(header) -> renameLegacyExtension(entry, targetName, parentRelative, rootDir, storage)
                else -> false
            }
            if (ok) migrated++
        }
        migrated
    }

    private suspend fun migrateAdts(
        entry: DownloadStorageEntry,
        targetName: String,
        parentRelative: String,
        rootDir: String,
        storage: DownloadStorageGateway,
        stagingDir: File,
    ): Boolean {
        val source = File(stagingDir, "legacy_${entry.displayName.hashCode()}.$LEGACY_EXTENSION")
        val target = File(stagingDir, "legacy_${entry.displayName.hashCode()}.$MUXED_EXTENSION")
        val ok = runCatching {
            storage.openInput(entry.reference).use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            AdtsToMp4Muxer.convert(source, target)
            val targetReference = writeTarget(targetName, parentRelative, rootDir, storage) { output ->
                target.inputStream().use { input -> input.copyTo(output) }
            } ?: return@runCatching false
            if (!storage.delete(entry.reference)) {
                // 旧文件删不掉就回滚新文件，避免同一首出现两条音轨。
                storage.delete(targetReference)
                return@runCatching false
            }
            true
        }.onFailure {
            Log.w(TAG, "adts migration failed: ${entry.relativePath} ${it.message}")
        }.getOrDefault(false)
        source.delete()
        target.delete()
        return ok
    }

    private suspend fun renameLegacyExtension(
        entry: DownloadStorageEntry,
        targetName: String,
        parentRelative: String,
        rootDir: String,
        storage: DownloadStorageGateway,
    ): Boolean {
        return runCatching {
            val targetReference = writeTarget(targetName, parentRelative, rootDir, storage) { output ->
                storage.openInput(entry.reference).use { input -> input.copyTo(output) }
            } ?: return@runCatching false
            if (!storage.delete(entry.reference)) {
                storage.delete(targetReference)
                return@runCatching false
            }
            true
        }.onFailure {
            Log.w(TAG, "aac rename failed: ${entry.relativePath} ${it.message}")
        }.getOrDefault(false)
    }

    /** 把转封装结果写回下载目录（文件目录或 SAF 文档树），返回新文件引用；失败返回 null。 */
    private suspend fun writeTarget(
        targetName: String,
        parentRelative: String,
        rootDir: String,
        storage: DownloadStorageGateway,
        write: suspend (OutputStream) -> Unit,
    ): String? {
        val parentReference = storage.resolveDirectory(rootDir, parentRelative)
        val targetReference = storage.ensureFile(parentReference, targetName, "audio/mp4")
        storage.openOutput(targetReference).use { output -> write(output) }
        return targetReference
    }

    private fun isAdtsHeader(header: ByteArray): Boolean {
        return header.size >= 2 &&
            (header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xFF and 0xF0) == 0xF0
    }

    private fun isMp4Header(header: ByteArray): Boolean {
        return header.size >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
    }

    private const val TAG = "AdtsMigration"
}
