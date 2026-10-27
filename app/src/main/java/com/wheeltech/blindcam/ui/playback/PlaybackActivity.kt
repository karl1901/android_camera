package com.wheeltech.blindcam.ui.playback

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wheeltech.blindcam.UvcApp
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.databinding.ActivityPlaybackBinding
import com.wheeltech.blindcam.domain.CameraSupervisor
import com.wheeltech.blindcam.domain.GridLayoutSpec
import com.wheeltech.blindcam.location.TrackPoint
import com.wheeltech.blindcam.recorder.GalleryMedia
import com.wheeltech.blindcam.ui.viewer.VideoPlayerActivity
import com.wheeltech.blindcam.util.CoordinateConverter
import com.wheeltech.blindcam.util.Logger
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 录像回看页（总录像按日期）：
 * - 顶部 TextureView 播放当天总录像（多个分段连续播放），默认不自动播放
 * - 时间轴：拖动即时定位（节流 seek，API26+ SEEK_CLOSEST），有/无录像段可视化
 * - 画面切换：固定像素矩阵（任意播放/暂停/加载状态都能切，不卡死）；按钮文字用自定义/设备名
 * - 双击画面：暂停 / 播放
 * - 全屏：跟随当前画面（全部→全画面；某一路→裁剪该路）
 * - 空日期：画面清黑、白字提示
 * - 下方行车轨迹地图（见 Amap 集成）
 */
class PlaybackActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityPlaybackBinding
    private val uiHandler = Handler(Looper.getMainLooper())
    private val app: UvcApp get() = application as UvcApp

    private class SegInfo(
        val file: java.io.File?,
        val uri: android.net.Uri?,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        /** 录制时参与合成的画面名称（来自同名 .json 元数据）；旧录像为空 */
        val cellNames: List<String> = emptyList()
    )

    /** 后台扫描结果（扫描/探测时长/读 GPS 都不允许占用主线程） */
    private class LoadResult(
        val videoTree: String,
        val segments: List<SegInfo>,
        val track: List<TrackPoint>
    )

    private var segments: List<SegInfo> = emptyList()
    private var curSegIndex = -1
    private var player: MediaPlayer? = null
    private var prepared = false
    private var surface: Surface? = null

    /** 当前播放位置（当天全局 ms） */
    private var currentGlobalMs = 0L
    /** 用户的播放意图（默认不自动播放 = false） */
    private var playing = false

    /** 播放器还没 prepared 时暂存的 seek / 播放意图（修复“准备中点播放/拖动无反应”） */
    private var seekWhenPrepared = 0
    private var playWhenPrepared = false

    /** 从相册某条总录像进入时的定位时间（epoch ms） */
    private var pendingInitialTimeMs: Long? = null

    /** 进入全屏前是否正在播放：返回时恢复，而不是自动播放 */
    private var resumeAfterFullscreen = false

    /** 扫描代号：日期快速切换时丢弃过期结果 */
    private var loadToken = 0
    private var loadRequested = false

    /** Surface 还没就绪时暂存的分段加载请求 */
    private class PendingLoad(val index: Int, val localMs: Int, val play: Boolean)
    private var pendingLoad: PendingLoad? = null

    private var dateKey: String = dateKeyOf(System.currentTimeMillis())

    // 画面（cell）
    private var cellIndex = -1
    private var cellNames: List<String> = emptyList()
    private var cellRects: List<IntArray> = emptyList()

    /** 拖动节流 */
    private var lastScrubSeek = 0L

    /** 暂停切画面时：start 一帧再 pause，强制 TextureView 应用新裁剪矩阵 */
    private var pauseAfterCellFrame = false

    // 高德地图
    private var aMap: AMap? = null
    private var trackPoints: List<TrackPoint> = emptyList()
    /** 当天全部原始 GPS 点；按当前录像分段筛选后再画，避免把别的时段轨迹连进来 */
    private var allTrackPoints: List<TrackPoint> = emptyList()
    private var trackLatLngs: List<LatLng> = emptyList()
    private var pastLine: Polyline? = null
    private var futureLine: Polyline? = null
    private var carMarker: Marker? = null
    private var lastMarkerEpoch = -1L

    private val progressTask = object : Runnable {
        override fun run() {
            if (prepared) {
                val seg = segments.getOrNull(curSegIndex)
                if (seg != null) {
                    val pos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
                    currentGlobalMs = seg.startMs + pos
                    binding.timeline.setCurrent(currentGlobalMs)
                    binding.tvCurrent.text = timeText(currentGlobalMs)
                    updateTrackMarker()
                }
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val appTheme = application as UvcApp
        setTheme(appTheme.themeResId(appTheme.currentThemeMode))
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 从相册点某条总录像进入：直接定位到那一天/那一段，而不是永远打开“今天”
        intent.getStringExtra(EXTRA_DATE)?.takeIf { it.length == 8 }?.let { dateKey = it }
        pendingInitialTimeMs = intent.getLongExtra(EXTRA_TIME_MS, 0L).takeIf { it > 0L }
        binding.btnDate.text = dateLabel(dateKey)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDate.setOnClickListener { pickDate() }
        binding.btnCell.setOnClickListener { cycleCell() }
        binding.btnFullscreen.setOnClickListener { openFullscreen() }

        binding.playbackView.surfaceTextureListener = this
        binding.playbackView.surfaceTexture?.let { onSurfaceTextureAvailable(it, 0, 0) }

        // 播放控制
        binding.btnPlayPause.setOnClickListener { togglePlay() }
        binding.btnCenterPlay.setOnClickListener { togglePlay() }
        binding.btnRewind.setOnClickListener { seekBy(-10_000) }
        binding.btnForward.setOnClickListener { seekBy(10_000) }

        // 时间轴拖动
        binding.timeline.onScrub = { globalMs, phase -> onScrub(globalMs, phase) }

        // 双击暂停/播放（GestureDetector）
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                binding.btnCenterPlay.visibility =
                    if (binding.btnCenterPlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
        })
        binding.playbackView.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }

        updatePlayIcon()
        uiHandler.post(progressTask)

        // 高德地图（9.8.2 用 getMap() 同步获取，无需 getMapAsync）
        binding.mapView.onCreate(null)
        runCatching {
            val map = binding.mapView.map
            aMap = map
            map.uiSettings.apply {
                isZoomControlsEnabled = false
                isMyLocationButtonEnabled = false
                isScaleControlsEnabled = true
            }
            map.mapType = if (app.currentThemeMode == com.wheeltech.blindcam.data.AppSettings.ThemeMode.DARK ||
                app.currentThemeMode == com.wheeltech.blindcam.data.AppSettings.ThemeMode.TECH_BLUE
            ) {
                AMap.MAP_TYPE_NIGHT
            } else {
                AMap.MAP_TYPE_NORMAL
            }
            drawTrack()
        }.onFailure { Logger.w("playback", "map init failed: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // 日期 / 扫描
    // ------------------------------------------------------------------

    private fun pickDate() {
        val c = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val cal = Calendar.getInstance()
                cal.set(y, m, d)
                dateKey = dateKeyOf(cal.timeInMillis)
                loadDate()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /**
     * 读取指定日期的总录像。
     *
     * 扫描目录、探测时长、读取 GPS 轨迹全部放到 IO 线程：车机 TF 卡上几十个分段时，
     * 原来在主线程逐个 MediaMetadataRetriever 会把界面卡住（历史“回看卡死”）。
     */
    private fun loadDate() {
        loadRequested = true
        val token = ++loadToken
        val app = application as UvcApp
        val key = dateKey
        // 立即清掉上一日期的画面/播放器，避免切到空日期时残留定格
        releasePlayer()
        pendingLoad = null
        playing = false
        playWhenPrepared = false
        updatePlayIcon()
        segments = emptyList()
        curSegIndex = -1
        resetCells()
        lastMarkerEpoch = -1
        showLoading()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { scanDate(app, key) }
            }
            if (token != loadToken || isFinishing || isDestroyed) return@launch
            val loaded = result.getOrNull()
            if (loaded == null) {
                Logger.w("playback", "scan date failed: ${result.exceptionOrNull()?.message}")
                segments = emptyList()
                showEmptyDate()
                return@launch
            }
            applyLoaded(loaded)
        }
    }

    private suspend fun scanDate(app: UvcApp, key: String): LoadResult {
        val videoTree = app.settingsRepository.settings.first().videoDirUri
        // 扫描总录像（scanVideos 已合并私有目录与 SAF 自定义目录）。
        // 先不读每个文件的时长，只对当天需要播放的分段探测，避免 TF 卡上
        // 几个月的历史录像逐个 MediaMetadataRetriever 导致回看页长时间转圈。
        val all = app.mediaSaver.scanVideos(videoTree, probeDurations = false)
            .filter { it.kind == GalleryMedia.Kind.ALL }
        // 当天总录像：同时支持私有 File 与 SAF 自定义目录 Uri
        val dayMedia = all.filter { it.dateKey == key && (it.file != null || it.uri != null) }
            .sortedBy { it.name }

        val list = ArrayList<SegInfo>()
        for (m in dayMedia) {
            // 时长优先用扫描时已探测的 durationMs；缺失时再现场探测
            val dur = m.durationMs.takeIf { it > 0 }
                ?: (m.file?.let { probeDuration(it) }
                    ?: m.uri?.let { probeDurationUri(it) } ?: 0L)
            if (dur <= 0) continue
            // 用文件时间戳定位全局起点（文件名 ALL_yyyyMMdd_HHmmss）
            val tsStart = parseAllTs(m.name)
            // 录制时写入的画面元数据（可能不存在：旧录像/旧版本）
            val meta = runCatching {
                app.mediaSaver.readCompositeMeta(m.name, videoTree, m.file)
            }.getOrNull()
            list.add(
                SegInfo(
                    m.file, m.uri, tsStart, tsStart + dur, dur,
                    meta?.cellNames ?: emptyList()
                )
            )
        }
        val track = runCatching {
            app.mediaSaver.readGpsTrack(videoTree, key)
        }.getOrDefault(emptyList())
        return LoadResult(videoTree, list, track)
    }

    private fun showLoading() {
        binding.blackOverlay.visibility = View.VISIBLE
        binding.tvGap.setTextColor(Color.WHITE)
        binding.tvGap.text = getString(com.wheeltech.blindcam.R.string.playback_loading)
        binding.tvGap.visibility = View.VISIBLE
        binding.btnCell.text = getString(com.wheeltech.blindcam.R.string.playback_all_cells)
        binding.tvCellName.text = ""
        binding.tvCellName.visibility = View.GONE
        binding.btnCenterPlay.visibility = View.GONE
        binding.timeline.setSegments(emptyList(), 0, 86_400_000)
        binding.timeline.setCurrent(0)
        binding.tvCurrent.text = "00:00:00"
        binding.tvRangeEnd.text = "00:00:00"
        binding.btnDate.text = dateLabel(dateKey)
    }

    /** 扫描完成后在主线程应用结果 */
    private fun applyLoaded(result: LoadResult) {
        val list = result.segments
        allTrackPoints = result.track
        trackPoints = emptyList()
        drawTrack()

        if (list.isEmpty()) {
            segments = emptyList()
            curSegIndex = -1
            resetCells()
            binding.timeline.setSegments(emptyList(), 0, 86_400_000)
            binding.timeline.setCurrent(0)
            showEmptyDate()
            return
        }

        segments = list
        val rangeStart = list.first().startMs
        val rangeEnd = list.last().endMs
        binding.timeline.setSegments(
            list.map { TimelineView.Segment(it.startMs, it.endMs) },
            rangeStart, rangeEnd
        )
        binding.tvRangeEnd.text = timeText(rangeEnd)

        // 从相册某条总录像进入：定位到该段的时间点；否则定位到当天第一段起点
        val target = pendingInitialTimeMs?.let { epoch ->
            (epoch - dayStartEpoch(dateKey)).coerceIn(rangeStart, rangeEnd)
        } ?: rangeStart
        pendingInitialTimeMs = null
        val idx = list.indexOfFirst { target in it.startMs..it.endMs }
            .let { if (it < 0) 0 else it }
        val local = (target - list[idx].startMs).toInt().coerceIn(0, list[idx].durationMs.toInt())
        currentGlobalMs = target
        binding.timeline.setCurrent(currentGlobalMs)
        binding.tvCurrent.text = timeText(currentGlobalMs)
        binding.blackOverlay.visibility = View.GONE
        hideGap()
        binding.btnDate.text = dateLabel(dateKey)
        // 默认不自动播放：加载首帧后保持暂停
        loadSegment(idx, local, false)
    }

    /** 空日期：黑色遮罩 + 白字提示（不锁 Surface，避免和 MediaPlayer 抢生产者） */
    private fun showEmptyDate() {
        binding.blackOverlay.visibility = View.VISIBLE
        binding.tvGap.setTextColor(Color.WHITE)
        binding.tvGap.text = getString(com.wheeltech.blindcam.R.string.playback_no_recording)
        binding.tvGap.visibility = View.VISIBLE
        binding.btnCell.text = getString(com.wheeltech.blindcam.R.string.playback_all_cells)
        binding.tvCellName.text = ""
        binding.tvCellName.visibility = View.GONE
        binding.btnDate.text = dateLabel(dateKey)
        binding.btnCenterPlay.visibility = View.GONE
        binding.tvCurrent.text = "00:00:00"
        binding.tvRangeEnd.text = "00:00:00"
    }

    private fun hideGap() {
        binding.tvGap.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // 画面（cell）
    // ------------------------------------------------------------------

    private fun resetCells() {
        cellNames = emptyList()
        cellRects = emptyList()
        cellIndex = -1
        applyCellTransform()
    }

    /**
     * 按“录制时”的画面元数据建立画面名称与裁剪矩形。
     * 旧录像没有 .json 元数据时退回当前在线摄像头；再没有则给通用名。
     */
    private fun applySegmentCells(index: Int) {
        val seg = segments.getOrNull(index)
        val metaNames = seg?.cellNames
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val names: List<String>
        if (metaNames.isNotEmpty()) {
            names = metaNames
        } else {
            val live = (application as UvcApp).supervisor.slots.value
                .filter { it.state != CameraSupervisor.SlotState.EMPTY }
            names = if (live.isNotEmpty()) {
                live.map { rt ->
                    rt.config?.name?.takeIf { it.isNotBlank() }
                        ?: rt.device?.productName?.takeIf { it.isNotBlank() }
                        ?: "摄像头 ${rt.slot + 1}"
                }
            } else {
                List(4) { "摄像头 ${it + 1}" }
            }
        }
        setCells(names)
    }

    private fun setCells(rawNames: List<String>) {
        val n = rawNames.size.coerceIn(1, 4)
        val oldCount = cellRects.size
        cellNames = rawNames.take(n)
        cellRects = List(n) { cellRect(it, n) }
        if (oldCount != n || cellIndex >= n) {
            cellIndex = -1
        }
        binding.btnCell.text = if (cellIndex < 0)
            getString(com.wheeltech.blindcam.R.string.playback_all_cells)
        else cellNames[cellIndex]
        if (cellIndex < 0) {
            binding.tvCellName.text = ""
            binding.tvCellName.visibility = View.GONE
        } else {
            binding.tvCellName.text = cellNames[cellIndex]
            binding.tvCellName.visibility = View.VISIBLE
        }
        applyCellTransform()
    }

    private fun cycleCell() {
        if (cellRects.isEmpty()) return
        cellIndex = if (cellIndex + 1 >= cellRects.size) -1 else cellIndex + 1
        applyCellTransform()
        // 暂停时 TextureView 的缓存帧不会自动按新矩阵重绘：seek 到当前位置强制出一帧，
        // 否则会出现“按钮文字变了、画面还是全部画面”的假切换（真机已复现）。
        refreshPausedFrame()
        if (cellIndex < 0) {
            binding.btnCell.text = getString(com.wheeltech.blindcam.R.string.playback_all_cells)
            binding.tvCellName.text = ""
            binding.tvCellName.visibility = View.GONE
        } else {
            binding.btnCell.text = cellNames[cellIndex]
            binding.tvCellName.text = cellNames[cellIndex]
            binding.tvCellName.visibility = View.VISIBLE
        }
    }

    /** 暂停状态下强制刷新一帧，使 TextureView 应用最新裁剪矩阵 */
    private fun refreshPausedFrame() {
        val p = player ?: return
        if (!prepared || p.isPlaying) return
        // 仅 seek 到原位置很多机型不会重绘；先 start 一帧再 pause 最可靠
        pauseAfterCellFrame = true
        runCatching { p.start() }
        uiHandler.postDelayed({
            if (pauseAfterCellFrame) {
                pauseAfterCellFrame = false
                runCatching { p.pause() }
                updatePlayIcon()
            }
        }, 120)
        binding.playbackView.invalidate()
    }

    /**
     * 固定像素矩阵（修复切换卡死）：
     * TextureView transform 工作在视图像素坐标。
     * 把目标 cell（视频坐标 x,y,w,h，视频 1280x720）放大到整个视图。
     */
    private fun applyCellTransform() {
        val vw = binding.playbackView.width.toFloat()
        val vh = binding.playbackView.height.toFloat()
        if (vw <= 0 || vh <= 0) {
            binding.playbackView.post { applyCellTransform() }
            return
        }
        val m = Matrix()
        if (cellIndex < 0) {
            // 全部画面：完整显示，fitCenter（宽高比不一致时上下/左右留黑，不裁切）。
            // 注意 TextureView 的基准图是“拉伸铺满 View”的，矩阵缩放要换算到 View 像素：
            // scaleX = S * 视频宽 / View 宽，scaleY = S * 视频高 / View 高。
            val s = minOf(vw / OW, vh / OH)
            m.setScale(s * OW / vw, s * OH / vh)
            m.postTranslate((vw - OW * s) / 2f, (vh - OH * s) / 2f)
        } else {
            val rect = cellRects.getOrNull(cellIndex) ?: return
            val cx = rect[0]; val cy = rect[1]; val cw = rect[2]; val ch = rect[3]
            if (cw <= 0 || ch <= 0) return
            // 单路：必须放大到铺满视图，把另一格移出可视区；fit 会把两格一起缩进来。
            val s = maxOf(vw / cw, vh / ch)
            m.setScale(s * OW / vw, s * OH / vh)
            m.postTranslate(
                (vw - cw * s) / 2f - cx * s,
                (vh - ch * s) / 2f - cy * s
            )
        }
        binding.playbackView.setTransform(m)
        Logger.i(
            "playback",
            "cellTransform idx=$cellIndex cells=${cellRects.size} view=${vw.toInt()}x${vh.toInt()} " +
                "m=$m"
        )
        // 暂停时没有新帧，部分机型不会自动用新矩阵重绘缓存帧，强制重绘一次
        binding.playbackView.invalidate()
    }

    /** 与 CompositeRecorder / 预览共用同一套布局规则（三路：第 3 路在顶部） */
    private fun cellRect(index: Int, n: Int): IntArray {
        val r = GridLayoutSpec.rect(n, index, OW, OH)
        return intArrayOf(r.left, r.top, r.right, r.bottom)
    }

    /** 全屏：跟随当前画面（全部→全画面；某一路→裁剪该路） */
    private fun openFullscreen() {
        val seg = segments.getOrNull(curSegIndex) ?: return
        resumeAfterFullscreen = playing
        val intent = android.content.Intent(this, VideoPlayerActivity::class.java)
        seg.file?.let { intent.putExtra(VideoPlayerActivity.EXTRA_PATH, it.absolutePath) }
        seg.uri?.let { intent.putExtra(VideoPlayerActivity.EXTRA_URI, it.toString()) }
        intent.putExtra(VideoPlayerActivity.EXTRA_NAME,
            if (cellIndex < 0) getString(com.wheeltech.blindcam.R.string.playback_all_cells)
            else cellNames[cellIndex])
        // 全屏从当前播放位置继续，并保持“暂停/播放”意图
        intent.putExtra(
            VideoPlayerActivity.EXTRA_START_MS,
            runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
        )
        intent.putExtra(VideoPlayerActivity.EXTRA_AUTOPLAY, playing)
        if (cellIndex >= 0) {
            val r = cellRects[cellIndex]
            intent.putExtra(VideoPlayerActivity.EXTRA_CROP_X, r[0])
            intent.putExtra(VideoPlayerActivity.EXTRA_CROP_Y, r[1])
            intent.putExtra(VideoPlayerActivity.EXTRA_CROP_W, r[2])
            intent.putExtra(VideoPlayerActivity.EXTRA_CROP_H, r[3])
        }
        startActivity(intent)
    }

    // ------------------------------------------------------------------
    // 播放 / 分段
    // ------------------------------------------------------------------

    private fun loadSegment(index: Int, localMs: Int, play: Boolean) {
        val seg = segments.getOrNull(index) ?: return
        if (surface == null) {
            // Surface 尚未就绪（刚进页面/刚重建）：等 onSurfaceTextureAvailable 再真正 prepare
            pendingLoad = PendingLoad(index, localMs, play)
            currentGlobalMs = seg.startMs + localMs
            return
        }
        pendingLoad = null
        if (index == curSegIndex && player != null) {
            currentGlobalMs = seg.startMs + localMs
            binding.timeline.setCurrent(currentGlobalMs)
            if (prepared) {
                seekLocal(localMs)
                playing = play
                if (play) startPlay() else pausePlay()
            } else {
                // 还在 prepareAsync：先记住意图，准备好后一次性 seek + 播放
                seekWhenPrepared = localMs
                playWhenPrepared = play
                playing = play
                updatePlayIcon()
            }
            return
        }
        releasePlayer()
        curSegIndex = index
        // 画面名称/裁剪区域跟随“这一段录制时”的布局
        applySegmentCells(index)
        updateTrackForSegment(index)
        try {
            val mp = MediaPlayer()
            when {
                seg.file != null -> mp.setDataSource(seg.file.absolutePath)
                seg.uri != null -> mp.setDataSource(this, seg.uri)
            }
            surface?.let { mp.setSurface(it) }
            mp.isLooping = false
            seekWhenPrepared = localMs
            playWhenPrepared = play
            mp.setOnPreparedListener {
                prepared = true
                seekPrepared(it, seekWhenPrepared)
                if (playWhenPrepared) runCatching { it.start() }
                playing = playWhenPrepared
                updatePlayIcon()
                // 首帧可能还没绘制，布局矩阵在 prepare 后再补一次
                binding.playbackView.post { applyCellTransform() }
            }
            mp.setOnCompletionListener {
                if (curSegIndex < segments.size - 1) {
                    advanceSegment()
                } else {
                    // 最后一段播完：回到暂停态并显示中央播放钮
                    playing = false
                    playWhenPrepared = false
                    updatePlayIcon()
                    binding.btnCenterPlay.visibility = View.VISIBLE
                }
            }
            mp.setOnErrorListener { _, w, e ->
                Logger.w("playback", "error w=$w e=$e")
                prepared = false
                playing = false
                updatePlayIcon()
                true
            }
            mp.prepareAsync()
            player = mp
            currentGlobalMs = seg.startMs + localMs
            binding.timeline.setCurrent(currentGlobalMs)
        } catch (t: Throwable) {
            Logger.w("playback", "loadSegment failed: ${t.message}")
        }
    }

    private fun advanceSegment() {
        if (curSegIndex + 1 < segments.size) {
            loadSegment(curSegIndex + 1, 0, playing)
        }
    }

    private fun seekPrepared(p: MediaPlayer, ms: Int) {
        val target = ms.coerceAtLeast(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { p.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST) }
        } else {
            runCatching { p.seekTo(target) }
        }
    }

    private fun seekLocal(ms: Int) {
        val p = player ?: return
        if (!prepared) {
            seekWhenPrepared = ms
            currentGlobalMs = (segments.getOrNull(curSegIndex)?.startMs ?: 0) + ms
            binding.timeline.setCurrent(currentGlobalMs)
            return
        }
        seekPrepared(p, ms)
        currentGlobalMs = (segments.getOrNull(curSegIndex)?.startMs ?: 0) + ms
        binding.timeline.setCurrent(currentGlobalMs)
    }

    private fun onScrub(globalMs: Long, phase: TimelineView.Phase) {
        if (segments.isEmpty()) return
        // 找到包含该时刻的段
        val idx = segments.indexOfFirst { globalMs in it.startMs..it.endMs }
        if (idx < 0) {
            // 落在无录像段：暂停并保留白字提示（不要把提示又藏起来）
            if (playing) pausePlay()
            binding.tvGap.text = getString(com.wheeltech.blindcam.R.string.playback_no_content)
            binding.tvGap.setTextColor(Color.WHITE)
            binding.tvGap.visibility = View.VISIBLE
            return
        }
        hideGap()
        val seg = segments[idx]
        val local = (globalMs - seg.startMs).toInt()
        val now = SystemClock.uptimeMillis()
        if (idx != curSegIndex) {
            // 跨段：重建播放器（保持当前播放意图；拖动中默认暂停更稳）
            loadSegment(idx, local, if (phase == TimelineView.Phase.END) playing else false)
            return
        }
        when (phase) {
            TimelineView.Phase.START, TimelineView.Phase.END -> seekLocal(local)
            TimelineView.Phase.MOVE -> {
                if (now - lastScrubSeek >= 100) {
                    lastScrubSeek = now
                    seekLocal(local)
                }
            }
        }
        if (phase == TimelineView.Phase.END) seekLocal(local)
    }

    private fun togglePlay() {
        if (segments.isEmpty()) return
        if (pauseAfterCellFrame) {
            // 正在为“暂停切画面”做 120ms 的临时播放：用户此时点是希望继续播放
            pauseAfterCellFrame = false
            playing = true
            playWhenPrepared = true
            binding.btnCenterPlay.visibility = View.GONE
            updatePlayIcon()
            return
        }
        val p = player
        if (!prepared || p == null) {
            // 还在 prepareAsync：立即反馈“准备播放”，并在 onPrepared 后自动开始
            playing = true
            playWhenPrepared = true
            binding.btnCenterPlay.visibility = View.GONE
            val seg = segments.firstOrNull { currentGlobalMs in it.startMs..it.endMs }
            if (seg != null) {
                val idx = segments.indexOf(seg)
                val local = (currentGlobalMs - seg.startMs).toInt().coerceAtLeast(0)
                if (idx != curSegIndex || player == null) {
                    loadSegment(idx, local, true)
                }
            }
            updatePlayIcon()
            return
        }
        if (p.isPlaying) pausePlay() else startPlay()
    }

    private fun startPlay() {
        pauseAfterCellFrame = false
        playing = true
        playWhenPrepared = true
        if (prepared) runCatching { player?.start() }
        binding.btnCenterPlay.visibility = View.GONE
        updatePlayIcon()
    }

    private fun pausePlay() {
        pauseAfterCellFrame = false
        playing = false
        playWhenPrepared = false
        if (prepared) runCatching { player?.pause() }
        binding.btnCenterPlay.visibility = View.VISIBLE
        updatePlayIcon()
    }

    private fun seekBy(delta: Int) {
        if (segments.isEmpty()) return
        val target = (currentGlobalMs + delta)
            .coerceIn(segments.first().startMs, segments.last().endMs)
        onScrub(target, TimelineView.Phase.END)
    }

    private fun updatePlayIcon() {
        val isPlaying = if (prepared) {
            runCatching { player?.isPlaying == true }.getOrDefault(playing)
        } else {
            playing
        }
        // 播放按钮：图标随播放状态切换
        binding.btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnCenterPlay.text = if (isPlaying) "❚❚" else "▶"
        // 暂停时显示中央播放钮，播放时隐藏（空日期/加载中由各自流程再覆盖）
        binding.btnCenterPlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
    }

    private fun timeText(dayMs: Long): String {
        val h = dayMs / 3600_000
        val m = (dayMs % 3600_000) / 60_000
        val s = (dayMs % 60_000) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun dateLabel(key: String): String =
        if (key.length == 8) "${key.substring(0, 4)}-${key.substring(4, 6)}-${key.substring(6, 8)}"
        else key

    // ------------------------------------------------------------------
    // 高德地图轨迹
    // ------------------------------------------------------------------

    /**
     * 只取当前录像分段前后的 GPS 点。
     * 同一天可能有多次总录像：不按分段筛选会把上一趟的轨迹连到这一趟，
     * 用户看到的就是“人没动、轨迹却跑到别处”。
     */
    private fun updateTrackForSegment(index: Int) {
        val seg = segments.getOrNull(index)
        if (seg == null) {
            trackPoints = emptyList()
            drawTrack()
            return
        }
        val base = dayStartEpoch(dateKey)
        val start = base + seg.startMs - 5_000
        val end = base + seg.endMs + 5_000
        val subset = allTrackPoints.filter { it.timeMs in start..end }
        trackPoints = sanitizeTrack(subset)
        lastMarkerEpoch = -1
        drawTrack()
    }

    /** 清洗旧数据：优先 GPS 点、过滤低精度和跳点，避免历史错误轨迹继续画乱 */
    private fun sanitizeTrack(raw: List<TrackPoint>): List<TrackPoint> {
        if (raw.isEmpty()) return emptyList()
        var pts = raw.filter {
            it.lat in -90.0..90.0 && it.lon in -180.0..180.0 &&
                !(it.lat == 0.0 && it.lon == 0.0)
        }.sortedBy { it.timeMs }
        val gpsOnly = pts.filter { it.provider.equals("gps", ignoreCase = true) }
        if (gpsOnly.isNotEmpty()) pts = gpsOnly
        val out = ArrayList<TrackPoint>(pts.size)
        for (p in pts) {
            if (p.accuracy > 80f) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                val dt = max(1L, (p.timeMs - prev.timeMs) / 1000L)
                val dist = CoordinateConverter.distanceMeters(prev.lat, prev.lon, p.lat, p.lon)
                val speed = max(prev.speed, p.speed).coerceAtLeast(0f)
                val maxJump = max(150.0, speed * dt + prev.accuracy + p.accuracy + 80.0)
                if (dist > maxJump) continue
            }
            out.add(p)
        }
        return out
    }

    private fun drawTrack() {
        val map = aMap ?: return
        map.clear()
        carMarker = null
        pastLine = null
        futureLine = null
        if (trackPoints.isEmpty()) {
            trackLatLngs = emptyList()
            binding.tvMapHint.text = getString(com.wheeltech.blindcam.R.string.playback_map_hint)
            return
        }
        binding.tvMapHint.text = "共 ${trackPoints.size} 个轨迹点"
        // Android 定位是 WGS-84，高德地图需要 GCJ-02；不转换会有几十到几百米的固定偏移
        val pts = trackPoints.map {
            val c = CoordinateConverter.wgs84ToGcj02(it.lat, it.lon)
            LatLng(c.lat, c.lon)
        }
        trackLatLngs = pts
        // 已走过（霓虹粉） + 未走过（冷灰）两条线，按播放进度动态分割
        pastLine = map.addPolyline(
            PolylineOptions()
                .addAll(pts)
                .width(16f)
                .color(0xFFFF6FB5.toInt())
                .geodesic(true)
        )
        futureLine = map.addPolyline(
            PolylineOptions()
                .addAll(pts)
                .width(8f)
                .color(0x667C8AA6)
                .geodesic(true)
        )
        // 车标（起点）
        carMarker = map.addMarker(
            MarkerOptions()
                .position(pts.first())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .anchor(0.5f, 0.5f)
        )
        updateTrackProgress(0)
        // 视野包含整条轨迹。newLatLngBounds 要求地图已完成布局（有尺寸），
        // 首次 onCreate 时地图可能还没布局，post 到布局后再移动；并给一个显式中心兜底。
        val b = com.amap.api.maps.model.LatLngBounds.Builder()
        pts.forEach { b.include(it) }
        val bounds = b.build()
        runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        }.onFailure {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    com.amap.api.maps.model.LatLng(
                        (bounds.southwest.latitude + bounds.northeast.latitude) / 2,
                        (bounds.southwest.longitude + bounds.northeast.longitude) / 2
                    ),
                    15f
                )
            )
        }
        binding.mapView.post {
            runCatching {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
            }
        }
    }

    /** 按播放进度更新车标位置（epoch = 当天 0 点 + 当前全局 ms） */
    private fun updateTrackMarker() {
        val marker = carMarker ?: return
        if (trackPoints.isEmpty()) return
        val epoch = dayStartEpoch(dateKey) + currentGlobalMs
        if (epoch == lastMarkerEpoch) return
        lastMarkerEpoch = epoch
        // 找时间最近的点
        val nearestIndex = trackPoints.indices.minByOrNull {
            abs(trackPoints[it].timeMs - epoch)
        } ?: return
        val nearest = trackPoints[nearestIndex]
        val mapPoint = CoordinateConverter.wgs84ToGcj02(nearest.lat, nearest.lon)
        marker.position = LatLng(mapPoint.lat, mapPoint.lon)
        if (nearest.bearing != 0f) marker.rotateAngle = nearest.bearing
        updateTrackProgress(nearestIndex)
        // 播放中：地图跟随车标（暂停时不抢用户手势）
        if (playing) {
            aMap?.animateCamera(CameraUpdateFactory.newLatLng(marker.position))
        }
    }

    /** 按当前车标位置把轨迹拆成“已走/未走”两段（需求：历史轨迹着色） */
    private fun updateTrackProgress(index: Int) {
        val pts = trackLatLngs
        if (pts.isEmpty()) return
        val idx = index.coerceIn(0, pts.size - 1)
        runCatching {
            pastLine?.points = pts.subList(0, idx + 1)
            futureLine?.points = pts.subList(idx, pts.size)
        }
    }

    private fun dayStartEpoch(key: String): Long {
        return try {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(key.substring(0, 4).toInt(),
                key.substring(4, 6).toInt() - 1,
                key.substring(6, 8).toInt())
            cal.timeInMillis
        } catch (t: Throwable) {
            0L
        }
    }

    // ------------------------------------------------------------------
    // Surface
    // ------------------------------------------------------------------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (surface == null) surface = Surface(st)
        if (segments.isEmpty()) {
            // 扫描还没开始才启动；正在后台扫描时不要重复 loadDate（会反复清屏）
            if (!loadRequested) loadDate()
            return
        }
        val pending = pendingLoad
        if (pending != null) {
            pendingLoad = null
            loadSegment(pending.index, pending.localMs, pending.play)
        } else {
            // surface 重建：把当前位置重新加载（重新挂到新 surface）
            val idx = segments.indexOfFirst { currentGlobalMs in it.startMs..it.endMs }
                .let { if (it < 0) 0 else it }
            val local = (currentGlobalMs - segments[idx].startMs).toInt().coerceAtLeast(0)
            loadSegment(idx, local, playing)
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        applyCellTransform()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onResume() {
        super.onResume()
        runCatching { binding.mapView.onResume() }
        // 从全屏播放器返回：仅当进入全屏前在播放时才恢复，避免“返回后自动响起”
        if (resumeAfterFullscreen) {
            resumeAfterFullscreen = false
            if (segments.isNotEmpty()) startPlay()
        }
    }

    override fun onPause() {
        // 离开页面（含进全屏、返回桌面）先暂停，防止后台继续解码耗电/被系统杀
        if (playing) pausePlay()
        super.onPause()
        runCatching { binding.mapView.onPause() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runCatching { binding.mapView.onSaveInstanceState(outState) }
    }

    override fun onDestroy() {
        loadToken++
        uiHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        surface = null
        runCatching { binding.mapView.onDestroy() }
        super.onDestroy()
    }

    private fun releasePlayer() {
        runCatching { player?.setSurface(null) }
        runCatching { player?.release() }
        player = null
        prepared = false
        curSegIndex = -1
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 从 ALL_yyyyMMdd_HHmmss 文件名取该段在当天的全局起点 ms */
    private fun parseAllTs(name: String): Long {
        return try {
            val m = Regex("ALL_(\\d{8})_(\\d{6})").find(name)
            val hhmmss = if (m != null) m.groupValues[2] else {
                // 兼容其它以 "_yyyyMMdd_HHmmss" 结尾的命名
                val parts = name.substringBeforeLast('.').split('_')
                parts.getOrNull(parts.size - 2) ?: return 0L
            }
            if (hhmmss.length < 6) return 0L
            val hh = hhmmss.substring(0, 2).toInt()
            val mm = hhmmss.substring(2, 4).toInt()
            val ss = hhmmss.substring(4, 6).toInt()
            (hh * 3600 + mm * 60 + ss) * 1000L
        } catch (t: Throwable) {
            0L
        }
    }

    private fun probeDuration(f: java.io.File): Long {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun probeDurationUri(uri: android.net.Uri): Long {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
           0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    companion object {
        private const val OW = 1280
        private const val OH = 720

        /** 从相册总录像卡进入时指定日期（yyyyMMdd） */
        const val EXTRA_DATE = "extra_date"
        /** 从相册总录像卡进入时定位到该时间点（epoch ms） */
        const val EXTRA_TIME_MS = "extra_time_ms"
    }
}

/** 由毫秒时间生成日期 key（yyyyMMdd）。GalleryMedia.dateKey 是实例属性，不能当静态函数调用 */
private fun dateKeyOf(ms: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

