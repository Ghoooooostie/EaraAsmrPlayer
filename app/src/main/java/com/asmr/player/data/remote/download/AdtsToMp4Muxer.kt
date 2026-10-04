package com.asmr.player.data.remote.download

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 把从 TS 分片抽取的 ADTS AAC（裸 AAC 拼接）转封装为 MP4(M4A) 容器。
 *
 * 原始 `.aac` 是裸 ADTS 流，没有容器索引；当文件通过不报告流长度的来源（如 SAF 外部存储）
 * 播放时，ExoPlayer 无法构建可定位的 SeekMap，导致时长未知、进度条禁用、无法拖动/点击歌词跳转。
 * MP4 容器自带 moov 采样表，时长与定位不再依赖底层数据源能否报告长度，从根本上解决该问题。
 */
internal object AdtsToMp4Muxer {

    // ISO/IEC 14496-3 采样率索引表
    private val SAMPLING_FREQ_TABLE = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350, 0, 0, 0
    )

    private val UNITY_MATRIX: ByteArray by lazy {
        val ints = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
        val b = ByteArray(36)
        for (i in ints.indices) {
            val v = u32(ints[i])
            System.arraycopy(v, 0, b, i * 4, 4)
        }
        b
    }

    /**
     * 解析 [adtsFile]（ADTS AAC 字节流），将其裸 AAC 访问单元写入 MP4 容器 [outFile]。
     * 返回写入的裸音频数据总字节数；若输入不是合法 ADTS（解析不到任何帧），抛出异常交由调用方回退。
     */
    fun convert(adtsFile: File, outFile: File): Long {
        val rawTmp = File(adtsFile.parentFile, adtsFile.name + ".raw.tmp")
        rawTmp.delete()

        val sizes = ArrayList<Int>()
        var maxFrame = 0
        var sampleRate = 44100
        var channelCount = 2
        var audioObjectType = 2
        var samplingFrequencyIndex = 4
        var firstConfigCaptured = false
        var totalRaw = 0L

        rawTmp.outputStream().buffered().use { rawOut ->
            val buffer = ByteArray(64 * 1024)
            var carry = ByteArray(0)
            var carryPos = 0
            var n = 0
            FileInputStream(adtsFile).buffered().use { input ->
                while (input.read(buffer).also { n = it } != -1) {
                    // 拼接上一次残留 + 新读取的数据
                    val merged = ByteArray((carry.size - carryPos) + n)
                    System.arraycopy(carry, carryPos, merged, 0, carry.size - carryPos)
                    System.arraycopy(buffer, 0, merged, carry.size - carryPos, n)
                    carry = merged
                    carryPos = 0

                    var progress = true
                    while (progress) {
                        progress = false
                        val remaining = carry.size - carryPos
                        if (remaining < 7) break
                        val syncIdx = findSyncFrom(carry, carryPos)
                        if (syncIdx < 0) {
                            carryPos = carry.size
                            break
                        }
                        if (syncIdx > carryPos) {
                            carryPos = syncIdx
                            if (carry.size - carryPos < 7) break
                        }
                        val frameLength = adtsFrameLength(carry, carryPos)
                        val protectionAbsent = carry[carryPos + 1].toInt() and 0xFF and 0x01
                        val headerSize = if (protectionAbsent == 1) 7 else 9
                        if (frameLength < headerSize + 1) {
                            carryPos += 1
                            progress = true
                            continue
                        }
                        if (frameLength > carry.size - carryPos) {
                            // 帧不完整，等待更多数据
                            break
                        }
                        if (!firstConfigCaptured) {
                            firstConfigCaptured = true
                            samplingFrequencyIndex = (carry[carryPos + 2].toInt() and 0xFF ushr 2) and 0x0F
                            channelCount = ((carry[carryPos + 2].toInt() and 0xFF and 0x01) shl 2) or
                                ((carry[carryPos + 3].toInt() and 0xFF ushr 6) and 0x03)
                            audioObjectType = ((carry[carryPos + 2].toInt() and 0xFF ushr 6) and 0x03) + 1
                            sampleRate = SAMPLING_FREQ_TABLE.getOrElse(samplingFrequencyIndex) { 44100 }
                        }
                        val rawLen = frameLength - headerSize
                        rawOut.write(carry, carryPos + headerSize, rawLen)
                        sizes.add(rawLen)
                        if (rawLen > maxFrame) maxFrame = rawLen
                        totalRaw += rawLen
                        carryPos += frameLength
                        progress = true
                    }
                }
            }
        }

        if (sizes.isEmpty() || !firstConfigCaptured) {
            rawTmp.copyTo(outFile, overwrite = true)
            rawTmp.delete()
            throw HlsDownloadException("无法从音频数据中解析 ADTS 帧")
        }

        val sampleCount = sizes.size
        val samplesPerFrame = 1024
        val timescale = sampleRate
        val duration = sampleCount.toLong() * samplesPerFrame

        val avgBitrate = if (duration > 0) (totalRaw * 8L * timescale / duration).toInt() else 0
        val maxBitrate = if (duration > 0) (maxFrame.toLong() * 8L * timescale / samplesPerFrame).toInt() else 0

        val ftypSize = 28
        val moovWithZero = buildMoov(
            sampleCount = sampleCount,
            sizes = sizes,
            timescale = timescale,
            duration = duration,
            sampleRate = sampleRate,
            channelCount = channelCount,
            audioObjectType = audioObjectType,
            samplingFrequencyIndex = samplingFrequencyIndex,
            maxFrameSize = maxFrame,
            avgBitrate = avgBitrate,
            maxBitrate = maxBitrate,
            chunkOffset = 0
        )
        // moov 大小与 chunkOffset 取值无关（固定 4 字节字段），据此算出 mdat 数据起始偏移后重建。
        val mdatPayloadOffset = ftypSize + moovWithZero.size + 8L
        val moov = buildMoov(
            sampleCount = sampleCount,
            sizes = sizes,
            timescale = timescale,
            duration = duration,
            sampleRate = sampleRate,
            channelCount = channelCount,
            audioObjectType = audioObjectType,
            samplingFrequencyIndex = samplingFrequencyIndex,
            maxFrameSize = maxFrame,
            avgBitrate = avgBitrate,
            maxBitrate = maxBitrate,
            chunkOffset = mdatPayloadOffset
        )

        outFile.outputStream().buffered().use { out ->
            out.write(ftypBox())
            out.write(moov)
            val mdatSize = 8 + totalRaw
            out.write(u32(mdatSize))
            out.write("mdat".toByteArray(Charsets.US_ASCII))
            rawTmp.inputStream().buffered().use { it.copyTo(out) }
        }
        rawTmp.delete()
        return totalRaw
    }

    private fun findSyncFrom(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 1 < data.size) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF and 0xF0) == 0xF0) {
                return i
            }
            i++
        }
        return -1
    }

    private fun adtsFrameLength(data: ByteArray, pos: Int): Int {
        return ((data[pos + 3].toInt() and 0xFF and 0x03) shl 11) or
            ((data[pos + 4].toInt() and 0xFF) shl 3) or
            ((data[pos + 5].toInt() and 0xFF ushr 5) and 0x07)
    }

    // ---- MP4 盒子构造 ----

    private fun u32(v: Long): ByteArray {
        val x = v and 0xFFFFFFFFL
        return byteArrayOf(
            ((x ushr 24) and 0xFF).toByte(),
            ((x ushr 16) and 0xFF).toByte(),
            ((x ushr 8) and 0xFF).toByte(),
            (x and 0xFF).toByte()
        )
    }

    private fun u16(v: Int): ByteArray {
        return byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArrayOutputStream()
        out.write(u32(size.toLong()))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun ftypBox(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(28))
        out.write("ftyp".toByteArray(Charsets.US_ASCII))
        out.write("M4A ".toByteArray(Charsets.US_ASCII))
        out.write(u32(0))
        out.write("M4A ".toByteArray(Charsets.US_ASCII))
        out.write("mp42".toByteArray(Charsets.US_ASCII))
        out.write("isom".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun mvhdBox(timescale: Int, duration: Long): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(u32(0))
        p.write(u32(0)) // creation_time
        p.write(u32(0)) // modification_time
        p.write(u32(timescale.toLong()))
        p.write(u32(duration))
        p.write(u32(0x00010000)) // rate
        p.write(u16(0x0100)) // volume
        p.write(u16(0)) // reserved
        p.write(ByteArray(8)) // reserved
        p.write(UNITY_MATRIX)
        p.write(ByteArray(24)) // pre_defined
        p.write(u32(2)) // next_track_ID
        return box("mvhd", p.toByteArray())
    }

    private fun tkhdBox(duration: Long): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(u32(7)) // version 0, flags 0x000007
        p.write(u32(0)) // creation_time
        p.write(u32(0)) // modification_time
        p.write(u32(1)) // track_ID
        p.write(u32(0)) // reserved
        p.write(u32(duration))
        p.write(ByteArray(8)) // reserved
        p.write(u16(0)) // layer
        p.write(u16(0)) // alternate_group
        p.write(u16(0x0100)) // volume
        p.write(u16(0)) // reserved
        p.write(UNITY_MATRIX)
        p.write(u32(0)) // width
        p.write(u32(0)) // height
        return box("tkhd", p.toByteArray())
    }

    private fun mdhdBox(timescale: Int, duration: Long): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(u32(0))
        p.write(u32(0)) // creation_time
        p.write(u32(0)) // modification_time
        p.write(u32(timescale.toLong()))
        p.write(u32(duration))
        p.write(u16(0x55C4)) // language 'und'
        p.write(u16(0)) // pre_defined
        return box("mdhd", p.toByteArray())
    }

    private fun hdlrBox(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(u32(0))
        p.write(u32(0)) // pre_defined
        p.write("soun".toByteArray(Charsets.US_ASCII))
        p.write(ByteArray(12)) // reserved
        p.write("SoundHandler\u0000".toByteArray(Charsets.US_ASCII))
        return box("hdlr", p.toByteArray())
    }

    private fun smhdBox(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(u32(0))
        p.write(u16(0)) // balance
        p.write(u16(0)) // reserved
        return box("smhd", p.toByteArray())
    }

    private fun dinfBox(): ByteArray {
        val url = box("url ", byteArrayOf(0, 0, 0, 1)) // self-contained
        val dref = ByteArrayOutputStream()
        dref.write(u32(0))
        dref.write(u32(1)) // entry_count
        dref.write(url)
        val dinf = ByteArrayOutputStream()
        dinf.write(box("dref", dref.toByteArray()))
        return box("dinf", dinf.toByteArray())
    }

    private fun stsdBox(sampleRate: Int, channelCount: Int, esds: ByteArray): ByteArray {
        val mp4a = ByteArrayOutputStream()
        mp4a.write(ByteArray(6)) // reserved
        mp4a.write(u16(1)) // data_reference_index
        mp4a.write(ByteArray(8)) // reserved[2]
        mp4a.write(u16(channelCount))
        mp4a.write(u16(16)) // samplesize
        mp4a.write(u16(0)) // pre_defined
        mp4a.write(u16(0)) // reserved
        mp4a.write(u32((sampleRate shl 16).toLong())) // sampling_frequency (16.16)
        mp4a.write(esds)
        val stsd = ByteArrayOutputStream()
        stsd.write(u32(0))
        stsd.write(u32(1)) // entry_count
        stsd.write(box("mp4a", mp4a.toByteArray()))
        return box("stsd", stsd.toByteArray())
    }

    private fun sttsBox(sampleCount: Int, samplesPerFrame: Int): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(u32(0))
        p.write(u32(1)) // entry_count
        p.write(u32(sampleCount))
        p.write(u32(samplesPerFrame))
        return box("stts", p.toByteArray())
    }

    private fun stscBox(sampleCount: Int): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(u32(0))
        p.write(u32(1)) // entry_count
        p.write(u32(1)) // first_chunk
        p.write(u32(sampleCount)) // samples_per_chunk
        p.write(u32(1)) // sample_desc_index
        return box("stsc", p.toByteArray())
    }

    private fun stszBox(sizes: ArrayList<Int>): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(u32(0))
        p.write(u32(0)) // sample_size
        p.write(u32(sizes.size)) // sample_count
        for (s in sizes) p.write(u32(s.toLong()))
        return box("stsz", p.toByteArray())
    }

    private fun stcoBox(chunkOffset: Long): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(u32(0))
        p.write(u32(1)) // entry_count
        p.write(u32(chunkOffset))
        return box("stco", p.toByteArray())
    }

    private fun esdsBox(
        audioObjectType: Int,
        samplingFrequencyIndex: Int,
        channelCount: Int,
        maxFrameSize: Int,
        avgBitrate: Int,
        maxBitrate: Int
    ): ByteArray {
        val asc = byteArrayOf(
            ((audioObjectType shl 3) or (samplingFrequencyIndex ushr 1)).toByte(),
            (((samplingFrequencyIndex and 1) shl 7) or (channelCount shl 3)).toByte()
        )
        val decSpecificInfo = descriptor(0x05, asc)
        val slConfig = descriptor(0x06, byteArrayOf(0x02))
        val decoderConfig = ByteArrayOutputStream()
        decoderConfig.write(0x40) // objectTypeIndication = AAC
        decoderConfig.write(0x14) // streamType(0x05)<<2 | upstream(0) | reserved(0)
        decoderConfig.write(byteArrayOf(
            ((maxFrameSize ushr 16) and 0xFF).toByte(),
            ((maxFrameSize ushr 8) and 0xFF).toByte(),
            (maxFrameSize and 0xFF).toByte()
        ))
        decoderConfig.write(u32(maxBitrate.toLong()))
        decoderConfig.write(u32(avgBitrate.toLong()))
        decoderConfig.write(decSpecificInfo)
        decoderConfig.write(slConfig)
        val decoderConfigDesc = descriptor(0x04, decoderConfig.toByteArray())

        val esPayload = ByteArrayOutputStream()
        esPayload.write(u16(1)) // ES_ID
        esPayload.write(0x00) // flags
        esPayload.write(decoderConfigDesc)

        val payload = ByteArrayOutputStream()
        payload.write(u32(0)) // version + flags
        payload.write(descriptor(0x03, esPayload.toByteArray()))
        return box("esds", payload.toByteArray())
    }

    private fun descriptor(tag: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        writeDescriptorLength(payload.size, out)
        out.write(payload)
        return out.toByteArray()
    }

    private fun writeDescriptorLength(len: Int, out: ByteArrayOutputStream) {
        if (len < 0x80) {
            out.write(len)
            return
        }
        var l = len
        val bytes = ArrayList<Byte>()
        bytes.add((l and 0x7F).toByte())
        l = l ushr 7
        while (l > 0) {
            bytes.add(0, (((l and 0x7F) or 0x80)).toByte())
            l = l ushr 7
        }
        for (b in bytes) out.write(b.toInt() and 0xFF)
    }

    private fun buildMoov(
        sampleCount: Int,
        sizes: ArrayList<Int>,
        timescale: Int,
        duration: Long,
        sampleRate: Int,
        channelCount: Int,
        audioObjectType: Int,
        samplingFrequencyIndex: Int,
        maxFrameSize: Int,
        avgBitrate: Int,
        maxBitrate: Int,
        chunkOffset: Long
    ): ByteArray {
        val esds = esdsBox(
            audioObjectType = audioObjectType,
            samplingFrequencyIndex = samplingFrequencyIndex,
            channelCount = channelCount,
            maxFrameSize = maxFrameSize,
            avgBitrate = avgBitrate,
            maxBitrate = maxBitrate
        )
        val stsd = stsdBox(sampleRate = sampleRate, channelCount = channelCount, esds = esds)
        val stts = sttsBox(sampleCount = sampleCount, samplesPerFrame = 1024)
        val stsc = stscBox(sampleCount = sampleCount)
        val stsz = stszBox(sizes = sizes)
        val stco = stcoBox(chunkOffset = chunkOffset)

        val stbl = ByteArrayOutputStream()
        stbl.write(stsd); stbl.write(stts); stbl.write(stsc); stbl.write(stsz); stbl.write(stco)
        val minf = ByteArrayOutputStream()
        minf.write(smhdBox()); minf.write(dinfBox()); minf.write(box("stbl", stbl.toByteArray()))
        val mdia = ByteArrayOutputStream()
        mdia.write(mdhdBox(timescale = timescale, duration = duration))
        mdia.write(hdlrBox())
        mdia.write(box("minf", minf.toByteArray()))
        val trak = ByteArrayOutputStream()
        trak.write(tkhdBox(duration = duration))
        trak.write(box("mdia", mdia.toByteArray()))
        val moov = ByteArrayOutputStream()
        moov.write(mvhdBox(timescale = timescale, duration = duration))
        moov.write(box("trak", trak.toByteArray()))
        return box("moov", moov.toByteArray())
    }
}
