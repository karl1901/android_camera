// =============================================================================
// uvc_native.cpp —— JNI 桥：Kotlin 层 NativeUvc 与 libuvc 之间的唯一通道
//
// 职责：
//   1. 初始化 / 释放 uvc_context（对应 libusb context）
//   2. 通过 UsbDeviceConnection 的 fd（wrap_sys_device）打开 UVC 设备，
//      USB 权限申请由 Java 层 UsbManager 完成，native 不碰权限弹窗
//   3. 启动 MJPEG 流，帧回调通过 JavaVM Attach 到 JVM，回调到 Kotlin
//   4. 关闭设备、停止流、异常码统一返回负数
//
// 注意：
//   - 帧回调线程是 libuvc 内部 streaming 线程，回调内禁止做耗时操作
//   - 当未拉取 third_party 源码时，整个文件编译为 stub（见 UVC_HAVE_LIBUVC）
// =============================================================================
#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <mutex>

#define LOG_TAG "uvc-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if UVC_HAVE_LIBUVC
// 顺序敏感：必须先包含 libusb.h，使 LIBUSB_API_VERSION 生效，
// libuvc.h 中的 uvc_wrap（要求 >= 0x01000107）才会被声明
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <libuvc/libuvc_internal.h>
#include <string>
#include <vector>
#include <algorithm>
#endif

static JavaVM *g_vm = nullptr;

// 诊断文件完整路径（由 Kotlin 传入 getExternalFilesDir 路径），供 libuvc 内
// uvc_diag 写文件使用（绕过 vivo 不输出 app logcat 的限制）
char g_diag_path[512] = {0};

#if UVC_HAVE_LIBUVC
#include <cstdio>
#include <cstdarg>
static void native_diag(const char *fmt, ...) {
    if (g_diag_path[0] == 0) return;
    FILE *fp = fopen(g_diag_path, "a");
    if (!fp) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(fp, fmt, ap);
    va_end(ap);
    fputc('\n', fp);
    fclose(fp);
}
#endif

// 每一路摄像头一个会话
struct UvcSession {
    int cameraId = -1;
    jobject callbackGlobalRef = nullptr;   // FrameCallback 的全局引用
    jclass callbackIfaceClass = nullptr;  // FrameCallback 接口类全局引用
    jmethodID onFrameMid = nullptr;       // 接口 onFrame 方法 ID
    jbyteArray frameArray = nullptr;      // 复用的帧字节数组（全局引用，避免每帧 NewByteArray）
    jsize frameArrayCap = 0;              // frameArray 当前容量
#if UVC_HAVE_LIBUVC
    uvc_context_t       *ctx = nullptr;   // 不持有所有权（归 NativeUvc 上下文）
    uvc_device_t        *dev = nullptr;
    uvc_device_handle_t *devh = nullptr;
    uvc_stream_ctrl_t    streamCtrl{};
    bool streaming = false;
#endif
};

// 上下文（一个进程一个）
struct UvcContext {
#if UVC_HAVE_LIBUVC
    uvc_context_t *ctx = nullptr;
#endif
    std::mutex lock;
};

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
#if UVC_HAVE_LIBUVC
    LOGI("uvc_native loaded with libuvc backend");
#else
    LOGW("uvc_native loaded as STUB (no libuvc). Run scripts/setup-native.sh");
#endif
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// 辅助：在 libuvc 线程上拿到 JNIEnv
// ---------------------------------------------------------------------------
static JNIEnv *attachJniEnv(bool *attached) {
    JNIEnv *env = nullptr;
    *attached = false;
    if (!g_vm) return nullptr;
    int ret = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            *attached = true;
        } else {
            return nullptr;
        }
    }
    return env;
}

static void detachJniEnv(bool attached) {
    if (attached && g_vm) {
        g_vm->DetachCurrentThread();
    }
}

#if UVC_HAVE_LIBUVC
// ---------------------------------------------------------------------------
// libuvc 帧回调（运行在 streaming 线程，禁止耗时操作）
// 只做一次 JNI 拷贝，把 MJPEG 字节透传给 Kotlin
// ---------------------------------------------------------------------------
static void onUvcFrame(uvc_frame_t *frame, void *userPtr) {
    static int s_frameCount = 0;
    auto *session = static_cast<UvcSession *>(userPtr);
    if (!session || !session->callbackGlobalRef || !frame || frame->data_bytes == 0) {
        return;
    }
    s_frameCount++;
    if (s_frameCount <= 5 || s_frameCount % 60 == 0) {
        const uint8_t *d = (const uint8_t*)frame->data;
        native_diag("FRAME #%d bytes=%zu %dx%d fmt=%d head=%02X %02X %02X %02X tail=%02X %02X",
             s_frameCount, (size_t)frame->data_bytes, frame->width,
             frame->height, frame->frame_format,
             frame->data_bytes > 0 ? d[0] : 0,
             frame->data_bytes > 1 ? d[1] : 0,
             frame->data_bytes > 2 ? d[2] : 0,
             frame->data_bytes > 3 ? d[3] : 0,
             frame->data_bytes > 1 ? d[frame->data_bytes-2] : 0,
             frame->data_bytes > 0 ? d[frame->data_bytes-1] : 0);
    }
    bool attached = false;
    JNIEnv *env = attachJniEnv(&attached);
    if (!env) {
        if (s_frameCount <= 3) native_diag("attachJniEnv FAILED");
        return;
    }

    jsize need = static_cast<jsize>(frame->data_bytes);
    // 复用本会话的数组：容量不足时才重建（按最大帧大小增长，之后稳定复用）
    if (session->frameArray == nullptr || session->frameArrayCap < need) {
        if (session->frameArray != nullptr) {
            env->DeleteGlobalRef(session->frameArray);
            session->frameArray = nullptr;
        }
        jbyteArray fresh = env->NewByteArray(need);
        if (fresh != nullptr) {
            session->frameArray = static_cast<jbyteArray>(env->NewGlobalRef(fresh));
            env->DeleteLocalRef(fresh);
            session->frameArrayCap = need;
        }
    }
    jbyteArray byteArray = session->frameArray;
    if (byteArray) {
        env->SetByteArrayRegion(
                byteArray, 0, need,
                reinterpret_cast<const jbyte *>(frame->data));

        // 使用 nativeStartStream 中缓存的接口类与方法 ID（native 线程
        // FindClass 无法解析 app 类）。CallVoidMethod 按实际对象多态分发。
        if (s_frameCount <= 3)
            native_diag("JNI use cached cls=%p mid=%p attached=%d",
                        session->callbackIfaceClass, session->onFrameMid, attached);
        if (session->onFrameMid) {
            env->CallVoidMethod(
                    session->callbackGlobalRef, session->onFrameMid,
                    static_cast<jint>(session->cameraId),
                    byteArray,
                    static_cast<jint>(frame->width),
                    static_cast<jint>(frame->height),
                    static_cast<jint>(frame->frame_format));
        }
    } else if (s_frameCount <= 3) {
        native_diag("NewByteArray FAILED");
    }

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    detachJniEnv(attached);
}
#endif // UVC_HAVE_LIBUVC

// ===========================================================================
// JNI 接口实现（与 com.wheeltech.blindcam.native.NativeUvc 一一对应）
// ===========================================================================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeIsBackendAvailable(JNIEnv *, jclass) {
#if UVC_HAVE_LIBUVC
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeInit(JNIEnv *env, jobject, jstring diagPath) {
    auto *context = new (std::nothrow) UvcContext();
    if (!context) return 0;
    if (diagPath != nullptr) {
        const char *path = env->GetStringUTFChars(diagPath, nullptr);
        if (path) {
            std::strncpy(g_diag_path, path, sizeof(g_diag_path) - 1);
            env->ReleaseStringUTFChars(diagPath, path);
        }
    }
#if UVC_HAVE_LIBUVC
    // Android 上 app 无权 opendir("/dev/bus/usb")（EACCES）。我们通过
    // UsbDeviceConnection 的 fd 直接 uvc_wrap 打开已授权设备，不需要 libusb
    // 枚举/扫描设备，因此设置全局选项 NO_DEVICE_DISCOVERY 跳过初始扫描，
    // 否则 libusb_init 会因扫描失败返回 -1。
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    libusb_set_option(nullptr, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_DEBUG);

    uvc_error_t err = uvc_init(&context->ctx, nullptr);
    if (err != UVC_SUCCESS || !context->ctx) {
        LOGE("uvc_init failed: %d", err);
        delete context;
        return 0;
    }
    LOGI("uvc_init ok");
#endif
    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeRelease(JNIEnv *, jobject, jlong handle) {
    auto *context = reinterpret_cast<UvcContext *>(handle);
    if (!context) return;
#if UVC_HAVE_LIBUVC
    if (context->ctx) {
        uvc_exit(context->ctx);
        context->ctx = nullptr;
    }
#endif
    delete context;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeOpenFd(
        JNIEnv *env, jobject, jlong ctxHandle, jint fd,
        jint vid, jint pid, jint cameraId) {
    auto *context = reinterpret_cast<UvcContext *>(ctxHandle);
    if (!context) return 0;
    auto *session = new (std::nothrow) UvcSession();
    if (!session) return 0;
    session->cameraId = cameraId;
#if UVC_HAVE_LIBUVC
    std::lock_guard<std::mutex> guard(context->lock);
    session->ctx = context->ctx;

    // v0.0.7 API：uvc_wrap(fd, ctx, &devh) 内部完成 libusb_wrap_sys_device + open，
    // USB 权限已由 Java 层 UsbManager 授予；fd 由 Java 侧 UsbDeviceConnection 持有，
    // libuvc 不会关闭该 fd（uvc_close 只释放句柄）。
    uvc_error_t err = uvc_wrap(
            static_cast<int>(fd),
            context->ctx,
            &session->devh);
    if (err != UVC_SUCCESS || !session->devh) {
        // 典型失败原因：SELinux enforcing 拦截 / 内核未开启 UVC 驱动 / 设备被占用
        LOGE("uvc_wrap(fd=%d, %04x:%04x) failed: %d (SELinux? kernel uvc? occupied?)",
             fd, vid, pid, err);
        delete session;
        return 0;
    }
    // uvc_wrap 不产出 uvc_device_t*，session->dev 保持 nullptr（关闭时不需要 unref）

    // 自动 detach 内核驱动（部分车机内核已绑定 uvcvideo 驱动，不 detach 会 claim 失败）
    libusb_device_handle *usbHandle = uvc_get_libusb_handle(session->devh);
    if (usbHandle) {
        libusb_set_auto_detach_kernel_driver(usbHandle, 1);
        libusb_device *ldev = libusb_get_device(usbHandle);
        if (ldev) {
            int spd = libusb_get_device_speed(ldev);
            struct libusb_device_descriptor dd;
            if (libusb_get_device_descriptor(ldev, &dd) == 0) {
                native_diag("open cam=%d vid=%04x pid=%04x speed=%d(1=full,2=high,3=super) bcdUSB=%x bcdDevice=%x bNumConfigurations=%d",
                              cameraId, vid, pid, spd, dd.bcdUSB, dd.bcdDevice, dd.bNumConfigurations);
            }
        }
    }

    LOGI("uvc open ok: cameraId=%d %04x:%04x", cameraId, vid, pid);
#endif
    return reinterpret_cast<jlong>(session);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeClose(JNIEnv *env, jobject, jlong sessionHandle) {
    auto *session = reinterpret_cast<UvcSession *>(sessionHandle);
    if (!session) return;
#if UVC_HAVE_LIBUVC
    if (session->streaming) {
        uvc_stop_streaming(session->devh);
        session->streaming = false;
    }
    if (session->devh) {
        uvc_close(session->devh);
        session->devh = nullptr;
    }
    if (session->dev) {
        uvc_unref_device(session->dev);
        session->dev = nullptr;
    }
#endif
    if (session->callbackGlobalRef) {
        env->DeleteGlobalRef(session->callbackGlobalRef);
        session->callbackGlobalRef = nullptr;
    }
    if (session->callbackIfaceClass) {
        env->DeleteGlobalRef(session->callbackIfaceClass);
        session->callbackIfaceClass = nullptr;
    }
    if (session->frameArray) {
        env->DeleteGlobalRef(session->frameArray);
        session->frameArray = nullptr;
        session->frameArrayCap = 0;
    }
    delete session;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeStartStream(
        JNIEnv *env, jobject, jlong sessionHandle,
        jint width, jint height, jint fps, jobject callback) {
    auto *session = reinterpret_cast<UvcSession *>(sessionHandle);
    if (!session || !callback) return -1;

    // 保存回调全局引用
    session->callbackGlobalRef = env->NewGlobalRef(callback);
    if (!session->callbackGlobalRef) return -2;

    // 在 app 线程（持有正确 ClassLoader）解析 FrameCallback 接口类与 onFrame，
    // 缓存为全局引用，供 native 回调线程使用（native 线程 FindClass 会失败）。
    jclass ifaceClass = env->FindClass(
        "com/wheeltech/blindcam/native/NativeUvc$FrameCallback");
    if (ifaceClass) {
        session->callbackIfaceClass = (jclass)env->NewGlobalRef(ifaceClass);
        session->onFrameMid = env->GetMethodID(
            ifaceClass, "onFrame", "(I[BIII)V");
        env->DeleteLocalRef(ifaceClass);
    }
    if (!session->callbackIfaceClass || !session->onFrameMid) {
        native_diag("resolve callback iface failed cls=%p mid=%p",
                    session->callbackIfaceClass, session->onFrameMid);
        return -2;
    }

#if UVC_HAVE_LIBUVC
    // 请求 MJPEG 格式；libuvc 会在协商失败时返回错误（后续可做格式回退枚举）
    uvc_error_t err = uvc_get_stream_ctrl_format_size(
            session->devh, &session->streamCtrl,
            UVC_FRAME_FORMAT_MJPEG,
            width, height, fps);
    if (err != UVC_SUCCESS) {
        LOGE("get_stream_ctrl(MJPEG %dx%d@%d) failed: %d",
             width, height, fps, err);
        return -3;
    }

    err = uvc_start_streaming(
            session->devh, &session->streamCtrl,
            &onUvcFrame, session, 0);
    if (err != UVC_SUCCESS) {
        LOGE("uvc_start_streaming failed: %d", err);
        return -4;
    }
    session->streaming = true;
    LOGI("stream started: %dx%d@%d", width, height, fps);
    return 0;
#else
    (void) width; (void) height; (void) fps;
    return -100; // stub 桩库固定错误码
#endif
}

extern "C"
JNIEXPORT void JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeStopStream(JNIEnv *, jobject, jlong sessionHandle) {
    auto *session = reinterpret_cast<UvcSession *>(sessionHandle);
    if (!session) return;
#if UVC_HAVE_LIBUVC
    if (session->streaming && session->devh) {
        uvc_stop_streaming(session->devh);
        session->streaming = false;
        LOGI("stream stopped, cameraId=%d", session->cameraId);
    }
#endif
}

// 返回 libuvc 枚举 UVC_FRAME_FORMAT_MJPEG 的数值，供 Kotlin 侧比较
extern "C"
JNIEXPORT jint JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeGetMjpegFormat(JNIEnv *, jclass) {
#if UVC_HAVE_LIBUVC
    return static_cast<jint>(UVC_FRAME_FORMAT_MJPEG);
#else
    return -1;
#endif
}

#if UVC_HAVE_LIBUVC
// 原始 UVC 描述符 dump：当 libuvc 解析出的 format_descs 为空时，直接遍历
// libusb 配置描述符的 extra 字节，打印每个 VideoStreaming 描述符的 subtype，
// 用于判断设备是否只有非 MJPEG 格式或描述符布局不规范。
static void dumpRawUvcDescriptors(uvc_device_handle_t *devh) {
    libusb_device_handle *h = uvc_get_libusb_handle(devh);
    if (!h) return;
    libusb_device *ldev = libusb_get_device(h);
    if (!ldev) return;
    struct libusb_config_descriptor *cfg = nullptr;
    if (libusb_get_active_config_descriptor(ldev, &cfg) != 0 || !cfg) return;
    native_diag("rawDesc nIf=%u", cfg->bNumInterfaces);
    // 直接通过控制传输读取完整 config 描述符原始字节，避免 libusb 拆分 extra 的歧义
    {
        unsigned char hdr[9];
        int got = libusb_control_transfer(
            h, 0x80, 0x06, (uint16_t)(0x0200 | cfg->bConfigurationValue), 0,
            hdr, 9, 1000);
        if (got == 9) {
            int totalLen = hdr[2] | (hdr[3] << 8);
            std::vector<unsigned char> raw(totalLen);
            int got2 = libusb_control_transfer(
                h, 0x80, 0x06, (uint16_t)(0x0200 | cfg->bConfigurationValue), 0,
                raw.data(), (uint16_t)totalLen, 2000);
            if (got2 > 0) {
                std::string all;
                char hb[8];
                for (int k = 0; k < got2; ++k) {
                    snprintf(hb, sizeof(hb), "%02X ", raw[k]);
                    all += hb;
                }
                native_diag("rawCfg total=%d [%s]", got2, all.c_str());
            }
        }
    }
    for (uint8_t i = 0; i < cfg->bNumInterfaces; ++i) {
        const struct libusb_interface *itf = &cfg->interface[i];
        for (int a = 0; a < itf->num_altsetting; ++a) {
            const struct libusb_interface_descriptor *alt = &itf->altsetting[a];
            if (a == 0)
                native_diag("rawDesc if=%d class=0x%02X sub=0x%02X nAlt=%d nEp=%d alt0extra=%d",
                            alt->bInterfaceNumber, alt->bInterfaceClass,
                            alt->bInterfaceSubClass,
                            itf->num_altsetting, alt->bNumEndpoints,
                            itf->altsetting[0].extra_length);
            // 每个 alt 的端点信息（地址/属性/最大包长/extra），定位非标准设备的数据流端点
            for (int e = 0; e < alt->bNumEndpoints; ++e) {
                const struct libusb_endpoint_descriptor *ep = &alt->endpoint[e];
                native_diag("rawEp if=%d alt=%d ep=0x%02X attr=0x%02X maxPkt=%u epExtra=%d",
                            alt->bInterfaceNumber, alt->bAlternateSetting,
                            ep->bEndpointAddress, ep->bmAttributes,
                            ep->wMaxPacketSize, ep->extra_length);
            }
            const unsigned char *p = alt->extra;
            int total = alt->extra_length;
            // 完整连续 dump（仅 alt=0），便于分析非标准描述符
            if (a == 0 && total > 0) {
                std::string full;
                char hb[8];
                for (int k = 0; k < total; ++k) {
                    snprintf(hb, sizeof(hb), "%02X ", p[k]);
                    full += hb;
                }
                native_diag("fullExtra if=%d len=%d [%s]",
                            alt->bInterfaceNumber, total, full.c_str());
            }
            int off = 0;
            while (p && off + 2 < total) {
                int len = p[off];
                int type = p[off+1];
                if (len <= 0 || off + len > total) break;
                // 0x24=VideoStreaming class-specific descriptor；第二字节是 subtype
                if (type == 0x24 && off + 2 < total) {
                    int sub = p[off+2];
                    const char *name = "VS_other";
                    if (sub==0x01) name="INPUT_HEADER";
                    else if (sub==0x03) name="STILL";
                    else if (sub==0x04) name="FORMAT_UNCOMPRESSED";
                    else if (sub==0x05) name="FRAME_UNCOMPRESSED";
                    else if (sub==0x06) name="FORMAT_MJPEG";
                    else if (sub==0x07) name="FRAME_MJPEG";
                    else if (sub==0x0d) name="COLORFORMAT";
                    std::string hex;
                    int dumpLen = len < 38 ? len : 38;
                    char hb[8];
                    for (int k = 0; k < dumpLen; ++k) {
                        snprintf(hb, sizeof(hb), "%02X ", p[off+k]);
                        hex += hb;
                    }
                    native_diag("rawDesc if=%d alt=%d sub=0x%02X(%s) len=%d [%s]",
                                alt->bInterfaceNumber, alt->bAlternateSetting, sub, name,
                                len, hex.c_str());
                }
                off += len;
            }
        }
    }
    libusb_free_config_descriptor(cfg);
}

// 把单个 MJPEG format 下的所有 frame 及帧率编码为 "WxH:fps,fps;WxH:fps"
static std::string encodeMjpegFormat(uvc_format_desc_t *fmt) {
    std::string out;
    bool firstFrame = true;
    for (uvc_frame_desc_t *frame = fmt->frame_descs; frame; frame = frame->next) {
        if (!firstFrame) out += ";";
        firstFrame = false;
        out += std::to_string(frame->wWidth) + "x" + std::to_string(frame->wHeight) + ":";

        // 原始 interval 诊断：确认设备描述符真实报告的间隔
        {
            std::string raw;
            if (frame->intervals) {
                for (int i = 0; frame->intervals[i] != 0 && i < 12; ++i) {
                    raw += std::to_string(frame->intervals[i]) + ",";
                }
            }
            native_diag("frameDesc %dx%d defaultIv=%u minIv=%u maxIv=%u step=%u raw=[%s]",
                        frame->wWidth, frame->wHeight,
                        frame->dwDefaultFrameInterval,
                        frame->dwMinFrameInterval,
                        frame->dwMaxFrameInterval,
                        frame->dwFrameIntervalStep, raw.c_str());
        }

        // intervals 为 100ns 单位、0 终止；去重并按帧率降序
        std::vector<int> fpsList;
        if (frame->intervals) {
            for (int i = 0; frame->intervals[i] != 0; ++i) {
                uint32_t iv = frame->intervals[i];
                if (iv == 0) continue;
                int fps = static_cast<int>(10000000u / iv);
                if (fps > 0 && std::find(fpsList.begin(), fpsList.end(), fps) == fpsList.end())
                    fpsList.push_back(fps);
            }
        }
        // 兜底：没有离散 intervals 时用默认 interval
        if (fpsList.empty() && frame->dwDefaultFrameInterval > 0)
            fpsList.push_back(static_cast<int>(10000000u / frame->dwDefaultFrameInterval));
        std::sort(fpsList.begin(), fpsList.end(), std::greater<int>());
        for (size_t i = 0; i < fpsList.size(); ++i) {
            if (i) out += ",";
            out += std::to_string(fpsList[i]);
        }
    }
    return out;
}
#endif

// 更新会话的 cameraId（设备在槽位间重新映射时调用，保证帧回调命中新槽位）
extern "C"
JNIEXPORT void JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeSetCameraId(
        JNIEnv *, jobject, jlong sessionHandle, jint cameraId) {
#if UVC_HAVE_LIBUVC
    auto *session = reinterpret_cast<UvcSession *>(sessionHandle);
    if (session) session->cameraId = cameraId;
#else
    (void) sessionHandle; (void) cameraId;
#endif
}

// 查询摄像头支持的 MJPEG 分辨率与帧率。
// 返回编码字符串："MJPEG;WxH:fps,fps;WxH:fps"，无能力返回空串。
extern "C"
JNIEXPORT jstring JNICALL
Java_com_wheeltech_blindcam_native_NativeUvc_nativeQueryCapabilities(
        JNIEnv *env, jobject, jlong sessionHandle) {
#if UVC_HAVE_LIBUVC
    auto *session = reinterpret_cast<UvcSession *>(sessionHandle);
    if (!session || !session->devh || !session->devh->info)
        return env->NewStringUTF("");

    std::string result;
    uvc_device_info_t *info = session->devh->info;
    int sifCount = 0, fmtCount = 0, mjpegCount = 0;
    if (info) {
        for (uvc_streaming_interface_t *sif = info->stream_ifs; sif; sif = sif->next) {
            sifCount++;
            for (uvc_format_desc_t *fmt = sif->format_descs; fmt; fmt = fmt->next) {
                fmtCount++;
                if (fmt->bDescriptorSubtype != UVC_VS_FORMAT_MJPEG) continue;
                mjpegCount++;
                std::string encoded = encodeMjpegFormat(fmt);
                if (encoded.empty()) continue;
                if (!result.empty()) result += "|";
                result += "MJPEG;" + encoded;
            }
        }
    }
    native_diag("queryCapabilities: info=%p sif=%d fmt=%d mjpeg=%d result=[%s]",
                (void*)info, sifCount, fmtCount, mjpegCount, result.c_str());
    if (fmtCount == 0)
        dumpRawUvcDescriptors(session->devh);
    return env->NewStringUTF(result.c_str());
#else
    (void) sessionHandle;
    return env->NewStringUTF("");
#endif
}
