package net.tgnra.morningissue.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.tgnra.morningissue.appContainer
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.settings.Setting
import net.tgnra.morningissue.ui.settings.SettingsSection

/**
 * Debug builds only (the release source set has an empty twin): loads or clears the sample
 * data, on request and never by itself. Its labels are not translated on purpose.
 */
@Composable
fun DebugSettingsSection() {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> String) {
        if (busy) return
        busy = true
        status = "Working…"
        // On the app scope: leaving Settings must not abandon the database half-seeded.
        container.appScope.launch {
            status = runCatching { action() }.getOrElse { "Failed: ${it.message}" }
            busy = false
        }
    }

    SettingsSection("Debug") {
        Setting("Sample data", "Replaces everything stored with invented feeds, articles and editions covering every page type. Pictures need a connection.") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperButton(
                    text = "Load sample data",
                    onClick = {
                        run {
                            val settings = container.settings.settings.first()
                            SampleDataSeeder(container.core.database, container.clock, settings.resetTime).seed()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                    enabled = !busy,
                )
                PaperButton(
                    text = "Load sample data, no edition today",
                    onClick = {
                        run {
                            val settings = container.settings.settings.first()
                            SampleDataSeeder(container.core.database, container.clock, settings.resetTime, newestDaysBehind = 1).seed()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                PaperButton(
                    text = "Clear all data",
                    onClick = {
                        run {
                            SampleDataSeeder(container.core.database, container.clock, java.time.LocalTime.MIDNIGHT).clear()
                            "Cleared."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                PaperButton(
                    text = "Show onboarding on next launch",
                    onClick = {
                        scope.launch {
                            container.settings.setOnboardingDone(false)
                            status = "Onboarding will show the next time the app starts."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                status?.let { NoticeLine(it) }
            }
        }
    }
}
