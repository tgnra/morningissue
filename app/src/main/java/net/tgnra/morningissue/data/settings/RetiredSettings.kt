package net.tgnra.morningissue.data.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Removes, once, the keys of settings the app no longer has, as a migration of the settings
 * store that runs before anything reads it:
 *
 * - `relay_address` and `relay_registration_token`, which an earlier Relay sync screen let the
 *   reader type. The relay is now the build's alone (`RelaySettings`), and a value left here
 *   would only be misleading.
 * - `kobo_image_widths`, the picture width each Kobo last asked for over the local network,
 *   which the app no longer serves. A relay package takes each Kobo's width from its pairing.
 * - `relay_registered_at`, `relay_last_published` and `relay_last_done`, from when the relay
 *   kept the group between syncs and Publish and Done were separate. A sync is now one session
 *   that makes the group and deletes it at the end (`RelaySync`).
 */
object RetiredSettings : DataMigration<Preferences> {
    val KEYS: List<Preferences.Key<*>> = listOf(
        stringPreferencesKey("relay_address"),
        stringPreferencesKey("relay_registration_token"),
        stringPreferencesKey("kobo_image_widths"),
        stringPreferencesKey("relay_registered_at"),
        longPreferencesKey("relay_last_published"),
        longPreferencesKey("relay_last_done"),
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = KEYS.any { it in currentData }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { KEYS.forEach { remove(it) } }.toPreferences()

    override suspend fun cleanUp() = Unit
}
