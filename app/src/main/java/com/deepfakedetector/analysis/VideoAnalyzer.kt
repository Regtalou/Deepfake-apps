package com.deepfakedetector.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.deepfakedetector.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VideoAnalyzer — Analyse pixel, fréquentielle et temporelle
 *
 * Modules implémentés :
 * 1. Analyse histogramme (distribution couleurs)
 * 2. Analyse bruit numérique (signature GAN vs bruit naturel)
 * 3. Détection artefacts haute fréquence (FFT 2D simplifiée)
 * 4. Cohérence temporelle inter-frames (Optical Flow simplifié)
 * 5. Flicker temporel (variations luminosité anormales)
 * 6. Uniformité de texture (score trop lisse = GAN)
 */
@Singleton
class VideoAnalyzer @Inject constructor(
    private val context: Context
) {

    companion object {
        // Seuils de détection
        private const val NOISE_UNIFORMITY_THRESHOLD = 0.15f
        private const val HISTOGRAM_GAN_PEAK_THRESHOLD = 0.04f
        private const val FRAME_DIFF_CONSISTENCY_THRESHOLD = 0.30f
        private const val TEXTURE_SMOOTHNESS_THRESHOLD = 0.72f
        private const val MAX_FRAMES_FAST = 8
        private const val MAX_FRAMES_COMPLETE = 24
        private const val FRAME_SAMPLE_INTERVAL_MS = 1000L  // 1 frame/sec
    }

    // ─────────────────────────────────────────────────────────
    // POINT D'ENTRÉE PRINCIPAL
    // ─────────────────────────────────────────────────────────

    suspend fun analyze(
        uri: Uri,
        mode: AnalysisMode,
        onProgress: (Float) -> Unit = {}
    ): Pair<ModuleScore, ModuleScore> = withContext(Dispatchers.Default) {

        val startTime = System.currentTimeMillis()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val maxFrames = when (mode) {
                AnalysisMode.INSTANT -> 4
                AnalysisMode.FAST -> MAX_FRAMES_FAST
                AnalysisMode.COMPLETE -> MAX_FRAMES_COMPLETE
            }

            // Extraction des frames
            onProgress(0.1f)
            val frames = extractFrames(retriever, durationMs, maxFrames)
            onProgress(0.3f)

            if (frames.isEmpty()) {
                return@withContext createFallbackScores()
            }

            // ── Analyse PIXEL ─────────────────────────────────
            val pixelAnomalies = mutableListOf<Anomaly>()
            val pixelDetails = mutableListOf<String>()
            var pixelScore = 0f
            var pixelConfidence = 0f

            // 1. Analyse histogramme
            val histResult = analyzeHistograms(frames)
            pixelScore += histResult.score * 0.30f
            pixelConfidence += histResult.confidence * 0.30f
            if (histResult.score > 0.5f) {
                pixelAnomalies.addAll(histResult.anomalies)
                pixelDetails.add("Distribution de couleurs atypique détectée")
            }
            onProgress(0.45f)

            // 2. Analyse du bruit
            val noiseResult = analyzeNoise(frames)
            pixelScore += noiseResult.score * 0.35f
            pixelConfidence += noiseResult.confidence * 0.35f
            if (noiseResult.score > 0.5f) {
                pixelAnomalies.addAll(noiseResult.anomalies)
                pixelDetails.add("Signature de bruit anormale (pattern GAN possible)")
            }
            onProgress(0.55f)

            // 3. Artefacts haute fréquence (FFT)
            val fftResult = analyzeFrequencyDomain(frames)
            pixelScore += fftResult.score * 0.20f
            pixelConfidence += fftResult.confidence * 0.20f
            if (fftResult.score > 0.5f) {
                pixelAnomalies.addAll(fftResult.anomalies)
                pixelDetails.add("Pics spectraux caractéristiques des réseaux génératifs")
            }

            // 4. Texture (trop lisse = GAN)
            val textureResult = analyzeTexture(frames)
            pixelScore += textureResult.score * 0.15f
            pixelConfidence += textureResult.confidence * 0.15f
            if (textureResult.score > 0.5f) {
                pixelAnomalies.addAll(textureResult.anomalies)
                pixelDetails.add("Textures trop uniformes pour une vidéo naturelle")
            }
            onProgress(0.70f)

            val pixelModule = ModuleScore(
                score = pixelScore.coerceIn(0f, 1f),
                confidence = pixelConfidence.coerceIn(0f, 1f),
                details = if (pixelDetails.isEmpty()) listOf("Analyse pixel : aucune anomalie majeure") else pixelDetails,
                anomalies = pixelAnomalies,
                processingTimeMs = System.currentTimeMillis() - startTime
            )

            // ── Analyse TEMPORELLE ────────────────────────────
            val temporalAnomalies = mutableListOf<Anomaly>()
            val temporalDetails = mutableListOf<String>()
            var temporalScore = 0f
            var temporalConfidence = 0f

            if (frames.size >= 3) {
                // Optical flow simplifié
                val flowResult = analyzeTemporalConsistency(frames)
                temporalScore += flowResult.score * 0.50f
                temporalConfidence += flowResult.confidence * 0.50f
                if (flowResult.score > 0.5f) {
                    temporalAnomalies.addAll(flowResult.anomalies)
                    temporalDetails.add("Incohérences de mouvement entre les frames")
                }

                // Flicker luminosité
                val flickerResult = analyzeFlicker(frames)
                temporalScore += flickerResult.score * 0.30f
                temporalConfidence += flickerResult.confidence * 0.30f
                if (flickerResult.score > 0.5f) {
                    temporalAnomalies.addAll(flickerResult.anomalies)
                    temporalDetails.add("Variations de luminosité anormales (flicker GAN)")
                }

                // Cohérence bord de frame
                val edgeResult = analyzeEdgeConsistency(frames)
                temporalScore += edgeResult.score * 0.20f
                temporalConfidence += edgeResult.confidence * 0.20f
                if (edgeResult.score > 0.5f) {
                    temporalAnomalies.addAll(edgeResult.anomalies)
                    temporalDetails.add("Instabilité des bords de frame détectée")
                }
            } else {
                temporalScore = 0.3f
                temporalConfidence = 0.3f
                temporalDetails.add("Pas assez de frames pour l'analyse temporelle complète")
            }
            onProgress(0.90f)

            val temporalModule = ModuleScore(
                score = temporalScore.coerceIn(0f, 1f),
                confidence = temporalConfidence.coerceIn(0.2f, 1f),
                details = if (temporalDetails.isEmpty()) listOf("Analyse temporelle : flux vidéo cohérent") else temporalDetails,
                anomalies = temporalAnomalies,
                processingTimeMs = System.currentTimeMillis() - startTime
            )

            onProgress(1.0f)
            Pair(pixelModule, temporalModule)

        } finally {
            retriever.release()
        }
    }

    // ─────────────────────────────────────────────────────────
    // EXTRACTION DES FRAMES
    // ─────────────────────────────────────────────────────────

    private fun extractFrames(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        maxFrames: Int
    ): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        if (durationMs <= 0) return frames

        val interval = maxOf(FRAME_SAMPLE_INTERVAL_MS, durationMs / maxFrames)
        var timeUs = 0L

        while (timeUs < durationMs * 1000 && frames.size < maxFrames) {
            try {
                val bmp = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (bmp != null) {
                    // Redimensionner pour performance (max 256x256)
                    val scaled = scaleBitmap(bmp, 256)
                    frames.add(scaled)
                    if (scaled !== bmp) bmp.recycle()
                }
            } catch (e: Exception) {
                // Frame corrompue, continuer
            }
            timeUs += interval * 1000
        }

        return frames
    }

    private fun scaleBitmap(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxDim && h <= maxDim) return bmp
        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        return Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE HISTOGRAMME
    // ─────────────────────────────────────────────────────────

    private data class AnalysisSubResult(
        val score: Float,
        val confidence: Float,
        val anomalies: List<Anomaly>
    )

    private fun analyzeHistograms(frames: List<Bitmap>): AnalysisSubResult {
        val anomalies = mutableListOf<Anomaly>()
        var totalScore = 0f

        frames.forEachIndexed { idx, bmp ->
            val (rHist, gHist, bHist) = computeRGBHistograms(bmp)

            // Pics GAN : distributions trop piquées en certaines valeurs
            val rPeakScore = detectGANPeaks(rHist)
            val gPeakScore = detectGANPeaks(gHist)
            val bPeakScore = detectGANPeaks(bHist)
            val peakScore = (rPeakScore + gPeakScore + bPeakScore) / 3f

            // Distribution trop uniforme (GAN) vs bruit naturel (Gaussien)
            val rUniform = computeHistogramUniformity(rHist)
            val gUniform = computeHistogramUniformity(gHist)
            val uniformityScore = (rUniform + gUniform) / 2f

            val frameScore = peakScore * 0.6f + uniformityScore * 0.4f
            totalScore += frameScore

            if (frameScore > 0.6f) {
                anomalies.add(
                    Anomaly(
                        type = "HISTOGRAM_ANOMALY",
                        severity = AnomalySeverity.MEDIUM,
                        description = "Distribution couleur anormale",
                        technicalDetail = "Peak score: ${"%.3f".format(peakScore)}, Uniformity: ${"%.3f".format(uniformityScore)}",
                        frameIndex = idx
                    )
                )
            }
        }

        val avgScore = if (frames.isNotEmpty()) totalScore / frames.size else 0.5f
        return AnalysisSubResult(
            score = avgScore.coerceIn(0f, 1f),
            confidence = if (frames.size >= 4) 0.75f else 0.50f,
            anomalies = anomalies
        )
    }

    private fun computeRGBHistograms(bmp: Bitmap): Triple<IntArray, IntArray, IntArray> {
        val rHist = IntArray(256)
        val gHist = IntArray(256)
        val bHist = IntArray(256)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        pixels.forEach { px ->
            rHist[Color.red(px)]++
            gHist[Color.green(px)]++
            bHist[Color.blue(px)]++
        }
        return Triple(rHist, gHist, bHist)
    }

    private fun detectGANPeaks(hist: IntArray): Float {
        val total = hist.sum().toFloat()
        if (total == 0f) return 0f
        val normalized = hist.map { it / total }

        // Les GAN créent des pics très prononcés à des valeurs spécifiques
        val mean = normalized.average().toFloat()
        val peaksAboveThreshold = normalized.count { it > mean + HISTOGRAM_GAN_PEAK_THRESHOLD }
        return (peaksAboveThreshold.toFloat() / hist.size).coerceIn(0f, 1f)
    }

    private fun computeHistogramUniformity(hist: IntArray): Float {
        val total = hist.sum().toFloat()
        if (total == 0f) return 0f
        // Entropie : une distribution trop uniforme → score élevé
        var entropy = 0.0
        hist.forEach { count ->
            if (count > 0) {
                val p = count / total
                entropy -= p * ln(p.toDouble())
            }
        }
        val maxEntropy = ln(256.0)
        // Uniformité élevée → peut indiquer un GAN
        val uniformity = (entropy / maxEntropy).toFloat()
        // Score : valeurs extrêmes (trop haute ou trop basse) sont suspectes
        return when {
            uniformity > 0.92f -> 0.7f  // trop uniforme
            uniformity < 0.30f -> 0.6f  // trop concentré
            else -> 0.2f
        }
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE BRUIT
    // ─────────────────────────────────────────────────────────

    private fun analyzeNoise(frames: List<Bitmap>): AnalysisSubResult {
        val anomalies = mutableListOf<Anomaly>()
        val noiseProfiles = mutableListOf<Float>()

        frames.forEach { bmp ->
            val noiseLevel = estimateNoiseLevel(bmp)
            noiseProfiles.add(noiseLevel)
        }

        if (noiseProfiles.isEmpty()) return AnalysisSubResult(0.3f, 0.3f, emptyList())

        val mean = noiseProfiles.average().toFloat()
        val variance = noiseProfiles.map { (it - mean).pow(2) }.average().toFloat()
        val stdDev = sqrt(variance)

        // Bruit GAN : uniforme et constant (faible variance)
        // Bruit naturel : varie selon la scène
        val isUniformNoise = stdDev < NOISE_UNIFORMITY_THRESHOLD && mean < 0.04f
        val isAbsentNoise = mean < 0.005f

        var score = 0f
        if (isAbsentNoise) {
            score = 0.85f
            anomalies.add(Anomaly(
                type = "ABSENT_NOISE",
                severity = AnomalySeverity.HIGH,
                description = "Absence totale de bruit numérique naturel",
                technicalDetail = "Noise mean: ${"%.5f".format(mean)}"
            ))
        } else if (isUniformNoise) {
            score = 0.65f
            anomalies.add(Anomaly(
                type = "UNIFORM_NOISE",
                severity = AnomalySeverity.MEDIUM,
                description = "Bruit trop uniforme — signature GAN possible",
                technicalDetail = "Noise std: ${"%.5f".format(stdDev)}"
            ))
        } else {
            score = 0.15f
        }

        return AnalysisSubResult(
            score = score,
            confidence = if (frames.size >= 6) 0.80f else 0.55f,
            anomalies = anomalies
        )
    }

    private fun estimateNoiseLevel(bmp: Bitmap): Float {
        // Estimation bruit via filtre Laplacien simplifié
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        var sumDiff = 0.0
        var count = 0

        for (y in 1 until bmp.height - 1) {
            for (x in 1 until bmp.width - 1) {
                val center = getLuminance(pixels[y * bmp.width + x])
                val top = getLuminance(pixels[(y - 1) * bmp.width + x])
                val bottom = getLuminance(pixels[(y + 1) * bmp.width + x])
                val left = getLuminance(pixels[y * bmp.width + (x - 1)])
                val right = getLuminance(pixels[y * bmp.width + (x + 1)])

                // Laplacien
                val laplacian = abs(4 * center - top - bottom - left - right)
                sumDiff += laplacian
                count++
            }
        }

        return if (count > 0) (sumDiff / count).toFloat() else 0f
    }

    private fun getLuminance(pixel: Int): Double {
        val r = Color.red(pixel) / 255.0
        val g = Color.green(pixel) / 255.0
        val b = Color.blue(pixel) / 255.0
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE FRÉQUENTIELLE (FFT 2D SIMPLIFIÉE)
    // ─────────────────────────────────────────────────────────

    private fun analyzeFrequencyDomain(frames: List<Bitmap>): AnalysisSubResult {
        val anomalies = mutableListOf<Anomaly>()
        val framesToAnalyze = frames.take(4) // FFT coûteuse, limiter

        var totalGANScore = 0f

        framesToAnalyze.forEachIndexed { idx, bmp ->
            val ganScore = detectGANFrequencySignature(bmp)
            totalGANScore += ganScore

            if (ganScore > 0.6f) {
                anomalies.add(Anomaly(
                    type = "FFT_GAN_SIGNATURE",
                    severity = AnomalySeverity.HIGH,
                    description = "Pic spectral caractéristique d'un réseau génératif",
                    technicalDetail = "FFT GAN score: ${"%.3f".format(ganScore)}",
                    frameIndex = idx
                ))
            }
        }

        val avgScore = if (framesToAnalyze.isNotEmpty()) totalGANScore / framesToAnalyze.size else 0.3f

        return AnalysisSubResult(
            score = avgScore.coerceIn(0f, 1f),
            confidence = 0.70f,
            anomalies = anomalies
        )
    }

    private fun detectGANFrequencySignature(bmp: Bitmap): Float {
        // DCT approximée via différences locales hautes fréquences
        // Les GANs laissent des artefacts périodiques à ~N/2 cycles
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // Analyse bande passante haute fréquence
        var hfEnergy = 0.0
        var lfEnergy = 0.0

        for (y in 2 until h - 2 step 2) {
            for (x in 2 until w - 2 step 2) {
                val lum = getLuminance(pixels[y * w + x])

                // Voisins éloignés (basse fréquence)
                val lf = getLuminance(pixels[(y - 2) * w + x]) +
                        getLuminance(pixels[(y + 2) * w + x]) +
                        getLuminance(pixels[y * w + (x - 2)]) +
                        getLuminance(pixels[y * w + (x + 2)])

                // Voisins proches (haute fréquence)
                val hf = getLuminance(pixels[(y - 1) * w + x]) +
                        getLuminance(pixels[(y + 1) * w + x]) +
                        getLuminance(pixels[y * w + (x - 1)]) +
                        getLuminance(pixels[y * w + (x + 1)])

                hfEnergy += abs(4 * lum - hf)
                lfEnergy += abs(4 * lum - lf / 4)
            }
        }

        // Ratio HF/LF élevé → artefacts GAN potentiels
        val ratio = if (lfEnergy > 0) (hfEnergy / lfEnergy).toFloat() else 0f
        return when {
            ratio > 3.5f -> 0.80f
            ratio > 2.5f -> 0.55f
            ratio > 1.8f -> 0.35f
            else -> 0.10f
        }
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE TEXTURE
    // ─────────────────────────────────────────────────────────

    private fun analyzeTexture(frames: List<Bitmap>): AnalysisSubResult {
        val anomalies = mutableListOf<Anomaly>()
        var totalSmoothnessScore = 0f

        frames.forEach { bmp ->
            val smoothness = computeTextureSmoothness(bmp)
            totalSmoothnessScore += smoothness
        }

        val avgSmoothness = if (frames.isNotEmpty()) totalSmoothnessScore / frames.size else 0.5f

        val score = if (avgSmoothness > TEXTURE_SMOOTHNESS_THRESHOLD) {
            anomalies.add(Anomaly(
                type = "OVER_SMOOTH_TEXTURE",
                severity = AnomalySeverity.MEDIUM,
                description = "Textures trop lisses — caractéristique des GAN",
                technicalDetail = "Smoothness: ${"%.3f".format(avgSmoothness)}"
            ))
            (avgSmoothness - TEXTURE_SMOOTHNESS_THRESHOLD) * 3.5f
        } else 0.1f

        return AnalysisSubResult(
            score = score.coerceIn(0f, 1f),
            confidence = 0.65f,
            anomalies = anomalies
        )
    }

    private fun computeTextureSmoothness(bmp: Bitmap): Float {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        val gradients = mutableListOf<Double>()
        for (y in 0 until bmp.height - 1) {
            for (x in 0 until bmp.width - 1) {
                val dx = abs(
                    getLuminance(pixels[y * bmp.width + x + 1]) -
                    getLuminance(pixels[y * bmp.width + x])
                )
                val dy = abs(
                    getLuminance(pixels[(y + 1) * bmp.width + x]) -
                    getLuminance(pixels[y * bmp.width + x])
                )
                gradients.add(sqrt(dx * dx + dy * dy))
            }
        }

        // Faible gradient moyen → texture lisse → GAN suspect
        val avgGradient = gradients.average()
        return (1.0 - avgGradient.coerceIn(0.0, 1.0)).toFloat()
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE TEMPORELLE
    // ─────────────────────────────────────────────────────────

    private fun analyzeTemporalConsistency(frames: List<Bitmap>): AnalysisSubResult {
        val anomalies = mutableListOf<Anomaly>()
        val frameDiffs = mutableListOf<Float>()

        for (i in 1 until frames.size) {
            val diff = computeFrameDifference(frames[i - 1], frames[i])
            frameDiffs.add(diff)
        }

        if (frameDiffs.isEmpty()) return AnalysisSubResult(0.3f, 0.3f, emptyList())

        val mean = frameDiffs.average().toFloat()
        val variance = frameDiffs.map { (it - mean).pow(2) }.average().toFloat()
        val stdDev = sqrt(variance)

        // Incohérence : certaines transitions trop grandes ou trop petites
        val abruptJumps = frameDiffs.count { it > mean + 2 * stdDev }
        val jumpRatio = abruptJumps.toFloat() / frameDiffs.size

        var score = 0f
        if (jumpRatio > FRAME_DIFF_CONSISTENCY_THRESHOLD) {
            score = 0.7f
            anomalies.add(Anomaly(
                type = "TEMPORAL_INCONSISTENCY",
                severity = AnomalySeverity.HIGH,
                description = "Sauts brusques entre frames (incohérence temporelle)",
                technicalDetail = "Jump ratio: ${"%.2f".format(jumpRatio)}, StdDev: ${"%.4f".format(stdDev)}"
            ))
        } else if (stdDev < 0.001f && mean < 0.01f) {
            // Trop statique — peut indiquer contenu synthétique figé
            score = 0.5f
            anomalies.add(Anomaly(
                type = "STATIC_VIDEO",
                severity = AnomalySeverity.LOW,
                description = "Vidéo anormalement statique",
                technicalDetail = "Frame diff variance: ${"%.6f".format(variance)}"
            ))
        } else {
            score = (jumpRatio * 1.5f).coerceIn(0f, 0.4f)
        }

        return AnalysisSubResult(
            score = score.coerceIn(0f, 1f),
            confidence = if (frames.size >= 8) 0.80f else 0.55f,
            anomalies = anomalies
        )
    }

    private fun computeFrameDifference(bmp1: Bitmap, bmp2: Bitmap): Float {
        val w = minOf(bmp1.width, bmp2.width)
        val h = minOf(bmp1.height, bmp2.height)

        val p1 = IntArray(w * h)
        val p2 = IntArray(w * h)
        bmp1.getPixels(p1, 0, w, 0, 0, w, h)
        bmp2.getPixels(p2, 0, w, 0, 0, w, h)

        var sumDiff = 0.0
        val sampleStep = maxOf(1, w * h / 1000)  // Sous-échantillonnage

        var count = 0
        var i = 0
        while (i < p1.size) {
            sumDiff += abs(getLuminance(p1[i]) - getLuminance(p2[i]))
            count++
            i += sampleStep
        }

        return if (count > 0) (sumDiff / count).toFloat() else 0f
    }

    private fun analyzeFlicker(frames: List<Bitmap>): AnalysisSubResult {
        val anomalies = mutableListOf<Anomaly>()
        val luminances = frames.map { computeAverageLuminance(it) }

        if (luminances.size < 3) return AnalysisSubResult(0.2f, 0.3f, emptyList())

        // Détection flicker : oscillations haute fréquence de luminosité
        val diffs = luminances.zipWithNext { a, b -> abs(b - a) }
        val mean = diffs.average()
        val maxDiff = diffs.maxOrNull() ?: 0.0

        val flickerScore = when {
            maxDiff > 0.15 -> 0.75f
            maxDiff > 0.10 -> 0.50f
            mean > 0.05 -> 0.35f
            else -> 0.10f
        }

        if (flickerScore > 0.5f) {
            anomalies.add(Anomaly(
                type = "LUMINANCE_FLICKER",
                severity = AnomalySeverity.MEDIUM,
                description = "Variations de luminosité anormales entre frames",
                technicalDetail = "Max lum diff: ${"%.3f".format(maxDiff)}"
            ))
        }

        return AnalysisSubResult(flickerScore, 0.65f, anomalies)
    }

    private fun analyzeEdgeConsistency(frames: List<Bitmap>): AnalysisSubResult {
        // Analyse cohérence des bords (artefacts GAN aux bordures)
        val edgeScores = frames.map { detectEdgeArtifacts(it) }
        val avgScore = edgeScores.average().toFloat()

        val anomalies = if (avgScore > 0.6f) {
            listOf(Anomaly(
                type = "EDGE_ARTIFACTS",
                severity = AnomalySeverity.MEDIUM,
                description = "Artefacts détectés aux bords de l'image",
                technicalDetail = "Edge consistency score: ${"%.3f".format(avgScore)}"
            ))
        } else emptyList()

        return AnalysisSubResult(avgScore, 0.60f, anomalies)
    }

    private fun detectEdgeArtifacts(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // Analyse les 10% de bord
        val borderSize = maxOf(2, (minOf(w, h) * 0.05).toInt())
        var borderAnomaly = 0.0
        var centerAnomaly = 0.0
        var borderCount = 0
        var centerCount = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val isBorder = x < borderSize || x >= w - borderSize ||
                               y < borderSize || y >= h - borderSize
                val lum = getLuminance(pixels[y * w + x])

                if (isBorder) {
                    borderAnomaly += lum
                    borderCount++
                } else {
                    centerAnomaly += lum
                    centerCount++
                }
            }
        }

        if (borderCount == 0 || centerCount == 0) return 0.2f

        val borderMean = borderAnomaly / borderCount
        val centerMean = centerAnomaly / centerCount
        val diff = abs(borderMean - centerMean)

        return (diff * 2.5f).toFloat().coerceIn(0f, 1f)
    }

    private fun computeAverageLuminance(bmp: Bitmap): Double {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val step = maxOf(1, pixels.size / 500)
        var sum = 0.0
        var count = 0
        for (i in pixels.indices step step) {
            sum += getLuminance(pixels[i])
            count++
        }
        return if (count > 0) sum / count else 0.5
    }

    private fun createFallbackScores(): Pair<ModuleScore, ModuleScore> {
        val fallback = ModuleScore(
            score = 0.3f,
            confidence = 0.2f,
            details = listOf("Impossible d'extraire les frames de cette vidéo"),
            anomalies = emptyList()
        )
        return Pair(fallback, fallback)
    }
}
