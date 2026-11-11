package net.tgnra.morningissue.data.images

/** Every number the image store works to. */
object ImageStoreConfig {
    /**
     * The longest edge of a stored image, in pixels. The largest slot an image is shown in is
     * the reader's column on a tablet, 640 dp (`PaperLayoutSpec.Expanded.readerMaxMeasure`; the
     * paper's lead across two of four columns is about the same), which is 1,280 px at the 2×
     * density usual for tablets. A phone's full width is 1,080 px on most and 1,440 px on a
     * few, where a stored image is scaled up by an eighth. More detail than this is what "tap
     * to see the original" is for.
     */
    const val MAX_EDGE_PX = 1280

    /**
     * Lossy WebP quality, 0..100. Chosen from `ImageRecompressionMeasurementTest` on Wikimedia
     * Commons pictures (the table is under "The image store" in `docs/how-it-works.md`). On
     * five photographs already at web size, the stored copies came to 10% of the originals'
     * bytes at 70, 13% at 80 and 20% at 90, at a mean PSNR of 34.7, 36.5 and 39.1 dB. 80 is the
     * usual middle: 90 costs half as much again for 2.6 dB, 70 saves a quarter for 1.8 dB less.
     * The figures are from Android's encoder as Robolectric builds it for the host, not from a device.
     */
    const val QUALITY = 80

    /** A download is abandoned beyond this; nothing that large is a picture in an article. */
    const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024

    /**
     * The most that is stored without re-encoding: an animated GIF or WebP, an SVG, or an
     * original that came out smaller than its re-encoding. A larger one is not stored at all
     * and is shown from the network as before, so the store never holds a 15 MB animation.
     */
    const val MAX_UNCHANGED_BYTES = 2L * 1024 * 1024

    /** Network transfers at a time. */
    const val PARALLEL_DOWNLOADS = 4

    /**
     * Images decoded and re-encoded at a time. One: a 40-megapixel photograph decoded at a
     * quarter of its size is about 10 MB of pixels, and a second copy exists while it is scaled.
     */
    const val PARALLEL_RECOMPRESSIONS = 1

    /** The download of one image, from the moment it starts; a slower one is skipped. */
    const val DOWNLOAD_TIMEOUT_MILLIS = 30_000L

    /** Body images stored per article, in reading order; a photo essay's later pictures come from the network. */
    const val MAX_BODY_IMAGES_PER_ARTICLE = 24

    /** A body image the feed says is narrower than this is a tracking pixel or an icon; the reader does not show it. */
    const val MIN_BODY_IMAGE_WIDTH_PX = 80

    /** A half-written download older than this was left by a killed process and is swept away. */
    const val TEMP_MAX_AGE_MILLIS = 60 * 60 * 1000L
}
