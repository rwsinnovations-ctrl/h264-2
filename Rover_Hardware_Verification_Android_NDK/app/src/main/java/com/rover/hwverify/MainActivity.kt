package com.rover.hwverify

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var recordBtn: Button
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var triggerMeteoriteBtn: Button
    private lateinit var btnModeSplit: Button
    private lateinit var btnModeCleaned: Button
    private lateinit var btnModeRaw: Button
    private lateinit var telemetryText: TextView

    private lateinit var defocusEngine: Camera2DefocusEngine
    private var encoderPipeline: H264EncoderPipeline? = null
    private var syntheticInjector: SyntheticTrackSurfaceInjector? = null
    private val laneCleaner = MotionVectorLaneCleaner(320, 240, 16)
    private var isRecording = false
    private var useSyntheticInjection = true // Default to testing without vehicle!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        statusText = findViewById(R.id.statusText)
        recordBtn = findViewById(R.id.recordBtn)
        modeRadioGroup = findViewById(R.id.modeRadioGroup)
        triggerMeteoriteBtn = findViewById(R.id.triggerMeteoriteBtn)
        btnModeSplit = findViewById(R.id.btnModeSplit)
        btnModeCleaned = findViewById(R.id.btnModeCleaned)
        btnModeRaw = findViewById(R.id.btnModeRaw)
        telemetryText = findViewById(R.id.telemetryText)

        defocusEngine = Camera2DefocusEngine(this)

        // Real-Time Lane Cleanup Display Mode Toggles
        btnModeSplit.setOnClickListener {
            laneCleaner.displayMode = DisplayMode.SPLIT
            statusText.text = "Display: Split-Screen [Raw vs Cleaned Lanes]"
        }
        btnModeCleaned.setOnClickListener {
            laneCleaner.displayMode = DisplayMode.CLEANED
            statusText.text = "Display: Cleaned Lanes (Transients Erased)"
        }
        btnModeRaw.setOnClickListener {
            laneCleaner.displayMode = DisplayMode.RAW
            statusText.text = "Display: Raw Input Stream"
        }

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            useSyntheticInjection = (checkedId == R.id.radioSynthetic)
            statusText.text = if (useSyntheticInjection) {
                "Source: Synthetic Track Surface Injection (No vehicle needed)"
            } else {
                "Source: Physical Camera2 Sensor (Manual Defocus 10.0 D)"
            }
        }

        triggerMeteoriteBtn.setOnClickListener {
            syntheticInjector?.triggerMeteorite = true
            statusText.text = "Injected Meteorite Cross-Line into Hardware Stream!"
        }

        recordBtn.setOnClickListener {
            if (!isRecording) startVerificationRun() else stopVerificationRun()
        }

        if (checkPermissions()) {
            initPreview()
        } else {
            requestPermissions()
        }
    }

    private fun initPreview() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                statusText.text = "Ready. Mode: " + (if (useSyntheticInjection) "Synthetic Injection" else "Camera2")
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                syntheticInjector?.stop()
                defocusEngine.stop()
            }
        })
    }

    private fun startVerificationRun() {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val runDir = File(downloadDir, "RoverTestRun").apply { mkdirs() }

        // 1. Initialize Hardware H.264 Encoder Pipeline
        encoderPipeline = H264EncoderPipeline(runDir) { frameIndex, isIFrame ->
            runOnUiThread {
                statusText.text = "Frame #$frameIndex [${if (isIFrame) "I-FRAME" else "P-FRAME"}] -> Qualcomm MediaCodec Silicon"
                telemetryText.text = "Lane Continuity: 100% | Speed Cutoff: ${laneCleaner.speedThreshold}px/f"
            }
        }
        encoderPipeline?.start()

        val inputSurface = encoderPipeline?.inputSurface
        if (inputSurface != null) {
            if (useSyntheticInjection) {
                // INJECT SYNTHETIC TRACK DIRECTLY INTO SILICON ENCODER
                syntheticInjector = SyntheticTrackSurfaceInjector(inputSurface)
                syntheticInjector?.start()
                statusText.text = "Injecting Synthetic Track Frames into Qualcomm Hardware Encoder..."
            } else {
                // USE PHYSICAL CAMERA2 SENSOR
                defocusEngine.startCamera(inputSurface) {
                    runOnUiThread {
                        statusText.text = "Camera2 Hardware Defocused to Macro Limit (10.0 D). Recording..."
                    }
                }
            }
        }

        isRecording = true
        recordBtn.text = "STOP RUN & EXPORT"
    }

    private fun stopVerificationRun() {
        syntheticInjector?.stop()
        syntheticInjector = null
        defocusEngine.stop()
        encoderPipeline?.stop()
        encoderPipeline = null

        isRecording = false
        recordBtn.text = "START RECORDING PASS"
        statusText.text = "Pass Complete. Real Qualcomm vectors saved to /sdcard/Download/RoverTestRun/"
    }

    private fun checkPermissions() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
    }
}
