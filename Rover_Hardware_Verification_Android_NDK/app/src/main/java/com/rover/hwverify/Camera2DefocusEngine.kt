package com.rover.hwverify

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Surface

class Camera2DefocusEngine(context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null

    @SuppressLint("MissingPermission")
    fun startCamera(targetSurface: Surface, onReady: () -> Unit) {
        val cameraId = cameraManager.cameraIdList[0]
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(targetSurface)

                // 1. Edge Enhancement for sharp gradients
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)

                // 2. Hardware Color Correction Matrix (Red Track Isolation)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(arrayOf(
                    Rational(3, 1), Rational(0, 1), Rational(0, 1),
                    Rational(0, 1), Rational(1, 4), Rational(0, 1),
                    Rational(0, 1), Rational(0, 1), Rational(1, 4)
                )))
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(2.5f, 0.8f, 0.8f, 2.5f))

                // 3. Freeze Tonemap
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)

                captureRequestBuilder = builder

                camera.createCaptureSession(listOf(targetSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(builder.build(), null, null)
                        onReady()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, Handler(Looper.getMainLooper()))
            }

            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {}
        }, Handler(Looper.getMainLooper()))
    }

    fun setMidpointFocus(diopters: Float) {
        val session = captureSession ?: return
        val builder = captureRequestBuilder ?: return
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        session.setRepeatingRequest(builder.build(), null, null)
    }

    fun stop() {
        captureSession?.close()
        cameraDevice?.close()
    }
}
