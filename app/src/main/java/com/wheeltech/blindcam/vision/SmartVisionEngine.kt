package com.wheeltech.blindcam.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.wheeltech.blindcam.util.Logger
import java.util.concurrent.atomic.AtomicReference

/**
 * 智能视觉引擎（分级推理 + INT8 量化 + 主路优先 + 丢帧不排队 + 热降级）。
 *
 * 工程策略（同一套代码在不同车机跑不同精度）：
 * 1. 设备能力分级 [DeviceCapability]：决定输入分辨率/频率/是否跑模型。
 * 2. 热降级 [ThermalGovernor]：过热逐级降负载，直到回退运动检测。
 * 3. 主路优先：[primarySlot] 优先分析、间隔更短；其余路间隔更长。
 * 4. 丢帧不排队：每路只保留最新帧（AtomicReference）；分析线程忙时新帧直接丢弃，不堆积。
 * 5. INT8 占位：模型以 INT8 量化推理（接入模型后实现）；当前未内置模型，回退运动检测。
 *
 * 真实目标检测/红绿灯分类需训练并内置 INT8 模型；在此之前，盲区预警由
 * WarningManager 的运动检测提供，本引擎不产生分类结果（结果中 detections 为空）。
 */
class SmartVisionEngine(private val context: Context) {

    companion object {
        private const val TAG = "vision"
    }

    /** 主路（优先级最高的画面，默认 0） */
    @Volatile var primarySlot: Int = 0

    private val baseTier: DeviceTier = DeviceCapability.evaluate(context)
    private val thermalGovernor = ThermalGovernor(context)

    private val worker = HandlerThread("vision-infer").apply { start() }
    private val handler = Handler(worker.looper)

    /** 每路待分析的最新帧（latest-only；为 null 表示没有待处理帧） */
    private val pending = HashMap<Int, AtomicReference<Bitmap>>()

    /** 每路最后分析时间（用于按间隔采样） */
    private val lastRun = HashMap<Int, Long>()

    /** 每路最新结果（无模型时只反映 motion 占位） */
    private val results = HashMap<Int, VisionResult>()

    @Volatile private var released = false

    /** 模型是否可用（当前未内置，恒为 false）。接入 INT8 模型后改为检测模型加载。 */
    private val modelAvailable: Boolean = false

    fun start() {
        thermalGovernor.start()
        Logger.i(TAG, "engine started tier=$baseTier modelAvailable=$modelAvailable")
    }

    /**
     * 提交一路最新帧（在帧回调线程调用，非阻塞）。
     * 若该路已有待处理帧（分析线程还没取走），直接丢弃旧帧，保证不排队。
     */
    fun submit(slot: Int, bitmap: Bitmap) {
        if (released) return
        val params = effectiveParams()
        // 仅运动检测档：不进入模型推理（交给 WarningManager）
        if (!modelAvailable || params.inputSize <= 0) return
        val ref = pending.getOrPut(slot) { AtomicReference() }
        ref.set(bitmap)
        scheduleIfIdle(slot)
    }

    private fun effectiveParams(): DeviceCapability.InferenceParams {
        return thermalGovernor.effectiveParams(DeviceCapability.paramsFor(baseTier))
    }

    private fun scheduleIfIdle(slot: Int) {
        val params = effectiveParams()
        val now = SystemClock.uptimeMillis()
        val last = lastRun[slot] ?: 0L
        // 主路间隔不额外放大；非主路间隔×1.6（主路优先）
        val interval = if (slot == primarySlot) params.intervalMs
            else (params.intervalMs * 1.6).toLong()
        if (now - last < interval) return
        handler.post { processSlot(slot) }
    }

    private fun processSlot(slot: Int) {
        if (released) return
        val ref = pending[slot] ?: return
        val bmp = ref.getAndSet(null) ?: return
        lastRun[slot] = SystemClock.uptimeMillis()
        val params = effectiveParams()
        try {
            // 1) 缩放为模型输入（INT8 模型输入）
            val input = Bitmap.createScaledBitmap(bmp, params.inputSize, params.inputSize, true)
            // 2) INT8 推理占位：无模型时跳过；接入后在此调用 int8Interpreter.run(input)
            val detections = if (modelAvailable) runInt8Model(slot, input, params) else emptyList()
            val light = if (modelAvailable && params.enableTrafficLight)
                classifyTrafficLight(input) else TrafficLightState.UNKNOWN
            results[slot] = VisionResult(slot, motion = true, detections = detections, trafficLight = light)
            if (input != bmp) input.recycle()
        } catch (t: Throwable) {
            Logger.w(TAG, "infer failed slot=$slot: ${t.message}")
        }
    }

    /** INT8 模型推理（占位，接入模型后实现） */
    private fun runInt8Model(
        slot: Int,
        input: Bitmap,
        params: DeviceCapability.InferenceParams
    ): List<Detection> {
        // 真实实现：加载 INT8 TFLite 模型，按 primarySlot 分配优先级，输出检测框
        return emptyList()
    }

    /** 红绿灯分类（占位，接入模型后实现） */
    private fun classifyTrafficLight(input: Bitmap): TrafficLightState {
        return TrafficLightState.UNKNOWN
    }

    fun resultFor(slot: Int): VisionResult? = results[slot]

    fun stop() {
        if (released) return
        released = true
        thermalGovernor.stop()
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
        pending.clear()
        Logger.i(TAG, "engine stopped")
    }
}
