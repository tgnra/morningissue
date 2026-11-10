package net.tgnra.morningissue.data.db

import androidx.room3.ColumnTypeConverter
import java.time.Instant
import java.time.LocalDate

/**
 * Time is stored as integers: [Instant] as epoch milliseconds, [LocalDate] as epoch day.
 * Enums are stored by name (Room's built-in handling).
 */
class Converters {
    @ColumnTypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @ColumnTypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @ColumnTypeConverter
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @ColumnTypeConverter
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
}
