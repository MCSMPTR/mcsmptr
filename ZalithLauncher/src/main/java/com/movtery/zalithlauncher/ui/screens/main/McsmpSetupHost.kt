package com.movtery.zalithlauncher.ui.screens.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movtery.zalithlauncher.game.mcsmp.McsmpBootstrap

private var autoLaunchDone = false

/**
 * İlk kurulum ilerleme penceresi. Kurulum bitince (yalnızca ilk kurulumda) [onReady] bir kez çağrılır.
 */
@Composable
fun McsmpSetupHost(onReady: () -> Unit) {
    val context = LocalContext.current
    val state by McsmpBootstrap.state.collectAsState()

    LaunchedEffect(Unit) { McsmpBootstrap.start(context) }

    LaunchedEffect(state) {
        if (state is McsmpBootstrap.State.Ready && McsmpBootstrap.justInstalled && !autoLaunchDone) {
            autoLaunchDone = true
            onReady()
        }
    }

    when (val s = state) {
        is McsmpBootstrap.State.Working -> Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp)) {
                    Text("MCSMP TR hazırlanıyor", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        is McsmpBootstrap.State.Failed -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Kurulum başarısız") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { McsmpBootstrap.start(context) }) { Text("Tekrar dene") } }
        )
        else -> Unit
    }
}
