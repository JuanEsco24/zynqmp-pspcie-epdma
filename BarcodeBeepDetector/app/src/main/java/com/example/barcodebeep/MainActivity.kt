package com.example.barcodebeep

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val REPEAT_INTERVAL_MS = 3_000L
        private const val LOST_GRACE_MS = 700L
        private const val BEEP_DURATION_MS = 120
        private const val BEEP_SPACING_MS = 190L
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner

    private val mainHandler = Handler(Looper.getMainLooper())
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    private var codeVisible = false
    private var lastSeenAtMs = 0L
    private var nextBeepAtMs = 0L

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                statusText.setText(R.string.camera_permission_required)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val scannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        barcodeScanner = BarcodeScanning.getClient(scannerOptions)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val inputImage = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees
                )

                barcodeScanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        handleDetection(barcodes.isNotEmpty())
                    }
                    .addOnFailureListener {
                        // Ignore a failed frame; the next frame is analyzed normally.
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (exc: Exception) {
                statusText.text = "Unable to start camera"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleDetection(found: Boolean) {
        val now = SystemClock.elapsedRealtime()

        if (found) {
            lastSeenAtMs = now

            if (!codeVisible) {
                codeVisible = true
                nextBeepAtMs = now + REPEAT_INTERVAL_MS
                playTripleBeep()
                updateStatus(true)
                return
            }

            if (now >= nextBeepAtMs) {
                nextBeepAtMs = now + REPEAT_INTERVAL_MS
                playTripleBeep()
            }
        } else if (codeVisible && now - lastSeenAtMs >= LOST_GRACE_MS) {
            codeVisible = false
            nextBeepAtMs = 0L
            updateStatus(false)
        }
    }

    private fun playTripleBeep() {
        repeat(3) { index ->
            mainHandler.postDelayed({
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
            }, index * BEEP_SPACING_MS)
        }
    }

    private fun updateStatus(detected: Boolean) {
        runOnUiThread {
            statusText.setText(
                if (detected) R.string.status_detected else R.string.status_searching
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        barcodeScanner.close()
        cameraExecutor.shutdown()
        toneGenerator.release()
    }
}
