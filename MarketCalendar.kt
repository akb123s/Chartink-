package chartink.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * NSE equity session schedule in IST: Mon-Fri 09:15-15:30, minus listed holidays, plus optional
 * special sessions that replace a given day's schedule. Data comes from res/raw/market_calendar.txt.
 */
class MarketCalendar(
    private val holidays: Set<LocalDate>,
    private val specials: Map<LocalDate, Session>,
) {
    data class Session(val start: LocalTime, val end: LocalTime)

    fun isOpen(now: ZonedDateTime = ZonedDateTime.now(IST)): Boolean {
        val local = now.withZoneSameInstant(IST)
        val date = local.toLocalDate()
        val time = local.toLocalTime()
        specials[date]?.let { return time >= it.start && time < it.end }
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return false
        if (date in holidays) return false
        return time >= REGULAR_OPEN && time < REGULAR_CLOSE
    }

    fun isOpenAt(epochMs: Long): Boolean =
        isOpen(java.time.Instant.ofEpochMilli(epochMs).atZone(IST))

    companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        val REGULAR_OPEN: LocalTime = LocalTime.of(9, 15)
        val REGULAR_CLOSE: LocalTime = LocalTime.of(15, 30)

        /** Parses the text format documented at the top of market_calendar.txt. Bad lines are skipped. */
        fun parse(text: String): MarketCalendar {
            val holidays = HashSet<LocalDate>()
            val specials = HashMap<LocalDate, Session>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                try {
                    when (parts[0].uppercase()) {
                        "HOLIDAY" -> holidays += LocalDate.parse(parts[1])
                        "SPECIAL" -> specials[LocalDate.parse(parts[1])] =
                            Session(LocalTime.parse(parts[2]), LocalTime.parse(parts[3]))
                    }
                } catch (_: Exception) {
                    // ignore malformed line
                }
            }
            return MarketCalendar(holidays, specials)
        }
    }
}
