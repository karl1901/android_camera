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
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.databinding.ActivityVideoPlayerBinding
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
class VideoPlayerActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityVideoPlayerBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var startWhenReady = false
    private var seekToMs = 0
    private var isSeeking = false

    // 裁剪（回看全屏跟随某一路）：视频像素坐标；cropW<=0 表示整段
    private var cropX = 0
    private var cropY = 0
    private var cropW = 0
    private var cropH = 0

    /** 控件自动收起：进入显示 1 秒；播放中无操作 2 秒收起；暂停时常驻 */
    private val hideRunnable = Runnable { if (isPlayingNow()) hideControls() }
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
        binding.btnBack.setOnClickListener { finish() }
        binding.videoView.surfaceTextureListener = this

        binding.btnPlayPause.setOnClickListener { togglePlay() }
        binding.btnCenterPlay.setOnClickListener { togglePlay() }
        binding.btnRewind.setOnClickListener { seekBy(-10_000) }
        binding.btnForward.setOnClickListener { seekBy(10_000) }

        // 点击画面任意处：显示控件并重置自动收起计时
        binding.videoView.setOnClickListener { showControlsThenSchedule() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvCurrent.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true; uiHandler.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                runCatching { player?.seekTo(sb?.progress ?: 0) }
                isSeeking = false
                scheduleHide()
            }
        })

        // 若 surface 已就绪（复用），直接初始化
        binding.videoView.surfaceTexture?.let { onSurfaceTextureAvailable(it, 0, 0) }
        showControls()
        scheduleHide(2000)
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
                if (startWhenReady) it.start()
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
            val sx = videoW.toFloat() / cropW
            val sy = videoH.toFloat() / cropH
            m.setScale(sx, sy)
            m.postTranslate(-cropX * viewW / cropW, -cropY * viewH / cropH)
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
        if (p.isPlaying) {
            p.pause()
            showControls()
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
        p.seekTo(target)
        binding.seekBar.progress = target
        binding.tvCurrent.text = formatTime(target)
        scheduleHide()
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

    private fun hideControls() {
        if (!isPlayingNow()) return // 暂停时不收起，方便用户操作
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
        startWhenReady = true
        buildDataSource()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val vw = player?.videoWidth ?: 0
        val vh = player?.videoHeight ?: 0
        applyVideoTransform(vw, vh)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        this.surface?.release()
        this.surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        runCatching {
            player?.release()
        }
        player = null
        surface?.release()
        surface = null
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
    }
}
