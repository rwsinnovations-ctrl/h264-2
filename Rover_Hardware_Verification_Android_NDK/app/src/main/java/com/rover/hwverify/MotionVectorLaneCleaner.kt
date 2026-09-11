package com.rover.hwverify

import android.graphics.*
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.sqrt

enum class DisplayMode {
    SPLIT,      // Split Screen: Raw input on left, Cleaned on right
    CLEANED,    // Cleaned output with transient marks inpainted
    RAW,        // Raw input stream (Camera or Synthetic)
    MASK        // Motion vector classification mask (Green = Lane, Red = Transient)
}

data class CleanupReport(
    val preservedCount: Int,
    val suppressedCount: Int,
    val avgLaneVx: Float,
    val maxTransientVy: Float
)

class MotionVectorLaneCleaner(
    private val width: Int = 320,
    private val height: Int = 240,
    private val mbSize: Int = 16
) {
    var speedThreshold: Float = 3.0f
    var displayMode: DisplayMode = DisplayMode.SPLIT
    var splitPosition: Float = 0.5f
    var highlightPreservedLanes: Boolean = true

    private val cols = width / mbSize
    private val rows = height / mbSize

    // High performance paints
    private val transientMaskPaint = Paint().apply {
        color = Color.argb(120, 239, 68, 68)
        style = Paint.Style.FILL
    }
    private val preservedLanePaint = Paint().apply {
        color = Color.argb(120, 34, 197, 94)
        style = Paint.Style.FILL
    }
    private val trackInpaintPaint = Paint().apply {
        color = Color.rgb(158, 53, 43) // Red running track inpaint
        style = Paint.Style.FILL
    }
    private val splitLinePaint = Paint().apply {
        color = Color.rgb(56, 189, 248)
        strokeWidth = 3f
    }
    private val hudTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 12f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    private val laneDotPaint = Paint().apply {
        color = Color.rgb(34, 197, 94)
        style = Paint.Style.FILL
    }

    /**
     * Clean frame and render directly to on-screen SurfaceView in real time
     */
    fun cleanAndRender(
        rawBitmap: Bitmap,
        macroblockVectors: List<FloatArray>, // each item: [mbX, mbY, motionX, motionY, variance]
        holder: SurfaceHolder
    ): CleanupReport {
        val canvas = holder.lockCanvas() ?: return CleanupReport(0, 0, 0f, 0f)
        var preservedCount = 0
        var suppressedCount = 0
        var laneVxSum = 0f
        var maxTransientVy = 0f

        try {
            // Allocate cleaned mutable bitmap copy
            val cleanedBitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val cleanedCanvas = Canvas(cleanedBitmap)

            // Scale to display surface dimensions
            val destRect = Rect(0, 0, canvas.width, canvas.height)
            val scaleX = canvas.width.toFloat() / width.toFloat()
            val scaleY = canvas.height.toFloat() / height.toFloat()

            // 1. Process each macroblock vector
            for (mb in macroblockVectors) {
                if (mb.size < 4) continue
                val mbX = mb[0].toInt()
                val mbY = mb[1].toInt()
                val vx = mb[2]
                val vy = mb[3]
                val variance = if (mb.size >= 5) mb[4] else 50f
                val speed = sqrt(vx * vx + vy * vy)

                // Criteria: Erase anything with a fast vertical component (|Vy| >= speedThreshold) and leave everything else
                val isTransient = abs(vy) >= speedThreshold
                val isLane = !isTransient

                val left = mbX * mbSize.toFloat()
                val top = mbY * mbSize.toFloat()
                val right = left + mbSize.toFloat()
                val bottom = top + mbSize.toFloat()

                if (isTransient) {
                    suppressedCount++
                    if (abs(vy) > maxTransientVy) maxTransientVy = abs(vy)
                    // Erase fast vertical transient from cleaned bitmap by inpainting track surface
                    cleanedCanvas.drawRect(left, top, right, bottom, trackInpaintPaint)
                } else if (isLane) {
                    preservedCount++
                    laneVxSum += vx
                    if (highlightPreservedLanes) {
                        cleanedCanvas.drawCircle(left + mbSize / 2f, top + mbSize / 2f, 3f, laneDotPaint)
                    }
                }
            }

            // 2. Render to phone screen based on selected DisplayMode
            when (displayMode) {
                DisplayMode.RAW -> {
                    canvas.drawBitmap(rawBitmap, null, destRect, null)
                }
                DisplayMode.CLEANED -> {
                    canvas.drawBitmap(cleanedBitmap, null, destRect, null)
                }
                DisplayMode.SPLIT -> {
                    val splitPx = (canvas.width * splitPosition).toInt()
                    // Left half: Raw camera/synthetic input
                    val rawSrcRect = Rect(0, 0, (width * splitPosition).toInt(), height)
                    val rawDstRect = Rect(0, 0, splitPx, canvas.height)
                    canvas.drawBitmap(rawBitmap, rawSrcRect, rawDstRect, null)

                    // Right half: Real-time cleaned lanes
                    val cleanSrcRect = Rect((width * splitPosition).toInt(), 0, width, height)
                    val cleanDstRect = Rect(splitPx, 0, canvas.width, canvas.height)
                    canvas.drawBitmap(cleanedBitmap, cleanSrcRect, cleanDstRect, null)

                    // Dividing divider line
                    canvas.drawLine(splitPx.toFloat(), 0f, splitPx.toFloat(), canvas.height.toFloat(), splitLinePaint)
                    canvas.drawText("RAW INPUT", (splitPx - 90).toFloat(), 30f, hudTextPaint)
                    canvas.drawText("CLEANED LANES", (splitPx + 15).toFloat(), 30f, hudTextPaint)
                }
                DisplayMode.MASK -> {
                    canvas.drawBitmap(rawBitmap, null, destRect, null)
                    for (mb in macroblockVectors) {
                        val mbX = mb[0].toInt()
                        val mbY = mb[1].toInt()
                        val vx = mb[2]
                        val vy = mb[3]
                        val isTransient = abs(vy) >= speedThreshold
                        val left = mbX * mbSize * scaleX
                        val top = mbY * mbSize * scaleY
                        val right = left + mbSize * scaleX
                        val bottom = top + mbSize * scaleY

                        if (isTransient) {
                            canvas.drawRect(left, top, right, bottom, transientMaskPaint)
                        } else if (abs(vx) <= 3.5f && abs(vy) < speedThreshold) {
                            canvas.drawRect(left, top, right, bottom, preservedLanePaint)
                        }
                    }
                }
            }

            // On-screen telemetry banner
            val bannerText = "Cleaned: $suppressedCount MBs | Preserved: $preservedCount MBs | Cutoff: ${speedThreshold}px"
            canvas.drawText(bannerText, 20f, canvas.height - 20f, hudTextPaint)

        } finally {
            holder.unlockCanvasAndPost(canvas)
        }

        return CleanupReport(
            preservedCount = preservedCount,
            suppressedCount = suppressedCount,
            avgLaneVx = if (preservedCount > 0) laneVxSum / preservedCount else 0f,
            maxTransientVy = maxTransientVy
        )
    }
}
