package com.wheeltech.blindcam.recorder

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.wheeltech.blindcam.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 媒体保存：拍照 JPEG、录像 MP4 落地。
 *
 * 保存位置支持两种来源：
 * 1. 用户通过 SAF（OpenDocumentTree）选择的目录树 Uri（拍照/录像各自独立配置）
 * 2. 未配置时退回应用私有外部目录（无需任何存储权限，卸载自动删除）
 *
 * 录像先写入可随机读写的私有缓存文件，录制结束再整体发布到目标目录
 * （SAF 只能流式写，无法边录边回填 moov）。
 */
class MediaSaver(private val context: Context) {

    private val cacheSeq = AtomicLong(0L)

    /**
     * 总录像的分段元数据：记录录制时参与合成的画面名称/路数。
     * 回看时优先使用它，而不是“当前还插着的摄像头”，这样拔掉摄像头后
     * 仍能显示正确的自定义名称与裁剪区域。
     */
    data class CompositeMeta(
        val cellNames: List<String>,
        val width: Int,
        val height: Int
    )

    /**
     * 新总录像组元数据：每个画框一个独立 MP4，回看按组同步播放。
     */
    data class TotalMeta(
        val cellNames: List<String>,
        val partNames: List<String>,
        val previewName: String,
        val width: Int,
        val height: Int
    )

    companion object {
        private const val TAG = "media-saver"
        private const val PHOTO_DIR = "blindcam_photos"
        private const val VIDEO_DIR = "blindcam_videos"
        private const val CACHE_DIR = "record_cache"
        private val TOTAL_PART_REGEX =
            Regex("^(ALL_\\d{8}_\\d{6})_c\\d+_", RegexOption.IGNORE_CASE)
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

    /** 新缓存文件（录像写入用，默认 MP4）。
     *  同一毫秒内多路总录像会同时申请缓存文件，仅用时间戳会重名，
     *  后一路会把前一路的 MP4 覆盖/删除，最终得到 0 字节损坏文件。 */
    fun newCacheFile(extension: String = "mp4"): File =
        File(
            cacheDir(),
            "rec_${System.currentTimeMillis()}_${cacheSeq.incrementAndGet()}.$extension"
        )

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
            try {
                context.contentResolver.openOutputStream(doc, "w")?.use { out ->
                    cache.inputStream().use { it.copyTo(out, 64 * 1024) }
                } ?: throw IllegalStateException("无法写入文件：$fileName")
            } catch (t: Throwable) {
                // 复制失败必须删掉刚创建的 0 字节目标，避免相册出现损坏文件
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
                throw t
            }
            cache.delete()
            return fileName
        }
        val target = File(defaultVideoDir(), fileName)
        try {
            if (!cache.renameTo(target)) {
                target.outputStream().use { out -> cache.inputStream().use { it.copyTo(out, 64 * 1024) } }
                cache.delete()
            }
        } catch (t: Throwable) {
            runCatching { if (target.exists()) target.delete() }
            throw t
        }
        return target.absolutePath
    }

    /** 丢弃录像缓存（停止且不保存） */
    fun discardCache(cache: File) {
        runCatching { if (cache.exists()) cache.delete() }
    }

    // ------------------------------------------------------------------
    // 总录像分段元数据（.json sidecar）
    // ------------------------------------------------------------------

    private fun metaName(videoFileName: String): String =
        videoFileName.substringBeforeLast('.', videoFileName) + ".json"

    /**
     * 发布总录像分段元数据。失败只记日志，不影响录像文件本身。
     * 文件名与 MP4 同名（扩展名 .json），相册扫描只认 mp4/jpg，不会把 json 当成媒体。
     */
    fun publishCompositeMeta(
        videoFileName: String,
        cellNames: List<String>,
        width: Int,
        height: Int,
        videoDirUri: String
    ) {
        val json = runCatching {
            JSONObject().apply {
                put("version", 1)
                put("width", width)
                put("height", height)
                put("cells", JSONArray(cellNames))
            }.toString()
        }.getOrNull() ?: return
        val name = metaName(videoFileName)
        runCatching {
            if (videoDirUri.isNotBlank()) {
                val tree = Uri.parse(videoDirUri)
                val doc = createDocument(tree, "application/json", name)
                    ?: throw IllegalStateException("无法创建元数据文件")
                context.contentResolver.openOutputStream(doc, "w")?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
            } else {
                File(defaultVideoDir(), name).writeText(json, Charsets.UTF_8)
            }
            Logger.i(TAG, "composite meta saved $name cells=${cellNames.size}")
        }.onFailure {
            Logger.w(TAG, "composite meta save failed $name: ${it.message}")
        }
    }

    /**
     * 读取总录像分段元数据。
     * @param videoFileName 录制文件名（ALL_...mp4）
     * @param videoDirUri   当前录像目录（SAF；空=私有默认目录）
     * @param file          私有目录文件（SAF 项为 null）
     */
    fun readCompositeMeta(
        videoFileName: String,
        videoDirUri: String,
        file: File?
    ): CompositeMeta? {
        val name = metaName(videoFileName)
        val text = runCatching {
            if (file != null) {
                File(file.parentFile, name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            } else if (videoDirUri.isNotBlank()) {
                val tree = Uri.parse(videoDirUri)
                val doc = findChildUri(tree, name) ?: return null
                context.contentResolver.openInputStream(doc)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                }
            } else {
                File(defaultVideoDir(), name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(text)
            val arr = o.optJSONArray("cells") ?: JSONArray()
            val names = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) names.add(arr.optString(i))
            CompositeMeta(
                cellNames = names,
                width = o.optInt("width", 1280),
                height = o.optInt("height", 720)
            )
        }.onFailure { Logger.w(TAG, "parse composite meta failed: ${it.message}") }
            .getOrNull()
    }

    /** 删除总录像时同时删除同名元数据，避免目录里堆积孤儿 json */
    fun deleteCompositeMeta(videoFileName: String, videoDirUri: String, file: File?) {
        val name = metaName(videoFileName)
        runCatching {
            if (file != null) {
                File(file.parentFile, name).delete()
            } else if (videoDirUri.isNotBlank()) {
                findChildUri(Uri.parse(videoDirUri), name)?.let {
                    DocumentsContract.deleteDocument(context.contentResolver, it)
                }
            } else {
                File(defaultVideoDir(), name).delete()
            }
        }
    }

    /**
     * 新总录像组元数据：groupKey=ALL_yyyyMMdd_HHmmss；每个 part 一个 MP4。
     */
    fun publishTotalMeta(
        groupKey: String,
        cellNames: List<String>,
        partNames: List<String>,
        previewName: String,
        width: Int,
        height: Int,
        videoDirUri: String
    ) {
        val json = runCatching {
            JSONObject().apply {
                put("version", 2)
                put("group", groupKey)
                put("width", width)
                put("height", height)
                put("preview", previewName)
                put("cells", JSONArray(cellNames))
                put("parts", JSONArray(partNames))
            }.toString()
        }.getOrNull() ?: return
        val name = "$groupKey.json"
        runCatching {
            if (videoDirUri.isNotBlank()) {
                val tree = Uri.parse(videoDirUri)
                val doc = createDocument(tree, "application/json", name)
                    ?: throw IllegalStateException("无法创建总录像元数据")
                context.contentResolver.openOutputStream(doc, "w")?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
            } else {
                File(defaultVideoDir(), name).writeText(json, Charsets.UTF_8)
            }
            Logger.i(TAG, "total meta saved $name cells=${cellNames.size} parts=${partNames.size}")
        }.onFailure { Logger.w(TAG, "total meta save failed $name: ${it.message}") }
    }

    fun readTotalMeta(groupKey: String, videoDirUri: String, file: File?): TotalMeta? {
        val name = "$groupKey.json"
        val text = runCatching {
            if (file != null) {
                File(file.parentFile, name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            } else if (videoDirUri.isNotBlank()) {
                val tree = Uri.parse(videoDirUri)
                val doc = findChildUri(tree, name) ?: return null
                context.contentResolver.openInputStream(doc)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                }
            } else {
                File(defaultVideoDir(), name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(text)
            fun strings(key: String): List<String> {
                val arr = o.optJSONArray(key) ?: JSONArray()
                return List(arr.length()) { arr.optString(it) }
            }
            TotalMeta(
                cellNames = strings("cells"),
                partNames = strings("parts"),
                previewName = o.optString("preview"),
                width = o.optInt("width", 1280),
                height = o.optInt("height", 720)
            )
        }.onFailure { Logger.w(TAG, "parse total meta failed: ${it.message}") }
            .getOrNull()
    }

    fun publishPreview(cache: File, name: String, videoDirUri: String): String =
        publishFile(cache, name, videoDirUri, "image/jpeg")

    fun deleteTotalMeta(groupKey: String, videoDirUri: String, file: File?) {
        val name = "$groupKey.json"
        runCatching {
            if (file != null) {
                File(file.parentFile, name).delete()
            } else if (videoDirUri.isNotBlank()) {
                findChildUri(Uri.parse(videoDirUri), name)?.let {
                    DocumentsContract.deleteDocument(context.contentResolver, it)
                }
            } else {
                File(defaultVideoDir(), name).delete()
            }
        }
    }

    /** 在 SAF 目录树中按显示名查找子文档 */
    private fun findChildUri(tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) {
                if (nameIdx >= 0 && c.getString(nameIdx) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(idIdx))
                }
            }
        }
        return null
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

    /** 总录像分段（ALL_ 开头，按时间从旧到新），用于循环覆盖时删最旧。无需读取时长。 */
    fun compositeSegments(treeUri: String): List<GalleryMedia> =
        scanVideos(treeUri, probeDurations = false)
            .filter { it.kind == GalleryMedia.Kind.ALL }
            .sortedBy { it.timeMs }

    /**
     * 删除单个媒体项（照片/视频通用）：
     * - SAF 目录项（[GalleryMedia.uri] 非空）：直接 deleteDocument
     * - 私有目录项（[GalleryMedia.file] 非空）：file.delete()
     */
    fun deleteMedia(media: GalleryMedia): Boolean {
        if (media.parts.isNotEmpty()) {
            var any = false
            media.parts.forEach { part ->
                val ok = part.uri?.let { u ->
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, u) }
                        .getOrDefault(false)
                } ?: part.file?.let { f ->
                    runCatching { if (f.exists()) f.delete() else false }.getOrDefault(false)
                } ?: false
                any = any || ok
            }
            media.previewFile?.let { runCatching { it.delete() } }
            media.previewUri?.let {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) }
            }
            media.groupKey?.let {
                deleteTotalMeta(it, media.treeUri.orEmpty(), media.file)
            }
            return any
        }
        val deleted = media.uri?.let { u ->
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, u)
            }.getOrDefault(false)
        } ?: media.file?.let { f ->
            runCatching { if (f.exists()) f.delete() else false }.getOrDefault(false)
        } ?: false
        if (deleted && media.isVideo && media.kind == GalleryMedia.Kind.ALL) {
            deleteCompositeMeta(media.name, media.treeUri.orEmpty(), media.file)
        }
        return deleted
    }

    /** 删除指定文件名的录像（私有目录按名删除；SAF 目录按 displayName 定位后删除）。 */
    fun deleteVideoByName(treeUri: String, name: String): Boolean {
        val deleted = if (treeUri.isBlank()) {
            runCatching { File(defaultVideoDir(), name).delete() }.getOrDefault(false)
        } else {
            runCatching {
                val tree = Uri.parse(treeUri)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                var ok = false
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
                            ok = DocumentsContract.deleteDocument(context.contentResolver, docUri)
                            break
                        }
                    }
                }
                ok
            }.getOrDefault(false)
        }
        if (deleted) {
            // 同步清理总录像的 .json 元数据，避免循环覆盖后留下孤儿文件
            deleteCompositeMeta(name, treeUri, null)
        }
        return deleted
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
            val provider = c.getOrNull(7)?.trim().orEmpty()
            list.add(
                com.wheeltech.blindcam.location.TrackPoint(
                    t, lat, lon, acc, spd, brg, provider
                )
            )
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

    /**
     * 扫描全部视频（私有默认目录 + SAF 自定义目录），含分录像与总录像。
     * @param probeDurations 是否逐个读取时长；回看页只关心某一天、循环清理只关心文件名，
     *   传 false 可避免在几百个录像上逐个 MediaMetadataRetriever 造成长时间卡顿。
     */
    fun scanVideos(videoTreeUri: String, probeDurations: Boolean = true): List<GalleryMedia> {
        val out = ArrayList<GalleryMedia>()
        out.addAll(scanPrivate(defaultVideoDir(), wantVideo = true, probe = probeDurations))
        if (videoTreeUri.isNotBlank()) {
            out.addAll(scanTree(videoTreeUri, wantVideo = true, probe = probeDurations))
        }
        return groupTotalParts(out).sortedByDescending { it.timeMs }
    }

    /**
     * 新总录像由多个 ALL_<ts>_cN_*.mp4 组成；相册只显示一张卡。
     * 旧版单文件总录像保持原样（回看内部仍兼容）。
     */
    private fun groupTotalParts(items: List<GalleryMedia>): List<GalleryMedia> {
        val parts = ArrayList<GalleryMedia>()
        val others = ArrayList<GalleryMedia>()
        items.forEach { m ->
            if (m.kind == GalleryMedia.Kind.ALL && TOTAL_PART_REGEX.containsMatchIn(m.name)) {
                parts.add(m)
            } else {
                others.add(m)
            }
        }
        if (parts.isEmpty()) return items
        val groups = parts.groupBy { m ->
            TOTAL_PART_REGEX.find(m.name)?.groupValues?.getOrNull(1) ?: m.name
        }
        groups.forEach { (key, list) ->
            val sorted = list.sortedBy { it.name }
            val first = sorted.first()
            val previewName = "${key}_preview.jpg"
            val previewFile = first.file?.parentFile
                ?.let { File(it, previewName).takeIf { f -> f.isFile } }
            val previewUri = if (first.uri != null && first.treeUri != null) {
                runCatching { findChildUri(Uri.parse(first.treeUri), previewName) }.getOrNull()
            } else null
            others.add(
                first.copy(
                    name = key,
                    groupKey = key,
                    device = "总录像",
                    timeMs = totalGroupTime(key) ?: first.timeMs,
                    durationMs = sorted.maxOfOrNull { it.durationMs } ?: first.durationMs,
                    parts = sorted.map {
                        MediaPart(
                            name = it.name,
                            file = it.file,
                            uri = it.uri,
                            treeUri = it.treeUri
                        )
                    },
                    previewFile = previewFile,
                    previewUri = previewUri
                )
            )
        }
        return others
    }

    private fun totalGroupTime(groupKey: String): Long? = runCatching {
        val m = Regex("ALL_(\\d{8})_(\\d{6})").find(groupKey) ?: return null
        java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            .parse(m.groupValues[1] + m.groupValues[2])?.time
    }.getOrNull()

    private fun scanPrivate(
        dir: File,
        wantVideo: Boolean,
        probe: Boolean = true
    ): List<GalleryMedia> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && accept(it.name, null, wantVideo) }
            .mapNotNull { f ->
                GalleryMedia.parse(f.name, wantVideo, f.lastModified())
                    ?.copy(
                        file = f,
                        durationMs = if (wantVideo && probe) probeDuration(f.absolutePath) else 0L
                    )
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

    private fun scanTree(
        treeUri: String,
        wantVideo: Boolean,
        probe: Boolean = true
    ): List<GalleryMedia> {
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
                            treeUri = treeUri,
                            durationMs = if (wantVideo && probe) probeDurationUri(docUri) else 0L)
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
