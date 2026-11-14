package net.tgnra.morningissue.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads the model's reply to an [InterestPrompt]. The model is asked for `{"1": 4, "2": 2}`,
 * but a small model does not always do as asked, so this is defensive: anything it cannot read
 * with confidence counts as "unrated", which scores as if there were no model (N = 1).
 *
 * - The JSON may be wrapped in prose or a code fence; the outermost `{…}` (or `[…]`) is taken.
 * - Keys are the posts' numbers, 1 to `count`; others are ignored.
 * - A rating is a whole number from 1 to 5, written as a number (4 or 4.0) or a string ("4").
 *   Anything else (0, 6, 3.5, "high", null) leaves the post unrated.
 * - A post given two different ratings is unrated.
 * - `{"ratings": [{"id": 1, "rating": 4}, …]}` and a bare array of such objects are read too.
 * - Anything that is not JSON at all gives no ratings.
 */
object InterestReply {
    private val json = Json { isLenient = true }

    /** The ratings in [reply], by post number (1-based); a post without a usable rating is missing. */
    fun parse(reply: String, count: Int): Map<Int, Int> {
        for (element in candidates(reply)) {
            val ratings = read(element, count)
            if (ratings.isNotEmpty()) return ratings
        }
        return emptyMap()
    }

    private fun read(element: JsonElement, count: Int): Map<Int, Int> {
        val pairs: List<Pair<Int?, Int?>> = when (element) {
            is JsonObject -> {
                val list = element["ratings"] as? JsonArray
                if (list != null) fromArray(list) else element.map { (key, value) -> key.trim().toIntOrNull() to rating(value) }
            }
            is JsonArray -> fromArray(element)
            else -> return emptyMap()
        }
        val ratings = HashMap<Int, Int>()
        val unrated = HashSet<Int>()
        for ((number, rating) in pairs) {
            if (number == null || number !in 1..count) continue
            if (rating == null) {
                unrated += number
                continue
            }
            val earlier = ratings.put(number, rating)
            if (earlier != null && earlier != rating) unrated += number
        }
        return ratings - unrated
    }

    private fun fromArray(array: JsonArray): List<Pair<Int?, Int?>> = array.mapNotNull { entry ->
        val item = entry as? JsonObject ?: return@mapNotNull null
        val id = item["id"] ?: item["post"] ?: item["number"]
        val rating = item["rating"] ?: item["score"]
        (id as? JsonPrimitive)?.let(::whole) to rating?.let(::rating)
    }

    /** A rating from 1 to 5, or null. */
    private fun rating(value: JsonElement): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        return whole(primitive)?.takeIf { it in 1..5 }
    }

    /** A whole number written as 4, 4.0 or "4"; null for anything else. */
    private fun whole(primitive: JsonPrimitive): Int? {
        primitive.intOrNull?.let { return it }
        val text = primitive.content.trim()
        text.toIntOrNull()?.let { return it }
        val number = primitive.doubleOrNull ?: text.toDoubleOrNull() ?: return null
        return if (number == Math.floor(number) && number in -1e6..1e6) number.toInt() else null
    }

    /**
     * The outermost JSON object and array in [reply] that parse, the one whose bracket opens
     * first coming first (an array of objects starts with `[`, an object of ratings with `{`).
     */
    private fun candidates(reply: String): List<JsonElement> {
        val brackets = listOf('{' to '}', '[' to ']').sortedBy { (open, _) -> reply.indexOf(open).let { if (it < 0) Int.MAX_VALUE else it } }
        return brackets.mapNotNull { (open, close) ->
            val start = reply.indexOf(open)
            val end = reply.lastIndexOf(close)
            if (start < 0 || end <= start) return@mapNotNull null
            try {
                json.parseToJsonElement(reply.substring(start, end + 1))
            } catch (_: Exception) {
                null
            }
        }
    }
}
