package com.wheeltech.blindcam.ui.playback

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.DatePicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.databinding.ActivityPlaybackBinding
import com.wheeltech.blindcam.domain.CameraSupervisor
import com.wheeltech.blindcam.recorder.GalleryMedia
import com.wheeltech.blindcam.location.TrackPoint
import com.wheeltech.blindcam.ui.viewer.VideoPlayerActivity
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 录像回看页（总录像时间轴回看）：
 * - 顶部 16:9 预览，播放当天总录像（合成，顺序与主界面一致）；
 * - 时间轴范围从当天录像文件计算，有录像段高亮、无录像段灰色，
 *   拖到无录像段提示「此时间段没有视频内容」；
 * - 按钮：全屏（进内置播放器，含完整控件）、切换显示画面（全部/单路，矩阵裁剪）、切换日期；
 * - 底部地图：有 GPS 轨迹时随播放联动车标，离线显示内置示意图，联网显示在线地图。
 */
class PlaybackActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private data class Segment(
        val media: GalleryMedia,
        val startMs: Long,
        var durMs: Long,
        val path: String?,
        val uri: Uri?
    ) {
        val endMs get() = startMs + durMs
    }

    private lateinit var binding: ActivityPlaybackBinding
    private val ui = Handler(Looper.getMainLooper())

    private var segments: List<Segment> = emptyList()
    private var rangeMin = 0L
    private var rangeMax = 0L
    private var track: List<TrackPoint> = emptyList()

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var curSeg = -1
    private var wantPlaying = false
    private var loading = false

    private var dateKey: String = ""
    private var videoDirUri: String = ""

    /** 画面切换：-1=全部，0..N-1 单路 */
    private var cellIndex = -1
    private var cellCount = 1
    private val cellNames = ArrayList<String>()

    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val prettyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val tick = object : Runnable {
        override fun run() {
            if (prepared && !loading) {
                val seg = segments.getOrNull(curSeg)
                val p = player
                if (seg != null && p != null) {
                    val global = seg.startMs + runCatching { p.currentPosition }.getOrDefault(0)
                    updateCurrent(global)
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme((application as UvcApp).themeResId((application as UvcApp).currentThemeMode))
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.playbackView.surfaceTextureListener = this
        binding.btnPlayPause.setOnClickListener { togglePlay() }
        binding.btnCenterPlay.setOnClickListener { togglePlay() }
        binding.btnRewind.setOnClickListener { scrubBy(-10_000) }
        binding.btnForward.setOnClickListener { scrubBy(10_000) }
        binding.btnFullscreen.setOnClickListener { openFullscreen() }
        binding.btnCell.setOnClickListener { cycleCell() }
        binding.btnDate.setOnClickListener { showDatePicker() }
        binding.timeline.onScrub = { ms -> scrubTo(ms) }

        setupWebView()
        binding.playbackView.surfaceTexture?.let { onSurfaceTextureAvailable(it, 0, 0) }
        dateKey = todayKey()
        binding.btnDate.text = prettyFmt.format(Date())

        lifecycleScope.launch {
            videoDirUri = (application as UvcApp).settingsRepository.settings.first().videoDirUri
            buildCellNames()
            loadDate(dateKey)
        }
        ui.post(tick)
    }

    private fun buildCellNames() {
        cellNames.clear()
        val slots = (application as UvcApp).supervisor.slots.value
            .filter { it.key != null && it.state != CameraSupervisor.SlotState.EMPTY }
        slots.forEach { rt ->
            val n = rt.config?.name?.takeIf { it.isNotBlank() }
                ?: rt.device?.productName?.takeIf { it.isNotBlank() }
                ?: "画面 ${rt.slot + 1}"
            cellNames.add(n)
        }
        cellCount = cellNames.size.coerceIn(1, 4)
    }

    // ------------------------------------------------------------------
    // 日期与数据加载
    // ------------------------------------------------------------------

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        runCatching { dateFmt.parse(dateKey) }.getOrNull()?.let { cal.time = it }
        DatePickerDialog(
            this,
            { _: DatePicker, y: Int, m: Int, d: Int ->
                val c = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }
                dateKey = dateFmt.format(c.time)
                binding.btnDate.text = prettyFmt.format(c.time)
                loadDate(dateKey)
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun loadDate(key: String) {
        val app = application as UvcApp
        lifecycleScope.launch {
            val (mediaList, trackPts) = withContext(Dispatchers.IO) {
                val all = app.mediaSaver.scanVideos(videoDirUri)
                    .filter { it.kind == GalleryMedia.Kind.ALL && it.dateKey == key }
                    .sortedBy { it.timeMs }
                val segs = all.map { m ->
                    Segment(
                        media = m,
                        startMs = m.timeMs,
                        durMs = readDurationMs(m),
                        path = m.file?.absolutePath,
                        uri = m.uri
                    )
                }
                segs to app.mediaSaver.readGpsTrack(videoDirUri, key)
            }
            stopPlayback()
            segments = mediaList
            track = trackPts
            if (segments.isEmpty()) {
                rangeMin = 0; rangeMax = 0
                binding.timeline.setData(0, 0, emptyList(), 0)
                binding.tvGap.visibility = View.VISIBLE
                binding.tvGap.text = getString(R.string.playback_no_recording)
                binding.tvRangeEnd.text = "--:--:--"
                binding.tvCurrent.text = "--:--:--"
                updateCellLabel()
                loadTrackToMap()
                return@launch
            }
            // 时长为 0 的兜底
            segments.forEach { if (it.durMs <= 0) it.durMs = 3000 }
            rangeMin = segments.first().startMs
            rangeMax = segments.maxOf { it.endMs }
            binding.tvGap.visibility = View.GONE
            binding.tvGap.text = getString(R.string.playback_no_content)
            binding.tvRangeEnd.text = clockFmt.format(Date(rangeMax))
            renderTimelineSegments(segments.first().startMs)
            loadTrackToMap()
            // 自动从第一段开始播放
            loadSegment(0, 0, true)
        }
    }

    private fun readDurationMs(m: GalleryMedia): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            when {
                m.file != null -> mmr.setDataSource(m.file.absolutePath)
                m.uri != null -> mmr.setDataSource(this, m.uri)
                else -> return 0L
            }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            Logger.w("playback", "duration failed ${m.name}: ${t.message}")
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun renderTimelineSegments(current: Long) {
        val ranges = segments.map { TimelineView.SegmentRange(it.startMs, it.endMs) }
        binding.timeline.setData(rangeMin, rangeMax, ranges, current)
    }

    // ------------------------------------------------------------------
    // 播放控制（分段 MediaPlayer）
    // ------------------------------------------------------------------

    /** 加载并播放（或暂停）某一段；[localMs] 段内偏移。 */
    private fun loadSegment(index: Int, localMs: Int, play: Boolean) {
        if (index < 0 || index >= segments.size) return
        loading = true
        releasePlayer()
        val seg = segments[index]
        curSeg = index
        try {
            val mp = MediaPlayer()
            when {
                !seg.path.isNullOrBlank() -> mp.setDataSource(seg.path)
                seg.uri != null -> mp.setDataSource(this, seg.uri)
                else -> { loading = false; return }
            }
            mp.isLooping = false
            mp.setOnPreparedListener {
                prepared = true
                loading = false
                surface?.let { s -> it.setSurface(s) }
                if (localMs > 0) it.seekTo(localMs)
                if (play) {
                    it.start()
                    wantPlaying = true
                } else {
                    wantPlaying = false
                }
                updatePlayIcon()
                applyCellTransform()
            }
            mp.setOnCompletionListener {
                if (curSeg < segments.size - 1) {
                    loadSegment(curSeg + 1, 0, true)
                } else {
                    wantPlaying = false
                    updatePlayIcon()
                    updateCurrent(segments.last().endMs)
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Logger.w("playback", "error what=$what extra=$extra")
                loading = false
                true
            }
            mp.prepareAsync()
            player = mp
            binding.tvGap.visibility = View.GONE
        } catch (t: Throwable) {
            loading = false
            Logger.w("playback", "open seg failed: ${t.message}")
        }
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }

    private fun stopPlayback() {
        wantPlaying = false
        releasePlayer()
        curSeg = -1
    }

    private fun togglePlay() {
        val p = player
        if (segments.isEmpty()) return
        // 播放结束 / 无可用段：回到第一段
        if (!prepared || curSeg < 0) {
            loadSegment(0, 0, true)
            return
        }
        if (p!!.isPlaying) {
            p.pause(); wantPlaying = false
        } else {
            p.start(); wantPlaying = true
        }
        updatePlayIcon()
    }

    /** 全局时间轴跳转：定位到段或落在无录像间隙。 */
    private fun scrubTo(globalMs: Long) {
        if (segments.isEmpty()) return
        val idx = segments.indexOfFirst { globalMs >= it.startMs && globalMs < it.endMs }
        if (idx >= 0) {
            binding.tvGap.visibility = View.GONE
            val local = (globalMs - segments[idx].startMs).toInt()
            if (idx == curSeg && prepared) {
                player?.seekTo(local)
                if (wantPlaying) player?.start()
            } else {
                loadSegment(idx, local, wantPlaying)
            }
            updateCurrent(globalMs)
            updateMap(globalMs)
        } else {
            // 落在无录像区间：暂停并提示
            player?.pause()
            wantPlaying = false
            updatePlayIcon()
            binding.tvGap.visibility = View.VISIBLE
            binding.tvGap.text = getString(R.string.playback_no_content)
            updateCurrent(globalMs)
            updateMap(globalMs)
        }
    }

    private fun scrubBy(deltaMs: Long) {
        val seg = segments.getOrNull(curSeg) ?: return
        val p = player ?: return
        if (!prepared) return
        val global = seg.startMs + p.currentPosition + deltaMs
        scrubTo(global.coerceIn(rangeMin, rangeMax))
    }

    private fun updateCurrent(globalMs: Long) {
        binding.timeline.setCurrent(globalMs.coerceIn(rangeMin, rangeMax))
        binding.tvCurrent.text = if (rangeMin > 0) clockFmt.format(Date(globalMs)) else "--:--:--"
        updateMap(globalMs)
    }

    private fun updatePlayIcon() {
        val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnCenterPlay.visibility = if (playing) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------
    // 画面切换：全部 / 单路（按总录像宫格布局矩阵裁剪）
    // ------------------------------------------------------------------

    private fun cycleCell() {
        if (cellCount <= 1) {
            Toast.makeText(this, "当前为单路画面", Toast.LENGTH_SHORT).show()
            return
        }
        cellIndex = if (cellIndex + 1 >= cellCount) -1 else cellIndex + 1
        applyCellTransform()
        updateCellLabel()
    }

    /** 与 CompositeRecorder.cellRect 一致的归一化裁剪框（基于 1280x720） */
    private fun cellNormRect(index: Int, n: Int): FloatArray {
        val W = 1280f; val H = 720f
        var x0 = 0f; var y0 = 0f; var x1 = W; var y1 = H
        when (n) {
            2 -> when (index) {
                0 -> x1 = W / 2
                else -> x0 = W / 2
            }
            3 -> when (index) {
                0 -> y1 = H / 2
                1 -> { y0 = H / 2; x1 = W / 2 }
                else -> { y0 = H / 2; x0 = W / 2 }
            }
            else -> {
                val col = index % 2; val row = index / 2
                x0 = col * W / 2; x1 = (col + 1) * W / 2
                y0 = row * H / 2; y1 = (row + 1) * H / 2
            }
        }
        return floatArrayOf(x0 / W, y0 / H, (x1 - x0) / W, (y1 - y0) / H)
    }

    private fun applyCellTransform() {
        val vw = binding.playbackView.width
        val vh = binding.playbackView.height
        if (vw <= 0 || vh <= 0) return
        val m = Matrix()
        if (cellIndex < 0) {
            // 全部：视频 16:9 与容器 16:9 一致，直接铺满
            m.setScale(1f, 1f)
        } else {
            val (x, y, w, h) = cellNormRect(cellIndex, cellCount)
            m.setScale(1f / w, 1f / h)
            m.postTranslate(-x / w, -y / h)
        }
        binding.playbackView.setTransform(m)
    }

    private fun updateCellLabel() {
        binding.tvCellName.text = if (cellIndex < 0) {
            "全部画面"
        } else {
            cellNames.getOrElse(cellIndex) { "画面 ${cellIndex + 1}" }
        }
        binding.btnCell.text = if (cellIndex < 0) getString(R.string.playback_all_cells)
            else getString(R.string.playback_cell_n, cellIndex + 1)
    }

    private fun openFullscreen() {
        val seg = segments.getOrNull(curSeg) ?: return
        val intent = android.content.Intent(this, VideoPlayerActivity::class.java)
        if (!seg.path.isNullOrBlank()) intent.putExtra(VideoPlayerActivity.EXTRA_PATH, seg.path)
        seg.uri?.let { intent.putExtra(VideoPlayerActivity.EXTRA_URI, it.toString()) }
        intent.putExtra(VideoPlayerActivity.EXTRA_NAME, "总录像 · ${seg.media.name}")
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.viewer_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 地图
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv: WebView = binding.mapView
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loadTrackToMap()
            }
        }
        wv.loadUrl("file:///android_asset/track_map.html")
    }

    private fun loadTrackToMap() {
        val arr = JSONArray()
        track.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("t", p.timeMs)
                    put("lat", p.lat)
                    put("lon", p.lon)
                    put("brg", p.bearing.toDouble())
                }
            )
        }
        val json = arr.toString()
        // 单引号包裹，避免转义
        binding.mapView.evaluateJavascript("setTrack('$json')", null)
        binding.tvMapHint.text = if (track.isEmpty())
            "该日期没有 GPS 轨迹（未开启 GPS 或无定位信号）"
        else getString(R.string.playback_map_hint)
    }

    private fun updateMap(globalMs: Long) {
        if (track.isEmpty()) return
        binding.mapView.evaluateJavascript("updateTime($globalMs)", null)
    }

    // ------------------------------------------------------------------
    // TextureView
    // ------------------------------------------------------------------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (this.surface == null) this.surface = Surface(surface)
        if (curSeg < 0 && segments.isNotEmpty()) {
            loadSegment(0, 0, true)
        } else {
            player?.setSurface(this.surface)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyCellTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        this.surface?.release()
        this.surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        releasePlayer()
        surface?.release()
        surface = null
        runCatching { binding.mapView.destroy() }
    }

    private fun todayKey(): String = dateFmt.format(Date())
}
