package net.tgnra.morningissue.api

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire forms the server, the phone and the Kobo agree on. */
class ApiDtosTest {
    private val json = ApiJson.json
    private val at = Instant.parse("2026-11-11T06:30:00Z")

    @Test
    fun timesAreIsoInstantsDatesAndLocalTimes() {
        val settings = ServerSettings(
            resetTime = LocalTime.of(6, 30), editionLength = EditionLength.STANDARD, eveningEnabled = false,
            eveningTime = LocalTime.of(18, 0), timeZone = "Europe/London", extraOfferDismissedAt = at,
            nextRelease = ReleaseDto(EditionKind.MORNING, at),
        )
        val text = json.encodeToString(settings)
        assertTrue(text, "\"resetTime\":\"06:30\"" in text)
        assertTrue(text, "\"extraOfferDismissedAt\":\"2026-11-11T06:30:00Z\"" in text)
        assertEquals(settings, json.decodeFromString<ServerSettings>(text))
        // Offsets and fractions are read too.
        val offset = json.decodeFromString<ReleaseDto>("""{"kind":"EVENING","at":"2026-11-11T07:30:00.250+01:00"}""")
        assertEquals(Instant.parse("2026-11-11T06:30:00.250Z"), offset.at)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val health = json.decodeFromString<HealthResponse>("""{"ok":true,"version":"1.2.3","api":1,"later":{"x":1}}""")
        assertEquals(HealthResponse(version = "1.2.3"), health)
    }

    @Test
    fun aPatchTellsAnAbsentKeyFromNull() {
        assertEquals(Patch.Absent, json.decodeFromString<FeedPatch>("""{"title":"New"}""").muteUntil)
        assertEquals(Patch.Set(null), json.decodeFromString<FeedPatch>("""{"muteUntil":null}""").muteUntil)
        assertEquals(Patch.Set(at), json.decodeFromString<FeedPatch>("""{"muteUntil":"2026-11-11T06:30:00Z"}""").muteUntil)

        assertFalse("muteUntil" in json.encodeToString(FeedPatch(title = "New")))
        assertTrue("\"muteUntil\":null" in json.encodeToString(FeedPatch(muteUntil = Patch.of(null))))
        assertEquals(at, json.decodeFromString<SettingsPatch>(json.encodeToString(SettingsPatch(extraOfferDismissedAt = Patch.of(at)))).extraOfferDismissedAt.orElse(null))
        assertEquals(at, Patch.Absent.orElse(at))
    }

    @Test
    fun passwordChangeUsesTheKeyNew() {
        val text = json.encodeToString(ChangePasswordRequest("generated", "a long new password"))
        assertEquals("""{"current":"generated","new":"a long new password"}""", text)
    }

    @Test
    fun eventsAreTheKoboFormat() {
        val request = json.decodeFromString<EventsRequest>(
            """{"events":[{"seq":45,"type":"liked","articleId":9001,"value":true,"at":"2026-11-11T06:30:00Z"},
               {"seq":47,"type":"pageView","editionId":120,"page":2,"ms":8200,"at":"2026-11-11T06:31:00Z"}]}""",
        )
        assertEquals(listOf(45L, 47L), request.events.map(SyncEvent::seq))
        assertEquals(8200L, request.events[1].ms)
    }

    @Test
    fun aBundleWritesNullsAndTheKoboFields() {
        val bundle = EditionBundleDto(
            id = 120, date = LocalDate.of(2026, 11, 11), kind = EditionKind.MORNING, number = 120,
            length = EditionLength.STANDARD, releaseAt = at, finishedAt = null,
            pages = listOf(BundlePageDto(1, listOf(BundleItemDto(9001, 0, ItemKind.LEAD, null, "Reason")))),
            articles = mapOf(
                "9001" to BundleArticleDto(
                    feedTitle = "Feed", title = "Title", author = null, url = null, publishedAt = at, summary = null,
                    imageUrl = null, wordCount = 10, blocks = JsonArray(emptyList()), engagement = BundleEngagementDto(),
                ),
            ),
        )
        val text = json.encodeToString(bundle)
        assertTrue(text, "\"finishedAt\":null" in text)
        assertTrue(text, "\"date\":\"2026-11-11\"" in text)
        assertTrue(text, "\"kind\":\"LEAD\"" in text)
        val article = json.parseToJsonElement(text).jsonObject.getValue("articles").jsonObject.getValue("9001").jsonObject
        assertEquals(JsonArray(emptyList()), article.getValue("blocks"))
        assertEquals(bundle, json.decodeFromString<EditionBundleDto>(text))
    }

    @Test
    fun theManifestSaysServerOne() {
        val manifest = ManifestDto(dbId = "db", serverTime = at, editions = emptyList(), saved = ManifestSavedDto(0, "v"), acked = mapOf("phone" to 3))
        val text = json.encodeToString(manifest)
        assertTrue(text, "\"protocol\":\"server/1\"" in text)
        assertTrue(text, "\"acked\":{\"phone\":3}" in text)
    }

    @Test
    fun everyErrorCodeHasItsStatus() {
        assertEquals(400, ApiError.statusOf(ApiError.EVENTS_THROUGH_REQUIRED))
        assertEquals(401, ApiError.statusOf(ApiError.UNKNOWN))
        assertEquals(403, ApiError.statusOf(ApiError.FORBIDDEN_FOR_DEVICE))
        assertEquals(409, ApiError.statusOf(ApiError.EVENTS_PENDING))
        assertEquals(422, ApiError.statusOf(ApiError.NOT_A_FEED))
        assertEquals(503, ApiError.statusOf(ApiError.BUSY))
        val pending = json.encodeToString(ApiErrorBody(ApiError.EVENTS_PENDING, "Upload your events first", acked = 41))
        assertEquals("""{"error":"events_pending","message":"Upload your events first","acked":41}""", pending)
    }

    @Test
    fun theImportModeIsLowerCaseInTheQuery() {
        assertEquals(ApiQuery.MODE_MERGE, ImportMode.MERGE.query)
        assertEquals(ApiQuery.MODE_REPLACE, ImportMode.REPLACE.query)
        assertEquals("/editions/7/bundle", ApiPaths.editionBundle(7))
        assertEquals(ApiPaths.EDITION_BUNDLE.replace("{id}", "7"), ApiPaths.editionBundle(7))
    }
}
