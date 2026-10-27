package com.wheeltech.blindcam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateConverterTest {

    @Test
    fun wgs84ToGcj02_appliesOffsetInsideChina() {
        // 北京天安门附近：WGS-84 转 GCJ-02 后纬度/经度都应有数百米级偏移
        val wgsLat = 39.9087
        val wgsLon = 116.3975
        val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
        assertTrue("lat offset should be positive in Beijing", gcj.lat > wgsLat)
        assertTrue("lon offset should be positive in Beijing", gcj.lon > wgsLon)
        assertTrue("offset should be within 1km", CoordinateConverter.distanceMeters(
            wgsLat, wgsLon, gcj.lat, gcj.lon
        ) in 100.0..1000.0)
    }

    @Test
    fun wgs84ToGcj02_keepsOverseasCoordinate() {
        val out = CoordinateConverter.wgs84ToGcj02(35.6762, 139.6503) // Tokyo
        assertEquals(35.6762, out.lat, 1e-9)
        assertEquals(139.6503, out.lon, 1e-9)
    }

    @Test
    fun distanceMeters_oneDegreeLatitudeIsAbout111km() {
        val d = CoordinateConverter.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertTrue("expected ~111km, got $d", d in 110_000.0..112_500.0)
    }
}
