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
import java.nio.ByteBuffer

class Camera2DefocusEngine(private val context: Context) {
    companion object {
        private const val TAG = "Camera2Engine"
        const val WIDTH = 320
        const val HEIGHT = 240
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Callback delivering direct hardware memory pointers without copying
    var onDirectFrameAvailable: ((yPlaneDirectBuffer: ByteBuffer, rowStride: Int, timestampNs: Long) -> Unit)? = null

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraHardwareThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun startCamera(onReady: () -> Unit) {
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList[0]

        // 1. Hardware-backed YUV_420_888 buffer allocation (DMA-BUF / ION shared memory)
        imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image: Image? = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val yPlane = image.planes[0]
                    // DIRECT NIO BUFFER: Points directly to physical DMA memory, 0 CPU copy
                    val directBuffer: ByteBuffer = yPlane.buffer
                    val rowStride = yPlane.rowStride
                    val timestamp = image.timestamp

                    onDirectFrameAvailable?.invoke(directBuffer, rowStride, timestamp)
                } finally {
                    image.close() // Release buffer back to hardware pool immediately
                }
            }
        }, backgroundHandler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val targetSurface = imageReader!!.surface
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(targetSurface)

                // 2. Camera2 Hardware ISP Controls
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 1.5f) // Midpoint default

                // 3. Hardware ISP Color Correction Matrix (Red Track Isolation)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(arrayOf(
                    Rational(3, 1), Rational(0, 1), Rational(0, 1),
                    Rational(0, 1), Rational(1, 4), Rational(0, 1),
                    Rational(0, 1), Rational(0, 1), Rational(1, 4)
                )))
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(2.5f, 0.8f, 0.8f, 2.5f))
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)

                captureRequestBuilder = builder

                camera.createCaptureSession(listOf(targetSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        Log.i(TAG, "Hardware Camera2 zero-copy stream configured @ 320x240.")
                        onReady()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera2 session configuration failed.")
                    }
                }, backgroundHandler)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera device error: $error")
            }
        }, backgroundHandler)
    }

    fun setMidpointFocus(diopters: Float) {
        val session = captureSession ?: return
        val builder = captureRequestBuilder ?: return
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
    }

    fun stop() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        backgroundThread?.quitSafely()
    }
}
