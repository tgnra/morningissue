package net.tgnra.morningissue.server.config

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigTest {
    private val full = """
        [server]
        listen = "127.0.0.1"
        port = 9000
        publicUrl = "https://news.example.org/"   # shown to Kobos
        dataDir = "/srv/morningissue"

        [users]
        names = ["tania", "sam"]

        [refresh]
        intervalMinutes = 60
        prePrintMaxAgeMinutes = 90
        concurrency = 3
        timeoutSeconds = 100

        [editions]
        buildLeadMinutes = 20

        [kobo]
        imageCacheMb = 256

        [import]
        maxMb = 100
    """.trimIndent()

    @Test
    fun readsEverySetting() {
        val c = ConfigLoader.parse(full)
        assertEquals("127.0.0.1", c.listen)
        assertEquals(9000, c.port)
        assertEquals("https://news.example.org", c.publicUrl)
        assertEquals(File("/srv/morningissue"), c.dataDir)
        assertEquals(listOf("tania", "sam"), c.users)
        assertEquals(RefreshConfig(60, 90, 3, 100), c.refresh)
        assertEquals(20, c.editions.buildLeadMinutes)
        assertEquals(256L * 1024 * 1024, c.kobo.imageCacheBytes)
        assertEquals(100L * 1024 * 1024, c.import.maxBytes)
        assertEquals(File("/srv/morningissue/initial-passwords"), c.initialPasswordsDir)
        assertEquals(File("/srv/morningissue/morningissue.db"), c.databaseFile)
    }

    @Test
    fun anEmptyFileGivesTheDesignDefaults() {
        val c = ConfigLoader.parse("")
        assertEquals(ServerConfig(), c)
        assertEquals("0.0.0.0", c.listen)
        assertEquals(8417, c.port)
        assertNull(c.publicUrl)
        assertEquals(File("/var/lib/morningissue"), c.dataDir)
        assertEquals(emptyList<String>(), c.users)
        assertEquals(120, c.refresh.intervalMinutes)
        assertEquals(120, c.refresh.prePrintMaxAgeMinutes)
        assertEquals(6, c.refresh.concurrency)
        assertEquals(240, c.refresh.timeoutSeconds)
        assertEquals(30, c.editions.buildLeadMinutes)
        assertEquals(1024, c.kobo.imageCacheMb)
        assertEquals(4096, c.import.maxMb)
    }

    @Test
    fun theEnvironmentOverridesTheFile() {
        val c = ConfigLoader.applyEnv(
            ConfigLoader.parse(full),
            mapOf(
                "MORNINGISSUE_USERS" to " alice, bob ,,",
                "MORNINGISSUE_PORT" to "8080",
                "MORNINGISSUE_LISTEN" to "::",
                "MORNINGISSUE_PUBLIC_URL" to "http://kobo.lan:8080/",
                "MORNINGISSUE_DATA_DIR" to "/data",
            ),
        )
        assertEquals(listOf("alice", "bob"), c.users)
        assertEquals(8080, c.port)
        assertEquals("::", c.listen)
        assertEquals("http://kobo.lan:8080", c.publicUrl)
        assertEquals(File("/data"), c.dataDir)
        // Untouched by the environment.
        assertEquals(60, c.refresh.intervalMinutes)
    }

    @Test
    fun blankEnvironmentValuesAreIgnored() {
        val c = ConfigLoader.applyEnv(ConfigLoader.parse(full), mapOf("MORNINGISSUE_USERS" to " ", "MORNINGISSUE_PORT" to ""))
        assertEquals(listOf("tania", "sam"), c.users)
        assertEquals(9000, c.port)
    }

    @Test
    fun dockerRunsWithNoFileAtAll() {
        val c = ConfigLoader.load(null, mapOf("MORNINGISSUE_CONFIG" to "", "MORNINGISSUE_USERS" to "tania", "MORNINGISSUE_DATA_DIR" to "/data"))
        // /etc/morningissue/config.toml is optional when not named.
        assertEquals(listOf("tania"), c.users)
        assertEquals(File("/data"), c.dataDir)
    }

    @Test
    fun theFileComesFromTheFlagThenTheEnvironment() {
        val dir = Files.createTempDirectory("config-test").toFile()
        try {
            val a = File(dir, "a.toml").apply { writeText("[server]\nport = 1111\n") }
            val b = File(dir, "b.toml").apply { writeText("[server]\nport = 2222\n") }
            assertEquals(1111, ConfigLoader.load(a.path, mapOf("MORNINGISSUE_CONFIG" to b.path)).port)
            assertEquals(2222, ConfigLoader.load(null, mapOf("MORNINGISSUE_CONFIG" to b.path)).port)
            assertEquals(File(ConfigLoader.DEFAULT_PATH) to false, ConfigLoader.locate(null, emptyMap()))
            expectError("does not exist") { ConfigLoader.load(File(dir, "missing.toml").path, emptyMap()) }
            expectError("does not exist") { ConfigLoader.load(null, mapOf("MORNINGISSUE_CONFIG" to File(dir, "missing.toml").path)) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownKeysAreWarnedAboutAndIgnored() {
        val warnings = ArrayList<String>()
        val c = ConfigLoader.parse("[server]\nport = 1234\nhttps = true\n[mystery]\nx = 1\n", "t.toml") { warnings += it }
        assertEquals(1234, c.port)
        assertEquals(listOf("t.toml: unknown key \"server.https\" ignored", "t.toml: unknown key \"mystery\" ignored"), warnings)
    }

    @Test
    fun badValuesAreErrorsThatNameTheKey() {
        expectError("server.port must be an integer") { ConfigLoader.parse("[server]\nport = \"80\"") }
        expectError("server.port must be 1..65535") { ConfigLoader.parse("[server]\nport = 70000") }
        expectError("users.names must be a list of strings") { ConfigLoader.parse("[users]\nnames = \"tania\"") }
        expectError("users.names must be a list of strings") { ConfigLoader.parse("[users]\nnames = [1, 2]") }
        expectError("not a usable user name") { ConfigLoader.parse("[users]\nnames = [\"../root\"]") }
        expectError("listed more than once") { ConfigLoader.parse("[users]\nnames = [\"a\", \"a\"]") }
        expectError("refresh.concurrency must be above 0") { ConfigLoader.parse("[refresh]\nconcurrency = 0") }
        expectError("MORNINGISSUE_PORT must be a number") { ConfigLoader.applyEnv(ServerConfig(), mapOf("MORNINGISSUE_PORT" to "http")) }
        expectError("not a usable user name") { ConfigLoader.applyEnv(ServerConfig(), mapOf("MORNINGISSUE_USERS" to "ok,not ok")) }
        expectError("config:") { ConfigLoader.parse("[server\nport = 1") }
    }

    private fun expectError(fragment: String, block: () -> Unit) {
        try {
            block()
        } catch (e: ConfigException) {
            assertTrue("\"${e.message}\" should mention \"$fragment\"", e.message!!.contains(fragment))
            return
        }
        fail("expected a ConfigException mentioning \"$fragment\"")
    }
}
