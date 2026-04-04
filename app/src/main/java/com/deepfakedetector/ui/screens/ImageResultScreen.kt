package com.deepfakedetector.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepfakedetector.data.*
import com.deepfakedetector.ui.theme.DeepfakeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageResultScreen(
    result: ImageAnalysisResult,
    onBack: () -> Unit,
    onShare: () -> Unit = {}
) {
    val verdictColor = verdictColor(result.verdictLevel)
    val scrollState  = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Résultat — Image", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Partager")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Verdict principal ────────────────────────────────────────
            ImageVerdictCard(result, verdictColor)

            // ── Score global ─────────────────────────────────────────────
            ImageScoreCard(result, verdictColor)

            // ── Fiabilité ────────────────────────────────────────────────
            ReliabilityBadge(result.reliabilityLevel, result.confidenceScorePercent)

            // ── Scores par module ────────────────────────────────────────
            ImageModuleScoresCard(result.moduleScores)

            // ── Explication ──────────────────────────────────────────────
            ExplanationCard(result.explanation)

            // ── Métadonnées image ────────────────────────────────────────
            ImageMetadataCard(result.metadata)

            // ── Findings clés ────────────────────────────────────────────
            if (result.keyFindings.isNotEmpty()) {
                KeyFindingsCard(result.keyFindings)
            }

            // ── Warnings ─────────────────────────────────────────────────
            if (result.warnings.isNotEmpty()) {
                WarningsBanner(result.warnings)
            }

            // ── Note éducative ───────────────────────────────────────────
            ImageEducationNote(result.verdictLevel)

            Spacer(Modifier.height(80.dp))
        }
    }
}

// ─── Verdict principal ────────────────────────────────────────────────────────

@Composable
private fun ImageVerdictCard(result: ImageAnalysisResult, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(color.copy(alpha = 0.20f), color.copy(alpha = 0.05f)))
                )
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Badge IMAGE
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Image, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "ANALYSE IMAGE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    verdictEmoji(result.verdictLevel),
                    fontSize = 56.sp
                )

                Text(
                    result.verdictText,
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color      = color,
                    textAlign  = TextAlign.Center
                )

                Text(
                    result.imageName,
                    style  = MaterialTheme.typography.bodySmall,
                    color  = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ─── Score global animé ───────────────────────────────────────────────────────

@Composable
private fun ImageScoreCard(result: ImageAnalysisResult, color: Color) {
    var animated by remember { mutableStateOf(false) }
    val animatedScore by animateFloatAsState(
        targetValue  = if (animated) result.overallScore else 0f,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label         = "score"
    )
    LaunchedEffect(Unit) { animated = true }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Probabilité de génération IA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress     = { animatedScore },
                        modifier     = Modifier.size(80.dp),
                        color        = color,
                        trackColor   = color.copy(alpha = 0.15f),
                        strokeWidth  = 7.dp
                    )
                    Text(
                        "${(animatedScore * 100).toInt()}%",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color      = color
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ScoreBarRow("Pixel & FFT", result.moduleScores.pixelAnalysis?.score ?: 0f)
                    ScoreBarRow("Statistiques", result.moduleScores.statistics?.score ?: 0f)
                    ScoreBarRow("Artefacts IA", result.moduleScores.artifactDetection?.score ?: 0f)
                    ScoreBarRow("Métadonnées", result.moduleScores.metadataAnalysis?.score ?: 0f)
                }
            }
        }
    }
}

@Composable
private fun ScoreBarRow(label: String, score: Float) {
    val color = when {
        score > 0.65f -> DeepfakeColors.DangerRed
        score > 0.45f -> DeepfakeColors.WarnAmber
        else          -> DeepfakeColors.SafeGreen
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(90.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress   = { score },
            modifier   = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(50)),
            color      = color,
            trackColor = color.copy(alpha = 0.15f)
        )
        Text(
            "${(score * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.width(32.dp)
        )
    }
}

// ─── Badge fiabilité ──────────────────────────────────────────────────────────

@Composable
private fun ReliabilityBadge(level: ReliabilityLevel, confidencePct: Int) {
    val (color, bg) = when (level) {
        ReliabilityLevel.RELIABLE  -> DeepfakeColors.SafeGreen  to DeepfakeColors.SafeContainer
        ReliabilityLevel.UNCERTAIN -> DeepfakeColors.WarnAmber  to DeepfakeColors.WarnContainer
        ReliabilityLevel.SUSPICIOUS -> DeepfakeColors.DangerRed to DeepfakeColors.DangerContainer
    }
    Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(level.emoji, style = MaterialTheme.typography.titleMedium)
                Column {
                    Text("Fiabilité de l'analyse", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(level.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
                }
            }
            Text("$confidencePct%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

// ─── Scores modules image ─────────────────────────────────────────────────────

@Composable
private fun ImageModuleScoresCard(scores: ImageModuleScores) {
    var expanded by remember { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Détail des analyses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    scores.pixelAnalysis?.let {
                        ImageModuleRow("🔬", "Pixel & FFT", "Bruit capteur, fréquences, lissage", it)
                    }
                    scores.statistics?.let {
                        ImageModuleRow("📊", "Statistiques", "Histogramme, entropie, couleurs", it)
                    }
                    scores.artifactDetection?.let {
                        ImageModuleRow("🎭", "Artefacts IA", "Symétrie, répétitions, halos", it)
                    }
                    scores.metadataAnalysis?.let {
                        ImageModuleRow("🗂️", "Métadonnées", "EXIF, caméra, logiciel", it)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageModuleRow(emoji: String, name: String, subtitle: String, score: ModuleScore) {
    val color = when {
        score.score > 0.65f -> DeepfakeColors.DangerRed
        score.score > 0.45f -> DeepfakeColors.WarnAmber
        else                -> DeepfakeColors.SafeGreen
    }
    var showDetails by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { showDetails = !showDetails },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${score.scorePercent}%",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = color
            )
            LinearProgressIndicator(
                progress   = { score.score },
                modifier   = Modifier.width(60.dp).height(5.dp).clip(RoundedCornerShape(50)),
                color      = color,
                trackColor = color.copy(alpha = 0.15f)
            )
        }
        AnimatedVisibility(visible = showDetails) {
            Column(Modifier.padding(start = 36.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                score.details.forEach { detail ->
                    Text("• $detail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ─── Explication ──────────────────────────────────────────────────────────────

@Composable
private fun ExplanationCard(explanation: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Analyse", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─── Métadonnées image ────────────────────────────────────────────────────────

@Composable
private fun ImageMetadataCard(meta: ImageMetadata) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Informations image", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val rows = buildList {
                add("Résolution" to meta.resolution)
                add("Taille" to "${"%.1f".format(meta.fileSizeKb)} Ko")
                add("Mégapixels" to "${"%.1f".format(meta.megapixels)} MP")
                add("Format" to (if (meta.mimeType.isNotEmpty()) meta.mimeType else "Inconnu"))
                add("EXIF" to (if (meta.hasExif) "Présent ✓" else "Absent ⚠️"))
                meta.cameraModel?.let { add("Caméra" to it) }
                meta.software?.let { add("Logiciel" to it) }
                add("GPS" to (if (meta.gpsPresent) "Oui" else "Non"))
            }
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ─── Key Findings ─────────────────────────────────────────────────────────────

@Composable
private fun KeyFindingsCard(findings: List<String>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FindInPage, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Anomalies détectées", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            findings.forEach { finding ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(finding, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ─── Warnings ─────────────────────────────────────────────────────────────────

@Composable
private fun WarningsBanner(warnings: List<String>) {
    Surface(
        color = DeepfakeColors.DangerContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Alertes", style = MaterialTheme.typography.labelLarge, color = DeepfakeColors.DangerRed, fontWeight = FontWeight.Bold)
            warnings.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = DeepfakeColors.DangerRed)
            }
        }
    }
}

// ─── Note éducative ───────────────────────────────────────────────────────────

@Composable
private fun ImageEducationNote(verdict: VerdictLevel) {
    val tips = when (verdict) {
        VerdictLevel.FAKE, VerdictLevel.LIKELY_FAKE -> listOf(
            "Les images IA manquent souvent de cohérence dans les détails fins (dents, mains, bijoux)",
            "Les reflets dans les yeux sont souvent absents ou incohérents dans les images générées",
            "Vérifiez les arrière-plans : l'IA génère souvent des structures architecturales impossibles"
        )
        VerdictLevel.LIKELY_REAL, VerdictLevel.REAL -> listOf(
            "Une image réelle contient toujours du bruit de capteur naturel",
            "Les métadonnées EXIF présentes et cohérentes sont un bon signe d'authenticité"
        )
        VerdictLevel.UNCERTAIN -> listOf(
            "En cas de doute, cherchez l'image originale avec la recherche d'images inversée",
            "Les outils comme FotoForensics ou Hive Moderation peuvent compléter cette analyse"
        )
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DeepfakeColors.ElectricContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("💡", style = MaterialTheme.typography.titleSmall)
                Text("À savoir", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = DeepfakeColors.Electric)
            }
            tips.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun verdictColor(level: VerdictLevel): Color = when (level) {
    VerdictLevel.FAKE, VerdictLevel.LIKELY_FAKE -> DeepfakeColors.DangerRed
    VerdictLevel.REAL, VerdictLevel.LIKELY_REAL -> DeepfakeColors.SafeGreen
    VerdictLevel.UNCERTAIN                      -> DeepfakeColors.WarnAmber
}

private fun verdictEmoji(level: VerdictLevel): String = when (level) {
    VerdictLevel.FAKE        -> "🚫"
    VerdictLevel.LIKELY_FAKE -> "⚠️"
    VerdictLevel.UNCERTAIN   -> "❓"
    VerdictLevel.LIKELY_REAL -> "✅"
    VerdictLevel.REAL        -> "🟢"
}
