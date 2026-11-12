package com.example.launcher

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.view.Surface
import android.view.TextureView
import android.widget.TextView

/**
 * نافذة حية للكاميرا. تعرض أول كاميرا يلقاها النظام.
 *
 * ملاحظة: كاميرا الرجوع في بعض الرواديو تجي كمدخل فيديو (AV-IN) مو كاميرا Camera2،
 * وهذي تحتاج واجهة خاصة من المصنع. لو كذا، نغيّر هذا الملف بعد ما نعرف نوع المدخل.
 */
class CameraTile(
    private val context: Context,
    private val texture: TextureView,
    private val status: TextView
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val handler = Handler(context.mainLooper)

    private var surface: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    init {
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(st)
                open()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                close()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    /** يفتح الكاميرا إذا كان السطح جاهز ولم تكن مفتوحة. */
    fun open() {
        if (device != null || surface == null) return

        val id = try {
            cameraManager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        }
        if (id == null) {
            status.text = "لا توجد كاميرا"
            return
        }

        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    startPreview(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    status.text = "انقطع اتصال الكاميرا"
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    status.text = "تعذر فتح الكاميرا"
                }
            }, handler)
        } catch (e: SecurityException) {
            status.text = "امنح التطبيق صلاحية الكاميرا"
        } catch (e: Exception) {
            status.text = "تعذر فتح الكاميرا"
        }
    }

    private fun startPreview(camera: CameraDevice) {
        val target = surface ?: return
        try {
            camera.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        .apply { addTarget(target) }
                        .build()
                    s.setRepeatingRequest(request, null, handler)
                    status.text = ""
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    status.text = "تعذر بدء البث"
                }
            }, handler)
        } catch (e: Exception) {
            status.text = "تعذر بدء البث"
        }
    }

    /** يقفل الكاميرا (يستدعى عند إخفاء الشاشة لتوفير الموارد). */
    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        device?.close()
        session = null
        device = null
    }
}
