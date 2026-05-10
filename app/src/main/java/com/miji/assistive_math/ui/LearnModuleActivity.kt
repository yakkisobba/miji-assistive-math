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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miji.assistive_math.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

class LearnModuleActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var btnBack: ImageView
    private lateinit var tvModuleTitle: TextView
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvScoreLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvQuestion: TextView
    private lateinit var tvQuestionPhonetic: TextView
    private lateinit var btnReadQuestion: View
    private lateinit var etAnswer: EditText
    private lateinit var btnMic: View
    private lateinit var btnSubmitAnswer: View
    private lateinit var cardFeedback: View
    private lateinit var tvFeedback: TextView
    private lateinit var tvSolutionContent: TextView
    private lateinit var btnNext: View
    private lateinit var tvNextLabel: TextView
    private lateinit var tvSubmitLabel: TextView


    // ── TTS ────────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech

    // ── Speech ─────────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else speakText("Microphone permission is needed to answer by voice.")
        }

    // ── Module data ────────────────────────────────────────────────────────────

    data class Question(
        val display: String,
        val phonetic: String,
        val answer: Double
    )

    private var moduleType: String = MODULE_ADDITION
    private var questions: List<Question> = emptyList()
    private var currentIndex = 0
    private var score = 0
    private var answered = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learn_module)

        moduleType = intent.getStringExtra(EXTRA_MODULE_TYPE) ?: MODULE_ADDITION
        questions  = questionsFor(moduleType)

        tts = TextToSpeech(this, this)

        bindViews()
        setupListeners()
        setupBottomNav()
        showQuestion(currentIndex)
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
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
                    if (utteranceId == UTT_QUESTION) openMicForAnswer()
                }
            }
        })

        readCurrentQuestionAloud()
    }

    // ── Question display ───────────────────────────────────────────────────────

    private fun showQuestion(index: Int) {
        val q = questions[index]
        answered = false

        tvQuestion.text         = q.display
        tvQuestionPhonetic.text = "\"${q.phonetic}\""
        tvProgressLabel.text    = "Question ${index + 1} of ${questions.size}"
        tvScoreLabel.text       = "Score: $score"
        progressBar.max         = questions.size
        progressBar.progress    = index + 1

        etAnswer.setText("")
        etAnswer.isEnabled        = true
        btnMic.isEnabled          = true
        btnSubmitAnswer.isEnabled = true
        btnReadQuestion.isEnabled = true

        cardFeedback.visibility = View.GONE
        btnNext.visibility      = View.GONE
    }

    private fun readCurrentQuestionAloud() {
        if (!::tts.isInitialized) return
        val q = questions[currentIndex]
        tts.speak(
            "Question ${currentIndex + 1}. ${titleFor(moduleType)}. ${q.phonetic}. What is the answer?",
            TextToSpeech.QUEUE_FLUSH, null, UTT_QUESTION
        )
    }

    // ── Answer submission ──────────────────────────────────────────────────────

    private fun submitAnswer(input: String) {
        if (answered) return
        val userAnswer = parseNumber(input.trim()) ?: run {
            speakText("I did not understand that number. Please try again.")
            return
        }

        answered = true
        etAnswer.isEnabled        = false
        btnMic.isEnabled          = false
        btnSubmitAnswer.isEnabled = false

        val q         = questions[currentIndex]
        val isCorrect = abs(userAnswer - q.answer) < 0.001
        if (isCorrect) score++

        showFeedback(isCorrect, q)
    }

    private fun showFeedback(correct: Boolean, q: Question) {
        val answerStr = formatAnswer(q.answer)
        cardFeedback.visibility = View.VISIBLE

        if (correct) {
            tvFeedback.text = "✓ Correct! Well done!"
            tvFeedback.setTextColor(getColor(R.color.accent))
            tvSolutionContent.text = "The answer is $answerStr."
            speakText("Correct! Great job! The answer is $answerStr.")
        } else {
            tvFeedback.text = "✗ Incorrect."
            tvFeedback.setTextColor(getColor(R.color.danger))
            tvSolutionContent.text = "The correct answer is $answerStr."
            speakText("Incorrect. The correct answer is $answerStr.")
        }

        tvScoreLabel.text   = "Score: $score"
        btnNext.visibility  = View.VISIBLE
        tvNextLabel.text    = if (currentIndex >= questions.size - 1) "See Final Score" else "Next Question"
    }

    // ── Next / Restart ─────────────────────────────────────────────────────────

    private fun goNext() {
        currentIndex++
        if (currentIndex >= questions.size) showFinalScore()
        else { showQuestion(currentIndex); readCurrentQuestionAloud() }
    }

    private fun showFinalScore() {
        tvModuleTitle.text      = "Finished!"
        tvProgressLabel.text    = "All ${questions.size} questions done"
        tvScoreLabel.text       = "Score: $score / ${questions.size}"
        progressBar.progress    = questions.size
        tvQuestion.text         = "$score / ${questions.size}"
        tvQuestionPhonetic.text = "\"You scored $score out of ${questions.size}\""

        etAnswer.isEnabled        = false
        btnMic.isEnabled          = false
        btnSubmitAnswer.isEnabled = true
        tvSubmitLabel.text = "Return Home"
        btnReadQuestion.isEnabled = false
        cardFeedback.visibility   = View.GONE
        btnNext.visibility        = View.VISIBLE
        tvNextLabel.text          = "Try Again"

        speakText("Module complete! You scored $score out of ${questions.size}.")
    }

    private fun restartModule() {
        currentIndex    = 0
        score           = 0
        tvModuleTitle.text = titleFor(moduleType)
        showQuestion(currentIndex)
        readCurrentQuestionAloud()
    }

    // ── Listeners ──────────────────────────────────────────────────────────────

    private fun setupListeners() {
        btnBack.setOnClickListener         { finish() }
        btnReadQuestion.setOnClickListener { readCurrentQuestionAloud() }

        btnMic.setOnClickListener {
            tts.stop()
            openMicForAnswer()
        }

        btnSubmitAnswer.setOnClickListener {
            if (tvSubmitLabel.text == "Return Home") {
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
                return@setOnClickListener
            }
            val input = etAnswer.text.toString().trim()
            if (input.isEmpty()) {
                etAnswer.error = "Enter your answer"
                speakText("Please enter your answer.")
                return@setOnClickListener
            }
            submitAnswer(input)
        }

        btnNext.setOnClickListener {
            if (tvNextLabel.text == "Try Again") restartModule() else goNext()
        }
    }

    // ── Speech recognizer ──────────────────────────────────────────────────────

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
                        SpeechRecognizer.ERROR_NO_MATCH       -> "No match. Please try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timed out. Please try again."
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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun speakText(text: String) {
        if (::tts.isInitialized) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun parseNumber(input: String): Double? {
        val words = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "twenty one" to 21,
            "twenty two" to 22, "twenty three" to 23, "twenty four" to 24,
            "twenty five" to 25, "thirty" to 30, "thirty six" to 36,
            "forty" to 40, "fifty" to 50, "sixty" to 60
        )
        return words[input.lowercase()]?.toDouble() ?: input.toDoubleOrNull()
    }

    private fun formatAnswer(ans: Double): String =
        if (ans == floor(ans) && !ans.isInfinite()) ans.toInt().toString()
        else "%.2f".format(ans)

    private fun titleFor(type: String): String = when (type) {
        MODULE_ADDITION       -> "Addition"
        MODULE_SUBTRACTION    -> "Subtraction"
        MODULE_MULTIPLICATION -> "Multiplication"
        MODULE_DIVISION       -> "Division"
        else                  -> "Module"
    }

    // ── View binding ───────────────────────────────────────────────────────────

    private fun bindViews() {
        btnBack            = findViewById(R.id.btnBack)
        tvModuleTitle      = findViewById(R.id.tvModuleTitle)
        tvProgressLabel    = findViewById(R.id.tvProgressLabel)
        tvScoreLabel       = findViewById(R.id.tvScoreLabel)
        progressBar        = findViewById(R.id.progressBar)
        tvQuestion         = findViewById(R.id.tvQuestion)
        tvQuestionPhonetic = findViewById(R.id.tvQuestionPhonetic)
        btnReadQuestion    = findViewById(R.id.btnReadQuestion)
        etAnswer           = findViewById(R.id.etAnswer)
        btnMic             = findViewById(R.id.btnMic)
        btnSubmitAnswer    = findViewById(R.id.btnSubmitAnswer)
        cardFeedback       = findViewById(R.id.cardFeedback)
        tvFeedback         = findViewById(R.id.tvFeedback)
        tvSolutionContent  = findViewById(R.id.tvSolutionContent)
        btnNext            = findViewById(R.id.btnNext)
        tvNextLabel        = findViewById(R.id.tvNextLabel)

        tvModuleTitle.text = titleFor(moduleType)
        setupSpeechRecognizer()

        tvSubmitLabel = findViewById(R.id.tvSubmitLabel)
    }

    // ── Bottom navigation ──────────────────────────────────────────────────────

    private fun setupBottomNav() {
        val nav = findViewById<View>(R.id.bottomNav)
        BottomNavHelper.bind(
            navRoot   = nav,
            activeTab = BottomNavHelper.Tab.HOME,
            onHome    = { startActivity(Intent(this, HomeActivity::class.java)); finish() },
            onScan    = { startActivity(Intent(this, ScanActivity::class.java)) },
            onProfile = { startActivity(Intent(this, ProfileActivity::class.java)) }
        )
    }

    // ── Questions ──────────────────────────────────────────────────────────────

    private fun questionsFor(type: String): List<Question> = when (type) {
        MODULE_ADDITION -> listOf(
            Question("2 + 3 = ?",  "two plus three",    5.0),
            Question("5 + 7 = ?",  "five plus seven",   12.0),
            Question("8 + 6 = ?",  "eight plus six",    14.0),
            Question("9 + 4 = ?",  "nine plus four",    13.0),
            Question("11 + 9 = ?", "eleven plus nine",  20.0)
        )
        MODULE_SUBTRACTION -> listOf(
            Question("8 - 3 = ?",   "eight minus three",          5.0),
            Question("15 - 7 = ?",  "fifteen minus seven",        8.0),
            Question("20 - 9 = ?",  "twenty minus nine",          11.0),
            Question("13 - 6 = ?",  "thirteen minus six",         7.0),
            Question("25 - 11 = ?", "twenty five minus eleven",   14.0)
        )
        MODULE_MULTIPLICATION -> listOf(
            Question("3 × 4 = ?", "three times four",  12.0),
            Question("6 × 2 = ?", "six times two",     12.0),
            Question("5 × 7 = ?", "five times seven",  35.0),
            Question("8 × 3 = ?", "eight times three", 24.0),
            Question("9 × 4 = ?", "nine times four",   36.0)
        )
        MODULE_DIVISION -> listOf(
            Question("12 ÷ 3 = ?", "twelve divided by three",      4.0),
            Question("20 ÷ 4 = ?", "twenty divided by four",       5.0),
            Question("15 ÷ 5 = ?", "fifteen divided by five",      3.0),
            Question("24 ÷ 6 = ?", "twenty four divided by six",   4.0),
            Question("36 ÷ 9 = ?", "thirty six divided by nine",   4.0)
        )
        else -> listOf(Question("2 + 3 = ?", "two plus three", 5.0))
    }

    companion object {
        const val EXTRA_MODULE_TYPE      = "extra_module_type"
        const val MODULE_ADDITION        = "addition"
        const val MODULE_SUBTRACTION     = "subtraction"
        const val MODULE_MULTIPLICATION  = "multiplication"
        const val MODULE_DIVISION        = "division"
        private const val UTT_QUESTION   = "question_read"
    }
}