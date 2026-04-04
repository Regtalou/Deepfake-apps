package com.deepfakedetector.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.deepfakedetector.ui.theme.DeepfakeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EducationScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Centre éducatif", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Intro
            item { EducationIntroCard() }

            // Chapitres
            items(educationChapters) { chapter ->
                EducationChapterCard(chapter)
            }

            // Quiz
            item { QuizCard() }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ─── Intro ────────────────────────────────────────────────────────────────────

@Composable
private fun EducationIntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(DeepfakeColors.ElectricContainer, DeepfakeColors.Surface2)
                    )
                )
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🎓", style = MaterialTheme.typography.displaySmall)
                    Column {
                        Text(
                            "Comprendre les deepfakes",
                            style      = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Guide interactif pour détecter les manipulations vidéo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "Les deepfakes sont des vidéos générées ou altérées par intelligence artificielle pour faire dire ou faire des choses à des personnes réelles. Leur sophistication croissante rend la détection humaine de plus en plus difficile.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ─── Chapitres ────────────────────────────────────────────────────────────────

data class EducationChapter(
    val emoji: String,
    val title: String,
    val subtitle: String,
    val content: String,
    val signals: List<String>,
    val color: Color
)

val educationChapters = listOf(
    EducationChapter(
        emoji    = "👁️",
        title    = "Artefacts visuels",
        subtitle = "Ce que vos yeux peuvent détecter",
        content  = "Les IA de génération vidéo laissent souvent des traces visibles, en particulier aux transitions ou sur les zones à fort détail (cheveux, dents, oreilles).",
        signals  = listOf(
            "Contours flous ou imprécis autour du visage",
            "Clignements d'yeux irréguliers ou absents",
            "Asymétrie faciale anormale",
            "Distorsions lors des mouvements rapides",
            "Texture de peau lissée ou plastifiée",
            "Bijoux, lunettes ou cheveux mal rendus"
        ),
        color = DeepfakeColors.Electric
    ),
    EducationChapter(
        emoji    = "🎵",
        title    = "Indices audio",
        subtitle = "Écouter au-delà des mots",
        content  = "Le clonage vocal est souvent plus facile à détecter que le deepfake vidéo. Les synthèses vocales actuelles peinent avec les émotions subtiles et les bruits de fond.",
        signals  = listOf(
            "Voix trop lisse, sans aspérités naturelles",
            "Silences abrupts entre les mots",
            "Désynchronisation avec les lèvres",
            "Bruit de fond incohérent avec l'environnement",
            "Émotions vocales qui ne correspondent pas au contexte",
            "Réverbération artificielle ou métallique"
        ),
        color = DeepfakeColors.WarnAmber
    ),
    EducationChapter(
        emoji    = "🧬",
        title    = "Cohérence physiologique",
        subtitle = "Ce que le corps révèle",
        content  = "Notre corps génère des signaux biologiques difficiles à falsifier : battements cardiaques visibles dans la peau, micro-expressions, coordination gestes/paroles.",
        signals  = listOf(
            "Absence de pulsation visible au niveau du front/tempes",
            "Micro-expressions incohérentes",
            "Gestuelle qui ne suit pas naturellement les mots",
            "Réflexion dans les yeux absente ou figée",
            "Mouvement de tête non naturel ou trop stable",
            "Rougeurs ou pâleurs émotionnelles absentes"
        ),
        color = DeepfakeColors.SafeGreen
    ),
    EducationChapter(
        emoji    = "📊",
        title    = "Métadonnées & contexte",
        subtitle = "La provenance du fichier",
        content  = "Les fichiers vidéo contiennent des informations cachées révélatrices. Un deepfake passe souvent par des outils de compression/encodage spécifiques qui laissent des traces.",
        signals  = listOf(
            "Encodeur inconnu ou lié à des outils IA (ffmpeg, DeepFaceLab…)",
            "FPS non standard (ex: 23.976 → 25.0 exact)",
            "Date de création incohérente avec le contenu",
            "Bitrate anormalement bas pour la résolution",
            "Codec inhabituel pour la plateforme de publication",
            "Ratio taille/durée suspect"
        ),
        color = DeepfakeColors.ElectricDark
    ),
    EducationChapter(
        emoji    = "🔄",
        title    = "Techniques courantes",
        subtitle = "Comment sont-ils créés ?",
        content  = "Comprendre les méthodes de création aide à identifier les failles spécifiques de chaque technique.",
        signals  = listOf(
            "Face-swap (FaceSwap, DeepFaceLab) : remplace le visage seul",
            "Lip-sync (Wav2Lip) : synchronise les lèvres sur un audio",
            "Full synthesis (StyleGAN, Stable Video) : génère tout",
            "Voice cloning (ElevenLabs, Tortoise) : clone la voix",
            "Puppeteering : transfère les mouvements d'un acteur",
            "Inpainting : modifie une zone spécifique de la vidéo"
        ),
        color = DeepfakeColors.DangerRed
    )
)

@Composable
private fun EducationChapterCard(chapter: EducationChapter) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        onClick  = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment  = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(chapter.color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(chapter.emoji, style = MaterialTheme.typography.titleMedium)
                    }
                    Column {
                        Text(
                            chapter.title,
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            chapter.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Contenu expandable
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider()

                    Text(
                        chapter.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        "Signaux à surveiller",
                        style      = MaterialTheme.typography.labelLarge,
                        color      = chapter.color,
                        fontWeight = FontWeight.SemiBold
                    )

                    chapter.signals.forEach { signal ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment     = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(chapter.color)
                            )
                            Text(
                                signal,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Quiz rapide ──────────────────────────────────────────────────────────────

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

val quizQuestions = listOf(
    QuizQuestion(
        question     = "Quel est l'indice le plus fiable d'un deepfake face-swap ?",
        options      = listOf(
            "La qualité de l'image en général",
            "Les artefacts aux contours du visage et dans les cheveux",
            "La durée de la vidéo",
            "Le format de fichier"
        ),
        correctIndex = 1,
        explanation  = "Les outils de face-swap laissent des artefacts caractéristiques aux limites entre le visage généré et la peau/cheveux natifs."
    ),
    QuizQuestion(
        question     = "Pourquoi les clignements d'yeux sont-ils un indice ?",
        options      = listOf(
            "Les yeux clignent toujours au même rythme dans les deepfakes",
            "Les premiers modèles étaient entraînés sur des photos sans clignements",
            "La couleur des yeux change dans les deepfakes",
            "Les yeux sont toujours flous"
        ),
        correctIndex = 1,
        explanation  = "Les premiers générateurs de deepfake étaient entraînés sur des jeux de photos où les personnes regardaient la caméra avec les yeux ouverts, ce qui rendait les clignements rares. Les modèles récents ont corrigé ce problème."
    ),
    QuizQuestion(
        question     = "Qu'est-ce que le rPPG dans la détection de deepfakes ?",
        options      = listOf(
            "Un format de compression vidéo",
            "Une mesure du pouls via les variations de couleur de la peau",
            "Un outil d'encodage IA",
            "Un type de réseau de neurones"
        ),
        correctIndex = 1,
        explanation  = "Le remote PhotoPlethysmoGraphy (rPPG) détecte les micro-variations de teinte cutanée causées par les battements cardiaques. Les vidéos synthétiques ne reproduisent généralement pas ce signal physiologique."
    )
)

@Composable
private fun QuizCard() {
    var currentQuestion by remember { mutableStateOf(0) }
    var selectedAnswer  by remember { mutableStateOf<Int?>(null) }
    var score           by remember { mutableStateOf(0) }
    var finished        by remember { mutableStateOf(false) }

    val question = quizQuestions[currentQuestion]

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🧩", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Quiz rapide",
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${currentQuestion + 1} / ${quizQuestions.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider()

            if (finished) {
                // Résultat final
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (score == quizQuestions.size) "🏆" else "📚",
                        style = MaterialTheme.typography.displaySmall
                    )
                    Text(
                        "Score : $score / ${quizQuestions.size}",
                        style      = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color      = if (score >= 2) DeepfakeColors.SafeGreen else DeepfakeColors.WarnAmber
                    )
                    Text(
                        if (score == quizQuestions.size) "Excellent ! Vous êtes prêt à détecter les deepfakes."
                        else "Continuez à apprendre, les deepfakes deviennent de plus en plus sophistiqués.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            currentQuestion = 0
                            selectedAnswer  = null
                            score           = 0
                            finished        = false
                        }
                    ) {
                        Text("Recommencer")
                    }
                }
            } else {
                // Question
                Text(
                    question.question,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                // Options
                question.options.forEachIndexed { index, option ->
                    val bgColor = when {
                        selectedAnswer == null               -> MaterialTheme.colorScheme.surfaceVariant
                        index == question.correctIndex       -> DeepfakeColors.SafeGreen.copy(alpha = 0.2f)
                        index == selectedAnswer              -> DeepfakeColors.DangerRed.copy(alpha = 0.2f)
                        else                                 -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    Surface(
                        modifier  = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = selectedAnswer == null) {
                                selectedAnswer = index
                                if (index == question.correctIndex) score++
                            },
                        color = bgColor,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            val prefix = when {
                                selectedAnswer != null && index == question.correctIndex -> "✅"
                                selectedAnswer == index && index != question.correctIndex -> "❌"
                                else -> "${('A' + index)}"
                            }
                            Text(prefix, style = MaterialTheme.typography.labelLarge)
                            Text(option, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // Explication + bouton suivant
                AnimatedVisibility(visible = selectedAnswer != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                "💡 ${question.explanation}",
                                modifier = Modifier.padding(12.dp),
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                if (currentQuestion < quizQuestions.size - 1) {
                                    currentQuestion++
                                    selectedAnswer = null
                                } else {
                                    finished = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (currentQuestion < quizQuestions.size - 1)
                                    "Question suivante →"
                                else
                                    "Voir le résultat"
                            )
                        }
                    }
                }
            }
        }
    }
}
