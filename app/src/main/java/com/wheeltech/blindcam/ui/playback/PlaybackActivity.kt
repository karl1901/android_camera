package com.wheeltech.blindcam.ui.playback

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
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
import com.wheeltech.blindcam.UvcApp
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.wheeltech.blindcam.databinding.ActivityPlaybackBinding
import com.wheeltech.blindcam.domain.CameraSupervisor
import com.wheeltech.blindcam.location.TrackPoint
import com.wheeltech.blindcam.recorder.GalleryMedia
import com.wheeltech.blindcam.ui.viewer.VideoPlayerActivity
import com.wheeltech.blindcam.util.Logger
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.first

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
        val durationMs: Long
    )

    private var segments: List<SegInfo> = emptyList()
    private var curSegIndex = -1
    private var player: MediaPlayer? = null
    private var prepared = false
    private var surface: Surface? = null
    private var surfaceReady = false

    /** 当前播放位置（当天全局 ms） */
    private var currentGlobalMs = 0L
    /** 用户的播放意图（默认不自动播放 = false） */
    private var playing = false

    private var dateKey: String = dateKeyOf(System.currentTimeMillis())

    // 画面（cell）
    private var cellIndex = -1
    private var cellNames: List<String> = emptyList()
    private var cellRects: List<IntArray> = emptyList()

    // 拖动节流
    private var lastScrubSeek = 0L
    private var pendingScrubMs = -1L

    // 高德地图
    private var aMap: AMap? = null
    private var trackPoints: List<TrackPoint> = emptyList()
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

    private val nextSegTask = Runnable { advanceSegment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            map.mapType = AMap.MAP_TYPE_NORMAL
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

    private fun loadDate() {
        val app = application as UvcApp
        // 当前视频目录（私有默认 + 可能的 SAF 自定义）
        val videoTree = kotlinx.coroutines.runBlocking {
            app.settingsRepository.settings.first()
        }.videoDirUri
        // 扫描总录像（scanVideos 已合并私有目录与 SAF 自定义目录）
        val all = app.mediaSaver.scanVideos(videoTree)
            .filter { it.kind == GalleryMedia.Kind.ALL }
        // 当天总录像：同时支持私有 File 与 SAF 自定义目录 Uri（不能只认 file，
        // 否则用户把录像目录改到 SAF 后，当天文件会被误判为"没有总录像"）
        val dayMedia = all.filter { it.dateKey == dateKey && (it.file != null || it.uri != null) }
            .sortedBy { it.name }

        // 建立分段
        val list = ArrayList<SegInfo>()
        val dayStart = dayStartMs(dateKey)
        for (m in dayMedia) {
            // 时长优先用扫描时已探测的 durationMs；缺失时再现场探测
            val dur = m.durationMs.takeIf { it > 0 }
                ?: (m.file?.let { probeDuration(it) }
                    ?: m.uri?.let { probeDurationUri(it) } ?: 0L)
            if (dur <= 0) continue
            // 用文件时间戳定位全局起点（文件名 ALL_yyyyMMdd_HHmmss）
            val tsStart = parseAllTs(m.name, dayStart)
            list.add(SegInfo(m.file, m.uri, tsStart, tsStart + dur, dur))
        }
        releasePlayer()
        currentGlobalMs = 0
        cellIndex = -1
        lastMarkerEpoch = -1
        loadTrack(videoTree)

        if (list.isEmpty()) {
            segments = emptyList()
            curSegIndex = -1
            buildCells(0)
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
        buildCells(estimateCellCount())
        // 默认不自动播放：定位到第一段起点，保持暂停
        currentGlobalMs = list.first().startMs
        binding.timeline.setCurrent(currentGlobalMs)
        hideGap()
        binding.blackOverlay.visibility = View.GONE
        loadSegment(0, 0, false)
        binding.btnDate.text = dateKey
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
        binding.btnDate.text = dateKey
        binding.btnCenterPlay.visibility = View.GONE
    }

    private fun hideGap() {
        binding.tvGap.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // 画面（cell）
    // ------------------------------------------------------------------

    private fun estimateCellCount(): Int {
        val app = application as UvcApp
        val live = app.supervisor.slots.value.count { it.state != CameraSupervisor.SlotState.EMPTY }
        return live.coerceAtLeast(0)
    }

    /** 建立画面名称与裁剪矩形；count=0 时（无摄像头）给 4 个通用占位 */
    private fun buildCells(count: Int) {
        val app = application as UvcApp
        val live = app.supervisor.slots.value.filter { it.state != CameraSupervisor.SlotState.EMPTY }
        val names: List<String>
        val n: Int
        if (live.isNotEmpty()) {
            names = live.map { rt ->
                rt.config?.name?.takeIf { it.isNotBlank() }
                    ?: rt.device?.productName?.takeIf { it.isNotBlank() }
                    ?: "摄像头 ${rt.slot + 1}"
            }
            n = names.size
        } else {
            // 无摄像头（回看旧录像）：最多 4 个通用名
            n = 4
            names = List(4) { "摄像头 ${it + 1}" }
        }
        cellNames = names
        cellRects = List(n) { cellRect(it, n) }
        cellIndex = -1
        applyCellTransform()
        binding.btnCell.text = getString(com.wheeltech.blindcam.R.string.playback_all_cells)
        binding.tvCellName.text = ""
        binding.tvCellName.visibility = View.GONE
    }

    private fun cycleCell() {
        if (cellRects.isEmpty()) return
        cellIndex = if (cellIndex + 1 >= cellRects.size) -1 else cellIndex + 1
        applyCellTransform()
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

    /**
     * 固定像素矩阵（修复切换卡死）：
     * TextureView transform 工作在视图像素坐标。
     * 把目标 cell（视频坐标 x,y,w,h，视频 1280x720）放大到整个视图。
     */
    private fun applyCellTransform() {
        if (cellIndex < 0) {
            binding.playbackView.setTransform(Matrix())
            binding.playbackView.invalidate()
            return
        }
        val rect = cellRects.getOrNull(cellIndex) ?: return
        val cx = rect[0]; val cy = rect[1]; val cw = rect[2]; val ch = rect[3]
        val vw = binding.playbackView.width.toFloat()
        val vh = binding.playbackView.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val sx = OW.toFloat() / cw
        val sy = OH.toFloat() / ch
        val m = Matrix()
        m.setScale(sx, sy)
        m.postTranslate(-cx * vw / cw, -cy * vh / ch)
        binding.playbackView.setTransform(m)
        // 暂停时没有新帧，部分机型不会自动用新矩阵重绘缓存帧，强制重绘一次
        binding.playbackView.invalidate()
    }

    /** 与 CompositeRecorder 完全一致的 cell 矩形（1280x720） */
    private fun cellRect(index: Int, n: Int): IntArray = when (n) {
        1 -> intArrayOf(0, 0, OW, OH)
        2 -> when (index) {
            0 -> intArrayOf(0, 0, OW / 2, OH)
            else -> intArrayOf(OW / 2, 0, OW, OH)
        }
        3 -> when (index) {
            0 -> intArrayOf(0, 0, OW, OH / 2)
            1 -> intArrayOf(0, OH / 2, OW / 2, OH)
            else -> intArrayOf(OW / 2, OH / 2, OW, OH)
        }
        else -> {
            val col = index % 2
            val row = index / 2
            intArrayOf(col * OW / 2, row * OH / 2,
                (col + 1) * OW / 2, (row + 1) * OH / 2)
        }
    }

    /** 全屏：跟随当前画面（全部→全画面；某一路→裁剪该路） */
    private fun openFullscreen() {
        val seg = segments.getOrNull(curSegIndex) ?: return
        val intent = android.content.Intent(this, VideoPlayerActivity::class.java)
        seg.file?.let { intent.putExtra(VideoPlayerActivity.EXTRA_PATH, it.absolutePath) }
        seg.uri?.let { intent.putExtra(VideoPlayerActivity.EXTRA_URI, it.toString()) }
        intent.putExtra(VideoPlayerActivity.EXTRA_NAME,
            if (cellIndex < 0) getString(com.wheeltech.blindcam.R.string.playback_all_cells)
            else cellNames[cellIndex])
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
        if (index == curSegIndex && player != null) {
            seekLocal(localMs)
            if (play) startPlay() else pausePlay()
            return
        }
        releasePlayer()
        curSegIndex = index
        try {
            val mp = MediaPlayer()
            when {
                seg.file != null -> mp.setDataSource(seg.file.absolutePath)
                seg.uri != null -> mp.setDataSource(this, seg.uri)
            }
            surface?.let { mp.setSurface(it) }
            mp.isLooping = false
            mp.setOnPreparedListener {
                prepared = true
                it.seekTo(localMs)
                if (play) it.start()
                playing = play
                updatePlayIcon()
                scheduleNextSeg()
            }
            mp.setOnCompletionListener {
                if (curSegIndex < segments.size - 1) advanceSegment()
            }
            mp.setOnErrorListener { _, w, e ->
                Logger.w("playback", "error w=$w e=$e")
                true
            }
            mp.prepareAsync()
            player = mp
            currentGlobalMs = seg.startMs + localMs
        } catch (t: Throwable) {
            Logger.w("playback", "loadSegment failed: ${t.message}")
        }
    }

    private fun advanceSegment() {
        if (curSegIndex + 1 < segments.size) {
            loadSegment(curSegIndex + 1, 0, playing)
        }
    }

    private fun scheduleNextSeg() {
        uiHandler.removeCallbacks(nextSegTask)
        val seg = segments.getOrNull(curSegIndex) ?: return
        val remain = (seg.endMs - System.currentTimeMillis())
        // 用视频自身 completion 更可靠；这里不做定时兜底（completion 已覆盖）
    }

    private fun seekLocal(ms: Int) {
        val p = player ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            p.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            p.seekTo(ms)
        }
        currentGlobalMs = (segments.getOrNull(curSegIndex)?.startMs ?: 0) + ms
        binding.timeline.setCurrent(currentGlobalMs)
    }

    private fun onScrub(globalMs: Long, phase: TimelineView.Phase) {
        if (segments.isEmpty()) return
        // 找到包含该时刻的段
        val idx = segments.indexOfFirst { globalMs in it.startMs..it.endMs }
        if (idx < 0) {
            // 落在无录像段
            binding.tvGap.text = getString(com.wheeltech.blindcam.R.string.playback_no_content)
            binding.tvGap.setTextColor(Color.WHITE)
            binding.tvGap.visibility = View.VISIBLE
            pendingScrubMs = globalMs
            if (phase == TimelineView.Phase.END) hideGap()
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
                } else {
                    pendingScrubMs = globalMs
                }
            }
        }
        if (phase == TimelineView.Phase.END) seekLocal(local)
    }

    private fun togglePlay() {
        if (segments.isEmpty()) return
        val p = player
        if (!prepared || p == null) {
            // 未准备好：从当前位置开始
            val seg = segments.firstOrNull { currentGlobalMs in it.startMs..it.endMs }
            if (seg != null) loadSegment(segments.indexOf(seg), (currentGlobalMs - seg.startMs).toInt(), true)
            return
        }
        if (p.isPlaying) pausePlay() else startPlay()
    }

    private fun startPlay() {
        runCatching { player?.start() }
        playing = true
        binding.btnCenterPlay.visibility = View.GONE
        updatePlayIcon()
    }

    private fun pausePlay() {
        runCatching { player?.pause() }
        playing = false
        binding.btnCenterPlay.visibility = View.VISIBLE
        updatePlayIcon()
    }

    private fun seekBy(delta: Int) {
        val seg = segments.getOrNull(curSegIndex) ?: return
        val target = (currentGlobalMs + delta).coerceIn(segments.first().startMs, segments.last().endMs)
        onScrub(target, TimelineView.Phase.END)
    }

    private fun updatePlayIcon() {
        val isPlaying = runCatching { player?.isPlaying == true }.getOrDefault(playing)
        // btnPlayPause 是 TextView（与快进/快退风格一致），用文字切换
        binding.btnPlayPause.text = if (isPlaying) "⏸" else "▶"
    }

    private fun timeText(dayMs: Long): String {
        val h = dayMs / 3600_000
        val m = (dayMs % 3600_000) / 60_000
        val s = (dayMs % 60_000) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    // ------------------------------------------------------------------
    // 高德地图轨迹
    // ------------------------------------------------------------------

    /** 加载当天 GPS 轨迹点并绘制 */
    private fun loadTrack(videoTree: String) {
        trackPoints = runCatching {
            app.mediaSaver.readGpsTrack(videoTree, dateKey)
        }.getOrDefault(emptyList())
        drawTrack()
    }

    private fun drawTrack() {
        val map = aMap ?: return
        map.clear()
        carMarker = null
        if (trackPoints.isEmpty()) {
            binding.tvMapHint.text = getString(com.wheeltech.blindcam.R.string.playback_map_hint)
            return
        }
        binding.tvMapHint.text = "共 ${trackPoints.size} 个轨迹点"
        val pts = trackPoints.map { LatLng(it.lat, it.lon) }
        // 走过的轨迹（霓虹色）
        map.addPolyline(
            PolylineOptions()
                .addAll(pts)
                .width(16f)
                .color(0xFFFF6FB5.toInt())
                .geodesic(true)
        )
        // 车标（起点）
        carMarker = map.addMarker(
            MarkerOptions()
                .position(pts.first())
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .anchor(0.5f, 0.5f)
        )
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
        val nearest = trackPoints.minByOrNull { abs(it.timeMs - epoch) } ?: return
        marker.position = LatLng(nearest.lat, nearest.lon)
        if (nearest.bearing != 0f) marker.rotateAngle = nearest.bearing
        // 播放中：地图跟随车标（暂停时不抢用户手势）
        if (playing) {
            aMap?.animateCamera(CameraUpdateFactory.newLatLng(marker.position))
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
        surfaceReady = true
        if (segments.isEmpty()) {
            loadDate()
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
        surface?.release()
        surface = null
        surfaceReady = false
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onResume() {
        super.onResume()
        runCatching { binding.mapView.onResume() }
    }

    override fun onPause() {
        super.onPause()
        runCatching { binding.mapView.onPause() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runCatching { binding.mapView.onSaveInstanceState(outState) }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        surface?.release()
        surface = null
        runCatching { binding.mapView.onDestroy() }
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        prepared = false
        curSegIndex = -1
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun dayStartMs(dateKey: String): Long {
        // dateKey 仅用于解析；全局起点固定为当天 0 点
        return 0L
    }

    /** 从 ALL_yyyyMMdd_HHmmss 文件名取该段在当天的全局起点 ms */
    private fun parseAllTs(name: String, ignore: Long): Long {
        return try {
            val ts = name.removePrefix("ALL_").substringBefore('_')
            // 形如 yyyyMMdd_HHmmss
            val parts = name.split('_')
            // 文件名 ALL_yyyyMMdd_HHmmss_WxH.mp4
            val hhmmss = parts[2]
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
    }
}

/** 由毫秒时间生成日期 key（yyyyMMdd）。GalleryMedia.dateKey 是实例属性，不能当静态函数调用 */
private fun dateKeyOf(ms: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))
