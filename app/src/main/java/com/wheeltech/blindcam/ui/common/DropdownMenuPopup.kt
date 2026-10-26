package com.wheeltech.blindcam.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 自定义全宽下拉菜单（玻璃拟态）。
 *
 * 修复"设备筛选 Spinner 点不动"：
 * - 不依赖系统 Spinner 的触摸/首击行为；每行全宽、48dp 高、首击即触发。
 * - 用 [PopupWindow] 锚定按钮展示，点外部自动消失。
 *
 * @param options 选项文本
 * @param selectedIndex 当前选中项（用于高亮，-1 不高亮）
 * @param onSelect 选中回调（index）
 */
class DropdownMenuPopup(
    private val context: Context,
    private val options: List<String>,
    private val selectedIndex: Int,
    private val onSelect: (Int) -> Unit
) {

    fun show(anchor: View) {
        val list = ListView(context).apply {
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            val bg = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F21A1330"))
                setStroke(dp(1), Color.parseColor("#3DFFFFFF"))
            }
            background = bg
            setPadding(0, dp(6), 0, dp(6))
        }

        val adapter = object : ArrayAdapter<String>(context, 0, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(context).apply {
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(dp(20), dp(13), dp(20), dp(13))
                    textSize = 14f
                }
                tv.text = getItem(position)
                val selected = position == selectedIndex
                tv.setTextColor(if (selected) Color.parseColor("#FFFF8FC4") else Color.parseColor("#FFEDE9FB"))
                tv.isSelected = selected
                return tv
            }
        }
        list.adapter = adapter

        // 宽度：至少与锚点等宽，过窄选项给一个下限
        val width = maxOf(anchor.width, dp(200))
        val popup = PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onSelect(position)
        }
        popup.showAsDropDown(anchor, 0, dp(6), Gravity.START)
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
