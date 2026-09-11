package com.rover.hwverify

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class H264EncoderPipeline(
    private val outputDir: File,
    private val onFrameExtracted: (frameIndex: Int, isIFrame: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "H264EncoderPipeline"
        const val WIDTH = 320
        const val HEIGHT = 240
        const val FRAME_RATE = 30
        const val BIT_RATE = 1_200_000 // 1.2 Mbps
    }

    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set
    private var isRecording = false
    private var frameCounter = 0

    init {
        // Initialize Native FFmpeg C++ motion vector decoder
        System.loadLibrary("rover_vector_native")
        nativeInitDecoder(WIDTH, HEIGHT, outputDir.absolutePath)
    }

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)

            // CRITICAL HARDWARE SPECS:
            // 1. Force consistent I-frame placement every 1 second
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            // 2. Explicitly ban bi-directional frame buffering completely
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)

            // Real-time zero-latency rate control
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 0)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec?.createInputSurface()
        codec?.start()
        isRecording = true

        Thread { drainEncoderLoop() }.start()
    }

    private fun drainEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRecording) {
            val encoder = codec ?: break
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex >= 0) {
                val encodedData: ByteBuffer? = encoder.getOutputBuffer(outIndex)
                if (encodedData != null && bufferInfo.size > 0) {
                    val isIFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val packetBytes = ByteArray(bufferInfo.size)
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    encodedData.get(packetBytes)

                    frameCounter++
                    // Route packet through Native C++ FFmpeg side data extractor
                    nativeFeedH264Packet(packetBytes, packetBytes.size, frameCounter, isIFrame)
                    onFrameExtracted(frameCounter, isIFrame)
                }
                encoder.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    fun stop() {
        isRecording = false
        codec?.stop()
        codec?.release()
        nativeCloseDecoder()
    }

    private external fun nativeInitDecoder(width: Int, height: Int, outDir: String)
    private external fun nativeFeedH264Packet(data: ByteArray, size: Int, frameIndex: Int, isIFrame: Boolean)
    private external fun nativeCloseDecoder()
}
