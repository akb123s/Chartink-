package chartink.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns timestamp-looking text found on a Chartink page (e.g. "Last updated: 14:31:05") into an
 * instant. Times without a zone are read as IST, because Chartink is an Indian-market site.
 * This is a best-effort reader of what the page displays; it never invents a timestamp.
 */
object TimestampParser {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    data class Parsed(val epochMs: Long, val raw: String, val hasSeconds: Boolean)

    private val TIME = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)(?:\s*([AaPp])\.?[Mm]\.?)?""")
    private val D_DMY = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})(?!\d)""")
    private val D_YMD = Regex("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)""")
    private val D_D_MON_Y = Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s-]+([A-Za-z]{3,9})\.?,?[\s-]+(\d{4})""")
    private val D_MON_D_Y = Regex("""([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})""")
    private val D_D_MON = Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s-]+([A-Za-z]{3,9})\b""")
    private val D_MON_D = Regex("""\b([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?!\d)""")

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun month(name: String): Int? {
        val i = MONTHS.indexOf(name.take(3).lowercase(Locale.ROOT))
        return if (i >= 0) i + 1 else null
    }

    private fun findDate(text: String, today: LocalDate): LocalDate? {
        try {
            D_YMD.find(text)?.let { return LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            D_DMY.find(text)?.let { return LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
            D_D_MON_Y.find(text)?.let { m ->
                month(m.groupValues[2])?.let { return LocalDate.of(m.groupValues[3].toInt(), it, m.groupValues[1].toInt()) }
            }
            D_MON_D_Y.find(text)?.let { m ->
                month(m.groupValues[1])?.let { return LocalDate.of(m.groupValues[3].toInt(), it, m.groupValues[2].toInt()) }
            }
            val noYear: LocalDate? =
                D_D_MON.find(text)?.let { m -> month(m.groupValues[2])?.let { LocalDate.of(today.year, it, m.groupValues[1].toInt()) } }
                    ?: D_MON_D.find(text)?.let { m -> month(m.groupValues[1])?.let { LocalDate.of(today.year, it, m.groupValues[2].toInt()) } }
            if (noYear != null) return if (noYear.isAfter(today.plusDays(1))) noYear.minusYears(1) else noYear
        } catch (_: Exception) {
            // invalid calendar date such as 31 Feb
        }
        return null
    }

    private fun parseIso(text: String): Long? {
        val t = text.trim()
        if (!Regex("""^\d{4}-\d{2}-\d{2}T""").containsMatchIn(t)) return null
        return try { OffsetDateTime.parse(t, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) {
            try { Instant.parse(t).toEpochMilli() } catch (_: Exception) {
                try { LocalDateTime.parse(t).atZone(IST).toInstant().toEpochMilli() } catch (_: Exception) { null }
            }
        }
    }

    /** Returns the epoch millis for one candidate string, or null when no usable time is in it. */
    fun parseOne(text: String, nowMs: Long): Pair<Long, Boolean>? {
        parseIso(text)?.let { return it to true }
        val now = Instant.ofEpochMilli(nowMs).atZone(IST)
        val tm = TIME.find(text) ?: return null
        var h = tm.groupValues[1].toInt()
        val mi = tm.groupValues[2].toInt()
        val hasSeconds = tm.groupValues[3].isNotEmpty()
        val s = if (hasSeconds) tm.groupValues[3].toInt() else 0
        val ap = tm.groupValues[4]
        if (ap.isNotEmpty()) {
            if (h !in 1..12) return null
            h = h % 12 + if (ap.equals("p", ignoreCase = true)) 12 else 0
        }
        if (h > 23 || mi > 59 || s > 59) return null
        val rest = text.removeRange(tm.range)
        val date = findDate(rest, now.toLocalDate())
        var ts: ZonedDateTime = (date ?: now.toLocalDate()).atTime(h, mi, s).atZone(IST)
        if (date == null && ts.isAfter(now.plusMinutes(5))) ts = ts.minusDays(1) // "15:30" read at 09:00 next day
        if (ts.isAfter(now.plusMinutes(10))) return null // future timestamps are not credible
        return ts.toInstant().toEpochMilli() to hasSeconds
    }

    /** Newest usable timestamp among the strings found on the page. */
    fun newest(candidates: List<String>, nowMs: Long): Parsed? {
        var best: Parsed? = null
        for (c in candidates) {
            val (ms, sec) = parseOne(c, nowMs) ?: continue
            if (best == null || ms > best.epochMs) best = Parsed(ms, c.trim().take(80), sec)
        }
        return best
    }

    /** Short label such as "14:31:05" or "20 Nov 15:30" (date shown when not today). */
    fun label(p: Parsed, nowMs: Long): String {
        val ts = Instant.ofEpochMilli(p.epochMs).atZone(IST)
        val today = Instant.ofEpochMilli(nowMs).atZone(IST).toLocalDate()
        val time = ts.format(DateTimeFormatter.ofPattern(if (p.hasSeconds) "HH:mm:ss" else "HH:mm"))
        return if (ts.toLocalDate() == today) time
        else ts.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " " + time
    }
}
