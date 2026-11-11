// Official schedule parser for the Teletica product flavor.
package Nexotv.iptv.player.branding
import Nexotv.iptv.player.BuildConfig

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import org.json.JSONException
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal object BrandScheduleParser {
    const val CHANNEL_ID = "Canal.7.de.Costa.Rica.(Teletica).cr"

    private const val DEFAULT_CATEGORY = "General"
    private const val DEFAULT_DESCRIPTION = "Transmisión oficial de Teletica Canal 7 · Costa Rica."
    private const val WINDOW_DAYS = 7
    private const val DAY_MS = 24L * 60L * 60L * 1_000L

    private const val KEY_DAYS = "r"
    private const val KEY_DAY_NAME = "d"
    private const val KEY_SLOTS = "s"
    private const val KEY_TITLE = "n"
    private const val KEY_START = "h"
    private const val KEY_END = "f"
    private const val KEY_ICON = "i"
    private const val KEY_NODE_SLUG = "nodeSlug"

    private val COSTA_RICA_ZONE: TimeZone = TimeZone.getTimeZone("GMT-06:00")
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")

    private class ClockTime(val hour: Int, val minute: Int)

    private class ScheduleSlot(
        val start: ClockTime,
        val end: ClockTime,
        val title: String,
        val category: String,
        val iconUrl: String,
        val description: String,
    )

    private class DescriptionRule(val keywords: List<String>, val text: String)

    private val DESCRIPTION_RULES = listOf(
        DescriptionRule(
            listOf("telenoticias"),
            "Edición informativa de Telenoticias por Teletica Canal 7 con noticias nacionales e internacionales, sucesos y reportes en vivo.",
        ),
        DescriptionRule(
            listOf("buen día", "buen dia"),
            "Revista matutina de Teletica con consejos prácticos de salud, bienestar, cocina, entrevistas y actualidad.",
        ),
        DescriptionRule(
            listOf("boca en boca"),
            "Programa de entretenimiento y farándula con lo último del espectáculo nacional e internacional y entrevistas exclusivas.",
        ),
        DescriptionRule(
            listOf("buena tarde"),
            "Revista vespertina de entretenimiento con concursos en vivo, humor, música e invitados especiales.",
        ),
        DescriptionRule(
            listOf("7 días", "7 dias"),
            "Espacio de investigación periodística y análisis a fondo de la coyuntura nacional e internacional.",
        ),
        DescriptionRule(
            listOf("+qn", "más que noticias", "mas que noticias"),
            "Historias positivas, emprendimientos y tradiciones costarricenses que inspiran.",
        ),
        DescriptionRule(
            listOf("calle 7"),
            "Informativo de comunidad y actualidad que refleja la realidad y el sentir de los costarricenses.",
        ),
        DescriptionRule(
            listOf("100 latinos"),
            "Programa de concursos y preguntas de ingenio para disfrutar en familia.",
        ),
        DescriptionRule(
            listOf("sábado feliz", "sabado feliz"),
            "El tradicional programa sabatino de entretenimiento familiar con juegos y premios en directo.",
        ),
        DescriptionRule(
            listOf("millonario"),
            "El programa de preguntas y conocimiento general donde los participantes compiten por el gran premio millonario.",
        ),
        DescriptionRule(
            listOf("mira quién baila", "mira quien baila"),
            "Gran formato de baile y espectáculo con personalidades queridas de Costa Rica.",
        ),
        DescriptionRule(
            listOf("deporte +", "deporte+"),
            "Resumen deportivo con los goles, jugadas y análisis del fútbol nacional e internacional.",
        ),
        DescriptionRule(
            listOf("cine", "película", "pelicula", "cineteca"),
            "Función estelar de cine seleccionada para la pantalla de Teletica Canal 7.",
        ),
    )

    private val CATEGORY_DESCRIPTIONS = mapOf(
        "noticias" to "Espacio periodístico y de actualidad en vivo por Teletica Canal 7.",
        "deportes" to "Transmisión y cobertura deportiva por Teletica Canal 7.",
        "entretenimiento" to "Programa de entretenimiento y variedad por Teletica Canal 7.",
    )

    /** Mantiene una interfaz común con la variante FUTV al cargar la guía en caché. */
    fun withArtwork(program: TvProgram): TvProgram = program

    fun parse(rawJson: String, nowMs: Long): List<TvProgram> {
        if (BuildConfig.BRAND_KEY != "teletica" || rawJson.trimStart().firstOrNull() != '{') return emptyList()
        val root = try {
            JSONObject(rawJson)
        } catch (_: JSONException) {
            return emptyList()
        }

        val slotsByDay = collectSlotsByDay(root)
        if (slotsByDay.isNullOrEmpty()) return emptyList()

        val programs = ArrayList<TvProgram>()
        for (offset in -WINDOW_DAYS..WINDOW_DAYS) {
            val day = Calendar.getInstance(COSTA_RICA_ZONE, Locale.ROOT).apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val slots = dayKeyOf(day)?.let { slotsByDay[it] } ?: continue
            for (slot in slots) {
                toProgramOrNull(slot, day)?.let { programs.add(it) }
            }
        }
        return programs.distinctAndSorted()
    }

    /** FUTV-only HTML schedule entry point; Teletica uses its official JSON API instead. */
    fun parseWeek(html: String, nowMs: Long): List<TvProgram> = emptyList()

    private fun collectSlotsByDay(root: JSONObject): Map<String, List<ScheduleSlot>>? {
        val days = root.optJSONArray(KEY_DAYS) ?: return null
        val result = LinkedHashMap<String, MutableList<ScheduleSlot>>()
        for (i in 0 until days.length()) {
            val day = days.optJSONObject(i) ?: continue
            val dayKey = normalizeDayName(day.readString(KEY_DAY_NAME))
            if (dayKey.isEmpty()) continue
            val slotsArray = day.optJSONArray(KEY_SLOTS) ?: continue
            val bucket = result.getOrPut(dayKey) { mutableListOf() }
            for (j in 0 until slotsArray.length()) {
                val slot = slotsArray.optJSONObject(j)?.let { parseSlot(it) } ?: continue
                bucket.add(slot)
            }
        }
        return result
    }

    private fun parseSlot(slot: JSONObject): ScheduleSlot? {
        val title = slot.readString(KEY_TITLE)
        if (title.isEmpty()) return null
        val start = parseClock(slot.readString(KEY_START)) ?: return null
        val end = parseClock(slot.readString(KEY_END)) ?: return null

        val category = buildCategory(slot.readString(KEY_NODE_SLUG))

        return ScheduleSlot(
            start = start,
            end = end,
            title = title,
            category = category,
            iconUrl = slot.readString(KEY_ICON),
            description = describeProgram(title, category),
        )
    }

    private fun buildCategory(nodeSlug: String): String {
        val rawCategory = nodeSlug
            .replace('/', ' ')
            .replace('-', ' ')
            .trim()
            .replace(WHITESPACE, " ")
            .ifEmpty { DEFAULT_CATEGORY }
        return rawCategory.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }

    private fun parseClock(raw: String): ClockTime? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val parts = value.split(':')
        if (parts.size < 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        return when {
            hour in 0..23 -> ClockTime(hour, minute)
            // "24:00" se interpreta como medianoche del día siguiente
            hour == 24 && minute == 0 -> ClockTime(0, 0)
            else -> null
        }
    }

    private fun toProgramOrNull(slot: ScheduleSlot, day: Calendar): TvProgram? {
        return try {
            val startMs = day.timeAt(slot.start)
            var endMs = day.timeAt(slot.end)
            // Las emisiones que cruzan la medianoche terminan al día siguiente
            if (endMs <= startMs) endMs += DAY_MS
            TvProgram(
                channelId = CHANNEL_ID,
                title = slot.title,
                description = slot.description,
                startMs = startMs,
                endMs = endMs,
                category = slot.category,
                rating = "",
                iconUrl = slot.iconUrl,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun Calendar.timeAt(clock: ClockTime): Long {
        val copy = clone() as Calendar
        copy.set(Calendar.HOUR_OF_DAY, clock.hour)
        copy.set(Calendar.MINUTE, clock.minute)
        copy.set(Calendar.SECOND, 0)
        copy.set(Calendar.MILLISECOND, 0)
        return copy.timeInMillis
    }

    private fun dayKeyOf(calendar: Calendar): String? =
        when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "domingo"
            Calendar.MONDAY -> "lunes"
            Calendar.TUESDAY -> "martes"
            Calendar.WEDNESDAY -> "miercoles"
            Calendar.THURSDAY -> "jueves"
            Calendar.FRIDAY -> "viernes"
            Calendar.SATURDAY -> "sabado"
            else -> null
        }

    private fun normalizeDayName(name: String): String =
        name.lowercase(Locale.ROOT).stripAccents().trim()

    private fun describeProgram(title: String, category: String): String {
        val lowered = title.lowercase(Locale.ROOT)
        val unaccented = lowered.stripAccents()
        DESCRIPTION_RULES.firstOrNull { rule ->
            rule.keywords.any { lowered.contains(it) || unaccented.contains(it) }
        }?.let { return it.text }
        return CATEGORY_DESCRIPTIONS[category.lowercase(Locale.ROOT)] ?: DEFAULT_DESCRIPTION
    }

    private fun String.stripAccents(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

    // optString devuelve el texto "null" para valores JSON nulos; se trata como vacío
    private fun JSONObject.readString(key: String): String =
        if (isNull(key)) "" else optString(key).trim()
}
