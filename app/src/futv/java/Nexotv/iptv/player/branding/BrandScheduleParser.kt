package Nexotv.iptv.player.branding

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.FutvArtworkCatalog
import android.util.Log
import org.jsoup.Jsoup
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Parses the daily schedule text published on FUTV's public homepage. */
internal object BrandScheduleParser {
    const val CHANNEL_ID = "futv"
    private const val DAY_MS = 24L * 60L * 60L * 1_000L
    private val CR_ZONE = TimeZone.getTimeZone("GMT-06:00")
    private val header = Regex(
        """Programaci[oó]n\s+(?:\p{L}+\s+)?(\d{1,2})\s+de\s+(\p{L}+)""",
        RegexOption.IGNORE_CASE,
    )
    private val dateOnly = Regex(
        """^(?:\p{L}+,?\s+)?(\d{1,2})\s+de\s+(\p{L}+)(?:\s+de\s+\d{4})?$""",
        RegexOption.IGNORE_CASE,
    )
    private val timeOnly = Regex("""^(\d{1,2}):(\d{2})$""")
    private val timeAndTitle = Regex("""^(\d{1,2}):(\d{2})\s*[/\-–]?\s*(\S.*)$""")
    private val separator = Regex("""^[/\-–|]+$""")
    private val weekHeader = Regex(
        """^(?:(lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)\s+)?(\d{1,2})\s+de\s+(\p{L}+)(?:\s+de\s+(\d{4}))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val flags = Regex("""\s*-?\s*\((?:EN VIVO|ESTRENO)\)\s*$""", RegexOption.IGNORE_CASE)
    private val months = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
        "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val matchTitle = Regex("""^(JUEGO|PREVIA|POST|F[ÚU]TBOL EN 60|LIGA|FUTSAL|U20)\b""", RegexOption.IGNORE_CASE)
    private val weekdayNumbers = mapOf(
        "lunes" to Calendar.MONDAY,
        "martes" to Calendar.TUESDAY,
        "miércoles" to Calendar.WEDNESDAY,
        "miercoles" to Calendar.WEDNESDAY,
        "jueves" to Calendar.THURSDAY,
        "viernes" to Calendar.FRIDAY,
        "sábado" to Calendar.SATURDAY,
        "sabado" to Calendar.SATURDAY,
        "domingo" to Calendar.SUNDAY,
    )

    /** Completa imágenes del catálogo también en entradas que vienen de la caché anterior. */
    fun withArtwork(program: TvProgram): TvProgram =
        if (program.iconUrl.isNotBlank()) program
        else program.copy(iconUrl = FutvArtworkCatalog.imageFor(program.title))

    fun parse(html: String, nowMs: Long): List<TvProgram> {
        if (html.isBlank()) return emptyList()
        val texts = Jsoup.parse(html).body().allElements
            .map { it.ownText().trim() }
            .filter(String::isNotEmpty)
        var headerIndex = -1
        var day = 0
        var month = 0
        for (i in texts.indices) {
            val combined = header.find(texts[i])
            val parts = combined ?: run {
                val date = dateOnly.find(texts[i]) ?: return@run null
                val near = (maxOf(0, i - 2) until i)
                    .any { texts[it].contains("Programaci", ignoreCase = true) }
                if (near) date else null
            } ?: continue
            val m = months.indexOf(parts.groupValues[2].lowercase(Locale.ROOT)) + 1
            if (m == 0) continue
            headerIndex = i
            day = parts.groupValues[1].toInt()
            month = m
            break
        }
        if (headerIndex < 0) return emptyList()
        val date = nearestDate(day, month, nowMs) ?: return emptyList()

        val slots = mutableListOf<Pair<Long, Pair<String, Boolean>>>()
        var previousStart: Long? = null
        var dayShift = 0L
        var index = headerIndex + 1
        loop@ while (index < texts.size) {
            val t = texts[index]
            if (separator.matches(t)) { index++; continue }
            val hour: Int
            val minute: Int
            val rawTitle: String
            val only = timeOnly.matchEntire(t)
            val both = timeAndTitle.matchEntire(t)
            if (only != null) {
                var j = index + 1
                while (j < texts.size && separator.matches(texts[j])) j++
                if (j >= texts.size || timeOnly.matches(texts[j]) || timeAndTitle.matches(texts[j])) break@loop
                hour = only.groupValues[1].toInt(); minute = only.groupValues[2].toInt()
                rawTitle = texts[j]
                index = j + 1
            } else if (both != null) {
                hour = both.groupValues[1].toInt(); minute = both.groupValues[2].toInt()
                rawTitle = both.groupValues[3]
                index++
            } else break@loop
            if (hour !in 0..23 || minute !in 0..59) continue
            var start = date.timeAt(hour, minute) + dayShift * DAY_MS
            if (previousStart != null && start < previousStart) {
                if (hour >= 6) continue
                dayShift++
                start += DAY_MS
            }
            if (start == previousStart) continue
            previousStart = start
            slots += start to (rawTitle to rawTitle.contains("EN VIVO", ignoreCase = true))
        }

        return slots.mapIndexedNotNull { i, (start, details) ->
            val nextStart = slots.getOrNull(i + 1)?.first ?: (date.timeAt(0, 0) + (dayShift + 1) * DAY_MS)
            if (nextStart <= start) null else createProgram(details.first, start, nextStart)
        }.distinctAndSorted()
    }

    fun parseWeek(html: String, nowMs: Long): List<TvProgram> {
        fun reject(reason: String): List<TvProgram> {
            runCatching { Log.w("XmlTvGuide", "FUTV weekly table rejected: $reason") }
            return emptyList()
        }

        if (html.isBlank()) return reject("HTML vacío")
        val rows = Jsoup.parse(html).select("table#positions-table tr")
        if (rows.size != 49) return reject("se esperaban 49 filas y se encontraron ${rows.size}")

        val rowCells = rows.map { row ->
            row.children().filter { it.tagName().equals("td", ignoreCase = true) || it.tagName().equals("th", ignoreCase = true) }
        }
        val headerCells = rowCells.firstOrNull().orEmpty()
        if (headerCells.size != 8) return reject("encabezado con ${headerCells.size} celdas; se esperaban 8")
        if (!headerCells[0].text().trim().equals("Hora", ignoreCase = true)) {
            return reject("la primera celda del encabezado no es Hora")
        }

        data class HeaderDate(val weekdayText: String?, val day: Int, val month: Int, val year: Int?)
        val headerDates = headerCells.drop(1).mapIndexed { index, cell ->
            val text = cell.text().trim()
            val match = weekHeader.matchEntire(text)
                ?: return reject("fecha no reconocida en columna ${index + 1}: '$text'")
            val monthNumber = months.indexOf(match.groupValues[3].lowercase(Locale.ROOT)) + 1
            if (monthNumber == 0) return reject("mes desconocido en columna ${index + 1}: '$text'")
            HeaderDate(
                weekdayText = match.groupValues[1].takeIf(String::isNotBlank),
                day = match.groupValues[2].toInt(),
                month = monthNumber,
                year = match.groupValues[4].toIntOrNull(),
            )
        }

        val first = headerDates.first()
        val firstDate = if (first.year == null) {
            nearestDate(first.day, first.month, nowMs)
        } else {
            Calendar.getInstance(CR_ZONE, Locale.ROOT).apply {
                clear()
                set(first.year, first.month - 1, first.day, 0, 0, 0)
            }.takeIf { it.get(Calendar.DAY_OF_MONTH) == first.day }
        } ?: return reject("no se pudo resolver el año de la primera fecha")
        firstDate.set(Calendar.HOUR_OF_DAY, 0)
        firstDate.set(Calendar.MINUTE, 0)
        firstDate.set(Calendar.SECOND, 0)
        firstDate.set(Calendar.MILLISECOND, 0)

        val dates = (0 until 7).map { offset ->
            (firstDate.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
        }
        headerDates.forEachIndexed { index, headerDate ->
            val expected = dates[index]
            if (expected.get(Calendar.DAY_OF_MONTH) != headerDate.day ||
                expected.get(Calendar.MONTH) + 1 != headerDate.month ||
                (headerDate.year != null && expected.get(Calendar.YEAR) != headerDate.year)
            ) {
                return reject("las fechas no son consecutivas en la columna ${index + 1}")
            }
            val expectedWeekday = headerDate.weekdayText
                ?.lowercase(Locale.ROOT)
                ?.let(weekdayNumbers::get)
            if (headerDate.weekdayText != null && expectedWeekday == null) {
                return reject("día de semana desconocido en la columna ${index + 1}")
            }
            if (expectedWeekday != null && expected.get(Calendar.DAY_OF_WEEK) != expectedWeekday) {
                return reject("día de semana no coincide en la columna ${index + 1}")
            }
        }

        val events = mutableListOf<TvProgram>()
        val remaining = IntArray(7)
        val programCoverage = IntArray(7)
        val expectedTime = Regex("""^(\d{1,2}):(\d{2})$""")
        val slotsPerDay = 48
        val slotDurationMs = 30L * 60L * 1_000L

        for (rowIndex in 0 until slotsPerDay) {
            val cells = rowCells[rowIndex + 1]
            if (cells.isEmpty()) return reject("fila ${rowIndex + 1} sin celda de hora")
            val timeText = cells[0].text().trim()
            val timeMatch = expectedTime.matchEntire(timeText)
                ?: return reject("hora inválida en fila ${rowIndex + 1}: '$timeText'")
            val hour = timeMatch.groupValues[1].toInt()
            val minute = timeMatch.groupValues[2].toInt()
            val minuteOfDay = hour * 60 + minute
            if (hour !in 0..23 || minute !in 0..59 || minuteOfDay != rowIndex * 30) {
                return reject("secuencia horaria inesperada en fila ${rowIndex + 1}: '$timeText'")
            }

            var nextCellIndex = 1
            for (column in 0 until 7) {
                if (remaining[column] > 0) {
                    remaining[column]--
                    continue
                }
                if (nextCellIndex >= cells.size) {
                    return reject("faltan celdas en fila ${rowIndex + 1}, columna ${column + 1}")
                }
                val cell = cells[nextCellIndex++]
                val title = cell.text().trim()
                val rowSpan = cell.attr("rowspan").toIntOrNull() ?: 1
                if (rowSpan < 1) return reject("rowspan inválido en fila ${rowIndex + 1}")
                if (cell.attr("colspan").toIntOrNull()?.let { it != 1 } == true) {
                    return reject("colspan no soportado en fila ${rowIndex + 1}")
                }
                val durationSlots = minOf(rowSpan, slotsPerDay - rowIndex)
                remaining[column] = durationSlots - 1
                programCoverage[column] += durationSlots
                if (title.isNotEmpty()) {
                    val start = dates[column].timeAt(hour, minute)
                    val end = start + durationSlots * slotDurationMs
                    createProgram(title, start, end)?.let(events::add)
                }
            }
            if (nextCellIndex != cells.size) {
                return reject("sobran ${cells.size - nextCellIndex} celdas en fila ${rowIndex + 1}")
            }
        }

        programCoverage.forEachIndexed { column, coveredSlots ->
            if (coveredSlots != slotsPerDay) {
                return reject("columna ${column + 1} cubre $coveredSlots de $slotsPerDay franjas")
            }
        }
        return events.distinctAndSorted()
    }

    private fun createProgram(rawTitle: String, startMs: Long, endMs: Long): TvProgram? {
        if (endMs <= startMs) return null
        val title = rawTitle.replace(flags, "").trim()
        if (title.isEmpty()) return null
        val category = if (matchTitle.containsMatchIn(title)) "Partido" else "Programa"
        val live = if (rawTitle.contains("EN VIVO", ignoreCase = true)) " · En vivo" else ""
        return TvProgram(
            channelId = CHANNEL_ID,
            title = title,
            description = "Programación publicada por FUTV$live.",
            startMs = startMs,
            endMs = endMs,
            category = category,
            rating = "",
            iconUrl = FutvArtworkCatalog.imageFor(title),
        )
    }

    private fun nearestDate(day: Int, month: Int, nowMs: Long): Calendar? {
        val today = Calendar.getInstance(CR_ZONE, Locale.ROOT).apply { timeInMillis = nowMs }
        return (-1..1).mapNotNull { offset ->
            (today.clone() as Calendar).apply {
                add(Calendar.YEAR, offset)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, 1)
                if (day !in 1..getActualMaximum(Calendar.DAY_OF_MONTH)) return@mapNotNull null
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }.minByOrNull { kotlin.math.abs(it.timeInMillis - nowMs) }
    }

    private fun Calendar.timeAt(hour: Int, minute: Int): Long =
        (clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
