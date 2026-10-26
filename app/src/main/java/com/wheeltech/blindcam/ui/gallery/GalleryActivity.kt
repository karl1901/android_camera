package com.wheeltech.blindcam.ui.gallery

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.databinding.ActivityGalleryBinding
import com.wheeltech.blindcam.recorder.GalleryMedia
import com.wheeltech.blindcam.ui.common.DropdownMenuPopup
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
class GalleryActivity : AppCompatActivity() {

    private enum class Tab { PHOTOS, SPLIT, TOTAL }

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: GalleryAdapter

    private var tab = Tab.PHOTOS

    /** 当前 Tab 扫描到的全部媒体（未做设备/日期筛选） */
    private var scanned: List<GalleryMedia> = emptyList()

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

        adapter = GalleryAdapter(
            onItemClick = { pos -> onCardClick(pos) },
            onItemLongClick = { pos -> onCardLongClick(pos) },
            selectionState = {
                GalleryAdapter.SelectionState(selectMode, synchronized(selectedKeys) { LinkedHashSet(selectedKeys) })
            }
        )
        // 横屏车机：列数按宽度自适应（每列约 220dp）
        val columns = (resources.displayMetrics.widthPixels /
                (220 * resources.displayMetrics.density)).toInt().coerceIn(2, 6)
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

        switchTab(Tab.PHOTOS)
    }

    // ------------------------------------------------------------------
    // 数据加载 / 筛选
    // ------------------------------------------------------------------

    private fun switchTab(t: Tab) {
        tab = t
        // 切 Tab 退出多选并清筛选（设备筛选在不同 Tab 间选项不同）
        if (selectMode) exitSelectMode()
        deviceFilter = null
        dateFilter = null
        binding.btnDevice.setText(R.string.gallery_filter_device)
        binding.btnDate.setText(R.string.gallery_filter_date_all)
        updateTabStyles()
        reload()
    }

    private fun updateTabStyles() {
        setTabBg(binding.tabPhotos, tab == Tab.PHOTOS)
        setTabBg(binding.tabSplit, tab == Tab.SPLIT)
        setTabBg(binding.tabTotal, tab == Tab.TOTAL)
    }

    private fun setTabBg(v: TextView, selected: Boolean) {
        v.setBackgroundResource(if (selected) R.drawable.bg_pill_gradient else R.drawable.bg_pill)
        v.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFB9B3CC.toInt())
    }

    private fun reload() {
        lifecycleScope.launch {
            val app = application as UvcApp
            val list = withContext(Dispatchers.IO) {
                val s = app.settingsRepository.settings.first()
                when (tab) {
                    Tab.PHOTOS -> app.mediaSaver.scanPhotos(s.photoDirUri)
                    Tab.SPLIT -> app.mediaSaver.scanVideos(s.videoDirUri)
                        .filter { it.kind == GalleryMedia.Kind.SINGLE }
                    Tab.TOTAL -> app.mediaSaver.scanVideos(s.videoDirUri)
                        .filter { it.kind == GalleryMedia.Kind.ALL }
                }
            }
            scanned = list
            applyFilter()
        }
    }

    private fun applyFilter() {
        var list = scanned
        deviceFilter?.let { name -> list = list.filter { it.device == name } }
        dateFilter?.let { key -> list = list.filter { it.dateKey == key } }
        adapter.submit(list)
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
        DropdownMenuPopup(this, options, selected) { idx ->
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
        DropdownMenuPopup(this, options, selected) { idx ->
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
            // 删除后不退出多选；清空已删项并实时刷新
            keys.forEach { selectedKeys.remove(it) }
            toast(getString(R.string.gallery_delete_ok, ok))
            reload()
            // reload 完成后仍保持多选态：reload 不退出多选，但 applyFilter 会刷新
            if (selectMode) {
                binding.barTopNormal.visibility = View.GONE
                binding.barTopSelect.visibility = View.VISIBLE
                binding.barBottomSelect.visibility = View.VISIBLE
                binding.btnTimeline.visibility = View.GONE
                updateSelectTitle()
            }
        }
    }

    private fun toast(m: String) {
        com.google.android.material.snackbar.Snackbar.make(binding.root, m,
            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        // 从查看器返回时刷新（期间可能有新文件）
        reload()
    }
}
