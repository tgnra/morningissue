package net.tgnra.morningissue.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.HealthResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    @Test
    fun healthAnswersWithoutAuthentication() = testApplication {
        application { morningIssue() }
        val response = client.get(ApiPaths.BASE + ApiPaths.HEALTH)
        assertEquals(HttpStatusCode.OK, response.status)
        val health = ApiJson.json.decodeFromString<HealthResponse>(response.bodyAsText())
        assertTrue(health.ok)
        assertEquals(1, health.api)
    }
}
