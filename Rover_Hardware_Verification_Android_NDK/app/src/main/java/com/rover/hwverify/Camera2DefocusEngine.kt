@SuppressLint("MissingPermission")
    fun startCamera(previewSurface: Surface, onReady: () -> Unit) {
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList[0]

        // Portrait resolution (Width=240, Height=320)
        imageReader = ImageReader.newInstance(240, 320, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image: Image? = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val yPlane = image.planes[0]
                    onDirectFrameAvailable?.invoke(yPlane.buffer, yPlane.rowStride, image.timestamp)
                } finally {
                    image.close()
                }
            }
        }, backgroundHandler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera

                val hardwareProcessingSurface = imageReader!!.surface
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

                // DUAL TARGET SURFACE SHARING (Zero-Copy Hardware Split):
                // Target 1: Feeds directly to the phone's screen
                builder.addTarget(previewSurface)
                // Target 2: Feeds directly to C++ NDK 1D Otsu / blob detector
                builder.addTarget(hardwareProcessingSurface)

                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 1.5f)

                camera.createCaptureSession(
                    listOf(previewSurface, hardwareProcessingSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            onReady()
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    },
                    backgroundHandler
                )
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {}
        }, backgroundHandler)
    }
