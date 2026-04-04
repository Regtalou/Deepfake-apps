package com.deepfakedetector.receiver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.deepfakedetector.MainActivity
import com.deepfakedetector.ui.theme.DeepfakeDetectorTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activité transparente pour intercepter les partages Android.
 * Elle affiche une mini-dialog de confirmation puis transfère la vidéo
 * à MainActivity pour analyse.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUri  = resolveSharedUri(intent)
        val sharedText = resolveSharedText(intent)

        if (sharedUri == null && sharedText == null) {
            Toast.makeText(this, "Aucun média détecté", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            DeepfakeDetectorTheme {
                ShareConfirmationDialog(
                    uri  = sharedUri,
                    url  = sharedText,
                    onAnalyze = {
                        launchAnalysis(sharedUri, sharedText)
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    // ─── Résolution du contenu partagé ────────────────────────────────────────

    private fun resolveSharedUri(intent: Intent): Uri? {
        return when {
            Intent.ACTION_SEND == intent.action -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_SEND_MULTIPLE == intent.action -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
                }
            }
            Intent.ACTION_VIEW == intent.action -> intent.data
            else -> null
        }
    }

    private fun resolveSharedText(intent: Intent): String? {
        if (Intent.ACTION_SEND != intent.action) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        // Vérifie que c'est bien une URL vidéo (TikTok, YouTube, Twitter/X, Instagram…)
        val videoUrlPatterns = listOf(
            "tiktok.com", "vm.tiktok.com",
            "youtube.com/watch", "youtu.be",
            "twitter.com", "x.com",
            "instagram.com/reel", "instagram.com/p/",
            "facebook.com/watch", "fb.watch"
        )
        return if (videoUrlPatterns.any { text.contains(it, ignoreCase = true) }) text else null
    }

    // ─── Lancement de l'analyse ───────────────────────────────────────────────

    private fun launchAnalysis(uri: Uri?, url: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (uri != null) {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else if (url != null) {
                putExtra("shared_url", url)
            }
        }
        startActivity(intent)
    }
}

// ─── Dialog de confirmation ───────────────────────────────────────────────────

@Composable
private fun ShareConfirmationDialog(
    uri: Uri?,
    url: String?,
    onAnalyze: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icône scanner
                Text(text = "🔍", style = MaterialTheme.typography.displaySmall)

                Text(
                    text = "Analyser cette vidéo ?",
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    text = when {
                        url  != null -> "Lien : ${url.take(50)}…"
                        uri  != null -> "Fichier vidéo partagé"
                        else         -> "Contenu inconnu"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "DeepFake Detector va analyser ce contenu pour détecter toute manipulation.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Annuler")
                    }

                    Button(
                        onClick = onAnalyze,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Analyser")
                    }
                }
            }
        }
    }
}
