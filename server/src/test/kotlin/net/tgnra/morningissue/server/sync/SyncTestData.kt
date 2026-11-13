package net.tgnra.morningissue.server.sync

import java.time.Instant
import java.time.LocalDate
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.InlineSpan
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.EditionItemEntity
import net.tgnra.morningissue.server.db.FeedEntity
import net.tgnra.morningissue.server.editions.TestWorld

internal val RELEASE: Instant = Instant.parse("2026-11-11T06:30:00Z")
internal val NOW: Instant = Instant.parse("2026-11-11T07:00:00Z")

/** One block of every type the block model has, and every inline style. */
internal val EVERY_BLOCK: List<ContentBlock> = listOf(
    ContentBlock.Heading(2, RichText("What the frost left")),
    ContentBlock.Paragraph(
        RichText(
            "Bold, italic, code, a link, struck, x2 and H2O.",
            listOf(
                InlineSpan(0, 4, InlineStyle.BOLD),
                InlineSpan(6, 12, InlineStyle.ITALIC),
                InlineSpan(14, 18, InlineStyle.CODE),
                InlineSpan(22, 26, InlineStyle.LINK, "https://garden.example/frost"),
                InlineSpan(28, 34, InlineStyle.STRIKETHROUGH),
                InlineSpan(37, 38, InlineStyle.SUPERSCRIPT),
                InlineSpan(44, 45, InlineStyle.SUBSCRIPT),
            ),
        ),
    ),
    ContentBlock.Image(
        url = "https://garden.example/img/frost.jpg",
        alt = "Frost on a cabbage leaf",
        caption = RichText("The cabbages, at dawn."),
        width = 1200,
        height = 800,
    ),
    ContentBlock.Quote(listOf(RichText("Nothing is so beautiful as spring.")), citation = RichText("Gerard Manley Hopkins")),
    ContentBlock.ItemList(ordered = false, items = listOf(RichText("Fleece"), RichText("Cloches"), RichText("Patience"))),
    ContentBlock.ItemList(ordered = true, items = listOf(RichText("Water"), RichText("Wait"))),
    ContentBlock.Preformatted("frost_date = \"2026-11-10\"", language = "toml"),
)

/** IDs [SyncTestData.seed] creates. */
internal data class Seeded(
    val user: Long,
    val feed: Long,
    val lead: Long,
    val top: Long,
    val section: Long,
    val index: Long,
    val edition: Long,
    val older: Long,
    val unreleased: Long,
)

/** The app's `KoboTestData`, for one user of the server. */
internal object SyncTestData {
    /**
     * A user subscribed to a feed with four posts, and three editions of theirs:
     * [Seeded.edition] released at [RELEASE] (a lead with every block type, a top story without
     * stored text, a section story and an index line), one released the day before, and one
     * not released until tomorrow. With [feedId] the posts go in that feed, already made.
     */
    suspend fun seed(world: TestWorld, userId: Long? = null, dbId: String = "3f0c6f2e-6c55-4f7b-9d1e-0a6f2b9e5c11"): Seeded {
        val user = userId ?: world.addUser(dbId = dbId)
        val importDao = world.db.importDao()
        val url = "https://garden.example/feed.xml"
        val feedId = importDao.feedIdByUrl(url) ?: importDao.insertFeed(
            FeedEntity(url = url, urlKey = FeedUrls.normalize(url), title = "The Kitchen Garden", addedAt = RELEASE),
        )
        world.subscribe(user, feedId, addedAt = RELEASE.minusSeconds(86_400 * 30L))
        fun post(guid: String, title: String, summary: String?, imageUrl: String? = null, author: String? = null) = ArticleEntity(
            feedId = feedId,
            guid = guid,
            url = "https://garden.example/$guid",
            title = title,
            summary = summary,
            imageUrl = imageUrl,
            author = author,
            publishedAt = RELEASE.minusSeconds(7_200),
            firstSeenAt = RELEASE.minusSeconds(3_600),
            wordCount = 420,
        )
        suspend fun insert(article: ArticleEntity, blocks: List<ContentBlock>? = null): Long =
            importDao.articleByGuid(feedId, article.guid)?.id ?: importDao.insertNew(
                article,
                blocksJson = blocks?.let(ContentBlocks::encode),
                plainText = blocks?.let(ContentBlocks::plainText),
            )!!
        val lead = insert(
            post("frost", "The first frost", "Brassicas sweeten after a frost.", imageUrl = "https://garden.example/img/lead.jpg", author = "M. Fenwick"),
            EVERY_BLOCK,
        )
        val top = insert(post("leeks", "Leeks in November", "Earth them up."))
        val section = insert(post("seed", "Saving seed", null), listOf(ContentBlock.Paragraph(RichText("Dry them well."))))
        val index = insert(post("compost", "Compost notes", "Turn it."))

        val editions = world.db.editionDao()
        fun edition(date: String, release: Instant, number: Int) = EditionEntity(
            userId = user, date = LocalDate.parse(date), kind = EditionKind.MORNING, cutoffAt = release.minusSeconds(5_400), releaseAt = release,
            builtAt = release.minusSeconds(5_400), length = EditionLength.STANDARD, number = number, pageViewsTracked = true,
        )
        val older = editions.insertEditionWithItems(
            edition("2026-11-10", RELEASE.minusSeconds(86_400), 119),
            listOf(EditionItemEntity(0, top, page = 1, slot = 0, kind = ItemKind.LEAD, score = 1.0, reason = "Earlier")),
        )
        val today = editions.insertEditionWithItems(
            edition("2026-11-11", RELEASE, 120),
            // Out of order on purpose: the bundle sorts by page, then slot.
            listOf(
                EditionItemEntity(0, index, page = 3, slot = 0, kind = ItemKind.INDEX, score = 0.1),
                EditionItemEntity(0, section, page = 2, slot = 0, section = "Growing", kind = ItemKind.SECTION, score = 0.7, reason = "From Growing"),
                EditionItemEntity(0, top, page = 1, slot = 1, kind = ItemKind.FRONT, score = 0.9, reason = "New from a weekly feed"),
                EditionItemEntity(0, lead, page = 1, slot = 0, kind = ItemKind.LEAD, score = 1.4, reason = "A rare post from a feed you love"),
            ),
        )
        val unreleased = editions.insertEditionWithItems(
            edition("2026-11-12", RELEASE.plusSeconds(86_400), 121),
            listOf(EditionItemEntity(0, index, page = 1, slot = 0, kind = ItemKind.LEAD, score = 1.0)),
        )
        return Seeded(user, feedId, lead, top, section, index, today, older, unreleased)
    }
}
