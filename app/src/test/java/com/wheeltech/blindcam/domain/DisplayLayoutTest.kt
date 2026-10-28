package com.wheeltech.blindcam.domain

import com.wheeltech.blindcam.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayLayoutTest {

    private val occupied = listOf(0, 1, 2, 3)

    @Test
    fun quadShowsAllConnectedCameras() {
        assertEquals(occupied, DisplayLayout.visibleSlots(AppSettings.LayoutMode.AUTO, occupied, 2))
    }

    @Test
    fun tripleAndDualTakeFirstN() {
        assertEquals(listOf(0, 1, 2), DisplayLayout.visibleSlots(AppSettings.LayoutMode.TRIPLE, occupied, 3))
        assertEquals(listOf(0, 1), DisplayLayout.visibleSlots(AppSettings.LayoutMode.DUAL, occupied, 3))
    }

    @Test
    fun singleUsesFocusedSlotAndFallsBackWhenMissing() {
        assertEquals(listOf(2), DisplayLayout.visibleSlots(AppSettings.LayoutMode.SINGLE, occupied, 2))
        assertEquals(listOf(0), DisplayLayout.visibleSlots(AppSettings.LayoutMode.SINGLE, occupied, 9))
        assertEquals(listOf(0, 1), DisplayLayout.visibleSlots(AppSettings.LayoutMode.DUAL, listOf(0, 1), 1))
    }

    @Test
    fun availableModesFollowConnectedCount() {
        assertEquals(
            listOf(AppSettings.LayoutMode.SINGLE),
            DisplayLayout.availableModes(1)
        )
        assertEquals(
            listOf(AppSettings.LayoutMode.DUAL, AppSettings.LayoutMode.SINGLE),
            DisplayLayout.availableModes(2)
        )
        assertEquals(
            listOf(
                AppSettings.LayoutMode.TRIPLE,
                AppSettings.LayoutMode.DUAL,
                AppSettings.LayoutMode.SINGLE
            ),
            DisplayLayout.availableModes(3)
        )
        assertEquals(4, DisplayLayout.availableModes(4).size)
        assertEquals(emptyList<AppSettings.LayoutMode>(), DisplayLayout.availableModes(0))
    }

    @Test
    fun effectiveModeFallsBackWhenCamerasRemoved() {
        assertEquals(
            AppSettings.LayoutMode.DUAL,
            DisplayLayout.effectiveMode(AppSettings.LayoutMode.AUTO, 2)
        )
        assertEquals(
            AppSettings.LayoutMode.TRIPLE,
            DisplayLayout.effectiveMode(AppSettings.LayoutMode.AUTO, 3)
        )
        assertEquals(
            AppSettings.LayoutMode.SINGLE,
            DisplayLayout.effectiveMode(AppSettings.LayoutMode.TRIPLE, 1)
        )
        assertEquals(
            AppSettings.LayoutMode.AUTO,
            DisplayLayout.effectiveMode(AppSettings.LayoutMode.AUTO, 4)
        )
    }

    @Test
    fun modeForConnectedFollowsHotplugCount() {
        assertEquals(AppSettings.LayoutMode.SINGLE, DisplayLayout.modeForConnected(1))
        assertEquals(AppSettings.LayoutMode.DUAL, DisplayLayout.modeForConnected(2))
        assertEquals(AppSettings.LayoutMode.TRIPLE, DisplayLayout.modeForConnected(3))
        assertEquals(AppSettings.LayoutMode.AUTO, DisplayLayout.modeForConnected(4))
        assertEquals(AppSettings.LayoutMode.AUTO, DisplayLayout.modeForConnected(5))
        assertEquals(null, DisplayLayout.modeForConnected(0))
    }

    @Test
    fun duplicateAndTooManySlotsAreClamped() {
        assertEquals(listOf(0, 1), DisplayLayout.visibleSlots(AppSettings.LayoutMode.AUTO, listOf(0, 1, 1), 0))
        assertEquals(listOf(0, 1, 2, 3), DisplayLayout.visibleSlots(AppSettings.LayoutMode.AUTO, listOf(0, 1, 2, 3, 4), 0))
    }
}
