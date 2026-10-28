package com.wheeltech.blindcam.ui.viewer

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.SeekBar
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.databinding.ActivityVideoPlayerBinding
import com.wheeltech.blindcam.ui.common.ThemedActivity
import com.wheeltech.blindcam.util.Logger
import java.util.Locale

/**
 * 内置全屏视频播放器（替代系统播放器）：
 * - 原生 [MediaPlayer] + [TextureView]，零第三方依赖，支持文件路径与 SAF content uri
 * - 控件：左上返回、右上名称、底部播放/暂停、进度条、快退/快进 10 秒
 * - 进入全屏先显示控件 1 秒后自动收起；点击画面任意处显示控件；无操作 2 秒后收起
 *
 * 后期回看页/时间轴可直接复用同样的控件与自动收起交互。
 */
class VideoPlayerActivity : ThemedActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityVideoPlayerBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var startWhenReady = false
    private var seekToMs = 0
    private var isSeeking = false

    /** 暂停进入全屏时：先 start 一帧再 pause，保证 MediaPlayer 把首帧渲染到 Surface */
    private var pauseAfterFirstFrame = false

    // 裁剪（回看全屏跟随某一路）：视频像素坐标；cropW<=0 表示整段
    private var cropX = 0
    private var cropY = 0
    private var cropW = 0
    private var cropH = 0

    /** 控件自动收起：进入显示 1 秒；点击画面重新显示，无操作 2 秒后收起（暂停时保持可见） */
    private val hideRunnable = Runnable { if (isPlayingNow()) hideControls() }

    /** 双击播放/暂停；单击切换工具栏显隐（与回看页一致的通用交互） */
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val visible = binding.barTop.visibility == View.VISIBLE ||
                    binding.barBottom.visibility == View.VISIBLE
                if (visible) {
                    // 用户主动点击非工具栏区域：即使暂停也隐藏
                    hideControls(force = true)
                } else {
                    showControlsThenSchedule()
                }
                return true
            }
        })
    }
    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (prepared && !isSeeking) {
                val pos = p.currentPosition
                binding.seekBar.progress = pos
                binding.tvCurrent.text = formatTime(pos)
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    private fun isPlayingNow(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvName.text = intent.getStringExtra(EXTRA_NAME).orEmpty()
        cropX = intent.getIntExtra(EXTRA_CROP_X, 0)
        cropY = intent.getIntExtra(EXTRA_CROP_Y, 0)
        cropW = intent.getIntExtra(EXTRA_CROP_W, 0)
        cropH = intent.getIntExtra(EXTRA_CROP_H, 0)
        seekToMs = intent.getIntExtra(EXTRA_START_MS, 0)
        startWhenReady = intent.getBooleanExtra(EXTRA_AUTOPLAY, true)
        binding.btnBack.setOnClickListener { finish() }
        binding.videoView.surfaceTextureListener = this

        binding.btnPlayPause.setOnClickListener { togglePlay() }
        binding.btnCenterPlay.setOnClickListener { togglePlay() }
        binding.btnRewind.setOnClickListener { seekBy(-10_000) }
        binding.btnForward.setOnClickListener { seekBy(10_000) }

        // 单击画面：显示/隐藏控件；双击：播放/暂停
        binding.videoView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvCurrent.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true; uiHandler.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                val p = player
                if (p != null && prepared) seekAndRender(p, sb?.progress ?: 0)
                scheduleHide()
            }
        })

        // 若 surface 已就绪（复用），直接初始化
        binding.videoView.surfaceTexture?.let { onSurfaceTextureAvailable(it, 0, 0) }
        showControls()
        scheduleHide(1000)
    }

    private fun buildDataSource() {
        val path = intent.getStringExtra(EXTRA_PATH)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        try {
            val mp = MediaPlayer()
            when {
                !path.isNullOrBlank() -> mp.setDataSource(path)
                !uriStr.isNullOrBlank() -> mp.setDataSource(this, Uri.parse(uriStr))
                else -> { binding.tvName.text = getString(R.string.viewer_open_failed); return }
            }
            mp.isLooping = false
            mp.setOnPreparedListener {
                prepared = true
                binding.seekBar.max = it.duration
                binding.tvDuration.text = formatTime(it.duration)
                surface?.let { s -> it.setSurface(s) }
                if (seekToMs > 0) it.seekTo(seekToMs)
                if (startWhenReady) {
                    it.start()
                } else {
                    // 暂停进入全屏：MediaPlayer 仅 seek 不一定刷新首帧，先播一帧再暂停
                    pauseAfterFirstFrame = true
                    runCatching { it.start() }
                    uiHandler.postDelayed({
                        if (pauseAfterFirstFrame) {
                            pauseAfterFirstFrame = false
                            runCatching { it.pause() }
                            updatePlayIcon()
                        }
                    }, 180)
                }
                updatePlayIcon()
                applyVideoTransform(it.videoWidth, it.videoHeight)
                uiHandler.post(progressRunnable)
            }
            mp.setOnCompletionListener {
                updatePlayIcon()
                binding.btnCenterPlay.visibility = View.VISIBLE
                showControls()
            }
            mp.setOnVideoSizeChangedListener { _, w, h -> applyVideoTransform(w, h) }
            mp.setOnErrorListener { _, what, extra ->
                Logger.w("player", "MediaPlayer error what=$what extra=$extra")
                binding.tvName.text = getString(R.string.viewer_open_failed)
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            Logger.w("player", "open failed: ${t.message}")
            binding.tvName.text = getString(R.string.viewer_open_failed)
        }
    }

    /**
     * 画面几何：
     * - 有裁剪（回看全屏跟随某一路）：把该裁剪区域放大到整个视图（像素矩阵）
     * - 无裁剪：按视频原始比例居中适配（fitCenter），画面不变形、留黑边
     */
    private fun applyVideoTransform(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        val viewW = binding.videoView.width.toFloat()
        val viewH = binding.videoView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        val m = Matrix()
        if (cropW > 0 && cropH > 0) {
            // 单路全屏：放大裁剪区域铺满视图，只显示该路（fit 会把相邻格一起缩进来）
            val s = maxOf(viewW / cropW, viewH / cropH)
            m.setScale(s * videoW / viewW, s * videoH / viewH)
            m.postTranslate(
                (viewW - cropW * s) / 2f - cropX * s,
                (viewH - cropH * s) / 2f - cropY * s
            )
        } else {
            val s = minOf(viewW / videoW, viewH / videoH)
            val scaledW = videoW * s
            val scaledH = videoH * s
            m.setScale(scaledW / viewW, scaledH / viewH)
            m.postTranslate((viewW - scaledW) / 2f, (viewH - scaledH) / 2f)
        }
        binding.videoView.setTransform(m)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (!prepared) return
        pauseAfterFirstFrame = false
        if (p.isPlaying) {
            p.pause()
            showControlsThenSchedule()
        } else {
            p.start()
            binding.btnCenterPlay.visibility = View.GONE
            scheduleHide()
        }
        updatePlayIcon()
    }

    private fun seekBy(deltaMs: Int) {
        val p = player ?: return
        if (!prepared) return
        val target = (p.currentPosition + deltaMs).coerceIn(0, p.duration)
        seekAndRender(p, target)
        scheduleHide()
    }

    /** 暂停状态下拖动进度：seek 后短暂 start 再 pause，强制刷新画面帧 */
    private fun seekAndRender(p: MediaPlayer, target: Int) {
        runCatching { p.seekTo(target) }
        binding.seekBar.progress = target
        binding.tvCurrent.text = formatTime(target)
        if (!isPlayingNow()) {
            pauseAfterFirstFrame = true
            runCatching { p.start() }
            uiHandler.postDelayed({
                if (pauseAfterFirstFrame) {
                    pauseAfterFirstFrame = false
                    runCatching { p.pause() }
                    updatePlayIcon()
                }
            }, 180)
        }
    }

    private fun updatePlayIcon() {
        val playing = isPlayingNow()
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnCenterPlay.visibility = if (playing) View.GONE else View.VISIBLE
    }

    private fun showControls() {
        binding.barTop.visibility = View.VISIBLE
        binding.barBottom.visibility = View.VISIBLE
    }

    private fun hideControls(force: Boolean = false) {
        if (!force && !isPlayingNow()) return // 自动收起时暂停画面保留控件
        binding.barTop.visibility = View.GONE
        binding.barBottom.visibility = View.GONE
    }

    private fun showControlsThenSchedule() {
        showControls()
        scheduleHide()
    }

    private fun scheduleHide(delayMs: Long = 2000) {
        uiHandler.removeCallbacks(hideRunnable)
        uiHandler.postDelayed(hideRunnable, delayMs)
    }

    private fun formatTime(ms: Int): String {
        if (ms < 0) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    // ---- TextureView.SurfaceTextureListener ----
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (this.surface == null) this.surface = Surface(surface)
        if (player == null) {
            // startWhenReady 在 onCreate 按 EXTRA_AUTOPLAY 初始化，Surface 重建时由
            // onSurfaceTextureDestroyed 按当时是否在播放恢复
            buildDataSource()
        } else {
            // Surface 重建：把现有播放器挂到新 Surface
            runCatching { player?.setSurface(this.surface) }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val vw = player?.videoWidth ?: 0
        val vh = player?.videoHeight ?: 0
        applyVideoTransform(vw, vh)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // 必须先释放播放器并记录位置，否则新 Surface 上会继续使用已释放的旧 Surface（黑屏）
        runCatching { seekToMs = player?.currentPosition ?: seekToMs }
        startWhenReady = isPlayingNow()
        runCatching { player?.setSurface(null) }
        runCatching { player?.release() }
        player = null
        prepared = false
        this.surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onResume() {
        if (recreateIfThemeChanged()) return
        super.onResume()
    }

    override fun onPause() {
        // 退到后台/锁屏时暂停，避免继续解码耗电、被系统回收
        if (isPlayingNow()) {
            runCatching { player?.pause() }
            updatePlayIcon()
            showControls()
        }
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        runCatching { player?.setSurface(null) }
        runCatching { player?.release() }
        player = null
        surface = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_TIME_MS = "extra_time_ms"
        const val EXTRA_CROP_X = "extra_crop_x"
        const val EXTRA_CROP_Y = "extra_crop_y"
        const val EXTRA_CROP_W = "extra_crop_w"
        const val EXTRA_CROP_H = "extra_crop_h"
        const val EXTRA_START_MS = "extra_start_ms"
        const val EXTRA_AUTOPLAY = "extra_autoplay"
    }
}

