package net.tgnra.morningissue.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A field of a PATCH body that tells "leave it" (the key is absent) from "set it", where the
 * value may be null ("clear it"): `{"muteUntil": null}` clears a mute, `{}` leaves it alone.
 *
 * Declare it with a default of [Absent] and `@EncodeDefault(EncodeDefault.Mode.NEVER)`, so that
 * an absent value is not written (with `@file:UseSerializers(IsoInstantSerializer::class)`):
 *
 * ```
 * @EncodeDefault(EncodeDefault.Mode.NEVER)
 * val muteUntil: Patch<Instant?> = Patch.Absent
 * ```
 */
@Serializable(with = PatchSerializer::class)
sealed interface Patch<out T> {
    /** The key was absent: leave the stored value as it is. */
    data object Absent : Patch<Nothing>

    /** The key was present: store [value], null included. */
    data class Set<out T>(val value: T) : Patch<T>

    companion object {
        fun <T> of(value: T): Patch<T> = Set(value)
    }
}

/** The patched value if set, else [current]. */
fun <T> Patch<T>.orElse(current: T): T = if (this is Patch.Set) value else current

/** Reads a present key, null included, as [Patch.Set]; an absent key never reaches it and keeps the default. */
class PatchSerializer<T>(private val value: KSerializer<T>) : KSerializer<Patch<T>> {
    override val descriptor: SerialDescriptor = value.descriptor

    override fun serialize(encoder: Encoder, value: Patch<T>) {
        when (value) {
            is Patch.Set -> encoder.encodeSerializableValue(this.value, value.value)
            // Only reached when the property is not marked EncodeDefault.NEVER; null is the safest reading.
            Patch.Absent -> encoder.encodeNull()
        }
    }

    override fun deserialize(decoder: Decoder): Patch<T> = Patch.Set(decoder.decodeSerializableValue(value))
}
