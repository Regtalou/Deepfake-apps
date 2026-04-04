package com.deepfakedetector.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

// ─── DTOs API ─────────────────────────────────────────────────────────────────

data class VideoCheckRequest(
    @SerializedName("video_hash") val videoHash: String,
    @SerializedName("url")        val url: String? = null,
    @SerializedName("duration_s") val durationSeconds: Int? = null
)

data class VideoCheckResponse(
    @SerializedName("hash")              val hash: String,
    @SerializedName("known")             val known: Boolean,
    @SerializedName("report_count")      val reportCount: Int,
    @SerializedName("fake_probability")  val fakeProbability: Float?,
    @SerializedName("sources")           val sources: List<String>,
    @SerializedName("first_seen")        val firstSeen: String?,
    @SerializedName("verdict")           val verdict: String?,      // "FAKE" | "REAL" | "UNCERTAIN"
    @SerializedName("tags")              val tags: List<String>     // ["viral", "political", "celebrity"]
)

data class CommunityVoteRequest(
    @SerializedName("video_hash") val videoHash: String,
    @SerializedName("vote")       val vote: String,   // "FAKE" | "REAL"
    @SerializedName("device_id")  val deviceId: String
)

data class CommunityVoteResponse(
    @SerializedName("success")     val success: Boolean,
    @SerializedName("fake_votes")  val fakeVotes: Int,
    @SerializedName("real_votes")  val realVotes: Int
)

data class TrendingVideoDto(
    @SerializedName("hash")          val hash: String,
    @SerializedName("url")           val url: String?,
    @SerializedName("report_count")  val reportCount: Int,
    @SerializedName("fake_proba")    val fakeProbability: Float,
    @SerializedName("platform")      val platform: String
)

data class DownloadUrlResponse(
    @SerializedName("download_url")  val downloadUrl: String,
    @SerializedName("filename")      val filename: String,
    @SerializedName("size_bytes")    val sizeBytes: Long,
    @SerializedName("platform")      val platform: String,
    @SerializedName("expires_at")    val expiresAt: Long
)

// ─── Service Retrofit ─────────────────────────────────────────────────────────

interface ExternalApiService {

    /**
     * Vérifie si une vidéo (par hash) est déjà connue dans la base communautaire.
     */
    @POST("api/v1/videos/check")
    suspend fun checkVideo(
        @Body request: VideoCheckRequest
    ): Response<VideoCheckResponse>

    /**
     * Soumet un vote communautaire (FAKE / REAL).
     */
    @POST("api/v1/community/vote")
    suspend fun submitVote(
        @Body request: CommunityVoteRequest
    ): Response<CommunityVoteResponse>

    /**
     * Récupère les vidéos virales suspectes du moment.
     */
    @GET("api/v1/trending/suspicious")
    suspend fun getTrendingSuspicious(
        @Query("limit") limit: Int = 20
    ): Response<List<TrendingVideoDto>>

    /**
     * Demande l'URL de téléchargement pour une vidéo partagée via lien.
     * Le serveur gère TikTok / YouTube / X via yt-dlp backend.
     */
    @POST("api/v1/download/resolve")
    suspend fun resolveDownloadUrl(
        @Body body: Map<String, String>    // {"url": "https://..."}
    ): Response<DownloadUrlResponse>

    /**
     * Soumet un signalement (vidéo détectée localement comme fake).
     */
    @POST("api/v1/reports/submit")
    suspend fun submitReport(
        @Body body: Map<String, Any>
    ): Response<Map<String, Boolean>>
}

// ─── Constantes ───────────────────────────────────────────────────────────────

object ApiConfig {
    // À remplacer par votre vrai endpoint de production
    const val BASE_URL = "https://api.deepfakedetector.app/"
    const val CONNECT_TIMEOUT_S = 15L
    const val READ_TIMEOUT_S    = 30L
    const val API_VERSION       = "v1"
}
