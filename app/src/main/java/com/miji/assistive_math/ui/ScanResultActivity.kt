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
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
            speakText("Equation recognized. $equationPhonetic")
        }
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        tvRecognizedLabel = findViewById(R.id.tvRecognizedLabel)
        tvConfidence = findViewById(R.id.tvConfidence)
        tvEquation = findViewById(R.id.tvEquation)
        tvEquationPhonetic = findViewById(R.id.tvEquationPhonetic)
        btnScanAgain = findViewById(R.id.btnScanAgain)
        btnReadAloud = findViewById(R.id.btnReadAloud)
        etAnswer = findViewById(R.id.etAnswer)
        btnMic = findViewById(R.id.btnMic)
        btnSubmitAnswer = findViewById(R.id.btnSubmitAnswer)
        btnReadSolution = findViewById(R.id.btnReadSolution)
        tvSolutionContent = findViewById(R.id.tvSolutionContent)
    }

    private fun populateData() {
        tvEquation.text = equationDisplay
        tvEquationPhonetic.text = "\"$equationPhonetic\""
        tvConfidence.text = "CNN Confidence: ${"%.1f".format(confidencePercent)}%"
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

        btnMic.setOnClickListener {
            if (hasMicPermission()) startListening()
            else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnSubmitAnswer.setOnClickListener {
            val input = etAnswer.text.toString().trim()

            if (input.isEmpty()) {
                etAnswer.error = "Enter answer"
                speakText("Please enter your answer.")
                return@setOnClickListener
            }

            val userAnswer = parseSpokenNumber(input)
            if (userAnswer == null) {
                speakText("Invalid number.")
                return@setOnClickListener
            }

            val correctAnswer = evaluateExpression(equationDisplay) ?: return@setOnClickListener

            if (abs(userAnswer - correctAnswer) < 0.001) {
                tvSolutionContent.text = "Correct!"
                speakText("Correct!")
            } else {
                val correct = formatAnswerForSpeech(correctAnswer)
                tvSolutionContent.text = "Incorrect. Answer is $correct"
                speakText("Incorrect. The answer is $correct")
            }
        }

        btnReadSolution.setOnClickListener {
            readSolutionAloud()
        }
    }

    private fun speakText(text: String) {
        if (::tts.isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun parseSpokenNumber(input: String): Double? {
        val map = mapOf(
            "zero" to 0, "one" to 1, "two" to 2,
            "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8,
            "nine" to 9, "ten" to 10
        )
        return map[input.lowercase()]?.toDouble() ?: input.toDoubleOrNull()
    }

    // ── Step-by-step with precedence ──
    private fun readSolutionAloud() {
        val expr = equationDisplay.replace("×","*").replace("÷","/").replace(" ","")
        val tokens = tokenize(expr).toMutableList()

        val steps = mutableListOf<String>()
        val display = mutableListOf<String>()

        var step = 1

        var i = 0
        while (i < tokens.size) {
            if (tokens[i] is Char && (tokens[i]=='*'||tokens[i]=='/')) {
                val op = tokens[i] as Char
                val left = tokens[i-1] as Double
                val right = tokens[i+1] as Double

                val result = if (op=='*') left*right else left/right

                steps.add("Step $step: ${if(op=='*')"Multiply" else "Divide"} $left and $right. Result is $result")
                display.add("$left ${if(op=='*')"×" else "÷"} $right = $result")

                tokens[i-1] = result
                tokens.removeAt(i)
                tokens.removeAt(i)

                step++
                i = 0
            } else i++
        }

        var current = tokens[0] as Double
        display.add("Start: $current")
        steps.add("Step $step: Start with $current")
        step++

        i = 1
        while (i < tokens.size-1) {
            val op = tokens[i] as Char
            val value = tokens[i+1] as Double

            if (op=='+') current+=value else current-=value

            steps.add("Step $step: ${if(op=='+')"Add" else "Subtract"} $value. Result is $current")
            display.add("$op $value = $current")

            step++
            i+=2
        }

        display.add("Answer: $current")
        steps.add("Final answer is $current")

        tvSolutionContent.text = display.joinToString("\n")

        tts.stop()
        steps.forEachIndexed { index, s ->
            tts.speak(s,
                if(index==0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "step$index")
        }
    }

    // ── Math Engine ──
    private fun evaluateExpression(expr:String):Double?{
        return try{ evalArithmetic(expr.replace("×","*").replace("÷","/").replace(" ","")) }catch(e:Exception){null}
    }

    private fun evalArithmetic(expr:String):Double{
        val tokens=tokenize(expr)
        val list= mutableListOf<Any>()
        var i=0

        while(i<tokens.size){
            val t=tokens[i]
            if(t is Double && list.isNotEmpty() && list.last() is Char){
                val op=list.last() as Char
                if(op=='*'||op=='/'){
                    list.removeAt(list.lastIndex)
                    val left=list.removeAt(list.lastIndex) as Double
                    list.add(if(op=='*') left*t else left/t)
                    i++; continue
                }
            }
            list.add(t); i++
        }

        var result=list[0] as Double
        var j=1
        while(j<list.size-1){
            val op=list[j] as Char
            val r=list[j+1] as Double
            result=if(op=='+') result+r else result-r
            j+=2
        }
        return result
    }

    private fun tokenize(expr:String):List<Any>{
        val list= mutableListOf<Any>()
        var i=0
        while(i<expr.length){
            val c=expr[i]
            if(c.isDigit()||c=='.'){
                val sb=StringBuilder()
                while(i<expr.length&&(expr[i].isDigit()||expr[i]=='.')) sb.append(expr[i++])
                list.add(sb.toString().toDouble())
            } else if("+-*/".contains(c)){
                list.add(c); i++
            } else i++
        }
        return list
    }

    private fun formatAnswerForSpeech(ans:Double):String{
        return if(ans== floor(ans)) ans.toInt().toString() else "%.2f".format(ans)
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object:RecognitionListener{
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if(text!=null) etAnswer.setText(text)
            }
            override fun onError(error: Int) { speakText("Speech error") }
            override fun onReadyForSpeech(p0: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        speechRecognizer?.startListening(intent)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun showMicDeniedMessage() {
        speakText("Enable microphone permission.")
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

    companion object {
        const val EXTRA_EQUATION_DISPLAY = "extra_equation_display"
        const val EXTRA_EQUATION_PHONETIC = "extra_equation_phonetic"
        const val EXTRA_CONFIDENCE = "extra_confidence"
    }
}

