package com.wheeltech.blindcam.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.databinding.ActivitySettingsBinding
import com.wheeltech.blindcam.domain.CameraSupervisor
import com.wheeltech.blindcam.ui.main.MainViewModel
import com.wheeltech.blindcam.util.Logger
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 独立设置页（深色玻璃拟态）：
 * 分组玻璃卡片：显示与主题 / 保存位置 / 单路设备（名称·镜像·旋转·降噪·预警） /
 * 录像叠加(OSD) / 码率与画质 / 循环录像 / GPS 轨迹 / 日志与排查。
 * 所有改动即时保存；目录显示完整路径；主题切换重建生效。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var viewModel: MainViewModel
    private val app: UvcApp get() = application as UvcApp

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as UvcApp
        setTheme(app.themeResId(app.currentThemeMode))
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[MainViewModel::class.java]

        binding.btnBack.setOnClickListener { finish() }
        buildContent()
    }

    private fun buildContent() {
        lifecycleScope.launch {
            val s = app.settingsRepository.settings.first()
            val container = binding.cardContainer
            container.removeAllViews()

            // 1. 显示与主题
            card(container, "显示与主题") { c ->
                c.addView(label("主题"))
                c.addView(
                    segments(
                        listOf(
                            getString(com.wheeltech.blindcam.R.string.theme_system),
                            getString(com.wheeltech.blindcam.R.string.theme_dark),
                            getString(com.wheeltech.blindcam.R.string.theme_light),
                            getString(com.wheeltech.blindcam.R.string.theme_tech_blue)
                        ), s.themeMode.ordinal
                    ) { i ->
                        val theme = AppSettings.ThemeMode.entries[i]
                        lifecycleScope.launch {
                            app.settingsRepository.setThemeMode(theme)
                            app.applyTheme(theme)
                            recreate()
                        }
                    }
                )
                c.addView(label("显示布局"))
                c.addView(
                    segments(
                        listOf(
                            getString(com.wheeltech.blindcam.R.string.layout_auto),
                            getString(com.wheeltech.blindcam.R.string.layout_single)
                        ), s.layoutMode.ordinal
                    ) { i ->
                        lifecycleScope.launch {
                            app.settingsRepository.setLayoutMode(AppSettings.LayoutMode.entries[i])
                        }
                    }
                )
                c.addView(label("开机与后台"))
                c.addView(switchRow("开机自动启动本应用", s.bootAutoStart) { checked ->
                    viewModel.setBootAutoStart(checked)
                    toast(if (checked) "已开启开机自启动" else "已关闭开机自启动")
                })
            }

            // 2. 保存位置
            card(container, "保存位置") { c ->
                c.addView(label(getString(com.wheeltech.blindcam.R.string.settings_photo_dir)))
                c.addView(pathRow(app.mediaSaver.photoDisplayPath(s.photoDirUri)) {
                    photoDirLauncher.launch(null)
                })
                if (s.photoDirUri.isNotBlank()) {
                    c.addView(linkRow("恢复为应用默认目录") {
                        lifecycleScope.launch { viewModel.resetPhotoDir(); toast("已恢复默认目录"); buildContent() }
                    })
                }
                c.addView(label(getString(com.wheeltech.blindcam.R.string.settings_video_dir)))
                c.addView(pathRow(app.mediaSaver.videoDisplayPath(s.videoDirUri)) {
                    videoDirLauncher.launch(null)
                })
                if (s.videoDirUri.isNotBlank()) {
                    c.addView(linkRow("恢复为应用默认目录") {
                        lifecycleScope.launch { viewModel.resetVideoDir(); toast("已恢复默认目录"); buildContent() }
                    })
                }
            }

            // 3. 单路设备（标签页切换，避免多设备时设置模块重复堆叠）
            val live = viewModel.slots.value.filter { it.state != CameraSupervisor.SlotState.EMPTY }
            card(container, "单路设备") { c ->
                if (live.isEmpty()) {
                    c.addView(label("暂无已接入摄像头，接入后可在此设置名称、镜像、旋转、降噪与预警"))
                } else {
                    val tabRow = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(2), 0, dp(6))
                    }
                    val tabs = ArrayList<TextView>()
                    val detail = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
                    fun select(slot: Int) {
                        tabs.forEach { tv ->
                            val on = tv.tag as Int == slot
                            tv.background = if (on) {
                                themeDrawable(com.wheeltech.blindcam.R.attr.pillSelectedBg)
                                    ?: ContextCompat.getDrawable(
                                        this@SettingsActivity,
                                        com.wheeltech.blindcam.R.drawable.bg_pill_gradient
                                    )
                            } else {
                                themeDrawable(com.wheeltech.blindcam.R.attr.pillBg)
                                    ?: ContextCompat.getDrawable(
                                        this@SettingsActivity,
                                        com.wheeltech.blindcam.R.drawable.bg_pill
                                    )
                            }
                            tv.setTextColor(
                                if (on) Color.WHITE
                                else themeColor(com.google.android.material.R.attr.colorOnSurface)
                            )
                        }
                        detail.removeAllViews()
                        live.firstOrNull { it.slot == slot }?.let { rt ->
                            renderDeviceDetail(detail, rt)
                        }
                    }
                    live.forEach { rt ->
                        val name = rt.config?.name?.takeIf { it.isNotBlank() }
                            ?: rt.device?.productName?.takeIf { it.isNotBlank() }
                            ?: "摄像头 ${rt.slot + 1}"
                        val tab = TextView(this@SettingsActivity).apply {
                            text = name
                            tag = rt.slot
                            textSize = 13f
                            gravity = Gravity.CENTER
                            minWidth = dp(90)
                            setPadding(dp(18), dp(9), dp(18), dp(9))
                            isClickable = true
                            setOnClickListener { select(rt.slot) }
                        }
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(8) }
                        tabRow.addView(tab, lp)
                        tabs.add(tab)
                    }
                    c.addView(
                        android.widget.HorizontalScrollView(this@SettingsActivity).apply {
                            isHorizontalScrollBarEnabled = false
                            addView(tabRow)
                        }
                    )
                    c.addView(detail)
                    select(live.first().slot)
                }
            }

            // 4. 录像叠加 OSD
            card(container, "录像叠加（OSD）") { c ->
                c.addView(label("单路与总录像均生效"))
                c.addView(switchRow("左上角显示自定义名称", s.osdName) { viewModel.setOsdName(it) })
                c.addView(switchRow("右上角显示系统时间", s.osdClock) { viewModel.setOsdClock(it) })
            }

            // 5. 码率与画质
            card(container, "码率与画质") { c ->
                c.addView(label("单路分录像（自动≈高画质，1080p30 约 9Mbps、4K30 约 37Mbps）"))
                c.addView(segments(listOf("自动", "省流", "标准", "高画质", "原画"),
                    s.singleBitrateQuality) { viewModel.setSingleBitrateQuality(it) })
                c.addView(label("总录像（按合成画面路数自动提高码率）"))
                c.addView(segments(listOf("自动", "省流", "标准", "高画质", "原画"),
                    s.compositeBitrateQuality) { viewModel.setCompositeBitrateQuality(it) })
            }

            // 6. 循环录像
            card(container, "循环录像（总录像）") { c ->
                c.addView(switchRow("开启循环录像（分段存储，空间不足覆盖最旧段）", s.loopEnabled) {
                    viewModel.setLoopEnabled(it)
                    buildContent()
                })
                if (s.loopEnabled) {
                    c.addView(label("单段时长"))
                    val segValues = listOf(1, 3, 5, 10)
                    c.addView(
                        segments(listOf("1分钟", "3分钟", "5分钟", "10分钟"),
                            segValues.indexOf(s.loopSegmentMinutes).let { if (it < 0) 1 else it }) {
                            viewModel.setLoopSegmentMinutes(segValues[it])
                        }
                    )
                    c.addView(label("保留剩余空间"))
                    val freeValues = listOf(512, 1024, 2048, 5120)
                    c.addView(
                        segments(listOf("512MB", "1GB", "2GB", "5GB"),
                            freeValues.indexOf(s.loopKeepFreeMb).let { if (it < 0) 1 else it }) {
                            viewModel.setLoopKeepFreeMb(freeValues[it])
                        }
                    )
                }
            }

            // 7. GPS 轨迹
            card(container, "GPS 轨迹（总录像）") { c ->
                c.addView(label("开启后总录像记录行车轨迹，回看页联动地图；车机无 GPS 自动跳过"))
                c.addView(switchRow("总录像记录 GPS 轨迹", s.gpsEnabled) { checked ->
                    if (checked) {
                        if (hasLocationPermission()) viewModel.setGpsEnabled(true)
                        else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    } else viewModel.setGpsEnabled(false)
                })
            }

            // 8. 预警音量
            card(container, "预警音量") { c ->
                c.addView(label("提示音音量：${s.warnVolume}"))
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val seek = SeekBar(this@SettingsActivity).apply {
                    max = 100; progress = s.warnVolume
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val value = TextView(this@SettingsActivity).apply {
                    text = "${s.warnVolume}"; setPadding(dp(12), 0, 0, 0)
                    setTextColor(themeColor(com.wheeltech.blindcam.R.attr.onSurfaceDim))
                }
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        value.text = progress.toString()
                        if (fromUser) viewModel.setWarnVolume(progress)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                row.addView(seek); row.addView(value)
                c.addView(row)
                c.addView(label("预警使用警报流合成蜂鸣，不依赖系统通知铃声；可点下方按钮试听"))
                c.addView(pillButton("测试蜂鸣", true) {
                    app.warningManager.testBeep()
                    toast("已触发测试蜂鸣（1 秒）")
                })
            }

            // 9. 日志与排查
            card(container, "日志与排查") { c ->
                c.addView(label("出现问题时导出全部运行日志，可发送给开发者排查"))
                c.addView(pillButton("导出运行日志（手动选择文件夹）", true) {
                    logDirLauncher.launch(null)
                })
            }
        }
    }

    /** 单设备详情（标签页切换时复用，避免每个设备重复堆叠整块设置） */
    private fun renderDeviceDetail(c: LinearLayout, rt: CameraSupervisor.SlotRuntime) {
        c.addView(sectionTitle("画面 ${rt.slot + 1} 设置"))
        c.addView(label("自定义名称（与设备唯一 ID 绑定，重启保持）"))
        c.addView(nameEdit(rt.config?.name.orEmpty()) { txt ->
            viewModel.rename(rt.slot, txt)
        })
        c.addView(switchRow("镜像翻转", rt.config?.mirror == true) {
            viewModel.setMirror(rt.slot, it)
        })
        c.addView(label("旋转角度（当前 ${rt.config?.rotation ?: 0}°）"))
        c.addView(
            segments(listOf("0°", "90°", "180°", "270°"),
                ((rt.config?.rotation ?: 0) / 90).coerceIn(0, 3)) { i ->
                viewModel.setRotation(rt.slot, i * 90)
            }
        )
        c.addView(label("实时降噪 / 夜视增强（按该画框分辨率与帧率独立处理）"))
        c.addView(label("弱/中/强逐级增强暗部与去噪；强档为多帧时域累积，运动物体不拖影"))
        c.addView(
            segments(listOf("关闭", "弱", "中", "强"),
                (rt.config?.denoise ?: 0).coerceIn(0, 3)) {
                viewModel.setDenoise(rt.slot, it)
            }
        )
        c.addView(switchRow("开启盲区预警", rt.config?.warn == true) {
            viewModel.setWarn(rt.slot, it)
        })
    }

    // ------------------------------------------------------------------
    // 构建辅助
    // ------------------------------------------------------------------

    private fun card(container: LinearLayout, title: String, body: (LinearLayout) -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = themeDrawable(com.wheeltech.blindcam.R.attr.cardBg)
                ?: ContextCompat.getDrawable(this@SettingsActivity, com.wheeltech.blindcam.R.drawable.bg_glass_card)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10); bottomMargin = dp(6)
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val bodyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body(bodyBox)
        card.addView(bodyBox)
        container.addView(card, lp)
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(themeColor(com.wheeltech.blindcam.R.attr.accentColor))
        textSize = 14f
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(themeColor(com.wheeltech.blindcam.R.attr.onSurfaceDim))
        textSize = 13f
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun linkRow(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(themeColor(com.wheeltech.blindcam.R.attr.onSurfaceDim))
        textSize = 12f
        gravity = Gravity.END
        setPadding(0, dp(2), dp(4), dp(6))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun pathRow(path: String, onClick: () -> Unit) = TextView(this).apply {
        text = "📁  $path\n点击可更改"
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        background = themeDrawable(com.wheeltech.blindcam.R.attr.pillBg)
            ?: ContextCompat.getDrawable(this@SettingsActivity, com.wheeltech.blindcam.R.drawable.bg_pill)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setLineSpacing(dp(2).toFloat(), 1f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun nameEdit(current: String, onDone: (String) -> Unit) = EditText(this).apply {
        setText(current)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        setHintTextColor(themeColor(com.wheeltech.blindcam.R.attr.onSurfaceDim))
        textSize = 14f
        setSingleLine(true)
        background = themeDrawable(com.wheeltech.blindcam.R.attr.pillBg)
            ?: ContextCompat.getDrawable(this@SettingsActivity, com.wheeltech.blindcam.R.drawable.bg_pill)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onDone(text.toString().trim())
        }
    }

    /** 开关行：自定义胶囊 + 文字（避免系统 Switch 样式不统一） */
    private fun switchRow(labelText: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val tv = TextView(this).apply {
            text = labelText
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggle = TextView(this).apply {
            text = if (checked) "●" else "○"
            textSize = 22f
            setTextColor(if (checked) 0xFFFF6FB5.toInt() else 0xFF8A8499.toInt())
            setPadding(dp(12), 0, 0, 0)
        }
        var state = checked
        row.setOnClickListener {
            state = !state
            toggle.text = if (state) "●" else "○"
            toggle.setTextColor(if (state) 0xFFFF6FB5.toInt() else 0xFF8A8499.toInt())
            onChange(state)
        }
        row.addView(tv); row.addView(toggle)
        return row
    }

    /** 分段选择：胶囊组，选中渐变 */
    private fun segments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(6))
        }
        val views = ArrayList<TextView>()
        fun refresh(sel: Int) {
            views.forEachIndexed { i, tv ->
                tv.background = if (i == sel) {
                    themeDrawable(com.wheeltech.blindcam.R.attr.pillSelectedBg)
                        ?: ContextCompat.getDrawable(
                            this@SettingsActivity,
                            com.wheeltech.blindcam.R.drawable.bg_pill_gradient
                        )
                } else {
                    themeDrawable(com.wheeltech.blindcam.R.attr.pillBg)
                        ?: ContextCompat.getDrawable(
                            this@SettingsActivity,
                            com.wheeltech.blindcam.R.drawable.bg_pill
                        )
                }
                tv.setTextColor(
                    if (i == sel) Color.WHITE
                    else themeColor(com.google.android.material.R.attr.colorOnSurface)
                )
            }
        }
        labels.forEachIndexed { i, lab ->
            val tv = TextView(this).apply {
                text = lab
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                isClickable = true
                setOnClickListener { onSelect(i); refresh(i) }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            row.addView(tv, lp); views.add(tv)
        }
        refresh(selected)
        return row
    }

    private fun pillButton(text: String, gradient: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackground(
            if (gradient) {
                themeDrawable(com.wheeltech.blindcam.R.attr.pillSelectedBg)
                    ?: ContextCompat.getDrawable(
                        this@SettingsActivity,
                        com.wheeltech.blindcam.R.drawable.bg_pill_gradient
                    )
            } else {
                themeDrawable(com.wheeltech.blindcam.R.attr.pillBg)
                    ?: ContextCompat.getDrawable(
                        this@SettingsActivity,
                        com.wheeltech.blindcam.R.drawable.bg_pill
                    )
            }
        )
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 解析当前主题的颜色属性（浅色/深色/科技蓝自适应） */
    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun themeDrawable(attr: Int): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, tv, true) || tv.resourceId == 0) return null
        return ContextCompat.getDrawable(this, tv.resourceId)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // Launchers
    // ------------------------------------------------------------------

    private val photoDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                app.mediaSaver.persistTreeUri(uri)
                lifecycleScope.launch {
                    app.settingsRepository.setPhotoDirUri(uri.toString())
                    toast("照片保存位置已更新"); buildContent()
                }
            }
        }

    private val videoDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                app.mediaSaver.persistTreeUri(uri)
                lifecycleScope.launch {
                    app.settingsRepository.setVideoDirUri(uri.toString())
                    toast("录像保存位置已更新"); buildContent()
                }
            }
        }

    private val logDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                app.mediaSaver.persistTreeUri(uri)
                viewModel.exportLogs(uri.toString()) { msg -> toast(msg) }
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.setGpsEnabled(true); toast("已开启 GPS 轨迹记录")
            } else {
                viewModel.setGpsEnabled(false); toast("未授予定位权限，无法记录 GPS 轨迹")
            }
            buildContent()
        }

    private fun toast(m: String) {
        Snackbar.make(binding.root, m, Snackbar.LENGTH_SHORT).show()
    }
}

