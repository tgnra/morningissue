package net.tgnra.morningissue.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe destinations. Arguments are IDs only; screens load what they need. */
@Serializable data object OnboardingRoute

@Serializable data object NewsstandRoute

@Serializable data class PaperRoute(val editionId: Long)

@Serializable data class ReaderRoute(val articleId: Long)

@Serializable data object BackIssuesRoute

@Serializable data object SearchRoute

@Serializable data object SavedRoute

@Serializable data object SourcesRoute

@Serializable data class SourcePageRoute(val feedId: Long)

@Serializable data object SubscriptionsDeskRoute

@Serializable data object SettingsRoute

@Serializable data object KoboSyncRoute

@Serializable data object RelaySyncRoute

@Serializable data object DiscoverRoute

@Serializable data object DiscoverPassedRoute
