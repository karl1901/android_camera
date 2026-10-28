package com.wheeltech.blindcam.domain

import com.wheeltech.blindcam.data.AppSettings

/** 显示模式 → 实际参与宫格的槽位（纯逻辑，方便 JVM 单测与持久化语义统一）。 */
object DisplayLayout {
    /** 当前接入路数下可选的显示模式（由多到少；1 路只有单路，2 路双屏/单路…） */
    fun availableModes(connected: Int): List<AppSettings.LayoutMode> = when {
        connected >= 4 -> listOf(
            AppSettings.LayoutMode.AUTO,
            AppSettings.LayoutMode.TRIPLE,
            AppSettings.LayoutMode.DUAL,
            AppSettings.LayoutMode.SINGLE
        )
        connected == 3 -> listOf(
            AppSettings.LayoutMode.TRIPLE,
            AppSettings.LayoutMode.DUAL,
            AppSettings.LayoutMode.SINGLE
        )
        connected == 2 -> listOf(
            AppSettings.LayoutMode.DUAL,
            AppSettings.LayoutMode.SINGLE
        )
        connected == 1 -> listOf(AppSettings.LayoutMode.SINGLE)
        else -> emptyList()
    }

    /**
     * 摄像头热插拔/启动时，按“接入几路就显示几屏”自动落到的模式。
     * 4 路用 AUTO（四屏），3 路三屏，2 路双屏，1 路单路；0 路返回 null（保持原设置）。
     */
    fun modeForConnected(connected: Int): AppSettings.LayoutMode? = when {
        connected >= 4 -> AppSettings.LayoutMode.AUTO
        connected == 3 -> AppSettings.LayoutMode.TRIPLE
        connected == 2 -> AppSettings.LayoutMode.DUAL
        connected == 1 -> AppSettings.LayoutMode.SINGLE
        else -> null
    }

    /**
     * 摄像头减少后，原模式可能不再可用（例如 4 路时选了四屏，拔到 2 路）。
     * 不覆盖用户持久化选择，只在显示时落到当前路数最接近的模式。
     */
    fun effectiveMode(mode: AppSettings.LayoutMode, connected: Int): AppSettings.LayoutMode = when {
        connected <= 0 -> mode
        connected == 1 -> AppSettings.LayoutMode.SINGLE
        connected == 2 -> when (mode) {
            AppSettings.LayoutMode.AUTO,
            AppSettings.LayoutMode.TRIPLE -> AppSettings.LayoutMode.DUAL
            else -> mode
        }
        connected == 3 -> when (mode) {
            AppSettings.LayoutMode.AUTO -> AppSettings.LayoutMode.TRIPLE
            else -> mode
        }
        else -> mode
    }

    /**
     * @param mode 四屏/三屏/双屏/单路
     * @param occupied 已接入的槽位（按接入顺序）
     * @param focusSlot 单路模式选中的槽位
     */
    fun visibleSlots(
        mode: AppSettings.LayoutMode,
        occupied: List<Int>,
        focusSlot: Int,
        maxSlots: Int = 4
    ): List<Int> {
        val list = occupied.distinct().take(maxSlots.coerceIn(1, 4))
        return when (effectiveMode(mode, list.size)) {
            AppSettings.LayoutMode.AUTO -> list
            AppSettings.LayoutMode.TRIPLE -> list.take(3)
            AppSettings.LayoutMode.DUAL -> list.take(2)
            AppSettings.LayoutMode.SINGLE ->
                if (focusSlot in list) listOf(focusSlot) else list.take(1)
        }
    }
}
