package com.deepfakedetector.analysis

import com.deepfakedetector.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class TextAnalyzer @Inject constructor() {

    /**
     * Analyse un texte et retourne un ModuleScore.
     * Détecte : patterns LLM, structure trop parfaite, ton alarmiste/manipulateur.
     */
    suspend fun analyze(text: String): ModuleScore = withContext(Dispatchers.Default) {
        if (text.isBlank()) {
            return@withContext ModuleScore(
                score      = 0.3f,
                confidence = 0.2f,
                details    = listOf("Texte vide ou trop court pour analyse"),
                anomalies  = emptyList()
            )
        }

        val anomalies = mutableListOf<Anomaly>()
        val details   = mutableListOf<String>()

        // ── Module A : Patterns LLM ──────────────────────────────────────
        val llmScore = detectLLMPatterns(text)
        if (llmScore > 0.6f) {
            anomalies.add(Anomaly(
                type            = "llm_patterns",
                severity        = AnomalySeverity.HIGH,
                description     = "Structure typique d'un texte généré par IA",
                technicalDetail = "Phrases longues et équilibrées, ponctuation parfaite, absence de familiarités naturelles"
            ))
        }
        details.add("Patterns LLM : ${(llmScore * 100).toInt()}%")

        // ── Module B : Diversité lexicale ────────────────────────────────
        val repetitionScore = detectRepetitions(text)
        if (repetitionScore > 0.55f) {
            anomalies.add(Anomaly(
                type            = "low_lexical_diversity",
                severity        = AnomalySeverity.MEDIUM,
                description     = "Diversité lexicale suspecte",
                technicalDetail = "Ratio type/token bas — réutilisation de mots caractéristique des LLM"
            ))
        }
        details.add("Diversité lexicale : ${if (repetitionScore < 0.4f) "naturelle" else "suspecte"} (${(repetitionScore * 100).toInt()}%)")

        // ── Module C : Ton émotionnel / alarmiste ────────────────────────
        val toneScore = detectManipulativeTone(text)
        if (toneScore > 0.5f) {
            anomalies.add(Anomaly(
                type            = "manipulative_tone",
                severity        = if (toneScore > 0.75f) AnomalySeverity.CRITICAL else AnomalySeverity.MEDIUM,
                description     = "Ton alarmiste ou manipulateur détecté",
                technicalDetail = "Mots à forte charge émotionnelle, urgence artificielle, appel à la peur"
            ))
        }
        details.add("Ton : ${classifyTone(toneScore)} (${(toneScore * 100).toInt()}%)")

        // ── Module D : Erreurs naturelles absentes ───────────────────────
        val perfectnessScore = detectArtificialPerfection(text)
        if (perfectnessScore > 0.65f) {
            anomalies.add(Anomaly(
                type            = "no_natural_errors",
                severity        = AnomalySeverity.LOW,
                description     = "Absence d'imperfections naturelles",
                technicalDetail = "Aucune abréviation, faute de frappe, hésitation — texte trop soigné"
            ))
        }
        details.add("Imperfections : ${if (perfectnessScore < 0.5f) "présentes (humain)" else "absentes (suspect)"}")

        // ── Fusion ───────────────────────────────────────────────────────
        val finalScore = (
            llmScore        * 0.35f +
            repetitionScore * 0.20f +
            toneScore       * 0.30f +
            perfectnessScore * 0.15f
        ).coerceIn(0f, 1f)

        val confidence = when {
            text.length > 300 -> 0.82f
            text.length > 100 -> 0.68f
            else              -> 0.50f
        }

        ModuleScore(
            score      = finalScore,
            confidence = confidence,
            details    = details,
            anomalies  = anomalies
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODULE A — Patterns LLM
    // ══════════════════════════════════════════════════════════════════════

    private fun detectLLMPatterns(text: String): Float {
        var score = 0f
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return 0.3f

        // LLM écrit des phrases longues et équilibrées
        val lengths = sentences.map { it.length.toFloat() }
        val avgLen  = lengths.average().toFloat()
        val stdDev  = sqrt(lengths.map { (it - avgLen).pow(2) }.average()).toFloat()
        val cv      = if (avgLen > 0) stdDev / avgLen else 0f // Coefficient de variation

        // CV faible → phrases très uniformes → LLM
        score += when {
            cv < 0.20f -> 0.80f
            cv < 0.35f -> 0.55f
            cv < 0.50f -> 0.35f
            else       -> 0.15f
        }

        // Phrases trop longues en moyenne (LLM aime les longues phrases)
        score += when {
            avgLen > 200f -> 0.70f
            avgLen > 120f -> 0.50f
            avgLen > 80f  -> 0.30f
            else          -> 0.10f
        }

        // Formules de transition LLM typiques
        val llmTransitions = listOf(
            "il est important de", "il convient de noter", "il est essentiel",
            "en outre", "par ailleurs", "cela étant dit", "néanmoins",
            "en conclusion", "pour résumer", "en effet",
            "it is important to", "it is worth noting", "furthermore",
            "moreover", "in conclusion", "that being said", "however",
            "additionally", "in summary"
        )
        val textLower = text.lowercase()
        val transitionCount = llmTransitions.count { textLower.contains(it) }
        score += (transitionCount * 0.15f).coerceAtMost(0.60f)

        return (score / 3f).coerceIn(0f, 1f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODULE B — Répétitions / diversité lexicale
    // ══════════════════════════════════════════════════════════════════════

    private fun detectRepetitions(text: String): Float {
        val words = tokenize(text)
        if (words.size < 10) return 0.3f

        val uniqueWords = words.toSet().size
        val typeTokenRatio = uniqueWords.toFloat() / words.size

        // Répétitions de n-grammes (3 mots)
        val trigrams = (0 until words.size - 2).map {
            "${words[it]} ${words[it+1]} ${words[it+2]}"
        }
        val uniqueTrigrams = trigrams.toSet().size
        val trigramRepetition = 1f - (uniqueTrigrams.toFloat() / trigrams.size.coerceAtLeast(1))

        return (
            (1f - typeTokenRatio) * 0.60f +
            trigramRepetition     * 0.40f
        ).coerceIn(0f, 1f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODULE C — Ton manipulateur / alarmiste
    // ══════════════════════════════════════════════════════════════════════

    private val alarmistKeywords = setOf(
        // Français
        "urgent", "choc", "scandale", "révélation", "explosif", "incroyable",
        "vous ne croirez pas", "partagez", "avant suppression", "censuré",
        "complot", "vérité cachée", "ils ne veulent pas", "wake up", "éveil",
        "danger", "catastrophe", "fin du monde", "ça commence", "prenez garde",
        "exclusive", "breaking", "alerte", "choquant", "interdit",
        // Anglais
        "shocking", "explosive", "bombshell", "censored", "conspiracy",
        "they don't want you to know", "share before deleted", "wake up",
        "mainstream media", "deep state", "plandemic", "hoax", "banned",
        "must watch", "truth revealed", "hidden truth"
    )

    private val emotionalManipulation = setOf(
        "peur", "terreur", "menace", "danger", "crise", "effondrement",
        "sauvez", "maintenant", "immédiatement", "vite", "trop tard",
        "fear", "threat", "crisis", "collapse", "save", "now", "immediately"
    )

    private fun detectManipulativeTone(text: String): Float {
        val textLower = text.lowercase()
        val words     = tokenize(textLower)

        val alarmistCount    = alarmistKeywords.count { textLower.contains(it) }
        val emotionalCount   = emotionalManipulation.count { textLower.contains(it) }

        // Majuscules excessives (cri)
        val upperRatio = text.count { it.isUpperCase() }.toFloat() /
            text.count { it.isLetter() }.coerceAtLeast(1)

        // Points d'exclamation excessifs
        val exclamationCount = text.count { it == '!' }
        val exclamationScore = (exclamationCount * 0.15f).coerceAtMost(0.60f)

        val alarmistScore  = (alarmistCount  * 0.12f).coerceAtMost(0.70f)
        val emotionalScore = (emotionalCount * 0.10f).coerceAtMost(0.50f)
        val upperScore     = when {
            upperRatio > 0.30f -> 0.80f
            upperRatio > 0.15f -> 0.50f
            upperRatio > 0.08f -> 0.25f
            else               -> 0.05f
        }

        return (alarmistScore + emotionalScore + upperScore + exclamationScore)
            .div(4f)
            .coerceIn(0f, 1f)
            .let { (it * 4f).coerceIn(0f, 1f) }  // renormaliser
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODULE D — Perfection artificielle
    // ══════════════════════════════════════════════════════════════════════

    private fun detectArtificialPerfection(text: String): Float {
        var humanScore = 0f  // présence d'imperfections humaines

        // Abréviations informelles
        val informalMarkers = listOf(
            "lol", "mdr", "wtf", "omg", "btw", "fyi", "imo",
            "je sais pas", "j'sais", "chuis", "c'est nul", "trop bien",
            "jsp", "jpp", "ptdr", "stp", "svp"
        )
        val textLower = text.lowercase()
        if (informalMarkers.any { textLower.contains(it) }) humanScore += 0.3f

        // Hésitations
        val hesitations = listOf("euh", "bah", "ben ", "enfin ", "quoi ", "hum")
        if (hesitations.any { textLower.contains(it) }) humanScore += 0.25f

        // Ponctuation imparfaite (pas de point final, double espace, etc.)
        if (!text.trimEnd().endsWith('.') && !text.trimEnd().endsWith('!') &&
            !text.trimEnd().endsWith('?')) humanScore += 0.15f

        // Phrases très courtes (style naturel)
        val sentences = splitSentences(text)
        if (sentences.any { it.trim().length < 20 }) humanScore += 0.20f

        // Plus les imperfections humaines sont présentes, moins le texte est suspect
        return (1f - humanScore).coerceIn(0f, 1f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILITAIRES
    // ══════════════════════════════════════════════════════════════════════

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("[.!?]+")).filter { it.trim().length > 5 }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-zàâäéèêëîïôöùûüç\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }

    private fun classifyTone(score: Float) = when {
        score > 0.75f -> "très alarmiste 🚨"
        score > 0.50f -> "suspect ⚠️"
        score > 0.30f -> "légèrement chargé"
        else          -> "neutre ✓"
    }
}
