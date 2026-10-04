package com.asmr.player.data.remote.download

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADTS → MP4 转封装的结构校验。
 * 裸 ADTS 没有采样表，播放时无法定位（进度条不可拖 / 点歌词不跳转），
 * 因此这里锁死「moov 在前、单 chunk、stco 指向 mdat 载荷」的输出结构。
 */
class AdtsToMp4MuxerTest {

    @Test
    fun convert_writesFrontLoadedMoovWithSingleChunkPointingAtMdatPayload() {
        val dir = Files.createTempDirectory("adts2mp4").toFile()
        val adts = File(dir, "src.aac")
        val out = File(dir, "out.m4a")
        val frames = 7
        val payload = 11
        adts.writeBytes(buildAdtsStream(frames = frames, payloadSize = payload))

        val rawBytes = AdtsToMp4Muxer.convert(adts, out)

        val bytes = out.readBytes()
        val boxes = topLevelBoxes(bytes)
        assertEquals(listOf("ftyp", "moov", "mdat"), boxes.map { it.type })
        val ftyp = boxes[0]
        val moov = boxes[1]
        val mdat = boxes[2]
        // ftyp(28) + moov + mdat 头(8)
        assertEquals(ftyp.size.toLong() + moov.size.toLong() + 8L + frames * payload, bytes.size.toLong())
        assertEquals(frames * payload.toLong(), rawBytes)

        val moovBytes = bytes.copyOfRange(ftyp.size, ftyp.size + moov.size)
        val mvhd = requireBox(moovBytes, "mvhd")
        // payload: version/flags(4) + creation(4) + modification(4) + timescale(4) + duration(4)
        assertEquals(44100L, payloadU32(mvhd, 12))
        assertEquals(frames * 1024L, payloadU32(mvhd, 16))
        val trak = requireBox(moovBytes, "trak")
        val mdia = requireBox(trak, "mdia")
        val mdhd = requireBox(mdia, "mdhd")
        assertEquals(44100L, payloadU32(mdhd, 12))
        assertEquals(frames * 1024L, payloadU32(mdhd, 16))
        val minf = requireBox(mdia, "minf")
        val stbl = requireBox(minf, "stbl")
        val stsz = requireBox(stbl, "stsz")
        assertEquals(frames.toLong(), payloadU32(stsz, 8)) // version/flags + sample_size
        val stts = requireBox(stbl, "stts")
        assertEquals(1L, payloadU32(stts, 4)) // version/flags(4) + entry_count
        assertEquals(frames.toLong(), payloadU32(stts, 8)) // sample_count
        assertEquals(1024L, payloadU32(stts, 12)) // sample_delta
        val stco = requireBox(stbl, "stco")
        assertEquals(1L, payloadU32(stco, 4)) // entry_count
        assertEquals(ftyp.size.toLong() + moov.size.toLong() + 8L, payloadU32(stco, 8))

        // mdat 载荷即各帧的裸 AAC 数据
        val mdatPayload = bytes.copyOfRange(ftyp.size + moov.size + 8, bytes.size)
        assertEquals((frames * payload).toLong(), mdatPayload.size.toLong())
    }

    @Test
    fun convert_rejectsInputWithoutAnyAdtsFrame() {
        val dir = Files.createTempDirectory("adts2mp4-bad").toFile()
        val adts = File(dir, "src.aac")
        val out = File(dir, "out.m4a")
        adts.writeBytes(ByteArray(64) { 0x11 })

        val error = runCatching { AdtsToMp4Muxer.convert(adts, out) }.exceptionOrNull()

        assertTrue(error is HlsDownloadException)
    }

    /** 构造 [frames] 帧、每帧 [payloadSize] 字节净荷的 ADTS 流（44.1kHz / 立体声 / AAC-LC）。 */
    private fun buildAdtsStream(frames: Int, payloadSize: Int): ByteArray {
        val frameLength = 7 + payloadSize
        val out = ByteArray(frames * frameLength)
        for (index in 0 until frames) {
            val offset = index * frameLength
            out[offset] = 0xFF.toByte()
            // syncword(4)+ID(1)+layer(2)+protection_absent(1)
            out[offset + 1] = 0xF1.toByte()
            // profile=AAC-LC(1)<<6 | sampling_frequency_index=4(44.1kHz)<<2 | private(0) | channel_config 高位(0)
            out[offset + 2] = (1 shl 6 or (4 shl 2)).toByte()
            // channel_configuration 低 2 位=2(立体声)<<6 | 版权位(0) | frame_length 高 2 位
            out[offset + 3] = ((2 shl 6) or ((frameLength ushr 11) and 0x03)).toByte()
            // frame_length 中 8 位
            out[offset + 4] = ((frameLength ushr 3) and 0xFF).toByte()
            // frame_length 低 3 位<<5 | buffer_fullness 高位
            out[offset + 5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            for (p in 0 until payloadSize) {
                out[offset + 7 + p] = (index * 31 + p).toByte()
            }
        }
        return out
    }

    private data class TopLevelBox(val type: String, val size: Int)

    private fun topLevelBoxes(bytes: ByteArray): List<TopLevelBox> {
        val result = mutableListOf<TopLevelBox>()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val size = readU32(bytes, offset).toInt()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            result += TopLevelBox(type, size)
            offset += size
        }
        return result
    }
    /** 在 [box] 负载中定位第一个 [type] 子盒子，返回其完整内容（含 8 字节头）。 */
    private fun requireBox(box: ByteArray, type: String): ByteArray {
        var offset = 8 // 跳过当前盒子自己的头
        while (offset + 8 <= box.size) {
            val size = readU32(box, offset).toInt()
            if (size < 8 || offset + size > box.size) break
            if (String(box, offset + 4, 4, Charsets.US_ASCII) == type) {
                return box.copyOfRange(offset, offset + size)
            }
            offset += size
        }
        throw AssertionError("missing box $type")
    }

    /** [box] 为含 8 字节盒头的完整盒子，[payloadOffset] 相对盒内 payload 起点。 */
    private fun payloadU32(box: ByteArray, payloadOffset: Int): Long = readU32(box, payloadOffset + 8)

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }
}
