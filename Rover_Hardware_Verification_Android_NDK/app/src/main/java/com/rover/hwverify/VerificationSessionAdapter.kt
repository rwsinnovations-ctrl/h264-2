package com.rover.hwverify

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File

class VerificationSessionAdapter(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val defocusEngine = Camera2DefocusEngine(context)
    private var encoderPipeline: H264EncoderPipeline? = null

    // Configurables (writable by JavaScript)
    var farScanlineY: Int = 60
    var nearScanlineY: Int = 210
    var transientVyThreshold: Float = 3.0f
    var blobWidthMin: Int = 4
    var blobWidthMax: Int = 24
    var minGauge: Int = 80
    var maxGauge: Int = 260

    private val lumaBuffer = ByteArray(WIDTH * HEIGHT)

    init {
        System.loadLibrary("rover_vector_native")
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
            defocusEngine.setMidpointFocus(focusMidpointDiopters)
        }
        return true
    }

    fun onFrameCaptured(bitmap: Bitmap, packetBytes: ByteArray?, isIFrame: Boolean) {
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

        // Convert RGB to Y/Luma plane
        for (i in 0 until (WIDTH * HEIGHT)) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lumaBuffer[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }

        val jsonResult = nativeProcessPipeline(
            lumaBuffer, WIDTH, HEIGHT,
            packetBytes, packetBytes?.size ?: 0, isIFrame,
            farScanlineY, nearScanlineY, transientVyThreshold,
            blobWidthMin, blobWidthMax, minGauge, maxGauge
        )

        mainHandler.post {
            webView.evaluateJavascript("if (window.onPipelineResult) { window.onPipelineResult($jsonResult); }", null)
        }
    }

    private external fun nativeProcessPipeline(
        luma: ByteArray, width: Int, height: Int,
        packet: ByteArray?, packetSize: Int, isIFrame: Boolean,
        farY: Int, nearY: Int, vyThreshold: Float,
        wMin: Int, wMax: Int, minG: Int, maxG: Int
    ): String
}
