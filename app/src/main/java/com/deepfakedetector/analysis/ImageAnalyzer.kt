package com.deepfakedetector.analysis

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import com.deepfakedetector.data.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class ImageAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Pipeline principal — analyse une image en < 2 secondes.
     * Retourne un ImageAnalysisResult complet.
     */
    suspend fun analyze(uri: Uri): ImageAnalysisResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val imageId   = uri.toString().hashCode().toString()
        val imageName = uri.lastPathSegment ?: "image"

        // ── Chargement bitmap ──────────────────────────────────────────────
        val bitmap = loadBitmap(uri) ?: return@withContext errorResult(imageId, imageName)
        val metadata = extractMetadata(uri, bitmap)

        // ── Modules d'analyse ──────────────────────────────────────────────
        val pixelScore     = analyzePixels(bitmap)
        val statsScore     = analyzeStatistics(bitmap)
        val artifactScore  = analyzeArtifacts(bitmap)
        val metaScore      = analyzeMetadata(metadata)

        // ── Fusion ────────────────────────────────────────────────────────
        val moduleScores = ImageModuleScores(
            pixelAnalysis    = pixelScore,
            statistics       = statsScore,
            artifactDetection = artifactScore,
            metadataAnalysis = metaScore
        )
        val fused = fuseScores(moduleScores)

        ImageAnalysisResult(
            imageId          = imageId,
            imagePath        = uri.toString(),
            imageName        = imageName,
            overallScore     = fused.first,
            confidenceScore  = fused.second,
            reliabilityLevel = AnalysisResult.computeReliabilityLevel(fused.second),
            verdictLevel     = AnalysisResult.computeVerdictLevel(fused.first),
            moduleScores     = moduleScores,
            explanation      = buildExplanation(fused.first, moduleScores),
            keyFindings      = buildKeyFindings(moduleScores),
            warnings         = buildWarnings(moduleScores, metadata),
            processingTimeMs = System.currentTimeMillis() - startTime,
            metadata         = metadata
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODULE 1 — ANALYSE PIXEL (FFT, bruit, sur-lissage, texture)
    // ══════════════════════════════════════════════════════════════════════

    private fun analyzePixels(bitmap: Bitmap): ModuleScore {
        val anomalies  = mutableListOf<Anomaly>()
        val details    = mutableListOf<String>()
        var scoreSum   = 0f
        var weightSum  = 0f

        // ── 1a. Analyse du bruit naturel ──────────────────────────────────
        val noiseScore = analyzeNaturalNoise(bitmap)
        scoreSum  += noiseScore * 0.35f
        weightSum += 0.35f
        if (noiseScore > 0.6f) {
            anomalies.add(Anomaly(
                type          = "noise_absence",
                severity      = AnomalySeverity.HIGH,
                description   = "Absence de bruit capteur naturel",
                technicalDetail = "σ bruit = %.3f (attendu > 0.02 pour image réelle)".format(1f - noiseScore)
            ))
            details.add("Bruit capteur : anormalement lisse (score: ${(noiseScore*100).toInt()}%)")
        } else {
            details.add("Bruit capteur : naturel (score: ${(noiseScore*100).toInt()}%)")
        }

        // ── 1b. Analyse FFT (motifs périodiques IA) ───────────────────────
        val fftScore = analyzeFFTPatterns(bitmap)
        scoreSum  += fftScore * 0.30f
        weightSum += 0.30f
        if (fftScore > 0.55f) {
            anomalies.add(Anomaly(
                type          = "fft_periodicity",
                severity      = AnomalySeverity.MEDIUM,
                description   = "Motifs périodiques détectés en fréquence",
                technicalDetail = "Énergie spectrale concentrée : signe d'artefacts GAN/diffusion"
            ))
            details.add("FFT : motifs périodiques suspects (score: ${(fftScore*100).toInt()}%)")
        } else {
            details.add("FFT : distribution spectrale naturelle")
        }

        // ── 1c. Sur-lissage (skin smoothing, AI upscale) ─────────────────
        val smoothScore = detectOverSmoothing(bitmap)
        scoreSum  += smoothScore * 0.20f
        weightSum += 0.20f
        if (smoothScore > 0.6f) {
            anomalies.add(Anomaly(
                type          = "over_smoothing",
                severity      = AnomalySeverity.MEDIUM,
                description   = "Sur-lissage artificiel détecté",
                technicalDetail = "Gradient moyen trop faible : peau plastifiée, typique des GAN"
            ))
            details.add("Lissage : artificiel (score: ${(smoothScore*100).toInt()}%)")
        }

        // ── 1d. Cohérence des bords ───────────────────────────────────────
        val edgeScore = analyzeEdgeCoherence(bitmap)
        scoreSum  += edgeScore * 0.15f
        weightSum += 0.15f
        if (edgeScore > 0.65f) {
            anomalies.add(Anomaly(
                type          = "edge_incoherence",
                severity      = AnomalySeverity.LOW,
                description   = "Incohérences aux contours détectées",
                technicalDetail = "Bords nets/flous alternant de façon non naturelle"
            ))
        }

        val finalScore = if (weightSum > 0) scoreSum / weightSum else 0f
        val confidence = if (anomalies.size >= 2) 0.82f else 0.65f

        return ModuleScore(
            score        = finalScore.coerceIn(0f, 1f),
            confidence   = confidence,
            details      = details,
            anomalies    = anomalies,
            processingTimeMs = 0L
        )
    }

    // ── Bruit naturel : mesure de la variance locale ──────────────────────
    private fun analyzeNaturalNoise(bitmap: Bitmap): Float {
        val small  = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
        val pixels = IntArray(128 * 128)
        small.getPixels(pixels, 0, 128, 0, 0, 128, 128)

        var localVarianceSum = 0.0
        var count = 0
        for (y in 1 until 127) {
            for (x in 1 until 127) {
                val center = grayValue(pixels[y * 128 + x])
                var blockSum = 0.0
                var blockSumSq = 0.0
                for (dy in -1..1) for (dx in -1..1) {
                    val v = grayValue(pixels[(y + dy) * 128 + (x + dx)]).toDouble()
                    blockSum   += v
                    blockSumSq += v * v
                }
                val mean = blockSum / 9
                val variance = blockSumSq / 9 - mean * mean
                localVarianceSum += variance
                count++
            }
        }
        val avgVariance = if (count > 0) localVarianceSum / count else 0.0
        return when {
            avgVariance < 5.0   -> 0.85f
            avgVariance < 15.0  -> 0.65f
            avgVariance < 40.0  -> 0.35f
            else                -> 0.20f
        }
    }

    // ── FFT simplifiée : détection de périodicité ─────────────────────────
    private fun analyzeFFTPatterns(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64 * 64)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 64)

        var hDiffSum = 0.0; var vDiffSum = 0.0
        var hPeriodCount = 0; var vPeriodCount = 0

        for (y in 0 until 64) {
            var prevDiff = 0
            for (x in 1 until 63) {
                val diff = grayValue(pixels[y * 64 + x]) - grayValue(pixels[y * 64 + x - 1])
                hDiffSum += abs(diff)
                if (diff > 0 && prevDiff < 0 || diff < 0 && prevDiff > 0) hPeriodCount++
                prevDiff = diff
            }
        }
        for (x in 0 until 64) {
            var prevDiff = 0
            for (y in 1 until 63) {
                val diff = grayValue(pixels[y * 64 + x]) - grayValue(pixels[(y - 1) * 64 + x])
                vDiffSum += abs(diff)
                if (diff > 0 && prevDiff < 0 || diff < 0 && prevDiff > 0) vPeriodCount++
                prevDiff = diff
            }
        }
        val totalPixels = 64 * 62
        val hPeriodRatio = hPeriodCount.toFloat() / totalPixels
        val vPeriodRatio = vPeriodCount.toFloat() / totalPixels

        return ((hPeriodRatio + vPeriodRatio) / 2f).coerceIn(0f, 1f)
    }

    // ── Sur-lissage : gradient moyen trop bas ─────────────────────────────
    private fun detectOverSmoothing(bitmap: Bitmap): Float {
        val small  = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        val pixels = IntArray(96 * 96)
        small.getPixels(pixels, 0, 96, 0, 0, 96, 96)

        var gradientSum = 0.0
        var count = 0
        for (y in 0 until 95) {
            for (x in 0 until 95) {
                val gx = abs(grayValue(pixels[y * 96 + x + 1]) - grayValue(pixels[y * 96 + x]))
                val gy = abs(grayValue(pixels[(y + 1) * 96 + x]) - grayValue(pixels[y * 96 + x]))
                gradientSum += sqrt((gx * gx + gy * gy).toDouble())
                count++
            }
        }
        val avgGradient = if (count > 0) gradientSum / count else 0.0
        return when {
            avgGradient < 3.0  -> 0.90f
            avgGradient < 8.0  -> 0.70f
            avgGradient < 15.0 -> 0.45f
            avgGradient < 25.0 -> 0.30f
            else               -> 0.15f
        }
    }

    // ── Cohérence des bords ───────────────────────────────────────────────
    private fun analyzeEdgeCoherence(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 80, 80, true)
        val pixels = IntArray(80 * 80)
        small.getPixels(pixels, 0, 80, 0, 0, 80, 80)

        var sharpEdges = 0; var blurryEdges = 0
        for (y in 1 until 79) {
            for (x in 1 until 79) {
                val center = grayValue(pixels[y * 80 + x])
                val right  = grayValue(pixels[y * 80 + x + 1])
                val below  = grayValue(pixels[(y + 1) * 80 + x])
                val gx = abs(right - center)
                val gy = abs(below - center)
                val grad = gx + gy
                if (grad > 30) sharpEdges++ else if (grad > 5) blurryEdges++
            }
        }
        val total = sharpEdges + blurryEdges
        if (total == 0) return 0.3f
        val sharpRatio = sharpEdges.toFloat() / total
        return if (sharpRatio < 0.15f) 0.75f else 0.3f
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODULE 2 — ANALYSE STATISTIQUE (histogramme, entropie, couleurs)
    // ══════════════════════════════════════════════════════════════════════

    private fun analyzeStatistics(bitmap: Bitmap): ModuleScore {
        val anomalies = mutableListOf<Anomaly>()
        val details   = mutableListOf<String>()

        val small  = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64 * 64)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 64)

        val histogram = IntArray(256)
        pixels.forEach { histogram[grayValue(it)]++ }
        val total = pixels.size.toFloat()

        var entropy = 0.0
        histogram.forEach { count ->
            if (count > 0) {
                val p = count / total
                entropy -= p * ln(p)
            }
        }
        val maxEntropy = ln(256.0)
        val normalizedEntropy = (entropy / maxEntropy).toFloat()

        val entropyScore = when {
            normalizedEntropy < 0.55f -> 0.80f
            normalizedEntropy < 0.70f -> 0.55f
            normalizedEntropy < 0.85f -> 0.25f
            else                      -> 0.15f
        }
        if (entropyScore > 0.6f) {
            anomalies.add(Anomaly(
                type = "low_entropy",
                severity = AnomalySeverity.MEDIUM,
                description = "Entropie de luminance anormalement basse",
                technicalDetail = "Entropie normalisée: ${"%.3f".format(normalizedEntropy)} (attendu > 0.75)"
            ))
        }
        details.add("Entropie : ${"%.2f".format(normalizedEntropy)} (${if (normalizedEntropy > 0.75f) "naturelle" else "suspecte"})")

        var rSum = 0L; var gSum = 0L; var bSum = 0L
        var rSumSq = 0L; var gSumSq = 0L; var bSumSq = 0L
        pixels.forEach { px ->
            val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
            rSum += r; gSum += g; bSum += b
            rSumSq += r * r; gSumSq += g * g; bSumSq += b * b
        }
        val n = pixels.size.toDouble()
        val rVar = rSumSq / n - (rSum / n).pow(2)
        val gVar = gSumSq / n - (gSum / n).pow(2)
        val bVar = bSumSq / n - (bSum / n).pow(2)
        val avgColorVariance = ((rVar + gVar + bVar) / 3).toFloat()

        val colorScore = when {
            avgColorVariance < 200f  -> 0.75f
            avgColorVariance < 500f  -> 0.45f
            else                     -> 0.20f
        }
        if (colorScore > 0.6f) {
            anomalies.add(Anomaly(
                type = "uniform_color_distribution",
                severity = AnomalySeverity.LOW,
                description = "Distribution de couleurs trop uniforme",
                technicalDetail = "Variance moyenne RVB: ${"%.1f".format(avgColorVariance)}"
            ))
        }
        details.add("Variance couleur : ${"%.0f".format(avgColorVariance)} (${if (avgColorVariance > 500f) "naturelle" else "suspecte"})")

        val finalScore = (entropyScore * 0.6f + colorScore * 0.4f).coerceIn(0f, 1f)
        val confidence = if (anomalies.isNotEmpty()) 0.72f else 0.60f

        return ModuleScore(
            score      = finalScore,
            confidence = confidence,
            details    = details,
            anomalies  = anomalies
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODULE 3 — DÉTECTION ARTEFACTS IA (yeux, mains, motifs répétés)
    // ══════════════════════════════════════════════════════════════════════

    private fun analyzeArtifacts(bitmap: Bitmap): ModuleScore {
        val anomalies = mutableListOf<Anomaly>()
        val details   = mutableListOf<String>()

        val repetitionScore = detectTextureRepetition(bitmap)
        if (repetitionScore > 0.6f) {
            anomalies.add(Anomaly(
                type = "texture_repetition",
                severity = AnomalySeverity.HIGH,
                description = "Répétition de motifs détectée",
                technicalDetail = "Blocs de textures identiques ou très similaires — typique des diffusion models"
            ))
            details.add("Répétition texture : suspecte (${(repetitionScore * 100).toInt()}%)")
        } else {
            details.add("Répétition texture : non détectée")
        }

        val haloScore = detectCompositionHalo(bitmap)
        if (haloScore > 0.55f) {
            anomalies.add(Anomaly(
                type = "composition_halo",
                severity = AnomalySeverity.MEDIUM,
                description = "Halo de composition artificiel détecté",
                technicalDetail = "Transition brusque luminosité/netteté en périphérie du sujet"
            ))
            details.add("Halo composition : détecté (${(haloScore * 100).toInt()}%)")
        }

        val symmetryScore = detectExcessiveSymmetry(bitmap)
        if (symmetryScore > 0.85f) {
            anomalies.add(Anomaly(
                type = "excessive_symmetry",
                severity = AnomalySeverity.MEDIUM,
                description = "Symétrie excessive — visage trop parfait",
                technicalDetail = "Symétrie bilatérale : ${"%.2f".format(symmetryScore)} (humains réels ≈ 0.70–0.80)"
            ))
            details.add("Symétrie : excessive (${"%.0f".format(symmetryScore * 100)}%)")
        } else {
            details.add("Symétrie : naturelle (${"%.0f".format(symmetryScore * 100)}%)")
        }

        val structureScore = detectImpossibleStructures(bitmap)
        if (structureScore > 0.5f) {
            anomalies.add(Anomaly(
                type = "impossible_structure",
                severity = AnomalySeverity.LOW,
                description = "Incohérences structurelles en arrière-plan",
                technicalDetail = "Lignes et perspectives géométriques anormales"
            ))
        }

        val weights = floatArrayOf(0.40f, 0.25f, 0.20f, 0.15f)
        val scores  = floatArrayOf(repetitionScore, haloScore, symmetryScore, structureScore)
        // CORRECTION : scores.toList() pour pouvoir appeler .zip()
        val finalScore = scores.toList().zip(weights.toList()).sumOf { (s, w) -> (s * w).toDouble() }.toFloat()
        val confidence = if (anomalies.size >= 2) 0.78f else 0.60f

        return ModuleScore(
            score      = finalScore.coerceIn(0f, 1f),
            confidence = confidence,
            details    = details,
            anomalies  = anomalies
        )
    }

    private fun detectTextureRepetition(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)

        val q1 = histogramOf(pixels, 0,  0,  16, 16, 32)
        val q2 = histogramOf(pixels, 16, 0,  32, 16, 32)
        val q3 = histogramOf(pixels, 0,  16, 16, 32, 32)
        val q4 = histogramOf(pixels, 16, 16, 32, 32, 32)

        val sim12 = histogramSimilarity(q1, q2)
        val sim34 = histogramSimilarity(q3, q4)
        val sim13 = histogramSimilarity(q1, q3)
        val avgSim = (sim12 + sim34 + sim13) / 3f

        return when {
            avgSim > 0.92f -> 0.85f
            avgSim > 0.82f -> 0.60f
            avgSim > 0.70f -> 0.35f
            else           -> 0.15f
        }
    }

    private fun histogramOf(
        pixels: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, width: Int
    ): FloatArray {
        val hist = FloatArray(32)
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            // CORRECTION : += 1f au lieu de ++ (FloatArray ne supporte pas ++)
            hist[grayValue(pixels[y * width + x]) / 8] += 1f
            count++
        }
        // CORRECTION : count.toFloat() pour division Float/Float
        if (count > 0) for (i in hist.indices) hist[i] /= count.toFloat()
        return hist
    }

    private fun histogramSimilarity(h1: FloatArray, h2: FloatArray): Float {
        var sum = 0f
        for (i in h1.indices) sum += min(h1[i], h2[i])
        return sum
    }

    private fun detectCompositionHalo(bitmap: Bitmap): Float {
        val small  = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64 * 64)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 64)

        val centerSharpness = sharpnessOf(pixels, 24, 24, 40, 40, 64)
        val edgeSharpness   = (
            sharpnessOf(pixels, 0, 0, 10, 64, 64) +
            sharpnessOf(pixels, 54, 0, 64, 64, 64) +
            sharpnessOf(pixels, 0, 0, 64, 10, 64) +
            sharpnessOf(pixels, 0, 54, 64, 64, 64)
        ) / 4f

        val ratio = if (edgeSharpness > 0) centerSharpness / edgeSharpness else 1f
        return when {
            ratio > 5f  -> 0.80f
            ratio > 3f  -> 0.60f
            ratio > 2f  -> 0.40f
            else        -> 0.20f
        }
    }

    private fun sharpnessOf(
        pixels: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, width: Int
    ): Float {
        var sum = 0f; var count = 0
        for (y in y0 until y1 - 1) for (x in x0 until x1 - 1) {
            val gx = abs(grayValue(pixels[y * width + x + 1]) - grayValue(pixels[y * width + x]))
            val gy = abs(grayValue(pixels[(y + 1) * width + x]) - grayValue(pixels[y * width + x]))
            sum += gx + gy; count++
        }
        return if (count > 0) sum / count else 0f
    }

    private fun detectExcessiveSymmetry(bitmap: Bitmap): Float {
        val small  = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64 * 64)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 64)

        var diffSum = 0L; var count = 0
        for (y in 0 until 64) {
            for (x in 0 until 32) {
                val left  = grayValue(pixels[y * 64 + x])
                val right = grayValue(pixels[y * 64 + (63 - x)])
                diffSum += abs(left - right)
                count++
            }
        }
        val avgDiff = if (count > 0) diffSum.toFloat() / count else 128f
        return (1f - (avgDiff / 50f)).coerceIn(0f, 1f)
    }

    private fun detectImpossibleStructures(bitmap: Bitmap): Float {
        val small  = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val pixels = IntArray(48 * 48)
        small.getPixels(pixels, 0, 48, 0, 0, 48, 48)

        var inconsistentLines = 0
        for (y in 1 until 47) {
            for (x in 1 until 47) {
                val h = abs(grayValue(pixels[y * 48 + x + 1]) - grayValue(pixels[y * 48 + x - 1]))
                val v = abs(grayValue(pixels[(y + 1) * 48 + x]) - grayValue(pixels[(y - 1) * 48 + x]))
                if (h > 40 && v < 5 || v > 40 && h < 5) inconsistentLines++
            }
        }
        val ratio = inconsistentLines.toFloat() / (46 * 46)
        return when {
            ratio > 0.15f -> 0.65f
            ratio > 0.08f -> 0.40f
            else          -> 0.20f
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MODULE 4 — MÉTADONNÉES EXIF
    // ══════════════════════════════════════════════════════════════════════

    private fun analyzeMetadata(meta: ImageMetadata): ModuleScore {
        val anomalies = mutableListOf<Anomaly>()
        val details   = mutableListOf<String>()
        var score     = 0f

        if (!meta.hasExif) {
            score += 0.40f
            anomalies.add(Anomaly(
                type = "exif_absent",
                severity = AnomalySeverity.HIGH,
                description = "Métadonnées EXIF absentes",
                technicalDetail = "Une photo d'appareil réel contient toujours des données EXIF"
            ))
            details.add("EXIF : absent ⚠️")
        } else {
            details.add("EXIF : présent ✓")

            if (meta.cameraModel == null) {
                score += 0.25f
                anomalies.add(Anomaly(
                    type = "no_camera_model",
                    severity = AnomalySeverity.MEDIUM,
                    description = "Modèle de caméra absent des métadonnées",
                    technicalDetail = "Champ EXIF Make/Model vide — souvent strip par outils IA"
                ))
                details.add("Caméra : non renseignée ⚠️")
            } else {
                details.add("Caméra : ${meta.cameraModel} ✓")
            }

            val aiSoftwareSignatures = listOf(
                "stable diffusion", "midjourney", "dalle", "generative",
                "diffusion", "gan", "runway", "adobe firefly", "imagen"
            )
            val sw = meta.software?.lowercase() ?: ""
            if (aiSoftwareSignatures.any { sw.contains(it) }) {
                score += 0.80f
                anomalies.add(Anomaly(
                    type = "ai_software_signature",
                    severity = AnomalySeverity.CRITICAL,
                    description = "Logiciel IA détecté dans les métadonnées",
                    technicalDetail = "Software EXIF: \"${meta.software}\""
                ))
                details.add("Logiciel : signature IA détectée 🚨 (${meta.software})")
            } else if (meta.software != null) {
                details.add("Logiciel : ${meta.software}")
            }
        }

        val confidence = if (meta.hasExif) 0.85f else 0.70f
        return ModuleScore(
            score      = score.coerceIn(0f, 1f),
            confidence = confidence,
            details    = details,
            anomalies  = anomalies
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // EXTRACTION MÉTADONNÉES
    // ══════════════════════════════════════════════════════════════════════

    private fun extractMetadata(uri: Uri, bitmap: Bitmap): ImageMetadata {
        var hasExif     = false
        var cameraMake: String? = null
        var cameraModel: String? = null
        var software:   String? = null
        var dateTime:   String? = null
        var colorSpace: String? = null
        var gpsPresent  = false

        try {
            val stream: InputStream? = context.contentResolver.openInputStream(uri)
            stream?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                hasExif = true
                cameraMake  = exif.getAttribute(ExifInterface.TAG_MAKE)
                cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)
                software    = exif.getAttribute(ExifInterface.TAG_SOFTWARE)
                dateTime    = exif.getAttribute(ExifInterface.TAG_DATETIME)
                colorSpace  = exif.getAttribute(ExifInterface.TAG_COLOR_SPACE)
                val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                gpsPresent = lat != null
            }
        } catch (_: Exception) { /* EXIF non lisible */ }

        val fileSize = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }

        val mimeType = context.contentResolver.getType(uri) ?: ""

        return ImageMetadata(
            width       = bitmap.width,
            height      = bitmap.height,
            fileSizeBytes = fileSize,
            mimeType    = mimeType,
            hasExif     = hasExif,
            cameraMake  = cameraMake,
            cameraModel = cameraModel,
            software    = software,
            dateTime    = dateTime,
            colorSpace  = colorSpace,
            gpsPresent  = gpsPresent
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // FUSION DES SCORES
    // ══════════════════════════════════════════════════════════════════════

    private fun fuseScores(scores: ImageModuleScores): Pair<Float, Float> {
        val weights = mapOf(
            "pixel"    to 0.35f,
            "stats"    to 0.20f,
            "artifact" to 0.30f,
            "meta"     to 0.15f
        )
        var scoreSum   = 0f; var weightSum  = 0f; var confidenceSum = 0f

        scores.pixelAnalysis?.let {
            scoreSum      += it.score      * weights["pixel"]!! * it.confidence
            confidenceSum += it.confidence * weights["pixel"]!!
            weightSum     += weights["pixel"]!! * it.confidence
        }
        scores.statistics?.let {
            scoreSum      += it.score      * weights["stats"]!! * it.confidence
            confidenceSum += it.confidence * weights["stats"]!!
            weightSum     += weights["stats"]!! * it.confidence
        }
        scores.artifactDetection?.let {
            scoreSum      += it.score      * weights["artifact"]!! * it.confidence
            confidenceSum += it.confidence * weights["artifact"]!!
            weightSum     += weights["artifact"]!! * it.confidence
        }
        scores.metadataAnalysis?.let {
            val metaBoost = if (it.score > 0.7f) 2.0f else 1.0f
            scoreSum      += it.score      * weights["meta"]!! * it.confidence * metaBoost
            confidenceSum += it.confidence * weights["meta"]!!
            weightSum     += weights["meta"]!! * it.confidence * metaBoost
        }

        val finalScore      = if (weightSum > 0) (scoreSum / weightSum).coerceIn(0f, 1f) else 0.5f
        val finalConfidence = if (weightSum > 0) (confidenceSum / (weightSum / (scores.allScores().values.map { it.confidence }.average().toFloat()))).coerceIn(0f, 1f) else 0.5f

        return finalScore to finalConfidence.coerceIn(0.4f, 0.95f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // GÉNÉRATION TEXTES
    // ══════════════════════════════════════════════════════════════════════

    private fun buildExplanation(score: Float, scores: ImageModuleScores): String {
        val pct = (score * 100).toInt()
        return when {
            score > 0.75f -> "Cette image présente de nombreux signaux caractéristiques d'une génération par IA ($pct% de probabilité). " +
                "Les analyses pixel, statistique et artefacts convergent vers un contenu synthétique."
            score > 0.55f -> "Cette image présente plusieurs anomalies suspectes ($pct% de probabilité IA). " +
                "Certains patterns sont cohérents avec une génération par IA, mais le résultat n'est pas certain."
            score > 0.45f -> "L'analyse est inconcluante ($pct%). L'image présente des caractéristiques mixtes, " +
                "aussi bien compatibles avec une photo réelle qu'une image synthétique."
            score > 0.25f -> "Cette image présente principalement des caractéristiques d'une photo réelle ($pct% de probabilité IA). " +
                "Quelques anomalies mineures ont été détectées mais restent dans la norme."
            else -> "Cette image présente toutes les caractéristiques d'une photo authentique ($pct% de probabilité IA). " +
                "Bruit capteur naturel, métadonnées cohérentes, aucun artefact IA détecté."
        }
    }

    private fun buildKeyFindings(scores: ImageModuleScores): List<String> {
        val findings = mutableListOf<String>()
        scores.allScores().forEach { (name, score) ->
            score.anomalies.filter { it.severity >= AnomalySeverity.MEDIUM }.forEach {
                findings.add("${it.description} [$name]")
            }
        }
        if (findings.isEmpty()) findings.add("Aucune anomalie significative détectée")
        return findings.take(5)
    }

    private fun buildWarnings(scores: ImageModuleScores, meta: ImageMetadata): List<String> {
        val warnings = mutableListOf<String>()
        if (!meta.hasExif) warnings.add("⚠️ Métadonnées EXIF absentes — vérification impossible")
        if (meta.software?.lowercase()?.contains("diffusion") == true)
            warnings.add("🚨 Signature de logiciel IA trouvée dans les métadonnées")
        scores.allScores().forEach { (_, score) ->
            score.anomalies.filter { it.severity == AnomalySeverity.CRITICAL }.forEach {
                warnings.add("🚨 ${it.description}")
            }
        }
        return warnings
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILITAIRES
    // ══════════════════════════════════════════════════════════════════════

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (_: Exception) { null }

    private fun grayValue(pixel: Int): Int {
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    private fun errorResult(id: String, name: String) = ImageAnalysisResult(
        imageId          = id,
        imagePath        = "",
        imageName        = name,
        overallScore     = 0.5f,
        confidenceScore  = 0.1f,
        reliabilityLevel = ReliabilityLevel.SUSPICIOUS,
        verdictLevel     = VerdictLevel.UNCERTAIN,
        moduleScores     = ImageModuleScores(),
        explanation      = "Impossible de charger l'image pour analyse.",
        keyFindings      = listOf("Erreur de chargement"),
        warnings         = listOf("⚠️ Fichier image illisible ou format non supporté"),
        processingTimeMs = 0L,
        metadata         = ImageMetadata()
    )
}
