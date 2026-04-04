package com.deepfakedetector.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.deepfakedetector.data.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.*

/**
 * FaceAnalyzer — Analyse visage et signaux physiologiques
 *
 * Modules :
 * 1. Stabilité landmarks faciaux (ML Kit)
 * 2. Cohérence des clignements d'yeux
 * 3. rPPG — Remote PhotoPlethysmoGraphy (estimation pouls par couleur peau)
 * 4. Symétrie faciale anormale
 * 5. Artefacts aux bords du visage (blend artifacts)
 * 6. Cohérence expression / mouvement
 */
@Singleton
class FaceAnalyzer @Inject constructor(
    private val context: Context
) {

    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setMinFaceSize(0.15f)
        .enableTracking()
        .build()

    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)

    companion object {
        private const val MAX_FRAMES_FACE = 16
        private const val LANDMARK_STABILITY_THRESHOLD = 0.12f
        private const val BLINK_PROBABILITY_FLOOR = 0.05f
        private const val SYMMETRY_THRESHOLD = 0.25f
        private const val RPPG_WINDOW_SIZE = 10  // frames
    }

    // ─────────────────────────────────────────────────────────
    // POINT D'ENTRÉE
    // ─────────────────────────────────────────────────────────

    suspend fun analyze(
        uri: Uri,
        mode: AnalysisMode,
        onProgress: (Float) -> Unit = {}
    ): Pair<ModuleScore, ModuleScore>? = withContext(Dispatchers.Default) {

        val startTime = System.currentTimeMillis()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val maxFrames = when (mode) {
                AnalysisMode.INSTANT -> 4
                AnalysisMode.FAST -> 8
                AnalysisMode.COMPLETE -> MAX_FRAMES_FACE
            }

            // Extraire frames
            val frames = extractFaceFrames(retriever, durationMs, maxFrames)
            onProgress(0.20f)

            if (frames.isEmpty()) return@withContext null

            // Détecter visages dans toutes les frames
            val detectedFaces = mutableListOf<Pair<Bitmap, List<Face>>>()
            frames.forEachIndexed { idx, bmp ->
                val faces = detectFaces(bmp)
                detectedFaces.add(Pair(bmp, faces))
                onProgress(0.20f + (idx.toFloat() / frames.size) * 0.40f)
            }

            val framesWithFace = detectedFaces.filter { it.second.isNotEmpty() }

            if (framesWithFace.size < 2) {
                return@withContext createNoFaceScores()
            }

            onProgress(0.65f)

            // ── ANALYSE VISAGE ─────────────────────────────────
            val faceAnomalies = mutableListOf<Anomaly>()
            val faceDetails = mutableListOf<String>()
            var faceScore = 0f
            var faceConfidence = 0f

            // 1. Stabilité landmarks
            val landmarkResult = analyzeLandmarkStability(framesWithFace)
            faceScore += landmarkResult.first * 0.30f
            faceConfidence += landmarkResult.second * 0.30f
            if (landmarkResult.first > 0.5f) {
                faceDetails.add("Mouvements faciaux instables ou saccadés")
                faceAnomalies.add(Anomaly(
                    type = "LANDMARK_INSTABILITY",
                    severity = AnomalySeverity.HIGH,
                    description = "Landmarks faciaux incohérents entre les frames",
                    technicalDetail = "Stability score: ${"%.3f".format(landmarkResult.first)}"
                ))
            }

            // 2. Cohérence clignements
            val blinkResult = analyzeBlinkConsistency(framesWithFace)
            faceScore += blinkResult.first * 0.20f
            faceConfidence += blinkResult.second * 0.20f
            if (blinkResult.first > 0.5f) {
                faceDetails.add("Pattern de clignements oculaires anormal")
                faceAnomalies.add(Anomaly(
                    type = "BLINK_ANOMALY",
                    severity = AnomalySeverity.MEDIUM,
                    description = "Clignements d'yeux absents ou trop réguliers",
                    technicalDetail = "Blink score: ${"%.3f".format(blinkResult.first)}"
                ))
            }

            // 3. Symétrie faciale
            val symmetryResult = analyzeFacialSymmetry(framesWithFace)
            faceScore += symmetryResult.first * 0.25f
            faceConfidence += symmetryResult.second * 0.25f
            if (symmetryResult.first > 0.5f) {
                faceDetails.add("Asymétrie faciale anormale pour un visage réel")
                faceAnomalies.add(Anomaly(
                    type = "FACIAL_SYMMETRY",
                    severity = AnomalySeverity.MEDIUM,
                    description = "Symétrie faciale trop parfaite ou incohérente",
                    technicalDetail = "Symmetry score: ${"%.3f".format(symmetryResult.first)}"
                ))
            }

            // 4. Artefacts bords visage
            val blendResult = analyzeBlendArtifacts(framesWithFace)
            faceScore += blendResult.first * 0.25f
            faceConfidence += blendResult.second * 0.25f
            if (blendResult.first > 0.5f) {
                faceDetails.add("Artefacts de fusion détectés aux bords du visage")
                faceAnomalies.add(Anomaly(
                    type = "BLEND_ARTIFACTS",
                    severity = AnomalySeverity.HIGH,
                    description = "Contours du visage avec artefacts de génération",
                    technicalDetail = "Blend score: ${"%.3f".format(blendResult.first)}"
                ))
            }

            onProgress(0.82f)

            val faceModule = ModuleScore(
                score = faceScore.coerceIn(0f, 1f),
                confidence = faceConfidence.coerceIn(0.3f, 1f),
                details = if (faceDetails.isEmpty()) listOf("Analyse visage : comportement facial naturel") else faceDetails,
                anomalies = faceAnomalies,
                processingTimeMs = System.currentTimeMillis() - startTime
            )

            // ── ANALYSE PHYSIOLOGIQUE (rPPG) ──────────────────
            val physioModule = analyzeRPPG(framesWithFace, startTime)
            onProgress(1.0f)

            Pair(faceModule, physioModule)

        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    // ─────────────────────────────────────────────────────────
    // EXTRACTION FRAMES
    // ─────────────────────────────────────────────────────────

    private fun extractFaceFrames(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        maxFrames: Int
    ): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        if (durationMs <= 0) return frames

        val interval = durationMs * 1000 / maxFrames

        for (i in 0 until maxFrames) {
            val timeUs = i * interval
            try {
                val bmp = retriever.getFrameAtTime(timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    // Pas de redimensionnement trop agressif pour ML Kit
                    val scaled = if (bmp.width > 640) {
                        Bitmap.createScaledBitmap(bmp, 640, (640f * bmp.height / bmp.width).toInt(), true)
                    } else bmp
                    frames.add(scaled)
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return frames
    }

    // ─────────────────────────────────────────────────────────
    // DÉTECTION VISAGE ML KIT
    // ─────────────────────────────────────────────────────────

    private suspend fun detectFaces(bitmap: Bitmap): List<Face> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            faceDetector.process(image)
                .addOnSuccessListener { faces -> cont.resume(faces) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    // ─────────────────────────────────────────────────────────
    // ANALYSE LANDMARKS
    // ─────────────────────────────────────────────────────────

    private fun analyzeLandmarkStability(
        framesWithFace: List<Pair<Bitmap, List<Face>>>
    ): Pair<Float, Float> {
        if (framesWithFace.size < 3) return Pair(0.3f, 0.3f)

        val primaryFaces = framesWithFace.map { it.second.firstOrNull() }
            .filterNotNull()

        if (primaryFaces.size < 3) return Pair(0.3f, 0.3f)

        // Analyser la position de la boîte englobante
        val boundingBoxes = primaryFaces.map { face ->
            val bb = face.boundingBox
            floatArrayOf(
                bb.centerX().toFloat(),
                bb.centerY().toFloat(),
                bb.width().toFloat(),
                bb.height().toFloat()
            )
        }

        // Calculer les changements relatifs
        val diffs = mutableListOf<Float>()
        for (i in 1 until boundingBoxes.size) {
            val prev = boundingBoxes[i - 1]
            val curr = boundingBoxes[i]
            val size = prev[2]  // Largeur pour normalisation

            if (size > 0) {
                val dx = abs(curr[0] - prev[0]) / size
                val dy = abs(curr[1] - prev[1]) / size
                diffs.add(sqrt(dx * dx + dy * dy))
            }
        }

        if (diffs.isEmpty()) return Pair(0.3f, 0.4f)

        val mean = diffs.average().toFloat()
        val variance = diffs.map { (it - mean).pow(2) }.average().toFloat()
        val stdDev = sqrt(variance)

        // Mouvement trop erratique
        val score = when {
            stdDev > LANDMARK_STABILITY_THRESHOLD * 2 -> 0.80f
            stdDev > LANDMARK_STABILITY_THRESHOLD -> 0.55f
            mean > 0.3f -> 0.45f
            else -> 0.15f
        }

        return Pair(score, if (primaryFaces.size >= 8) 0.80f else 0.55f)
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE CLIGNEMENTS
    // ─────────────────────────────────────────────────────────

    private fun analyzeBlinkConsistency(
        framesWithFace: List<Pair<Bitmap, List<Face>>>
    ): Pair<Float, Float> {
        val eyeOpenProbs = framesWithFace
            .mapNotNull { it.second.firstOrNull() }
            .mapNotNull { face ->
                val leftProb = face.leftEyeOpenProbability ?: return@mapNotNull null
                val rightProb = face.rightEyeOpenProbability ?: return@mapNotNull null
                (leftProb + rightProb) / 2f
            }

        if (eyeOpenProbs.size < 4) return Pair(0.2f, 0.2f)

        // Analyser la distribution des probabilités
        val closedEyes = eyeOpenProbs.count { it < 0.4f }
        val halfClosed = eyeOpenProbs.count { it in 0.4f..0.7f }

        // Yeux jamais fermés → pas de clignement → deepfake suspect
        val noBlinkScore = if (closedEyes == 0) 0.75f else 0f

        // Tous les yeux au même niveau → artificiel
        val variance = eyeOpenProbs.map { (it - eyeOpenProbs.average()).pow(2) }.average().toFloat()
        val lowVarianceScore = if (variance < 0.01f) 0.60f else 0f

        // Probabilité inférieure au seuil minimum → incohérent
        val belowFloorCount = eyeOpenProbs.count { it < BLINK_PROBABILITY_FLOOR }
        val floorScore = if (belowFloorCount > eyeOpenProbs.size * 0.3f) 0.55f else 0f

        val score = maxOf(noBlinkScore, lowVarianceScore, floorScore)

        return Pair(score, if (eyeOpenProbs.size >= 8) 0.75f else 0.50f)
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE SYMÉTRIE
    // ─────────────────────────────────────────────────────────

    private fun analyzeFacialSymmetry(
        framesWithFace: List<Pair<Bitmap, List<Face>>>
    ): Pair<Float, Float> {
        if (framesWithFace.isEmpty()) return Pair(0.2f, 0.2f)

        var symmetryAnomalyCount = 0
        val totalFrames = framesWithFace.size

        framesWithFace.forEach { (bmp, faces) ->
            val face = faces.firstOrNull() ?: return@forEach
            val symmetryScore = computeSymmetryScore(bmp, face)

            // Trop symétrique → GAN
            // Trop asymétrique → artifacts GAN
            if (symmetryScore > 0.95f || symmetryScore < 0.60f) {
                symmetryAnomalyCount++
            }
        }

        val anomalyRatio = symmetryAnomalyCount.toFloat() / totalFrames
        val score = when {
            anomalyRatio > 0.6f -> 0.70f
            anomalyRatio > 0.3f -> 0.45f
            else -> 0.10f
        }

        return Pair(score, 0.60f)
    }

    private fun computeSymmetryScore(bmp: Bitmap, face: Face): Float {
        val bb = face.boundingBox
        val centerX = bb.centerX()
        val left = maxOf(0, bb.left)
        val right = minOf(bmp.width, bb.right)
        val top = maxOf(0, bb.top)
        val bottom = minOf(bmp.height, bb.bottom)

        if (right <= left || bottom <= top) return 0.5f

        val faceWidth = right - left
        val faceHeight = bottom - top

        var symmetrySum = 0.0
        var count = 0

        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        for (y in top until bottom step 4) {
            for (x in left until centerX step 4) {
                val mirrorX = 2 * centerX - x
                if (mirrorX >= right || mirrorX >= bmp.width) continue

                val leftPixel = pixels[y * bmp.width + x]
                val rightPixel = pixels[y * bmp.width + mirrorX]

                val leftLum = (android.graphics.Color.red(leftPixel) + android.graphics.Color.green(leftPixel) + android.graphics.Color.blue(leftPixel)) / (3.0 * 255)
                val rightLum = (android.graphics.Color.red(rightPixel) + android.graphics.Color.green(rightPixel) + android.graphics.Color.blue(rightPixel)) / (3.0 * 255)

                symmetrySum += 1 - abs(leftLum - rightLum)
                count++
            }
        }

        return if (count > 0) (symmetrySum / count).toFloat() else 0.5f
    }

    // ─────────────────────────────────────────────────────────
    // DÉTECTION ARTEFACTS DE FUSION (BLEND)
    // ─────────────────────────────────────────────────────────

    private fun analyzeBlendArtifacts(
        framesWithFace: List<Pair<Bitmap, List<Face>>>
    ): Pair<Float, Float> {
        var blendAnomalyCount = 0
        val totalFrames = framesWithFace.size

        framesWithFace.forEach { (bmp, faces) ->
            val face = faces.firstOrNull() ?: return@forEach
            val blendScore = detectFaceEdgeArtifacts(bmp, face)
            if (blendScore > 0.55f) blendAnomalyCount++
        }

        val anomalyRatio = blendAnomalyCount.toFloat() / totalFrames
        val score = when {
            anomalyRatio > 0.5f -> 0.80f
            anomalyRatio > 0.25f -> 0.55f
            else -> 0.15f
        }

        return Pair(score, 0.65f)
    }

    private fun detectFaceEdgeArtifacts(bmp: Bitmap, face: Face): Float {
        val bb = face.boundingBox
        val borderSize = maxOf(3, (minOf(bb.width(), bb.height()) * 0.08).toInt())

        val left = maxOf(0, bb.left)
        val right = minOf(bmp.width - 1, bb.right)
        val top = maxOf(0, bb.top)
        val bottom = minOf(bmp.height - 1, bb.bottom)

        if (right <= left + borderSize * 2 || bottom <= top + borderSize * 2) return 0.2f

        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        // Comparer les gradients à l'intérieur vs au bord du visage
        var borderGradient = 0.0
        var innerGradient = 0.0
        var borderCount = 0
        var innerCount = 0

        for (y in top + 1 until bottom) {
            for (x in left + 1 until right) {
                val isBorder = (x - left < borderSize || right - x < borderSize ||
                               y - top < borderSize || bottom - y < borderSize)

                val idx = y * bmp.width + x
                val lum = getLuminance(pixels[idx])
                val lumRight = getLuminance(pixels[idx + 1])
                val lumBottom = getLuminance(pixels[(y + 1) * bmp.width + x])

                val gradient = sqrt((lumRight - lum).pow(2) + (lumBottom - lum).pow(2))

                if (isBorder) {
                    borderGradient += gradient
                    borderCount++
                } else {
                    innerGradient += gradient
                    innerCount++
                }
            }
        }

        if (borderCount == 0 || innerCount == 0) return 0.2f

        val borderMean = borderGradient / borderCount
        val innerMean = innerGradient / innerCount

        // Gradient bord >> gradient intérieur → artefact de blend
        val ratio = if (innerMean > 0) (borderMean / innerMean).toFloat() else 1f
        return when {
            ratio > 3.5f -> 0.85f
            ratio > 2.5f -> 0.65f
            ratio > 1.8f -> 0.40f
            else -> 0.15f
        }
    }

    private fun getLuminance(pixel: Int): Double {
        val r = android.graphics.Color.red(pixel) / 255.0
        val g = android.graphics.Color.green(pixel) / 255.0
        val b = android.graphics.Color.blue(pixel) / 255.0
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    // ─────────────────────────────────────────────────────────
    // rPPG — REMOTE PHOTOPLETHYSMOGRAPHY
    // ─────────────────────────────────────────────────────────

    /**
     * Le rPPG exploite le fait que le sang absorbe la lumière verte.
     * En mesurant la variation du canal vert sur la peau,
     * on peut estimer le pouls (~60-100 BPM chez un humain vivant).
     * Un deepfake n'a pas de pouls → signal rPPG plat ou incohérent.
     */
    private fun analyzeRPPG(
        framesWithFace: List<Pair<Bitmap, List<Face>>>,
        startTime: Long
    ): ModuleScore {
        val anomalies = mutableListOf<Anomaly>()
        val details = mutableListOf<String>()

        if (framesWithFace.size < RPPG_WINDOW_SIZE) {
            return ModuleScore(
                score = 0.25f,
                confidence = 0.20f,
                details = listOf("Pas assez de frames pour analyse rPPG (besoin : $RPPG_WINDOW_SIZE)"),
                anomalies = emptyList(),
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Extraire signal vert moyen de la zone peau (front)
        val greenSignal = framesWithFace.mapNotNull { (bmp, faces) ->
            val face = faces.firstOrNull() ?: return@mapNotNull null
            extractSkinGreenChannel(bmp, face)
        }

        if (greenSignal.size < RPPG_WINDOW_SIZE) {
            return ModuleScore(
                score = 0.25f,
                confidence = 0.25f,
                details = listOf("Signal rPPG insuffisant — visage trop petit ou occlu"),
                anomalies = emptyList(),
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Normaliser le signal
        val normalizedSignal = normalizeSignal(greenSignal)

        // Calculer la puissance spectrale dans la bande cardiaque (0.75–3.0 Hz)
        val rPPGScore = analyzeHeartbeatSignal(normalizedSignal)

        val score: Float
        val confidence: Float

        when {
            rPPGScore.hasValidHeartbeat -> {
                // Pouls détecté → plutôt réel
                score = 0.10f
                confidence = 0.65f
                details.add("Signal physiologique (pouls ~${rPPGScore.estimatedBPM} BPM) détecté ✓")
            }
            rPPGScore.isFlatSignal -> {
                // Signal plat → deepfake possible
                score = 0.80f
                confidence = 0.60f
                details.add("Aucun signal physiologique détectable — pouls absent")
                anomalies.add(Anomaly(
                    type = "RPPG_NO_SIGNAL",
                    severity = AnomalySeverity.HIGH,
                    description = "Absence de signal de pouls (rPPG)",
                    technicalDetail = "Signal variance: ${"%.5f".format(rPPGScore.signalVariance)}"
                ))
            }
            rPPGScore.isOutOfRange -> {
                // BPM hors range humain (< 40 ou > 200)
                score = 0.70f
                confidence = 0.55f
                details.add("Signal physiologique incohérent (${rPPGScore.estimatedBPM} BPM hors norme)")
                anomalies.add(Anomaly(
                    type = "RPPG_INVALID_BPM",
                    severity = AnomalySeverity.MEDIUM,
                    description = "Fréquence cardiaque estimée impossible",
                    technicalDetail = "Estimated BPM: ${rPPGScore.estimatedBPM}"
                ))
            }
            else -> {
                score = 0.35f
                confidence = 0.40f
                details.add("Signal physiologique ambiguë — analyse inconcluante")
            }
        }

        return ModuleScore(
            score = score,
            confidence = confidence,
            details = details,
            anomalies = anomalies,
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private fun extractSkinGreenChannel(bmp: Bitmap, face: Face): Float? {
        val bb = face.boundingBox

        // Zone front : 30% supérieur du visage
        val faceTop = maxOf(0, bb.top)
        val foreheadBottom = minOf(bmp.height, bb.top + bb.height() / 3)
        val left = maxOf(0, bb.left + bb.width() / 4)
        val right = minOf(bmp.width, bb.right - bb.width() / 4)

        if (right <= left || foreheadBottom <= faceTop) return null

        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        var greenSum = 0L
        var count = 0

        for (y in faceTop until foreheadBottom step 2) {
            for (x in left until right step 2) {
                val pixel = pixels[y * bmp.width + x]
                greenSum += android.graphics.Color.green(pixel)
                count++
            }
        }

        return if (count > 0) greenSum.toFloat() / (count * 255f) else null
    }

    private data class RPPGAnalysis(
        val hasValidHeartbeat: Boolean,
        val isFlatSignal: Boolean,
        val isOutOfRange: Boolean,
        val estimatedBPM: Int,
        val signalVariance: Float
    )

    private fun normalizeSignal(signal: List<Float>): List<Float> {
        val mean = signal.average().toFloat()
        val variance = signal.map { (it - mean).pow(2) }.average().toFloat()
        val std = sqrt(variance)
        return if (std > 0) signal.map { (it - mean) / std } else signal.map { 0f }
    }

    private fun analyzeHeartbeatSignal(signal: List<Float>): RPPGAnalysis {
        // Variance du signal rPPG
        val variance = signal.map { it * it }.average().toFloat()

        if (variance < 0.01f) {
            return RPPGAnalysis(false, true, false, 0, variance)
        }

        // Autocorrélation pour estimer la fréquence dominante
        // Supposons 30 FPS → lag en frames pour 60-100 BPM
        // 60 BPM @ 30fps = lag de 30 frames
        // 100 BPM @ 30fps = lag de 18 frames
        val fps = 30f
        val minLag = (fps * 60f / 180f).toInt()   // 180 BPM max
        val maxLag = (fps * 60f / 40f).toInt()    // 40 BPM min
        val clampedMaxLag = minOf(maxLag, signal.size / 2)

        if (minLag >= clampedMaxLag || signal.size < clampedMaxLag * 2) {
            return RPPGAnalysis(false, false, false, 0, variance)
        }

        // Calculer autocorrélation
        var bestCorr = 0f
        var bestLag = 0

        for (lag in minLag until clampedMaxLag) {
            var corr = 0f
            for (i in 0 until signal.size - lag) {
                corr += signal[i] * signal[i + lag]
            }
            corr /= (signal.size - lag)
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        val estimatedBPM = if (bestLag > 0) (fps * 60f / bestLag).toInt() else 0
        val hasValidBPM = estimatedBPM in 40..180
        val hasGoodCorrelation = bestCorr > 0.15f

        return RPPGAnalysis(
            hasValidHeartbeat = hasValidBPM && hasGoodCorrelation,
            isFlatSignal = variance < 0.05f,
            isOutOfRange = estimatedBPM > 0 && !hasValidBPM,
            estimatedBPM = estimatedBPM,
            signalVariance = variance
        )
    }

    private fun createNoFaceScores(): Pair<ModuleScore, ModuleScore>? {
        // Aucun visage détecté → modules non applicables
        return null
    }
}
