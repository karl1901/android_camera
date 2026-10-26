package com.wheeltech.blindcam.vision

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.wheeltech.blindcam.util.Logger

/**
 * 热降级：监听系统热状态，过热时逐级降低推理负载，直到回退到纯运动检测。
 *
 * 策略（用工程换性能）：
 * - 正常：按设备能力分级跑
 * - 轻度发热：降分析频率（间隔×2）
 * - 中度发热：降输入分辨率 + 关闭红绿灯模型
 * - 严重发热：关闭模型，仅保留运动检测
 */
class ThermalGovernor(private val context: Context) {

    companion object {
        private const val TAG = "thermal"
    }

    /** 热降级等级（0=正常，越大越热） */
    @Volatile var degradeLevel: Int = 0
        private set

    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val l = PowerManager.OnThermalStatusChangedListener { status -> onStatus(status) }
        listener = l
        runCatching {
            pm.addThermalStatusListener(l)
            onStatus(pm.currentThermalStatus)
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        listener?.let { runCatching { pm.removeThermalStatusListener(it) } }
        listener = null
    }

    private fun onStatus(status: Int) {
        val level = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> 0
            PowerManager.THERMAL_STATUS_MODERATE -> 1
            PowerManager.THERMAL_STATUS_SEVERE -> 2
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> 3
            else -> 0
        }
        if (level != degradeLevel) {
            degradeLevel = level
            Logger.w(TAG, "thermal status=$status degradeLevel=$level")
        }
    }

    /**
     * 结合设备分级与热降级，给出最终生效的推理参数。
     */
    fun effectiveParams(base: DeviceCapability.InferenceParams): DeviceCapability.InferenceParams {
        return when (degradeLevel) {
            0 -> base
            1 -> base.copy(intervalMs = base.intervalMs * 2L)
            2 -> base.copy(
                inputSize = (base.inputSize * 3 / 4).coerceAtLeast(224),
                intervalMs = base.intervalMs * 2L,
                enableTrafficLight = false
            )
            else -> DeviceCapability.InferenceParams(0, 0L, false, false)
        }
    }
}
