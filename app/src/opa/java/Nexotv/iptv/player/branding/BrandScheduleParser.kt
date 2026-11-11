package Nexotv.iptv.player.branding

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal object BrandScheduleParser {
    const val CHANNEL_ID = "opa"
    private const val DEFAULT_DURATION_MINUTES = 60
    private const val MAX_DURATION_MINUTES = 12 * 60
    private const val MINUTES_PER_DAY = 24 * 60
    private val costaRica = TimeZone.getTimeZone("GMT-06:00")
    private val weekdayPattern = Regex(
        """\b(lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val timePattern = Regex(
        """(\d{1,2})\s*:\s*(\d{2})\s*([ap])\s*\.?\s*m\.?""",
        RegexOption.IGNORE_CASE,
    )
    private val rangeSeparator = Regex("""^\s*(?:a|hasta|[-–])\s*$""", RegexOption.IGNORE_CASE)
    private val weekdayNumbers = mapOf(
        "lunes" to Calendar.MONDAY,
        "martes" to Calendar.TUESDAY,
        "miercoles" to Calendar.WEDNESDAY,
        "jueves" to Calendar.THURSDAY,
        "viernes" to Calendar.FRIDAY,
        "sabado" to Calendar.SATURDAY,
        "domingo" to Calendar.SUNDAY,
    )

    fun withArtwork(program: TvProgram): TvProgram = program

    @Suppress("UNUSED_PARAMETER")
    fun parse(rawJson: String, nowMs: Long): List<TvProgram> = emptyList()

    fun parseWeek(html: String, nowMs: Long): List<TvProgram> {
        if (html.isBlank()) return emptyList()

        val today = Calendar.getInstance(costaRica, Locale.ROOT).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val programs = Jsoup.parse(html, "https://genteopa.com/programas/").select(".show-title").flatMap { show ->
            val paragraphs = show.select("p").map { it.text().trim() }.filter(String::isNotEmpty)
            val title = paragraphs.firstOrNull()
                ?: show.selectFirst("h2, h3, h4")?.text()?.trim().orEmpty()
            val schedule = paragraphs.drop(1).firstOrNull(::containsSchedule).orEmpty()
            parseShow(title, schedule, showArtwork(show), today)
        }
        return programs.distinctAndSorted()
    }

    private fun showArtwork(show: Element): String {
        val image = show.nextElementSibling()
            ?.takeIf { it.hasClass("shows-slider") }
            ?.selectFirst(".opa-cell img[src]")
            ?: return ""
        val artworkUrl = image.absUrl("src")
        return artworkUrl.takeIf { it.startsWith("https://genteopa.com/wp-content/uploads/", ignoreCase = true) }.orEmpty()
    }

    private fun containsSchedule(text: String): Boolean =
        weekdayPattern.containsMatchIn(text) && timePattern.containsMatchIn(text)

    private fun parseShow(title: String, schedule: String, iconUrl: String, today: Calendar): List<TvProgram> {
        if (title.isBlank() || schedule.isBlank()) return emptyList()
        val firstTime = timePattern.find(schedule) ?: return emptyList()
        val dayMatches = weekdayPattern.findAll(schedule.substring(0, firstTime.range.first)).toList()
        if (dayMatches.isEmpty()) return emptyList()

        val weekdays = dayMatches.mapNotNull { weekdayNumber(it.value) }
        if (weekdays.size != dayMatches.size) return emptyList()
        val selectedDays = if (
            weekdays.size == 2 &&
            rangeSeparator.matches(schedule.substring(dayMatches[0].range.last + 1, dayMatches[1].range.first))
        ) {
            daysInRange(weekdays[0], weekdays[1])
        } else {
            weekdays.toSet()
        }
        if (selectedDays.isEmpty()) return emptyList()

        val timeMatches = timePattern.findAll(schedule).take(2).toList()
        val startMinute = timeMatches.firstOrNull()?.let(::minutesSinceMidnight) ?: return emptyList()
        var endMinute = DEFAULT_DURATION_MINUTES + startMinute
        if (timeMatches.size == 2 &&
            rangeSeparator.containsMatchIn(schedule.substring(timeMatches[0].range.last + 1, timeMatches[1].range.first))
        ) {
            endMinute = minutesSinceMidnight(timeMatches[1]) ?: return emptyList()
            if (endMinute <= startMinute) endMinute += MINUTES_PER_DAY
        }
        val durationMinutes = endMinute - startMinute
        if (durationMinutes !in 1..MAX_DURATION_MINUTES) return emptyList()

        return selectedDays.map { weekday ->
            val offset = (weekday - today.get(Calendar.DAY_OF_WEEK) + 7) % 7
            val start = (today.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, startMinute / 60)
                set(Calendar.MINUTE, startMinute % 60)
            }.timeInMillis
            TvProgram(
                channelId = CHANNEL_ID,
                title = title,
                description = "Programación semanal publicada por OPA: $schedule",
                startMs = start,
                endMs = start + durationMinutes * 60_000L,
                category = "Programa",
                rating = "",
                iconUrl = iconUrl,
            )
        }
    }

    private fun weekdayNumber(raw: String): Int? {
        val normalized = raw.lowercase(Locale.ROOT)
            .replace('á', 'a')
            .replace('é', 'e')
            .replace('í', 'i')
            .replace('ó', 'o')
            .replace('ú', 'u')
        return weekdayNumbers[normalized]
    }

    private fun daysInRange(start: Int, end: Int): Set<Int> {
        val result = linkedSetOf<Int>()
        var day = start
        repeat(7) {
            result += day
            if (day == end) return result
            day = if (day == Calendar.SATURDAY) Calendar.SUNDAY else day + 1
        }
        return emptySet()
    }

    private fun minutesSinceMidnight(match: MatchResult): Int? {
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 1..12 || minute !in 0..59) return null
        val isPm = match.groupValues[3].equals("p", ignoreCase = true)
        return ((hour % 12) + if (isPm) 12 else 0) * 60 + minute
    }
}
