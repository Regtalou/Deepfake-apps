package com.deepfakedetector.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.deepfakedetector.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * MetadataAnalyzer — Analyse forensique des métadonnées vidéo
 *
 * Un deepfake laisse souvent des traces dans les métadonnées :
 * - Logiciel de création (FFmpeg, Python, Adobe)
 * - Date de création incohérente
 * - Codec inhabituel ou paramètres anormaux
 * - Ratio bitrate/qualité suspect
 * - Métadonnées manquantes ou tronquées
 */
@Singleton
class MetadataAnalyzer @Inject constructor(
    private val context: Context
) {
    // Encodeurs associés à des outils de génération IA
    private val aiEncoderSignatures = setOf(
        "lavf", "libav", "ffmpeg", "python", "deepfacelab", "faceswap",
        "dfl", "simswap", "roop", "insightface", "mediapipe",
        "torch", "tensorflow", "onnx", "runway", "pika", "sora",
        "stable-diffusion", "openai", "gen-2"
    )

    // Codecs rares ou inhabituels (indice possible)
    private val suspiciousCodecs = setOf("vp8", "theora", "mpeg4", "rawvideo")

    // FPS suspects (hors standards)
    private val standardFPS = setOf(24.0, 25.0, 30.0, 48.0, 60.0, 23.976, 29.97)

    suspend fun analyze(uri: Uri): Pair<ModuleScore, VideoMetadata> =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val anomalies = mutableListOf<Anomaly>()
            val details = mutableListOf<String>()
            var score = 0f
            var confidence = 0f

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)

                // ── Extraction métadonnées ─────────────────────
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val fps = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                    ?: retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.let { frameCount ->
                        if (durationMs > 0) frameCount.toFloat() / (durationMs / 1000f) else 0f
                    } ?: 0f
                val bitrate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                val encoder = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_WRITER)
                val codec = getCodecInfo(retriever)
                val creationDate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DATE)
                val hasAudio = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val audioSampleRate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                val audioChannels = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

                // Taille fichier
                val fileSizeBytes = getFileSize(context, uri)

                val metadata = VideoMetadata(
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    fps = fps,
                    codec = codec,
                    bitrate = bitrate,
                    fileSizeBytes = fileSizeBytes,
                    creationDate = creationDate,
                    encoder = encoder,
                    hasAudioTrack = hasAudio,
                    audioSampleRate = audioSampleRate,
                    audioChannels = audioChannels,
                    rotationDegrees = rotation
                )

                // ── ANALYSE 1 : Encodeur IA ────────────────────
                if (encoder != null) {
                    val encoderLower = encoder.lowercase()
                    val matchedSignature = aiEncoderSignatures.firstOrNull { sig ->
                        encoderLower.contains(sig)
                    }
                    if (matchedSignature != null) {
                        score += 0.85f * 0.30f
                        confidence += 0.90f * 0.30f
                        details.add("Encodeur associé à des outils IA détecté : \"$encoder\"")
                        anomalies.add(Anomaly(
                            type = "AI_ENCODER_SIGNATURE",
                            severity = AnomalySeverity.CRITICAL,
                            description = "L'encodeur révèle un outil de génération IA",
                            technicalDetail = "Encoder: $encoder, Match: $matchedSignature"
                        ))
                    } else {
                        confidence += 0.75f * 0.30f
                        details.add("Encodeur : $encoder (non suspect)")
                    }
                } else {
                    // Métadonnées encodeur manquantes → léger indice
                    score += 0.30f * 0.30f
                    confidence += 0.60f * 0.30f
                    details.add("Métadonnées encodeur absentes ou supprimées")
                    anomalies.add(Anomaly(
                        type = "MISSING_ENCODER_METADATA",
                        severity = AnomalySeverity.LOW,
                        description = "Informations d'encodage supprimées",
                        technicalDetail = "Encoder field: null"
                    ))
                }

                // ── ANALYSE 2 : FPS suspect ────────────────────
                if (fps > 0) {
                    val closestStandard = standardFPS.minByOrNull { abs(it - fps) } ?: 30.0
                    val fpsDiff = abs(fps - closestStandard)

                    if (fpsDiff > 2.0) {
                        score += 0.50f * 0.15f
                        confidence += 0.70f * 0.15f
                        details.add("FPS non standard : ${fps.format(2)} fps")
                        anomalies.add(Anomaly(
                            type = "NONSTANDARD_FPS",
                            severity = AnomalySeverity.MEDIUM,
                            description = "Framerate inhabituel pour une vidéo naturelle",
                            technicalDetail = "FPS: $fps, Closest standard: $closestStandard"
                        ))
                    } else {
                        confidence += 0.65f * 0.15f
                    }
                }

                // ── ANALYSE 3 : Bitrate anormal ───────────────
                if (bitrate > 0 && width > 0 && height > 0 && durationMs > 0) {
                    val pixelsPerSec = width.toLong() * height * fps
                    val bitsPerPixelPerSec = bitrate.toDouble() / pixelsPerSec

                    when {
                        bitsPerPixelPerSec < 0.001 -> {
                            // Bitrate trop faible → compression extrême (re-encodage)
                            score += 0.55f * 0.20f
                            confidence += 0.70f * 0.20f
                            details.add("Bitrate anormalement bas : ${bitrate / 1000} kbps")
                            anomalies.add(Anomaly(
                                type = "LOW_BITRATE",
                                severity = AnomalySeverity.MEDIUM,
                                description = "Compression excessive — re-encodage probable",
                                technicalDetail = "Bitrate: ${bitrate}bps, BPP: ${"%.5f".format(bitsPerPixelPerSec)}"
                            ))
                        }
                        bitsPerPixelPerSec > 1.0 -> {
                            // Bitrate très élevé → peut indiquer stream non compressé (outil IA)
                            score += 0.35f * 0.20f
                            confidence += 0.55f * 0.20f
                            details.add("Bitrate anormalement élevé")
                            anomalies.add(Anomaly(
                                type = "HIGH_BITRATE",
                                severity = AnomalySeverity.LOW,
                                description = "Débit binaire inhabituellement élevé",
                                technicalDetail = "Bitrate: ${bitrate}bps"
                            ))
                        }
                        else -> confidence += 0.70f * 0.20f
                    }
                }

                // ── ANALYSE 4 : Date de création ──────────────
                if (creationDate != null) {
                    val dateScore = analyzeDateAnomaly(creationDate)
                    score += dateScore.first * 0.15f
                    confidence += dateScore.second * 0.15f
                    if (dateScore.first > 0.5f) {
                        details.add("Date de création incohérente ou dans le futur")
                        anomalies.add(Anomaly(
                            type = "DATE_ANOMALY",
                            severity = AnomalySeverity.MEDIUM,
                            description = "Horodatage suspect",
                            technicalDetail = "Creation date: $creationDate"
                        ))
                    }
                } else {
                    confidence += 0.40f * 0.15f
                }

                // ── ANALYSE 5 : Codec ─────────────────────────
                if (codec.isNotEmpty()) {
                    if (suspiciousCodecs.any { codec.lowercase().contains(it) }) {
                        score += 0.45f * 0.10f
                        confidence += 0.65f * 0.10f
                        details.add("Codec inhabituel : $codec")
                        anomalies.add(Anomaly(
                            type = "UNUSUAL_CODEC",
                            severity = AnomalySeverity.LOW,
                            description = "Codec rarement utilisé pour des vidéos naturelles",
                            technicalDetail = "Codec: $codec"
                        ))
                    } else {
                        confidence += 0.70f * 0.10f
                    }
                }

                // ── ANALYSE 6 : Ratio taille/durée ───────────
                if (fileSizeBytes > 0 && durationMs > 0) {
                    val bytesPerSec = fileSizeBytes.toDouble() / (durationMs / 1000.0)
                    val expectedBytesPerSec = bitrate / 8.0

                    if (expectedBytesPerSec > 0) {
                        val sizeRatio = bytesPerSec / expectedBytesPerSec
                        if (sizeRatio < 0.3 || sizeRatio > 5.0) {
                            score += 0.40f * 0.10f
                            confidence += 0.60f * 0.10f
                            details.add("Incohérence entre taille et durée de la vidéo")
                        } else {
                            confidence += 0.65f * 0.10f
                        }
                    }
                }

                if (details.isEmpty()) {
                    details.add("Métadonnées : aucune anomalie détectée")
                }

                val moduleScore = ModuleScore(
                    score = score.coerceIn(0f, 1f),
                    confidence = confidence.coerceIn(0.3f, 1f),
                    details = details,
                    anomalies = anomalies,
                    processingTimeMs = System.currentTimeMillis() - startTime
                )

                Pair(moduleScore, metadata)

            } finally {
                retriever.release()
            }
        }

    private fun getCodecInfo(retriever: MediaMetadataRetriever): String {
        // Tenter d'extraire info codec depuis les métadonnées
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            ?.substringAfter("/")
            ?: ""
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun analyzeDateAnomaly(dateStr: String): Pair<Float, Float> {
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
                SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            )

            var date: Date? = null
            for (format in formats) {
                try {
                    date = format.parse(dateStr)
                    break
                } catch (e: Exception) { }
            }

            if (date == null) return Pair(0.2f, 0.3f)

            val now = Date()
            val future = Date(now.time + 24 * 60 * 60 * 1000L)  // +24h

            when {
                date.after(future) -> Pair(0.80f, 0.85f)  // Date dans le futur
                date.before(Date(0)) -> Pair(0.70f, 0.80f)  // Date avant 1970
                date.before(Date(631152000000L)) -> Pair(0.40f, 0.60f)  // Avant 1990 (improbable vidéo numérique)
                else -> Pair(0.05f, 0.70f)
            }
        } catch (e: Exception) {
            Pair(0.2f, 0.3f)
        }
    }

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)
}
