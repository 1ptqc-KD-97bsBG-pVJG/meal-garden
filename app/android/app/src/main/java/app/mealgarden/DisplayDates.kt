package app.mealgarden

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date-only records keep their calendar day; timestamps use the household's timezone. */
fun humanDate(value: String, zone: ZoneId = householdZone, today: LocalDate = LocalDate.now(zone)): String {
    val text = value.trim()
    val date = runCatching { Instant.parse(text).atZone(zone).toLocalDate() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).atZone(zone).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(text) }.getOrNull()
        ?: return "Date unknown"
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "MMM d" else "MMM d, uuuu", Locale.US))
    }
}

/** Only the displayed copy changes; original dated evidence remains intact. */
fun humanDatesInText(value: String, zone: ZoneId = householdZone): String =
    Regex("\\b\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?)?").replace(value) {
        humanDate(it.value, zone)
    }
