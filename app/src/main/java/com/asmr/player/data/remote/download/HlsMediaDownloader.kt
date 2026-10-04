package com.asmr.player.data.remote.download

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** HLS 下载过程中无法继续（加密流、分片 MP4、无音频轨等）。 */
class HlsDownloadException(message: String) : IOException(message)

/** HLS 下载被中断（worker 停止），携带已写入字节数以便下次从头重下。 */
class HlsTransferStoppedException(val downloadedBytes: Long) : IOException("hls transfer stopped")

/**
 * HLS（m3u8）播放列表的最小解析实现：只覆盖下载所需的媒体播放列表 / 主播放列表选择，
 * 站点常见的 VOD TS 分片（OtomeKoe 等）可直接落到单个音频文件。
 */
internal object HlsPlaylist {

    fun isPlaylistUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').trim()
        return path.endsWith(".m3u8", ignoreCase = true)
    }

    fun isMasterPlaylist(text: String): Boolean {
        return text.contains("#EXT-X-STREAM-INF", ignoreCase = true)
    }

    /** 选择码率最高的变体（主播放列表）。 */
    fun selectVariantUrl(playlistUrl: String, text: String): String? {
        var bestBandwidth = -1L
        var bestUri: String? = null
        var pendingBandwidth = -1L
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    pendingBandwidth = attributeValue(line, "AVERAGE-BANDWIDTH")?.toLongOrNull()
                        ?: attributeValue(line, "BANDWIDTH")?.toLongOrNull()
                        ?: 0L
                }
                return@forEach
            }
            if (pendingBandwidth >= bestBandwidth) {
                bestBandwidth = pendingBandwidth
                bestUri = resolveUri(playlistUrl, line)
            }
            pendingBandwidth = -1L
        }
        return bestUri
    }

    /** 解析媒体播放列表的分片地址（已解析为绝对地址）。 */
    fun parseSegmentUrls(playlistUrl: String, text: String): List<String> {
        val segments = ArrayList<String>()
        var encrypted = false
        var usesFragmentedMp4 = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    if (!line.contains("METHOD=NONE", ignoreCase = true)) encrypted = true
                }
                line.startsWith("#EXT-X-MAP", ignoreCase = true) -> usesFragmentedMp4 = true
                line.startsWith("#") -> Unit
                else -> segments += resolveUri(playlistUrl, line)
            }
        }
        if (segments.isEmpty()) throw HlsDownloadException("HLS 播放列表中没有可用分片")
        if (encrypted) throw HlsDownloadException("该在线音频已加密，暂不支持下载")
        if (usesFragmentedMp4) throw HlsDownloadException("该在线音频为分片 MP4，暂不支持下载")
        return segments
    }

    /** 分片是否需要从 MPEG-TS 中取出 AAC 数据（.m4s/.mp4 无法简单拼接）。 */
    fun requiresTsDemux(segmentUrl: String): Boolean {
        val path = segmentUrl.substringBefore('?').substringBefore('#')
        if (path.endsWith(".m4s", ignoreCase = true) || path.endsWith(".mp4", ignoreCase = true)) {
            throw HlsDownloadException("该在线音频为分片 MP4，暂不支持下载")
        }
        return path.endsWith(".ts", ignoreCase = true)
    }

    fun resolveUri(playlistUrl: String, uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        val base = playlistUrl.substringBefore('?').substringBefore('#')
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (trimmed.startsWith("/")) {
            val schemeEnd = base.indexOf("://")
            if (schemeEnd < 0) return trimmed
            val hostStart = schemeEnd + 3
            val hostEnd = base.indexOf('/', hostStart).let { if (it < 0) base.length else it }
            return base.substring(0, hostEnd) + trimmed
        }
        return base.substringBeforeLast('/') + "/" + trimmed
    }

    private fun attributeValue(line: String, name: String): String? {
        val nameIndex = line.indexOf(name, ignoreCase = true)
        if (nameIndex < 0) return null
        val eqIndex = line.indexOf('=', nameIndex)
        if (eqIndex < 0) return null
        var start = eqIndex + 1
        if (start < line.length && line[start] == '"') start++
        var end = start
        while (end < line.length && line[end] != ',' && line[end] != '"') end++
        return line.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * 把 HLS 流下载为单个音频文件：
 * - TS 分片（AAC/ADTS）逐包取出 PES 载荷，拼接成可直接播放的 .aac；
 * - 非 TS 分片（裸 AAC 等）直接按序拼接。
 */
internal class HlsMediaDownloader(private val client: OkHttpClient) {

    suspend fun downloadTo(
        playlistUrl: String,
        referer: String?,
        outputFile: File,
        shouldStop: () -> Boolean,
        onProgress: suspend (downloadedBytes: Long, deltaBytes: Long) -> Unit
    ): Long {
        val firstText = fetchText(playlistUrl, referer)
        val segments = if (HlsPlaylist.isMasterPlaylist(firstText)) {
            val variantUrl = HlsPlaylist.selectVariantUrl(playlistUrl, firstText)
                ?: throw HlsDownloadException("无法解析 HLS 播放列表")
            HlsPlaylist.parseSegmentUrls(variantUrl, fetchText(variantUrl, referer))
        } else {
            HlsPlaylist.parseSegmentUrls(playlistUrl, firstText)
        }
        val demuxTs = HlsPlaylist.requiresTsDemux(segments.first())
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists() && !outputFile.delete()) {
            throw HlsDownloadException("无法写入下载文件")
        }
        // 先把抽取出的 ADTS 写到临时文件，再统一转封装为 MP4（M4A），保证可定位 / 可显示时长。
        val adtsFile = File(outputFile.parentFile, outputFile.name + ".adts.tmp")
        adtsFile.delete()
        val demuxer = TsAdtsDemuxer()
        var downloaded = 0L
        adtsFile.outputStream().buffered(SEGMENT_BUFFER_SIZE).use { output ->
            segments.forEachIndexed { index, segmentUrl ->
                if (shouldStop()) throw HlsTransferStoppedException(downloaded)
                val raw = fetchBytes(segmentUrl, referer)
                val written = if (demuxTs) {
                    demuxer.writeTo(raw, output)
                } else {
                    output.write(raw)
                    raw.size.toLong()
                }
                downloaded += written
                if (index == 0 && written <= 0L) {
                    throw HlsDownloadException("无法从在线音频中提取音频数据")
                }
                onProgress(downloaded, written)
            }
        }
        if (downloaded <= 0L) throw HlsDownloadException("在线音频下载结果为空")
        try {
            AdtsToMp4Muxer.convert(adtsFile, outputFile)
        } catch (e: Exception) {
            // 非 ADTS（极少出现）：退回直接拷贝原始字节，避免下载失败。
            adtsFile.copyTo(outputFile, overwrite = true)
        } finally {
            adtsFile.delete()
        }
        return downloaded
    }

    private suspend fun fetchText(url: String, referer: String?): String {
        return fetchBytes(url, referer).toString(Charsets.UTF_8)
    }

    private suspend fun fetchBytes(url: String, referer: String?): ByteArray {
        var lastError: IOException? = null
        var attempt = 0
        while (attempt < MAX_FETCH_ATTEMPTS) {
            try {
                return executeGet(url, referer)
            } catch (e: IOException) {
                lastError = e
                attempt++
                if (attempt < MAX_FETCH_ATTEMPTS) delay(RETRY_DELAY_MS * attempt)
            }
        }
        throw lastError ?: IOException("无法下载 $url")
    }

    private fun executeGet(url: String, referer: String?): ByteArray {
        val builder = Request.Builder()
            .url(url)
            .get()
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            return body.bytes()
        }
    }

    private companion object {
        const val MAX_FETCH_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 800L
        const val SEGMENT_BUFFER_SIZE = 64 * 1024
    }
}

/**
 * MPEG-TS → AAC/ADTS 提取器。站点音频流为纯音频 TS（PAT/PMT + 一条 AAC 轨），
 * 逐包取出该轨的 PES 载荷即可得到连续合法的 ADTS 流。
 */
internal class TsAdtsDemuxer {

    private var audioPid = -1
    private val pmtPids = HashSet<Int>()

    fun writeTo(data: ByteArray, output: OutputStream): Long {
        if (audioPid < 0) audioPid = detectAudioPid(data)
        if (audioPid < 0) throw HlsDownloadException("无法定位在线音频的音频轨")
        var written = 0L
        var offset = 0
        while (offset + TS_PACKET_SIZE <= data.size) {
            if ((data[offset].toInt() and 0xFF) != TS_SYNC_BYTE.toInt()) {
                offset += TS_PACKET_SIZE
                continue
            }
            val second = data[offset + 1].toInt() and 0xFF
            val third = data[offset + 2].toInt() and 0xFF
            val fourth = data[offset + 3].toInt() and 0xFF
            val pid = ((second and 0x1F) shl 8) or third
            val payloadUnitStart = (second and 0x40) != 0
            val hasPayload = (fourth and 0x10) != 0
            val hasAdaptation = (fourth and 0x20) != 0
            val end = offset + TS_PACKET_SIZE
            var start = offset + 4
            if (hasAdaptation) start += 1 + (data[offset + 4].toInt() and 0xFF)
            offset = end
            if (!hasPayload || pid != audioPid || start >= end) continue
            var payloadStart = start
            if (payloadUnitStart) {
                if (start + 9 > end || !isPesPrefix(data, start)) continue
                payloadStart = start + 9 + (data[start + 8].toInt() and 0xFF)
                if (payloadStart >= end) continue
            }
            val length = end - payloadStart
            output.write(data, payloadStart, length)
            written += length.toLong()
        }
        return written
    }

    private fun detectAudioPid(data: ByteArray): Int {
        val candidates = HashMap<Int, Int>()
        var offset = 0
        while (offset + TS_PACKET_SIZE <= data.size) {
            if ((data[offset].toInt() and 0xFF) != TS_SYNC_BYTE.toInt()) {
                offset += TS_PACKET_SIZE
                continue
            }
            val second = data[offset + 1].toInt() and 0xFF
            val third = data[offset + 2].toInt() and 0xFF
            val fourth = data[offset + 3].toInt() and 0xFF
            val pid = ((second and 0x1F) shl 8) or third
            val payloadUnitStart = (second and 0x40) != 0
            val hasPayload = (fourth and 0x10) != 0
            val hasAdaptation = (fourth and 0x20) != 0
            val end = offset + TS_PACKET_SIZE
            var start = offset + 4
            if (hasAdaptation) start += 1 + (data[offset + 4].toInt() and 0xFF)
            offset = end
            if (!hasPayload || start >= end) continue
            when {
                pid == 0 -> if (payloadUnitStart) parsePat(data, start, end)
                pid in pmtPids -> {
                    if (payloadUnitStart) parsePmt(data, start, end)
                    if (audioPid >= 0) return audioPid
                }
                payloadUnitStart && start + 4 <= end && isAudioPesPrefix(data, start) ->
                    candidates[pid] = (candidates[pid] ?: 0) + 1
            }
        }
        return audioPid.takeIf { it >= 0 } ?: candidates.maxByOrNull { it.value }?.key ?: -1
    }

    private fun parsePat(data: ByteArray, start: Int, end: Int) {
        val sectionStart = start + 1 + (data[start].toInt() and 0xFF)
        if (sectionStart + 3 > end) return
        val sectionEnd = sectionEnd(data, sectionStart, end)
        var cursor = sectionStart + 8
        while (cursor + 4 <= sectionEnd) {
            val program = ((data[cursor].toInt() and 0xFF) shl 8) or (data[cursor + 1].toInt() and 0xFF)
            val pmtPid = ((data[cursor + 2].toInt() and 0x1F) shl 8) or (data[cursor + 3].toInt() and 0xFF)
            if (program != 0 && pmtPid != 0x1FFF) pmtPids += pmtPid
            cursor += 4
        }
    }

    private fun parsePmt(data: ByteArray, start: Int, end: Int) {
        val sectionStart = start + 1 + (data[start].toInt() and 0xFF)
        if (sectionStart + 12 > end) return
        val sectionEnd = sectionEnd(data, sectionStart, end)
        val programInfoLength = ((data[sectionStart + 10].toInt() and 0x0F) shl 8) or
            (data[sectionStart + 11].toInt() and 0xFF)
        var cursor = sectionStart + 12 + programInfoLength
        while (cursor + 5 <= sectionEnd) {
            val streamType = data[cursor].toInt() and 0xFF
            val elementaryPid = ((data[cursor + 1].toInt() and 0x1F) shl 8) or
                (data[cursor + 2].toInt() and 0xFF)
            val esInfoLength = ((data[cursor + 3].toInt() and 0x0F) shl 8) or
                (data[cursor + 4].toInt() and 0xFF)
            if (streamType in AUDIO_STREAM_TYPES) {
                audioPid = elementaryPid
                return
            }
            cursor += 5 + esInfoLength
        }
    }

    private fun sectionEnd(data: ByteArray, sectionStart: Int, end: Int): Int {
        val length = ((data[sectionStart + 1].toInt() and 0x0F) shl 8) or
            (data[sectionStart + 2].toInt() and 0xFF)
        return (sectionStart + 3 + length - 4).coerceAtMost(end)
    }

    private fun isPesPrefix(data: ByteArray, start: Int): Boolean {
        return (data[start].toInt() and 0xFF) == 0x00 &&
            (data[start + 1].toInt() and 0xFF) == 0x00 &&
            (data[start + 2].toInt() and 0xFF) == 0x01
    }

    private fun isAudioPesPrefix(data: ByteArray, start: Int): Boolean {
        if (!isPesPrefix(data, start)) return false
        val streamId = data[start + 3].toInt() and 0xFF
        return streamId in 0xC0..0xDF
    }

    private companion object {
        const val TS_PACKET_SIZE = 188
        const val TS_SYNC_BYTE = 0x47
        val AUDIO_STREAM_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)
    }
}
