package net.tgnra.morningissue.server.http

import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.server.service.ArticleService
import net.tgnra.morningissue.server.service.AuthService
import net.tgnra.morningissue.server.service.DeviceService
import net.tgnra.morningissue.server.service.DiscoveryService
import net.tgnra.morningissue.server.service.EditionService
import net.tgnra.morningissue.server.service.FeedService
import net.tgnra.morningissue.server.service.ImportService
import net.tgnra.morningissue.server.service.JobService
import net.tgnra.morningissue.server.service.KoboImageService
import net.tgnra.morningissue.server.service.SearchService
import net.tgnra.morningissue.server.service.SettingsService
import net.tgnra.morningissue.server.service.StandService
import net.tgnra.morningissue.server.service.SyncService

/**
 * The services the routes call (section 4.12). The routes depend on nothing else. A service not
 * given answers `501 not_implemented`, so the server runs before every workstream has landed.
 */
data class ServerServices(
    val auth: AuthService = unimplemented(),
    val devices: DeviceService = unimplemented(),
    val feeds: FeedService = unimplemented(),
    val articles: ArticleService = unimplemented(),
    val search: SearchService = unimplemented(),
    val discovery: DiscoveryService = unimplemented(),
    val editions: EditionService = unimplemented(),
    val stand: StandService = unimplemented(),
    val jobs: JobService = unimplemented(),
    val sync: SyncService = unimplemented(),
    val settings: SettingsService = unimplemented(),
    val imports: ImportService = unimplemented(),
    val koboImages: KoboImageService = unimplemented(),
)

/** What the HTTP layer needs beyond the services: where uploads go and how big they may be. */
data class HttpOptions(
    /** `<dataDir>/imports`: `POST /import` streams the archive here. */
    val importsDir: File = File(System.getProperty("java.io.tmpdir"), "morningissue-imports"),
    val importMaxBytes: Long = 4096L * 1024 * 1024,
    val maxBodyBytes: Long = ApiLimits.MAX_BODY_BYTES,
    val opmlMaxBytes: Long = ApiLimits.MAX_OPML_BYTES,
    /** `POST /kobo/images` carries at most 100 URLs; this is generous for that. */
    val koboImagesMaxBytes: Long = 4L * 1024 * 1024,
)

/**
 * A stand-in for a service that is not wired yet: every call throws [NotImplementedError], which
 * the HTTP layer answers with 501.
 */
inline fun <reified T : Any> unimplemented(): T = unimplemented(T::class.java)

fun <T : Any> unimplemented(type: Class<T>): T {
    val handler = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "toString" -> "Unimplemented(${type.simpleName})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw NotImplementedError("${type.simpleName}.${method.name} is not implemented yet")
        }
    }
    return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))
}
