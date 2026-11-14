package net.tgnra.morningissue.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule that feeds the reader only looked into through discovery
 * (`FeedStatus.DISCOVERED`) never reach an edition, sync, the health check, the learned
 * affinities, Sources, OPML, search filters or onboarding counts.
 *
 * Reads the DAO sources, and every `@Query` that reads `feed` or `article` must say which
 * feeds it wants (`status = 'SUBSCRIBED'` or `'DISCOVERED'`), unless it is keyed to one row
 * (`:feedId`, `:articleId`, …), it writes rather than reads, or it is listed in [ALLOWED] with
 * the reason it must see every feed. A new query that forgets the status fails here.
 */
class SubscribedOnlyQueryTest {
    private val sources = File("src/main/java/net/tgnra/morningissue/data/db")

    private class DaoQuery(val dao: String, val function: String, val sql: String)

    /** Joins the string literals of a Kotlin expression such as `"a" + "b" + "c"`. */
    private fun literals(expression: String): String =
        LITERAL.findAll(expression).joinToString("") { it.groupValues[1] }

    private fun queries(): List<DaoQuery> {
        val files = sources.listFiles { file -> file.name.endsWith("Dao.kt") }.orEmpty().sortedBy { it.name }
        assertTrue("DAO sources not found from ${File(".").absolutePath}", files.size >= 5)
        val constants = HashMap<String, String>()
        for (file in files) {
            val text = file.readText()
            for (match in CONSTANT.findAll(text)) constants[match.groupValues[1]] = literals(match.groupValues[2])
        }
        // Constants may use each other.
        fun resolve(sql: String): String {
            var out = sql
            repeat(3) {
                out = INTERPOLATION.replace(out) { match ->
                    val name = match.groupValues[1].ifEmpty { match.groupValues[2] }.substringAfterLast('.')
                    constants[name] ?: match.value
                }
            }
            return out
        }
        return files.flatMap { file ->
            val text = file.readText()
            QUERY.findAll(text).map { match ->
                val sql = resolve(literals(match.groupValues[1]))
                val function = FUNCTION.find(text, match.range.last)?.groupValues?.get(1) ?: "?"
                DaoQuery(file.name.removeSuffix(".kt"), function, sql)
            }.toList()
        }
    }

    @Test
    fun everyQueryOverFeedsOrPostsSaysWhichFeedsItWants() {
        val all = queries()
        assertTrue("expected the DAOs' queries, found ${all.size}", all.size > 80)
        val unguarded = all.filter { query ->
            val sql = query.sql
            val reads = sql.trimStart().startsWith("SELECT", ignoreCase = true) || sql.trimStart().startsWith("WITH", ignoreCase = true)
            reads &&
                READS_FEEDS_OR_POSTS.containsMatchIn(sql) &&
                !STATUS.containsMatchIn(sql) &&
                !ONE_ROW.containsMatchIn(sql) &&
                "${query.dao}.${query.function}" !in ALLOWED
        }
        assertEquals(
            "Queries over feed or article must filter on feed.status (or be allowed below with a reason):\n" +
                unguarded.joinToString("\n") { "  ${it.dao}.${it.function}: ${it.sql.take(160)}" },
            emptyList<String>(),
            unguarded.map { "${it.dao}.${it.function}" },
        )
    }

    @Test
    fun theAllowListHasNothingStale() {
        val names = queries().map { "${it.dao}.${it.function}" }.toSet()
        assertEquals(emptySet<String>(), ALLOWED.keys - names)
    }

    @Test
    fun theGuardSeesTheQueriesThatMatter() {
        val byName = queries().associateBy { "${it.dao}.${it.function}" }
        // The "nothing new" gate, the edition's candidates and the subscription list.
        for (name in listOf("ArticleDao.firstSeenBetween", "FeedDao.getSubscribed", "FeedDao.observeSubscribedWithDetails", "FeedDao.countSubscribed")) {
            val query = byName[name]
            assertTrue("$name not found", query != null)
            assertTrue("$name must filter on status", STATUS.containsMatchIn(query!!.sql))
        }
    }

    private companion object {
        val LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val CONSTANT = Regex("const val (\\w+)\\s*=\\s*((?:\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?)+)")
        val QUERY = Regex("@Query\\(\\s*((?:\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?)+)\\s*,?\\s*\\)")
        val FUNCTION = Regex("fun\\s+(\\w+)")
        val INTERPOLATION = Regex("\\$\\{([\\w.]+)}|\\$([A-Z_][A-Z0-9_]*)")
        val READS_FEEDS_OR_POSTS = Regex("\\b(FROM|JOIN)\\s+(feed|article)\\b(?!_)", RegexOption.IGNORE_CASE)
        val STATUS = Regex("status\\s*=\\s*'(SUBSCRIBED|DISCOVERED)'")
        val ONE_ROW = Regex(":(feedId|articleId|articleIds|editionId|editionIds|url|guid)\\b|\\bid\\s*=\\s*:")

        /** Queries that must see every feed, discovered ones included, and why. */
        val ALLOWED = mapOf(
            "FeedDao.getUrls" to "duplicate checks: adding a feed that is already discovered subscribes it instead",
            "FeedDao.getAllWithDetailsIncludingDiscovered" to "the archive keeps every feed, with its status",
            "FeedDao.emptyDiscoveredIds" to "says DISCOVERED through its own join",
            "ArticleDao.observeSaved" to "the Saved shelf: a post saved from discovery belongs there, with its Subscribe button",
            "ArticleDao.observeLiked" to "the Liked shelf: the same",
            "ArticleDao.getSaved" to "the Kobo's saved list: the Saved shelf's posts, discovered ones included",
            "ArticleDao.search" to "search finds saved discovered posts; the feed filter lists subscriptions only",
            "ArticleDao.count" to "a count of every post, for storage and the archive",
            "ArticleDao.trimmableContentIds" to "storage trimming applies to every stored post",
            "ArticleDao.trimCandidates" to "storage trimming applies to every stored post",
            "ArchiveDao.articlesAfter" to "the archive keeps every post",
            "ArticleSignalDao.articlesNeedingTraits" to "traits are computed for every stored post",
            "ArticleSignalDao.countWithoutEmbedding" to "vectors are computed for every stored post",
            "ArticleSignalDao.articlesWithoutEmbedding" to "vectors are computed for every stored post",
            "DiscoveryDao.engagedVectors" to "discovery's profile: a Liked post counts whatever its feed",
            "DiscoveryDao.readCategories" to "discovery's profile: the same",
            "EditionDao.observeCovers" to "the newsstand's covers show what was printed; a lead is never from a discovered feed",
            "StoredImageDao.trimCandidates" to "image trimming applies to every stored post",
            "StoredImageDao.imageSources" to "keyed by article IDs",
        )
    }
}
