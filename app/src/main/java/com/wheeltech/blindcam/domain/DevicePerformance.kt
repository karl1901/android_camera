package com.wheeltech.blindcam.domain

import android.content.Context
import android.content.res.Configuration
import com.wheeltech.blindcam.util.Logger
import com.wheeltech.blindcam.vision.DeviceCapability
import com.wheeltech.blindcam.vision.DeviceTier

/**
 * 应用启动时读取一次的设备画像，供全局调度使用。
 *
 * 目标：不同车机/手机（核心数、内存、屏幕尺寸/DPI/形态）用同一套代码，
 * 由这里给出预算参数，避免“低端车机上开强降噪/多路 4K 直接卡死”，
 * 也避免“高端手机上按钮/卡片过大”。
 *
 * 注意：只做轻量系统 API 读取（无轮询、无 wakeup），启动一次后常驻。
 */
object DevicePerformance {

    private const val TAG = "device-profile"

    @Volatile var initialized = false
        private set
    @Volatile var tier: DeviceTier = DeviceTier.MEDIUM
        private set
    @Volatile var cores: Int = 4
        private set
    @Volatile var ramMb: Long = 4096
        private set
    @Volatile var density: Float = 1f
        private set
    @Volatile var screenWidthPx: Int = 1920
        private set
    @Volatile var screenHeightPx: Int = 1080
        private set
    @Volatile var screenWidthDp: Int = 960
        private set
    @Volatile var screenHeightDp: Int = 540
        private set
    @Volatile var isLandscape: Boolean = true
        private set

    /** 降噪预算倍率：弱设备放宽预算，避免刚开就 overBudget 回落原图 */
    @Volatile var denoiseBudgetScale: Float = 1f
        private set

    /** 盲区预警分析间隔（ms），弱设备降低分析频率 */
    @Volatile var warningIntervalMs: Long = 200L
        private set

    /** 总录像合成目标帧率 */
    @Volatile var compositeFps: Int = 15
        private set

    /** 相册卡片最小宽度（dp），用于自适应列数 */
    @Volatile var galleryMinCardDp: Int = 180
        private set

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        updateDisplay(context)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        ramMb = (mi.totalMem / (1024 * 1024)).coerceAtLeast(512)
        cores = Runtime.getRuntime().availableProcessors()
        // 与 DeviceCapability 共用分级（CPU 频率/内存/核数）
        tier = DeviceCapability.evaluate(context)
        when (tier) {
            DeviceTier.LOW -> {
                denoiseBudgetScale = 1.6f
                warningIntervalMs = 320L
                compositeFps = 12
                galleryMinCardDp = 170
            }
            DeviceTier.MEDIUM -> {
                denoiseBudgetScale = 1.3f
                warningIntervalMs = 260L
                compositeFps = 15
                galleryMinCardDp = 175
            }
            DeviceTier.HIGH -> {
                denoiseBudgetScale = 1.0f
                warningIntervalMs = 200L
                compositeFps = 15
                galleryMinCardDp = 180
            }
            DeviceTier.PREMIUM -> {
                denoiseBudgetScale = 0.9f
                warningIntervalMs = 180L
                compositeFps = 18
                galleryMinCardDp = 190
            }
        }
        Logger.i(
            TAG,
            "tier=$tier cores=$cores ram=${ramMb}MB size=${screenWidthPx}x${screenHeightPx} " +
                "dp=${screenWidthDp}x${screenHeightDp} density=$density landscape=$isLandscape"
        )
        persist(context)
    }

    /**
     * 应用进程可能先以竖屏配置启动（例如开机自启/后台服务），随后 Activity 强制横屏。
     * 相册列数、缩略图高度必须使用当前方向的尺寸，所以在页面 onCreate 再刷新一次。
     */
    fun updateDisplay(context: Context) {
        val dm = context.resources.displayMetrics
        val cfg = context.resources.configuration
        density = if (dm.density > 0) dm.density else 1f
        screenWidthDp = cfg.screenWidthDp
        screenHeightDp = cfg.screenHeightDp
        // displayMetrics 在部分横屏 ROM 仍返回竖屏物理尺寸；configuration 是当前方向
        screenWidthPx = (screenWidthDp * density).toInt().coerceAtLeast(1)
        screenHeightPx = (screenHeightDp * density).toInt().coerceAtLeast(1)
        isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun persist(context: Context) {
        // 轻量落盘，便于后续日志/诊断/按机型调参（不参与每帧热路径）
        runCatching {
            context.getSharedPreferences("device_profile", Context.MODE_PRIVATE).edit()
                .putString("tier", tier.name)
                .putInt("cores", cores)
                .putLong("ram_mb", ramMb)
                .putInt("width_px", screenWidthPx)
                .putInt("height_px", screenHeightPx)
                .putInt("width_dp", screenWidthDp)
                .putInt("height_dp", screenHeightDp)
                .putFloat("density", density)
                .putBoolean("landscape", isLandscape)
                .apply()
        }
    }

    /** 按屏宽 dp 与最小卡片宽度计算相册列数，避免一张卡占半屏 */
    fun galleryColumns(): Int {
        val minCard = galleryMinCardDp.coerceAtLeast(140)
        return (screenWidthDp / minCard).coerceIn(2, 6)
    }

    /**
     * UI 紧凑比例：同时看宽和高。
     * 车机横屏常见 1280×720 / 1280×480，手机横屏短边只有 320–360dp；
     * 不能只用宽度判断，否则 766×320dp 的手机也会用“平板”字号/大按钮。
     */
    fun uiScale(): Float {
        val shortDp = minOf(screenWidthDp, screenHeightDp).coerceAtLeast(200)
        val byHeight = when {
            shortDp >= 700 -> 1.08f
            shortDp >= 480 -> 1.00f
            shortDp >= 380 -> 0.94f
            else -> 0.88f
        }
        val byWidth = when {
            screenWidthDp >= 1200 -> 1.06f
            screenWidthDp >= 900 -> 1.02f
            screenWidthDp >= 600 -> 1.00f
            else -> 0.94f
        }
        return (byHeight * byWidth).coerceIn(0.82f, 1.14f)
    }
}
