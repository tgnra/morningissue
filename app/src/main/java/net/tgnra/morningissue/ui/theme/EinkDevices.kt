package net.tgnra.morningissue.ui.theme

import android.os.Build
import net.tgnra.morningissue.model.DisplayMode

/** The one place that knows which hardware has an e-ink screen. */
object EinkDevices {
    /**
     * Lower-case fragments of `Build.MANUFACTURER` / `Build.BRAND` values used by makers whose
     * Android devices are e-ink. Makers that also ship LCD devices are deliberately left out;
     * their users can pick E-ink in Settings.
     */
    val knownMakers: Set<String> = setOf(
        "onyx", // Boox
        "boox",
        "boyue", // Likebook
        "likebook",
        "meebook",
        "bigme",
        "dasung",
        "pocketbook",
        "tolino",
        "kobo",
        "remarkable",
        "ratta", // Supernote
        "supernote",
        "viwoods",
        "hanvon",
        "moaan", // InkPalm
        "inkpalm",
        "xiaomi-eink",
    )

    fun isEinkMaker(manufacturer: String?, brand: String?): Boolean {
        val names = listOfNotNull(manufacturer, brand).map { it.lowercase() }
        return names.any { name -> knownMakers.any { it in name } }
    }

    /** True when this device is from a known e-ink maker. */
    val isEinkDevice: Boolean by lazy { isEinkMaker(Build.MANUFACTURER, Build.BRAND) }

    /** Resolves the setting to [DisplayMode.Standard] or [DisplayMode.Eink]; never returns Auto. */
    fun resolve(setting: DisplayMode, deviceIsEink: Boolean = isEinkDevice): DisplayMode = when (setting) {
        DisplayMode.Auto -> if (deviceIsEink) DisplayMode.Eink else DisplayMode.Standard
        else -> setting
    }
}
