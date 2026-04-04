package com.deepfakedetector.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.deepfakedetector.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    result: AnalysisResult,
    onBack: () -> Unit,
    onHistory: () -> Unit = onBack,
    onNewScan: () -> Unit = onBack,
    onEducationClick: () -> Unit = {}
) {
    var showDetailedScores by remember { mutableStateOf(false) }
    var showExplanation by remember { mutableStateOf(true) }
    var communityVote by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Résultat de l'analyse") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { /* partager */ }) {
                        Icon(Icons.Default.Share, "Partager")
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── 1. VERDICT PRINCIPAL ───────────────────────────
            VerdictCard(result = result)

            // ── 2. SCORE GLOBAL + CONFIANCE ───────────────────
            GlobalScoreCard(result = result)

            // ── 3. INDICATEUR FIABILITÉ ────────────────────────
            ReliabilityCard(result = result)

            // ── 4. EXPLICATION ────────────────────────────────
            ExplanationCard(
                result = result,
                expanded = showExplanation,
                onToggle = { showExplanation = !showExplanation }
            )

            // ── 5. SCORES DÉTAILLÉS ────────────────────────────
            DetailedScoresCard(
                moduleScores = result.moduleScores,
                expanded = showDetailedScores,
                onToggle = { showDetailedScores = !showDetailedScores }
            )

            // ── 6. MÉTADONNÉES VIDÉO ──────────────────────────
            VideoMetadataCard(metadata = result.videoMetadata, mode = result.analysisMode)

            // ── 7. MODE PÉDAGOGIQUE ───────────────────────────
            EducationCard(onEducationClick = onEducationClick)

            // ── 8. COMMUNAUTÉ ─────────────────────────────────
            CommunityCard(
                result = result,
                myVote = communityVote,
                onVote = { isFake -> communityVote = isFake }
            )

            // ── 9. AVERTISSEMENTS ─────────────────────────────
            WarningsCard(warnings = result.warnings)

            // ── 10. ACTIONS ───────────────────────────────────
            Button(
                onClick = onNewScan,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.VideoLibrary, null)
                Spacer(Modifier.width(8.dp))
                Text("Analyser une nouvelle vidéo")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────
// VERDICT CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun VerdictCard(result: AnalysisResult) {
    val (bgColor, textColor) = when (result.verdictLevel) {
        VerdictLevel.FAKE -> Pair(Color(0xFFFFEBEE), Color(0xFFB71C1C))
        VerdictLevel.LIKELY_FAKE -> Pair(Color(0xFFFFF3E0), Color(0xFFE65100))
        VerdictLevel.UNCERTAIN -> Pair(Color(0xFFFFFDE7), Color(0xFFF57F17))
        VerdictLevel.LIKELY_REAL -> Pair(Color(0xFFF3E5F5), Color(0xFF4A148C))
        VerdictLevel.REAL -> Pair(Color(0xFFE8F5E9), Color(0xFF1B5E20))
    }

    val emoji = when (result.verdictLevel) {
        VerdictLevel.FAKE -> "🔴"
        VerdictLevel.LIKELY_FAKE -> "🟠"
        VerdictLevel.UNCERTAIN -> "🟡"
        VerdictLevel.LIKELY_REAL -> "🟣"
        VerdictLevel.REAL -> "🟢"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 48.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                result.verdictText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                result.videoName,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.7f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// GLOBAL SCORE CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun GlobalScoreCard(result: AnalysisResult) {
    val animatedScore by animateFloatAsState(
        targetValue = result.overallScore,
        animationSpec = tween(1200, easing = EaseOutCubic),
        label = "score"
    )

    val scoreColor = when {
        result.overallScore >= 0.75f -> Color(0xFFD32F2F)
        result.overallScore >= 0.55f -> Color(0xFFF57C00)
        result.overallScore >= 0.45f -> Color(0xFFF9A825)
        result.overallScore >= 0.25f -> Color(0xFF558B2F)
        else -> Color(0xFF2E7D32)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Probabilité IA",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${(animatedScore * 100).toInt()}%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = scoreColor
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animatedScore },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                    color = scoreColor,
                    trackColor = scoreColor.copy(alpha = 0.15f)
                )
            }

            Spacer(Modifier.width(20.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Confiance",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${result.confidenceScorePercent}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${result.totalProcessingTimeMs / 1000}.${(result.totalProcessingTimeMs % 1000) / 100}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// RELIABILITY INDICATOR
// ─────────────────────────────────────────────────────────────

@Composable
fun ReliabilityCard(result: AnalysisResult) {
    val (emoji, color, desc) = when (result.reliabilityLevel) {
        ReliabilityLevel.RELIABLE -> Triple("🟢", Color(0xFF2E7D32), "Résultat fiable — analyse complète")
        ReliabilityLevel.UNCERTAIN -> Triple("🟡", Color(0xFFF57C00), "Résultat incertain — interpréter avec précaution")
        ReliabilityLevel.SUSPICIOUS -> Triple("🔴", Color(0xFFD32F2F), "Faible fiabilité — données insuffisantes")
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(result.reliabilityLevel.label,
                    fontWeight = FontWeight.SemiBold,
                    color = color)
                Text(desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// DETAILED SCORES CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun DetailedScoresCard(
    moduleScores: ModuleScores,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Analytics, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scores détaillés",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    moduleScores.allScores().forEach { (name, module) ->
                        ModuleScoreRow(name = name, module = module)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            if (!expanded) {
                Spacer(Modifier.height(8.dp))
                // Mini résumé
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    moduleScores.allScores().entries.take(4).forEach { (name, module) ->
                        MiniScoreChip(
                            name = name.take(5),
                            score = module.score,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModuleScoreRow(name: String, module: ModuleScore) {
    val animatedScore by animateFloatAsState(
        targetValue = module.score,
        animationSpec = tween(800),
        label = "moduleScore"
    )

    val barColor = when {
        module.score >= 0.7f -> Color(0xFFD32F2F)
        module.score >= 0.5f -> Color(0xFFF57C00)
        else -> Color(0xFF388E3C)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(module.score * 100).toInt()}%",
                    fontWeight = FontWeight.Bold,
                    color = barColor
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "(conf. ${module.confidencePercent}%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { animatedScore },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = barColor,
            trackColor = barColor.copy(alpha = 0.12f)
        )
        // Anomalies principales
        if (module.anomalies.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            module.anomalies.take(2).forEach { anomaly ->
                Text(
                    "⚠ ${anomaly.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MiniScoreChip(name: String, score: Float, modifier: Modifier = Modifier) {
    val color = when {
        score >= 0.7f -> Color(0xFFD32F2F)
        score >= 0.5f -> Color(0xFFF57C00)
        else -> Color(0xFF388E3C)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("${(score * 100).toInt()}%",
                fontWeight = FontWeight.Bold,
                color = color,
                style = MaterialTheme.typography.bodySmall)
            Text(name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// EXPLANATION CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun ExplanationCard(
    result: AnalysisResult,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LightMode, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Explication",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }

            Spacer(Modifier.height(8.dp))

            // Key findings — toujours visibles
            result.keyFindings.take(3).forEach { finding ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("• ", color = MaterialTheme.colorScheme.primary)
                    Text(finding, style = MaterialTheme.typography.bodySmall)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        result.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// VIDEO METADATA CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun VideoMetadataCard(metadata: VideoMetadata, mode: AnalysisMode) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Métadonnées vidéo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    MetaRow("Résolution", metadata.resolution)
                    MetaRow("FPS", metadata.fpsFormatted)
                    MetaRow("Durée", "${metadata.durationSeconds.toInt()}s")
                    MetaRow("Codec", metadata.codec.ifEmpty { "Inconnu" })
                    MetaRow("Taille", "${"%.1f".format(metadata.fileSizeMb)} Mo")
                    MetaRow("Bitrate", "${metadata.bitrate / 1000} kbps")
                    if (metadata.encoder != null)
                        MetaRow("Encodeur", metadata.encoder, isWarning = true)
                    if (metadata.creationDate != null)
                        MetaRow("Date", metadata.creationDate)
                    MetaRow("Mode analyse", mode.label)
                }
            }
        }
    }
}

@Composable
fun MetaRow(label: String, value: String, isWarning: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (isWarning) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface)
    }
}

// ─────────────────────────────────────────────────────────────
// EDUCATION CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun EducationCard(onEducationClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEducationClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.School,
                null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Pourquoi cette vidéo est suspecte ?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Comprendre l'analyse en détail",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// COMMUNITY CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun CommunityCard(
    result: AnalysisResult,
    myVote: Boolean?,
    onVote: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Group, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Avis communautaire",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(12.dp))

            result.communityReport?.let { report ->
                if (report.alreadyFlagged && report.flaggedCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFEBEE)
                    ) {
                        Text(
                            "⚠️ Cette vidéo a déjà été signalée ${report.flaggedCount} fois",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB71C1C)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                report.communityVerdict?.let {
                    Text(it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
            } ?: Text(
                "Soyez le premier à donner votre avis",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            Text("Votre avis :", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onVote(true) },
                    colors = if (myVote == true) ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFFFEBEE)
                    ) else ButtonDefaults.outlinedButtonColors(),
                    border = BorderStroke(
                        1.5.dp,
                        if (myVote == true) Color(0xFFD32F2F)
                        else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text("🤖 C'est un Fake")
                }

                OutlinedButton(
                    onClick = { onVote(false) },
                    colors = if (myVote == false) ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFE8F5E9)
                    ) else ButtonDefaults.outlinedButtonColors(),
                    border = BorderStroke(
                        1.5.dp,
                        if (myVote == false) Color(0xFF2E7D32)
                        else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text("✅ C'est réel")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// WARNINGS CARD
// ─────────────────────────────────────────────────────────────

@Composable
fun WarningsCard(warnings: List<String>) {
    if (warnings.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Limites de l'analyse",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            warnings.forEach { warning ->
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
