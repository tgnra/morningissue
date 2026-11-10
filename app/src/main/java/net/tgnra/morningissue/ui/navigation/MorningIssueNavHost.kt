package net.tgnra.morningissue.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import net.tgnra.morningissue.ui.backissues.BackIssuesScreen
import net.tgnra.morningissue.ui.newsstand.NewsstandScreen
import net.tgnra.morningissue.ui.onboarding.OnboardingScreen
import net.tgnra.morningissue.ui.paper.PaperScreen
import net.tgnra.morningissue.ui.reader.ReaderScreen
import net.tgnra.morningissue.ui.saved.SavedScreen
import net.tgnra.morningissue.ui.search.SearchScreen
import net.tgnra.morningissue.ui.settings.SettingsScreen
import net.tgnra.morningissue.ui.sources.SourcePageScreen
import net.tgnra.morningissue.ui.sources.SourcesScreen
import net.tgnra.morningissue.ui.sources.SubscriptionsDeskScreen
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * The whole navigation graph:
 *
 * ```
 * Newsstand ─┬─ Paper ── Reader
 *            ├─ Back issues ── Paper
 *            ├─ Search ── Reader
 *            ├─ Saved / Liked ── Reader
 *            ├─ Sources ── Source page, Subscriptions desk
 *            └─ Settings
 * ```
 *
 * Screens never see the controller; they get one callback per way out.
 *
 * @param showOnboarding start on onboarding (first launch) instead of the newsstand.
 */
@Composable
fun MorningIssueNavHost(
    showOnboarding: Boolean,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // E-ink screens ghost on crossfades, so destinations swap without animation there.
    val animate = !MorningIssueTheme.isEink
    // Never pops the last destination: a second "back" arriving while the first is still in
    // flight (a double tap, a key repeat) would otherwise leave an empty graph and a blank screen.
    val back: () -> Unit = { if (navController.previousBackStackEntry != null) navController.popBackStack() }
    val openReader: (Long) -> Unit = { navController.navigate(ReaderRoute(it)) }
    val openPaper: (Long) -> Unit = { navController.navigate(PaperRoute(it)) }
    val openSource: (Long) -> Unit = { navController.navigate(SourcePageRoute(it)) }

    NavHost(
        navController = navController,
        startDestination = if (showOnboarding) OnboardingRoute else NewsstandRoute,
        modifier = modifier,
        enterTransition = { if (animate) fadeIn() else EnterTransition.None },
        exitTransition = { if (animate) fadeOut() else ExitTransition.None },
        popEnterTransition = { if (animate) fadeIn() else EnterTransition.None },
        popExitTransition = { if (animate) fadeOut() else ExitTransition.None },
    ) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(NewsstandRoute) {
                        popUpTo<OnboardingRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<NewsstandRoute> {
            NewsstandScreen(
                onOpenEdition = openPaper,
                onOpenBackIssues = { navController.navigate(BackIssuesRoute) },
                onOpenSearch = { navController.navigate(SearchRoute) },
                onOpenSaved = { navController.navigate(SavedRoute) },
                onOpenSources = { navController.navigate(SourcesRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<PaperRoute> { entry ->
            PaperScreen(
                editionId = entry.toRoute<PaperRoute>().editionId,
                onOpenArticle = openReader,
                onOpenSource = openSource,
                onOpenBackIssues = { navController.navigate(BackIssuesRoute) },
                onOpenSearch = { navController.navigate(SearchRoute) },
                onOpenSubscriptionsDesk = { navController.navigate(SubscriptionsDeskRoute) },
                onBack = back,
            )
        }
        composable<ReaderRoute> { entry ->
            ReaderScreen(
                articleId = entry.toRoute<ReaderRoute>().articleId,
                onOpenSource = openSource,
                onBack = back,
            )
        }
        composable<BackIssuesRoute> {
            BackIssuesScreen(onOpenEdition = openPaper, onBack = back)
        }
        composable<SearchRoute> {
            SearchScreen(onOpenArticle = openReader, onBack = back)
        }
        composable<SavedRoute> {
            SavedScreen(onOpenArticle = openReader, onBack = back)
        }
        composable<SourcesRoute> {
            SourcesScreen(
                onOpenSource = openSource,
                onOpenSubscriptionsDesk = { navController.navigate(SubscriptionsDeskRoute) },
                onBack = back,
            )
        }
        composable<SourcePageRoute> { entry ->
            SourcePageScreen(
                feedId = entry.toRoute<SourcePageRoute>().feedId,
                onOpenArticle = openReader,
                onBack = back,
            )
        }
        composable<SubscriptionsDeskRoute> {
            SubscriptionsDeskScreen(onOpenSource = openSource, onBack = back)
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = back)
        }
    }
}
