package net.tgnra.morningissue.server.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.ApiQuery
import net.tgnra.morningissue.api.OpenDiscoveredRequest
import net.tgnra.morningissue.api.TopicsRequest
import net.tgnra.morningissue.api.VerdictRequest

/** 4.7: discovery, all on the server. */
internal fun Route.discoveryRoutes(s: ServerServices) {
    get(ApiPaths.DISCOVERY_DECK, Access.PHONE) { p ->
        call.respond(s.discovery.deck(p.userId))
    }
    get(ApiPaths.DISCOVERY_TOPICS, Access.PHONE) { p ->
        call.respond(s.discovery.topics(p.userId))
    }
    post(ApiPaths.DISCOVERY_TOPICS, Access.PHONE) { p ->
        call.respond(s.discovery.chooseTopics(p.userId, call.receiveJson<TopicsRequest>()))
    }
    get(ApiPaths.DISCOVERY_LIVE, Access.PHONE) { p ->
        call.respond(s.discovery.live(p.userId, call.requiredQuery(ApiQuery.URL)))
    }
    post(ApiPaths.DISCOVERY_VERDICTS, Access.PHONE) { p ->
        call.respond(s.discovery.decide(p.userId, call.receiveJson<VerdictRequest>()))
    }
    delete(ApiPaths.DISCOVERY_VERDICTS, Access.PHONE) { p ->
        s.discovery.undo(p.userId, call.requiredQuery(ApiQuery.URL))
        call.respond(HttpStatusCode.NoContent)
    }
    get(ApiPaths.DISCOVERY_PASSED, Access.PHONE) { p ->
        call.respond(s.discovery.passed(p.userId))
    }
    post(ApiPaths.DISCOVERY_OPEN, Access.PHONE) { p ->
        call.respond(s.discovery.open(p.userId, call.receiveJson<OpenDiscoveredRequest>()))
    }
}
