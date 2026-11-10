package net.tgnra.morningissue.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/*
 * The plain JVM tests parse with kxml2. These repeat them under Robolectric, where
 * `XmlPullParserFactory` hands out the parser that ships in Android
 * (`com.android.org.kxml2.io.KXmlParser`), which differs in small ways.
 */

@RunWith(AndroidJUnit4::class)
class FeedParserOnAndroidTest : FeedParserTest()

@RunWith(AndroidJUnit4::class)
class OpmlOnAndroidTest : OpmlTest()
