package com.wheeltech.blindcam.vision

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.wheeltech.blindcam.util.Logger
import java.io.File

/**
 * 设备能力分级：根据 CPU 核心数、内存、SoC 型号估计 [DeviceTier]，
 * 让同一套代码在不同车机上跑不同精度/频率的模型。
 */
object DeviceCapability {

    private const val TAG = "vision-cap"

    /** 读取总内存（MB） */
    private fun totalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    /** 读取 CPU 最大频点（KHz，取所有核最大），失败返回 0 */
    private fun maxCpuFreqKhz(): Long {
        var max = 0L
        runCatching {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }
                ?.forEach { cpu ->
                    val f = File(cpu, "cpufreq/cpuinfo_max_freq")
                    if (f.exists()) {
                        val v = f.readText().trim().toLongOrNull() ?: 0
                        if (v > max) max = v
                    }
                }
        }
        return max
    }

    /** 评估当前设备能力分级 */
    fun evaluate(context: Context): DeviceTier {
        val cores = Runtime.getRuntime().availableProcessors()
        val ram = totalRamMb(context)
        val freq = maxCpuFreqKhz()
        val soc = Build.HARDWARE + " " + Build.BOARD

        val tier = when {
            // 弱车机：核少 / 内存小 / 频点低
            cores <= 4 || ram <= 2048 || (freq in 1..1_400_000) -> DeviceTier.LOW
            cores <= 6 || ram <= 4096 -> DeviceTier.MEDIUM
            cores <= 8 || ram <= 7000 -> DeviceTier.HIGH
            else -> DeviceTier.PREMIUM
        }
        Logger.i(TAG, "tier=$tier cores=$cores ram=${ram}MB maxFreq=${freq}KHz soc=$soc")
        return tier
    }

    /**
     * 各分级的推理参数（无模型时仅作配置，真实模型接入后生效）。
     * 返回：分析输入边长、分析间隔（ms）、是否允许红绿灯模型。
     */
    data class InferenceParams(
        val inputSize: Int,
        val intervalMs: Long,
        val enableTrafficLight: Boolean,
        val enableObjectModel: Boolean
    )

    fun paramsFor(tier: DeviceTier): InferenceParams = when (tier) {
        DeviceTier.LOW -> InferenceParams(0, 0, false, false)          // 仅运动检测
        DeviceTier.MEDIUM -> InferenceParams(224, 500, false, true)
        DeviceTier.HIGH -> InferenceParams(320, 333, true, true)
        DeviceTier.PREMIUM -> InferenceParams(416, 200, true, true)
    }
}
