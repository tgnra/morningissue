package net.tgnra.morningissue.data.images

import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/** What a downloaded file is, going by its first bytes (never by its address or stated type). */
enum class ImageKind {
    JPEG, PNG, GIF, WEBP,

    /** BMP, HEIF and AVIF: decoded if this Android can, otherwise not stored. */
    OTHER_BITMAP,

    /** A vector drawing. The store cannot draw it and never re-encodes it. */
    SVG,

    /** An error page, a redirect to a login form, anything else. Never stored. */
    NOT_AN_IMAGE,
}

/** Reads just enough of an image file to say what it is and whether it moves. Pure Kotlin. */
object ImageFormats {
    /** How much of the file [kindOf] and [isAnimated] want to see. */
    const val HEAD_BYTES = 4096

    fun kindOf(head: ByteArray): ImageKind = when {
        head.startsWith(0xFF, 0xD8, 0xFF) -> ImageKind.JPEG
        head.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> ImageKind.PNG
        head.hasAscii(0, "GIF8") -> ImageKind.GIF
        head.hasAscii(0, "RIFF") && head.hasAscii(8, "WEBP") -> ImageKind.WEBP
        head.hasAscii(0, "BM") && head.size > 26 -> ImageKind.OTHER_BITMAP
        head.hasAscii(4, "ftyp") && HEIF_BRANDS.any { head.hasAscii(8, it) } -> ImageKind.OTHER_BITMAP
        looksLikeSvg(head) -> ImageKind.SVG
        else -> ImageKind.NOT_AN_IMAGE
    }

    /**
     * Whether [file] of [kind] has more than one frame: a GIF with a second image, a WebP whose
     * header sets the animation flag, a PNG with an animation chunk before its pixels.
     */
    fun isAnimated(kind: ImageKind, head: ByteArray, file: File): Boolean = when (kind) {
        ImageKind.GIF -> gifFrames(file, stopAt = 2) >= 2
        ImageKind.WEBP -> head.hasAscii(12, "VP8X") && head.size > 20 && (head[20].toInt() and WEBP_ANIMATION_FLAG) != 0
        ImageKind.PNG -> {
            val animation = head.indexOfAscii("acTL")
            val pixels = head.indexOfAscii("IDAT")
            animation >= 0 && (pixels < 0 || animation < pixels)
        }
        else -> false
    }

    /** Counts the images in a GIF, giving up at [stopAt]; 0 for a file that is not a readable GIF. */
    fun gifFrames(file: File, stopAt: Int = Int.MAX_VALUE): Int = try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val header = ByteArray(13)
            input.readFully(header)
            if (!header.hasAscii(0, "GIF8")) return 0
            input.skipColourTable(header[10].toInt())
            var frames = 0
            while (frames < stopAt) {
                when (input.read()) {
                    GIF_EXTENSION -> {
                        input.skipFully(1)
                        input.skipSubBlocks()
                    }
                    GIF_IMAGE -> {
                        val descriptor = ByteArray(9)
                        input.readFully(descriptor)
                        input.skipColourTable(descriptor[8].toInt())
                        input.skipFully(1)
                        input.skipSubBlocks()
                        frames++
                    }
                    else -> break
                }
            }
            frames
        }
    } catch (_: EOFException) {
        0
    }

    private fun DataInputStream.skipColourTable(flags: Int) {
        if (flags and 0x80 != 0) skipFully(3 * (1 shl ((flags and 0x07) + 1)))
    }

    private fun DataInputStream.skipSubBlocks() {
        while (true) {
            val length = readUnsignedByte()
            if (length == 0) return
            skipFully(length)
        }
    }

    private fun DataInputStream.skipFully(count: Int) {
        var left = count
        while (left > 0) {
            val skipped = skipBytes(left)
            if (skipped <= 0) {
                readByte()
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun looksLikeSvg(head: ByteArray): Boolean {
        val text = String(head, Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\n', '\r', '\t')
        if (!text.startsWith("<")) return false
        val tag = text.indexOf("<svg", ignoreCase = true)
        return tag >= 0 && !text.substring(0, tag).contains("<html", ignoreCase = true)
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { (this[it].toInt() and 0xFF) == bytes[it] }

    private fun ByteArray.hasAscii(offset: Int, text: String): Boolean =
        size >= offset + text.length && text.indices.all { this[offset + it].toInt().toChar() == text[it] }

    private fun ByteArray.indexOfAscii(text: String): Int =
        (0..size - text.length).firstOrNull { hasAscii(it, text) } ?: -1

    private val HEIF_BRANDS = listOf("heic", "heix", "hevc", "mif1", "msf1", "avif", "avis")
    private const val WEBP_ANIMATION_FLAG = 0x02
    private const val GIF_EXTENSION = 0x21
    private const val GIF_IMAGE = 0x2C
}
