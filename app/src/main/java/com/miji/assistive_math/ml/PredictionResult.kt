package com.miji.assistive_math.ml

data class PredictionResult(
    val label: String,
    val confidence: Float,
    val secondLabel: String,
    val secondConfidence: Float,
    val accepted: Boolean
)