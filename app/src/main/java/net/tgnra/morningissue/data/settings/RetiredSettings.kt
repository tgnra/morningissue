package net.tgnra.morningissue.data.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Removes, once, the keys of settings the app no longer has, as a migration of the settings
 * store that runs before anything reads it:
 *
 * - `relay_address`, `relay_registration_token`, `relay_registered_at`, `relay_last_published`
 *   and `relay_last_done`, from the relay sync of earlier versions. Kobos now sync with the
 *   reader's server (`docs/server-design.md`), and the app has no relay.
 * - `kobo_image_widths`, the picture width each Kobo last asked for over the local network,
 *   which the app no longer serves.
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
