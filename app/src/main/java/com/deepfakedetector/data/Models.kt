package com.deepfakedetector.data

import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ============================================================
// ENUMS
// ============================================================

enum class ReliabilityLevel(val label: String, val emoji: String) {
    RELIABLE("Fiable", "🟢"),
    UNCERTAIN("Incertain", "🟡"),
    SUSPICIOUS("Suspect", "🔴")
}

enum class AnalysisMode(val label: String, val durationHint: String) {
    INSTANT("Instantané", "~2 sec"),
    FAST("Rapide", "5–10 sec"),
    COMPLETE("Complet", "15–30 sec")
}

enum class AnalysisStatus {
    IDLE, LOADING_VIDEO, EXTRACTING_FRAMES, ANALYZING_METADATA,
    ANALYZING_PIXELS, ANALYZING_AUDIO, ANALYZING_FACE,
    FUSING_SCORES, COMPLETED, ERROR
}

enum class VerdictLevel {
    REAL,        // < 15%
    LIKELY_REAL, // 15–30%
    UNCERTAIN,   // 30–45%
    LIKELY_FAKE, // 45–65%
    FAKE         // > 65%
}

enum class AnomalySeverity(val weight: Float) {
    LOW(0.3f), MEDIUM(0.6f), HIGH(0.85f), CRITICAL(1.0f)
}

// ============================================================
// CORE ANALYSIS MODELS
// ============================================================

@Serializable
data class Anomaly(
    val type: String,
    val severity: AnomalySeverity,
    val description: String,
    val technicalDetail: String = "",
    val frameIndex: Int? = null,
    val regionX: Float? = null,
    val regionY: Float? = null,
    val regionWidth: Float? = null,
    val regionHeight: Float? = null
)

@Serializable
data class ModuleScore(
    val score: Float,
    val confidence: Float,
    val details: List<String>,
    val anomalies: List<Anomaly>,
    val processingTimeMs: Long = 0L
) {
    val scorePercent: Int get() = (score * 100).toInt()
    val confidencePercent: Int get() = (confidence * 100).toInt()

    val isHighConfidence: Boolean get() = confidence >= 0.75f
    val isMediumConfidence: Boolean get() = confidence in 0.5f..0.75f
    val isLowConfidence: Boolean get() = confidence < 0.5f
}

@Serializable
data class ModuleScores(
    val metadata: ModuleScore? = null,
    val pixel: ModuleScore? = null,
    val temporal: ModuleScore? = null,
    val audio: ModuleScore? = null,
    val face: ModuleScore? = null,
    val physiological: ModuleScore? = null
) {
    fun allScores(): Map<String, ModuleScore> = buildMap {
        metadata?.let { put("Métadonnées", it) }
        pixel?.let { put("Pixel", it) }
        temporal?.let { put("Temporel", it) }
        audio?.let { put("Audio", it) }
        face?.let { put("Visage", it) }
        physiological?.let { put("Physiologie", it) }
    }

    fun hasAnyScore(): Boolean = allScores().isNotEmpty()
}

@Serializable
data class AnalysisResult(
    val videoId: String,
    val videoPath: String,
    val videoName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val overallScore: Float,
    val confidenceScore: Float,
    val reliabilityLevel: ReliabilityLevel,
    val verdictLevel: VerdictLevel,
    val moduleScores: ModuleScores,
    val explanation: String,
    val keyFindings: List<String>,
    val warnings: List<String>,
    val analysisMode: AnalysisMode,
    val totalProcessingTimeMs: Long,
    val videoMetadata: VideoMetadata,
    val communityReport: CommunityReport? = null
) {
    val overallScorePercent: Int get() = (overallScore * 100).toInt()
    val confidenceScorePercent: Int get() = (confidenceScore * 100).toInt()

    val verdictText: String get() = when (verdictLevel) {
        VerdictLevel.REAL        -> "Vidéo probablement RÉELLE"
        VerdictLevel.LIKELY_REAL -> "Vraisemblablement réelle"
        VerdictLevel.UNCERTAIN   -> "Résultat INCERTAIN"
        VerdictLevel.LIKELY_FAKE -> "Probablement générée par IA"
        VerdictLevel.FAKE        -> "Vidéo très probablement FAKE"
    }

    companion object {
        // CORRECTION : seuils abaissés pour être plus sensible aux images IA modernes
        fun computeVerdictLevel(score: Float) = when {
            score < 0.15f -> VerdictLevel.REAL
            score < 0.30f -> VerdictLevel.LIKELY_REAL
            score < 0.45f -> VerdictLevel.UNCERTAIN
            score < 0.65f -> VerdictLevel.LIKELY_FAKE
            else          -> VerdictLevel.FAKE
        }

        fun computeReliabilityLevel(confidence: Float) = when {
            confidence >= 0.75f -> ReliabilityLevel.RELIABLE
            confidence >= 0.50f -> ReliabilityLevel.UNCERTAIN
            else                -> ReliabilityLevel.SUSPICIOUS
        }
    }
}

@Serializable
data class VideoMetadata(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val codec: String = "",
    val bitrate: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val creationDate: String? = null,
    val encoder: String? = null,
    val hasAudioTrack: Boolean = false,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val rotationDegrees: Int = 0
) {
    val durationSeconds: Float get() = durationMs / 1000f
    val resolution: String get() = "${width}x${height}"
    val fpsFormatted: String get() = "%.1f fps".format(fps)
    val fileSizeMb: Float get() = fileSizeBytes / (1024f * 1024f)
}

// ============================================================
// COMMUNITY MODELS
// ============================================================

@Serializable
data class CommunityReport(
    val videoHash: String,
    val fakeVotes: Int = 0,
    val realVotes: Int = 0,
    val totalReports: Int = 0,
    val isViral: Boolean = false,
    val alreadyFlagged: Boolean = false,
    val flaggedCount: Int = 0,
    val communityScore: Float? = null
) {
    val communityVerdict: String? get() {
        if (fakeVotes + realVotes < 5) return null
        val fakeRatio = fakeVotes.toFloat() / (fakeVotes + realVotes)
        return when {
            fakeRatio > 0.7f -> "La communauté pense que c'est FAKE (${(fakeRatio * 100).toInt()}%)"
            fakeRatio < 0.3f -> "La communauté pense que c'est RÉEL (${((1 - fakeRatio) * 100).toInt()}%)"
            else -> "La communauté est partagée"
        }
    }
}

// ============================================================
// BACKTEST MODELS
// ============================================================

data class BacktestVideo(
    val id: String,
    val uri: android.net.Uri,
    val groundTruth: Boolean,
    val label: String = if (groundTruth) "FAKE" else "RÉEL"
)

data class BacktestResult(
    val videoId: String,
    val groundTruth: Boolean,
    val predictedScore: Float,
    val predictedFake: Boolean,
    val analysisResult: AnalysisResult
)

data class BacktestReport(
    val timestamp: Long = System.currentTimeMillis(),
    val totalVideos: Int,
    val results: List<BacktestResult>,
    val globalModuleWeights: Map<String, Float>
) {
    val truePositives: Int get() = results.count { it.groundTruth && it.predictedFake }
    val trueNegatives: Int get() = results.count { !it.groundTruth && !it.predictedFake }
    val falsePositives: Int get() = results.count { !it.groundTruth && it.predictedFake }
    val falseNegatives: Int get() = results.count { it.groundTruth && !it.predictedFake }

    val accuracy: Float get() {
        val correct = truePositives + trueNegatives
        return if (totalVideos > 0) correct.toFloat() / totalVideos else 0f
    }

    val precision: Float get() {
        val denom = truePositives + falsePositives
        return if (denom > 0) truePositives.toFloat() / denom else 0f
    }

    val recall: Float get() {
        val denom = truePositives + falseNegatives
        return if (denom > 0) truePositives.toFloat() / denom else 0f
    }

    val f1Score: Float get() {
        val denom = precision + recall
        return if (denom > 0) 2 * precision * recall / denom else 0f
    }

    val falsePositiveRate: Float get() {
        val denom = falsePositives + trueNegatives
        return if (denom > 0) falsePositives.toFloat() / denom else 0f
    }

    val recommendations: List<String> get() = buildList {
        if (falsePositiveRate > 0.2f)
            add("⚠️ Taux faux positifs élevé (${(falsePositiveRate * 100).toInt()}%) — Augmenter le seuil de détection")
        if (recall < 0.7f)
            add("⚠️ Recall faible (${(recall * 100).toInt()}%) — Baisser le seuil ou renforcer le module Visage")
        if (accuracy >= 0.85f)
            add("✅ Excellentes performances globales — Modèle bien calibré")
        if (f1Score < 0.75f)
            add("🔧 F1 insuffisant — Rééquilibrer les poids des modules")
    }
}

// ============================================================
// MULTI-MODAL MODELS (Image + Texte)
// ============================================================

data class MultiModalInput(
    val imageUri: android.net.Uri,
    val text: String,
    val sourcePlatform: String = "",
    val capturedAt: Long = System.currentTimeMillis()
)

@Serializable
data class MultiModalScores(
    val image: ModuleScore? = null,
    val text: ModuleScore? = null,
    val coherence: ModuleScore? = null
) {
    fun allScores(): Map<String, ModuleScore> = buildMap {
        image?.let     { put("Image", it) }
        text?.let      { put("Texte", it) }
        coherence?.let { put("Cohérence", it) }
    }
}

data class MultiModalResult(
    val id: String,
    val input: MultiModalInput,
    val timestamp: Long = System.currentTimeMillis(),
    val manipulationIndex: Float,
    val confidence: Float,
    val verdictLevel: VerdictLevel,
    val reliabilityLevel: ReliabilityLevel,
    val scores: MultiModalScores,
    val explanation: String,
    val keyFindings: List<String>,
    val warnings: List<String>,
    val processingTimeMs: Long
) {
    val manipulationIndexPercent: Int get() = (manipulationIndex * 100).toInt()
    val confidencePercent: Int get() = (confidence * 100).toInt()

    val verdictText: String get() = when (verdictLevel) {
        VerdictLevel.FAKE        -> "Contenu très probablement manipulé"
        VerdictLevel.LIKELY_FAKE -> "Contenu probablement manipulé"
        VerdictLevel.UNCERTAIN   -> "Manipulation incertaine"
        VerdictLevel.LIKELY_REAL -> "Contenu vraisemblablement authentique"
        VerdictLevel.REAL        -> "Contenu authentique"
    }
}

// ============================================================
// IMAGE MODELS
// ============================================================

enum class ContentType { VIDEO, IMAGE }

enum class ImageAnalysisStatus {
    IDLE, LOADING, ANALYZING_PIXELS, ANALYZING_STATISTICS,
    ANALYZING_ARTIFACTS, ANALYZING_METADATA, FUSING_SCORES, COMPLETED, ERROR
}

data class ImageMetadata(
    val width: Int = 0,
    val height: Int = 0,
    val fileSizeBytes: Long = 0L,
    val mimeType: String = "",
    val hasExif: Boolean = false,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val gpsPresent: Boolean = false,
    val software: String? = null,
    val dateTime: String? = null,
    val colorSpace: String? = null
) {
    val resolution: String get() = "${width}x${height}"
    val fileSizeKb: Float get() = fileSizeBytes / 1024f
    val megapixels: Float get() = (width * height) / 1_000_000f
}

@Serializable
data class ImageModuleScores(
    val pixelAnalysis: ModuleScore? = null,
    val statistics: ModuleScore? = null,
    val artifactDetection: ModuleScore? = null,
    val metadataAnalysis: ModuleScore? = null
) {
    fun allScores(): Map<String, ModuleScore> = buildMap {
        pixelAnalysis?.let { put("Analyse pixel", it) }
        statistics?.let { put("Statistiques", it) }
        artifactDetection?.let { put("Artefacts IA", it) }
        metadataAnalysis?.let { put("Métadonnées", it) }
    }
    fun hasAnyScore(): Boolean = allScores().isNotEmpty()
}

data class ImageAnalysisResult(
    val imageId: String,
    val imagePath: String,
    val imageName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val overallScore: Float,
    val confidenceScore: Float,
    val reliabilityLevel: ReliabilityLevel,
    val verdictLevel: VerdictLevel,
    val moduleScores: ImageModuleScores,
    val explanation: String,
    val keyFindings: List<String>,
    val warnings: List<String>,
    val processingTimeMs: Long,
    val metadata: ImageMetadata
) {
    val overallScorePercent: Int get() = (overallScore * 100).toInt()
    val confidenceScorePercent: Int get() = (confidenceScore * 100).toInt()

    val verdictText: String get() = when (verdictLevel) {
        VerdictLevel.REAL        -> "Image probablement RÉELLE"
        VerdictLevel.LIKELY_REAL -> "Vraisemblablement réelle"
        VerdictLevel.UNCERTAIN   -> "Résultat INCERTAIN"
        VerdictLevel.LIKELY_FAKE -> "Probablement générée par IA"
        VerdictLevel.FAKE        -> "Image très probablement générée par IA"
    }
}

sealed class DetectionContent {
    data class VideoContent(val result: AnalysisResult)   : DetectionContent()
    data class ImageContent(val result: ImageAnalysisResult) : DetectionContent()

    val overallScore: Float get() = when (this) {
        is VideoContent -> result.overallScore
        is ImageContent -> result.overallScore
    }
    val verdictLevel: VerdictLevel get() = when (this) {
        is VideoContent -> result.verdictLevel
        is ImageContent -> result.verdictLevel
    }
    val verdictText: String get() = when (this) {
        is VideoContent -> result.verdictText
        is ImageContent -> result.verdictText
    }
    val contentType: ContentType get() = when (this) {
        is VideoContent -> ContentType.VIDEO
        is ImageContent -> ContentType.IMAGE
    }
}

// ============================================================
// UI STATE MODELS
// ============================================================

data class AnalysisUiState(
    val status: AnalysisStatus = AnalysisStatus.IDLE,
    val currentStep: String = "",
    val stepProgress: Float = 0f,
    val result: AnalysisResult? = null,
    val imageResult: ImageAnalysisResult? = null,
    val multiModalResult: MultiModalResult? = null,
    val error: String? = null,
    val selectedMode: AnalysisMode = AnalysisMode.FAST
)

data class AnalysisProgress(
    val step: AnalysisStatus,
    val label: String,
    val progressFraction: Float
)

// ============================================================
// ROOM TYPE CONVERTERS
// ============================================================

class RoomConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromReliabilityLevel(value: ReliabilityLevel): String = value.name

    @TypeConverter
    fun toReliabilityLevel(value: String): ReliabilityLevel =
        ReliabilityLevel.valueOf(value)

    @TypeConverter
    fun fromVerdictLevel(value: VerdictLevel): String = value.name

    @TypeConverter
    fun toVerdictLevel(value: String): VerdictLevel =
        VerdictLevel.valueOf(value)

    @TypeConverter
    fun fromAnalysisMode(value: AnalysisMode): String = value.name

    @TypeConverter
    fun toAnalysisMode(value: String): AnalysisMode =
        AnalysisMode.valueOf(value)
}
