package net.tgnra.morningissue.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import net.tgnra.morningissue.model.DisplayMode
import net.tgnra.morningissue.model.ThemeMode
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec

/**
 * The resolved display mode: [DisplayMode.Standard] or [DisplayMode.Eink], never Auto.
 * Most code wants [MorningIssueTheme.isEink] instead.
 */
val LocalDisplayMode = staticCompositionLocalOf { DisplayMode.Standard }

/**
 * The Settings option "show images in greyscale", for any theme. E-ink mode greyscales images
 * whatever this says; image code reads `ImageTreatment.tone`, which combines the two.
 */
val LocalGreyscaleImages = staticCompositionLocalOf { false }

/** Draws nothing on press, hover or focus; used in E-ink mode in place of ripples. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -1
}

/**
 * The app theme. E-ink mode (chosen, or detected when [displayMode] is Auto) overrides
 * [themeMode] with pure black on white and switches off ripples and press indication.
 * The type scale follows `LocalPaperLayoutSpec`, so provide the layout spec outside this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningIssueTheme(
    themeMode: ThemeMode = ThemeMode.System,
    displayMode: DisplayMode = DisplayMode.Auto,
    greyscaleImages: Boolean = false,
    content: @Composable () -> Unit,
) {
    val resolvedMode = EinkDevices.resolve(displayMode)
    val eink = resolvedMode == DisplayMode.Eink
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = when {
        eink -> EinkPaperColors
        dark -> DarkPaperColors
        else -> LightPaperColors
    }
    val typeScale = LocalPaperLayoutSpec.current.typeScale
    val typography = remember(typeScale, eink) { paperTypography(typeScale, eink) }
    val colorScheme = remember(colors) { colors.toColorScheme() }
    val materialTypography = remember(typography) { typography.toMaterialTypography() }

    CompositionLocalProvider(
        LocalDisplayMode provides resolvedMode,
        LocalGreyscaleImages provides greyscaleImages,
        LocalPaperColors provides colors,
        LocalPaperTypography provides typography,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = materialTypography) {
            // Inside MaterialTheme, which would otherwise install its ripple. The provider is
            // always present (with the inherited values outside E-ink) so that switching display
            // mode in Settings recomposes the app in place instead of rebuilding it from scratch,
            // which would throw the reader back to the newsstand.
            CompositionLocalProvider(
                LocalIndication provides if (eink) NoIndication else LocalIndication.current,
                LocalRippleConfiguration provides if (eink) null else LocalRippleConfiguration.current,
                content = content,
            )
        }
    }
}

/** Accessors for the current theme, in the style of `MaterialTheme`. */
object MorningIssueTheme {
    val colors: PaperColors
        @Composable @ReadOnlyComposable get() = LocalPaperColors.current

    val typography: PaperTypography
        @Composable @ReadOnlyComposable get() = LocalPaperTypography.current

    /** True in E-ink mode: no animation, no ripples, paged movement, greyscale images. */
    val isEink: Boolean
        @Composable @ReadOnlyComposable get() = LocalDisplayMode.current == DisplayMode.Eink
}
