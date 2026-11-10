package net.tgnra.morningissue.ui

import androidx.compose.runtime.staticCompositionLocalOf
import net.tgnra.morningissue.AppContainer

/** The app's dependencies for composables and their view models. Provided by `MainActivity`. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer provided")
}
