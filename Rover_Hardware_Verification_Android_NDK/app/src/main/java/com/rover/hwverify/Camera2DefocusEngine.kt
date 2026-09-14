package com.rover.hwverify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Rational
import android.view.Surface
import java.nio.ByteBuffer

class Camera2DefocusEngine(private val context: Context) {
    companion object {
        private const val TAG = "Camera2Engine"
        const val WIDTH = 240
        const val HEIGHT = 320
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    var onDirectFrameAvailable: ((yPlaneDirectBuffer: ByteBuffer, rowStride: Int, timestampNs: Long) -> Unit)? = null

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraHardwareThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun startCamera(previewSurface: Surface, onReady: () -> Unit) {
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) {
                Log.e(TAG, "No cameras available.")
                return
            }

            val cameraId = cameraIds.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraIds[0]

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val supportsManual = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)

            // Hardware zero-copy YUV buffer allocation (Portrait 240x320)
            imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image: Image? = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val yPlane = image.planes[0]
                        onDirectFrameAvailable?.invoke(yPlane.buffer, yPlane.rowStride, image.timestamp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accessing direct buffer: ${e.message}")
                    } finally {
                        image.close()
                    }
                }
            }, backgroundHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val processingSurface = imageReader!!.surface
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

                        // DUAL HARDWARE SURFACES:
                        // 1. Direct on-screen preview so you can see where camera points
                        builder.addTarget(previewSurface)
                        // 2. Headless zero-copy buffer to C++ NDK
                        builder.addTarget(processingSurface)

                        builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 1.5f)

                        // ISP Red Track Matrix (if supported by device)
                        if (supportsManual && hwLevel != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED && hwLevel != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                            try {
                                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                                builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(arrayOf(
                                    Rational(3, 1), Rational(0, 1), Rational(0, 1),
                                    Rational(0, 1), Rational(1, 4), Rational(0, 1),
                                    Rational(0, 1), Rational(0, 1), Rational(1, 4)
                                )))
                                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(2.5f, 0.8f, 0.8f, 2.5f))
                            } catch (e: Exception) {
                                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
                            }
                        } else {
                            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
                        }

                        builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
                        captureRequestBuilder = builder

                        camera.createCaptureSession(
                            listOf(previewSurface, processingSurface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                                    Log.i(TAG, "Camera2 dual hardware surfaces active.")
                                    onReady()
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Camera2 configuration failed.")
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error configuring camera session: ${e.message}")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera device error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Fatal camera opening error: ${e.message}")
        }
    }

    fun setMidpointFocus(diopters: Float) {
        val session = captureSession ?: return
        val builder = captureRequestBuilder ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting focus: ${e.message}")
        }
    }

    fun stop() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            backgroundThread?.quitSafely()
            backgroundThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera: ${e.message}")
        }
    }
}
