package com.deepfakedetector.analysis

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiOrNotService @Inject constructor() {

    companion object {
        private const val API_URL = "https://api.aiornot.com/v1/reports/image"
        private const val API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6IjIxMDk5NTdhLTkzNzUtNDM2OS04OWViLWQ2NDcwMmUyZjQ1MyIsInVzZXJfaWQiOiIzMjkzNzNiMC1iMTFiLTRkYzgtOWM1Mi05ODIyNjI1NDNmOTAiLCJhdWQiOiJhY2Nlc3MiLCJleHAiOjE5MzM5MTM4NjcsInNjb3BlIjoiYWxsIn0.vIiJN-WhBaTLrO75woUPo98-omW9YIkSM9P86MQTFr4"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun analyze(bitmap: Bitmap): AiOrNotResult? = withContext(Dispatchers.IO) {
        try {
            val imageBytes = compressBitmap(bitmap)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "object",
                    "image.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $API_KEY")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            parseResponse(body)

        } catch (e: Exception) {
            null
        }
    }

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        val maxDim = 1024
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else bitmap

        return ByteArrayOutputStream().also { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }.toByteArray()
    }

    private fun parseResponse(json: String): AiOrNotResult? {
        return try {
            val root = JSONObject(json)
            if (root.has("report")) {
                val report = root.getJSONObject("report")
                val aiObj = report.optJSONObject("ai")
                val verdict = report.optString("verdict", "unknown")
                if (aiObj != null) {
                    val aiScore = aiObj.optDouble("score", 0.0).toFloat()
                    return AiOrNotResult(
                        aiScore = aiScore,
                        humanScore = 1f - aiScore,
                        verdict = verdict,
                        isAiDetected = aiObj.optBoolean("is_detected", aiScore > 0.5f)
                    )
                }
            }
            if (root.has("score")) {
                val aiScore = root.optDouble("score", 0.0).toFloat()
                val verdict = root.optString("verdict", if (aiScore > 0.5f) "ai" else "human")
                return AiOrNotResult(
                    aiScore = aiScore,
                    humanScore = 1f - aiScore,
                    verdict = verdict,
                    isAiDetected = aiScore > 0.5f
                )
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}

data class AiOrNotResult(
    val aiScore: Float,
    val humanScore: Float,
    val verdict: String,
    val isAiDetected: Boolean
)
