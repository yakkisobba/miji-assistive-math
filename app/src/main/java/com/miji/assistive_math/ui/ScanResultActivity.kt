package com.miji.assistive_math.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miji.assistive_math.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

class ScanResultActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

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

    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else showMicDeniedMessage()
        }

    private var equationDisplay: String = ""
    private var equationPhonetic: String = ""
    private var confidencePercent: Float = 0f

    private enum class FlowState {
        IDLE, READING_EQ, WAITING_STT, VERIFYING, READING_SOL
    }
    private var flowState = FlowState.IDLE


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_result)

        equationDisplay   = intent.getStringExtra(EXTRA_EQUATION_DISPLAY)  ?: "2 + 6 - 7"
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
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    // Read Scanned Equation, auto-prompt STT
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.ENGLISH

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    when (flowState) {

                        FlowState.READING_EQ -> {
                            flowState = FlowState.WAITING_STT
                            speakThenListen(
                                "What is your answer?",
                                utteranceId = "prompt_stt"
                            )
                        }

                        FlowState.WAITING_STT -> {
                            if (utteranceId == "prompt_stt") {
                                openMicForAnswer()
                            }
                        }

                        FlowState.VERIFYING -> {
                            flowState = FlowState.READING_SOL
                            readSolutionAloud()
                        }

                        else -> { /* IDLE or READING_SOL — nothing to chain */ }
                    }
                }
            }
        })

        flowState = FlowState.READING_EQ
        tts.speak(
            "Equation recognized. $equationPhonetic",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "eq_read"
        )
    }


    // Listen to user for answer
    private fun openMicForAnswer() {
        if (hasMicPermission()) startListening()
        else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

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
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""

                etAnswer.hint = "Your answer"

                if (text.isNotEmpty()) {
                    etAnswer.setText(text)
                    submitAnswer(text)
                } else {
                    speakText("Could not hear your answer. Please try again.")
                }
            }

            override fun onError(error: Int) {
                etAnswer.hint = "Your answer"
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH       -> "No speech detected. Please tap the mic and try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out. Please tap the mic and try again."
                    SpeechRecognizer.ERROR_AUDIO          -> "Audio error. Please try again."
                    SpeechRecognizer.ERROR_NETWORK        -> "Network error. Please check your connection."
                    else                                  -> "Something went wrong. Please try again."
                }
                speakText(msg)
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

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

    // Verify correctness of answer
    private fun submitAnswer(input: String) {
        val userAnswer = parseSpokenNumber(input.trim())
        if (userAnswer == null) {
            speakText("I did not understand that number. Please try again.")
            return
        }

        val correctAnswer = evaluateExpression(equationDisplay)
        if (correctAnswer == null) {
            speakText("Could not evaluate the equation. Please scan again.")
            return
        }

        flowState = FlowState.VERIFYING

        if (abs(userAnswer - correctAnswer) < 0.001) {
            tvSolutionContent.text = "✓ Correct! Well done!"
            // Utterance id "feedback" → triggers solution read in onDone
            tts.speak("Correct! Great job!", TextToSpeech.QUEUE_FLUSH, null, "feedback")
        } else {
            val correctStr = formatAnswerForSpeech(correctAnswer)
            tvSolutionContent.text = "✗ Incorrect. The correct answer is $correctStr."
            tts.speak(
                "Incorrect. The answer is $correctStr.",
                TextToSpeech.QUEUE_FLUSH, null, "feedback"
            )
        }
    }

    private fun parseSpokenNumber(input: String): Double? {
        val wordMap = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3,
            "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
            "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
            "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19, "twenty" to 20
        )
        return wordMap[input.lowercase()]?.toDouble() ?: input.toDoubleOrNull()
    }

    // Read the complete step-by-step solution aloud
    private fun readSolutionAloud() {
        val expr = equationDisplay
            .replace("×", "*")
            .replace("÷", "/")
            .replace(" ", "")

        val tokens = tokenize(expr).toMutableList()
        val speechSteps  = mutableListOf<String>()
        val displayLines = mutableListOf<String>()

        speechSteps.add("Let us solve the equation step by step.")
        var stepNum = 1
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Char && (t == '*' || t == '/')) {
                val left  = tokens[i - 1] as Double
                val right = tokens[i + 1] as Double
                val result = if (t == '*') left * right else left / right
                val opWord = if (t == '*') "Multiply" else "Divide"
                val opSym  = if (t == '*') "×" else "÷"

                speechSteps.add(
                    "Step $stepNum: $opWord ${formatAnswerForSpeech(left)} " +
                            "and ${formatAnswerForSpeech(right)}. " +
                            "You get ${formatAnswerForSpeech(result)}."
                )
                displayLines.add(
                    "${formatAnswerForSpeech(left)} $opSym " +
                            "${formatAnswerForSpeech(right)} = ${formatAnswerForSpeech(result)}"
                )

                tokens[i - 1] = result
                tokens.removeAt(i + 1)
                tokens.removeAt(i)
                stepNum++
                i = 0
            } else {
                i++
            }
        }

        var current = tokens[0] as Double
        speechSteps.add("Step $stepNum: Start with ${formatAnswerForSpeech(current)}.")
        displayLines.add("Start: ${formatAnswerForSpeech(current)}")
        stepNum++

        i = 1
        while (i < tokens.size - 1) {
            val op    = tokens[i] as Char
            val value = tokens[i + 1] as Double
            val prev  = current

            if (op == '+') current += value else current -= value

            val opWord = if (op == '+') "Add" else "Subtract"
            val result = formatAnswerForSpeech(current)

            speechSteps.add(
                "Step $stepNum: $opWord ${formatAnswerForSpeech(value)}. " +
                        "You get $result."
            )
            displayLines.add(
                "${formatAnswerForSpeech(prev)} $op " +
                        "${formatAnswerForSpeech(value)} = $result"
            )
            stepNum++
            i += 2
        }

        val finalAnswer = formatAnswerForSpeech(current)
        speechSteps.add("The final answer is $finalAnswer.")
        displayLines.add("Answer: $finalAnswer")

        tvSolutionContent.text = displayLines.joinToString("\n")

        tts.stop()
        speechSteps.forEachIndexed { index, step ->
            tts.speak(
                step,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "sol_$index"
            )
        }

        flowState = FlowState.IDLE
    }

    // Arithmetic helpers
    private fun evaluateExpression(expr: String): Double? {
        return try {
            evalArithmetic(
                expr.replace("×", "*").replace("÷", "/").replace(" ", "")
            )
        } catch (e: Exception) { null }
    }

    private fun evalArithmetic(expr: String): Double {
        val tokens = tokenize(expr)
        val list   = mutableListOf<Any>()
        var i = 0

        // Pass 1: * and /
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Double && list.isNotEmpty() && list.last() is Char) {
                val op = list.last() as Char
                if (op == '*' || op == '/') {
                    list.removeAt(list.lastIndex)
                    val left = list.removeAt(list.lastIndex) as Double
                    list.add(if (op == '*') left * t else left / t)
                    i++; continue
                }
            }
            list.add(t); i++
        }

        // Pass 2: + and -
        var result = list[0] as Double
        var j = 1
        while (j < list.size - 1) {
            val op = list[j] as Char
            val r  = list[j + 1] as Double
            result = if (op == '+') result + r else result - r
            j += 2
        }
        return result
    }

    private fun tokenize(expr: String): List<Any> {
        val list = mutableListOf<Any>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c.isDigit() || c == '.') {
                val sb = StringBuilder()
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.'))
                    sb.append(expr[i++])
                list.add(sb.toString().toDouble())
            } else if ("+-*/".contains(c)) {
                list.add(c); i++
            } else i++
        }
        return list
    }

    private fun formatAnswerForSpeech(ans: Double): String =
        if (ans == floor(ans) && !ans.isInfinite()) ans.toInt().toString()
        else "%.2f".format(ans)

    // UI wiring
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
            flowState = FlowState.READING_EQ
            tts.speak(
                "Equation. $equationPhonetic",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "eq_read"
            )
        }

        btnMic.setOnClickListener {
            tts.stop()
            openMicForAnswer()
        }

        btnSubmitAnswer.setOnClickListener {
            val input = etAnswer.text.toString().trim()
            if (input.isEmpty()) {
                etAnswer.error = "Enter your answer"
                speakText("Please enter your answer.")
                return@setOnClickListener
            }
            submitAnswer(input)
        }

        btnReadSolution.setOnClickListener {
            flowState = FlowState.READING_SOL
            readSolutionAloud()
        }
    }

    // TTS convenience
    private fun speakText(text: String) {
        if (::tts.isInitialized)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /**
     * Speaks [text] with the given [utteranceId] so the UtteranceProgressListener
     * can chain the next step after it finishes.
     */
    private fun speakThenListen(text: String, utteranceId: String) {
        if (::tts.isInitialized)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }


    // Error/loading states (called by ScanActivity)
    fun showProcessingState() {
        btnScanAgain.isEnabled    = false
        btnReadAloud.isEnabled    = false
        btnMic.isEnabled          = false
        btnSubmitAnswer.isEnabled = false
        btnReadSolution.isEnabled = false
        tvSolutionContent.text    = "Processing…"
        speakText("Processing equation. Please wait.")
    }

    fun hideProcessingState() {
        btnScanAgain.isEnabled    = true
        btnReadAloud.isEnabled    = true
        btnMic.isEnabled          = true
        btnSubmitAnswer.isEnabled = true
        btnReadSolution.isEnabled = true
    }

    fun showDetectionError() {
        tvSolutionContent.text = "Detection failed. Please retake the photo."
        speakText("Detection failed. Please go back and retake the photo.")
    }

    fun showUncertainError(expression: String) {
        tvSolutionContent.text = "Unclear equation: $expression. Please retake the photo."
        speakText(
            "The equation was unclear. Detected $expression but confidence is low. " +
                    "Please retake the photo or move closer."
        )
    }

    fun showNoSymbolsError() {
        tvSolutionContent.text = "No equation found. Please retake the photo."
        speakText("No equation symbols were detected. Please go back and try again.")
    }

    // Permissions & bottom nav
    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun showMicDeniedMessage() {
        speakText(
            "Microphone permission is needed to answer by voice. " +
                    "Please enable Microphone access for MIJI in Settings."
        )
    }

    private fun setupBottomNav() {
        val nav = findViewById<View>(R.id.bottomNav)
        BottomNavHelper.bind(
            nav,
            BottomNavHelper.Tab.SCAN,
            { startActivity(Intent(this, HomeActivity::class.java)) },
            { },
            { startActivity(Intent(this, ProfileActivity::class.java)) }
        )
    }

    // Companion
    companion object {
        const val EXTRA_EQUATION_DISPLAY  = "extra_equation_display"
        const val EXTRA_EQUATION_PHONETIC = "extra_equation_phonetic"
        const val EXTRA_CONFIDENCE        = "extra_confidence"
    }
}