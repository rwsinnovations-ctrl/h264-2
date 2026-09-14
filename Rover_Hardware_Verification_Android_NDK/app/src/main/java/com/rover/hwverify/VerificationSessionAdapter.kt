package com.rover.hwverify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.nio.ByteBuffer

class VerificationSessionAdapter(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraEngine = Camera2DefocusEngine(context)

    // Configurables (writable by JavaScript)
    var farScanlineY: Int = 60
    var nearScanlineY: Int = 210
    var transientVyThreshold: Float = 3.0f
    var blobWidthMin: Int = 4
    var blobWidthMax: Int = 24
    var minGauge: Int = 80
    var maxGauge: Int = 260

    private var frameCounter = 0

    init {
        System.loadLibrary("rover_vector_native")

        // Hook up zero-copy hardware listener
        cameraEngine.onDirectFrameAvailable = { directBuffer, rowStride, timestampNs ->
            onHardwareFrame(directBuffer, rowStride, timestampNs)
        }

        // Start hardware camera sensor immediately
        cameraEngine.startCamera {
            mainHandler.post {
                webView.evaluateJavascript(
                    "if (document.getElementById('status-badge')) { document.getElementById('status-badge').innerText = 'CAMERA HARDWARE ACTIVE'; }",
                    null
                )
            }
        }
    }

    /**
     * Executes natively per hardware frame.
     * directBuffer is a physical DMA memory pointer. 0 CPU copies.
     */
    private fun onHardwareFrame(directBuffer: ByteBuffer, rowStride: Int, timestampNs: Long) {
        frameCounter++
        val isIFrame = (frameCounter % 30 == 0) // Stage 1 check

        // Pass direct memory address straight to C++ NDK
        val jsonResult = nativeProcessDirectScanlines(
            directBuffer, WIDTH, HEIGHT, rowStride,
            null, 0, isIFrame,
            farScanlineY, nearScanlineY, transientVyThreshold,
            blobWidthMin, blobWidthMax, minGauge, maxGauge
        )

        // Post JSON result to Web UI
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
        cameraEngine.stop()
    }

    // Direct memory JNI signature
    private external fun nativeProcessDirectScanlines(
        directLumaBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int,
        packet: ByteArray?, packetSize: Int, isIFrame: Boolean,
        farY: Int, nearY: Int, vyThreshold: Float,
        wMin: Int, wMax: Int, minG: Int, maxG: Int
    ): String
}
