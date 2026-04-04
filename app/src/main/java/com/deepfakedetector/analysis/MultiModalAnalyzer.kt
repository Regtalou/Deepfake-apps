package com.deepfakedetector.analysis

import com.deepfakedetector.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class MultiModalAnalyzer @Inject constructor(
    private val imageAnalyzer: ImageAnalyzer,
    private val textAnalyzer: TextAnalyzer,
    private val coherenceAnalyzer: CoherenceAnalyzer
) {

    /**
     * Pipeline multi-modal complet.
     * Lance image + texte en parallèle, puis calcule la cohérence.
     */
    suspend fun analyze(
        input: MultiModalInput,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): MultiModalResult = withContext(Dispatchers.Default) {

        val startTime = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        // ── Étape 1 & 2 en parallèle : image + texte ─────────────────────
        onProgress(0.10f, "Analyse de l'image…")
        val imageResultDeferred = async { imageAnalyzer.analyze(input.imageUri) }

        onProgress(0.20f, "Analyse du texte…")
        val textScoreDeferred = async { textAnalyzer.analyze(input.text) }

        val imageResult = imageResultDeferred.await()
        val textScore   = textScoreDeferred.await()

        // ── Étape 3 : Cohérence image/texte ──────────────────────────────
        onProgress(0.75f, "Analyse de la cohérence…")
        val coherenceScore = coherenceAnalyzer.analyze(
            imageUri   = input.imageUri,
            text       = input.text,
            imageScore = imageResult.overallScore
        )

        // ── Fusion globale ────────────────────────────────────────────────
        onProgress(0.95f, "Calcul de l'indice de manipulation…")
        val scores = MultiModalScores(
            image     = imageResult.moduleScores.allScores().values
                .takeIf { it.isNotEmpty() }
                ?.let { modules ->
                    ModuleScore(
                        score      = imageResult.overallScore,
                        confidence = imageResult.confidenceScore,
                        details    = imageResult.keyFindings,
                        anomalies  = modules.flatMap { it.anomalies }
                    )
                },
            text      = textScore,
            coherence = coherenceScore
        )

        val fused = fuseScores(scores)

        val result = MultiModalResult(
            id                 = id,
            input              = input,
            manipulationIndex  = fused.first,
            confidence         = fused.second,
            verdictLevel       = AnalysisResult.computeVerdictLevel(fused.first),
            reliabilityLevel   = AnalysisResult.computeReliabilityLevel(fused.second),
            scores             = scores,
            explanation        = buildExplanation(fused.first, scores, imageResult),
            keyFindings        = buildKeyFindings(scores, imageResult),
            warnings           = buildWarnings(scores, imageResult),
            processingTimeMs   = System.currentTimeMillis() - startTime
        )

        onProgress(1.0f, "Analyse terminée ✓")
        result
    }

    // ══════════════════════════════════════════════════════════════════════
    // FUSION
    // ══════════════════════════════════════════════════════════════════════

    private fun fuseScores(scores: MultiModalScores): Pair<Float, Float> {
        // Poids officiels : image 30% | texte 20% | cohérence 50%
        // La cohérence a le poids le plus élevé car c'est le signal le plus discriminant
        val imageScore     = scores.image?.score     ?: 0.3f
        val textScore      = scores.text?.score      ?: 0.3f
        val coherenceScore = scores.coherence?.score ?: 0.3f

        val imageConf     = scores.image?.confidence     ?: 0.5f
        val textConf      = scores.text?.confidence      ?: 0.5f
        val coherenceConf = scores.coherence?.confidence ?: 0.5f

        var weightedScore = (
            imageScore     * 0.30f * imageConf     +
            textScore      * 0.20f * textConf       +
            coherenceScore * 0.50f * coherenceConf
        ) / (0.30f * imageConf + 0.20f * textConf + 0.50f * coherenceConf).coerceAtLeast(0.01f)

        // Boost critique : image IA présentée comme réelle
        val criticalAnomaly = scores.coherence?.anomalies
            ?.any { it.type == "ia_presented_as_real" } == true
        if (criticalAnomaly) weightedScore = (weightedScore + 0.20f).coerceAtMost(1.0f)

        // Boost si les 3 modules convergent vers fake
        if (imageScore > 0.6f && textScore > 0.6f && coherenceScore > 0.6f)
            weightedScore = (weightedScore + 0.10f).coerceAtMost(1.0f)

        val confidence = ((imageConf + textConf + coherenceConf) / 3f).coerceIn(0.4f, 0.92f)
        return weightedScore.coerceIn(0f, 1f) to confidence
    }

    // ══════════════════════════════════════════════════════════════════════
    // GÉNÉRATION TEXTES
    // ══════════════════════════════════════════════════════════════════════

    private fun buildExplanation(
        score: Float,
        scores: MultiModalScores,
        imageResult: ImageAnalysisResult
    ): String {
        val pct = (score * 100).toInt()
        val imagePct = (imageResult.overallScore * 100).toInt()
        val textPct  = ((scores.text?.score ?: 0f) * 100).toInt()
        val cohPct   = ((scores.coherence?.score ?: 0f) * 100).toInt()

        val hasCritical = scores.coherence?.anomalies
            ?.any { it.type == "ia_presented_as_real" } == true

        return when {
            hasCritical ->
                "🚨 ALERTE : Le texte affirme que l'image est réelle, mais l'analyse détecte une image générée par IA " +
                "(score image IA : $imagePct%). Cette combinaison est caractéristique de la désinformation intentionnelle. " +
                "Indice de manipulation global : $pct%."

            score > 0.75f ->
                "Ce post présente un indice de manipulation très élevé ($pct%). " +
                "Image : $imagePct% de probabilité IA. Texte : ton suspect à $textPct%. " +
                "Cohérence image/texte : $cohPct% d'anomalies. Les trois modules convergent vers un contenu fabriqué."

            score > 0.55f ->
                "Ce post présente plusieurs signaux suspects (indice : $pct%). " +
                "L'image ($imagePct% IA) combinée au texte ($textPct% suspect) forment un ensemble douteux. " +
                "Incohérences image/texte : $cohPct%."

            score > 0.45f ->
                "Le bilan est mitigé (indice : $pct%). Certains éléments sont suspects " +
                "mais aucune preuve formelle de manipulation n'a été trouvée. " +
                "Vérifier la source avant de partager."

            else ->
                "Ce post ne présente pas de signes évidents de manipulation ($pct%). " +
                "L'image, le texte et leur cohérence semblent authentiques."
        }
    }

    private fun buildKeyFindings(
        scores: MultiModalScores,
        imageResult: ImageAnalysisResult
    ): List<String> {
        val findings = mutableListOf<String>()

        // Résumé image
        val imagePct = (imageResult.overallScore * 100).toInt()
        findings.add(
            if (imageResult.overallScore > 0.6f)
                "⚠️ Image : $imagePct% de probabilité IA"
            else
                "✅ Image : $imagePct% — semble authentique"
        )

        // Résumé texte
        scores.text?.let { t ->
            val pct = (t.score * 100).toInt()
            findings.add(
                if (t.score > 0.6f) "⚠️ Texte : ton suspect à $pct%"
                else "✅ Texte : $pct% — langage naturel"
            )
            t.anomalies.take(2).forEach { findings.add("   → ${it.description}") }
        }

        // Résumé cohérence
        scores.coherence?.anomalies?.take(2)?.forEach {
            findings.add("❌ ${it.description}")
        }

        return findings.take(6)
    }

    private fun buildWarnings(
        scores: MultiModalScores,
        imageResult: ImageAnalysisResult
    ): List<String> {
        val warnings = mutableListOf<String>()

        if (scores.coherence?.anomalies?.any { it.type == "ia_presented_as_real" } == true) {
            warnings.add("🚨 Image IA présentée comme une photo réelle — manipulation intentionnelle probable")
        }
        if (scores.text?.anomalies?.any { it.type == "manipulative_tone" } == true) {
            warnings.add("⚠️ Contenu conçu pour déclencher une réaction émotionnelle")
        }
        if (scores.coherence?.anomalies?.any { it.type == "tone_mismatch" } == true) {
            warnings.add("⚠️ L'image et le texte ne racontent pas la même histoire")
        }
        if (imageResult.warnings.isNotEmpty()) {
            warnings.addAll(imageResult.warnings.take(2))
        }

        return warnings
    }
}
