package com.wheeltech.blindcam.ui.gallery

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.databinding.ActivityGalleryBinding
import com.wheeltech.blindcam.recorder.GalleryMedia
import com.wheeltech.blindcam.ui.common.GlassMenuPopup
import com.wheeltech.blindcam.ui.common.ThemedActivity
import com.wheeltech.blindcam.ui.playback.PlaybackActivity
import com.wheeltech.blindcam.ui.viewer.PhotoViewerActivity
import com.wheeltech.blindcam.ui.viewer.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale

/** 由毫秒时间生成日期 key（yyyyMMdd），用于日期筛选（GalleryMedia.dateKey 是实例属性） */
private fun dateKeyOf(ms: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

/**
 * 相册（深色玻璃拟态）：
 * - 三个 Tab：照片 / 分录像 / 总录像
 * - 设备筛选（自定义全宽下拉，首击即选）、日期筛选
 * - 卡片首击即打开（照片→内置看图器；视频→内置播放器）
 * - 长按进入多选：卡片右上角勾选、点任意处勾选；顶栏全选/选择项目/X；底栏删除；删除后实时刷新
 */
class GalleryActivity : ThemedActivity() {

    private enum class Tab { PHOTOS, SPLIT, TOTAL }

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: GalleryAdapter

    private var tab = Tab.PHOTOS

    /** 当前 Tab 扫描到的全部媒体（未做设备/日期筛选） */
    private var scanned: List<GalleryMedia> = emptyList()

    /** 三个 Tab 的扫描结果缓存：切换 Tab 立即显示，不再每次重新扫描/探测时长 */
    private val tabCache = HashMap<Tab, List<GalleryMedia>>()
    private var loading = false

    /** 设备筛选：选中的设备显示名；null=全部 */
    private var deviceFilter: String? = null
    /** 日期筛选：dateKey；null=全部 */
    private var dateFilter: String? = null

    // ---- 多选 ----
    private var selectMode = false
    private val selectedKeys: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 进程可能在竖屏配置下启动，Activity 才切横屏；这里刷新当前方向尺寸
        com.wheeltech.blindcam.domain.DevicePerformance.updateDisplay(this)

        // 横屏车机/手机/平板：按屏幕宽 dp 计算列数，卡片不再固定 220dp 占半屏
        val columns = com.wheeltech.blindcam.domain.DevicePerformance.galleryColumns()
        val screenW = com.wheeltech.blindcam.domain.DevicePerformance.screenWidthPx
        val thumbHeightPx = ((screenW.toFloat() / columns) * 0.62f).toInt()
        adapter = GalleryAdapter(
            onItemClick = { pos -> onCardClick(pos) },
            onItemLongClick = { pos -> onCardLongClick(pos) },
            selectionState = {
                GalleryAdapter.SelectionState(selectMode, synchronized(selectedKeys) { LinkedHashSet(selectedKeys) })
            },
            thumbHeightPx = thumbHeightPx
        )
        binding.recycler.layoutManager = GridLayoutManager(this, columns)
        binding.recycler.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDevice.setOnClickListener { showDeviceDropdown() }
        binding.btnDate.setOnClickListener { showDateMenu() }
        binding.btnTimeline.setOnClickListener {
            startActivity(Intent(this, PlaybackActivity::class.java))
        }

        // 多选顶栏
        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnCloseSelect.setOnClickListener { exitSelectMode() }
        binding.btnDelete.setOnClickListener { deleteSelected() }

        // Tabs
        binding.tabPhotos.setOnClickListener { switchTab(Tab.PHOTOS) }
        binding.tabSplit.setOnClickListener { switchTab(Tab.SPLIT) }
        binding.tabTotal.setOnClickListener { switchTab(Tab.TOTAL) }

        // 进入相册时一次性后台预加载三个 Tab，之后切换就是内存数据，无扫描延迟
        switchTab(Tab.PHOTOS, refreshIfMissing = false)
        preloadAll()
    }

    // ------------------------------------------------------------------
    // 数据加载 / 筛选
    // ------------------------------------------------------------------

    private fun switchTab(t: Tab, refreshIfMissing: Boolean = true) {
        tab = t
        // 切 Tab 退出多选并清筛选（设备筛选在不同 Tab 间选项不同）
        if (selectMode) exitSelectMode()
        deviceFilter = null
        dateFilter = null
        binding.btnDevice.setText(R.string.gallery_filter_device)
        binding.btnDate.setText(R.string.gallery_filter_date_all)
        updateTabStyles()
        val cached = tabCache[t]
        scanned = cached ?: emptyList()
        if (cached == null && loading) {
            showLoading()
        } else {
            applyFilter()
        }
        if (cached == null && refreshIfMissing && !loading) refreshTab(t, showLoading = true)
    }

    /**
     * 预加载：照片与视频并行扫描，谁先完成就先更新对应 Tab；
     * 分录像、总录像从视频结果派生，之后切换 Tab 不再重复扫描/探测时长。
     */
    private fun preloadAll() {
        if (loading) return
        loading = true
        showLoading()
        var pending = 2
        fun oneDone() {
            pending--
            if (pending <= 0) loading = false
        }
        val app = application as UvcApp
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { app.settingsRepository.settings.first() }
            launch {
                runCatching { withContext(Dispatchers.IO) { app.mediaSaver.scanPhotos(s.photoDirUri) } }
                    .onSuccess { list ->
                        tabCache[Tab.PHOTOS] = list
                        if (tab == Tab.PHOTOS) {
                            scanned = list
                            applyFilter()
                        }
                    }
                oneDone()
            }
            launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        app.mediaSaver.scanVideos(s.videoDirUri)
                    }
                }.onSuccess { videos ->
                    tabCache[Tab.SPLIT] = videos.filter { it.kind == GalleryMedia.Kind.SINGLE }
                    tabCache[Tab.TOTAL] = videos.filter { it.kind == GalleryMedia.Kind.ALL }
                    if (tab == Tab.SPLIT || tab == Tab.TOTAL) {
                        scanned = tabCache[tab] ?: emptyList()
                        applyFilter()
                    }
                }
                oneDone()
            }
        }
    }

    /** 只刷新一个 Tab（从播放器返回、删除后、设置目录变化） */
    private fun refreshTab(t: Tab, showLoading: Boolean = false, onDone: (() -> Unit)? = null) {
        val seq = ++loadSeq
        if (showLoading) showLoading()
        lifecycleScope.launch {
            val app = application as UvcApp
            val list = withContext(Dispatchers.IO) {
                val s = app.settingsRepository.settings.first()
                when (t) {
                    Tab.PHOTOS -> app.mediaSaver.scanPhotos(s.photoDirUri)
                    Tab.SPLIT -> app.mediaSaver.scanVideos(s.videoDirUri)
                        .filter { it.kind == GalleryMedia.Kind.SINGLE }
                    Tab.TOTAL -> app.mediaSaver.scanVideos(s.videoDirUri)
                        .filter { it.kind == GalleryMedia.Kind.ALL }
                }
            }
            if (seq != loadSeq) return@launch
            tabCache[t] = list
            if (tab == t && !isFinishing && !isDestroyed) {
                scanned = list
                applyFilter()
                onDone?.invoke()
            }
        }
    }

    private fun showLoading() {
        binding.tvEmpty.text = getString(R.string.gallery_loading)
        binding.tvEmpty.visibility = View.VISIBLE
        adapter.submit(emptyList())
    }

    private fun updateTabStyles() {
        setTabBg(binding.tabPhotos, tab == Tab.PHOTOS)
        setTabBg(binding.tabSplit, tab == Tab.SPLIT)
        setTabBg(binding.tabTotal, tab == Tab.TOTAL)
    }

    private fun setTabBg(v: TextView, selected: Boolean) {
        v.background = if (selected) {
            themeDrawable(R.attr.pillSelectedBg)
                ?: androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_pill_gradient)
        } else {
            themeDrawable(R.attr.pillBg)
                ?: androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_pill)
        }
        v.setTextColor(if (selected) 0xFFFFFFFF.toInt() else themeColor(
            com.google.android.material.R.attr.colorOnSurface
        ))
    }

    private fun themeDrawable(attr: Int): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, tv, true) || tv.resourceId == 0) return null
        return androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId)
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    /** 扫描代号：快速切 Tab/返回时只应用最后一次结果 */
    private var loadSeq = 0

    private fun applyFilter() {
        var list = scanned
        deviceFilter?.let { name -> list = list.filter { it.device == name } }
        dateFilter?.let { key -> list = list.filter { it.dateKey == key } }
        adapter.submit(list)
        binding.tvEmpty.setText(R.string.gallery_empty)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // 设备筛选 / 日期
    // ------------------------------------------------------------------

    private fun showDeviceDropdown() {
        // 设备选项来自当前 Tab 全部扫描（不受当前设备/日期筛选影响）
        val names = scanned.map { it.device }.distinct()
        val options = mutableListOf(getString(R.string.gallery_filter_device_all))
        options.addAll(names)
        val selected = deviceFilter?.let { names.indexOf(it) + 1 } ?: 0
        GlassMenuPopup(
            this,
            options.mapIndexed { i, text ->
                GlassMenuPopup.Entry.Item(text, selected = i == selected)
            }
        ) { idx ->
            deviceFilter = if (idx == 0) null else names[idx - 1]
            binding.btnDevice.text = deviceFilter ?: getString(R.string.gallery_filter_device)
            applyFilter()
        }.show(binding.btnDevice)
    }

    private fun showDateMenu() {
        // 简单选项：全部日期 / 今日 / 选择日期
        val options = listOf(
            getString(R.string.gallery_filter_date_all),
            getString(R.string.gallery_filter_date_today),
            getString(R.string.gallery_filter_date_pick)
        )
        val selected = when {
            dateFilter == null -> 0
            dateFilter == dateKeyOf(System.currentTimeMillis()) -> 1
            else -> 2
        }
        GlassMenuPopup(
            this,
            options.mapIndexed { i, text ->
                GlassMenuPopup.Entry.Item(text, selected = i == selected)
            }
        ) { idx ->
            when (idx) {
                0 -> { dateFilter = null; binding.btnDate.setText(R.string.gallery_filter_date_all); applyFilter() }
                1 -> {
                    dateFilter = dateKeyOf(System.currentTimeMillis())
                    binding.btnDate.text = dateFilter
                    applyFilter()
                }
                else -> pickDate()
            }
        }.show(binding.btnDate)
    }

    private fun pickDate() {
        val c = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val cal = Calendar.getInstance()
                cal.set(y, m, d)
                dateFilter = dateKeyOf(cal.timeInMillis)
                binding.btnDate.text = dateFilter
                applyFilter()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ------------------------------------------------------------------
    // 点击 / 长按
    // ------------------------------------------------------------------

    private fun onCardClick(pos: Int) {
        val m = adapter.get(pos) ?: return
        if (selectMode) {
            toggleSelection(m)
        } else {
            openMedia(m)
        }
    }

    private fun onCardLongClick(pos: Int) {
        val m = adapter.get(pos) ?: return
        if (!selectMode) enterSelectMode()
        toggleSelection(m)
    }

    private fun openMedia(m: GalleryMedia) {
        if (m.isVideo) {
            if (m.kind == GalleryMedia.Kind.ALL) {
                // 总录像：进入回看页（时间轴、画面切换、GPS 轨迹联动），而不是简单播放器
                val intent = Intent(this, PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_DATE, m.dateKey)
                    putExtra(PlaybackActivity.EXTRA_TIME_MS, m.timeMs)
                }
                startActivity(intent)
                return
            }
            val intent = Intent(this, VideoPlayerActivity::class.java)
            putExtras(intent, m)
            startActivity(intent)
        } else {
            val intent = Intent(this, PhotoViewerActivity::class.java)
            putExtras(intent, m)
            startActivity(intent)
        }
    }

    private fun putExtras(intent: Intent, m: GalleryMedia) {
        m.file?.let { intent.putExtra(PhotoViewerActivity.EXTRA_PATH, it.absolutePath) }
        m.uri?.let { intent.putExtra(PhotoViewerActivity.EXTRA_URI, it.toString()) }
        intent.putExtra(PhotoViewerActivity.EXTRA_NAME, m.device)
        intent.putExtra(PhotoViewerActivity.EXTRA_TIME_MS, m.timeMs)
    }

    // ------------------------------------------------------------------
    // 多选
    // ------------------------------------------------------------------

    private fun enterSelectMode() {
        selectMode = true
        selectedKeys.clear()
        binding.barTopNormal.visibility = View.GONE
        binding.barTopSelect.visibility = View.VISIBLE
        binding.barBottomSelect.visibility = View.VISIBLE
        binding.btnTimeline.visibility = View.GONE
        updateSelectTitle()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectMode() {
        selectMode = false
        selectedKeys.clear()
        binding.barTopNormal.visibility = View.VISIBLE
        binding.barTopSelect.visibility = View.GONE
        binding.barBottomSelect.visibility = View.GONE
        binding.btnTimeline.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(m: GalleryMedia) {
        val key = m.keyOf()
        if (!selectedKeys.add(key)) selectedKeys.remove(key)
        updateSelectTitle()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelectAll() {
        val current = adapter.currentList()
        if (selectedKeys.size == current.size) {
            selectedKeys.clear()
        } else {
            current.forEach { selectedKeys.add(it.keyOf()) }
        }
        updateSelectTitle()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectTitle() {
        binding.tvSelectTitle.text =
            getString(R.string.gallery_select_count, selectedKeys.size)
        binding.btnSelectAll.text = getString(
            if (selectedKeys.size == adapter.itemCount && adapter.itemCount > 0)
                R.string.gallery_deselect_all else R.string.gallery_select_all
        )
    }

    private fun deleteSelected() {
        if (selectedKeys.isEmpty()) {
            toast(getString(R.string.gallery_delete_none))
            return
        }
        val targets = synchronized(selectedKeys) { ArrayList(selectedKeys) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.gallery_delete_confirm_title)
            .setMessage(getString(R.string.gallery_delete_confirm_msg, targets.size))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_confirm) { _, _ -> performDelete(targets) }
            .show()
    }

    private fun performDelete(keys: List<String>) {
        lifecycleScope.launch {
            val app = application as UvcApp
            var ok = 0
            withContext(Dispatchers.IO) {
                // 从当前扫描结果里找到对应媒体并删除
                val toDelete = scanned.filter { it.keyOf() in keys }
                toDelete.forEach { if (app.mediaSaver.deleteMedia(it)) ok++ }
            }
            // 删除后不退出多选；先本地移除再后台刷新
            keys.forEach { selectedKeys.remove(it) }
            scanned = scanned.filter { it.keyOf() !in keys }
            tabCache.remove(tab)
            applyFilter()
            toast(getString(R.string.gallery_delete_ok, ok))
            refreshTab(tab) {
                // 刷新完成后仍保持多选态，并刷新“已选择 N 项/全选”文案
                if (selectMode) {
                    binding.barTopNormal.visibility = View.GONE
                    binding.barTopSelect.visibility = View.VISIBLE
                    binding.barBottomSelect.visibility = View.VISIBLE
                    binding.btnTimeline.visibility = View.GONE
                    updateSelectTitle()
                }
            }
        }
    }

    private fun toast(m: String) {
        com.google.android.material.snackbar.Snackbar.make(binding.root, m,
            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
    }

    override fun onResume() {
        if (recreateIfThemeChanged()) return
        super.onResume()
        // 从查看器/播放器/回看返回时刷新当前 Tab（期间可能有新文件）；其他 Tab 用缓存
        if (!loading) refreshTab(tab)
    }
}
