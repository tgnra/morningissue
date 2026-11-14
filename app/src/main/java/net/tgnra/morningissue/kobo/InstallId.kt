package net.tgnra.morningissue.kobo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.sync.FullTextFetcher

/**
 * The install's `dbId`: a random UUID made the first time it is asked for and kept in the
 * settings store. A Kobo that sees a different one in a package's manifest drops everything it
 * holds from this phone, because a reinstalled app reuses Room IDs.
 */
class InstallId(private val dataStore: DataStore<Preferences>) {
    suspend fun get(): String {
        dataStore.data.first()[KEY]?.let { return it }
        var id = ""
        dataStore.edit { prefs -> id = prefs[KEY] ?: UUID.randomUUID().toString().also { prefs[KEY] = it } }
        return id
    }

    private companion object {
        val KEY = stringPreferencesKey("db_id")
    }
}

/** Saved articles' pages fetched at once, as the edition prefetch does. */
private const val TEXT_PARALLEL = 4

/**
 * The reader's full-text fetch for each saved article whose page has not been fetched yet, so
 * that the saved shelf a Kobo gets in a relay package carries the full text.
 */
suspend fun fetchSavedFullText(database: MorningIssueDatabase, fullText: FullTextFetcher) = coroutineScope {
    val articles = database.articleDao()
    val permits = Semaphore(TEXT_PARALLEL)
    for (row in articles.getSaved()) {
        val id = row.article.id
        if (articles.getContentSource(id) == ContentSource.FETCHED) continue
        launch { permits.withPermit { fullText.ensureFullText(id) } }
    }
}
