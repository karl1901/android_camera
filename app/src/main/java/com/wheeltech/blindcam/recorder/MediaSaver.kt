package com.wheeltech.blindcam.recorder

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.wheeltech.blindcam.util.Logger
import java.io.File
import java.io.FileOutputStream

/**
 * 媒体保存：拍照 JPEG、录像文件落地。
 *
 * 保存位置支持两种来源：
 * 1. 用户通过 SAF（OpenDocumentTree）选择的目录树 Uri（拍照/录像各自独立配置）
 * 2. 未配置时退回应用私有外部目录（无需任何存储权限，卸载自动删除）
 *
 * 录像（AVI）因为需要在结束时回填文件头，先用可随机读写的私有缓存文件，
 * 录制结束再一次性拷贝到目标目录（兼容 SAF 的流式输出）。
 */
class MediaSaver(private val context: Context) {

    companion object {
        private const val TAG = "media-saver"
        private const val PHOTO_DIR = "blindcam_photos"
        private const val VIDEO_DIR = "blindcam_videos"
        private const val CACHE_DIR = "record_cache"
    }

    /** 照片默认目录（私有） */
    fun defaultPhotoDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, PHOTO_DIR).apply { mkdirs() }

    /** 录像默认目录（私有） */
    fun defaultVideoDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, VIDEO_DIR).apply { mkdirs() }

    /** 录像临时缓存目录 */
    fun cacheDir(): File =
        File(context.cacheDir, CACHE_DIR).apply { mkdirs() }

    /** 新缓存文件（录像写入用） */
    fun newCacheFile(extension: String = "avi"): File =
        File(cacheDir(), "rec_${System.currentTimeMillis()}.$extension")

    /** 持久化 SAF 目录授权（重启后仍可写） */
    fun persistTreeUri(uri: Uri) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { Logger.w(TAG, "takePersistableUriPermission failed: ${it.message}") }
    }

    /**
     * 保存照片 JPEG（原始字节已是设备输出的完整 JPEG）。
     * @return 保存结果的可读路径 / 文件名（用于提示）；失败抛异常
     */
    fun savePhoto(jpeg: ByteArray, fileName: String, treeUri: String): String {
        if (treeUri.isNotBlank()) {
            val uri = Uri.parse(treeUri)
            val doc = createDocument(uri, "image/jpeg", fileName)
                ?: throw IllegalStateException("无法在所选目录创建照片")
            context.contentResolver.openOutputStream(doc, "w")?.use { it.write(jpeg) }
                ?: throw IllegalStateException("无法写入照片")
            return fileName
        }
        val f = File(defaultPhotoDir(), fileName)
        FileOutputStream(f).use { it.write(jpeg) }
        return f.absolutePath
    }

    /**
     * 把录像缓存文件移动（优先改名，跨卷则拷贝）到目标目录。
     * @return 最终文件名
     */
    fun publishVideo(cache: File, fileName: String, treeUri: String): String {
        if (treeUri.isNotBlank()) {
            val uri = Uri.parse(treeUri)
            val mime = "video/x-msvideo"
            val doc = createDocument(uri, mime, fileName)
                ?: throw IllegalStateException("无法在所选目录创建录像")
            context.contentResolver.openOutputStream(doc, "w")?.use { out ->
                cache.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IllegalStateException("无法写入录像")
            cache.delete()
            return fileName
        }
        val target = File(defaultVideoDir(), fileName)
        if (!cache.renameTo(target)) {
            target.outputStream().use { out -> cache.inputStream().use { it.copyTo(out, 64 * 1024) } }
            cache.delete()
        }
        return target.absolutePath
    }

    /** 丢弃录像缓存（停止且不保存） */
    fun discardCache(cache: File) {
        runCatching { if (cache.exists()) cache.delete() }
    }

    /** 导出文本（如运行日志）到用户选择的 SAF 目录，返回文件名 */
    fun exportText(treeUri: String, fileName: String, text: String): String {
        val uri = Uri.parse(treeUri)
        val doc = createDocument(uri, "text/plain", fileName)
            ?: throw IllegalStateException("无法在所选目录创建文件")
        context.contentResolver.openOutputStream(doc, "w")?.use { it.write(text.toByteArray()) }
            ?: throw IllegalStateException("无法写入文件")
        return fileName
    }

    /** 在 SAF 目录树下创建文档，返回新文档 Uri */
    private fun createDocument(treeUri: Uri, mimeType: String, displayName: String): Uri? {
        return runCatching {
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            DocumentsContract.createDocument(
                context.contentResolver, parent, mimeType, displayName
            )
        }.getOrElse {
            // 某些设备 buildDocumentUriUsingTree 需要用 child 形式，退而求其次
            runCatching {
                DocumentsContract.createDocument(
                    context.contentResolver, treeUri, mimeType, displayName
                )
            }.getOrNull()
        }
    }
}
