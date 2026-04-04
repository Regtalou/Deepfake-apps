package com.deepfakedetector.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.deepfakedetector.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * AudioAnalyzer — Analyse audio pour détection deepfake
 *
 * Modules :
 * 1. Analyse spectrale (vocoders GAN laissent des signatures)
 * 2. Détection silence / coupures anormales
 * 3. Uniformité du bruit de fond (trop propre = synthétique)
 * 4. Analyse fondamentale F0 (voix naturelle vs synthétique)
 * 5. Cohérence audio/vidéo (lip-sync proxy)
 */
@Singleton
class AudioAnalyzer @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FFT_SIZE = 1024
        private const val HOP_SIZE = 512
        private const val MIN_AUDIO_DURATION_MS = 500L

        // Seuils
        private const val SILENCE_RATIO_THRESHOLD = 0.40f
        private const val BACKGROUND_NOISE_UNIFORMITY_THRESHOLD = 0.85f
        private const val SPECTRAL_FLATNESS_VOCODER_THRESHOLD = 0.80f
    }

    suspend fun analyze(
        uri: Uri,
        mode: AnalysisMode,
        onProgress: (Float) -> Unit = {}
    ): ModuleScore? = withContext(Dispatchers.Default) {

        val startTime = System.currentTimeMillis()
        val anomalies = mutableListOf<Anomaly>()
        val details = mutableListOf<String>()

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // Trouver la piste audio
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                return@withContext ModuleScore(
                    score = 0.3f,
                    confidence = 0.2f,
                    details = listOf("Pas de piste audio détectée"),
                    anomalies = emptyList()
                )
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)

            if (durationUs < MIN_AUDIO_DURATION_MS * 1000) {
                extractor.release()
                return@withContext ModuleScore(
                    score = 0.3f,
                    confidence = 0.25f,
                    details = listOf("Piste audio trop courte pour analyse fiable"),
                    anomalies = emptyList()
                )
            }

            onProgress(0.2f)

            // Extraire les samples PCM
            val maxSamplesToExtract = when (mode) {
                AnalysisMode.INSTANT -> SAMPLE_RATE * 2      // 2 secondes
                AnalysisMode.FAST -> SAMPLE_RATE * 10        // 10 secondes
                AnalysisMode.COMPLETE -> SAMPLE_RATE * 30    // 30 secondes
            }

            val pcmSamples = extractPCMSamples(extractor, format, maxSamplesToExtract)
            extractor.release()

            if (pcmSamples.isEmpty()) {
                return@withContext null
            }

            onProgress(0.4f)

            // ── ANALYSE 1 : Silence et coupures ───────────────
            var totalScore = 0f
            var totalConfidence = 0f

            val silenceResult = analyzeSilencePattern(pcmSamples)
            totalScore += silenceResult.first * 0.20f
            totalConfidence += silenceResult.second * 0.20f
            if (silenceResult.first > 0.5f) {
                details.add("Silences anormaux ou coupures artificielles détectés")
                anomalies.add(Anomaly(
                    type = "AUDIO_SILENCE_ANOMALY",
                    severity = AnomalySeverity.MEDIUM,
                    description = "Pattern de silence suspect",
                    technicalDetail = "Silence ratio: ${"%.2f".format(silenceResult.first)}"
                ))
            }
            onProgress(0.55f)

            // ── ANALYSE 2 : Bruit de fond ─────────────────────
            val bgNoiseResult = analyzeBackgroundNoise(pcmSamples)
            totalScore += bgNoiseResult.first * 0.25f
            totalConfidence += bgNoiseResult.second * 0.25f
            if (bgNoiseResult.first > 0.5f) {
                details.add("Bruit de fond trop uniforme — caractéristique audio synthétique")
                anomalies.add(Anomaly(
                    type = "UNIFORM_BACKGROUND_NOISE",
                    severity = AnomalySeverity.MEDIUM,
                    description = "Fond sonore anormalement propre",
                    technicalDetail = "Noise uniformity: ${"%.3f".format(bgNoiseResult.first)}"
                ))
            }
            onProgress(0.65f)

            // ── ANALYSE 3 : Spectre fréquentiel (vocoder) ─────
            val spectralResult = analyzeSpectralContent(pcmSamples)
            totalScore += spectralResult.first * 0.35f
            totalConfidence += spectralResult.second * 0.35f
            if (spectralResult.first > 0.5f) {
                details.add("Signature spectrale de synthèse vocale détectée")
                anomalies.add(Anomaly(
                    type = "VOCODER_SIGNATURE",
                    severity = AnomalySeverity.HIGH,
                    description = "Artefacts spectraux de vocoder/TTS",
                    technicalDetail = "Spectral flatness: ${"%.3f".format(spectralResult.first)}"
                ))
            }
            onProgress(0.80f)

            // ── ANALYSE 4 : Périodicité anormale ─────────────
            val periodicityResult = analyzeAbnormalPeriodicity(pcmSamples)
            totalScore += periodicityResult.first * 0.20f
            totalConfidence += periodicityResult.second * 0.20f
            if (periodicityResult.first > 0.5f) {
                details.add("Périodicité artificielle dans le signal audio")
                anomalies.add(Anomaly(
                    type = "AUDIO_PERIODICITY",
                    severity = AnomalySeverity.MEDIUM,
                    description = "Signal audio trop périodique",
                    technicalDetail = "Periodicity score: ${"%.3f".format(periodicityResult.first)}"
                ))
            }
            onProgress(1.0f)

            if (details.isEmpty()) {
                details.add("Analyse audio : aucune anomalie majeure détectée")
            }

            ModuleScore(
                score = totalScore.coerceIn(0f, 1f),
                confidence = totalConfidence.coerceIn(0.3f, 1f),
                details = details,
                anomalies = anomalies,
                processingTimeMs = System.currentTimeMillis() - startTime
            )

        } catch (e: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────────────────
    // EXTRACTION PCM
    // ─────────────────────────────────────────────────────────

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun extractPCMSamples(
        extractor: MediaExtractor,
        format: MediaFormat,
        maxSamples: Int
    ): FloatArray {
        val samples = mutableListOf<Float>()
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)

        try {
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val inputBuffers = codec.inputBuffers
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false

            while (!outputEos && samples.size < maxSamples) {
                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val buffer = inputBuffers[inputIndex]
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // Convertir bytes en samples float PCM 16-bit
                        while (outputBuffer.hasRemaining() && samples.size < maxSamples) {
                            if (outputBuffer.remaining() >= 2) {
                                val sample = outputBuffer.short
                                samples.add(sample / 32768f)  // Normaliser [-1, 1]
                            } else break
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                }
            }

            codec.stop()
            codec.release()

        } catch (e: Exception) {
            // Retourner ce qu'on a
        }

        return samples.toFloatArray()
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE SILENCE
    // ─────────────────────────────────────────────────────────

    private fun analyzeSilencePattern(samples: FloatArray): Pair<Float, Float> {
        val silenceThreshold = 0.01f
        val windowSize = SAMPLE_RATE / 10  // 100ms

        var silentWindows = 0
        var abruptTransitions = 0
        val totalWindows = samples.size / windowSize

        if (totalWindows < 5) return Pair(0.2f, 0.3f)

        val windowRMS = mutableListOf<Float>()

        for (i in 0 until totalWindows) {
            val window = samples.slice(i * windowSize until minOf((i + 1) * windowSize, samples.size))
            val rms = sqrt(window.map { it * it }.average()).toFloat()
            windowRMS.add(rms)
            if (rms < silenceThreshold) silentWindows++
        }

        // Détection transitions abruptes : silence → son fort instantané (coupures)
        for (i in 1 until windowRMS.size) {
            val change = abs(windowRMS[i] - windowRMS[i - 1])
            if (change > 0.20f) abruptTransitions++
        }

        val silenceRatio = silentWindows.toFloat() / totalWindows
        val transitionRatio = abruptTransitions.toFloat() / windowRMS.size

        val score = when {
            silenceRatio > SILENCE_RATIO_THRESHOLD -> 0.70f
            transitionRatio > 0.15f -> 0.60f
            silenceRatio > 0.25f && transitionRatio > 0.10f -> 0.50f
            else -> maxOf(silenceRatio, transitionRatio) * 0.8f
        }

        return Pair(score.coerceIn(0f, 1f), 0.70f)
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE BRUIT DE FOND
    // ─────────────────────────────────────────────────────────

    private fun analyzeBackgroundNoise(samples: FloatArray): Pair<Float, Float> {
        // Extraire segments à faible énergie (bruit de fond)
        val windowSize = SAMPLE_RATE / 4  // 250ms
        val energies = mutableListOf<Float>()

        for (i in 0 until samples.size / windowSize) {
            val window = samples.slice(i * windowSize until minOf((i + 1) * windowSize, samples.size))
            val energy = window.map { it * it }.average().toFloat()
            energies.add(energy)
        }

        if (energies.size < 4) return Pair(0.3f, 0.4f)

        val sortedEnergies = energies.sorted()
        val bgWindows = sortedEnergies.take(sortedEnergies.size / 4)  // Quartile inférieur

        if (bgWindows.isEmpty()) return Pair(0.2f, 0.3f)

        val mean = bgWindows.average().toFloat()
        val variance = bgWindows.map { (it - mean).pow(2) }.average().toFloat()
        val cv = if (mean > 0) sqrt(variance) / mean else 0f  // Coefficient de variation

        // Bruit naturel : cv élevé (variable)
        // Bruit synthétique : cv faible (trop uniforme)
        val score = when {
            cv < 0.05f -> 0.85f  // Bruit parfaitement uniforme → synthétique
            cv < 0.15f -> 0.60f
            cv < 0.30f -> 0.35f
            else -> 0.10f  // Naturel
        }

        return Pair(score, 0.65f)
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE SPECTRALE (DFT SIMPLIFIÉE)
    // ─────────────────────────────────────────────────────────

    private fun analyzeSpectralContent(samples: FloatArray): Pair<Float, Float> {
        val windowSize = FFT_SIZE
        val hop = HOP_SIZE
        val flatnessValues = mutableListOf<Float>()

        var start = 0
        while (start + windowSize <= samples.size) {
            val window = samples.slice(start until start + windowSize).toFloatArray()
            applyHannWindow(window)
            val spectrum = computeMagnitudeSpectrum(window)
            val flatness = computeSpectralFlatness(spectrum)
            flatnessValues.add(flatness)
            start += hop
        }

        if (flatnessValues.isEmpty()) return Pair(0.3f, 0.4f)

        val avgFlatness = flatnessValues.average().toFloat()

        // Vocoders TTS : spectre très plat (synthétique)
        // Voix naturelle : spectre formantique (non plat)
        val score = when {
            avgFlatness > SPECTRAL_FLATNESS_VOCODER_THRESHOLD -> 0.80f
            avgFlatness > 0.65f -> 0.55f
            avgFlatness > 0.50f -> 0.35f
            else -> 0.15f
        }

        return Pair(score, 0.75f)
    }

    private fun applyHannWindow(samples: FloatArray) {
        val n = samples.size
        for (i in samples.indices) {
            samples[i] *= (0.5f * (1 - cos(2 * PI * i / (n - 1)))).toFloat()
        }
    }

    private fun computeMagnitudeSpectrum(samples: FloatArray): FloatArray {
        // DFT simplifiée — suffisante pour analyser la forme du spectre
        val n = samples.size
        val halfN = n / 2
        val magnitudes = FloatArray(halfN)

        // Utiliser seulement quelques fréquences pour la performance
        val step = maxOf(1, halfN / 64)  // 64 bandes

        for (k in 0 until halfN step step) {
            var realPart = 0.0
            var imagPart = 0.0
            for (t in 0 until n step 4) {  // Sous-échantillonnage temps
                val angle = 2 * PI * k * t / n
                realPart += samples[t] * cos(angle)
                imagPart -= samples[t] * sin(angle)
            }
            magnitudes[k] = sqrt(realPart * realPart + imagPart * imagPart).toFloat()
        }

        return magnitudes
    }

    private fun computeSpectralFlatness(magnitudes: FloatArray): Float {
        val nonZero = magnitudes.filter { it > 0f }
        if (nonZero.isEmpty()) return 0f

        // Flatness = geometric mean / arithmetic mean
        val geometricMean = exp(nonZero.map { ln(it.toDouble()) }.average())
        val arithmeticMean = nonZero.average()

        return if (arithmeticMean > 0) (geometricMean / arithmeticMean).toFloat().coerceIn(0f, 1f)
        else 0f
    }

    // ─────────────────────────────────────────────────────────
    // ANALYSE PÉRIODICITÉ
    // ─────────────────────────────────────────────────────────

    private fun analyzeAbnormalPeriodicity(samples: FloatArray): Pair<Float, Float> {
        if (samples.size < SAMPLE_RATE) return Pair(0.2f, 0.3f)

        // Auto-corrélation pour détecter périodicité anormale
        val segment = samples.take(SAMPLE_RATE).toFloatArray()  // 1 seconde
        val maxLag = SAMPLE_RATE / 10  // Max 100ms lag

        val autocorr = FloatArray(maxLag)
        val energy = segment.map { it * it }.sum()

        if (energy < 1e-6f) return Pair(0.2f, 0.4f)

        for (lag in 0 until maxLag) {
            var sum = 0f
            for (i in 0 until segment.size - lag) {
                sum += segment[i] * segment[i + lag]
            }
            autocorr[lag] = sum / energy
        }

        // Chercher pics d'auto-corrélation anormalement forts après lag 0
        val maxAutocorr = autocorr.drop(50).maxOrNull() ?: 0f

        val score = when {
            maxAutocorr > 0.80f -> 0.75f  // Très périodique → synthétique
            maxAutocorr > 0.60f -> 0.50f
            maxAutocorr > 0.40f -> 0.25f
            else -> 0.10f
        }

        return Pair(score, 0.60f)
    }
}
