package com.wheeltech.blindcam.ui.playback

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.SeekBar
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
import com.wheeltech.blindcam.recorder.MediaPart
import com.wheeltech.blindcam.ui.common.GlassMenuPopup
import com.wheeltech.blindcam.ui.common.ThemedActivity
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
class PlaybackActivity : ThemedActivity() {

    private lateinit var binding: ActivityPlaybackBinding
    private val uiHandler = Handler(Looper.getMainLooper())
    private val app: UvcApp get() = application as UvcApp

    private class SegInfo(
        val file: java.io.File?,
        val uri: android.net.Uri?,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        /** 录制时参与合成的画面名称（来自元数据）；旧录像为空 */
        val cellNames: List<String> = emptyList(),
        /** 新架构：每个画框一个独立 MP4；旧录像为空 */
        val parts: List<MediaPart> = emptyList(),
        val groupKey: String? = null
    ) {
        /** 当前可播放源：cellIndex<0 时用旧合成文件，否则用该路独立文件 */
        fun sourcesFor(cell: Int): List<MediaPart> {
            if (parts.isEmpty()) {
                return listOf(
                    MediaPart(
                        name = nameOfMain(),
                        file = file,
                        uri = uri
                    )
                )
            }
            return if (cell in parts.indices) listOf(parts[cell]) else parts
        }

        private fun nameOfMain(): String = file?.name ?: uri?.lastPathSegment ?: "ALL"
    }

    /** 后台扫描结果（扫描/探测时长/读 GPS 都不允许占用主线程） */
    private class LoadResult(
        val videoTree: String,
        val segments: List<SegInfo>,
        val track: List<TrackPoint>
    )

    private var segments: List<SegInfo> = emptyList()
    private var curSegIndex = -1
    private var prepared = false
    private val videoViews = ArrayList<TextureView>()
    private var readyCount = 0
    private var completedCount = 0
    private val players = LinkedHashMap<Int, MediaPlayer>()
    private val playerSurfaces = LinkedHashMap<Int, Surface>()
    private var pendingParts: List<MediaPart> = emptyList()
    private var pendingLocalMs = 0
    private var playWhenPrepared = false
    private var rangeStartMs = 0L
    private var rangeEndMs = 0L
    private var seekingFromUser = false
    private var resumeAfterScrub = false

    /** 当前播放位置（当天全局 ms） */
    private var currentGlobalMs = 0L
    /** 用户的播放意图（默认不自动播放 = false） */
    private var playing = false

    /** 从相册某条总录像进入时的定位时间（epoch ms） */
    private var pendingInitialTimeMs: Long? = null

    /** 进入全屏前是否正在播放：返回时恢复，而不是自动播放 */
    private var resumeAfterFullscreen = false

    /** 扫描代号：日期快速切换时丢弃过期结果 */
    private var loadToken = 0
    private var loadRequested = false

    private var dateKey: String = dateKeyOf(System.currentTimeMillis())

    // 画面（cell）
    private var cellIndex = -1
    private var cellNames: List<String> = emptyList()

    /** 拖动节流 */
    private var lastScrubSeek = 0L

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
                    val pos = runCatching { players.values.firstOrNull()?.currentPosition ?: 0 }.getOrDefault(0)
                    currentGlobalMs = seg.startMs + pos
                    if (!seekingFromUser) {
                        binding.seekBar.max = (rangeEndMs - rangeStartMs).toInt().coerceAtLeast(1)
                        binding.seekBar.progress = (currentGlobalMs - rangeStartMs).toInt()
                            .coerceIn(0, binding.seekBar.max)
                    }
                    binding.tvCurrent.text = timeText(currentGlobalMs)
                    updateTrackMarker()
                }
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 从相册点某条总录像进入：直接定位到那一天/那一段，而不是永远打开“今天”
        intent.getStringExtra(EXTRA_DATE)?.takeIf { it.length == 8 }?.let { dateKey = it }
        pendingInitialTimeMs = intent.getLongExtra(EXTRA_TIME_MS, 0L).takeIf { it > 0L }
        binding.btnDate.text = dateLabel(dateKey)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDate.setOnClickListener { pickDate() }
        binding.btnCell.setOnClickListener { showCellMenu() }
        binding.btnFullscreen.setOnClickListener { openFullscreen() }

        // 播放控制
        binding.btnPlayPause.setOnClickListener { togglePlay() }
        binding.btnCenterPlay.setOnClickListener { togglePlay() }
        binding.btnRewind.setOnClickListener { seekBy(-10_000) }
        binding.btnForward.setOnClickListener { seekBy(10_000) }

        // 普通进度条（与内置视频播放器一致）
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || segments.isEmpty()) return
                val now = SystemClock.uptimeMillis()
                if (now - lastScrubSeek >= 100) {
                    lastScrubSeek = now
                    onScrub(rangeStartMs + progress, TimelineView.Phase.MOVE)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seekingFromUser = true
                resumeAfterScrub = playing
                if (playing) pausePlay()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekingFromUser = false
                if (segments.isNotEmpty()) {
                    onScrub(rangeStartMs + (sb?.progress ?: 0), TimelineView.Phase.END)
                }
                if (resumeAfterScrub) {
                    resumeAfterScrub = false
                    startPlay()
                }
            }
        })

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
        binding.videoGrid.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }

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

        // 进入页面即扫描当天录像（旧版靠 TextureView surface 回调触发；
        // 改用 VideoView 后必须显式启动，否则一直停在加载黑屏）
        if (!loadRequested) loadDate()
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
            val totalMeta = m.groupKey?.let { gk ->
                runCatching { app.mediaSaver.readTotalMeta(gk, videoTree, m.file) }.getOrNull()
            }
            // 录制时写入的画面元数据（可能不存在：旧录像/旧版本）
            val meta = if (m.parts.isEmpty()) {
                runCatching { app.mediaSaver.readCompositeMeta(m.name, videoTree, m.file) }.getOrNull()
            } else null
            val cellNames = totalMeta?.cellNames?.takeIf { it.isNotEmpty() }
                ?: meta?.cellNames ?: emptyList()
            list.add(
                SegInfo(
                    m.file, m.uri, tsStart, tsStart + dur, dur,
                    cellNames,
                    m.parts,
                    m.groupKey
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
        binding.btnCell.text = getString(com.wheeltech.blindcam.R.string.playback_all_cells) + "  ▾"
        binding.tvCellName.text = ""
        binding.tvCellName.visibility = View.GONE
        binding.btnCenterPlay.visibility = View.GONE
        rangeStartMs = 0L
        rangeEndMs = 86_400_000L
        binding.seekBar.max = 86_400_000
        binding.seekBar.progress = 0
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
            rangeStartMs = 0L
            rangeEndMs = 86_400_000L
            binding.seekBar.max = 86_400_000
            binding.seekBar.progress = 0
            showEmptyDate()
            return
        }

        Logger.i("playback", "applyLoaded segments=${list.size}")
        segments = list
        val rangeStart = list.first().startMs
        val rangeEnd = list.last().endMs
        rangeStartMs = rangeStart
        rangeEndMs = rangeEnd
        binding.seekBar.max = (rangeEnd - rangeStart).toInt().coerceAtLeast(1)
        binding.seekBar.progress = 0
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
        updateSeekBar(currentGlobalMs)
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
        binding.btnCell.text = getString(com.wheeltech.blindcam.R.string.playback_all_cells) + "  ▾"
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
        cellIndex = -1
    }

    /**
     * 按“录制时”的画面元数据建立画面名称。
     * 新架构每路一个独立 MP4，不再需要视频像素裁剪矩形。
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
        val count = seg?.parts?.size?.takeIf { it > 0 }
            ?: (if (seg?.cellNames?.isNotEmpty() == true) seg.cellNames.size else names.size)
        setCells(names, count.coerceIn(1, 4))
    }

    private fun setCells(rawNames: List<String>, count: Int) {
        val n = count.coerceIn(1, 4)
        cellNames = List(n) { i -> rawNames.getOrNull(i) ?: "画面 ${i + 1}" }
        if (cellIndex >= n) cellIndex = -1
        updateCellLabel()
    }

    /** 与主页一致：点“全部画面/画面名”打开下拉菜单选择播放哪些画面 */
    private fun showCellMenu() {
        if (cellNames.isEmpty()) return
        val entries = ArrayList<GlassMenuPopup.Entry>()
        val actions = ArrayList<() -> Unit>()
        entries.add(GlassMenuPopup.Entry.Header(getString(R.string.playback_choose_cell)))
        actions.add {}
        entries.add(
            GlassMenuPopup.Entry.Item(
                getString(R.string.playback_all_cells),
                selected = cellIndex < 0
            )
        )
        actions.add { selectCell(-1) }
        cellNames.forEachIndexed { i, name ->
            entries.add(GlassMenuPopup.Entry.Item(name, selected = cellIndex == i))
            actions.add { selectCell(i) }
        }
        GlassMenuPopup(this, entries) { idx ->
            actions.getOrNull(idx)?.invoke()
        }.show(binding.btnCell)
    }

    private fun selectCell(index: Int) {
        val target = if (index < 0 || index >= cellNames.size) -1 else index
        if (target == cellIndex) return
        cellIndex = target
        updateCellLabel()
        // 新架构：全部/单路只是选择播放哪些独立文件，不做合成画面裁剪。
        val seg = segments.getOrNull(curSegIndex) ?: return
        val local = (currentGlobalMs - seg.startMs).toInt().coerceAtLeast(0)
        loadSegment(curSegIndex, local, playing)
    }

    private fun updateCellLabel() {
        val label = if (cellIndex < 0) {
            getString(com.wheeltech.blindcam.R.string.playback_all_cells)
        } else {
            cellNames.getOrNull(cellIndex).orEmpty()
        }
        binding.btnCell.text = "$label  ▾"
        binding.tvCellName.text = ""
        binding.tvCellName.visibility = View.GONE
    }

    /** 全屏：跟随当前画面（全部→全画面；某一路→裁剪该路） */
    private fun openFullscreen() {
        val seg = segments.getOrNull(curSegIndex) ?: return
        if (cellIndex < 0 && seg.parts.size > 1) {
            // 全画面是多路独立视频，交给单路全屏播放器会丢画面；要求先选一路
            android.widget.Toast.makeText(this, "全部画面请先切换到单路画面再全屏", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val part = if (cellIndex >= 0) seg.parts.getOrNull(cellIndex) else null
        resumeAfterFullscreen = playing
        val intent = android.content.Intent(this, VideoPlayerActivity::class.java)
        part?.file?.let { intent.putExtra(VideoPlayerActivity.EXTRA_PATH, it.absolutePath) }
        part?.uri?.let { intent.putExtra(VideoPlayerActivity.EXTRA_URI, it.toString()) }
        if (part == null) {
            seg.file?.let { intent.putExtra(VideoPlayerActivity.EXTRA_PATH, it.absolutePath) }
            seg.uri?.let { intent.putExtra(VideoPlayerActivity.EXTRA_URI, it.toString()) }
        }
        intent.putExtra(
            VideoPlayerActivity.EXTRA_NAME,
            if (cellIndex < 0) getString(com.wheeltech.blindcam.R.string.playback_all_cells)
            else cellNames.getOrNull(cellIndex).orEmpty()
        )
        intent.putExtra(
            VideoPlayerActivity.EXTRA_START_MS,
            runCatching { players.values.firstOrNull()?.currentPosition ?: 0 }.getOrDefault(0)
        )
        intent.putExtra(VideoPlayerActivity.EXTRA_AUTOPLAY, playing)
        startActivity(intent)
    }

    // ------------------------------------------------------------------
    // 播放 / 分段
    // ------------------------------------------------------------------

    private fun loadSegment(index: Int, localMs: Int, play: Boolean) {
        val seg = segments.getOrNull(index) ?: return
        releasePlayer()
        curSegIndex = index
        applySegmentCells(index)
        updateTrackForSegment(index)
        val sources = seg.sourcesFor(cellIndex).filter { it.file != null || it.uri != null }
        if (sources.isEmpty()) {
            binding.tvGap.text = getString(com.wheeltech.blindcam.R.string.playback_no_content)
            binding.tvGap.visibility = View.VISIBLE
            return
        }
        pendingLocalMs = localMs
        playWhenPrepared = play
        playing = play
        prepared = false
        readyCount = 0
        pendingParts = sources
        buildVideoViews(sources)
        Logger.i("playback", "load index=$index parts=${seg.parts.size} cell=$cellIndex sources=${sources.size} names=${cellNames.size}")
        currentGlobalMs = seg.startMs + localMs
        updateSeekBar(currentGlobalMs)
    }

    private fun buildVideoViews(parts: List<MediaPart>) {
        binding.videoGrid.removeAllViews()
        videoViews.clear()
        players.clear()
        playerSurfaces.clear()
        completedCount = 0
        readyCount = 0
        for (i in parts.indices) {
            val tv = TextureView(this)
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    startPlayer(i, parts[i], st)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    players[i]?.let { mp ->
                        applyFit(tv, mp.videoWidth, mp.videoHeight)
                    }
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    releasePlayerAt(i)
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            val cell = GridLayoutSpec.cell(parts.size.coerceIn(1, 4), i)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                rowSpec = GridLayout.spec(cell.row, cell.rowSpan, 1f)
                columnSpec = GridLayout.spec(cell.col, cell.colSpan, 1f)
            }
            binding.videoGrid.addView(tv, lp)
            videoViews.add(tv)
        }
        binding.videoGrid.post {
            videoViews.forEachIndexed { i, tv ->
                if (tv.isAvailable && !players.containsKey(i)) {
                    startPlayer(i, parts[i], tv.surfaceTexture)
                }
            }
        }
    }

    private fun startPlayer(index: Int, part: MediaPart, st: SurfaceTexture?) {
        if (st == null || players.containsKey(index)) return
        val tv = videoViews.getOrNull(index) ?: return
        val surface = Surface(st)
        val mp = MediaPlayer()
        try {
            when {
                part.file != null -> mp.setDataSource(part.file.absolutePath)
                part.uri != null -> mp.setDataSource(this, part.uri)
            }
            mp.setSurface(surface)
            mp.isLooping = false
            players[index] = mp
            playerSurfaces[index] = surface
            mp.setOnPreparedListener {
                runCatching { it.seekTo(pendingLocalMs) }
                applyFit(tv, it.videoWidth, it.videoHeight)
                readyCount++
                Logger.i("playback", "prepared view=$index/${pendingParts.size} size=${tv.width}x${tv.height}")
                if (readyCount >= pendingParts.size) {
                    prepared = true
                    if (playWhenPrepared) {
                        startPlay()
                    } else {
                        players.values.forEach { p -> runCatching { p.start() } }
                        uiHandler.postDelayed({
                            if (!playing) {
                                players.values.forEach { p -> runCatching { p.pause() } }
                                binding.btnCenterPlay.visibility = View.VISIBLE
                                updatePlayIcon()
                            }
                        }, 120)
                    }
                }
            }
            mp.setOnCompletionListener {
                if (playing && ++completedCount >= pendingParts.size) {
                    if (curSegIndex < segments.size - 1) advanceSegment()
                    else {
                        playing = false
                        playWhenPrepared = false
                        updatePlayIcon()
                        binding.btnCenterPlay.visibility = View.VISIBLE
                    }
                }
            }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                applyFit(tv, w, h)
            }
            mp.setOnErrorListener { _, what, extra ->
                Logger.w("playback", "MediaPlayer error what=$what extra=$extra")
                prepared = false
                playing = false
                updatePlayIcon()
                true
            }
            mp.prepareAsync()
        } catch (t: Throwable) {
            Logger.w("playback", "startPlayer failed: ${t.message}")
            runCatching { mp.release() }
            runCatching { surface.release() }
        }
    }

    private fun applyFit(tv: TextureView, videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        val vw = tv.width.toFloat()
        val vh = tv.height.toFloat()
        if (vw <= 0 || vh <= 0) {
            tv.post { applyFit(tv, videoW, videoH) }
            return
        }
        val m = Matrix()
        // 旧版总录像是一个 1280x720 合成文件，没有独立分路；选中某一路时只能裁切查看。
        // 新版每路独立 MP4（parts 非空）绝不做裁剪，按原始比例完整显示。
        val legacy = segments.getOrNull(curSegIndex)?.parts?.isEmpty() == true
        if (legacy && cellIndex >= 0 && cellNames.size > 1) {
            val n = cellNames.size.coerceIn(2, 4)
            val r = GridLayoutSpec.rect(n, cellIndex, videoW, videoH)
            val cw = (r.right - r.left).toFloat()
            val ch = (r.bottom - r.top).toFloat()
            if (cw > 0 && ch > 0) {
                val s = maxOf(vw / cw, vh / ch)
                m.setScale(s * videoW / vw, s * videoH / vh)
                m.postTranslate(
                    (vw - cw * s) / 2f - r.left * s,
                    (vh - ch * s) / 2f - r.top * s
                )
                tv.setTransform(m)
                tv.invalidate()
                return
            }
        }
        val s = maxOf(vw / videoW, vh / videoH)
        m.setScale(s * videoW / vw, s * videoH / vh)
        m.postTranslate((vw - videoW * s) / 2f, (vh - videoH * s) / 2f)
        tv.setTransform(m)
        tv.invalidate()
    }

    private fun releasePlayerAt(index: Int) {
        players.remove(index)?.let { mp ->
            runCatching { mp.setSurface(null) }
            runCatching { mp.release() }
        }
        playerSurfaces.remove(index)?.let { runCatching { it.release() } }
    }

    private fun advanceSegment() {
        if (curSegIndex + 1 < segments.size) {
            loadSegment(curSegIndex + 1, 0, playing)
        }
    }

    private fun seekLocal(ms: Int) {
        pendingLocalMs = ms.coerceAtLeast(0)
        if (prepared) {
            players.values.forEach { runCatching { it.seekTo(pendingLocalMs) } }
        }
        currentGlobalMs = (segments.getOrNull(curSegIndex)?.startMs ?: 0) + pendingLocalMs
        updateSeekBar(currentGlobalMs)
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
        if (phase == TimelineView.Phase.END) {
            seekLocal(local)
            if (!playing) refreshPausedFrame()
        }
    }

    /** 暂停拖动后强制出一帧：部分机型 seek 不刷新 TextureView 缓存帧 */
    private fun refreshPausedFrame() {
        if (playing || !prepared) return
        players.values.forEach { runCatching { it.start() } }
        uiHandler.postDelayed({
            if (!playing) {
                players.values.forEach { runCatching { it.pause() } }
                updatePlayIcon()
            }
        }, 120)
    }

    private fun togglePlay() {
        if (segments.isEmpty()) return
        if (!prepared) {
            playing = true
            playWhenPrepared = true
            binding.btnCenterPlay.visibility = View.GONE
            updatePlayIcon()
            return
        }
        val playingNow = players.values.firstOrNull()?.isPlaying == true
        if (playingNow) pausePlay() else startPlay()
    }

    private fun startPlay() {
        playing = true
        playWhenPrepared = true
        completedCount = 0
        if (prepared) players.values.forEach { runCatching { it.start() } }
        binding.btnCenterPlay.visibility = View.GONE
        updatePlayIcon()
    }

    private fun pausePlay() {
        playing = false
        playWhenPrepared = false
        if (prepared) players.values.forEach { runCatching { it.pause() } }
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
            players.values.firstOrNull()?.isPlaying == true
        } else {
            playing
        }
        binding.btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnCenterPlay.text = if (isPlaying) "❚❚" else "▶"
        binding.btnCenterPlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
    }

    private fun updateSeekBar(globalMs: Long) {
        if (rangeEndMs <= rangeStartMs) return
        val max = (rangeEndMs - rangeStartMs).toInt().coerceAtLeast(1)
        if (binding.seekBar.max != max) binding.seekBar.max = max
        binding.seekBar.progress = (globalMs - rangeStartMs).toInt().coerceIn(0, max)
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
    // 生命周期
    // ------------------------------------------------------------------

    override fun onResume() {
        if (recreateIfThemeChanged()) return
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
        runCatching { binding.mapView.onDestroy() }
        super.onDestroy()
    }

    private fun releasePlayer() {
        players.values.forEach { mp ->
            runCatching { mp.setSurface(null) }
            runCatching { mp.release() }
        }
        players.clear()
        playerSurfaces.values.forEach { runCatching { it.release() } }
        playerSurfaces.clear()
        videoViews.clear()
        runCatching { binding.videoGrid.removeAllViews() }
        prepared = false
        playing = false
        playWhenPrepared = false
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

















