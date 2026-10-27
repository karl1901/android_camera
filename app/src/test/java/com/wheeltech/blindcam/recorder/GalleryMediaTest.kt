package com.wheeltech.blindcam.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相册文件白名单测试（需求 12.5 / 历史 Bug #8）：
 * 即使用户把保存目录设为系统相册目录，也只识别本 APP 前缀的文件。
 */
class GalleryMediaTest {

    @Test
    fun systemPhotosAndVideosAreIgnored() {
        assertNull(GalleryMedia.parse("IMG_20261026_120000.jpg", false, 0L))
        assertNull(GalleryMedia.parse("VID_20261026_120000.mp4", true, 0L))
        assertNull(GalleryMedia.parse("Screenshot_20261026-120000.png", false, 0L))
        assertNull(GalleryMedia.parse("20261026_120000.jpg", false, 0L))
        assertNull(GalleryMedia.parse("wechat_video.mp4", true, 0L))
    }

    @Test
    fun ownPhotoParsesDeviceAndTimestamp() {
        val m = GalleryMedia.parse(
            "P_Web_Camera_20261026_201530_123.jpg", false, 0L
        )
        assertNotNull(m)
        assertEquals(GalleryMedia.Kind.PHOTO, m!!.kind)
        assertEquals("Web Camera", m.device)
        assertEquals("20261026", m.dateKey)
        assertTrue(!m.isVideo)
    }

    @Test
    fun ownSingleRecordingParses() {
        val m = GalleryMedia.parse(
            "V_Left_20261026_201530_1920x1080.mp4", true, 0L
        )
        assertNotNull(m)
        assertEquals(GalleryMedia.Kind.SINGLE, m!!.kind)
        assertEquals("Left", m.device)
        assertTrue(m.isVideo)
    }

    @Test
    fun ownTotalRecordingHasStableName() {
        val m = GalleryMedia.parse(
            "ALL_20261026_201530_1280x720.mp4", true, 0L
        )
        assertNotNull(m)
        assertEquals(GalleryMedia.Kind.ALL, m!!.kind)
        assertEquals("总录像", m.device)
        assertEquals("20261026", m.dateKey)
    }

    @Test
    fun lowercaseOwnPrefixIsAcceptedButNeverUnknownDevice() {
        val m = GalleryMedia.parse("p_cam_20261026_201530_123.jpg", false, 0L)
        assertNotNull(m)
        // 旧文件/异常命名也不能再出现“未命名设备”
        assertTrue(m!!.device.isNotBlank())
        assertTrue(m.device != "未命名设备")
    }
}
