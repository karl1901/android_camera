package com.wheeltech.blindcam.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宫格规则单元测试：预览 / 总录像 / 回看三处必须完全一致。
 * 重点回归：3 路时第 3 路在顶部，前两路在下方左右（历史版本总录像顺序反了）。
 */
class GridLayoutSpecTest {

    @Test
    fun singleFillsWholeGrid() {
        val c = GridLayoutSpec.cell(1, 0)
        assertEquals(0, c.row)
        assertEquals(0, c.col)
        assertEquals(2, c.rowSpan)
        assertEquals(2, c.colSpan)

        val r = GridLayoutSpec.rect(1, 0, 1280, 720)
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertEquals(1280, r.right)
        assertEquals(720, r.bottom)
    }

    @Test
    fun twoCamerasSideBySide() {
        assertEquals(0, GridLayoutSpec.cell(2, 0).col)
        assertEquals(1, GridLayoutSpec.cell(2, 1).col)

        val left = GridLayoutSpec.rect(2, 0, 1280, 720)
        val right = GridLayoutSpec.rect(2, 1, 1280, 720)
        assertEquals(0, left.left)
        assertEquals(640, left.right)
        assertEquals(640, right.left)
        assertEquals(1280, right.right)
    }

    @Test
    fun thirdCameraGoesOnTop() {
        // 预览 GridLayout：第 1/2 路在下一行左右，第 3 路横跨顶部
        val first = GridLayoutSpec.cell(3, 0)
        val second = GridLayoutSpec.cell(3, 1)
        val third = GridLayoutSpec.cell(3, 2)
        assertEquals(1, first.row)
        assertEquals(0, first.col)
        assertEquals(1, second.row)
        assertEquals(1, second.col)
        assertEquals(0, third.row)
        assertEquals(0, third.col)
        assertEquals(2, third.colSpan)

        // 总录像/回看的像素矩形必须同序：0=左下，1=右下，2=顶部横铺
        val r0 = GridLayoutSpec.rect(3, 0, 1280, 720)
        val r1 = GridLayoutSpec.rect(3, 1, 1280, 720)
        val r2 = GridLayoutSpec.rect(3, 2, 1280, 720)
        assertEquals(0, r0.left)
        assertEquals(360, r0.top)
        assertEquals(640, r0.right)
        assertEquals(640, r1.left)
        assertEquals(360, r1.top)
        assertEquals(0, r2.top)
        assertEquals(360, r2.bottom)
        assertEquals(1280, r2.right)
    }

    @Test
    fun fourCamerasInReadingOrder() {
        val r0 = GridLayoutSpec.rect(4, 0, 1280, 720)
        val r1 = GridLayoutSpec.rect(4, 1, 1280, 720)
        val r2 = GridLayoutSpec.rect(4, 2, 1280, 720)
        val r3 = GridLayoutSpec.rect(4, 3, 1280, 720)
        // 上左、上右、下左、下右
        assertEquals(0, r0.left); assertEquals(0, r0.top)
        assertEquals(640, r1.left); assertEquals(0, r1.top)
        assertEquals(0, r2.left); assertEquals(360, r2.top)
        assertEquals(640, r3.left); assertEquals(360, r3.top)
    }

    @Test
    fun invalidIndicesAreClamped() {
        val c = GridLayoutSpec.cell(0, 99) // count 归一化为 1，index 夹到 0
        assertEquals(2, c.rowSpan)
        assertEquals(2, c.colSpan)
        val r = GridLayoutSpec.rect(9, 99, 1280, 720)
        assertEquals(640, r.left)
        assertEquals(360, r.top)
    }
}
