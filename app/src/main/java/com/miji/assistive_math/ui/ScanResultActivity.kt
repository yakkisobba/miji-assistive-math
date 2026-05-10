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

    // ── Speech ────────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else speakText("Microphone permission is needed to answer by voice.")
        }

    // ── Data ──────────────────────────────────────────────────────────────────
    private var equationDisplay: String = ""
    private var equationPhonetic: String = ""
    private var confidencePercent: Float = 0f
    private var answered = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

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
        setupSpeechRecognizer()
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    // ── TTS init ───────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.ENGLISH

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    if (utteranceId == UTT_EQ_READ) openMicForAnswer()
                }
            }
        })

        tts.speak(
            "Equation recognized. $equationPhonetic. What is your answer?",
            TextToSpeech.QUEUE_FLUSH, null, UTT_EQ_READ
        )
    }

    // ── Speech Recognizer ──────────────────────────────────────────────────────

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            btnMic.isEnabled = false
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) { etAnswer.hint = "Listening…" }
            override fun onBeginningOfSpeech()             { etAnswer.hint = "Hearing you…" }
            override fun onEndOfSpeech()                   { etAnswer.hint = "Processing…" }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                etAnswer.hint = "Type here..."
                if (text.isNotEmpty()) {
                    etAnswer.setText(text)
                    submitAnswer(text)
                } else {
                    speakText("Could not hear your answer. Please try again.")
                }
            }

            override fun onError(error: Int) {
                etAnswer.hint = "Type here..."
                speakText(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH       -> "No match found. Please try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timed out. Please try again."
                        SpeechRecognizer.ERROR_AUDIO          -> "Audio error. Please try again."
                        SpeechRecognizer.ERROR_NETWORK        -> "Network error. Please check your connection."
                        else                                  -> "Something went wrong. Please try again."
                    }
                )
            }

            override fun onRmsChanged(rmsdB: Float)               {}
            override fun onBufferReceived(buffer: ByteArray?)      {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?)  {}
        })
    }

    private fun openMicForAnswer() {
        if (answered) return
        if (hasMicPermission()) startListening()
        else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,       Locale.ENGLISH)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,    1)
            putExtra(RecognizerIntent.EXTRA_PROMPT,         "Say your answer")
        }
        speechRecognizer?.startListening(intent)
    }

    // ── Answer verification ────────────────────────────────────────────────────

    private fun submitAnswer(input: String) {
        if (answered) return

        val userAnswer = parseSpokenNumber(input.trim()) ?: run {
            speakText("I did not understand that number. Please try again.")
            return
        }

        val correctAnswer = evaluateExpression(equationDisplay) ?: run {
            speakText("Could not evaluate the equation. Please scan again.")
            return
        }

        answered = true
        btnMic.isEnabled          = false
        btnSubmitAnswer.isEnabled = false

        if (abs(userAnswer - correctAnswer) < 0.001) {
            tvSolutionContent.text = "✓ Correct! Well done!"
            speakText("Correct! Great job!")
        } else {
            val correctStr = formatAnswerForSpeech(correctAnswer)
            tvSolutionContent.text = "✗ Incorrect. The correct answer is $correctStr."
            speakText("Incorrect. The answer is $correctStr.")
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

    // ── Step-by-step solution ──────────────────────────────────────────────────

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
                val left   = tokens[i - 1] as Double
                val right  = tokens[i + 1] as Double
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
                "Step $stepNum: $opWord ${formatAnswerForSpeech(value)}. You get $result."
            )
            displayLines.add("${formatAnswerForSpeech(prev)} $op ${formatAnswerForSpeech(value)} = $result")
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
    }

    // ── Arithmetic helpers ─────────────────────────────────────────────────────

    private fun evaluateExpression(expr: String): Double? {
        return try {
            evalArithmetic(expr.replace("×", "*").replace("÷", "/").replace(" ", ""))
        } catch (e: Exception) { null }
    }

    private fun evalArithmetic(expr: String): Double {
        val tokens = tokenize(expr)
        val list   = mutableListOf<Any>()
        var i = 0

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

    // ── UI wiring ──────────────────────────────────────────────────────────────

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
            tts.speak(
                "The equation is: $equationPhonetic",
                TextToSpeech.QUEUE_FLUSH, null, null
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
            readSolutionAloud()
        }
    }

    // ── TTS helper ─────────────────────────────────────────────────────────────

    private fun speakText(text: String) {
        if (::tts.isInitialized) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    // ── Bottom navigation ──────────────────────────────────────────────────────

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

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val EXTRA_EQUATION_DISPLAY  = "extra_equation_display"
        const val EXTRA_EQUATION_PHONETIC = "extra_equation_phonetic"
        const val EXTRA_CONFIDENCE        = "extra_confidence"
        private const val UTT_EQ_READ     = "eq_read"
    }
}