package com.wheeltech.blindcam.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.wheeltech.blindcam.data.DeviceConfig
import com.wheeltech.blindcam.data.SettingsRepository
import com.wheeltech.blindcam.usb.UsbDeviceInfo
import com.wheeltech.blindcam.usb.UsbMonitor
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * 摄像头常驻状态机（Domain 层）。
 *
 * 不依赖 Activity / ViewModel，挂在 Application 上：前台后台都持续运行，
 * 保证「任何时候打开都能显示所有已授权摄像头」。
 *
 * 职责：
 * - 枚举当前 USB 设备，按持久化的 [DeviceConfig.order] 计算每路应处的槽位
 * - 自动 / 一键 / 手动申请 USB 授权（多个设备串行弹窗，避免互相吞掉）
 * - 持续 reconcile：已授权设备 → 打开 → 按最大/用户选定档位取流；失败指数退避重试
 * - 热插拔：插入自动授权并显示，拔出关闭并让后续画面自动前移
 * - 暴露 [slots] 状态流供 UI 渲染；所有用户设置（顺序/名称/镜像/旋转/档位）落盘
 */
class CameraSupervisor(
    context: Context,
    private val manager: UvcCameraManager,
    private val settings: SettingsRepository
) {
    companion object {
        private const val TAG = "supervisor"
        private const val RECONCILE_PERIOD_MS = 1500L
        private const val MAX_RETRY_BACKOFF_MS = 8000L
        /** 每轮对齐最多尝试的档位数量（带宽不足时逐级降档） */
        private const val ATTEMPT_PER_ROUND = 8
        /** 设备完整尝试所有档位仍失败的轮数上限：达到后停止自动重试，等待用户手动点击 */
        private const val MAX_FAIL_ROUNDS = 6
    }

    enum class SlotState { EMPTY, NO_PERMISSION, OPENING, STREAMING, ERROR }

    data class SlotRuntime(
        val slot: Int,
        val key: String? = null,
        val device: UsbDeviceInfo? = null,
        val state: SlotState = SlotState.EMPTY,
        val capabilities: List<StreamProfile> = emptyList(),
        val activeProfile: StreamProfile? = null,
        val config: DeviceConfig? = null,
        val error: String? = null
    )

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 申请 USB 设备权限前必须先持有 CAMERA 运行时权限，否则部分系统上
     * USB 权限请求会被静默吞掉（不弹窗、立即返回拒绝）。
     */
    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    private val reconcileLock = Mutex()
    private val permissionLock = Mutex()

    private val _slots = MutableStateFlow(List(UvcCameraManager.MAX_SLOTS) { SlotRuntime(it) })
    val slots: StateFlow<List<SlotRuntime>> = _slots.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    @Volatile private var configs: Map<String, DeviceConfig> = emptyMap()
    @Volatile private var started = false

    /** 失败退避：deviceId -> 下次允许重试时间(ms)。并发访问（UI/周期对齐）用并发容器 */
    private val nextRetryAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** 已达失败上限、放弃自动重试的 deviceId：保持占位，等待用户手动点击重试（避免无限取流拖垮其他路） */
    private val givenUp: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 上一轮已对齐的「已授权设备」key 集合；用于检测新插入/新授权设备并触发全局重协商 */
    private var lastAuthKeys: Set<String> = emptySet()

    /** 本次运行已自动尝试过授权的设备 key（避免对未授权设备反复弹窗骚扰） */
    private val autoTried: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 新加入但尚未出流的设备：用于“按需”重协商带宽，而不是一插入就打断所有已稳定画面 */
    private val pendingJoinKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 已为某次新加入做过一次全量重协商，避免失败循环 */
    private val joinRenegotiated: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 下一个可用的显示顺序号。
     *
     * 不能每次都用 `configs.values.max(order)+1`：连续授权两个摄像头时 DataStore 的
     * configs 流可能还没回调，第二个会拿到和第一个相同的 order（随后按 deviceId 排序，
     * 与“按接入顺序显示”不符）。这里用单调递增的游标兜底。
     */
    @Volatile
    private var nextOrderHint: Int = -1

    @Synchronized
    private fun bumpOrderHint(order: Int) {
        if (order > nextOrderHint) nextOrderHint = order
    }

    /** 清除某物理设备（同 vid:pid）的“已自动尝试授权”标记，允许重插后再次自动弹窗 */
    private fun forgetAutoTried(info: UsbDeviceInfo) {
        val prefix = "%04X:%04X:".format(info.vendorId, info.productId)
        autoTried.removeIf { it.startsWith(prefix) }
    }

    /** 原子分配下一个显示顺序，连续授权多路时也不会重复 */
    @Synchronized
    private fun allocateOrder(): Int {
        val base = maxOf(nextOrderHint, configs.values.maxOfOrNull { it.order } ?: -1)
        nextOrderHint = base + 1
        return nextOrderHint
    }

    fun start() {
        if (started) return
        started = true
        Logger.i(TAG, "supervisor start")
        manager.registerUsbMonitor()

        // 设备配置流：更新后重排
        scope.launch {
            settings.deviceConfigs.collect { cfg ->
                configs = cfg
                cfg.values.maxOfOrNull { it.order }?.let { bumpOrderHint(it) }
                reconcile()
            }
        }
        // 设备列表流：枚举变化即对齐
        scope.launch {
            manager.devices.collect { reconcile() }
        }
        // 热插拔事件
        scope.launch {
            manager.usbEvents.collect { ev ->
                when (ev) {
                    is UsbMonitor.UsbEvent.Attached -> {
                        manager.refreshDevices()
                        reconcile()
                        // 新插入：自动尝试授权（同一设备本次只自动弹一次）
                        autoRequestOnce(ev.device)
                    }
                    is UsbMonitor.UsbEvent.Detached -> {
                        // 未授权设备拔出时没有 camera 对象，也要清掉自动授权去重标记，
                        // 否则同一台“授权失败后重插”的摄像头不会再自动弹窗。
                        ev.device?.let { forgetAutoTried(it) }
                        manager.refreshDevices()
                        reconcile()
                    }
                }
            }
        }
        // 兜底周期对齐（覆盖漏事件 / 取流意外中断）
        scope.launch {
            while (coroutineContext.isActive) {
                delay(RECONCILE_PERIOD_MS)
                reconcile()
            }
        }
        // 启动后对所有未授权设备串行自动授权（开机自启时逐个弹窗）。
        // 必须走 grantAll() 获取 permissionLock：直接调 grantAllUnlocked 会与
        // Activity 的 grantAll 并发，对同一设备重复 requestPermission 覆盖回调，
        // 造成授权协程永久挂起/只弹一个窗。
        scope.launch {
            delay(600)
            grantAll(manual = false)
        }
        manager.refreshDevices()
    }

    // ------------------------------------------------------------------
    // 授权
    // ------------------------------------------------------------------

    /** 新插入设备自动请求（同 key 本次只弹一次） */
    private fun autoRequestOnce(device: UsbDeviceInfo) {
        if (device.hasPermission) {
            scope.launch { afterPermissionGranted(device.deviceId); reconcile() }
            return
        }
        // CAMERA 未授予时不弹 USB 窗（也不标记已尝试），等 Activity 拿到 CAMERA 后由 grantAll 统一申请
        if (!hasCameraPermission()) return
        val key = device.stableKey
        if (!autoTried.add(key)) return
        scope.launch {
            permissionLock.withLock { doRequest(device) }
        }
    }

    /**
     * 一键授权：串行请求所有未授权设备（用户连续点允许即可）。
     * @param manual true=用户主动点击钥匙图标：必须绕过 autoTried 去重，始终重新弹窗，
     *   否则之前已被自动尝试过（autoTried 已含 key）的失败设备会被静默跳过、表现为"点了没反应"。
     */
    fun grantAll(manual: Boolean = false) {
        if (!hasCameraPermission()) {
            Logger.i(TAG, "grantAll skipped: CAMERA runtime permission not granted yet")
            if (manual) _messages.tryEmit("请先授予相机权限")
            return
        }
        scope.launch { permissionLock.withLock { grantAllUnlocked(manual) } }
    }

    private suspend fun grantAllUnlocked(manual: Boolean = false) {
        if (!hasCameraPermission()) return
        var pending = manager.devices.value.filter { !it.hasPermission }
        // 去重（按 key），避免重复弹窗
        val seen = HashSet<String>()
        pending = pending.filter { seen.add(it.stableKey) }
        if (pending.isEmpty()) {
            if (manual) _messages.tryEmit("所有摄像头均已授权")
            return
        }
        for (d in pending) {
            if (manual) {
                // 手动授权：无条件重新请求（即便 autoTried 已含该 key）
                doRequest(d)
            } else if (autoTried.add(d.stableKey)) {
                // 仅当 autoRequestOnce（Attached 事件）尚未处理过该设备时才请求，
                // 否则两者竞态会对同一设备连弹两次系统授权窗
                doRequest(d)
            }
        }
    }

    /** 手动授权单个设备（点击未授权画面 / chip 调用），失败会提示可重试 */
    fun grant(deviceId: Int) {
        if (!hasCameraPermission()) {
            _messages.tryEmit("请先授予相机权限")
            return
        }
        scope.launch {
            permissionLock.withLock {
                val device = manager.devices.value.firstOrNull { it.deviceId == deviceId }
                if (device != null) doRequest(device)
            }
        }
    }

    private suspend fun doRequest(device: UsbDeviceInfo) {
        if (device.hasPermission) {
            afterPermissionGranted(device.deviceId)
            reconcile()
            return
        }
        Logger.i(TAG, "request permission: ${device.displayText}")
        val granted = manager.requestPermission(device)
        if (granted) {
            afterPermissionGranted(device.deviceId)
            _messages.tryEmit("已授权：${device.productName ?: "摄像头"}")
        } else {
            // 刷新一次，避免状态滞后
            manager.refreshDevices()
            _messages.tryEmit("未授权：${device.productName ?: "摄像头"}，点击对应画面可重新授权")
        }
        reconcile()
    }

    /** 授权成功后：以序列号稳定标识建立持久化配置（决定显示顺序） */
    private suspend fun afterPermissionGranted(deviceId: Int) {
        manager.refreshDevices()
        val fresh = manager.devices.value.firstOrNull { it.deviceId == deviceId }
            ?: return
        if (!fresh.hasPermission) return
        val key = fresh.stableKey
        if (configs[key] == null) {
            // 以单调游标分配顺序，保证连续授权多个摄像头时不会拿到重复 order
            val nextOrder = allocateOrder()
            settings.saveDeviceConfig(DeviceConfig(key = key, order = nextOrder))
            Logger.i(TAG, "new device config key=$key order=$nextOrder")
        }
    }

    // ------------------------------------------------------------------
    // 对齐（reconcile）
    // ------------------------------------------------------------------

    private suspend fun reconcile() {
        if (!started) return
        reconcileLock.withLock {
            try {
                doReconcile()
            } catch (t: Throwable) {
                Logger.w(TAG, "reconcile error: ${t.message}")
            }
        }
    }

    private fun doReconcile() {
        val devices = manager.devices.value
        val now = System.currentTimeMillis()

        // 1) 关闭已物理拔出的设备所在槽位，并清理其重试/档位缓存。
        // 用 stableKey 判断而不是 deviceId：系统 deviceId 在拔插后可能被新设备复用，
        // 只比 deviceId 会把“旧设备的相机对象”误认为新设备，导致开流失败/画面错乱。
        val liveIds = devices.map { it.deviceId }.toSet()
        val liveKeys = devices.map { it.stableKey }.toSet()
        for (slot in 0 until UvcCameraManager.MAX_SLOTS) {
            val cam = manager.getCamera(slot)
            if (cam != null && cam.info.stableKey !in liveKeys) {
                Logger.i(TAG, "device gone, close slot=$slot dev=${cam.info.deviceId}")
                manager.closeCamera(slot)
                deviceProfileIdx.remove(cam.info.stableKey)
                profileUpgradeAt.remove(cam.info.stableKey)
                profileUpgradeTried.remove(cam.info.stableKey)
                failCount.remove(cam.info.deviceId)
                nextRetryAt.remove(cam.info.deviceId)
                givenUp.remove(cam.info.deviceId)
                // 允许同一物理设备重新插入后再次自动弹授权：
                // 整 Hub 重连时有序列号设备 key 不变，不清此标记会被 autoRequestOnce 去重，
                // 导致重连后不再自动申请、只能手动点占位。
                // 同时清掉 vid:pid 下的节点 key（授权前稳定标识是总线节点，
                // 只删 serial key 会让“拔插后未授权”设备无法再次自动弹窗）。
                forgetAutoTried(cam.info)
            }
        }
        // 清理已不在设备列表中的孤儿重试/放弃标记（设备可能在失败占位时尚未建立 camera）
        givenUp.removeAll { it !in liveIds }
        failCount.keys.removeAll { it !in liveIds }
        nextRetryAt.keys.removeAll { it !in liveIds }

        // 2) 目标排列：按持久化 order 升序（未配置/未授权排最后），再按 deviceId 稳定
        val ordered = devices.sortedWith(
            compareBy<UsbDeviceInfo> {
                // 已授权且有配置用其 order；未授权(总线节点 key)或无配置排末尾
                if (it.hasPermission && configs[it.stableKey] != null)
                    configs[it.stableKey]!!.order
                else Int.MAX_VALUE
            }.thenBy { it.deviceId }
        )
        val visible = ordered.take(UvcCameraManager.MAX_SLOTS)

        // 2.5) 有「新授权/新插入」的摄像头加入时，USB2.0 等时带宽需在所有路之间
        // 一次性重新分配：若只对新路降档，旧路已占满带宽，新路即使降到最低也可能
        // 一直 ENOSPC（表现为「运行中插入第 N 路黑屏，重启后又正常」）。
        // 因此新增成员时全部关闭、重置降档游标，按最终顺序统一重开协商，
        // 效果与冷启动一致。仅排序变化（用户手动换位）不改变成员集合，不触发。
        val authKeys = visible.filter { it.hasPermission }.map { it.stableKey }.toSet()
        val joined = authKeys - lastAuthKeys
        // 授权消失的设备（整 Hub 重连 / 授权被撤销 / 拔出）：清掉自动申请去重，
        // 使其重新插入时被当新设备，autoRequestOnce 能再次自动弹授权
        val left = lastAuthKeys - authKeys
        if (left.isNotEmpty()) {
            left.forEach { autoTried.remove(it) }
            left.forEach { pendingJoinKeys.remove(it); joinRenegotiated.remove(it) }
        }
        if (joined.isNotEmpty()) {
            // 先不打断已稳定画面：新摄像头能启动就直接启动；只有它确实起不来时，
            // 才做一次全量重协商（见下方 align 后的处理）。这样两个 1080p 摄像头
            // 正常接入时不会再被无谓地“全部关闭重开 + 报带宽不足”。
            pendingJoinKeys.addAll(joined)
            Logger.i(TAG, "camera(s) joined=$joined, try incremental open first")
        }
        lastAuthKeys = authKeys

        // 3) 逐槽位对齐
        for ((slot, device) in visible.withIndex()) {
            alignSlot(slot, device, now)
        }
        // 4) 多余槽位关闭
        for (slot in visible.size until UvcCameraManager.MAX_SLOTS) {
            if (manager.getCamera(slot) != null) manager.closeCamera(slot)
        }

        // 4.5) 新加入设备确实起不来时，做一次全量重协商（可能释放 USB 等时带宽）。
        // 仅尝试一次，且只在它已经失败过（failCount>0）时触发，避免插入就打断画面。
        val needRenegotiate = pendingJoinKeys.filter { key ->
            if (key in joinRenegotiated) return@filter false
            val idx = visible.indexOfFirst { it.stableKey == key }
            if (idx < 0) return@filter false
            val cam = manager.getCamera(idx)
            val dev = visible[idx]
            cam?.streaming != true && (failCount[dev.deviceId] ?: 0) > 0
        }
        if (needRenegotiate.isNotEmpty()) {
            Logger.i(TAG, "join failed, full renegotiate once for $needRenegotiate")
            joinRenegotiated.addAll(needRenegotiate)
            for (s in 0 until UvcCameraManager.MAX_SLOTS) manager.closeCamera(s)
            deviceProfileIdx.clear()
            profileUpgradeAt.clear()
            profileUpgradeTried.clear()
            failCount.clear()
            nextRetryAt.clear()
            for (slot in 0 until UvcCameraManager.MAX_SLOTS) {
                if (slot < visible.size) alignSlot(slot, visible[slot], now)
            }
        }
        // 已成功出流的 key 从 pending 中移除
        pendingJoinKeys.removeAll { key ->
            val idx = visible.indexOfFirst { it.stableKey == key }
            idx >= 0 && manager.getCamera(idx)?.streaming == true
        }

        // 5) 发布 UI 状态
        publish(visible)
    }

    private fun alignSlot(slot: Int, device: UsbDeviceInfo, now: Long) {
        if (!device.hasPermission) {
            // 占位 NO_PERMISSION（若该槽位先前开着别的设备，先关掉）
            val cur = manager.getCamera(slot)
            if (cur != null && cur.info.stableKey != device.stableKey) {
                manager.closeCamera(slot)
            }
            return
        }

        // 已达失败上限：停止自动重试（不再反复 open/close），保持失败占位等用户手动点击。
        // 这样非摄像头设备（理论上已在枚举层过滤）或异常设备不会触发无限取流，
        // 也不会拖垮已成功的其他路。
        if (device.deviceId in givenUp) {
            val gave = manager.getCamera(slot)
            if (gave != null && gave.info.stableKey != device.stableKey) manager.closeCamera(slot)
            if (gave != null && gave.info.stableKey == device.stableKey && gave.streaming) {
                // 已在流（不应发生），清除放弃标记
                givenUp.remove(device.deviceId)
            } else if (gave != null && gave.info.stableKey == device.stableKey) {
                manager.closeCamera(slot)
            }
            return
        }

        // 退避窗口内：不重复打开/协商（UI 显示重试中）
        val waitUntil = nextRetryAt[device.deviceId]
        if (waitUntil != null && now < waitUntil) return

        // 设备是否已在正确槽位
        val here = manager.getCamera(slot)
        if (here == null || here.info.stableKey != device.stableKey) {
            val existingSlot = manager.findDeviceSlot(device)
            if (existingSlot >= 0) {
                manager.assignDeviceToSlot(slot, device)
            } else {
                val r = manager.openCamera(slot, device)
                if (r is UvcCameraManager.OpenResult.Failure) {
                    Logger.w(TAG, "open failed slot=$slot ${r.reason}")
                    markRetry(device.deviceId, now)
                    return
                }
            }
        }

        val camera = manager.getCamera(slot) ?: return
        if (camera.streaming) {
            // 启动时被异常压到 640x480/360 的摄像头：12 秒后重试一次首选档，
            // 给 USB 电源/固件恢复时间；仅尝试一次，避免反复中断画面。
            val key = device.stableKey
            val upAt = profileUpgradeAt[key]
            if (upAt != null && now >= upAt) {
                profileUpgradeAt.remove(key)
                deviceProfileIdx.remove(key)
                Logger.i(TAG, "retry preferred profile for $key after low-res fallback")
                manager.closeCamera(slot)
                return
            }
            nextRetryAt.remove(device.deviceId)
            failCount.remove(device.deviceId)
            return
        }
        attemptProfiles(slot, device, now)
    }

    /**
     * 从期望档位开始尝试取流；失败（多为多路 iso 共享 USB2.0 带宽不足 ENOSPC）
     * 则自动逐级降档，直到该路能显示——保证"所有摄像头都能出画面"。
     * 每轮最多尝试 [ATTEMPT_PER_ROUND] 档，进度记在 [deviceProfileIdx]，
     * 下一轮继续，避免一次阻塞太久；成功后固定该档位，后续不再反复失败。
     */
    private fun attemptProfiles(slot: Int, device: UsbDeviceInfo, now: Long) {
        val initial = manager.getCamera(slot)
        val caps = initial?.capabilities
        if (caps.isNullOrEmpty()) {
            markRetry(device.deviceId, now)
            return
        }
        val profileKey = device.stableKey
        val startIdx = cfgStartIndex(caps, configs[profileKey])
        var idx = (deviceProfileIdx[profileKey] ?: startIdx).coerceAtLeast(startIdx)
        val end = minOf(caps.size, idx + ATTEMPT_PER_ROUND)

        var ok = false
        var used: StreamProfile? = null
        while (idx < end) {
            val candidate = caps[idx]
            var cam = manager.getCamera(slot)
            if (cam == null) {
                val r = manager.openCamera(slot, device)
                if (r is UvcCameraManager.OpenResult.Failure) {
                    deviceProfileIdx.remove(profileKey)
                    markRetry(device.deviceId, now)
                    return
                }
                cam = manager.getCamera(slot)
            }
            if (cam != null && manager.startStream(slot, candidate)) {
                ok = true
                used = candidate
                deviceProfileIdx[profileKey] = idx
                break
            }
            // 该档协商失败：关闭，下一档重新打开协商
            manager.stopStream(slot)
            manager.closeCamera(slot)
            idx++
        }

        if (ok) {
            nextRetryAt.remove(device.deviceId)
            failCount.remove(device.deviceId)
            givenUp.remove(device.deviceId)
            Logger.i(TAG, "streaming slot=$slot profile=$used (idx=${deviceProfileIdx[profileKey]})")
            if ((deviceProfileIdx[profileKey] ?: 0) > startIdx) {
                // 静默降档，只在日志记录：UI 上该路的分辨率/帧率标签已经是实际档位。
                // 不弹“带宽不足”——USB2 Hub 下 1080p30+1080p30 的等时带宽确实可能不够，
                // 但用户只看到两条 1K 摄像头时这句提示会严重误导，且每次切档都弹很烦。
                Logger.i(
                    TAG,
                    "${device.defaultName} auto profile ${used?.display} " +
                        "(requested idx=$startIdx, used idx=${deviceProfileIdx[profileKey]})"
                )
                // 启动即跌到 640 级别时，12 秒后给硬件一次恢复机会（只尝试一次）
                if (startIdx == 0 && (used?.height ?: 0) < 720 && profileUpgradeTried.add(profileKey)) {
                    profileUpgradeAt[profileKey] = now + 12_000
                }
            }
        } else {
            if (idx >= caps.size) {
                // 全部档位都失败：下一轮从期望档位重新尝试（环境可能变化）
                deviceProfileIdx.remove(profileKey)
            } else {
                deviceProfileIdx[profileKey] = idx
            }
            markRetry(device.deviceId, now)
        }
    }

    private fun cfgStartIndex(caps: List<StreamProfile>, cfg: DeviceConfig?): Int {
        if (cfg != null && cfg.width > 0 && cfg.height > 0) {
            val i = caps.indexOfFirst {
                it.width == cfg.width && it.height == cfg.height &&
                    (cfg.fps <= 0 || it.fps == cfg.fps)
            }
            if (i >= 0) return i
        }
        return 0
    }

    /** 每设备（按稳定标识）当前已降到的档位 index；槽位交换/重插后仍能复用，避免重复协商 */
    private val deviceProfileIdx = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 启动时异常降级到 <720p 的设备：12 秒后重试一次首选高分辨率（硬件/USB 电源恢复） */
    private val profileUpgradeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val profileUpgradeTried = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val failCount = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private fun markRetry(deviceId: Int, now: Long) {
        val n = (failCount[deviceId] ?: 0) + 1
        failCount[deviceId] = n
        if (n >= MAX_FAIL_ROUNDS) {
            // 达到失败上限：放弃自动重试，保留占位由用户手动点击（杜绝无限取流循环）
            givenUp.add(deviceId)
            nextRetryAt.remove(deviceId)
            Logger.w(TAG, "deviceId=$deviceId give up after $n failed rounds; tap frame to retry")
            return
        }
        // 指数退避：1s、2s、4s、8s、8s…
        val delayMs = (1000L shl (n - 1)).coerceAtMost(MAX_RETRY_BACKOFF_MS)
        nextRetryAt[deviceId] = now + delayMs
    }

    /** 手动重试某槽位：清除失败/退避/放弃状态并立即重新对齐 */
    fun retrySlot(slot: Int) {
        val rt = _slots.value.getOrNull(slot) ?: return
        val id = rt.device?.deviceId ?: return
        scope.launch {
            reconcileLock.withLock {
                givenUp.remove(id)
                failCount.remove(id)
                nextRetryAt.remove(id)
                rt.key?.let { deviceProfileIdx.remove(it) }
                manager.closeCamera(slot)
                doReconcile()
            }
        }
    }

    private fun publish(visible: List<UsbDeviceInfo>) {
        val list = List(UvcCameraManager.MAX_SLOTS) { slot ->
            if (slot >= visible.size) {
                SlotRuntime(slot)
            } else {
                val device = visible[slot]
                val camera = manager.getCamera(slot)
                val state = when {
                    !device.hasPermission -> SlotState.NO_PERMISSION
                    camera?.streaming == true -> SlotState.STREAMING
                    camera != null -> SlotState.OPENING
                    else -> SlotState.ERROR
                }
                SlotRuntime(
                    slot = slot,
                    key = device.stableKey,
                    device = device,
                    state = state,
                    capabilities = camera?.capabilities ?: emptyList(),
                    activeProfile = camera?.currentProfile,
                    config = configs[device.stableKey],
                    error = when {
                        state != SlotState.ERROR -> null
                        device.deviceId in givenUp -> "取流失败，点击重试"
                        else -> "取流失败，重试中…"
                    }
                )
            }
        }
        _slots.update { list }
    }

    // ------------------------------------------------------------------
    // 用户操作（UI -> ViewModel -> 本类）
    // ------------------------------------------------------------------

    /** 把某设备移动到指定槽位（重排顺序并持久化），随后对齐 */
    fun assignDeviceToSlot(device: UsbDeviceInfo, targetSlot: Int) {
        scope.launch {
            var effective = device
            // 注意：不能在持有 reconcileLock 时调用 doRequest()——doRequest 内部会
            // reconcile() 再次申请同一把（非可重入）Mutex，导致授权成功后永久挂起。
            // 因此先完成授权，再进入重排临界区。
            if (!device.hasPermission) {
                permissionLock.withLock { doRequest(device) }
                manager.refreshDevices()
                val fresh = manager.devices.value.firstOrNull { it.deviceId == device.deviceId }
                if (fresh == null || !fresh.hasPermission) {
                    _messages.tryEmit("未授权：${device.productName ?: "摄像头"}")
                    return@launch
                }
                effective = fresh
            }
            val movingKey = effective.stableKey
            reconcileLock.withLock {
                // 当前可见设备按现有顺序的 key 列表
                val ordered = manager.devices.value.sortedWith(
                    compareBy<UsbDeviceInfo> {
                        if (it.hasPermission && configs[it.stableKey] != null)
                            configs[it.stableKey]!!.order
                        else Int.MAX_VALUE
                    }.thenBy { it.deviceId }
                )
                val keys = ordered.filter { it.hasPermission }.map { it.stableKey }.toMutableList()
                val cur = keys.indexOf(movingKey)
                if (cur >= 0) keys.removeAt(cur)
                val insertAt = targetSlot.coerceIn(0, keys.size)
                keys.add(insertAt, movingKey)
                // 重新落盘 order
                val newConfigs = LinkedHashMap(configs)
                keys.forEachIndexed { idx, key ->
                    val old = newConfigs[key]
                    if (old != null) newConfigs[key] = old.copy(order = idx)
                    else newConfigs[key] = DeviceConfig(key = key, order = idx)
                }
                keys.forEachIndexed { idx, key -> bumpOrderHint(idx) }
                settings.saveAllDeviceConfigs(newConfigs)
            }
            reconcile()
        }
    }

    /** 切换某路档位（仅能力范围内），持久化并重开 */
    fun setProfile(slot: Int, profile: StreamProfile) {
        val runtime = _slots.value[slot]
        val key = runtime.key ?: return
        val deviceId = runtime.device?.deviceId
        scope.launch {
            reconcileLock.withLock {
                val cfg = configs[key] ?: DeviceConfig(key = key)
                settings.saveDeviceConfig(
                    cfg.copy(width = profile.width, height = profile.height, fps = profile.fps)
                )
                // 手动选档：清除自动降档缓存与退避，按新档位重新协商
                deviceProfileIdx.remove(key)
                profileUpgradeAt.remove(key)
                profileUpgradeTried.remove(key)
                if (deviceId != null) {
                    failCount.remove(deviceId)
                    nextRetryAt.remove(deviceId)
                }
                manager.switchProfile(slot, profile)
            }
            reconcile()
        }
    }

    /** 水平镜像（持久化） */
    fun setMirror(slot: Int, mirror: Boolean) {
        val key = _slots.value[slot].key ?: return
        scope.launch {
            val cfg = configs[key] ?: DeviceConfig(key = key)
            settings.saveDeviceConfig(cfg.copy(mirror = mirror))
        }
    }

    /** 旋转（0/90/180/270 循环，持久化） */
    fun rotate(slot: Int) {
        val key = _slots.value[slot].key ?: return
        val current = configs[key]?.rotation ?: 0
        setRotation(slot, current + 90)
    }

    /**
     * 直接设置旋转角度（0/90/180/270，持久化）。
     *
     * 设置页的分段选择器必须调用本方法：连续调用 [rotate] 时每个协程都从
     * 尚未更新的 DataStore 快照读当前角度，快速点击 0°→270° 会被合并成只转 90°。
     */
    fun setRotation(slot: Int, degrees: Int) {
        val key = _slots.value[slot].key ?: return
        val normalized = (((degrees % 360) + 360) % 360) / 90 * 90
        scope.launch {
            val cfg = configs[key] ?: DeviceConfig(key = key)
            if (cfg.rotation == normalized) return@launch
            settings.saveDeviceConfig(cfg.copy(rotation = normalized))
        }
    }

    /** 重命名某路（持久化） */
    fun rename(slot: Int, name: String) {
        val key = _slots.value[slot].key ?: return
        scope.launch {
            val cfg = configs[key] ?: DeviceConfig(key = key)
            settings.saveDeviceConfig(cfg.copy(name = name.trim()))
        }
    }

    /** 开关某路盲区预警（持久化，按稳定标识绑定） */
    fun setWarn(slot: Int, warn: Boolean) {
        val key = _slots.value[slot].key ?: return
        scope.launch {
            val cfg = configs[key] ?: DeviceConfig(key = key)
            settings.saveDeviceConfig(cfg.copy(warn = warn))
        }
    }

    /** 设置某路实时降噪档位（0关/1弱/2中/3强，持久化，按稳定标识绑定） */
    fun setDenoise(slot: Int, level: Int) {
        val key = _slots.value[slot].key ?: return
        scope.launch {
            val cfg = configs[key] ?: DeviceConfig(key = key)
            settings.saveDeviceConfig(cfg.copy(denoise = level.coerceIn(0, 3)))
        }
    }

    /** 手动触发一次刷新/对齐 */
    fun refresh() {
        manager.refreshDevices()
        scope.launch { reconcile() }
    }
}
