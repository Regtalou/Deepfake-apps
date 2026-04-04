package com.deepfakedetector.analysis

import com.deepfakedetector.data.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * ScoreFusionEngine — Fusion intelligente des scores multi-modules
 *
 * Algorithme :
 * 1. Pondération dynamique (ajustée selon disponibilité + fiabilité)
 * 2. Score global = moyenne pondérée par confiance
 * 3. Détection d'anomalies critiques (surcharge du score)
 * 4. Génération explication + findings clés
 * 5. Calibration anti-biais (éviter faux positifs systématiques)
 */
@Singleton
class ScoreFusionEngine @Inject constructor() {

    // Poids de base des modules (total = 1.0)
    data class ModuleWeights(
        val metadata: Float = 0.12f,
        val pixel: Float = 0.25f,
        val temporal: Float = 0.20f,
        val audio: Float = 0.15f,
        val face: Float = 0.18f,
        val physiological: Float = 0.10f
    ) {
        fun toMap() = mapOf(
            "metadata" to metadata,
            "pixel" to pixel,
            "temporal" to temporal,
            "audio" to audio,
            "face" to face,
            "physiological" to physiological
        )
    }

    // Poids adaptatifs selon le mode
    private val weightsByMode = mapOf(
        AnalysisMode.INSTANT to ModuleWeights(
            metadata = 0.30f, pixel = 0.40f, temporal = 0.20f,
            audio = 0.10f, face = 0.0f, physiological = 0.0f
        ),
        AnalysisMode.FAST to ModuleWeights(
            metadata = 0.15f, pixel = 0.25f, temporal = 0.22f,
            audio = 0.18f, face = 0.15f, physiological = 0.05f
        ),
        AnalysisMode.COMPLETE to ModuleWeights(
            metadata = 0.12f, pixel = 0.22f, temporal = 0.18f,
            audio = 0.15f, face = 0.18f, physiological = 0.15f
        )
    )

    fun fuse(
        scores: ModuleScores,
        mode: AnalysisMode,
        videoMetadata: VideoMetadata,
        videoId: String,
        videoPath: String,
        videoName: String,
        totalProcessingTimeMs: Long
    ): AnalysisResult {

        val baseWeights = weightsByMode[mode] ?: ModuleWeights()

        // ─── ÉTAPE 1 : Redistribution des poids pour modules absents ──
        val activeWeights = computeActiveWeights(scores, baseWeights)

        // ─── ÉTAPE 2 : Calcul score pondéré par confiance ─────────────
        var weightedScoreSum = 0f
        var totalEffectiveWeight = 0f

        data class ModuleContrib(val name: String, val score: Float, val weight: Float, val conf: Float)
        val contributions = mutableListOf<ModuleContrib>()

        fun addModule(name: String, module: ModuleScore?, weight: Float) {
            if (module != null && weight > 0f) {
                val effectiveWeight = weight * module.confidence
                weightedScoreSum += module.score * effectiveWeight
                totalEffectiveWeight += effectiveWeight
                contributions.add(ModuleContrib(name, module.score, weight, module.confidence))
            }
        }

        addModule("metadata", scores.metadata, activeWeights.metadata)
        addModule("pixel", scores.pixel, activeWeights.pixel)
        addModule("temporal", scores.temporal, activeWeights.temporal)
        addModule("audio", scores.audio, activeWeights.audio)
        addModule("face", scores.face, activeWeights.face)
        addModule("physiological", scores.physiological, activeWeights.physiological)

        var globalScore = if (totalEffectiveWeight > 0f) {
            weightedScoreSum / totalEffectiveWeight
        } else 0.3f

        // ─── ÉTAPE 3 : Boosters critiques ─────────────────────────────
        // Certaines anomalies critiques doublent leur influence
        val criticalBoost = computeCriticalBoost(scores)
        globalScore = (globalScore + criticalBoost * 0.15f).coerceIn(0f, 1f)

        // ─── ÉTAPE 4 : Calibration anti-biais ─────────────────────────
        // Légère pression vers le centre pour les vidéos ambiguës
        globalScore = calibrate(globalScore, contributions.size)

        // ─── ÉTAPE 5 : Score de confiance global ──────────────────────
        val confidenceScore = computeGlobalConfidence(scores, contributions.size, mode)

        // ─── ÉTAPE 6 : Génération explication ─────────────────────────
        val explanation = generateExplanation(globalScore, scores, contributions, videoMetadata)
        val keyFindings = generateKeyFindings(scores, globalScore)
        val warnings = generateWarnings(scores, confidenceScore, contributions.size)

        val verdictLevel = AnalysisResult.computeVerdictLevel(globalScore)
        val reliabilityLevel = AnalysisResult.computeReliabilityLevel(confidenceScore)

        return AnalysisResult(
            videoId = videoId,
            videoPath = videoPath,
            videoName = videoName,
            overallScore = globalScore,
            confidenceScore = confidenceScore,
            reliabilityLevel = reliabilityLevel,
            verdictLevel = verdictLevel,
            moduleScores = scores,
            explanation = explanation,
            keyFindings = keyFindings,
            warnings = warnings,
            analysisMode = mode,
            totalProcessingTimeMs = totalProcessingTimeMs,
            videoMetadata = videoMetadata
        )
    }

    // ─────────────────────────────────────────────────────────
    // REDISTRIBUTION POIDS DYNAMIQUE
    // ─────────────────────────────────────────────────────────

    private fun computeActiveWeights(scores: ModuleScores, base: ModuleWeights): ModuleWeights {
        val activeMap = mutableMapOf(
            "metadata" to if (scores.metadata != null) base.metadata else 0f,
            "pixel" to if (scores.pixel != null) base.pixel else 0f,
            "temporal" to if (scores.temporal != null) base.temporal else 0f,
            "audio" to if (scores.audio != null) base.audio else 0f,
            "face" to if (scores.face != null) base.face else 0f,
            "physiological" to if (scores.physiological != null) base.physiological else 0f
        )

        val totalActive = activeMap.values.sum()
        if (totalActive <= 0f) return base

        // Renormaliser pour que la somme = 1.0
        val normalized = activeMap.mapValues { (_, v) -> v / totalActive }

        return ModuleWeights(
            metadata = normalized["metadata"] ?: 0f,
            pixel = normalized["pixel"] ?: 0f,
            temporal = normalized["temporal"] ?: 0f,
            audio = normalized["audio"] ?: 0f,
            face = normalized["face"] ?: 0f,
            physiological = normalized["physiological"] ?: 0f
        )
    }

    // ─────────────────────────────────────────────────────────
    // BOOST CRITIQUES
    // ─────────────────────────────────────────────────────────

    private fun computeCriticalBoost(scores: ModuleScores): Float {
        var boost = 0f
        val allModules = scores.allScores().values

        allModules.forEach { module ->
            module.anomalies
                .filter { it.severity == AnomalySeverity.CRITICAL }
                .forEach { _ -> boost += 0.5f }
        }

        // Boost si plusieurs modules très élevés simultanément
        val highScoreModules = allModules.count { it.score > 0.7f }
        if (highScoreModules >= 3) boost += 0.3f

        // Boost encodeur IA (signal très fort)
        val hasAiEncoderAnomaly = scores.metadata?.anomalies?.any {
            it.type == "AI_ENCODER_SIGNATURE"
        } == true
        if (hasAiEncoderAnomaly) boost += 0.8f

        return boost.coerceIn(0f, 1f)
    }

    // ─────────────────────────────────────────────────────────
    // CALIBRATION
    // ─────────────────────────────────────────────────────────

    private fun calibrate(score: Float, moduleCount: Int): Float {
        if (moduleCount < 2) {
            // Peu de modules actifs → pression forte vers l'incertitude
            return score * 0.7f + 0.3f * 0.5f
        }
        if (moduleCount < 4) {
            // Calibration légère
            return score * 0.85f + 0.5f * 0.15f
        }
        // Score plein
        return score
    }

    // ─────────────────────────────────────────────────────────
    // CONFIANCE GLOBALE
    // ─────────────────────────────────────────────────────────

    private fun computeGlobalConfidence(
        scores: ModuleScores,
        moduleCount: Int,
        mode: AnalysisMode
    ): Float {
        val moduleConfidences = scores.allScores().values.map { it.confidence }
        if (moduleConfidences.isEmpty()) return 0.20f

        val avgModuleConfidence = moduleConfidences.average().toFloat()

        // Pénalité si peu de modules actifs
        val moduleCoverageFactor = when (moduleCount) {
            0 -> 0.1f
            1 -> 0.4f
            2 -> 0.55f
            3 -> 0.70f
            4 -> 0.82f
            5 -> 0.90f
            else -> 0.95f
        }

        // Bonus mode complet
        val modeBonus = when (mode) {
            AnalysisMode.INSTANT -> 0.0f
            AnalysisMode.FAST -> 0.05f
            AnalysisMode.COMPLETE -> 0.10f
        }

        return (avgModuleConfidence * moduleCoverageFactor + modeBonus).coerceIn(0f, 1f)
    }

    // ─────────────────────────────────────────────────────────
    // GÉNÉRATION EXPLICATION
    // ─────────────────────────────────────────────────────────

    private fun generateExplanation(
        score: Float,
        scores: ModuleScores,
        contributions: List<Any>,
        metadata: VideoMetadata
    ): String {
        val sb = StringBuilder()

        when {
            score >= 0.75f -> {
                sb.appendLine("🔴 Cette vidéo présente de fortes caractéristiques d'une génération par intelligence artificielle.")
                sb.appendLine()
                sb.appendLine("Notre analyse multi-couches a identifié plusieurs indicateurs convergents :")
            }
            score >= 0.55f -> {
                sb.appendLine("🟡 Cette vidéo présente certains signes suspects qui pourraient indiquer une manipulation ou une génération par IA.")
                sb.appendLine()
                sb.appendLine("Les anomalies détectées sont les suivantes :")
            }
            score >= 0.45f -> {
                sb.appendLine("🟡 Les résultats sont ambigus. Nous ne pouvons pas conclure avec certitude.")
                sb.appendLine()
                sb.appendLine("Certains éléments attirent l'attention, mais ils ne sont pas suffisamment convergents :")
            }
            score >= 0.25f -> {
                sb.appendLine("🟢 Cette vidéo présente principalement les caractéristiques d'une vidéo authentique.")
                sb.appendLine()
                sb.appendLine("Quelques points méritent néanmoins attention :")
            }
            else -> {
                sb.appendLine("🟢 Cette vidéo présente toutes les caractéristiques d'une vidéo réelle et authentique.")
                sb.appendLine()
                sb.appendLine("Aucune anomalie significative n'a été détectée dans les modules analysés.")
            }
        }

        // Anomalies principales
        val allAnomalies = scores.allScores().values
            .flatMap { it.anomalies }
            .sortedByDescending { it.severity.weight }
            .take(4)

        if (allAnomalies.isNotEmpty()) {
            sb.appendLine()
            allAnomalies.forEach { anomaly ->
                val emoji = when (anomaly.severity) {
                    AnomalySeverity.CRITICAL -> "🚨"
                    AnomalySeverity.HIGH -> "⚠️"
                    AnomalySeverity.MEDIUM -> "📊"
                    AnomalySeverity.LOW -> "ℹ️"
                }
                sb.appendLine("$emoji ${anomaly.description}")
            }
        }

        // Contexte vidéo
        if (metadata.encoder != null) {
            sb.appendLine()
            sb.appendLine("Encodeur détecté : ${metadata.encoder}")
        }

        sb.appendLine()
        sb.appendLine("⚠️ Rappel : Cette analyse est automatisée et peut comporter des erreurs. Elle ne constitue pas une preuve légale.")

        return sb.toString().trim()
    }

    private fun generateKeyFindings(scores: ModuleScores, globalScore: Float): List<String> {
        val findings = mutableListOf<String>()

        // Top anomalies
        scores.allScores().forEach { (moduleName, module) ->
            if (module.score > 0.5f) {
                findings.add("Module $moduleName : ${(module.score * 100).toInt()}% suspect")
            }
            module.anomalies
                .filter { it.severity >= AnomalySeverity.HIGH }
                .take(2)
                .forEach { findings.add(it.description) }
        }

        if (findings.isEmpty()) {
            findings.add("Aucune anomalie significative détectée")
        }

        return findings.distinct().take(6)
    }

    private fun generateWarnings(
        scores: ModuleScores,
        confidence: Float,
        moduleCount: Int
    ): List<String> {
        val warnings = mutableListOf<String>()

        if (confidence < 0.5f) {
            warnings.add("⚠️ Confiance faible — résultat à interpréter avec précaution")
        }

        if (moduleCount < 3) {
            warnings.add("ℹ️ Analyse partielle — certains modules n'ont pas pu s'exécuter")
        }

        if (scores.face == null && scores.physiological == null) {
            warnings.add("ℹ️ Aucun visage détecté — analyse physiologique non disponible")
        }

        if (scores.audio == null) {
            warnings.add("ℹ️ Piste audio absente ou illisible — module audio ignoré")
        }

        warnings.add("ℹ️ Les deepfakes très récents peuvent contourner certains modules")
        warnings.add("ℹ️ Ce score ne constitue pas une preuve juridique")

        return warnings
    }
}

private operator fun AnomalySeverity.compareTo(other: AnomalySeverity): Int =
    this.weight.compareTo(other.weight)
