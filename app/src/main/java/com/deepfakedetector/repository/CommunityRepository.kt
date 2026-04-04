package com.deepfakedetector.repository

import android.content.Context
import com.deepfakedetector.data.CommunityReport
import com.deepfakedetector.db.CommunityDao
import com.deepfakedetector.db.CommunityReportEntity
import com.deepfakedetector.network.ExternalApiService
import com.deepfakedetector.network.VideoCheckRequest
import com.deepfakedetector.network.CommunityVoteRequest
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val communityDao: CommunityDao,
    private val apiService: ExternalApiService,
    private val gson: Gson
) {

    // ─── Vérification externe + cache local ───────────────────────────────────

    /**
     * Vérifie si la vidéo est connue dans la base communautaire.
     * Priorité : cache local → API externe.
     */
    suspend fun checkVideo(videoHash: String, url: String? = null): CommunityReport? =
        withContext(Dispatchers.IO) {
            // 1. Cache local frais (< 1h)
            val cached = communityDao.getByHash(videoHash)
            if (cached != null) {
                val ageMs = System.currentTimeMillis() - cached.lastUpdated
                if (ageMs < 3_600_000L) {
                    return@withContext cached.toModel()
                }
            }

            // 2. Appel API externe
            try {
                val response = apiService.checkVideo(
                    VideoCheckRequest(videoHash = videoHash, url = url)
                )
                if (response.isSuccessful) {
                    val dto = response.body() ?: return@withContext cached?.toModel()
                    val entity = CommunityReportEntity(
                        id           = UUID.randomUUID().toString(),
                        videoHash    = videoHash,
                        fakeVotes    = (dto.fakeProbability?.times(100))?.toInt() ?: 0,
                        realVotes    = 100 - ((dto.fakeProbability?.times(100))?.toInt() ?: 0),
                        reportCount  = dto.reportCount,
                        sources      = gson.toJson(dto.sources),
                        lastUpdated  = System.currentTimeMillis()
                    )
                    communityDao.upsert(entity)
                    return@withContext entity.toModel()
                }
            } catch (_: Exception) { /* réseau indisponible → cache */ }

            cached?.toModel()
        }

    // ─── Vote communautaire ───────────────────────────────────────────────────

    suspend fun voteAsFake(videoHash: String) = withContext(Dispatchers.IO) {
        communityDao.incrementFakeVotes(videoHash)
        submitVoteToApi(videoHash, "FAKE")
    }

    suspend fun voteAsReal(videoHash: String) = withContext(Dispatchers.IO) {
        communityDao.incrementRealVotes(videoHash)
        submitVoteToApi(videoHash, "REAL")
    }

    private suspend fun submitVoteToApi(videoHash: String, vote: String) {
        try {
            val deviceId = getOrCreateDeviceId()
            apiService.submitVote(
                CommunityVoteRequest(
                    videoHash = videoHash,
                    vote      = vote,
                    deviceId  = deviceId
                )
            )
        } catch (_: Exception) { /* fire-and-forget */ }
    }

    // ─── Signalement ──────────────────────────────────────────────────────────

    suspend fun reportVideo(
        videoHash: String,
        reason: String,
        analysisScore: Float
    ) = withContext(Dispatchers.IO) {
        try {
            apiService.submitReport(
                mapOf(
                    "video_hash"    to videoHash,
                    "reason"        to reason,
                    "ai_score"      to analysisScore,
                    "device_id"     to getOrCreateDeviceId()
                )
            )
        } catch (_: Exception) { }
    }

    // ─── Vidéos virales suspectes ─────────────────────────────────────────────

    suspend fun getTrendingSuspicious(): List<TrendingVideo> =
        withContext(Dispatchers.IO) {
            try {
                val response = apiService.getTrendingSuspicious(limit = 10)
                if (response.isSuccessful) {
                    return@withContext response.body()?.map {
                        TrendingVideo(
                            hash             = it.hash,
                            url              = it.url,
                            reportCount      = it.reportCount,
                            fakeProbability  = it.fakeProbability,
                            platform         = it.platform
                        )
                    } ?: emptyList()
                }
            } catch (_: Exception) { }
            emptyList()
        }

    // ─── Utilitaires ──────────────────────────────────────────────────────────

    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("deepfake_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newId).apply()
            newId
        }
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private fun CommunityReportEntity.toModel(): CommunityReport {
        return CommunityReport(
            videoHash    = videoHash,
            fakeVotes    = fakeVotes,
            realVotes    = realVotes,
            totalReports = reportCount
        )
    }
}

data class TrendingVideo(
    val hash: String,
    val url: String?,
    val reportCount: Int,
    val fakeProbability: Float,
    val platform: String
)
