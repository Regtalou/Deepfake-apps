package com.deepfakedetector.analysis

import android.net.Uri
import com.deepfakedetector.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * BacktestEngine — Système d'évaluation des performances
 *
 * Fonctionnalités :
 * 1. Évaluation sur dataset de référence (REAL + FAKE)
 * 2. Calcul métriques : Accuracy, Precision, Recall, F1, AUC-ROC
 * 3. Matrice de confusion complète
 * 4. Rapport détaillé avec recommandations
 * 5. Auto-calibration des seuils et poids
 * 6. Analyse erreurs (cas limites)
 */
@Singleton
class BacktestEngine @Inject constructor(
    private val videoAnalyzer: VideoAnalyzer,
    private val audioAnalyzer: AudioAnalyzer,
    private val faceAnalyzer: FaceAnalyzer,
    private val metadataAnalyzer: MetadataAnalyzer,
    private val scoreFusionEngine: ScoreFusionEngine
) {

    data class BacktestProgress(
        val currentIndex: Int,
        val total: Int,
        val currentVideoName: String,
        val completedResults: List<BacktestResult>,
        val isComplete: Boolean = false
    )

    // Seuil de décision FAKE/REAL (calibré automatiquement)
    private var decisionThreshold = 0.50f

    // ─────────────────────────────────────────────────────────
    // ÉVALUATION PRINCIPALE
    // ─────────────────────────────────────────────────────────

    fun runBacktest(
        videos: List<BacktestVideo>,
        mode: AnalysisMode = AnalysisMode.FAST
    ): Flow<BacktestProgress> = flow {

        val results = mutableListOf<BacktestResult>()
        val shuffled = videos.shuffled()  // Anti-biais d'ordre

        shuffled.forEachIndexed { idx, video ->
            emit(BacktestProgress(
                currentIndex = idx,
                total = videos.size,
                currentVideoName = video.label + " #${video.id}",
                completedResults = results.toList()
            ))

            try {
                val result = analyzeVideo(video, mode)
                results.add(result)
            } catch (e: Exception) {
                // Vidéo en erreur → exclure de l'évaluation
            }

            emit(BacktestProgress(
                currentIndex = idx + 1,
                total = videos.size,
                currentVideoName = video.label + " #${video.id}",
                completedResults = results.toList(),
                isComplete = idx == shuffled.size - 1
            ))
        }
    }

    private suspend fun analyzeVideo(video: BacktestVideo, mode: AnalysisMode): BacktestResult {
        val uri = video.uri
        val videoId = video.id
        val startTime = System.currentTimeMillis()

        // Métadonnées
        val (metadataScore, videoMetadata) = metadataAnalyzer.analyze(uri)

        // Pixel + Temporel
        val (pixelScore, temporalScore) = videoAnalyzer.analyze(uri, mode)

        // Audio
        val audioScore = audioAnalyzer.analyze(uri, mode)

        // Visage + Physiologique
        val facePair = faceAnalyzer.analyze(uri, mode)
        val faceScore = facePair?.first
        val physioScore = facePair?.second

        val moduleScores = ModuleScores(
            metadata = metadataScore,
            pixel = pixelScore,
            temporal = temporalScore,
            audio = audioScore,
            face = faceScore,
            physiological = physioScore
        )

        val totalTime = System.currentTimeMillis() - startTime

        val analysisResult = scoreFusionEngine.fuse(
            scores = moduleScores,
            mode = mode,
            videoMetadata = videoMetadata,
            videoId = videoId,
            videoPath = uri.toString(),
            videoName = video.label,
            totalProcessingTimeMs = totalTime
        )

        return BacktestResult(
            videoId = videoId,
            groundTruth = video.groundTruth,
            predictedScore = analysisResult.overallScore,
            predictedFake = analysisResult.overallScore >= decisionThreshold,
            analysisResult = analysisResult
        )
    }

    // ─────────────────────────────────────────────────────────
    // GÉNÉRATION RAPPORT
    // ─────────────────────────────────────────────────────────

    fun generateReport(
        results: List<BacktestResult>,
        weights: Map<String, Float> = emptyMap()
    ): BacktestReport {
        return BacktestReport(
            totalVideos = results.size,
            results = results,
            globalModuleWeights = weights
        )
    }

    // ─────────────────────────────────────────────────────────
    // AUTO-CALIBRATION DU SEUIL
    // ─────────────────────────────────────────────────────────

    /**
     * Cherche le seuil optimal minimisant les erreurs (max F1)
     */
    fun calibrateThreshold(results: List<BacktestResult>): CalibrationResult {
        val thresholds = (0..100).map { it / 100f }
        var bestF1 = 0f
        var bestThreshold = 0.5f
        val rocPoints = mutableListOf<ROCPoint>()

        thresholds.forEach { threshold ->
            val tp = results.count { it.groundTruth && it.predictedScore >= threshold }
            val tn = results.count { !it.groundTruth && it.predictedScore < threshold }
            val fp = results.count { !it.groundTruth && it.predictedScore >= threshold }
            val fn = results.count { it.groundTruth && it.predictedScore < threshold }

            val precision = if (tp + fp > 0) tp.toFloat() / (tp + fp) else 0f
            val recall = if (tp + fn > 0) tp.toFloat() / (tp + fn) else 0f
            val f1 = if (precision + recall > 0) 2 * precision * recall / (precision + recall) else 0f
            val tpr = recall
            val fpr = if (fp + tn > 0) fp.toFloat() / (fp + tn) else 0f

            rocPoints.add(ROCPoint(threshold, tpr, fpr, f1))

            if (f1 > bestF1) {
                bestF1 = f1
                bestThreshold = threshold
            }
        }

        // Calculer AUC-ROC par méthode trapèze
        val sortedRoc = rocPoints.sortedBy { it.fpr }
        var aucRoc = 0f
        for (i in 1 until sortedRoc.size) {
            val dFpr = sortedRoc[i].fpr - sortedRoc[i - 1].fpr
            val avgTpr = (sortedRoc[i].tpr + sortedRoc[i - 1].tpr) / 2f
            aucRoc += dFpr * avgTpr
        }

        // Appliquer le nouveau seuil
        decisionThreshold = bestThreshold

        return CalibrationResult(
            optimalThreshold = bestThreshold,
            bestF1 = bestF1,
            aucRoc = abs(aucRoc),
            rocPoints = rocPoints
        )
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE DES ERREURS
    // ─────────────────────────────────────────────────────────

    fun analyzeErrors(results: List<BacktestResult>): ErrorAnalysis {
        val falsePositives = results.filter { !it.groundTruth && it.predictedFake }
        val falseNegatives = results.filter { it.groundTruth && !it.predictedFake }

        // Scores moyens des erreurs
        val avgFPScore = if (falsePositives.isNotEmpty())
            falsePositives.map { it.predictedScore }.average().toFloat() else 0f
        val avgFNScore = if (falseNegatives.isNotEmpty())
            falseNegatives.map { it.predictedScore }.average().toFloat() else 0f

        // Modules ayant le plus contribué aux erreurs
        val fpModuleContrib = analyzeModuleContributionsInErrors(falsePositives)
        val fnModuleContrib = analyzeModuleContributionsInErrors(falseNegatives)

        return ErrorAnalysis(
            falsePositiveCount = falsePositives.size,
            falseNegativeCount = falseNegatives.size,
            avgFalsePositiveScore = avgFPScore,
            avgFalseNegativeScore = avgFNScore,
            fpModuleContributions = fpModuleContrib,
            fnModuleContributions = fnModuleContrib,
            edgeCases = identifyEdgeCases(results)
        )
    }

    private fun analyzeModuleContributionsInErrors(
        errorResults: List<BacktestResult>
    ): Map<String, Float> {
        if (errorResults.isEmpty()) return emptyMap()

        val contributions = mutableMapOf<String, MutableList<Float>>()

        errorResults.forEach { result ->
            result.analysisResult.moduleScores.allScores().forEach { (name, module) ->
                contributions.getOrPut(name) { mutableListOf() }.add(module.score)
            }
        }

        return contributions.mapValues { (_, scores) ->
            scores.average().toFloat()
        }
    }

    private fun identifyEdgeCases(results: List<BacktestResult>): List<EdgeCase> {
        return results
            .filter { result ->
                // Cas limites : score entre 0.40 et 0.60 (zone incertaine)
                result.predictedScore in 0.40f..0.60f
            }
            .map { result ->
                EdgeCase(
                    videoId = result.videoId,
                    score = result.predictedScore,
                    groundTruth = result.groundTruth,
                    isCorrect = result.groundTruth == result.predictedFake,
                    reason = "Score dans la zone d'incertitude (${(result.predictedScore * 100).toInt()}%)"
                )
            }
    }

    // ─────────────────────────────────────────────────────────
    // RAPPORT TEXTUEL COMPLET
    // ─────────────────────────────────────────────────────────

    fun formatReport(
        report: BacktestReport,
        calibration: CalibrationResult?,
        errorAnalysis: ErrorAnalysis
    ): String {
        val sb = StringBuilder()

        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("       RAPPORT BACKTEST DEEPFAKE")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()
        sb.appendLine("📊 MÉTRIQUES GLOBALES")
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("Vidéos évaluées  : ${report.totalVideos}")
        sb.appendLine("Accuracy         : ${(report.accuracy * 100).format(1)}%")
        sb.appendLine("Precision        : ${(report.precision * 100).format(1)}%")
        sb.appendLine("Recall           : ${(report.recall * 100).format(1)}%")
        sb.appendLine("F1-Score         : ${(report.f1Score * 100).format(1)}%")
        sb.appendLine("Faux positifs    : ${report.falsePositives} (${(report.falsePositiveRate * 100).format(1)}%)")
        sb.appendLine("Faux négatifs    : ${report.falseNegatives}")
        sb.appendLine()

        sb.appendLine("🔢 MATRICE DE CONFUSION")
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("               │ Prédit RÉEL │ Prédit FAKE")
        sb.appendLine("Réel  (RÉEL)   │    ${report.trueNegatives.toString().padStart(5)}    │     ${report.falsePositives.toString().padStart(5)}")
        sb.appendLine("Réel  (FAKE)   │    ${report.falseNegatives.toString().padStart(5)}    │     ${report.truePositives.toString().padStart(5)}")
        sb.appendLine()

        if (calibration != null) {
            sb.appendLine("📈 CALIBRATION")
            sb.appendLine("───────────────────────────────────────")
            sb.appendLine("Seuil optimal : ${(calibration.optimalThreshold * 100).format(0)}%")
            sb.appendLine("Meilleur F1   : ${(calibration.bestF1 * 100).format(1)}%")
            sb.appendLine("AUC-ROC       : ${calibration.aucRoc.format(3)}")
            sb.appendLine()
        }

        sb.appendLine("🔍 ANALYSE DES ERREURS")
        sb.appendLine("───────────────────────────────────────")
        sb.appendLine("Faux positifs (${errorAnalysis.falsePositiveCount}) — Score moyen : ${(errorAnalysis.avgFalsePositiveScore * 100).format(1)}%")
        sb.appendLine("Faux négatifs (${errorAnalysis.falseNegativeCount}) — Score moyen : ${(errorAnalysis.avgFalseNegativeScore * 100).format(1)}%")
        sb.appendLine("Cas limites   : ${errorAnalysis.edgeCases.size} vidéos dans la zone 40–60%")
        sb.appendLine()

        sb.appendLine("💡 RECOMMANDATIONS")
        sb.appendLine("───────────────────────────────────────")
        report.recommendations.forEach { sb.appendLine(it) }
        sb.appendLine()

        sb.appendLine("📦 CONTRIBUTION DES MODULES (FP)")
        errorAnalysis.fpModuleContributions.entries
            .sortedByDescending { it.value }
            .forEach { (name, score) ->
                sb.appendLine("  $name : ${(score * 100).format(1)}% (score moyen sur erreurs)")
            }

        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────
    // DATA CLASSES
    // ─────────────────────────────────────────────────────────

    data class ROCPoint(
        val threshold: Float,
        val tpr: Float,   // True Positive Rate
        val fpr: Float,   // False Positive Rate
        val f1: Float
    )

    data class CalibrationResult(
        val optimalThreshold: Float,
        val bestF1: Float,
        val aucRoc: Float,
        val rocPoints: List<ROCPoint>
    )

    data class EdgeCase(
        val videoId: String,
        val score: Float,
        val groundTruth: Boolean,
        val isCorrect: Boolean,
        val reason: String
    )

    data class ErrorAnalysis(
        val falsePositiveCount: Int,
        val falseNegativeCount: Int,
        val avgFalsePositiveScore: Float,
        val avgFalseNegativeScore: Float,
        val fpModuleContributions: Map<String, Float>,
        val fnModuleContributions: Map<String, Float>,
        val edgeCases: List<EdgeCase>
    )

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)
}
