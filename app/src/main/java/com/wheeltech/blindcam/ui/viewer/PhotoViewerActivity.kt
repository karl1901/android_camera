package com.wheeltech.blindcam.ui.viewer

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wheeltech.blindcam.R
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
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        !path.isNullOrBlank() -> BitmapFactory.decodeFile(path)
                        !uriStr.isNullOrBlank() ->
                            contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                                BitmapFactory.decodeStream(it)
                            }
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

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_TIME_MS = "extra_time_ms"
    }
}
