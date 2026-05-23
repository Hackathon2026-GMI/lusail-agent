package com.lusail.stadium.network

import com.lusail.stadium.models.QrValidationResult
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
 * QR payload security validator backed by DeepSeek-V4-Pro on GMI Cloud.
 *
 * Ported from Mr_QR's Validity-Check.py — same system prompt, same model,
 * same verdict format (VALID / SUSPICIOUS / INVALID on its own line).
 *
 * This runs BEFORE the local inference step: if a QR payload is flagged
 * SUSPICIOUS or INVALID, the app shows the AI's security explanation
 * instead of proceeding to bubble inference.
 */
class QrValidator(
    private val apiKey: String,
    private val apiUrl: String = "https://api.gmi-serving.com/v1/chat/completions",
    private val model: String = "deepseek-ai/DeepSeek-V4-Pro",
    private val maxRetries: Int = 3,
    private val baseBackoffMs: Long = 500L
) {
    companion object {
        /** Mr_QR validation prompt — instructs the model to classify QR payloads. */
        val SYSTEM_PROMPT = """
You are a QR code payload security validator.
Analyze the given QR code payload and assess:
1. What type of content it contains (URL, vCard, text, WiFi credentials, payment, etc.)
2. Whether the content appears legitimate and safe
3. Any potential security risks (phishing URLs, malicious patterns, suspicious data)
4. A clear verdict: VALID, SUSPICIOUS, or INVALID

Start your response with the verdict on its own line, then explain.
        """.trimIndent()
    }

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
     * Validate a QR code payload against the Mr_QR security model.
     *
     * @param payload The decoded QR code text content
     * @return Result with QrValidationResult on success
     */
    suspend fun validate(payload: String): Result<QrValidationResult> =
        withContext(Dispatchers.IO) {
            val requestBody = GmiChatRequest(
                model = model,
                messages = listOf(
                    GmiMessage(role = "system", content = SYSTEM_PROMPT),
                    GmiMessage(
                        role = "user",
                        content = "Validate this QR code payload:\n\n$payload"
                    )
                ),
                temperature = 0.0,
                maxTokens = 500
            )

            val bodyJson = json.encodeToString(
                GmiChatRequest.serializer(),
                requestBody
            )

            var lastError: Exception? = null

            for (attempt in 0..maxRetries) {
                try {
                    val startTime = System.currentTimeMillis()

                    val request = Request.Builder()
                        .url(apiUrl)
                        .post(bodyJson.toRequestBody(mediaType))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer $apiKey")
                        .header("Accept", "application/json")
                        .build()

                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        val errorBody = try {
                            response.body?.string()?.let { body ->
                                Json { ignoreUnknownKeys = true }
                                    .parseToJsonElement(body)
                                    .jsonObject["error"]
                                    ?.jsonObject?.get("message")
                                    ?.let { it.toString().trim('"') }
                            } ?: "Unknown error"
                        } catch (_: Exception) {
                            response.body?.string() ?: "Unknown error"
                        }
                        response.close()
                        throw IOException("GMI HTTP ${response.code}: $errorBody")
                    }

                    val responseBody = response.body?.string()
                        ?: throw IOException("Empty response body")
                    response.close()

                    val gmiResponse = json.decodeFromString(
                        GmiChatResponse.serializer(),
                        responseBody
                    )

                    val content = gmiResponse.choices
                        .firstOrNull()
                        ?.message
                        ?.content
                        ?: throw IOException("No content in GMI response")

                    val elapsedMs = System.currentTimeMillis() - startTime

                    val result = QrValidationResult.parse(
                        rawResponse = content,
                        payload = payload,
                        responseTimeMs = elapsedMs
                    )

                    return@withContext Result.success(result)

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

            return@withContext Result.failure(
                lastError ?: IOException("All $maxRetries retries exhausted")
            )
        }

    // ── GMI API serialization types ──

    @Serializable
    data class GmiChatRequest(
        val model: String,
        val messages: List<GmiMessage>,
        val temperature: Double = 0.0,
        @kotlinx.serialization.SerialName("max_tokens")
        val maxTokens: Int = 500
    )

    @Serializable
    data class GmiMessage(
        val role: String,
        val content: String
    )

    @Serializable
    data class GmiChatResponse(
        val choices: List<GmiChoice> = emptyList()
    )

    @Serializable
    data class GmiChoice(
        val message: GmiMessage? = null
    )
}
