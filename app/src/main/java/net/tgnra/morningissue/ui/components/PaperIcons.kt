package net.tgnra.morningissue.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icons the app needs that `material-icons-core` does not carry, defined from path data so the
 * extended icon set (several megabytes) is not needed.
 */
object PaperIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            )
            .build()

    val Bookmark: ImageVector by lazy {
        icon("Bookmark", "M17,3H7C5.9,3 5.01,3.9 5.01,5L5,21l7,-3 7,3V5C19,3.9 18.1,3 17,3z")
    }

    val BookmarkBorder: ImageVector by lazy {
        icon(
            "BookmarkBorder",
            "M17,3H7C5.9,3 5.01,3.9 5.01,5L5,21l7,-3 7,3V5C19,3.9 18.1,3 17,3zM17,18l-5,-2.18L7,18V5h10V18z",
        )
    }

    /** Sources: the usual feed mark. */
    val Feed: ImageVector by lazy {
        icon(
            "Feed",
            "M6.18,15.64a2.18,2.18 0,1 0,0 4.36a2.18,2.18 0,1 0,0 -4.36z" +
                "M4,4.44v2.83c7.03,0 12.73,5.7 12.73,12.73h2.83C19.56,11.41 12.59,4.44 4,4.44z" +
                "M4,10.1v2.83c3.9,0 7.07,3.17 7.07,7.07h2.83C13.9,14.53 9.47,10.1 4,10.1z",
        )
    }

    val OpenInBrowser: ImageVector by lazy {
        icon(
            "OpenInBrowser",
            "M19,19H5V5h7V3H5C3.89,3 3,3.9 3,5v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2V19z" +
                "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3H14z",
        )
    }

    val TextSize: ImageVector by lazy {
        icon("TextSize", "M9,4v3h5v12h3V7h5V4H9zM3,12h3v7h3v-7h3V9H3V12z")
    }

    /** A newspaper page, for empty states. */
    val Newspaper: ImageVector by lazy {
        icon(
            "Newspaper",
            "M4,3h16v18H4V3zM6,5v14h12V5H6z" +
                "M7.5,6.5h9v2h-9V6.5zM7.5,10h4v4h-4V10zM13,10h3.5v1.2H13V10zM13,12.8h3.5V14H13V12.8z" +
                "M7.5,15.5h9v1.2h-9V15.5z",
        )
    }

    val Calendar: ImageVector by lazy {
        icon(
            "Calendar",
            "M19,4h-1V2h-2v2H8V2H6v2H5C3.9,4 3,4.9 3,6v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6C21,4.9 20.1,4 19,4z" +
                "M19,20H5V10h14V20zM19,8H5V6h14V8z",
        )
    }
}
