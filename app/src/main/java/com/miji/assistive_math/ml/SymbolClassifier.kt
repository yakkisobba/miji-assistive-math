package com.miji.assistive_math.ml

import android.content.Context
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import kotlin.math.exp

class SymbolClassifier(
    context: Context
) {
    private val module: Module

    private val classNames = listOf(
        "0", "1", "2", "3", "4",
        "5", "6", "7", "8", "9",
        "dot", "minus", "plus", "slash", "x"
    )

    private val confidenceThreshold = 0.4f
    private val marginThreshold = 0.1f

    init {
        val modelPath = assetFilePath(context, "symbol_classifier_simple_cnn.pt")
        Log.d("SymbolClassifier", "Loading PyTorch Mobile model from: $modelPath")
        module = Module.load(modelPath)
        Log.d("SymbolClassifier", "PyTorch Mobile model loaded successfully")
    }

    fun classify(inputArray: FloatArray): PredictionResult {
        val inputTensor = Tensor.fromBlob(
            inputArray,
            longArrayOf(1, 1, 28, 28)
        )

        val outputTensor = module.forward(IValue.from(inputTensor)).toTensor()
        val logits = outputTensor.dataAsFloatArray
        val probabilities = softmax(logits)

        val sortedIndices = probabilities.indices.sortedByDescending {
            probabilities[it]
        }

        val top5Text = sortedIndices.take(5).joinToString(" | ") { idx ->
            "${classNames[idx]}=${"%.4f".format(probabilities[idx])}"
        }

        Log.d("SymbolClassifier", "Top5: $top5Text")

        val top1Index = sortedIndices[0]
        val top2Index = sortedIndices[1]

        val top1Prob = probabilities[top1Index]
        val top2Prob = probabilities[top2Index]

        val accepted = top1Prob >= confidenceThreshold &&
                (top1Prob - top2Prob) >= marginThreshold

        return PredictionResult(
            label = classNames[top1Index],
            confidence = top1Prob,
            secondLabel = classNames[top2Index],
            secondConfidence = top2Prob,
            accepted = accepted
        )
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f

        val expValues = logits.map {
            exp((it - maxLogit).toDouble())
        }

        val sumExp = expValues.sum()

        return expValues.map {
            (it / sumExp).toFloat()
        }.toFloatArray()
    }
}