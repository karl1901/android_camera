package com.wheeltech.blindcam.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.recorder.GalleryMedia
import com.wheeltech.blindcam.util.Logger
import java.util.concurrent.LinkedBlockingQueue

/** 媒体唯一 key：优先私有文件绝对路径，其次 SAF uri，最后文件名 */
internal fun GalleryMedia.keyOf(): String =
    file?.absolutePath ?: uri?.toString() ?: name

/**
 * 相册网格适配器（RecyclerView）。
 *
 * 修复点：
 * - 整卡可点：首击即触发（RecyclerView 标准点击，不再有"首击被吃"），封面/名称/时间任意处都打开。
 * - 长按进入多选；多选态点卡片任意位置切换勾选，右上角显示勾选框。
 * - 缩略图异步加载（后台线程 + 内存缓存），滚动手势不被阻塞。
 */
class GalleryAdapter(
    private val onItemClick: (Int) -> Unit,
    private val onItemLongClick: (Int) -> Unit,
    private val selectionState: () -> SelectionState
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    /** 多选状态快照（由 Activity 维护） */
    data class SelectionState(val active: Boolean, val selectedKeys: Set<String>)

    private var items: List<GalleryMedia> = emptyList()

    fun submit(list: List<GalleryMedia>) {
        items = list
        notifyDataSetChanged()
    }

    fun get(position: Int): GalleryMedia? = items.getOrNull(position)

    /** 当前展示（已筛选）的列表，用于全选 */
    fun currentList(): List<GalleryMedia> = items

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val imgThumb: ImageView = v.findViewById(R.id.imgThumb)
        val imgPlay: ImageView = v.findViewById(R.id.imgPlay)
        val tvDuration: TextView = v.findViewById(R.id.tvDuration)
        val imgCheck: ImageView = v.findViewById(R.id.imgCheck)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvTime: TextView = v.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val sel = selectionState()
        val isVideo = m.isVideo
        val key = m.keyOf()
        holder.tvName.text = m.device
        holder.tvTime.text = m.displayTime

        // 视频角标 / 时长
        holder.imgPlay.visibility = if (isVideo) View.VISIBLE else View.GONE
        if (isVideo) {
            holder.tvDuration.visibility = View.VISIBLE
            holder.tvDuration.text = durationText(m.durationMs)
        } else {
            holder.tvDuration.visibility = View.GONE
        }

        // 多选勾选框
        if (sel.active) {
            holder.imgCheck.visibility = View.VISIBLE
            holder.imgCheck.setImageResource(
                if (key in sel.selectedKeys) R.drawable.bg_checkbox_checked
                else R.drawable.bg_checkbox_unchecked
            )
        } else {
            holder.imgCheck.visibility = View.GONE
        }

        // 点击 / 长按（整卡）
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick(pos)
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onItemLongClick(pos)
                true
            } else false
        }

        // 缩略图（带 tag 防错位）
        holder.imgThumb.tag = key
        holder.imgThumb.setImageBitmap(null)
        ThumbLoader.load(m, holder.imgThumb)
    }

    private fun durationText(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val min = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, min, s)
        else String.format(java.util.Locale.US, "%02d:%02d", min, s)
    }

    /**
     * 缩略图加载器：内存 LruCache + 单后台线程串行加载（视频用 MediaMetadataRetriever，
     * 照片用 inSampleSize 解码），加载完按 tag 匹配回主线程设置。
     */
    private object ThumbLoader {
        private const val THUMB_W = 320
        private const val THUMB_H = 240

        private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }

        private data class Job(val media: GalleryMedia, val target: ImageView)
        private val queue = LinkedBlockingQueue<Job>()
        private val main = Handler(android.os.Looper.getMainLooper())
        private val worker: HandlerThread = HandlerThread("thumb-loader").apply { start() }
        private val workerHandler = Handler(worker.looper)

        init {
            workerHandler.post(object : Runnable {
                override fun run() {
                    while (true) {
                        val job = runCatching { queue.take() }.getOrNull() ?: continue
                        val bmp = build(job.media, job.target.context)
                        main.post {
                            if (job.target.tag == job.media.keyOf()) {
                                if (bmp != null) job.target.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            })
        }

        fun load(m: GalleryMedia, target: ImageView) {
            val key = m.keyOf()
            cache.get(key)?.let {
                target.setImageBitmap(it)
                return
            }
            queue.offer(Job(m, target))
        }

        private fun build(m: GalleryMedia, ctx: android.content.Context): Bitmap? {
            val key = m.keyOf()
            cache.get(key)?.let { return it }
            val bmp: Bitmap? = try {
                if (m.isVideo) videoFrame(m, ctx) else photoSample(m, ctx)
            } catch (t: Throwable) {
                Logger.w("thumb", "load failed $key: ${t.message}")
                null
            }
            if (bmp != null) cache.put(key, bmp)
            return bmp
        }

        private fun photoSample(m: GalleryMedia, ctx: android.content.Context): Bitmap? {
            val decode: (BitmapFactory.Options?) -> Bitmap? = { opts ->
                m.file?.let {
                    if (opts == null) BitmapFactory.decodeFile(it.absolutePath)
                    else BitmapFactory.decodeFile(it.absolutePath, opts)
                } ?: m.uri?.let { u ->
                    ctx.contentResolver.openInputStream(u)?.use { stream ->
                        if (opts == null) BitmapFactory.decodeStream(stream)
                        else BitmapFactory.decodeStream(stream, null, opts)
                    }
                }
            }
            // bounds
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decode(bo)
            var sample = 1
            var w = bo.outWidth; var h = bo.outHeight
            while (w / 2 >= THUMB_W && h / 2 >= THUMB_H) { w /= 2; h /= 2; sample *= 2 }
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            return decode(o)
        }

        private fun videoFrame(m: GalleryMedia, ctx: android.content.Context): Bitmap? {
            val mmr = MediaMetadataRetriever()
            return try {
                when {
                    m.file != null -> mmr.setDataSource(m.file.absolutePath)
                    m.uri != null -> mmr.setDataSource(ctx, m.uri)
                    else -> return null
                }
                mmr.getFrameAtTime(0)
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { mmr.release() }
            }
        }
    }
}
