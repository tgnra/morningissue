package net.tgnra.morningissue.embedding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** The Android side: the asset is found in the app's merged assets and loaded once. */
@RunWith(AndroidJUnit4::class)
class EmbedderAssetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theBundledAssetLoadsAndEmbedsLikeTheFileInTheSourceTree() = runTest {
        val embedder = EmbedderAsset(context, StandardTestDispatcher(testScheduler)).get()
        val text = "The quiet return of the fountain pen"
        assertArrayEquals(EmbeddingFixtures.embedder.embed(text), embedder.embed(text), 0f)
    }

    @Test
    fun concurrentCallersShareOneLoad() = runTest {
        val asset = EmbedderAsset(context, StandardTestDispatcher(testScheduler))
        val all = List(4) { async { asset.get() } }.awaitAll()
        all.forEach { assertSame(all.first(), it) }
        assertSame(all.first(), asset.get())
    }
}
