package net.tgnra.morningissue

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.account.AccountGate
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.layout.PaperLayoutSpec
import net.tgnra.morningissue.ui.navigation.MorningIssueNavHost
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer

        setContent {
            // Null until the stored settings have been read, so the first frame is not themed
            // with defaults and onboarding is not flashed at returning users.
            val stored by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val settings = stored ?: AppSettings()
            val layoutSpec = PaperLayoutSpec.from(calculateWindowSizeClass(this))

            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalPaperLayoutSpec provides layoutSpec,
            ) {
                MorningIssueTheme(
                    themeMode = settings.themeMode,
                    displayMode = settings.displayMode,
                    greyscaleImages = settings.greyscaleImages,
                ) {
                    val darkBars = MorningIssueTheme.colors.isDark
                    DisposableEffect(darkBars) {
                        // System bar icons follow the app's theme, not the system's.
                        val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkBars }
                        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                        onDispose {}
                    }
                    Box(Modifier.fillMaxSize().background(MorningIssueTheme.colors.paper)) {
                        if (stored != null) {
                            // The account, its password and the upload of an earlier library come first.
                            AccountGate {
                                // Decided once: finishing onboarding navigates, it does not restart the graph.
                                val showOnboarding = remember { !settings.onboardingDone }
                                MorningIssueNavHost(showOnboarding = showOnboarding)
                            }
                        }
                    }
                }
            }
        }
    }
}
