package com.rover.hwverify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.nio.ByteBuffer

class VerificationSessionAdapter(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "RoverAdapter"
        const val WIDTH = 320
        const val HEIGHT = 240
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraEngine = Camera2DefocusEngine(context)

    var farScanlineY: Int = 60
    var nearScanlineY: Int = 210
    var transientVyThreshold: Float = 3.0f
    var blobWidthMin: Int = 4
    var blobWidthMax: Int = 24
    var minGauge: Int = 80
    var maxGauge: Int = 260

    private var frameCounter = 0
    private var isStarted = false

    init {
        try {
            System.loadLibrary("rover_vector_native")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }

    /**
     * Called ONLY after Camera permission is verified.
     */
    fun startHardware() {
        if (isStarted) return
        isStarted = true

        cameraEngine.onDirectFrameAvailable = { directBuffer, rowStride, timestampNs ->
            onHardwareFrame(directBuffer, rowStride, timestampNs)
        }

        cameraEngine.startCamera {
            mainHandler.post {
                webView.evaluateJavascript(
                    "if (document.getElementById('status-badge')) { document.getElementById('status-badge').className = 'status-badge live'; document.getElementById('status-badge').innerText = 'CAMERA HARDWARE ACTIVE'; }",
                    null
                )
            }
        }
    }

    private fun onHardwareFrame(directBuffer: ByteBuffer, rowStride: Int, timestampNs: Long) {
        frameCounter++
        val isIFrame = (frameCounter % 30 == 0)

        val jsonResult = nativeProcessDirectScanlines(
            directBuffer, WIDTH, HEIGHT, rowStride,
            null, 0, isIFrame,
            farScanlineY, nearScanlineY, transientVyThreshold,
            blobWidthMin, blobWidthMax, minGauge, maxGauge
        )

        mainHandler.post {
            webView.evaluateJavascript(
                "if (window.onPipelineResult) { window.onPipelineResult($jsonResult); }",
                null
            )
        }
    }

    @JavascriptInterface
    fun configurePipeline(
        farY: Int,
        nearY: Int,
        focusMidpointDiopters: Float,
        vyThreshold: Float,
        wMin: Int,
        wMax: Int,
        gMin: Int,
        gMax: Int
    ): Boolean {
        farScanlineY = farY
        nearScanlineY = nearY
        transientVyThreshold = vyThreshold
        blobWidthMin = wMin
        blobWidthMax = wMax
        minGauge = gMin
        maxGauge = gMax

        mainHandler.post {
            cameraEngine.setMidpointFocus(focusMidpointDiopters)
        }
        return true
    }

    fun stop() {
        isStarted = false
        cameraEngine.stop()
    }

    private external fun nativeProcessDirectScanlines(
        directLumaBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int,
        packet: ByteArray?, packetSize: Int, isIFrame: Boolean,
        farY: Int, nearY: Int, vyThreshold: Float,
        wMin: Int, wMax: Int, minG: Int, maxG: Int
    ): String
}
