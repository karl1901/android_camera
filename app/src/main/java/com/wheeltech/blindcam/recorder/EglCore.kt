package com.wheeltech.blindcam.recorder

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * 极简 EGL14 环境：用于把 OpenGL 画面渲染到 [android.media.MediaCodec] 的输入 Surface，
 * 让 GPU 完成 Bitmap→纹理→H.264 的全硬件管线（色彩转换/编码都在 GPU/硬编，不占 CPU）。
 *
 * 每个录制器在自己的单一线程（HandlerThread）里创建并持续使用，EGL 上下文不跨线程。
 */
class EglCore {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    init {
        require(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
            "当前线程已存在 EGL 上下文"
        }
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize 失败: ${EGL14.eglGetError()}")
        }
        eglDisplay = display

        // 选择可录制（用于 MediaCodec input surface）的 ES2 配置
        val configAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        require(
            EGL14.eglChooseConfig(
                display, configAttr, 0, configs, 0, configs.size, numConfigs, 0
            )
        ) { "eglChooseConfig 失败: ${EGL14.eglGetError()}" }
        if (numConfigs[0] <= 0) {
            // 退回：不强制 recordable
            val fallback = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            EGL14.eglChooseConfig(display, fallback, 0, configs, 0, configs.size, numConfigs, 0)
        }
        eglConfig = configs[0]

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(
            display, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttr, 0
        )
        checkEGLError("eglCreateContext")
        eglContext = context
    }

    /** 在编码器输入 Surface 上创建一个窗口 Surface */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val surfaceAttr = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, surfaceAttr, 0
        )
        checkEGLError("eglCreateWindowSurface")
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("创建 EGLSurface 失败")
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent 失败: ${EGL14.eglGetError()}")
        }
    }

    /** 给当前帧设置呈现时间（纳秒），决定 MP4 的真实时间戳 */
    fun setPresentationTime(eglSurface: EGLSurface, timestampNs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean =
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    fun destroySurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    private fun checkEGLError(msg: String) {
        val err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS) throw RuntimeException("$msg 失败: 0x${Integer.toHexString(err)}")
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
