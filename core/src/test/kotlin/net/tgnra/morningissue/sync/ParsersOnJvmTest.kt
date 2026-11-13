package net.tgnra.morningissue.sync

/*
 * The parser tests themselves are in core's test fixtures, so that the app can repeat them under
 * Robolectric with Android's own XmlPullParser (`ParsersOnAndroidTest`). Here they run on the
 * plain JVM with kxml2.
 */

class FeedParserOnJvmTest : FeedParserTest()

class FeedSignalsParserOnJvmTest : FeedSignalsParserTest()

class OpmlOnJvmTest : OpmlTest()
