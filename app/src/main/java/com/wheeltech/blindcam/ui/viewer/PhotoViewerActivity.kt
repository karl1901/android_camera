package com.wheeltech.blindcam.ui.viewer

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.databinding.ActivityPhotoViewerBinding
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内置全屏照片查看器（替代系统看图器）：
 * - 双指缩放 / 双击放大 / 拖动平移（[ZoomableImageView]）
 * - 单击切换顶部栏
 * - 私有目录走文件路径、SAF 目录走 content uri，均在应用内解码，无需借助任何外部 App
 */
class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        val appTheme = application as UvcApp
        setTheme(appTheme.themeResId(appTheme.currentThemeMode))
        super.onCreate(savedInstanceState)
        // 沉浸式全屏（黑底）
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val timeMs = intent.getLongExtra(EXTRA_TIME_MS, 0L)
        binding.tvName.text = name
        binding.tvTime.text = if (timeMs > 0)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs)) else ""

        binding.btnBack.setOnClickListener { finish() }
        binding.photoView.onSingleTap = { toggleBar() }

        loadImage()
    }

    private fun toggleBar() {
        binding.barTop.visibility =
            if (binding.barTop.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun loadImage() {
        val path = intent.getStringExtra(EXTRA_PATH)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val target = maxOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        ).coerceAtLeast(1280)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        !path.isNullOrBlank() -> decodeSampledFile(path, target)
                        !uriStr.isNullOrBlank() -> decodeSampledUri(Uri.parse(uriStr), target)
                        else -> null
                    }
                }.onFailure { Logger.w("viewer", "photo decode failed: ${it.message}") }
                    .getOrNull()
            }
            if (bmp != null) {
                binding.photoView.setImageBitmap(bmp)
            } else {
                binding.tvName.text = getString(R.string.viewer_open_failed)
            }
        }
    }

    /** 按目标尺寸降采样解码，避免 4K 原图在低内存车机上 OOM（仍保留 2 倍屏幕像素供缩放） */
    private fun decodeSampledFile(path: String, target: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, target)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun decodeSampledUri(uri: Uri, target: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, target)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun sampleSizeFor(w: Int, h: Int, target: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target && s < 16) s *= 2
        return s
    }

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_TIME_MS = "extra_time_ms"
    }
}
