package net.tgnra.morningissue.data.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Removes, once, the keys of settings the app no longer has, as a migration of the settings
 * store that runs before anything reads it:
 *
 * - Everything the Kobo sync of earlier versions kept: `relay_address`,
 *   `relay_registration_token`, `relay_registered_at`, `relay_last_published`,
 *   `relay_last_done`, `relay_owner_secret`, `relay_leftover_group_cleared`,
 *   `kobo_image_widths` and `db_id` (the install ID a Kobo checked). The Kobo reader is
 *   deprecated and the app has no relay.
 */
object RetiredSettings : DataMigration<Preferences> {
    val KEYS: List<Preferences.Key<*>> = listOf(
        stringPreferencesKey("relay_address"),
        stringPreferencesKey("relay_registration_token"),
        stringPreferencesKey("kobo_image_widths"),
        stringPreferencesKey("relay_registered_at"),
        longPreferencesKey("relay_last_published"),
        longPreferencesKey("relay_last_done"),
        stringPreferencesKey("relay_owner_secret"),
        booleanPreferencesKey("relay_leftover_group_cleared"),
        stringPreferencesKey("db_id"),
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = KEYS.any { it in currentData }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { KEYS.forEach { remove(it) } }.toPreferences()

    override suspend fun cleanUp() = Unit
}
