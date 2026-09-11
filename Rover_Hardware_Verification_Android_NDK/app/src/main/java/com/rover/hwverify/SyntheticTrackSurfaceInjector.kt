package com.rover.hwverify

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.Surface
import kotlin.math.cos
import kotlin.math.sin

/**
 * SyntheticTrackSurfaceInjector:
 * Eliminates the need for a physical test vehicle or physical camera.
 * Draws synthetic rover track frames directly into MediaCodec's inputSurface,
 * exercising the phone's real Qualcomm H.264 silicon encoder with mathematically
 * controlled lateral drift and meteorite transients.
 */
class SyntheticTrackSurfaceInjector(
    private val inputSurface: Surface,
    private val width: Int = 320,
    private val height: Int = 240,
    private val fps: Int = 30
) {
    companion object {
        private const val TAG = "SyntheticTrackInjector"
    }

    @Volatile
    private var isRunning = false
    private var renderThread: Thread? = null

    // Simulation parameters
    var lateralDriftPx: Float = 1.2f
    var simulateMacroDefocus: Boolean = true
    var triggerMeteorite: Boolean = false

    // Paints
    private val trackPaint = Paint().apply { color = Color.rgb(158, 53, 43) } // Polyurethane Red Running Track
    private val granuleLight = Paint().apply { color = Color.argb(120, 215, 80, 68) }
    private val granuleDark = Paint().apply { color = Color.argb(120, 110, 25, 18) }
    private val whiteLinePaint = Paint().apply {
        color = Color.rgb(255, 255, 255)
        strokeWidth = 12f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val rightLinePaint = Paint().apply {
        color = Color.rgb(255, 255, 255)
        strokeWidth = 12f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val dashedLinePaint = Paint().apply {
        color = Color.rgb(241, 245, 249)
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val transientPaint = Paint().apply { color = Color.rgb(245, 158, 11) }

    fun start() {
        if (isRunning) return
        isRunning = true

        renderThread = Thread({
            var frameCount = 0
            var roadScrollY = 0f
            var roadCenterX = 160f
            var meteoriteY = -100f

            val frameDurationMs = (1000L / fps)

            Log.i(TAG, "Starting Synthetic Surface Injection into MediaCodec at ${width}x${height} @ ${fps} FPS.")

            while (isRunning) {
                val frameStartTime = System.currentTimeMillis()
                frameCount++

                // Update road coordinates
                roadScrollY = (roadScrollY + 4f) % 60f
                roadCenterX += lateralDriftPx * 0.7f
                if (roadCenterX < 90f) roadCenterX = 90f
                if (roadCenterX > 230f) roadCenterX = 230f

                if (triggerMeteorite) {
                    meteoriteY = 0f
                    triggerMeteorite = false
                }
                if (meteoriteY in 0f..260f) {
                    meteoriteY += 8f
                }

                // Lock the hardware MediaCodec InputSurface Canvas
                var canvas: Canvas? = null
                try {
                    canvas = inputSurface.lockCanvas(null)
                    if (canvas != null) {
                        // 1. Draw Red Polyurethane Running Track Ground
                        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), trackPaint)

                        // 2. Draw Rubber Granules (Only if macro defocus is NOT active)
                        if (!simulateMacroDefocus) {
                            for (i in 0 until 180) {
                                val gx = ((sin(i * 997.0 + frameCount * 0.05) * 0.5 + 0.5) * width).toFloat()
                                val gy = ((cos(i * 613.0 + roadScrollY * 0.1) * 0.5 + 0.5) * height).toFloat()
                                val p = if (i % 2 == 0) granuleLight else granuleDark
                                canvas.drawRect(gx, gy, gx + 2f, gy + 2f, p)
                            }
                        }

                        // 3. Draw Regulation White Track Lane Boundaries
                        val halfWidth = 70f
                        // Left white line
                        val leftPath = Path().apply {
                            moveTo(roadCenterX - halfWidth - 10f, 0f)
                            quadTo(roadCenterX - halfWidth, 120f, roadCenterX - halfWidth + 10f, 240f)
                        }
                        canvas.drawPath(leftPath, whiteLinePaint)

                        // Right white line
                        val rightPath = Path().apply {
                            moveTo(roadCenterX + halfWidth - 10f, 0f)
                            quadTo(roadCenterX + halfWidth, 120f, roadCenterX + halfWidth + 10f, 240f)
                        }
                        canvas.drawPath(rightPath, rightLinePaint)

                        // Center dashed line
                        canvas.drawLine(roadCenterX, 0f, roadCenterX, 240f, dashedLinePaint)

                        // 4. Horizontal Cross-Line (Fast Vertical Transient)
                        if (meteoriteY in 0f..250f) {
                            canvas.drawRect(0f, meteoriteY, width.toFloat(), meteoriteY + 14f, transientPaint)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error drawing synthetic frame: ${e.message}")
                } finally {
                    if (canvas != null) {
                        try {
                            inputSurface.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error posting canvas: ${e.message}")
                        }
                    }
                }

                val renderTime = System.currentTimeMillis() - frameStartTime
                val sleepTime = frameDurationMs - renderTime
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
            Log.i(TAG, "Synthetic Surface Injection stopped.")
        }, "SyntheticInjectorThread").apply { start() }
    }

    fun stop() {
        isRunning = false
        renderThread?.interrupt()
        renderThread = null
    }
}
