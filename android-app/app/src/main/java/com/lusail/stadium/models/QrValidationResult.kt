package com.lusail.stadium.models

/**
 * Result of QR payload security validation via DeepSeek-V4-Pro on GMI Cloud.
 * Mirrors the Mr_QR validation contract: VALID / SUSPICIOUS / INVALID.
 */
data class QrValidationResult(
    /** VALID, SUSPICIOUS, or INVALID */
    val verdict: String,
    /** Human-readable explanation from the AI model */
    val explanation: String,
    /** The raw payload that was validated */
    val payload: String,
    /** Wall-clock time for the validation API call in ms */
    val responseTimeMs: Long = 0L
) {
    val isValid: Boolean get() = verdict.uppercase() == "VALID"
    val isSuspicious: Boolean get() = verdict.uppercase() == "SUSPICIOUS"
    val isInvalid: Boolean get() = verdict.uppercase() == "INVALID"

    companion object {
        /** Parse the model's raw response into a structured result. */
        fun parse(rawResponse: String, payload: String, responseTimeMs: Long = 0L): QrValidationResult {
            val lines = rawResponse.trim().lines()
            val verdictLine = lines.firstOrNull()?.trim()?.uppercase() ?: "UNKNOWN"

            val verdict = when {
                verdictLine.startsWith("VALID") -> "VALID"
                verdictLine.startsWith("SUSPICIOUS") -> "SUSPICIOUS"
                verdictLine.startsWith("INVALID") -> "INVALID"
                else -> "UNKNOWN"
            }

            val explanation = if (lines.size > 1) {
                lines.drop(1).joinToString("\n").trim()
            } else {
                rawResponse.trim()
            }

            return QrValidationResult(
                verdict = verdict,
                explanation = explanation,
                payload = payload,
                responseTimeMs = responseTimeMs
            )
        }
    }
}
