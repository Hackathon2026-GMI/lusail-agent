package com.lusail.stadium.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live monitoring data for inference bridge performance.
 * Shared across the app via a singleton StateFlow.
 */
data class InferenceStats(
    /** Total wall-clock time for the request in milliseconds */
    val responseTimeMs: Long = 0L,
    /** Number of tokens generated (from response metadata) */
    val tokensGenerated: Int = 0,
    /** Calculated tokens per second */
    val tokensPerSec: Float = 0f,
    /** Model name reported by the backend */
    val modelName: String = "gemma-4-hermes-local",
    /** Backend identifier (always localhost:8080) */
    val backend: String = "localhost:8080",
    /** Whether the inference bridge is currently processing a request */
    val isInferring: Boolean = false,
    /** Last error message, or null if everything is fine */
    val lastError: String? = null
) {
    companion object {
        /** Singleton observable stats */
        private val _stats = MutableStateFlow(InferenceStats())
        val stats: StateFlow<InferenceStats> = _stats.asStateFlow()

        fun update(block: (InferenceStats) -> InferenceStats) {
            _stats.value = block(_stats.value)
        }

        fun recordResponse(
            responseTimeMs: Long,
            tokensGenerated: Int,
            modelName: String = "gemma-4-hermes-local"
        ) {
            _stats.value = _stats.value.copy(
                responseTimeMs = responseTimeMs,
                tokensGenerated = tokensGenerated,
                tokensPerSec = if (responseTimeMs > 0) {
                    tokensGenerated.toFloat() / (responseTimeMs / 1000f)
                } else 0f,
                modelName = modelName,
                isInferring = false,
                lastError = null
            )
        }

        fun recordError(error: String) {
            _stats.value = _stats.value.copy(
                isInferring = false,
                lastError = error
            )
        }

        fun setInferring(inferring: Boolean) {
            _stats.value = _stats.value.copy(isInferring = inferring)
        }
    }
}

/**
 * Response envelope from the inference backend at POST /chat.
 */
@kotlinx.serialization.Serializable
data class BubbleResponse(
    /** List of bubble suggestions to display */
    val bubbles: List<Bubble> = emptyList(),
    /** Free-text narration / welcome message */
    val narration: String = "",
    /** Context ID for chaining */
    val contextId: String = "",
    /** Generation metadata */
    val metadata: ResponseMetadata = ResponseMetadata()
)

/**
 * A single interactive bubble with label, icon, and payload.
 */
@kotlinx.serialization.Serializable
data class Bubble(
    /** Unique bubble identifier */
    val id: String = "",
    /** Display label (localized) */
    val label: String = "",
    /** Material icon name */
    val icon: String = "info",
    /** Action type: "navigate", "comm", "info", "scan" */
    val type: String = "info",
    /** Route target (relative) for navigate type */
    val route: String = "",
    /** Arbitrary payload for the action handler */
    val payload: String = "",
    /** Display priority (0 = low, 10 = critical) */
    val priority: Int = 5,
    /** Bubble display color as hex string */
    val color: String = "#228b22"
)

/**
 * Generation metadata from the inference backend.
 */
@kotlinx.serialization.Serializable
data class ResponseMetadata(
    val model: String = "gemma-4-hermes-local",
    @kotlinx.serialization.SerialName("tokens_generated")
    val tokensGenerated: Int = 0,
    @kotlinx.serialization.SerialName("tokens_per_sec")
    val tokensPerSec: Float = 0f,
    @kotlinx.serialization.SerialName("response_time_ms")
    val responseTimeMs: Long = 0L
)
