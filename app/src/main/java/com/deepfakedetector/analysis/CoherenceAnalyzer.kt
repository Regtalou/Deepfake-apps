package com.deepfakedetector.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.deepfakedetector.data.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
// FEATURE MODELS
// ─────────────────────────────────────────────────────────────────────────────

data class ImageFeatures(
    val labels: List<String>,
    val objects: List<String>,
    val sceneType: String,          // "urban", "nature", "indoor", "outdoor"
    val mood: String,               // "calm", "chaotic", "violent", "neutral", "dark"
    val dominantColors: List<String>,
    val faceDetected: Boolean,
    val brightness: Float,
    val complexity: Float
)

data class TextFeatures(
    val keywords: List<String>,
    val sentiment: String,          // "neutral", "alarmist", "positive", "negative"
    val entities: List<String>,
    val intent: String,             // "informative", "panic", "emotional", "neutral"
    val violenceLevel: Float,
    val urgencyLevel: Float
)

data class CoherenceResult(
    val score: Double,              // 0-1 : plus bas = plus incohérent
    val level: String,              // "coherent", "uncertain", "incoherent"
    val explanation: String,
    val similarityScore: Double,
    val contextScore: Double,
    val intensityScore: Double
)

// ─────────────────────────────────────────────────────────────────────────────
// COHERENCE ANALYZER
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class CoherenceAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun analyze(
        imageUri: Uri,
        text: String,
        imageScore: Float
    ): ModuleScore = withContext(Dispatchers.Default) {
        val bitmap        = loadBitmap(imageUri)
        val imageFeatures = extractImageFeatures(bitmap)
        val textFeatures  = extractTextFeatures(text)
        val result        = analyze(imageFeatures, textFeatures)
        val anomalies     = buildAnomalies(result, imageFeatures, textFeatures, imageScore)
        val details       = buildDetails(result, imageFeatures, textFeatures)

        val manipulationScore = (1.0 - result.score).toFloat().coerceIn(0f, 1f)
        val confidence = when {
            text.length > 100 && bitmap != null -> 0.82f
            text.length > 30  || bitmap != null -> 0.65f
            else -> 0.45f
        }

        ModuleScore(
            score      = manipulationScore,
            confidence = confidence,
            details    = details,
            anomalies  = anomalies
        )
    }

    // ─── Analyse principale ───────────────────────────────────────────────────

    fun analyze(image: ImageFeatures, text: TextFeatures): CoherenceResult {
        val similarity = computeSimilarity(image, text)
        val context    = computeContextScore(image, text)
        val intensity  = computeIntensityScore(image, text)
        val finalScore = (similarity * 0.4) + (context * 0.3) + (intensity * 0.3)
        val level = when {
            finalScore > 0.75 -> "coherent"
            finalScore > 0.40 -> "uncertain"
            else              -> "incoherent"
        }
        return CoherenceResult(
            score           = finalScore.coerceIn(0.0, 1.0),
            level           = level,
            explanation     = generateExplanation(similarity, context, intensity),
            similarityScore = similarity,
            contextScore    = context,
            intensityScore  = intensity
        )
    }

    // ─── Étape 1 : Similarité sémantique ────────────────────────────────────

    fun computeSimilarity(image: ImageFeatures, text: TextFeatures): Double {
        if (text.keywords.isEmpty()) return 0.5

        val directMatches = image.labels.map { it.lowercase() }
            .intersect(text.keywords.map { it.lowercase() }.toSet())
        val directScore = directMatches.size.toDouble() / text.keywords.size.coerceAtLeast(1)

        val semanticMatches = countSemanticMatches(image.labels, text.keywords)
        val semanticScore   = semanticMatches.toDouble() / text.keywords.size.coerceAtLeast(1)

        val sceneScore = computeSceneTextAlignment(image.sceneType, text.entities, text.keywords)

        return ((directScore * 0.40) + (semanticScore * 0.40) + (sceneScore * 0.20)).coerceIn(0.0, 1.0)
    }

    private val semanticGroups = mapOf(
        "conflict"    to setOf("guerre", "war", "combat", "attaque", "attack", "violence", "émeute", "riot", "bataille"),
        "fire"        to setOf("feu", "incendie", "flamme", "flame", "brûle", "burning", "explosion", "blast"),
        "crowd"       to setOf("foule", "manifestation", "protest", "rassemblement", "gathering", "people", "masse"),
        "destruction" to setOf("destruction", "ruines", "ruins", "debris", "dégâts", "damage", "effondrement", "collapse"),
        "urgency"     to setOf("urgent", "alerte", "alert", "danger", "emergency", "crise", "crisis"),
        "nature"      to setOf("forêt", "forest", "montagne", "mountain", "mer", "sea", "parc", "park", "arbres", "trees"),
        "celebration" to setOf("fête", "celebration", "victoire", "victory", "succès", "success", "joie", "joy"),
        "daily_life"  to setOf("quotidien", "daily", "normal", "routine", "vie", "life", "travail", "work"),
        "person"      to setOf("personne", "person", "homme", "femme", "man", "woman", "people", "gens"),
        "political"   to setOf("politique", "political", "président", "president", "gouvernement", "government", "élection", "election")
    )

    private fun countSemanticMatches(imageLabels: List<String>, textKeywords: List<String>): Int {
        var count = 0
        val imageLower = imageLabels.map { it.lowercase() }
        val textLower  = textKeywords.map { it.lowercase() }
        for ((_, members) in semanticGroups) {
            if (imageLower.any { it in members } && textLower.any { it in members }) count++
        }
        return count
    }

    private fun computeSceneTextAlignment(sceneType: String, entities: List<String>, keywords: List<String>): Double {
        val allText = (entities + keywords).map { it.lowercase() }
        val urbanKeywords  = setOf("rue", "street", "ville", "city", "bâtiment", "building", "route", "road")
        val natureKeywords = setOf("forêt", "forest", "montagne", "mountain", "mer", "sea", "rivière", "river")
        val indoorKeywords = setOf("maison", "house", "salle", "room", "bureau", "office")
        return when {
            sceneType == "urban"  && allText.any { it in urbanKeywords }  -> 0.85
            sceneType == "nature" && allText.any { it in natureKeywords } -> 0.85
            sceneType == "indoor" && allText.any { it in indoorKeywords } -> 0.85
            sceneType == "urban"  && allText.any { it in natureKeywords } -> 0.20
            sceneType == "nature" && allText.any { it in urbanKeywords }  -> 0.20
            else -> 0.50
        }
    }

    // ─── Étape 2 : Cohérence du contexte ────────────────────────────────────

    fun computeContextScore(image: ImageFeatures, text: TextFeatures): Double {
        return when {
            text.intent == "panic"       && image.mood == "calm"    -> 0.10
            text.intent == "panic"       && image.mood == "neutral" -> 0.20
            text.sentiment == "alarmist" && image.mood == "calm"    -> 0.15
            text.sentiment == "alarmist" && image.mood == "neutral" -> 0.25
            text.intent == "emotional"   && image.mood == "calm"    -> 0.35
            text.sentiment == "positive" && image.mood == "chaotic" -> 0.30
            text.sentiment == "positive" && image.mood == "violent" -> 0.15
            text.intent == "panic"       && image.mood == "chaotic" -> 0.90
            text.intent == "panic"       && image.mood == "violent" -> 0.95
            text.sentiment == "alarmist" && image.mood == "chaotic" -> 0.85
            text.intent == "neutral"     && image.mood == "calm"    -> 0.95
            text.intent == "neutral"     && image.mood == "neutral" -> 0.90
            text.intent == "informative" && image.mood == "calm"    -> 0.88
            text.intent == "emotional"   && image.mood == "chaotic" -> 0.70
            text.sentiment == "negative" && image.mood == "violent" -> 0.75
            else -> 0.50
        }
    }

    // ─── Étape 3 : Cohérence de l'intensité ─────────────────────────────────

    fun computeIntensityScore(image: ImageFeatures, text: TextFeatures): Double {
        val imageLabelsLower = image.labels.map { it.lowercase() }
        var score = 0.80

        val violenceLabels = setOf("fire", "explosion", "fight", "blood", "weapon", "crowd", "destruction", "conflict")
        val textViolent       = text.violenceLevel > 0.6f
        val imageShowsViolence = imageLabelsLower.any { it in violenceLabels }

        if (textViolent && !imageShowsViolence)                          score -= 0.50
        if (text.urgencyLevel > 0.65f && image.mood in listOf("calm", "neutral") && image.brightness > 0.6f) score -= 0.40
        if (text.urgencyLevel > 0.65f && image.brightness < 0.35f)      score += 0.10
        if (textViolent && imageShowsViolence)                           score += 0.20

        val textMentionsPeople = text.keywords.any { it.lowercase() in
            setOf("personne", "person", "homme", "femme", "man", "woman", "président", "president", "victime", "victim") }
        if (textMentionsPeople && !image.faceDetected && "person" !in imageLabelsLower) score -= 0.20

        if (text.urgencyLevel > 0.7f && image.complexity < 0.2f) score -= 0.25

        return score.coerceIn(0.0, 1.0)
    }

    // ─── Génération d'explication ────────────────────────────────────────────

    fun generateExplanation(sim: Double, ctx: Double, int: Double): String = when {
        sim < 0.20 && ctx < 0.30 -> "Le texte ne correspond pas au contenu visuel et le ton est incohérent avec la scène. Manipulation probable."
        sim < 0.30               -> "Le texte ne correspond pas au contenu visuel."
        ctx < 0.20               -> "Le ton du texte est fortement incohérent avec l'ambiance de l'image."
        ctx < 0.30               -> "Le ton du texte est incohérent avec la scène."
        int < 0.20               -> "Le niveau de gravité décrit dans le texte ne correspond pas du tout à l'image."
        int < 0.30               -> "Le niveau de gravité décrit ne correspond pas à l'image."
        sim > 0.70 && ctx > 0.70 && int > 0.70 -> "Le contenu image et texte est cohérent. Aucune incohérence majeure détectée."
        else                     -> "Certains éléments concordent mais des incohérences mineures subsistent."
    }

    // ─── Extraction features image ────────────────────────────────────────────

    fun extractImageFeatures(bitmap: Bitmap?): ImageFeatures {
        if (bitmap == null) return ImageFeatures(
            labels = emptyList(), objects = emptyList(), sceneType = "unknown",
            mood = "neutral", dominantColors = emptyList(), faceDetected = false,
            brightness = 0.5f, complexity = 0.5f
        )

        val small  = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64 * 64)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 64)

        val avgBrightness = pixels.map { px ->
            (0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px)) / 255.0
        }.average().toFloat()

        var darkCount = 0; var redCount = 0; var greenCount = 0; var blueCount = 0
        pixels.forEach { px ->
            val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            if (lum < 60) darkCount++
            if (r > g + 40 && r > b + 40) redCount++
            if (g > r + 20 && g > b + 10) greenCount++
            if (b > r + 20 && b > g + 10) blueCount++
        }
        val n = pixels.size.toFloat()

        var gradSum = 0.0; var cnt = 0
        for (y in 0 until 63) for (x in 0 until 63) {
            val gx = abs(Color.red(pixels[y * 64 + x + 1]) - Color.red(pixels[y * 64 + x]))
            val gy = abs(Color.red(pixels[(y + 1) * 64 + x]) - Color.red(pixels[y * 64 + x]))
            gradSum += sqrt((gx * gx + gy * gy).toDouble()); cnt++
        }
        val complexity = ((gradSum / cnt) / 100.0).toFloat().coerceIn(0f, 1f)

        val darkRatio  = darkCount  / n
        val redRatio   = redCount   / n
        val greenRatio = greenCount / n

        val labels = mutableListOf<String>()
        if (redRatio > 0.25f && darkRatio > 0.3f) labels.addAll(listOf("fire", "conflict", "danger"))
        if (greenRatio > 0.30f)                   labels.addAll(listOf("nature", "trees", "outdoor"))
        if (darkRatio > 0.55f)                    labels.add("dark_scene")
        if (avgBrightness > 0.75f)                labels.addAll(listOf("daylight", "outdoor"))
        if (complexity > 0.6f)                    labels.addAll(listOf("crowd", "complex_scene"))
        if (complexity < 0.2f)                    labels.add("simple_scene")

        val mood = when {
            redRatio > 0.25f && complexity > 0.5f        -> "violent"
            complexity > 0.6f && darkRatio > 0.3f        -> "chaotic"
            avgBrightness < 0.35f                        -> "dark"
            avgBrightness > 0.65f && complexity < 0.35f -> "calm"
            else -> "neutral"
        }

        val sceneType = when {
            greenRatio > 0.25f                             -> "nature"
            darkRatio  > 0.6f                              -> "indoor"
            avgBrightness > 0.5f && complexity > 0.35f   -> "urban"
            else -> "outdoor"
        }

        val centerPixels = (20..43).flatMap { y -> (20..43).map { x -> pixels[y * 64 + x] } }
        val centerAvgR   = centerPixels.map { Color.red(it) }.average()
        val faceDetected = centerAvgR in 140.0..220.0 && complexity in 0.2f..0.6f

        val dominantColors = mutableListOf<String>()
        if (darkRatio  > 0.4f)        dominantColors.add("dark")
        if (redRatio   > 0.2f)        dominantColors.add("red")
        if (blueCount / n > 0.25f)    dominantColors.add("blue")
        if (greenRatio > 0.25f)       dominantColors.add("green")
        if (avgBrightness > 0.7f)     dominantColors.add("bright")

        return ImageFeatures(
            labels         = labels.distinct(),
            objects        = labels.distinct(),
            sceneType      = sceneType,
            mood           = mood,
            dominantColors = dominantColors,
            faceDetected   = faceDetected,
            brightness     = avgBrightness,
            complexity     = complexity
        )
    }

    // ─── Extraction features texte ────────────────────────────────────────────

    fun extractTextFeatures(text: String): TextFeatures {
        if (text.isBlank()) return TextFeatures(
            keywords = emptyList(), sentiment = "neutral", entities = emptyList(),
            intent = "neutral", violenceLevel = 0f, urgencyLevel = 0f
        )
        val lower = text.lowercase()

        val violenceKw = setOf("guerre","war","attaque","attack","explosion","bomb","bombe","tué","killed",
            "mort","dead","massacre","violence","incendie","fire","destruction","effondrement",
            "collapse","émeute","riot","terroriste","terrorist","arme","weapon","fusil","gun")
        val urgencyKw  = setOf("urgent","breaking","alerte","alert","maintenant","now","immédiatement",
            "immediately","vite","partagez","censuré","censored","choc","shocking","avant suppression")
        val calmKw     = setOf("paisible","peaceful","calme","calm","serein","beau","beautiful","normal","quotidien")

        val foundViolence = violenceKw.filter { lower.contains(it) }
        val foundUrgency  = urgencyKw.filter  { lower.contains(it) }
        val foundCalm     = calmKw.filter     { lower.contains(it) }
        val keywords      = (foundViolence + foundUrgency + foundCalm).distinct()

        val violenceLevel = (foundViolence.size * 0.15f).coerceIn(0f, 1f)
        val exclamations  = text.count { it == '!' }
        val upperRatio    = text.count { it.isUpperCase() }.toFloat() / text.count { it.isLetter() }.coerceAtLeast(1)
        val urgencyLevel  = ((foundUrgency.size * 0.15f) + (exclamations * 0.05f) + (upperRatio * 0.3f)).coerceIn(0f, 1f)

        val sentiment = when {
            violenceLevel > 0.4f || urgencyLevel > 0.5f -> "alarmist"
            foundCalm.size >= 2                          -> "positive"
            foundViolence.isNotEmpty()                   -> "negative"
            else                                         -> "neutral"
        }

        val intent = when {
            urgencyLevel > 0.6f                          -> "panic"
            violenceLevel > 0.3f && urgencyLevel > 0.3f -> "emotional"
            violenceLevel < 0.1f && urgencyLevel < 0.2f -> "informative"
            else                                         -> "neutral"
        }

        val locationHints = listOf("france","paris","usa","ukraine","israel","gaza","syrie","russie",
            "europe","afrique","asie","chine","china","iran","iraq")
        val entities = locationHints.filter { lower.contains(it) }

        return TextFeatures(
            keywords      = keywords.take(15),
            sentiment     = sentiment,
            entities      = entities,
            intent        = intent,
            violenceLevel = violenceLevel,
            urgencyLevel  = urgencyLevel
        )
    }

    // ─── Anomalies et détails ─────────────────────────────────────────────────

    private fun buildAnomalies(
        result: CoherenceResult,
        image: ImageFeatures,
        text: TextFeatures,
        imageScore: Float
    ): List<Anomaly> {
        val anomalies = mutableListOf<Anomaly>()

        if (result.similarityScore < 0.25) anomalies.add(Anomaly(
            type = "semantic_mismatch", severity = AnomalySeverity.HIGH,
            description = "Le texte ne correspond pas au contenu visuel",
            technicalDetail = "Similarité sémantique : ${"%.0f".format(result.similarityScore * 100)}%"
        ))
        if (result.contextScore < 0.25) anomalies.add(Anomaly(
            type = "tone_mismatch", severity = AnomalySeverity.HIGH,
            description = "Ton du texte incohérent avec l'ambiance de l'image",
            technicalDetail = "Image: ${image.mood} | Texte: ${text.intent} (${text.sentiment})"
        ))
        if (result.intensityScore < 0.25) anomalies.add(Anomaly(
            type = "intensity_mismatch", severity = AnomalySeverity.MEDIUM,
            description = "Niveau de gravité textuel incompatible avec l'image",
            technicalDetail = "Violence texte: ${"%.0f".format(text.violenceLevel * 100)}% | Scène: ${image.mood}"
        ))

        val realityKw = setOf("real","réel","vrai","true","proof","preuve","confirmed","confirmé",
            "footage","caught on camera","photo réelle","vraie photo")
        if (text.keywords.any { it.lowercase() in realityKw } && imageScore > 0.6f) {
            anomalies.add(Anomaly(
                type = "ia_presented_as_real", severity = AnomalySeverity.CRITICAL,
                description = "Image IA présentée comme une photo réelle",
                technicalDetail = "Score IA image : ${"%.0f".format(imageScore * 100)}%"
            ))
        }
        return anomalies
    }

    private fun buildDetails(result: CoherenceResult, image: ImageFeatures, text: TextFeatures): List<String> = listOf(
        "Similarité sémantique : ${"%.0f".format(result.similarityScore * 100)}% ${if (result.similarityScore > 0.5) "✓" else "⚠️"}",
        "Cohérence contexte : ${"%.0f".format(result.contextScore * 100)}% (image: ${image.mood} / texte: ${text.intent})",
        "Cohérence intensité : ${"%.0f".format(result.intensityScore * 100)}% (violence texte: ${"%.0f".format(text.violenceLevel * 100)}%)",
        "Scène : ${image.sceneType} | Mood : ${image.mood} | Luminosité : ${"%.0f".format(image.brightness * 100)}%",
        "Mots-clés : ${text.keywords.take(5).joinToString(", ").ifEmpty { "aucun" }}",
        "Verdict : ${result.level.uppercase()} — ${result.explanation}"
    )

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts) }
    } catch (_: Exception) { null }
}
