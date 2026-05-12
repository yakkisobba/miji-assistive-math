package com.miji.assistive_math.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.miji.assistive_math.R
import com.miji.assistive_math.ml.ExpressionRecognizer
import com.miji.assistive_math.ml.RecognitionOutput
import com.miji.assistive_math.ml.YoloDetector
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {
    private var isFlashOn = false
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var isCapturing = false
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var expressionRecognizer: ExpressionRecognizer? = null
    private var yoloDetector: YoloDetector? = null
    private lateinit var tts: TextToSpeech

    private var lastGuidanceTime = 0L
    private var lastDirection = ""

    // Must hold still for HOLD_STILL_FRAMES_REQUIRED consecutive frames before auto-capture
    private var holdStillCount = 0

    // ── Permission launchers ───────────────────────────────────────────────────

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showCameraDeniedMessage()
        }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { handleGalleryImage(it) }
        }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.ENGLISH
        }

        setupSpeakingCard()
        setupShutterRow()
        setupBottomNav()
        setupTopBar()

        if (hasCameraPermission()) startCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onPause() {
        super.onPause()
        tts.stop()
    }

    override fun onResume() {
        super.onResume()
        // Re-enable the YOLO analyzer when returning from the result screen
        isCapturing = false
        holdStillCount = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        cameraExecutor.shutdown()
    }

    // ── Model lazy init ────────────────────────────────────────────────────────

    private fun getExpressionRecognizer(): ExpressionRecognizer {
        if (expressionRecognizer == null) {
            expressionRecognizer = ExpressionRecognizer(applicationContext)
        }
        return expressionRecognizer!!
    }

    private fun getYoloDetector(): YoloDetector {
        if (yoloDetector == null) {
            yoloDetector = YoloDetector(applicationContext)
        }
        return yoloDetector!!
    }

    // ── Camera permission ──────────────────────────────────────────────────────

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    // ── Camera setup ───────────────────────────────────────────────────────────

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.cameraPreview)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!isCapturing) {
                            val bitmap = imageProxy.toBitmap()
                            val direction = getYoloDetector().getGuidanceDirection(bitmap)
                            updateGuidance(direction)
                        }
                        imageProxy.close()
                    }
                }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                showCameraDeniedMessage()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showCameraDeniedMessage() {
        updateSpeakingCard("Camera permission is needed. Please enable it in Settings.")
        setAutoCaptureStatus("CAMERA UNAVAILABLE")
        speakText("Camera permission is needed to scan equations.")
    }

    // ── TTS ────────────────────────────────────────────────────────────────────

    private fun speakText(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // ── Guidance ───────────────────────────────────────────────────────────────

    data class GuidanceCue(val direction: String, val status: String, val isCentered: Boolean = false)

    fun updateGuidance(direction: String) {
        val cue = when (direction) {
            "move_left"       -> GuidanceCue("Move camera to the left",           "ADJUST")
            "move_right"      -> GuidanceCue("Move camera to the right",          "ADJUST")
            "move_up"         -> GuidanceCue("Move camera up",                    "ADJUST")
            "move_down"       -> GuidanceCue("Move camera down",                  "ADJUST")
            "move_up_left"    -> GuidanceCue("Move camera up and to the left",    "ADJUST")
            "move_up_right"   -> GuidanceCue("Move camera up and to the right",   "ADJUST")
            "move_down_left"  -> GuidanceCue("Move camera down and to the left",  "ADJUST")
            "move_down_right" -> GuidanceCue("Move camera down and to the right", "ADJUST")
            "hold_still"      -> GuidanceCue("Hold still. Capturing equation…",   "CAPTURING", true)
            else              -> GuidanceCue("Point camera at an equation",        "SEARCHING…")
        }
        runOnUiThread { applyGuidance(cue) }
    }

    private fun applyGuidance(cue: GuidanceCue) {
        updateSpeakingCard(cue.direction)
        setAutoCaptureStatus(cue.status)

        val now = System.currentTimeMillis()
        if (cue.direction != lastDirection || now - lastGuidanceTime > GUIDANCE_INTERVAL_MS) {
            speakText(cue.direction)
            lastDirection = cue.direction
            lastGuidanceTime = now
        }

        if (cue.isCentered) {
            holdStillCount++
            // Only auto-capture after HOLD_STILL_FRAMES_REQUIRED consecutive centred frames.
            // Any non-centred frame resets the counter — prevents wobbly false triggers.
            if (!isCapturing && holdStillCount >= HOLD_STILL_FRAMES_REQUIRED) {
                holdStillCount = 0
                capturePhoto()
            }
        } else {
            holdStillCount = 0
        }
    }

    // ── Top bar ────────────────────────────────────────────────────────────────

    private fun setupTopBar() {
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            MenuHelper.showClassroomMenu(this)
        }
    }

    // ── Speaking card ──────────────────────────────────────────────────────────

    fun updateSpeakingCard(instruction: String) {
        findViewById<TextView>(R.id.tvSpeakingInstruction).text = instruction
    }

    private fun setupSpeakingCard() { /* initial state via XML */ }

    // ── Shutter row ────────────────────────────────────────────────────────────

    private fun setupShutterRow() {
        val ivFlash = findViewById<ImageView>(R.id.ivFlash)

        findViewById<View>(R.id.btnFlash).setOnClickListener {
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)
            val tint = if (isFlashOn) getColor(R.color.accent) else getColor(R.color.white)
            ivFlash.setColorFilter(tint)
        }

        // Manual shutter — always available, ignores YOLO stability counter
        findViewById<FrameLayout>(R.id.btnShutter).setOnClickListener {
            capturePhoto()
        }

        findViewById<View>(R.id.btnUpload).setOnClickListener {
            galleryLauncher.launch("image/*")
        }
    }

    // ── Photo capture ──────────────────────────────────────────────────────────

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        if (isCapturing) return

        holdStillCount = 0
        isCapturing = true
        setAutoCaptureStatus("CAPTURING…")
        updateSpeakingCard("Hold still. Capturing equation…")
        speakText("Hold still. Capturing equation.")

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    setAutoCaptureStatus("PROCESSING…")
                    updateSpeakingCard("Processing equation…")
                    speakText("Processing equation.")
                    showLoadingOverlay("Processing equation…")
                    processImageFromProxy(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed: ${exception.message}", exception)
                    isCapturing = false
                    hideLoadingOverlay()
                    setAutoCaptureStatus("AUTO-CAPTURE READY")
                    updateSpeakingCard("Capture failed. Please try again.")
                    speakText("Capture failed. Please try again.")
                }
            }
        )
    }

    // ── Gallery ────────────────────────────────────────────────────────────────

    private fun handleGalleryImage(uri: Uri) {
        if (isCapturing) return         // already processing — ignore
        isCapturing = true              // block YOLO from firing during gallery processing
        Log.d(TAG, "Gallery image selected: $uri")
        try {
            // Verify the URI is readable
            contentResolver.getType(uri)?.let {
                Log.d(TAG, "Image MIME type: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not verify selected image: ${e.message}")
        }

        // Reset capture state before processing
        isCapturing = true
        setAutoCaptureStatus("PROCESSING…")
        updateSpeakingCard("Processing selected image…")
        speakText("Processing selected image.")
        showLoadingOverlay("Processing selected image…")
        processImageFromFile(uri)
    }

    // ── Status label ───────────────────────────────────────────────────────────

    fun setAutoCaptureStatus(status: String) {
        findViewById<TextView>(R.id.tvAutoCaptureLabel).text = status
    }

    // ── Process image ──────────────────────────────────────────────────────────


    private fun processImageFromProxy(image: ImageProxy){
        processImage(image.toBitmap())
    }

    private fun processImageFromFile(uri: Uri){
        val bitmap = loadBitmapFromUri(uri)
        if (bitmap == null) {
            runOnUiThread {
                setAutoCaptureStatus("FAILED")
                updateSpeakingCard("Could not read the image. Please try again.")
                speakText("Could not read the image. Please try again.")
            }
            return
        }
        processImage(bitmap)
    }
    private fun processImage(bitmap: Bitmap) {
        cameraExecutor.execute {
            try {
                val output = getExpressionRecognizer().recognizeExpression(bitmap)
                Log.d(TAG, "Detected: ${output.detectedSymbolCount} symbols — ${output.expression}")

                runOnUiThread {
                    val rejectedCount   = output.predictions.count { !it.accepted }
                    val majorityRejected = rejectedCount > output.predictions.size / 2

                    when {
                        output.detectedSymbolCount == 0 || output.expression.isBlank() -> {
                            isCapturing = false
                            hideLoadingOverlay()
                            setAutoCaptureStatus("NO SYMBOLS FOUND")
                            updateSpeakingCard("No equation detected. Please try again.")
                            speakText("No equation detected. Please try again.")
                        }
                        majorityRejected -> {
                            isCapturing = false
                            hideLoadingOverlay()
                            setAutoCaptureStatus("UNCERTAIN")
                            updateSpeakingCard("Equation unclear. Move closer and try again.")
                            speakText("Equation unclear. Move closer and try again.")
                        }
                        else -> {

                            hideLoadingOverlay()
                            setAutoCaptureStatus("DONE")
                            updateSpeakingCard("Equation recognized.")
                            speakText("Equation recognized.")
                            openResultScreen(output)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Processing failed: ${e.message}", e)
                runOnUiThread {
                    isCapturing = false
                    hideLoadingOverlay()
                    setAutoCaptureStatus("FAILED")
                    updateSpeakingCard("Processing failed: ${e.message}")
                    speakText("Processing failed. Please try again.")
                }
            }
            isCapturing = false
        }
    }

    // ── Loading overlay ────────────────────────────────────────────────────────

    private fun showLoadingOverlay(message: String = "Processing equation…") {
        findViewById<TextView>(R.id.tvProcessingLabel).text = message
        findViewById<View>(R.id.processingOverlay).visibility = View.VISIBLE
    }

    private fun hideLoadingOverlay() {
        findViewById<View>(R.id.processingOverlay).visibility = View.GONE
    }

    // ── Bitmap helpers ─────────────────────────────────────────────────────────

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        val bitmap = contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) null else BitmapFactory.decodeStream(stream)
        }
        if (bitmap == null) return null
        return rotateBitmapIfRequired(uri, bitmap)
        processImage(bitmap)
    }

    private fun rotateBitmapIfRequired(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) ExifInterface.ORIENTATION_NORMAL
            else ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().also { it.postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ── Result screen ──────────────────────────────────────────────────────────

    private fun openResultScreen(output: RecognitionOutput) {
        val confidence = output.predictions.map { it.confidence }.average().toFloat() * 100f
        val display    = output.expression
            .replace("*", " × ").replace("/", " ÷ ")
            .replace("+", " + ").replace("-", " - ").trim()
        val phonetic   = buildPhonetic(output.expression)

        startActivity(Intent(this, ScanResultActivity::class.java).apply {
            putExtra(ScanResultActivity.EXTRA_EQUATION_DISPLAY,  display)
            putExtra(ScanResultActivity.EXTRA_EQUATION_PHONETIC, phonetic)
            putExtra(ScanResultActivity.EXTRA_CONFIDENCE,        confidence)
        })
    }

    private fun buildPhonetic(expression: String): String {
        val digits = mapOf('0' to "zero",'1' to "one",'2' to "two",'3' to "three",
            '4' to "four",'5' to "five",'6' to "six",'7' to "seven",
            '8' to "eight",'9' to "nine")
        return expression.map { c -> when(c) {
            in '0'..'9' -> digits[c] ?: c.toString()
            '+' -> "plus"; '-' -> "minus"; '*' -> "times"
            '/' -> "divided by"; '.' -> "point"; else -> c.toString()
        }}.joinToString(" ")
    }

    // ── Bottom nav ─────────────────────────────────────────────────────────────

    private fun setupBottomNav() {
        BottomNavHelper.bind(
            navRoot   = findViewById(R.id.bottomNavScan),
            activeTab = BottomNavHelper.Tab.SCAN,
            onHome    = { startActivity(Intent(this, HomeActivity::class.java)) },
            onScan    = { },
            onProfile = { startActivity(Intent(this, ProfileActivity::class.java)) }
        )
    }

    companion object {
        private const val TAG                       = "ScanActivity"
        private const val SAMPLE_STEP               = 8
        private const val CONTRAST_THRESHOLD        = 10f
        private const val GUIDANCE_INTERVAL_MS      = 3000L
        private const val HOLD_STILL_FRAMES_REQUIRED = 4   // ~1 sec at 15 fps
    }
}