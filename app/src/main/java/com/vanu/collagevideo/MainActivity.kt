package com.vanu.collagevideo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanu.faceswap.core.AppTheme
import com.vanu.faceswap.core.ModelPhase
import com.vanu.faceswap.core.ModelSetup
import com.vanu.faceswap.core.Models
import com.vanu.faceswap.core.mb

/** "Collage Video": the people from a collage photo become the people in a video, moving like them. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { CollageApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageApp(vm: CollageViewModel = viewModel()) {
    val models by vm.models.state.collectAsStateWithLifecycle()
    // keep the screen on only while the first-run model download runs (the video job itself has a wake lock)
    val view = LocalView.current
    val keepOn = models.phase == ModelPhase.DOWNLOADING
    DisposableEffect(keepOn) { view.keepScreenOn = keepOn; onDispose { view.keepScreenOn = false } }

    Scaffold(topBar = { TopAppBar(title = { Text("Collage Video", fontWeight = FontWeight.SemiBold) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (models.phase != ModelPhase.READY) {
                ModelSetup(models, Models.COLLAGE_REQUIRED, onDownload = { vm.models.start() }, onPause = { vm.models.pause() },
                    extraNote = "Optional, later: detail enhancers for sharper faces — Light (${mb(Models.ENHANCER_LIGHT.bytes)}) " +
                        "or HQ (${mb(Models.ENHANCER.bytes)}) — can be added from the Face detail setting.")
            } else {
                CollageContent(vm)
            }
            Text("Everything runs on your phone — videos and photos never leave the device. " +
                "The internet is only used to download the AI models.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
    }
}
