package com.miji.assistive_math.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

    // ── Mic permission ─────────────────────────────────────────────────────────
    // Registers a callback for the system permission dialog. We don't show
    // the dialog here — we just declare what to do when the user responds.
    // .launch(...) (called from the mic button) is what actually shows it.
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

        // Read extras sent by ScanActivity
        equationDisplay    = intent.getStringExtra(EXTRA_EQUATION_DISPLAY) ?: "2 + 6 - 7"
        equationPhonetic   = intent.getStringExtra(EXTRA_EQUATION_PHONETIC) ?: "two plus six minus seven"
        confidencePercent  = intent.getFloatExtra(EXTRA_CONFIDENCE, 93.4f)

        tts = TextToSpeech(this, this)

        bindViews()
        populateData()
        setupListeners()
        setupBottomNav()
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    // ── TextToSpeech.OnInitListener ────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
        }
    }

    // ── Setup helpers ──────────────────────────────────────────────────────────

    private fun bindViews() {
        btnBack             = findViewById(R.id.btnBack)
        tvRecognizedLabel   = findViewById(R.id.tvRecognizedLabel)
        tvConfidence        = findViewById(R.id.tvConfidence)
        tvEquation          = findViewById(R.id.tvEquation)
        tvEquationPhonetic  = findViewById(R.id.tvEquationPhonetic)
        btnScanAgain        = findViewById(R.id.btnScanAgain)
        btnReadAloud        = findViewById(R.id.btnReadAloud)
        etAnswer            = findViewById(R.id.etAnswer)
        btnMic              = findViewById(R.id.btnMic)
        btnSubmitAnswer     = findViewById(R.id.btnSubmitAnswer)
        btnReadSolution     = findViewById(R.id.btnReadSolution)
        tvSolutionContent   = findViewById(R.id.tvSolutionContent)
    }

    private fun populateData() {
        tvEquation.text         = equationDisplay
        tvEquationPhonetic.text = "\"$equationPhonetic\""
        tvConfidence.text       = "CNN Confidence: ${"%.1f".format(confidencePercent)}%"
    }

    private fun setupListeners() {
        // Back arrow – close this screen
        btnBack.setOnClickListener { finish() }

        // Scan Again – return to ScanActivity
        btnScanAgain.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
            finish()
        }

        // Read Aloud – speak the phonetic equation
        btnReadAloud.setOnClickListener {
            speakText(equationPhonetic)
        }

        // Mic button – launch speech-to-text for answer input.
        // Permission gate: ask if not yet granted, then proceed.
        btnMic.setOnClickListener {
            if (hasMicPermission()) {
                startListening()
            } else {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // Submit Answer – validate user's answer
        btnSubmitAnswer.setOnClickListener {
            val userAnswer = etAnswer.text.toString().trim()
            if (userAnswer.isEmpty()) {
                etAnswer.error = "Please enter your answer"
                return@setOnClickListener
            }
            // TODO: Evaluate answer against computed result
            // val correct = evaluateAnswer(equationDisplay, userAnswer)
            // showAnswerFeedback(correct)
        }

        // Read Step-by-Step Solution Aloud
        btnReadSolution.setOnClickListener {
            // TODO: Generate and populate step-by-step solution, then speak it
            // val steps = SolutionEngine.solve(equationDisplay)
            // tvSolutionContent.text = steps.formatted
            // speakText(steps.spokenText)

            // Placeholder: speak the equation answer
            speakText("The solution for $equationPhonetic will be shown here step by step.")
        }
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

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Launch SpeechRecognizer to capture the student's spoken answer and
     * populate etAnswer. Stub for now — to be implemented alongside the
     * SpeechRecognizer checklist item.
     */
    private fun startListening() {
        // TODO: launch SpeechRecognizer; on result write the recognized text into etAnswer.
        // val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        //     .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // startActivityForResult(intent, REQUEST_SPEECH_INPUT)
    }

    /**
     * Graceful fallback when the user denies microphone permission.
     * Routed through TTS so blind users get the explanation read aloud.
     */
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