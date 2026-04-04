package com.deepfakedetector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deepfakedetector.ui.navigation.AppNavGraph
import com.deepfakedetector.ui.theme.DeepfakeDetectorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUri = resolveIncomingUri(intent)

        setContent {
            DeepfakeDetectorTheme {
                AppNavGraph(initialVideoUri = sharedUri)
            }
        }
    }

    // Handle re-delivery when app is already running (launchMode = singleTask)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Recreate so Compose recomposes with new intent URI
        recreate()
    }

    private fun resolveIncomingUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                val streamUri: Uri? =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                // URL text shared as plain text (TikTok, YouTube…)
                val urlText = intent.getStringExtra(Intent.EXTRA_TEXT)
                streamUri ?: urlText?.let { Uri.parse(it) }
            }
            else -> null
        }
    }
}
