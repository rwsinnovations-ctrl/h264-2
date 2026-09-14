package com.rover.hwverify

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_REQUEST_CAMERA = 101
    }

    private lateinit var cameraSurfaceView: SurfaceView
    private lateinit var webView: WebView
    private var adapter: VerificationSessionAdapter? = null
    private var cachedSurface: Surface? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraSurfaceView = findViewById(R.id.cameraSurfaceView)
        webView = findViewById(R.id.webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        adapter = VerificationSessionAdapter(this, webView)
        webView.addJavascriptInterface(adapter!!, "RoverBridge")
        webView.loadUrl("file:///android_asset/index.html")

        cameraSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                cachedSurface = holder.surface
                checkAndStartCamera()
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}

            override fun surfaceDestroyed(h: SurfaceHolder) {
                cachedSurface = null
                adapter?.stop()
            }
        })
    }

    private fun checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cachedSurface?.let { surface ->
                adapter?.startHardware(surface)
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CAMERA && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkAndStartCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter?.stop()
        adapter = null
    }
}
