package net.tgnra.morningissue.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reading the model's reply: anything it cannot read with confidence is "unrated". */
class InterestReplyTest {
    private fun parse(reply: String, count: Int = 3) = InterestReply.parse(reply, count)

    @Test
    fun theAskedForShapeIsRead() {
        assertEquals(mapOf(1 to 4, 2 to 2, 3 to 5), parse("""{"1": 4, "2": 2, "3": 5}"""))
    }

    @Test
    fun proseAndCodeFencesAroundTheJsonAreIgnored() {
        assertEquals(mapOf(1 to 4, 2 to 1), parse("Sure! Here are the ratings:\n```json\n{\"1\": 4, \"2\": 1}\n```\nHope this helps."))
    }

    @Test
    fun numbersWrittenAsStringsOrWholeDecimalsCount() {
        assertEquals(mapOf(1 to 4, 2 to 3, 3 to 5), parse("""{"1": "4", "2": 3.0, " 3 ": " 5 "}"""))
    }

    @Test
    fun ratingsOutsideOneToFiveOrNotWholeAreUnrated() {
        assertEquals(mapOf(3 to 2), parse("""{"1": 0, "2": 6, "3": 2}"""))
        assertEquals(emptyMap<Int, Int>(), parse("""{"1": 3.5, "2": "high", "3": null}"""))
        assertEquals(emptyMap<Int, Int>(), parse("""{"1": [4], "2": {"r": 4}, "3": true}"""))
        assertEquals(mapOf(2 to 1), parse("""{"1": -1, "2": 1}"""))
    }

    @Test
    fun postNumbersOutsideTheBatchAreIgnored() {
        assertEquals(mapOf(2 to 4), parse("""{"0": 5, "2": 4, "4": 5, "101": 3, "one": 2}"""))
    }

    @Test
    fun missingPostsAreUnrated() {
        assertEquals(mapOf(1 to 5), parse("""{"1": 5}""", count = 10))
    }

    @Test
    fun aPostGivenTwoDifferentRatingsIsUnrated() {
        assertEquals(mapOf(2 to 3), parse("""{"ratings": [{"id": 1, "rating": 4}, {"id": 1, "rating": 2}, {"id": 2, "rating": 3}]}"""))
        // The same rating twice is no contradiction.
        assertEquals(mapOf(1 to 4), parse("""[{"id": 1, "rating": 4}, {"id": "1", "rating": "4"}]"""))
    }

    @Test
    fun aListOfRatingsIsReadToo() {
        assertEquals(mapOf(1 to 4, 2 to 2), parse("""{"ratings": [{"id": 1, "rating": 4}, {"id": 2, "rating": 2}]}"""))
        assertEquals(mapOf(1 to 5, 3 to 1), parse("""Ratings: [{"post": 1, "score": 5}, {"number": 3, "rating": 1}, {"rating": 4}, 7]"""))
        assertEquals(mapOf(1 to 2), parse("""[{"id": 1, "rating": 2}]"""))
    }

    @Test
    fun bracketsInTheProseDoNotHideTheJson() {
        assertEquals(mapOf(1 to 4), parse("""[as requested] {"1": 4}"""))
    }

    @Test
    fun anythingThatIsNotJsonGivesNoRatings() {
        for (reply in listOf(
            "",
            "I cannot help with that.",
            "1: 4, 2: 2",
            "{\"1\": 4, \"2\": ",
            "{{{{",
            "}{",
            "[]",
            "{}",
            "\"4\"",
            "{\"1\" 4}",
        )) {
            assertEquals("for: $reply", emptyMap<Int, Int>(), parse(reply))
        }
    }

    @Test
    fun aTruncatedReplyWithACompleteObjectEarlierStillCounts() {
        // Cut off by the token limit after a complete object: the outermost braces still close it.
        assertEquals(mapOf(1 to 3, 2 to 4), parse("""{"1": 3, "2": 4} and then the model went on {"""))
    }
}
