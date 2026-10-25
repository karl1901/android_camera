package com.wheeltech.blindcam.recorder

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import com.wheeltech.blindcam.util.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 把逐帧 [Bitmap] 硬件编码为 H.264 并封装为 MP4（通用、手机/车机/电脑都能直接播放）。
 *
 * 管线：Bitmap ->(GL 纹理) MediaCodec 输入 Surface ->(硬件 H.264)-> MediaMuxer -> MP4。
 * 色彩转换与编码全部在 GPU / 硬编完成，CPU 只负责把已合成好的 Bitmap 上传纹理，
 * 因而 1080p / 4K 都能低占用录制。
 *
 * 每帧使用真实时间戳（PTS），回放时长 = 真实时长，设备实际只跑到 15fps 也不会被标记成 30fps。
 *
 * 必须在同一个线程（录像专用 HandlerThread）顺序调用 [frame] / [finish]。
 */
class Mp4Recorder(
    private val cacheFile: File,
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fpsHint: Int
) {
    companion object {
        private const val TAG = "mp4"
        private const val MIME = "video/avc"
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /** 按分辨率/帧率估算高画质 H.264 码率（bps） */
        fun suggestBitrate(w: Int, h: Int, fps: Int, minMbps: Double = 2.0): Int {
            val f = fps.coerceIn(1, 60)
            val v = (w.toLong() * h * f * 0.15).toLong()
            return v.coerceIn((minMbps * 1_000_000).toLong(), 50_000_000L).toInt()
        }

        private val VERT_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAG_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var muxerStarted = false
    private var inputSurface: Surface? = null
    private var egl: EglCore? = null
    private var eglSurface: EGLSurface? = null

    private var program = 0
    private var textureId = 0
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var uTexLoc = 0

    private val bufferInfo = MediaCodec.BufferInfo()
    private var frameCount = 0
    private var started = false
    private var startNs = -1L
    private var textureAllocated = false

    private val quadVerts: FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // 两个三角形（triangle strip）：左上、右上、左下、右下
            put(floatArrayOf(
                -1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f
            ))
            position(0)
        }
    private val texVerts: FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // 与 quadVerts（左上、右上、左下、右下）一一对应。
            // GLUtils.texImage2D 上传 Bitmap 后，bitmap 顶行位于纹理 t=0，
            // 故屏幕顶部（y=+1）采样 t=0、底部采样 t=1，画面才正立。
            put(floatArrayOf(
                0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
            ))
            position(0)
        }

    /** 送入一帧（bitmap 尺寸必须与构造的 width/height 一致）。ptsNs：帧真实到达时间（纳秒） */
    fun frame(bitmap: Bitmap, ptsNs: Long) {
        if (!started) init(bitmap)
        if (startNs < 0L) startNs = ptsNs
        val pts = (ptsNs - startNs).coerceAtLeast(0L)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (!textureAllocated) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureAllocated = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        quadVerts.position(0)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, quadVerts)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        texVerts.position(0)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texVerts)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        egl?.setPresentationTime(eglSurface!!, pts)
        egl?.swapBuffers(eglSurface!!)
        drainEncoder(false)
        frameCount++
    }

    val frames: Int get() = frameCount

    private fun init(bitmap: Bitmap) {
        require(bitmap.width == width && bitmap.height == height) {
            "帧尺寸 ${bitmap.width}x${bitmap.height} 与录制尺寸 ${width}x${height} 不一致"
        }
        require(width % 2 == 0 && height % 2 == 0) { "H.264 编码宽高需为偶数：${width}x${height}" }

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fpsHint.coerceIn(1, 60))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val c = MediaCodec.createEncoderByType(MIME)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        c.start()
        codec = c
        inputSurface = surface

        val core = EglCore()
        egl = core
        eglSurface = core.createWindowSurface(surface)
        core.makeCurrent(eglSurface!!)
        initGlProgram()
        started = true
        Logger.i(TAG, "encoder start ${width}x${height}@~${fpsHint}fps bitrate=${bitrate / 1000}kbps")
    }

    private fun initGlProgram() {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, VERT_SHADER)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAG_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vert)
            GLES20.glAttachShader(it, frag)
            GLES20.glLinkProgram(it)
        }
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("shader 编译失败: $log")
        }
        return s
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val c = codec ?: return
        if (endOfStream) {
            // 必须在最后一次 swapBuffers 之后、且 EGL 仍为当前上下文时调用
            c.signalEndOfInputStream()
        }
        var waitCount = 0
        while (true) {
            val outBufIndex = c.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // 结束时等待编码器排空，最多约 1.5s
                    if (++waitCount > 150) {
                        Logger.w(TAG, "结束等待超时，强制退出 drain")
                        return
                    }
                }
                outBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw RuntimeException("format 重复变化")
                    val newFormat = c.outputFormat
                    val m = MediaMuxer(cacheFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxerTrack = m.addTrack(newFormat)
                    m.start()
                    muxer = m
                    muxerStarted = true
                }
                outBufIndex >= 0 -> {
                    val encoded = c.getOutputBuffer(outBufIndex)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted && encoded != null) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(muxerTrack, encoded, bufferInfo)
                    }
                    c.releaseOutputBuffer(outBufIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (!endOfStream) Logger.w(TAG, "意外收到 END_OF_STREAM")
                        return
                    }
                }
            }
        }
    }

    /** 结束编码并落盘 MP4，返回文件；无帧时返回 null */
    fun finish(): File? {
        if (!started) {
            runCatching { cacheFile.delete() }
            return null
        }
        try {
            drainEncoder(true)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
            }
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            runCatching { eglSurface?.let { egl?.destroySurface(it) } }
            runCatching { egl?.release() }
            runCatching { inputSurface?.release() }
        }
        Logger.i(TAG, "encoder finish frames=$frameCount -> ${cacheFile.name}")
        return cacheFile
    }

    fun abort() {
        runCatching { codec?.stop(); codec?.release() }
        runCatching { if (muxerStarted) { muxer?.stop(); muxer?.release() } }
        runCatching { eglSurface?.let { egl?.destroySurface(it) } }
        runCatching { egl?.release() }
        runCatching { inputSurface?.release() }
        runCatching { cacheFile.delete() }
    }
}
