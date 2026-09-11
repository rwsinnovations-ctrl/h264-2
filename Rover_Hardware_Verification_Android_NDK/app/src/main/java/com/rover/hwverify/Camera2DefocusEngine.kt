package com.rover.hwverify

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.util.Log
import android.util.Size
import android.view.Surface

class Camera2DefocusEngine(private val context: Context) {
    companion object {
        private const val TAG = "Camera2DefocusEngine"
        val TARGET_RESOLUTION = Size(320, 240)
        const val TARGET_FPS = 30
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    var minHardwareFocusDistance: Float = 10.0f
        private set

    @SuppressLint("MissingPermission")
    fun startCamera(surface: Surface, onReady: () -> Unit) {
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList[0]

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        minHardwareFocusDistance = characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 10.0f

        Log.i(TAG, "Hardware Minimum Focus Distance: $minHardwareFocusDistance diopters (Macro limit)")

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createDefocusedCaptureSession(surface, onReady)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error code: $error")
            }
        }, null)
    }

    private fun createDefocusedCaptureSession(previewSurface: Surface, onReady: () -> Unit) {
        val camera = cameraDevice ?: return
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(previewSurface)

        // 1. DISABLE AUTOMATED CONTINUOUS FOCUS
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)

        // 2. SET FOCUS DISTANCE TO MAXIMUM DIOPTERS (MINIMUM DISTANCE / MACRO BLUR)
        // A camera 50cm above the ground will render asphalt as a flat uniform gray field (Vx=0, Vy=0)
        // while road markings become smooth continuous gradient edges with clean horizontal displacement.
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, minHardwareFocusDistance)

        // Lock frame rate to 30 FPS
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(TARGET_FPS, TARGET_FPS))

        camera.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                session.setRepeatingRequest(builder.build(), null, null)
                Log.i(TAG, "Defocus capture session configured successfully at 320x240 @ 30 FPS.")
                onReady()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure Camera2 capture session.")
            }
        }, null)
    }

    fun stop() {
        captureSession?.close()
        cameraDevice?.close()
    }
}
