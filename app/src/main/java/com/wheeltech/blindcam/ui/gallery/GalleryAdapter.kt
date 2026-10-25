package com.wheeltech.blindcam.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.databinding.ItemGalleryBinding
import com.wheeltech.blindcam.recorder.GalleryMedia
import java.util.concurrent.Executors

/**
 * 相册网格适配器：照片用降采样解码、视频用 [MediaMetadataRetriever] 取首帧，
 * 缩略图在后台线程加载并以 LruCache 缓存，滑动不卡。
 */
class GalleryAdapter(
    private val context: Context,
    private val onClick: (GalleryMedia) -> Unit
) : BaseAdapter() {

    private val items = ArrayList<GalleryMedia>()

    private val thumbCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val main = android.os.Handler(context.mainLooper)

    fun submit(list: List<GalleryMedia>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun shutdown() {
        pool.shutdownNow()
        thumbCache.evictAll()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): GalleryMedia = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val binding = (convertView?.tag as? ItemGalleryBinding)
            ?: ItemGalleryBinding.inflate(LayoutInflater.from(context), parent, false).also {
                it.root.tag = it
            }
        val item = items[position]
        binding.tvDevice.text = if (item.kind == GalleryMedia.Kind.ALL) "总录像" else item.device
        binding.tvTime.text = item.displayTime
        binding.tvPlayBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        binding.imgThumb.setImageDrawable(null)
        // 整卡可点：封面 / 名称 / 时间任意位置点击都触发打开。
        // 显式让所有子视图不拦截触摸，事件统一冒泡到根视图处理（比 GridView.onItemClickListener 更可靠）。
        listOf(binding.imgThumb, binding.tvPlayBadge, binding.tvDevice, binding.tvTime).forEach {
            it.isClickable = false
            it.isFocusable = false
        }
        binding.root.isClickable = true
        binding.root.isFocusable = true
        // 按下有水波纹反馈（foreground 在 M+ 可用，低版本忽略）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true
            )
            binding.root.foreground =
                androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
        }
        binding.root.setOnClickListener {
            com.wheeltech.blindcam.util.Logger.i("gallery", "item click pos=$position name=${item.name}")
            onClick(item)
        }
        loadThumb(binding, item)
        return binding.root
    }

    private fun loadThumb(binding: ItemGalleryBinding, item: GalleryMedia) {
        val key = item.uri?.toString() ?: item.file?.absolutePath ?: item.name
        binding.imgThumb.tag = key
        thumbCache.get(key)?.let {
            binding.imgThumb.setImageBitmap(it)
            return
        }
        pool.execute {
            val bmp = runCatching { decodeThumb(item) }.getOrNull()
            if (bmp != null) {
                thumbCache.put(key, bmp)
                main.post {
                    if (binding.imgThumb.tag == key) binding.imgThumb.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun decodeThumb(item: GalleryMedia): Bitmap? {
        return if (item.isVideo) decodeVideoThumb(item) else decodeImageThumb(item)
    }

    private fun decodeVideoThumb(item: GalleryMedia): Bitmap? {
        val r = MediaMetadataRetriever()
        val raw = try {
            when {
                item.uri != null -> r.setDataSource(context, item.uri)
                item.file != null -> r.setDataSource(item.file.absolutePath)
            }
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            runCatching { r.release() }
        } ?: return null
        return scaleToWidth(raw, 360)
    }

    private fun decodeImageThumb(item: GalleryMedia): Bitmap? {
        val targetW = 360
        // 第一次取原始尺寸
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        if (item.uri != null) {
            context.contentResolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        } else if (item.file != null) {
            BitmapFactory.decodeFile(item.file.absolutePath, bounds)
        }
        var sample = 1
        var w = bounds.outWidth
        while (w / 2 >= targetW) { w /= 2; sample *= 2 }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = when {
            item.uri != null -> context.contentResolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            item.file != null -> BitmapFactory.decodeFile(item.file.absolutePath, opts)
            else -> null
        }
        return raw?.let { scaleToWidth(it, targetW) }
    }

    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (src.width <= targetW) return src
        val h = (src.height * (targetW.toFloat() / src.width)).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, targetW, h, true)
        if (out != src) src.recycle()
        return out
    }
}
