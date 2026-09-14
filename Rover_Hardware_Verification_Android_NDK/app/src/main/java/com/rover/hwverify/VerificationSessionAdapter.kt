package com.rover.hwverify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.nio.ByteBuffer

class VerificationSessionAdapter(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "RoverAdapter"
        // Native Portrait Dimensions
        const val WIDTH = 240
        const val HEIGHT = 320
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraEngine = Camera2DefocusEngine(context)

    // Scanline configuration for Portrait mode (Height = 320)
    var farScanlineY: Int = 90
    var nearScanlineY: Int = 280
    var transientVyThreshold: Float = 3.0f
    var blobWidthMin: Int = 2
    var blobWidthMax: Int = 35
    var minGauge: Int = 40
    var maxGauge: Int = 220

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
     * Starts dual hardware surfaces:
     * 1. Direct on-screen preview SurfaceView (Visible Camera Feed)
     * 2. Zero-copy ImageReader DMA buffer (C++ NDK 1D Otsu processing)
     */
    fun startHardware(previewSurface: Surface) {
        if (isStarted) return
        isStarted = true

        cameraEngine.onDirectFrameAvailable = { directBuffer, rowStride, timestampNs ->
            onHardwareFrame(directBuffer, rowStride, timestampNs)
        }

        cameraEngine.startCamera(previewSurface) {
            mainHandler.post {
                webView.evaluateJavascript(
                    "if (document.getElementById('badge')) { document.getElementById('badge').style.background = '#064e3b'; document.getElementById('badge').style.color = '#34d399'; document.getElementById('badge').innerText = 'CAMERA LIVE (PORTRAIT)'; }",
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
