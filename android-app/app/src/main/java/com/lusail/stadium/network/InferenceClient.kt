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
 * The user profile (from USER.md) is appended to the system prompt so the
 * model can personalize responses, pre-fill forms, and give context-aware help.
 *
 * Handles retry with exponential backoff for transient failures.
 */
class InferenceClient(
    private val baseUrl: String = "http://localhost:8080",
    private val maxRetries: Int = 3,
    private val baseBackoffMs: Long = 500L,
    private val userProfile: String = ""
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
        val basePrompt = """
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

        // Append user profile with usage instructions so the model knows how to leverage it
        if (userProfile.isNotBlank()) {
            return buildString {
                append(basePrompt)
                append("\n\n")
                append("--- HOW TO USE THE USER PROFILE ---\n")
                append("You have access to the fan's personal profile below. Follow these rules:\n\n")
                append("1. GREET BY NAME on the first scan of a session. For return scans, vary your greeting.\n")
                append("2. LANGUAGE: If the profile says 'preferred_language: Arabic', narrate in Arabic. Otherwise use English.\n")
                append("3. FOOD SCANS (concession QR, menu): Use dietary restrictions and favorite concessions to pre-fill orders.\n")
                append("   Mention the payment method from the profile (e.g. 'Charging to Google Wallet?').\n")
                append("4. NAVIGATION: Use usual_gate, usual_section, and accessibility prefs. If they prefer elevators,\n")
                append("   route through elevator banks. Reference their usual parking lot.\n")
                append("5. MATCH INFO: If the match involves their favorite_team or favorite_player, call it out.\n")
                append("6. CONCESSION FAVORITES: Suggest their favorite stand when nearby. Mention usual drink order.\n")
                append("7. PRE-MATCH: If scan is near kickoff and profile says arrival 45 min early, acknowledge their routine.\n")
                append("8. FORM PRE-FILL: When a bubble triggers a form (food order, info request), pre-populate fields\n")
                append("   from the profile. The user confirms, they don't retype.\n")
                append("9. PRIVACY: Never display phone, email, or ticket_id in narration or bubble labels.\n")
                append("   These are for backend form pre-fill only — stay out of the visible UI.\n")
                append("10. TONE: Reference profile details conversationally. Don't list them — weave them in naturally.\n")
                append("    'Your usual karak chai at The Brew Stand, Kailor?' not 'User prefers karak chai.'\n\n")
                append("--- USER PROFILE ---\n\n")
                append(userProfile)
            }
        }

        return basePrompt
    }
}
