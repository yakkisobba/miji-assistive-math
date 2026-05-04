package com.miji.assistive_math.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miji.assistive_math.R
import java.util.Locale

class ScanResultActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var btnBack: ImageView
    private lateinit var tvRecognizedLabel: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvEquation: TextView
    private lateinit var tvEquationPhonetic: TextView
    private lateinit var btnScanAgain: View
    private lateinit var btnReadAloud: View
    private lateinit var etAnswer: EditText
    private lateinit var btnMic: View
    private lateinit var btnSubmitAnswer: View
    private lateinit var btnReadSolution: View
    private lateinit var tvSolutionContent: TextView

    // ── TTS ───────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech

    // ── SpeechRecognizer ──────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null

    // ── Mic permission ─────────────────────────────────────────────────────────
    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else showMicDeniedMessage()
        }

    // ── Data passed from ScanActivity ─────────────────────────────────────────
    private var equationDisplay: String = ""
    private var equationPhonetic: String = ""
    private var confidencePercent: Float = 0f

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_result)

        equationDisplay   = intent.getStringExtra(EXTRA_EQUATION_DISPLAY) ?: "2 + 6 - 7"
        equationPhonetic  = intent.getStringExtra(EXTRA_EQUATION_PHONETIC) ?: "two plus six minus seven"
        confidencePercent = intent.getFloatExtra(EXTRA_CONFIDENCE, 93.4f)

        tts = TextToSpeech(this, this)

        bindViews()
        populateData()
        setupListeners()
        setupBottomNav()
        initSpeechRecognizer()
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    // ── TextToSpeech.OnInitListener ────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
            // Auto-read equation when screen opens
            speakText("Equation recognized. $equationPhonetic")
        }
    }

    // ── Setup helpers ──────────────────────────────────────────────────────────

    private fun bindViews() {
        btnBack            = findViewById(R.id.btnBack)
        tvRecognizedLabel  = findViewById(R.id.tvRecognizedLabel)
        tvConfidence       = findViewById(R.id.tvConfidence)
        tvEquation         = findViewById(R.id.tvEquation)
        tvEquationPhonetic = findViewById(R.id.tvEquationPhonetic)
        btnScanAgain       = findViewById(R.id.btnScanAgain)
        btnReadAloud       = findViewById(R.id.btnReadAloud)
        etAnswer           = findViewById(R.id.etAnswer)
        btnMic             = findViewById(R.id.btnMic)
        btnSubmitAnswer    = findViewById(R.id.btnSubmitAnswer)
        btnReadSolution    = findViewById(R.id.btnReadSolution)
        tvSolutionContent  = findViewById(R.id.tvSolutionContent)
    }

    private fun populateData() {
        tvEquation.text         = equationDisplay
        tvEquationPhonetic.text = "\"$equationPhonetic\""
        tvConfidence.text       = "CNN Confidence: ${"%.1f".format(confidencePercent)}%"
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnScanAgain.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
            finish()
        }

        btnReadAloud.setOnClickListener {
            speakText(equationPhonetic)
        }

        // ── Mic button ─────────────────────────────────────────────────────────
        btnMic.setOnClickListener {
            if (hasMicPermission()) startListening()
            else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnSubmitAnswer.setOnClickListener {
            val userAnswer = etAnswer.text.toString().trim()
            if (userAnswer.isEmpty()) {
                etAnswer.error = "Please enter your answer"
                return@setOnClickListener
            }
            // TODO: Evaluate answer against computed result
        }

        btnReadSolution.setOnClickListener {
            speakText("The solution for $equationPhonetic will be shown here step by step.")
        }
    }

    // ── Speech Recognizer ──────────────────────────────────────────────────────

    /**
     * Initialize SpeechRecognizer once and reuse it.
     * Checks device support before creating.
     */
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            btnMic.isEnabled = false
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                etAnswer.hint = "Listening…"
            }

            override fun onBeginningOfSpeech() {
                etAnswer.hint = "Hearing you…"
            }

            override fun onEndOfSpeech() {
                etAnswer.hint = "Processing…"
            }

            override fun onResults(results: Bundle?) {
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull() ?: ""

                if (recognized.isNotEmpty()) {
                    etAnswer.setText(recognized)
                } else {
                    speakText("Could not recognize speech. Please try again.")
                }
                etAnswer.hint = "Your answer"
            }

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH       -> "No speech detected. Please try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out. Please try again."
                    SpeechRecognizer.ERROR_AUDIO          -> "Audio recording error. Please try again."
                    SpeechRecognizer.ERROR_NETWORK        -> "Network error. Please check your connection."
                    else                                  -> "Something went wrong. Please try again."
                }
                etAnswer.hint = "Your answer"
                speakText(message)
            }

            // Required overrides
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    /**
     * Starts listening for the student's spoken answer.
     */
    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your answer")
        }
        speechRecognizer?.startListening(intent)
    }

    // ── Loading / Processing state ─────────────────────────────────────────────

    /**
     * Called from ScanActivity while the model is running.
     * Disables buttons and speaks "Processing…" via TTS.
     */
    fun showProcessingState() {
        btnScanAgain.isEnabled    = false
        btnReadAloud.isEnabled    = false
        btnMic.isEnabled          = false
        btnSubmitAnswer.isEnabled = false
        btnReadSolution.isEnabled = false
        tvSolutionContent.text    = "Processing…"
        speakText("Processing equation. Please wait.")
    }

    /**
     * Called when the model finishes — restores all buttons.
     */
    fun hideProcessingState() {
        btnScanAgain.isEnabled    = true
        btnReadAloud.isEnabled    = true
        btnMic.isEnabled          = true
        btnSubmitAnswer.isEnabled = true
        btnReadSolution.isEnabled = true
    }

    // ── Error handling ─────────────────────────────────────────────────────────

    /**
     * Called when detection completely fails (crash, bad file, etc.)
     */
    fun showDetectionError() {
        tvSolutionContent.text = "Detection failed. Please retake the photo."
        speakText("Detection failed. Please go back and retake the photo.")
    }

    /**
     * Called when symbols were detected but confidence is too low.
     */
    fun showUncertainError(expression: String) {
        tvSolutionContent.text =
            "Unclear equation: $expression. Please retake the photo."
        speakText(
            "The equation was unclear. " +
                    "Detected $expression but confidence is low. " +
                    "Please retake the photo or move closer."
        )
    }

    /**
     * Called when no symbols were detected at all.
     */
    fun showNoSymbolsError() {
        tvSolutionContent.text = "No equation found. Please retake the photo."
        speakText("No equation symbols were detected. Please go back and try again.")
    }

    // ── Bottom navigation ──────────────────────────────────────────────────────

    private fun setupBottomNav() {
        val nav = findViewById<View>(R.id.bottomNav)
        BottomNavHelper.bind(
            navRoot   = nav,
            activeTab = BottomNavHelper.Tab.SCAN,
            onHome    = { startActivity(Intent(this, HomeActivity::class.java)) },
            onScan    = { /* already here */ },
            onProfile = { startActivity(Intent(this, ProfileActivity::class.java)) }
        )
    }

    // ── TTS helper ─────────────────────────────────────────────────────────────

    private fun speakText(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // ── Mic permission helpers ─────────────────────────────────────────────────

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun showMicDeniedMessage() {
        speakText(
            "Microphone permission is needed to answer by voice. " +
                    "Please enable Microphone access for MIJI in Settings."
        )
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val EXTRA_EQUATION_DISPLAY  = "extra_equation_display"
        const val EXTRA_EQUATION_PHONETIC = "extra_equation_phonetic"
        const val EXTRA_CONFIDENCE        = "extra_confidence"
    }
}