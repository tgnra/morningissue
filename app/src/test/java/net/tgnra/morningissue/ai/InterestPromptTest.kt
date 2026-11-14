package net.tgnra.morningissue.ai

import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The taste profile and the prompt the on-device model is given. */
class InterestPromptTest {
    private val profile = TasteProfile.build(
        tags = listOf("Space" to 0.82, "Politics" to 0.3, "Programming" to 0.71, "Cooking" to 0.5),
        feeds = listOf("Ars Technica" to SliderStep.MORE, "Daring Fireball" to SliderStep.FAVOURITE, "The Verge" to SliderStep.AUTO, "Noise" to SliderStep.RARELY),
        recentTitles = listOf("Starship's fifth flight", "A tour of Kotlin coroutines"),
    )

    private fun posts(n: Int) = (1..n).map { PostForRating(articleId = 100L + it, title = "Post $it", feed = "Feed $it", summary = "First sentence of $it. Second one here. Third, not shown.") }

    @Test
    fun theProfileTakesTheStrongestTagsTheLovedFeedsAndRecentLikes() {
        // Only tags above neutral, strongest first.
        assertEquals(listOf("Space", "Programming"), profile.topics)
        // Favourite before More; Auto and below are left out.
        assertEquals(listOf("Daring Fireball", "Ars Technica"), profile.sources)
        assertEquals(listOf("Starship's fifth flight", "A tour of Kotlin coroutines"), profile.recent)
        assertFalse(profile.isEmpty)
        assertEquals(
            "Topics they read most: Space, Programming\n" +
                "Sources they asked to see more of: Daring Fireball, Ars Technica\n" +
                "Posts they recently liked or saved:\n" +
                "- Starship's fifth flight\n" +
                "- A tour of Kotlin coroutines",
            profile.text(),
        )
    }

    @Test
    fun theProfileIsKeptShort() {
        val big = TasteProfile.build(
            tags = (1..20).map { "Tag $it" to 0.6 + it * 0.01 },
            feeds = (1..20).map { "Feed $it" to SliderStep.FAVOURITE },
            recentTitles = (1..20).map { "A rather long title number $it ".repeat(6) },
        )
        assertEquals(TasteProfile.MAX_TOPICS, big.topics.size)
        assertEquals("Tag 20", big.topics.first())
        assertEquals(TasteProfile.MAX_SOURCES, big.sources.size)
        assertEquals(TasteProfile.MAX_RECENT, big.recent.size)
        assertTrue(big.recent.all { it.length <= TasteProfile.MAX_TITLE_CHARS + 1 && it.endsWith("…") })
        assertTrue(big.text().length < 1_500)
    }

    @Test
    fun aReaderNobodyKnowsHasAnEmptyProfile() {
        val empty = TasteProfile.build(listOf("News" to 0.5), listOf("Feed" to SliderStep.AUTO), emptyList())
        assertTrue(empty.isEmpty)
    }

    @Test
    fun theHashFollowsTheProfile() {
        val same = TasteProfile(profile.topics, profile.sources, profile.recent)
        val moreLikes = profile.copy(recent = listOf("Something new") + profile.recent)

        assertEquals(profile.hash(), same.hash())
        assertNotEquals(profile.hash(), moreLikes.hash())
        assertEquals(16, profile.hash().length)
        assertTrue(profile.hash().all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun thePromptNumbersThePostsAndAsksForJson() {
        val prompt = InterestPrompt.build(profile, posts(3))

        assertTrue(prompt.contains(profile.text()))
        assertTrue(prompt.contains("1. Post 1 (Feed 1): First sentence of 1. Second one here.\n"))
        assertTrue(prompt.contains("3. Post 3 (Feed 3): First sentence of 3. Second one here.\n"))
        assertFalse(prompt.contains("Third, not shown"))
        // Article IDs are not shown; the reply uses the numbers.
        assertFalse(prompt.contains("101"))
        assertTrue(prompt.contains("from 1 to 5"))
        assertTrue(prompt.contains("every post from 1 to 3"))
        assertTrue(prompt.contains("{\"1\": 4, \"2\": 2}"))
    }

    @Test
    fun aBatchOfTenStaysWellWithinNanosContext() {
        val long = (1..10).map { PostForRating(it.toLong(), "T".repeat(300), "F".repeat(100), "S".repeat(2_000)) }
        val prompt = InterestPrompt.build(profile, long)
        // Roughly four characters a token: well under a thousand tokens.
        assertTrue("prompt is ${prompt.length} characters", prompt.length < 4_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun moreThanABatchIsRefused() {
        InterestPrompt.build(profile, posts(11))
    }

    @Test
    fun theSnippetIsTheFirstLineOrTwo() {
        assertEquals("One. Two!", InterestPrompt.snippet("One. Two! Three?"))
        assertEquals("Only one sentence", InterestPrompt.snippet("  Only   one\nsentence "))
        assertNull(InterestPrompt.snippet(null))
        assertNull(InterestPrompt.snippet("   "))
        val long = InterestPrompt.snippet("word ".repeat(100))!!
        assertTrue(long.length <= InterestPrompt.MAX_SNIPPET_CHARS + 1)
        assertTrue(long.endsWith("…"))
        // A post without text is shown by its title and feed alone.
        val prompt = InterestPrompt.build(profile, listOf(PostForRating(1, "Bare", "Feed", null)))
        assertTrue(prompt.contains("1. Bare (Feed)\n"))
    }
}
