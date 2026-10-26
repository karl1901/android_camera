package com.wheeltech.blindcam.recorder

import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 相册中的一条媒体（照片 / 分录像 / 总录像）。
 *
 * 文件名约定（本轮起）：
 * - 照片：P_<设备名>_yyyyMMdd_HHmmss_SSS.jpg
 * - 分录像：V_<设备名>_yyyyMMdd_HHmmss_宽x高.mp4
 * - 总录像：ALL_yyyyMMdd_HHmmss_宽x高.mp4
 * 兼容旧版：BLIND_*.jpg（照片）、BLIND_*.avi（分录像）、BLIND_ALL_*.avi（总录像）。
 */
data class GalleryMedia(
    val kind: Kind,
    /** 应用私有目录文件（用 FileProvider 打开）；SAF 文件该项为 null */
    val file: File?,
    /** SAF 目录文件的 content uri；私有文件该项为 null */
    val uri: Uri?,
    val name: String,
    /** 用于按设备分组/筛选的设备名（自定义名优先，文件名解析） */
    val device: String,
    /** 拍摄/录制时间（毫秒） */
    val timeMs: Long,
    val isVideo: Boolean,
    /** 视频时长（毫秒）；照片为 0，扫描时填充 */
    val durationMs: Long = 0L
) {
    enum class Kind { PHOTO, SINGLE, ALL }

    /** 日期 key：yyyyMMdd，用于按日期筛选 */
    val dateKey: String by lazy {
        SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date(timeMs))
    }

    /** 可读时间：MM-dd HH:mm */
    val displayTime: String by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(timeMs))
    }

    companion object {
        private const val UNKNOWN_DEVICE = "未命名设备"
        private const val ALL_DEVICE = "总录像"

        /**
         * 本软件产出文件的命名前缀（忽略大小写）：
         * P_ 照片、V_ 分录像、ALL_ 总录像、BLIND_/BLIND_ALL_ 旧版文件。
         * 相册只展示本软件产出的文件——用户即使把保存目录设为系统相册目录，
         * 其个人照片/视频（IMG_/VID_/Screenshot 等）也不会混进本相册。
         */
        private val OWNED_REGEX = Regex("^(P|V|ALL|BLIND)_", RegexOption.IGNORE_CASE)

        /**
         * 文件名时间戳段：
         * - 照片：`P_<device>_yyyyMMdd_HHmmss_SSS.jpg`
         * - 分录像：`V_<device>_yyyyMMdd_HHmmss_WxH.mp4`
         * - 总录像：`ALL_yyyyMMdd_HHmmss_WxH.mp4`
         * group1=设备名/前缀，group2=yyyyMMdd，group3=HHmmss，group4=毫秒(可空，仅照片且紧跟扩展名)。
         * 末尾分辨率段 `_WxH` 可空，避免把分辨率前三位误判为毫秒。
         */
        private val TS_REGEX =
            Regex("^(.+?)_(\\d{8})_(\\d{6})(?:_(\\d{3}))?(?:_\\d+x\\d+)?\\.(?:jpg|jpeg|mp4|avi|mov|mkv)$", RegexOption.IGNORE_CASE)

        /**
         * 根据文件名解析媒体类型 / 设备 / 时间。
         * @param fallbackMs 文件名解析不出时间时使用的时间（如 SAF lastModified）
         */
        fun parse(name: String, isVideo: Boolean, fallbackMs: Long): GalleryMedia? {
            val lower = name.lowercase(Locale.US)
            val isPhotoExt = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            val isVideoExt = lower.endsWith(".mp4") || lower.endsWith(".avi") ||
                lower.endsWith(".mov") || lower.endsWith(".mkv")
            if (!isPhotoExt && !isVideoExt) return null
            // 只展示本软件产出的文件，过滤自定义目录里用户的个人照片/视频
            if (!OWNED_REGEX.containsMatchIn(name)) return null

            // 类型判定：总录像 / 分录像 / 照片
            val kind = when {
                name.startsWith("ALL_") || name.startsWith("BLIND_ALL_") -> Kind.ALL
                isVideoExt -> Kind.SINGLE
                else -> Kind.PHOTO
            }

            // 设备名：P_<device>_yyyyMMdd_HHmmss / V_<device>_yyyyMMdd_HHmmss；
            // 设备名本身可能含下划线（safeName 用下划线替代空格，如 Web_Camera），
            // 故以「日期_时间」段为界取其前全部内容，再把下划线还原为空格展示。
            val device = when {
                name.startsWith("P_") || name.startsWith("V_") -> {
                    val rest = name.substring(2)
                    val raw = TS_REGEX.find(rest)?.groupValues?.getOrNull(1)
                    if (!raw.isNullOrBlank()) raw.replace('_', ' ').trim() else UNKNOWN_DEVICE
                }
                kind == Kind.ALL -> ALL_DEVICE
                else -> UNKNOWN_DEVICE
            }

            // 时间戳：优先文件名中的 yyyyMMdd_HHmmss(_SSS)
            val timeMs = parseTimeFromName(name) ?: fallbackMs

            return GalleryMedia(
                kind = kind,
                file = null,
                uri = null,
                name = name,
                device = device,
                timeMs = timeMs,
                isVideo = isVideoExt
            )
        }

        private fun parseTimeFromName(name: String): Long? {
            val m = TS_REGEX.find(name)
            return runCatching {
                if (m != null) {
                    val date = m.groupValues[2]
                    val time = m.groupValues[3]
                    val ms = m.groupValues[4].ifBlank { "000" }
                    SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).parse(date + time + ms)?.time
                } else {
                    // 兜底：连续 14/17 位数字（无下划线分隔的其它命名）
                    val digits = Regex("\\d{14,17}").find(name)?.value ?: return null
                    val fmt = when (digits.length) {
                        17 -> SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US)
                        14 -> SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                        else -> return null
                    }
                    fmt.parse(digits)?.time
                }
            }.getOrNull()
        }
    }
}
