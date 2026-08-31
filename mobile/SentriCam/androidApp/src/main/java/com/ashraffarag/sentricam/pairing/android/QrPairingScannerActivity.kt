package com.ashraffarag.sentricam.pairing.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.databinding.ActivityQrPairingScannerBinding
import com.ashraffarag.sentricam.device.registration.HubPairingPayloadParser
import com.ashraffarag.sentricam.device.registration.RegistrationCallResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrPairingScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrPairingScannerBinding
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
    @Volatile private var completed = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScanner() else finishWithActionRequired(ACTION_CAMERA_PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrPairingScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.qrCancel.setOnClickListener { finish() }
        binding.qrManualSetup.setOnClickListener { finishWithActionRequired(ACTION_MANUAL_SETUP) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        completed = true
        analyzerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startScanner() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.qrPreview.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analyzerExecutor, ::analyze) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                binding.qrScanStatus.setText(R.string.pairing_scan_camera_failed)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (completed) return
            val luminance = image.luminanceBytes()
            val source = PlanarYUVLuminanceSource(
                luminance,
                image.width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val rawPayload = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }
                .getOrNull()
                ?: return
            when (HubPairingPayloadParser.parse(rawPayload)) {
                is RegistrationCallResult.Success -> complete(rawPayload)
                is RegistrationCallResult.Failure -> runOnUiThread {
                    binding.qrScanStatus.setText(R.string.pairing_scan_invalid)
                }
            }
        } finally {
            reader.reset()
            image.close()
        }
    }

    private fun complete(payload: String) {
        if (completed) return
        completed = true
        runOnUiThread {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PAIRING_PAYLOAD, payload))
            finish()
        }
    }

    private fun finishWithActionRequired(action: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ACTION_REQUIRED, action))
        finish()
    }

    private fun ImageProxy.luminanceBytes(): ByteArray {
        val plane = planes.first()
        val buffer = plane.buffer.duplicate()
        val output = ByteArray(width * height)
        for (row in 0 until height) {
            val rowStart = row * plane.rowStride
            for (column in 0 until width) {
                output[row * width + column] = buffer.get(rowStart + column * plane.pixelStride)
            }
        }
        return output
    }

    companion object {
        const val EXTRA_PAIRING_PAYLOAD = "pairing_payload"
        const val EXTRA_ACTION_REQUIRED = "action_required"
        const val ACTION_CAMERA_PERMISSION = "camera_permission_required"
        const val ACTION_MANUAL_SETUP = "manual_setup"

        fun intent(context: Context) = Intent(context, QrPairingScannerActivity::class.java)
    }
}
