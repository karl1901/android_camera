package com.wheeltech.blindcam.domain

/**
 * 多路画面的统一宫格规则（唯一事实来源）。
 *
 * 预览 [com.wheeltech.blindcam.ui.main.MainActivity]、总录像
 * [com.wheeltech.blindcam.recorder.CompositeRecorder]、回看裁剪
 * [com.wheeltech.blindcam.ui.playback.PlaybackActivity] 必须使用同一套规则，
 * 否则会出现“预览三路在上、回放三路在下”的错位。
 *
 * 约定（与需求 3.1–3.6 一致）：
 * - 1 路：铺满
 * - 2 路：左 | 右
 * - 3 路：第 1 路左下、第 2 路右下、第 3 路（最后接入）顶部横铺
 * - 4 路：2×2，按接入顺序从上到下、从左到右
 * - 不足 3 路时不会留空位：2 路仍是左右各半屏
 *
 * 本类不依赖 Android，方便 JVM 单元测试。
 */
object GridLayoutSpec {

    /** 2×2 的逻辑网格（3 路时第 3 格横跨两列） */
    const val COLUMNS = 2
    const val ROWS = 2

    /** GridLayout 单元格规格 */
    data class Cell(
        val row: Int,
        val col: Int,
        val rowSpan: Int,
        val colSpan: Int
    )

    /** 像素矩形（左闭右开） */
    data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** 归一化到 1..4，非法值按 1 路处理，避免出现 0 除/空布局 */
    fun normalizeCount(count: Int): Int = count.coerceIn(1, 4)

    /**
     * 第 [index] 路在 [count] 路布局中的 GridLayout 单元格。
     * index 按接入顺序（0 为最早接入）。
     */
    fun cell(count: Int, index: Int): Cell {
        val n = normalizeCount(count)
        val i = index.coerceIn(0, n - 1)
        return when (n) {
            1 -> Cell(0, 0, ROWS, COLUMNS)
            2 -> Cell(0, i, ROWS, 1)
            3 -> when (i) {
                0 -> Cell(1, 0, 1, 1)
                1 -> Cell(1, 1, 1, 1)
                else -> Cell(0, 0, 1, COLUMNS)
            }
            else -> Cell(i / 2, i % 2, 1, 1)
        }
    }

    /**
     * 第 [index] 路在 [count] 路布局中的像素矩形（总录像画布 / 回看裁剪共用）。
     *
     * 三路顺序必须与 [cell] 一致：0=左下，1=右下，2=顶部横铺。
     */
    fun rect(count: Int, index: Int, width: Int, height: Int): Rect {
        val n = normalizeCount(count)
        val i = index.coerceIn(0, n - 1)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        return when (n) {
            1 -> Rect(0, 0, w, h)
            2 -> if (i == 0) Rect(0, 0, w / 2, h) else Rect(w / 2, 0, w, h)
            3 -> when (i) {
                0 -> Rect(0, h / 2, w / 2, h)
                1 -> Rect(w / 2, h / 2, w, h)
                else -> Rect(0, 0, w, h / 2)
            }
            else -> {
                val col = i % 2
                val row = i / 2
                Rect(
                    col * w / 2, row * h / 2,
                    if (col == 0) w / 2 else w,
                    if (row == 0) h / 2 else h
                )
            }
        }
    }
}
