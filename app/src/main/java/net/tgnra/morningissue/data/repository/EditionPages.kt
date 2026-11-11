package net.tgnra.morningissue.data.repository

import java.time.Instant
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemRow
import net.tgnra.morningissue.engine.EditionBuilder
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.EditionStory
import net.tgnra.morningissue.model.IndexGroup
import net.tgnra.morningissue.model.ItemKind

/** Turns stored `edition_item` rows into the page model. See `ItemKind` for the contract. */
internal object EditionPages {
    const val GENERAL_SECTION = EditionBuilder.GENERAL_SECTION

    fun toEdition(edition: EditionEntity, items: List<EditionItemRow>, nextEditionAt: Instant?): Edition {
        val printed = items.filter { it.item.kind != ItemKind.INDEX }
        val index = items.filter { it.item.kind == ItemKind.INDEX }

        val pages = mutableListOf<EditionPage>()
        printed.groupBy { it.item.page }.toSortedMap().values.forEach { rows ->
            val sorted = rows.sortedBy { it.item.slot }
            val stories = sorted.map { it.toStory() }
            val number = pages.size + 1
            // The first story decides the page type; a well-formed page has one type only.
            pages += when (stories.first().kind) {
                ItemKind.LEAD, ItemKind.FRONT -> {
                    val lead = stories.firstOrNull { it.kind == ItemKind.LEAD }
                    EditionPage.Front(number, lead, stories.filter { it !== lead })
                }
                ItemKind.TOP -> EditionPage.TopStories(number, stories)
                ItemKind.SECTION -> EditionPage.Section(number, sorted.first().item.section ?: GENERAL_SECTION, stories)
                ItemKind.BRIEF -> EditionPage.InBrief(number, stories)
                ItemKind.ARCHIVE -> EditionPage.FromTheArchive(number, stories)
                ItemKind.AWAY -> EditionPage.WhileYouWereAway(number, stories)
                ItemKind.READING_LIST -> EditionPage.FromYourReadingList(number, stories)
                ItemKind.INDEX -> error("index items are filtered out above")
            }
        }
        pages += EditionPage.Back(pages.size + 1, indexGroups(index), nextEditionAt)

        val readMinutes = printed
            .filter { it.item.kind != ItemKind.BRIEF }
            .sumOf { it.toStory().readMinutes }

        return Edition(
            id = edition.id,
            date = edition.date,
            kind = edition.kind,
            number = edition.number,
            length = edition.length,
            cutoffAt = edition.cutoffAt,
            releaseAt = edition.releaseAt,
            builtAt = edition.builtAt,
            pages = pages,
            readMinutes = readMinutes,
            isRead = edition.finishedAt != null,
        )
    }

    /** Groups by source, keeping the builder's slot order for groups and for stories within them. */
    private fun indexGroups(index: List<EditionItemRow>): List<IndexGroup> {
        val groups = LinkedHashMap<Long, MutableList<EditionStory>>()
        index.sortedBy { it.item.slot }.forEach { row ->
            groups.getOrPut(row.row.article.feedId) { mutableListOf() } += row.toStory()
        }
        return groups.map { (feedId, stories) -> IndexGroup(feedId, stories.first().feedTitle, stories) }
    }
}
