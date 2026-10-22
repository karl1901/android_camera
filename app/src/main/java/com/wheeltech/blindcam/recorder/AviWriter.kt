package com.wheeltech.blindcam.recorder

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 单路 MJPEG -> AVI 封装器（无重编码、低 CPU、不丢帧，车机/绝大多数播放器可播）。
 *
 * 文件布局：
 *   RIFF 'AVI '
 *     LIST 'hdrl'  (avih + strl(strh+strf)，固定大小，预留)
 *     LIST 'movi'  (每帧一个 '00dc' chunk，内容为设备输出的 JPEG)
 *     'idx1'      (每帧 16 字节索引)
 *
 * 关键尺寸字段在 [close] 时用 RandomAccessFile 回填。
 * 写入必须在单一线程顺序进行（由 RecordingManager 保证）。
 */
class AviWriter(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int
) {
    /** 一条 idx1 索引 */
    private class IdxEntry(val offset: Int, val length: Int)

    private val raf = RandomAccessFile(file, "rw")

    /** 帧条目索引（关流时写 idx1） */
    private val idx = ArrayList<IdxEntry>(1024)

    /** 已写帧数 */
    private var frameCount = 0

    /** movi LIST 数据起始偏移（帧 chunk 相对它计算偏移） */
    private val moviDataStart: Long

    private val headerSize: Int

    private val imageSize: Int = width * height * 3

    init {
        // ===== 构造固定头部（hdrl），先写占位，尺寸字段最后回填 =====
        val avih = chunk(0x68697661 /*'avih'*/, avihBytes())
        val strh = chunk(0x68727473 /*'strh'*/, strhBytes())
        val strf = chunk(0x66727473 /*'strf'*/, strfBytes())
        val strl = listChunk(0x6C727473 /*'strl'*/, strh + strf)
        val hdrl = listChunk(0x6C726468 /*'hdrl'*/, avih + strl)

        // RIFF 头 12 字节
        val riff = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        riff.put(byteArrayOf(0x52, 0x49, 0x46, 0x46)) // RIFF
        riff.putInt(0) // 回填：文件总大小 - 8
        riff.put(byteArrayOf(0x41, 0x56, 0x49, 0x20)) // 'AVI '

        val movi = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        movi.put(byteArrayOf(0x4C, 0x49, 0x53, 0x54)) // LIST
        movi.putInt(0) // 回填：movi 数据 + idx1 大小
        movi.put(byteArrayOf(0x6D, 0x6F, 0x76, 0x69)) // 'movi'

        raf.write(riff.array())
        raf.write(hdrl)
        raf.write(movi.array())

        headerSize = 12 + hdrl.size + 12
        moviDataStart = headerSize.toLong()
    }

    /** 追加一帧 JPEG（含完整 FFD8...FFD9） */
    fun addFrame(jpeg: ByteArray) {
        val chunkStart = raf.filePointer
        // 帧偏移相对 movi 数据起始（chunk 头从这里开始）
        val relOffset = (chunkStart - moviDataStart).toInt()

        val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put(byteArrayOf(0x30, 0x30, 0x64, 0x63)) // '00dc'
        hdr.putInt(jpeg.size)
        raf.write(hdr.array())
        raf.write(jpeg)
        // 奇数长度补 0x00（AVI chunk 必须偶数对齐）
        if (jpeg.size and 1 == 1) raf.writeByte(0)

        idx.add(IdxEntry(relOffset, jpeg.size))
        frameCount++
    }

    /** 回填头部、写 idx1 并关闭，返回最终文件 */
    fun finish(): File {
        val moviDataEnd = raf.filePointer
        val moviDataLen = (moviDataEnd - moviDataStart).toInt()

        // idx1
        val idxBuf = ByteBuffer.allocate(8 + idx.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        idxBuf.put(byteArrayOf(0x69, 0x64, 0x78, 0x31)) // 'idx1'
        idxBuf.putInt(idx.size * 16)
        for (e in idx) {
            idxBuf.put(byteArrayOf(0x30, 0x30, 0x64, 0x63)) // '00dc'
            idxBuf.putInt(0x10) // AVIIF_KEYFRAME
            idxBuf.putInt(e.offset)
            idxBuf.putInt(e.length)
        }
        raf.write(idxBuf.array())
        val fileEnd = raf.filePointer

        // 回填 RIFF 总大小（文件大小 - 8）
        raf.seek(4)
        writeLE32(raf, (fileEnd - 8).toInt())
        // 回填 movi LIST size（movi 数据 + idx1 大小）
        raf.seek(12L + headerSizeOffset_movi())
        writeLE32(raf, (moviDataLen + idxBuf.capacity()))
        // 回填 avih 帧数（avih 内 dwTotalFrames）
        raf.seek(avihTotalFramesOffset().toLong())
        writeLE32(raf, frameCount)
        // 回填 strh 帧数（dwLength）
        raf.seek(strhLengthOffset().toLong())
        writeLE32(raf, frameCount)

        raf.fd.sync()
        raf.close()
        return file
    }

    fun abort() {
        runCatching { raf.close() }
        runCatching { file.delete() }
    }

    val frames: Int get() = frameCount

    // ---- 头部字段偏移（基于本类固定布局） ----
    // RIFF(12) + LIST hdrl(12) + 'avih'(8) 然后 avih 结构
    private fun avihTotalFramesOffset(): Int =
        12 + 12 + 8 + (4 * 4) // avih 中前 4 个 DWORD 之后即 dwTotalFrames

    // RIFF + hdrl(avih 完整) + LIST strl(12) + strh(8) + strh 前若干字段
    private fun strhLengthOffset(): Int {
        // hdrl = listHeader(12)+avih(8+56)+strlList(12+strh(8+56)+strf(8+40))
        val avihChunk = 8 + 56
        val strhChunk = 8 + 56
        // strh body: fccType4 fccHandler4 dwFlags4 wPriority2 wLanguage2
        // dwInitialFrames4 dwScale4 dwRate4 dwStart4 dwLength4 -> dwLength 偏移 32
        return 12 + 12 + avihChunk + 12 + 8 + 32
    }

    // movi LIST size 字段偏移
    private fun headerSizeOffset_movi(): Int {
        val avihChunk = 8 + 56
        val strhChunk = 8 + 56
        val strfChunk = 8 + 40
        val hdrl = 12 + avihChunk + 12 + strhChunk + strfChunk
        return 12 + hdrl + 4 // RIFF 12 + hdrl 后 +4 跳过 LIST 四字
    }

    private fun avihBytes(): ByteArray {
        val b = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(1_000_000 / fps.coerceAtLeast(1)) // dwMicroSecPerFrame
        b.putInt(width * height * 3 * fps / 2)    // dwMaxBytesPerSec（估算）
        b.putInt(0)                               // dwPaddingGranularity
        b.putInt(0x10)                            // dwFlags = AVIF_HASINDEX
        b.putInt(0)                               // dwTotalFrames（回填）
        b.putInt(0)                               // dwInitialFrames
        b.putInt(1)                               // dwStreams
        b.putInt(imageSize)                       // dwSuggestedBufferSize
        b.putInt(width)
        b.putInt(height)
        repeat(4) { b.putInt(0) }                 // dwReserved[4]
        return b.array()
    }

    private fun strhBytes(): ByteArray {
        val b = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x76, 0x69, 0x64, 0x73)) // fccType = 'vids'
        b.put(byteArrayOf(0x4D, 0x4A, 0x50, 0x47)) // fccHandler = 'MJPG'
        b.putInt(0)                 // dwFlags
        b.putShort(0)               // wPriority
        b.putShort(0)               // wLanguage
        b.putInt(0)                 // dwInitialFrames
        b.putInt(1)                 // dwScale
        b.putInt(fps)               // dwRate
        b.putInt(0)                 // dwStart
        b.putInt(0)                 // dwLength（回填）
        b.putInt(imageSize)         // dwSuggestedBufferSize
        b.putInt(-1)                // dwQuality
        b.putInt(0)                 // dwSampleSize
        b.putShort(0); b.putShort(0) // rcFrame left/top
        b.putShort(width.toShort()); b.putShort(height.toShort()) // right/bottom
        return b.array()
    }

    private fun strfBytes(): ByteArray {
        val b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(40)                // biSize
        b.putInt(width)             // biWidth
        b.putInt(height)            // biHeight（正数=自下而上）
        b.putShort(1)               // biPlanes
        b.putShort(24)              // biBitCount
        b.put(byteArrayOf(0x4D, 0x4A, 0x50, 0x47)) // biCompression = 'MJPG'
        b.putInt(imageSize)         // biSizeImage
        b.putInt(0); b.putInt(0)    // biXPelsPerMeter / biYPelsPerMeter
        b.putInt(0); b.putInt(0)    // biClrUsed / biClrImportant
        return b.array()
    }

    private fun chunk(fourcc: Int, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(fourcc)
        b.putInt(body.size)
        b.put(body)
        return b.array()
    }

    private fun listChunk(fourcc: Int, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(12 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x4C, 0x49, 0x53, 0x54)) // LIST
        b.putInt(4 + body.size)
        b.putInt(fourcc)
        b.put(body)
        return b.array()
    }

    private fun writeLE32(raf: RandomAccessFile, v: Int) {
        raf.writeByte(v and 0xFF)
        raf.writeByte((v shr 8) and 0xFF)
        raf.writeByte((v shr 16) and 0xFF)
        raf.writeByte((v shr 24) and 0xFF)
    }
}
