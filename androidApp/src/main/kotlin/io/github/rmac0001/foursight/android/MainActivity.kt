package io.github.rmac0001.foursight.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.rmac0001.foursight.ui.App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val services get() = (application as FourSightApp).services

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            services.drive.onConsentResult(result.data)
        }

    private val saveFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            services.onExportTarget(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        services.activity = this
        services.drive.launcher = { intent -> consent.launch(IntentSenderRequest.Builder(intent.intentSender).build()) }
        services.exportLauncher = { name -> saveFile.launch(name) }
        setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { App(services) }
        }
        // Merge on launch (spec: Sync, "on launch"), quietly; the screens reload when asked.
        if (services.driveConnected) lifecycleScope.launch { services.syncNow() }
    }

    override fun onDestroy() {
        if (services.activity === this) {
            services.activity = null
            services.drive.launcher = null
            services.exportLauncher = null
        }
        super.onDestroy()
    }
}
