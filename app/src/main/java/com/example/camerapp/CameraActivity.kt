package com.example.camerapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class CameraActivity : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private lateinit var exposureSeekBar: SeekBar
    private lateinit var exposureText: TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraId: String? = null
    private var exposureRange: Range<Long>? = null
    private var sensitivityRange: Range<Int>? = null

    private val TAG = "CameraActivity"

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        textureView = findViewById(R.id.texture_view)
        exposureSeekBar = findViewById(R.id.seekBarExposure)
        exposureText = findViewById(R.id.txtExposure)

        exposureSeekBar.max = 1000

        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateExposureFromSeek(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopBackgroundThread: ${e.message}")
        }
    }

    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Choose the first back-facing camera we find
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) continue

                cameraId = id
                exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                break
            }

            if (cameraId == null) {
                Toast.makeText(this, "No suitable camera found", Toast.LENGTH_SHORT).show()
                return
            }

            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera: ${e.message}")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera device error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(textureView.width, textureView.height)
            val surface = Surface(texture)

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)

            // Prefer manual control if available
            try {
                previewRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                previewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            } catch (e: Exception) {
                // Some devices may not allow CONTROL_MODE_OFF; ignore and fall back
                previewRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }

            // Set an initial exposure if available
            val initialExposure: Long? = exposureRange?.let { range ->
                val mid = (range.upper + range.lower) / 2
                mid
            }

            initialExposure?.let {
                try {
                    previewRequestBuilder!!.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set SENSOR_EXPOSURE_TIME: ${e.message}")
                }
            }

            cameraDevice!!.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val request = previewRequestBuilder!!.build()
                        captureSession!!.setRepeatingRequest(request, null, backgroundHandler)

                        // Initialize seekbar to midpoint
                        exposureRange?.let { range ->
                            val mid = ((range.lower + range.upper) / 2)
                            val progress = exposureToProgress(mid)
                            runOnUiThread {
                                exposureSeekBar.progress = progress
                                updateExposureText(mid)
                            }
                        } ?: runOnUiThread {
                            exposureText.text = "Exposure control not supported"
                            exposureSeekBar.isEnabled = false
                        }

                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "createCaptureSession setRepeatingRequest: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@CameraActivity, "Preview configuration failed", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCameraPreviewSession: ${e.message}")
        }
    }

    private fun updateExposureFromSeek(progress: Int) {
        if (cameraDevice == null || previewRequestBuilder == null || captureSession == null) return
        if (exposureRange == null) return

        val exposure = progressToExposure(progress)
        try {
            // Set AE off and manual exposure
            previewRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            previewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            previewRequestBuilder!!.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)

            // Optionally set sensitivity if available (keep center value)
            sensitivityRange?.let { sRange ->
                val midIso = (sRange.lower + sRange.upper) / 2
                previewRequestBuilder!!.set(CaptureRequest.SENSOR_SENSITIVITY, midIso)
            }

            captureSession!!.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
            updateExposureText(exposure)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "updateExposureFromSeek: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "updateExposureFromSeek general: ${e.message}")
        }
    }

    private fun updateExposureText(exposureNs: Long) {
        // Exposure time is in nanoseconds. Convert to milliseconds for display and also present as 1/x sec if convenient
        val exposureMs = exposureNs / 1_000_000.0
        val shutterText = if (exposureNs > 0) {
            val seconds = exposureNs / 1_000_000_000.0
            if (seconds >= 1.0) String.format("%.2fs", seconds) else String.format("1/%.0f s", 1.0 / seconds)
        } else "Auto"

        runOnUiThread {
            exposureText.text = String.format("Exposure: %s (%.2f ms)", shutterText, exposureMs)
        }
    }

    private fun progressToExposure(progress: Int): Long {
        val range = exposureRange ?: return 0L
        val p = progress.coerceIn(0, exposureSeekBar.max)
        val fraction = p.toDouble() / exposureSeekBar.max
        // Interpolate on log scale for perceptual mapping (exposure spans many orders)
        val min = range.lower.toDouble()
        val max = range.upper.toDouble()
        val value = Math.exp(Math.log(min) * (1 - fraction) + Math.log(max) * fraction)
        return value.toLong().coerceIn(range.lower, range.upper)
    }

    private fun exposureToProgress(exposure: Long): Int {
        val range = exposureRange ?: return 0
        val min = range.lower.toDouble()
        val max = range.upper.toDouble()
        val frac = (Math.log(exposure.toDouble()) - Math.log(min)) / (Math.log(max) - Math.log(min))
        return (frac * exposureSeekBar.max).toInt().coerceIn(0, exposureSeekBar.max)
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "closeCamera: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
