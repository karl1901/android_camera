package com.wheeltech.blindcam.ui.gallery

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.DatePicker
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.databinding.ActivityGalleryBinding
import com.wheeltech.blindcam.recorder.GalleryMedia
import com.wheeltech.blindcam.ui.viewer.PhotoViewerActivity
import com.wheeltech.blindcam.ui.viewer.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 内置相册：照片 / 分录像 / 总录像三分类，按设备分组、按日期筛选，
 * 点击用系统查看器（照片/视频）打开。
 *
 * 数据来源：应用私有目录 + 用户 SAF 自定义目录（[com.wheeltech.blindcam.recorder.MediaSaver] 扫描）。
 */
class GalleryActivity : AppCompatActivity() {

    private enum class Tab(val label: String) {
        PHOTO("照片"), SINGLE("分录像"), ALL("总录像")
    }

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: GalleryAdapter

    private var photos: List<GalleryMedia> = emptyList()
    private var videos: List<GalleryMedia> = emptyList()

    private var currentTab = Tab.PHOTO
    private var deviceFilter: String? = null   // null=全部设备
    private var dateFilter: String? = null     // null=全部日期，yyyyMMdd

    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme((application as UvcApp).themeResId((application as UvcApp).currentThemeMode))
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = GalleryAdapter(this) { openBuiltIn(it) }
        binding.grid.adapter = adapter
        // GridView 级兜底：item 根视图已处理点击，这里再兜一层
        binding.grid.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                (parent.getItemAtPosition(position) as? GalleryMedia)
                    ?.let { openBuiltIn(it) }
            }

        binding.btnBack.setOnClickListener { finish() }

        Tab.values().forEach { tab ->
            binding.tabLayout.addTab(
                binding.tabLayout.newTab().setText(tab.label)
            )
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                currentTab = Tab.entries[t.position]
                deviceFilter = null
                refresh()
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        binding.spinnerDevice.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    val selected = parent?.getItemAtPosition(position) as? String
                    deviceFilter = if (selected == null || selected == DEVICE_ALL) null else selected
                    refresh()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        binding.btnDate.setOnClickListener { showDateFilter() }
        // 时间轴回看页（总录像时间轴 + 多路预览 + 轨迹地图）
        binding.btnTimeline.setOnClickListener {
            startActivity(Intent(this, com.wheeltech.blindcam.ui.playback.PlaybackActivity::class.java))
        }

        loadData()
    }

    private fun loadData() {
        val app = application as UvcApp
        lifecycleScope.launch {
            val s = app.settingsRepository.settings.first()
            val (p, v) = withContext(Dispatchers.IO) {
                app.mediaSaver.scanPhotos(s.photoDirUri) to
                    app.mediaSaver.scanVideos(s.videoDirUri)
            }
            photos = p
            videos = v
            refresh()
        }
    }

    /** 当前 Tab 对应的全部数据 */
    private fun tabData(): List<GalleryMedia> = when (currentTab) {
        Tab.PHOTO -> photos
        Tab.SINGLE -> videos.filter { it.kind == GalleryMedia.Kind.SINGLE }
        Tab.ALL -> videos.filter { it.kind == GalleryMedia.Kind.ALL }
    }

    private fun refresh() {
        val data = tabData()
        // 重建设备筛选下拉
        val devices = LinkedHashSet<String>().apply {
            data.forEach { add(if (it.kind == GalleryMedia.Kind.ALL) "总录像" else it.device) }
        }
        val options = mutableListOf(DEVICE_ALL).apply { addAll(devices.sorted()) }
        val spinnerAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, options
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerDevice.adapter = spinnerAdapter
        val selectIdx = options.indexOf(deviceFilter).coerceAtLeast(0)
        binding.spinnerDevice.setSelection(selectIdx)

        val filtered = data.filter { m ->
            (deviceFilter == null ||
                (if (m.kind == GalleryMedia.Kind.ALL) deviceFilter == "总录像"
                 else m.device == deviceFilter)) &&
                (dateFilter == null || m.dateKey == dateFilter)
        }.sortedByDescending { it.timeMs }

        adapter.submit(filtered)
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = getString(R.string.gallery_empty)
    }

    private fun showDateFilter() {
        val items = arrayOf(
            getString(R.string.gallery_filter_date_all),
            getString(R.string.gallery_filter_date_today),
            getString(R.string.gallery_filter_date_pick)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.gallery_filter_date)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { dateFilter = null; updateDateButton(); refresh() }
                    1 -> {
                        dateFilter = dateFmt.format(Date())
                        updateDateButton(); refresh()
                    }
                    2 -> showDatePicker()
                }
            }
            .show()
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        android.app.DatePickerDialog(
            this,
            { _: DatePicker, y: Int, m: Int, d: Int ->
                val c = Calendar.getInstance().apply { set(y, m, d) }
                dateFilter = dateFmt.format(c.time)
                updateDateButton()
                refresh()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateButton() {
        val key = dateFilter
        binding.btnDate.text = if (key == null) {
            getString(R.string.gallery_filter_date_all)
        } else {
            val parsed = dateFmt.parse(key) ?: Date()
            val pretty = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsed)
            getString(R.string.gallery_filter_date_fmt, pretty)
        }
    }

    /**
     * 用软件内置查看器打开照片/视频（不调用任何系统看图器/播放器）。
     * - 私有目录文件：直接传绝对路径（同应用进程，可读自己的私有文件）
     * - SAF 自定义目录：传 content uri（已持有持久化读权限）
     * 视频（含总录像/分录像）走内置 [VideoPlayerActivity]，照片走内置 [PhotoViewerActivity]。
     */
    private fun openBuiltIn(item: GalleryMedia) {
        val path = item.file?.absolutePath
        val uriStr = item.uri?.toString()
        if (path.isNullOrBlank() && uriStr.isNullOrBlank()) {
            com.wheeltech.blindcam.util.Logger.w("gallery", "open 失败：无 path/uri，name=${item.name}")
            return
        }
        val title = item.device + " · " + item.name
        val intent = if (item.isVideo) {
            Intent(this, VideoPlayerActivity::class.java)
        } else {
            Intent(this, PhotoViewerActivity::class.java)
        }
        intent.apply {
            if (!path.isNullOrBlank()) putExtra(VideoPlayerActivity.EXTRA_PATH, path)
            if (!uriStr.isNullOrBlank()) {
                putExtra(VideoPlayerActivity.EXTRA_URI, uriStr)
                // 保险：对 content uri 授予读权限（内置 Activity 同 UID，通常无需）
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            putExtra(VideoPlayerActivity.EXTRA_NAME, title)
            putExtra(VideoPlayerActivity.EXTRA_TIME_MS, item.timeMs)
        }
        com.wheeltech.blindcam.util.Logger.i(
            "gallery", "open built-in video=${item.isVideo} pos name=${item.name}"
        )
        runCatching { startActivity(intent) }.onFailure {
            com.wheeltech.blindcam.util.Logger.w("gallery", "open 失败 name=${item.name}: ${it.message}")
            android.widget.Toast.makeText(
                this, R.string.viewer_open_failed, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.shutdown()
    }

    companion object {
        private const val DEVICE_ALL = "全部设备"
    }
}
