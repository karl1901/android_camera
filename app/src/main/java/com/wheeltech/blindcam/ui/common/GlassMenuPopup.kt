package com.wheeltech.blindcam.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.domain.DevicePerformance

/**
 * 主题化玻璃下拉菜单（显示模式 / 设备 / 日期共用）。
 *
 * - 背景/文字/选中色全部取当前 Theme 属性，浅色/深色/科技蓝都会跟着变；
 * - 行高、字号按屏幕短边自适应，车机/手机/平板不会出现过大的菜单；
 * - 支持分组标题与选中项，首击即触发，不依赖系统 Spinner。
 */
class GlassMenuPopup(
    private val context: Context,
    private val entries: List<Entry>,
    private val onSelect: (Int) -> Unit
) {

    sealed class Entry {
        data class Header(val text: String) : Entry()
        data class Item(
            val text: String,
            val selected: Boolean = false,
            val enabled: Boolean = true
        ) : Entry()
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun themeDrawable(attr: Int, fallback: Int): Drawable {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId)?.let { return it }
        }
        return androidx.core.content.ContextCompat.getDrawable(context, fallback)!!
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun ui(v: Float): Float = v * DevicePerformance.uiScale()

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f)) * 255).toInt() shl 24)

    fun show(anchor: View, alignEnd: Boolean = true) {
        val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val dim = themeColor(R.attr.onSurfaceDim, 0xFFB9B3CC.toInt())
        val accent = themeColor(R.attr.accentColor, 0xFFFF8FC4.toInt())
        val selectedBg = themeDrawable(R.attr.pillBg, R.drawable.bg_pill)

        val list = ListView(context).apply {
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            setPadding(0, dp(5), 0, dp(5))
            background = null
        }

        val adapter = object : ArrayAdapter<Entry>(context, 0, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return when (val e = getItem(position)) {
                    is Entry.Header -> TextView(context).apply {
                        text = e.text
                        setTextColor(dim)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, ui(11f))
                        setPadding(dp(16), dp(10), dp(16), dp(4))
                        layoutParams = AbsListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        isEnabled = false
                    }
                    is Entry.Item -> {
                        val tv = (convertView as? TextView) ?: TextView(context).apply {
                            layoutParams = AbsListView.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        }
                        tv.text = if (e.selected) "✓  ${e.text}" else "     ${e.text}"
                        tv.setTextColor(
                            when {
                                !e.enabled -> dim
                                e.selected -> accent
                                else -> onSurface
                            }
                        )
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, ui(14f))
                        tv.gravity = Gravity.CENTER_VERTICAL
                        tv.setPadding(dp(10), dp(10), dp(16), dp(10))
                        tv.background = if (e.selected) selectedBg.constantState?.newDrawable() else null
                        tv.isEnabled = e.enabled
                        tv.alpha = if (e.enabled) 1f else 0.55f
                        tv
                    }
                    else -> TextView(context)
                }
            }
        }
        list.adapter = adapter

        val maxWidth = (context.resources.configuration.screenWidthDp * context.resources.displayMetrics.density).toInt() - dp(24)
        val width = minOf(maxOf(anchor.width, dp(220)), maxWidth).coerceAtLeast(dp(160))
        val popup = PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
        }
        // 玻璃卡片背景放在外层容器上，避免 ListView 滚动时把圆角切掉
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 下拉菜单必须足够不透明：玻璃 cardBg 叠在视频上会看不清文字
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(withAlpha(themeColor(com.google.android.material.R.attr.colorSurface, 0xFF1A1330.toInt()), 0.98f))
                setStroke(dp(1), themeColor(R.attr.dividerColor, 0x33FFFFFF))
            }
            setPadding(0, dp(4), 0, dp(4))
            addView(
                list,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        popup.contentView = box
        // 列表最多占屏幕 82%，四屏/三屏/双屏/单路四个选项尽量一屏放下
        val maxH = (context.resources.configuration.screenHeightDp * context.resources.displayMetrics.density * 0.82f).toInt()
        list.setOnItemClickListener { _, _, position, _ ->
            val e = entries.getOrNull(position) as? Entry.Item ?: return@setOnItemClickListener
            if (!e.enabled) return@setOnItemClickListener
            popup.dismiss()
            onSelect(position)
        }
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.showAsDropDown(
            anchor, 0, dp(6),
            if (alignEnd) Gravity.END else Gravity.START
        )
        // wrap_content 的 PopupWindow 在部分 ROM 上会撑满屏幕：测量后限制高度
        list.post {
            val h = list.measuredHeight.coerceAtMost(maxH)
            if (h > 0 && popup.height != h) popup.update(width, h)
        }
    }
}
