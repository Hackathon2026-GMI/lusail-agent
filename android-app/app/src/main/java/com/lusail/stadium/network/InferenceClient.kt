package com.lusail.stadium.network

import com.lusail.stadium.models.BubbleResponse
import com.lusail.stadium.models.InferenceStats
import com.lusail.stadium.models.ScanContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Request body for POST /chat.
 */
@Serializable
data class InferenceRequest(
    val context: ScanContext,
    val system_prompt: String
)

/**
 * HTTP client that talks to the local Gemma 4 inference backend at localhost:8080.
 *
 * Endpoint: POST /chat
 * Body: { "context": <serialized ScanContext>, "system_prompt": "..." }
 * Response: BubbleResponse JSON
 *
 * Handles retry with exponential backoff for transient failures.
 */
class InferenceClient(
    private val baseUrl: String = "http://localhost:8080",
    private val maxRetries: Int = 3,
    private val baseBackoffMs: Long = 500L
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Send a scan context to the inference backend and receive bubble suggestions.
     *
     * @param scanContext The scan data to send
     * @return Result with BubbleResponse on success, exception on failure
     */
    suspend fun infer(scanContext: ScanContext): Result<BubbleResponse> =
        withContext(Dispatchers.IO) {
            InferenceStats.setInferring(true)

            val systemPrompt = buildSystemPrompt()
            val requestObj = InferenceRequest(
                context = scanContext,
                system_prompt = systemPrompt
            )

            val bodyJson = json.encodeToString(InferenceRequest.serializer(), requestObj)

            var lastError: Exception? = null

            for (attempt in 0..maxRetries) {
                try {
                    val startTime = System.currentTimeMillis()

                    val request = Request.Builder()
                        .url("$baseUrl/chat")
                        .post(bodyJson.toRequestBody(mediaType))
                        .header("Accept", "application/json")
                        .header("X-Lusail-Client", "android-companion/0.1.0")
                        .build()

                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        response.close()
                        throw IOException("HTTP ${response.code}: $errorBody")
                    }

                    val responseBody = response.body?.string()
                        ?: throw IOException("Empty response body")

                    response.close()

                    val elapsedMs = System.currentTimeMillis() - startTime

                    val bubbleResponse = json.decodeFromString(
                        BubbleResponse.serializer(),
                        responseBody
                    )

                    // Record stats for live monitoring
                    val tokensGen = bubbleResponse.metadata.tokensGenerated
                    InferenceStats.recordResponse(
                        responseTimeMs = elapsedMs,
                        tokensGenerated = tokensGen,
                        modelName = bubbleResponse.metadata.model
                    )

                    return@withContext Result.success(bubbleResponse)

                } catch (e: IOException) {
                    lastError = e
                    if (attempt < maxRetries) {
                        val backoffMs = (baseBackoffMs * 2.0.pow(attempt.toDouble()))
                            .roundToLong()
                        delay(backoffMs)
                    }
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < maxRetries) {
                        val backoffMs = (baseBackoffMs * 2.0.pow(attempt.toDouble()))
                            .roundToLong()
                        delay(backoffMs)
                    }
                }
            }

            // All retries exhausted
            val errorMsg = lastError?.message ?: "Unknown error"
            InferenceStats.recordError(errorMsg)
            return@withContext Result.failure(
                lastError ?: IOException("All $maxRetries retries exhausted")
            )
        }

    /**
     * Perform a health check against the backend.
     *
     * @return true if the backend responds with 200 OK
     */
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (_: Exception) {
            false
        }
    }

    private fun buildSystemPrompt(): String {
        return """
You are the Lusail Stadium Matchday Companion AI, running on-device via Gemma 4.
Your role is to help fans at Lusail Iconic Stadium in Qatar navigate their matchday experience.

Given a scan context (QR code scan, ticket scan, or OCR from signage), respond with:
1. A friendly, concise narration in the user's preferred language
2. 2-3 interactive "bubble" suggestions that are contextually relevant

Bubble types:
- "navigate": Guide the user to their seat, gate, amenities
- "comm": Connect to stadium services (concierge, food ordering, first aid)
- "info": Provide relevant information (match stats, player bios, schedule)
- "scan": Prompt another scan (merchandise, food menu QR, etc.)

Always respond in a helpful, stadium-appropriate tone. Keep responses under 150 tokens total.
            """.trimIndent()
        }
}
