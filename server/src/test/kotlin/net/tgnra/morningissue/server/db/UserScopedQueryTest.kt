package net.tgnra.morningissue.server.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule of section 2.2: every query over a per-user table names the user. Reads the
 * DAO sources, as the app's `SubscribedOnlyQueryTest` does, and every `@Query` that reads or
 * writes a table with a `user_id` column must filter on `user_id = :userId`. A query over an
 * edition's items or page views may instead be keyed to the edition (`:editionId`), which the
 * caller has checked is the user's. Anything else is listed in [ALLOWED] with the reason it
 * must cross users. A new query that forgets the user fails here.
 */
class UserScopedQueryTest {
    private val sources = File("src/main/kotlin/net/tgnra/morningissue/server/db")

    private class DaoQuery(val dao: String, val function: String, val sql: String)

    /** Joins the string literals of a Kotlin expression such as `"a" + "b" + "c"`. */
    private fun literals(expression: String): String =
        LITERAL.findAll(expression).joinToString("") { it.groupValues[1] }

    private fun queries(): List<DaoQuery> {
        val files = sources.listFiles { file -> file.name.endsWith("Dao.kt") }.orEmpty().sortedBy { it.name }
        assertTrue("DAO sources not found from ${File(".").absolutePath}", files.size >= 6)
        val constants = HashMap<String, String>()
        for (file in files) {
            val text = file.readText()
            for (match in CONSTANT.findAll(text)) constants[match.groupValues[1]] = literals(match.groupValues[2])
        }
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

    private fun scoped(sql: String): Boolean {
        val user = USER_FILTER.containsMatchIn(sql) || USER_INSERT.containsMatchIn(sql)
        if (USER_TABLE.containsMatchIn(sql) && !user) return false
        if (EDITION_CHILD.containsMatchIn(sql) && !user && !EDITION_KEY.containsMatchIn(sql)) return false
        return true
    }

    @Test
    fun everyQueryOverAPerUserTableNamesTheUser() {
        val all = queries()
        assertTrue("expected the DAOs' queries, found ${all.size}", all.size > 80)
        val unscoped = all.filter { !scoped(it.sql) && "${it.dao}.${it.function}" !in ALLOWED }
        assertEquals(
            "Queries over per-user tables must filter on user_id = :userId (or be allowed below with a reason):\n" +
                unscoped.joinToString("\n") { "  ${it.dao}.${it.function}: ${it.sql.take(200)}" },
            emptyList<String>(),
            unscoped.map { "${it.dao}.${it.function}" },
        )
    }

    @Test
    fun theAllowListHasNothingStaleAndNothingAlreadyScoped() {
        val byName = queries().associateBy { "${it.dao}.${it.function}" }
        assertEquals(emptySet<String>(), ALLOWED.keys - byName.keys)
        assertEquals(emptyList<String>(), ALLOWED.keys.filter { scoped(byName.getValue(it).sql) })
    }

    @Test
    fun theGuardSeesTheQueriesThatMatter() {
        val byName = queries().associateBy { "${it.dao}.${it.function}" }
        // The user's view of an article, their subscriptions, search, flags and verdicts.
        for (name in listOf(
            "ArticleDao.row", "ArticleDao.isVisible", "ArticleDao.search", "ArticleDao.saved",
            "SubscriptionDao.details", "SubscriptionDao.subscribedDetails", "SubscriptionDao.deleteEngagementForFeed",
            "HealthDao.runStatsSince", "DiscoveryDao.verdicts", "DiscoveryDao.engagedVectors",
        )) {
            val query = byName[name]
            assertTrue("$name not found", query != null)
            assertTrue("$name must touch a per-user table", USER_TABLE.containsMatchIn(query!!.sql))
            assertTrue("$name must filter on the user", USER_FILTER.containsMatchIn(query.sql))
        }
        // And the guard does catch a query that forgets the user.
        assertTrue(!scoped("SELECT * FROM engagement WHERE articleId = :articleId"))
        assertTrue(!scoped("DELETE FROM edition_item WHERE articleId = :articleId"))
        assertTrue(scoped("SELECT * FROM edition_item WHERE editionId = :editionId"))
        assertTrue(scoped("INSERT OR IGNORE INTO feed_flag (user_id, feed_id) VALUES (:userId, :feedId)"))
        assertTrue(!scoped("INSERT INTO feed_flag (user_id, feed_id) SELECT user_id, feed_id FROM subscription"))
    }

    private companion object {
        val LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val CONSTANT = Regex("const val (\\w+)\\s*=\\s*((?:\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?)+)")
        val QUERY = Regex("@Query\\(\\s*((?:\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?)+)\\s*,?\\s*\\)")
        val FUNCTION = Regex("fun\\s+(\\w+)")
        val INTERPOLATION = Regex("\\$\\{([\\w.]+)}|\\$([A-Z_][A-Z0-9_]*)")

        /** The tables with a `user_id` column (schema v1). */
        val USER_TABLE = Regex(
            "\\b(FROM|JOIN|UPDATE|INTO)\\s+(device|user_settings|user_state|import_log|subscription|tag|subscription_tag|" +
                "feed_flag|discovery_verdict|engagement|edition)\\b(?!_)",
            RegexOption.IGNORE_CASE,
        )

        /** Per-user through their edition. */
        val EDITION_CHILD = Regex("\\b(FROM|JOIN|UPDATE|INTO)\\s+(edition_item|edition_page_view)\\b", RegexOption.IGNORE_CASE)
        val EDITION_KEY = Regex(":editionIds?\\b")
        val USER_FILTER = Regex("\\buser_id\\s*(=\\s*:userId\\b|IN\\s*\\(\\s*:userIds\\b)", RegexOption.IGNORE_CASE)

        /** An insert of the user's own row: `INSERT INTO t (user_id, …) VALUES (:userId, …)`. */
        val USER_INSERT = Regex("^\\s*INSERT\\b[^(]*\\(\\s*user_id\\b[^)]*\\)\\s*VALUES\\s*\\(\\s*:userId\\b", RegexOption.IGNORE_CASE)

        /** Queries that must cross users, and why. */
        val ALLOWED = mapOf(
            "FeedDao.fetchable" to "the refresher fetches a shared feed if anyone subscribes",
            "FeedDao.subscribedFeedIds" to "the shared feeds anyone subscribes to: the periodic refresh and repair",
            "FeedDao.orphanIds" to "global GC: feeds nobody subscribes to",
            "SubscriptionDao.subscribedPairs" to "every (user, feed) pair: refresh coverage and the health check's users",
            "SubscriptionDao.expiredDiscovered" to "discovery clean-up across users; each row names its user",
            "ArticleDao.unreferencedIds" to "global GC: posts no subscription, engagement or edition item refers to",
            "ArticleDao.feedBodiesSince" to "TeaserBackfill: posts of shared feeds anyone subscribes to",
            "DeviceDao.byTokenHash" to "authentication: the token is what tells the server who the user is; hashes are unique",
            "DeviceDao.byId" to "login and pairing take over a client's device ID whichever user held it; IDs are unique",
            "DeviceDao.touch" to "last-seen time of a device the caller has just authenticated, by its unique ID",
            "EditionDao.ownerOf" to "KoboImageService.prepare starts from an edition ID alone; the owner it returns scopes the rest",
        )
    }
}
