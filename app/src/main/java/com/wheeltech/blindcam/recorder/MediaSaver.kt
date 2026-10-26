package com.wheeltech.blindcam.recorder

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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

    /** 照片默认目录的完整可读路径（用于设置页展示） */
    fun defaultPhotoPath(): String = defaultPhotoDir().absolutePath

    /** 录像默认目录的完整可读路径（用于设置页展示） */
    fun defaultVideoPath(): String = defaultVideoDir().absolutePath

    /**
     * 把用户 SAF 选择的目录树 Uri 转成可读的绝对路径用于展示。
     * 例：primary:DCIM/Cam -> /storage/emulated/0/DCIM/Cam；
     *     外置卷 1A2B-3C4D:Movies -> /storage/1A2B-3C4D/Movies。
     * 解析失败时退回原始 Uri 字符串。
     */
    fun treeDisplayName(treeUri: String): String {
        if (treeUri.isBlank()) return defaultPhotoPath()
        return runCatching {
            val uri = Uri.parse(treeUri)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val sep = docId.indexOf(':')
            if (sep < 0) {
                // 形如 "raw:/storage/..." 或卷根目录
                docId.substringAfter("raw:").ifBlank { treeUri }
            } else {
                val vol = docId.substring(0, sep)
                val sub = docId.substring(sep + 1).trim('/', ':')
                val root = if (vol.equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else {
                    "${File.separator}storage${File.separator}$vol"
                }
                if (sub.isBlank()) root else "$root${File.separator}${sub.replace(':', File.separatorChar)}"
            }
        }.getOrDefault(treeUri)
    }

    /** 照片当前实际保存目录的完整路径（自定义则显示自定义，否则默认） */
    fun photoDisplayPath(treeUri: String): String =
        if (treeUri.isBlank()) defaultPhotoPath() else treeDisplayName(treeUri)

    /** 录像当前实际保存目录的完整路径（自定义则显示自定义，否则默认） */
    fun videoDisplayPath(treeUri: String): String =
        if (treeUri.isBlank()) defaultVideoPath() else treeDisplayName(treeUri)

    /** 录像临时缓存目录 */
    fun cacheDir(): File =
        File(context.cacheDir, CACHE_DIR).apply { mkdirs() }

    /** 新缓存文件（录像写入用，默认 MP4） */
    fun newCacheFile(extension: String = "mp4"): File =
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
    fun publishVideo(cache: File, fileName: String, treeUri: String): String =
        publishFile(cache, fileName, treeUri, "video/mp4")

    /**
     * 把缓存文件移动/拷贝到录像目录（私有目录按名落盘，SAF 目录按 mime 创建文档）。
     * 用于录像（mp4）与 GPS 轨迹（csv）等所有录制产物。
     */
    fun publishFile(cache: File, fileName: String, treeUri: String, mime: String): String {
        if (treeUri.isNotBlank()) {
            val uri = Uri.parse(treeUri)
            val doc = createDocument(uri, mime, fileName)
                ?: throw IllegalStateException("无法在所选目录创建文件：$fileName")
            context.contentResolver.openOutputStream(doc, "w")?.use { out ->
                cache.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IllegalStateException("无法写入文件：$fileName")
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

    // ------------------------------------------------------------------
    // 循环录像：空间查询 / 总录像段列举 / 删除最旧段
    // ------------------------------------------------------------------

    /** 录像目录可用空间（MB）。私有目录走 StatFs，SAF 目录解析所在卷后 StatFs。 */
    fun videoFreeSpaceMb(treeUri: String): Long {
        val root = if (treeUri.isBlank()) defaultVideoDir() else treeVolumeRoot(treeUri)
            ?: defaultVideoDir()
        return runCatching {
            val stat = android.os.StatFs(root.absolutePath)
            stat.availableBytes / (1024L * 1024L)
        }.getOrDefault(Long.MAX_VALUE / (1024L * 1024L))
    }

    /** 总录像分段（ALL_ 开头，按时间从旧到新），用于循环覆盖时删最旧。 */
    fun compositeSegments(treeUri: String): List<GalleryMedia> =
        scanVideos(treeUri).filter { it.kind == GalleryMedia.Kind.ALL }
            .sortedBy { it.timeMs }

    /**
     * 删除单个媒体项（照片/视频通用）：
     * - SAF 目录项（[GalleryMedia.uri] 非空）：直接 deleteDocument
     * - 私有目录项（[GalleryMedia.file] 非空）：file.delete()
     */
    fun deleteMedia(media: GalleryMedia): Boolean {
        media.uri?.let { u ->
            return runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, u)
            }.getOrDefault(false)
        }
        media.file?.let { f ->
            return runCatching { if (f.exists()) f.delete() else false }.getOrDefault(false)
        }
        return false
    }

    /** 删除指定文件名的录像（私有目录按名删除；SAF 目录按 displayName 定位后删除）。 */
    fun deleteVideoByName(treeUri: String, name: String): Boolean {
        if (treeUri.isBlank()) {
            return runCatching { File(defaultVideoDir(), name).delete() }.getOrDefault(false)
        }
        return runCatching {
            val tree = Uri.parse(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            var deleted = false
            context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == name) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(
                            tree, c.getString(idIdx)
                        )
                        deleted = DocumentsContract.deleteDocument(context.contentResolver, docUri)
                        break
                    }
                }
            }
            deleted
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // GPS 轨迹：按日期读取总录像关联的轨迹点
    // ------------------------------------------------------------------

    /**
     * 读取某一天（dateKey=yyyyMMdd）全部 GPS 轨迹点（可能跨多个总录像段）。
     * 合并私有目录与 SAF 目录下 GPS_<dateKey>*.csv，按时间升序。
     */
    fun readGpsTrack(videoDirUri: String, dateKey: String): List<com.wheeltech.blindcam.location.TrackPoint> {
        val out = ArrayList<com.wheeltech.blindcam.location.TrackPoint>()
        // 私有目录
        runCatching {
            defaultVideoDir().listFiles { f ->
                f.isFile && f.name.startsWith("GPS_$dateKey") &&
                    f.name.lowercase().endsWith(".csv")
            }?.forEach { out.addAll(parseGpsCsv(it.readText(Charsets.UTF_8))) }
        }
        // SAF 自定义目录
        if (videoDirUri.isNotBlank()) {
            runCatching {
                val tree = Uri.parse(videoDirUri)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                context.contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val name = c.getString(nameIdx) ?: continue
                        if (!name.startsWith("GPS_$dateKey") || !name.lowercase().endsWith(".csv")) continue
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(idIdx))
                        context.contentResolver.openInputStream(docUri)?.use { input ->
                            out.addAll(parseGpsCsv(input.bufferedReader(Charsets.UTF_8).readText()))
                        }
                    }
                }
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun parseGpsCsv(text: String): List<com.wheeltech.blindcam.location.TrackPoint> {
        val list = ArrayList<com.wheeltech.blindcam.location.TrackPoint>()
        text.lineSequence().forEach { line ->
            val c = line.split(',')
            if (c.size < 4) return@forEach
            val t = c[0].toLongOrNull() ?: return@forEach
            val lat = c[2].toDoubleOrNull() ?: return@forEach
            val lon = c[3].toDoubleOrNull() ?: return@forEach
            val acc = c.getOrNull(4)?.toFloatOrNull() ?: 0f
            val spd = c.getOrNull(5)?.toFloatOrNull() ?: 0f
            val brg = c.getOrNull(6)?.toFloatOrNull() ?: 0f
            list.add(com.wheeltech.blindcam.location.TrackPoint(t, lat, lon, acc, spd, brg))
        }
        return list
    }

    /** 解析 SAF 目录树所在存储卷根目录（用于 StatFs 取可用空间） */
    private fun treeVolumeRoot(treeUri: String): File? = runCatching {
        val uri = Uri.parse(treeUri)
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val sep = docId.indexOf(':')
        if (sep < 0) {
            val raw = docId.substringAfter("raw:")
            return@runCatching if (raw.isNotBlank()) File(raw) else null
        }
        val vol = docId.substring(0, sep)
        if (vol.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$vol")
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // 相册扫描：合并应用私有目录与用户 SAF 自定义目录
    // ------------------------------------------------------------------

    /** 扫描全部照片（私有默认目录 + SAF 自定义目录） */
    fun scanPhotos(photoTreeUri: String): List<GalleryMedia> {
        val out = ArrayList<GalleryMedia>()
        out.addAll(scanPrivate(defaultPhotoDir(), wantVideo = false))
        if (photoTreeUri.isNotBlank()) out.addAll(scanTree(photoTreeUri, wantVideo = false))
        return out.sortedByDescending { it.timeMs }
    }

    /** 扫描全部视频（私有默认目录 + SAF 自定义目录），含分录像与总录像 */
    fun scanVideos(videoTreeUri: String): List<GalleryMedia> {
        val out = ArrayList<GalleryMedia>()
        out.addAll(scanPrivate(defaultVideoDir(), wantVideo = true))
        if (videoTreeUri.isNotBlank()) out.addAll(scanTree(videoTreeUri, wantVideo = true))
        return out.sortedByDescending { it.timeMs }
    }

    private fun scanPrivate(dir: File, wantVideo: Boolean): List<GalleryMedia> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && accept(it.name, null, wantVideo) }
            .mapNotNull { f ->
                GalleryMedia.parse(f.name, wantVideo, f.lastModified())
                    ?.copy(file = f, durationMs = if (wantVideo) probeDuration(f.absolutePath) else 0L)
            }
    }

    /** 读取视频时长（毫秒），失败返回 0 */
    private fun probeDuration(path: String): Long {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun probeDurationUri(uri: Uri): Long {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun scanTree(treeUri: String, wantVideo: Boolean): List<GalleryMedia> {
        val out = ArrayList<GalleryMedia>()
        runCatching {
            val tree = Uri.parse(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val timeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else continue
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                    if (!accept(name, mime, wantVideo)) continue
                    val docId = c.getString(idIdx)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    val last = if (timeIdx >= 0 && !c.isNull(timeIdx)) c.getLong(timeIdx) else 0L
                    GalleryMedia.parse(name, wantVideo, last)
                        ?.copy(uri = docUri,
                            durationMs = if (wantVideo) probeDurationUri(docUri) else 0L)
                        ?.let { out.add(it) }
                }
            }
        }.onFailure { Logger.w(TAG, "scanTree failed: ${it.message}") }
        return out
    }

    /** 按 mime 或扩展名判断是否为目标类型（兼容 mime 为 octet-stream 的旧 .avi） */
    private fun accept(name: String, mime: String?, wantVideo: Boolean): Boolean {
        val lower = name.lowercase()
        val isImgExt = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
        val isVidExt = lower.endsWith(".mp4") || lower.endsWith(".avi") ||
            lower.endsWith(".mov") || lower.endsWith(".mkv")
        val isImgMime = mime?.startsWith("image/") == true
        val isVidMime = mime?.startsWith("video/") == true
        val isImg = isImgExt || isImgMime
        val isVid = isVidExt || isVidMime
        return if (wantVideo) isVid else isImg
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
