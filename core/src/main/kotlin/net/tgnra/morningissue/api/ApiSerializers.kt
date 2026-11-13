package net.tgnra.morningissue.api

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An instant on the wire: ISO-8601 in UTC (`2026-11-11T06:30:00Z`). Reads fractions of a
 * second and offsets too, as `docs/kobo-sync.md` asks of every reader.
 */
object IsoInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("net.tgnra.morningissue.api.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: throw SerializationException("Not an ISO-8601 instant: $text")
    }
}

/** A date on the wire: `yyyy-MM-dd`. */
object IsoDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("net.tgnra.morningissue.api.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate =
        runCatching { LocalDate.parse(decoder.decodeString()) }.getOrElse { throw SerializationException(it.message) }
}

/** A local time of day on the wire: `HH:mm`. Reads `HH:mm:ss` too. */
object LocalTimeSerializer : KSerializer<LocalTime> {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("net.tgnra.morningissue.api.LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.format(FORMAT))

    override fun deserialize(decoder: Decoder): LocalTime =
        runCatching { LocalTime.parse(decoder.decodeString()) }.getOrElse { throw SerializationException(it.message) }
}
