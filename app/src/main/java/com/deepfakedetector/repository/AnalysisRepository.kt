package com.deepfakedetector.repository

import android.content.Context
import android.net.Uri
import com.deepfakedetector.analysis.*
import com.deepfakedetector.data.*
import com.deepfakedetector.db.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalysisRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analysisDao: AnalysisDao,
    private val metadataAnalyzer: MetadataAnalyzer,
    private val videoAnalyzer: VideoAnalyzer,
    private val audioAnalyzer: AudioAnalyzer,
    private val faceAnalyzer: FaceAnalyzer,
    private val scoreFusionEngine: ScoreFusionEngine,
    private val gson: Gson
) {

    // ─── Flux historique ──────────────────────────────────────────────────────

    val allResults: Flow<List<AnalysisResult>> =
        analysisDao.getAllResults().map { entities ->
            entities.mapNotNull { it.toModel() }
        }

    val recentResults: Flow<List<AnalysisResult>> =
        analysisDao.getRecentResults(20).map { entities ->
            entities.mapNotNull { it.toModel() }
        }

    // ─── Analyse principale ───────────────────────────────────────────────────

    suspend fun analyzeVideo(
        uri: Uri,
        mode: AnalysisMode,
        onProgress: (AnalysisStep, Float) -> Unit
    ): AnalysisResult = withContext(Dispatchers.IO) {

        val videoHash = computeVideoHash(uri)
        val videoId   = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        // Cache INSTANT : même vidéo déjà analysée ?
        val cached = analysisDao.getByVideoHash(videoHash)
        if (cached != null && mode == AnalysisMode.INSTANT) {
            return@withContext cached.toModel()!!
        }

        // Étape 1 : Métadonnées
        onProgress(AnalysisStep.METADATA, 0.05f)
        val (metadataScore, videoMetadata) = metadataAnalyzer.analyze(uri)

        // Étape 2 : Pixel + temporel
        onProgress(AnalysisStep.PIXEL, 0.20f)
        val (pixelScore, temporalScore) = videoAnalyzer.analyze(uri, mode)

        // Étape 3 : Audio
        val audioScore: ModuleScore? = if (mode != AnalysisMode.INSTANT) {
            onProgress(AnalysisStep.AUDIO, 0.45f)
            audioAnalyzer.analyze(uri, mode)
        } else null

        // Étape 4 : Visage + physiologie
        val faceScore: ModuleScore?
        val physiologicalScore: ModuleScore?
        if (mode == AnalysisMode.COMPLETE) {
            onProgress(AnalysisStep.FACE, 0.65f)
            val facePair = faceAnalyzer.analyze(uri, mode)
            faceScore         = facePair?.first
            physiologicalScore = facePair?.second
        } else {
            faceScore         = null
            physiologicalScore = null
        }

        // Étape 5 : Fusion
        onProgress(AnalysisStep.FUSION, 0.90f)
        val moduleScores = ModuleScores(
            metadata      = metadataScore,
            pixel         = pixelScore,
            temporal      = temporalScore,
            audio         = audioScore,
            face          = faceScore,
            physiological = physiologicalScore
        )
        val totalTime = System.currentTimeMillis() - startTime
        val result = scoreFusionEngine.fuse(
            scores               = moduleScores,
            mode                 = mode,
            videoMetadata        = videoMetadata,
            videoId              = videoId,
            videoPath            = uri.toString(),
            videoName            = uri.lastPathSegment ?: "Vidéo",
            totalProcessingTimeMs = totalTime
        )

        // Étape 6 : Persistance
        onProgress(AnalysisStep.SAVING, 0.98f)
        analysisDao.insert(result.toEntity(videoHash))

        result
    }

    // ─── Accès historique ─────────────────────────────────────────────────────

    suspend fun getById(id: String): AnalysisResult? =
        analysisDao.getById(id)?.toModel()

    suspend fun deleteResult(id: String) =
        analysisDao.deleteById(id)

    suspend fun clearHistory() =
        analysisDao.deleteAll()

    suspend fun getStats(): HistoryStats {
        val counts   = analysisDao.verdictStats()
        val total    = analysisDao.count()
        val avgScore = analysisDao.averageScoreSince(
            System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        ) ?: 0f
        return HistoryStats(
            total       = total,
            fakeCounts  = counts.firstOrNull { it.verdict == VerdictLevel.LIKELY_FAKE.name }?.count ?: 0,
            realCounts  = counts.firstOrNull { it.verdict == VerdictLevel.LIKELY_REAL.name }?.count ?: 0,
            avgScore30d = avgScore
        )
    }

    // ─── Hash vidéo ───────────────────────────────────────────────────────────

    private suspend fun computeVideoHash(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext uri.toString().hashCode().toString()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytes = 0L
            while (inputStream.read(buffer).also { bytesRead = it } != -1 && totalBytes < 524288L) {
                digest.update(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }
            inputStream.close()
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            uri.toString().hashCode().toString()
        }
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private fun AnalysisResult.toEntity(hash: String) = AnalysisResultEntity(
        id                 = videoId,
        videoUri           = videoPath,
        videoHash          = hash,
        globalScore        = overallScore,
        confidence         = confidenceScore,
        verdict            = verdictLevel.name,
        reliability        = reliabilityLevel.name,
        mode               = analysisMode.name,
        explanation        = explanation,
        keyFindings        = gson.toJson(keyFindings),
        warnings           = gson.toJson(warnings),
        pixelScore         = moduleScores.pixel?.score,
        audioScore         = moduleScores.audio?.score,
        faceScore          = moduleScores.face?.score,
        physiologicalScore = moduleScores.physiological?.score,
        temporalScore      = moduleScores.temporal?.score,
        metadataScore      = moduleScores.metadata?.score,
        durationMs         = totalProcessingTimeMs,
        analyzedAt         = timestamp,
        thumbnailPath      = null
    )

    private fun AnalysisResultEntity.toModel(): AnalysisResult? = try {
        val listType = object : TypeToken<List<String>>() {}.type
        AnalysisResult(
            videoId              = id,
            videoPath            = videoUri,
            videoName            = videoUri.substringAfterLast('/'),
            timestamp            = analyzedAt,
            overallScore         = globalScore,
            confidenceScore      = confidence,
            reliabilityLevel     = ReliabilityLevel.valueOf(reliability),
            verdictLevel         = VerdictLevel.valueOf(verdict),
            moduleScores         = ModuleScores(
                pixel         = pixelScore?.let { ModuleScore(score = it, confidence = 0.8f, details = emptyList(), anomalies = emptyList()) },
                audio         = audioScore?.let { ModuleScore(score = it, confidence = 0.8f, details = emptyList(), anomalies = emptyList()) },
                face          = faceScore?.let { ModuleScore(score = it, confidence = 0.8f, details = emptyList(), anomalies = emptyList()) },
                physiological = physiologicalScore?.let { ModuleScore(score = it, confidence = 0.7f, details = emptyList(), anomalies = emptyList()) },
                temporal      = temporalScore?.let { ModuleScore(score = it, confidence = 0.8f, details = emptyList(), anomalies = emptyList()) },
                metadata      = metadataScore?.let { ModuleScore(score = it, confidence = 0.9f, details = emptyList(), anomalies = emptyList()) }
            ),
            explanation          = explanation,
            keyFindings          = gson.fromJson(keyFindings, listType),
            warnings             = gson.fromJson(warnings, listType),
            analysisMode         = AnalysisMode.valueOf(mode),
            totalProcessingTimeMs = durationMs,
            videoMetadata        = VideoMetadata()
        )
    } catch (e: Exception) { null }
}

// ─── Modèles locaux ───────────────────────────────────────────────────────────

data class HistoryStats(
    val total: Int,
    val fakeCounts: Int,
    val realCounts: Int,
    val avgScore30d: Float
)

enum class AnalysisStep {
    METADATA, PIXEL, AUDIO, FACE, FUSION, SAVING
}
