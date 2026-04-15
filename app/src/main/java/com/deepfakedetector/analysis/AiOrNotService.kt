package com.deepfakedetector.analysis

import android.graphics.Bitmap
import com.deepfakedetector.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service d'appel à l'API AI or Not (aiornot.com)
 * Retourne un score entre 0.0 (réel) et 1.0 (IA)
 * ou null si l'appel échoue (mode offline/quota dépassé)
 */
@Singleton
class AiOrNotService @Inject constructor() {

    companion object {
        private const val API_URL = "https://api.aiornot.com/v1/reports/image"
        private const val TIMEOUT_MS = 15_000
    }

    /**
     * Analyse une image via l'API AI or Not.
     * Retourne le score IA (0.0–1.0) ou null en cas d'erreur.
     */
    suspend fun analyze(bitmap: Bitmap): AiOrNotResult? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.AIORNOT_API_KEY
        if (apiKey.isBlank()) return@withContext null

        try {
            // Compression JPEG de l'image
            val imageBytes = compressBitmap(bitmap)

            // Construction de la requête multipart
            val boundary = "Boundary-${System.currentTimeMillis()}"
            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true

            // Écriture du body multipart
            conn.outputStream.use { out ->
                val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"object\"; filename=\"image.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
                val suffix = "\r\n--$boundary--\r\n"
                out.write(prefix.toByteArray())
                out.write(imageBytes)
                out.write(suffix.toByteArray())
            }

            val responseCode = conn.responseCode
            if (responseCode != 200) return@withContext null

            val responseBody = conn.inputStream.bufferedReader().readText()
            parseResponse(responseBody)

        } catch (e: Exception) {
            null  // Echec silencieux — l'app fonctionne sans l'API
        }
    }

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        // Redimensionne si trop grande pour économiser la bande passante
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
            val report = root.getJSONObject("report")
            val aiObj = report.getJSONObject("ai")
            val humanObj = report.getJSONObject("human")
            val verdict = report.optString("verdict", "unknown")

            AiOrNotResult(
                aiScore = aiObj.getDouble("score").toFloat(),
                humanScore = humanObj.getDouble("score").toFloat(),
                verdict = verdict,
                isAiDetected = aiObj.getBoolean("is_detected")
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class AiOrNotResult(
    val aiScore: Float,       // 0.0–1.0 : probabilité IA
    val humanScore: Float,    // 0.0–1.0 : probabilité humain
    val verdict: String,      // "ai" ou "human"
    val isAiDetected: Boolean
)
