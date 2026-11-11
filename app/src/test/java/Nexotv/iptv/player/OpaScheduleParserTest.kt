package Nexotv.iptv.player
import Nexotv.iptv.player.branding.BrandScheduleParser

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.settings.*
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpaScheduleParserTest {
    private val costaRica = TimeZone.getTimeZone("GMT-06:00")

    @Test
    fun parsesExplicitTimesAndRecurringWeekdayRanges() {
        val html = """
            <div class="show-title">
                <p>A Doble Nudo</p>
                <p>Temas de actualidad. Jueves de 5:00 p.m. a 6:00 p.m.</p>
            </div>
            <section class="opa-slider shows-slider">
                <article class="opa-cell"><img src="https://genteopa.com/wp-content/uploads/2026/10/a-doble-nudo.jpg"></article>
            </section>
            <div class="show-title">
                <p>Está Pasando</p>
                <p>De lunes a viernes, a las 7:00 p. m.</p>
            </div>
            <section class="opa-slider shows-slider">
                <article class="opa-cell"><img src="https://genteopa.com/wp-content/uploads/2026/10/esta-pasando.jpg"></article>
            </section>
            <div class="show-title">
                <p>Alma de Mujer</p>
                <p>Todos los miércoles a las 5 :00 p.m.</p>
            </div>
            <section class="opa-slider shows-slider">
                <article class="opa-cell"><img src="https://genteopa.com/wp-content/uploads/2026/10/alma-de-mujer.jpg"></article>
            </section>
        """.trimIndent()

        val now = timestamp(2026, Calendar.OCTOBER, 27, 12, 0)
        val programs = BrandScheduleParser.parseWeek(html, now)
        if (BuildConfig.BRAND_KEY != "opa") {
            assertTrue(programs.isEmpty())
            return
        }

        val thursday = programs.single { it.title == "A Doble Nudo" }
        assertEquals("opa", thursday.channelId)
        assertEquals(
            "https://genteopa.com/wp-content/uploads/2026/10/a-doble-nudo.jpg",
            thursday.iconUrl,
        )
        assertEquals(timestamp(2026, Calendar.OCTOBER, 29, 17, 0), thursday.startMs)
        assertEquals(timestamp(2026, Calendar.OCTOBER, 29, 18, 0), thursday.endMs)

        val weekdays = programs.filter { it.title == "Está Pasando" }
        assertEquals(5, weekdays.size)
        assertEquals(
            setOf("https://genteopa.com/wp-content/uploads/2026/10/esta-pasando.jpg"),
            weekdays.map(TvProgram::iconUrl).toSet(),
        )
        assertTrue(weekdays.all { it.endMs - it.startMs == 60 * 60_000L })
        assertEquals(
            listOf(
                timestamp(2026, Calendar.OCTOBER, 27, 19, 0),
                timestamp(2026, Calendar.OCTOBER, 28, 19, 0),
                timestamp(2026, Calendar.OCTOBER, 29, 19, 0),
                timestamp(2026, Calendar.OCTOBER, 30, 19, 0),
                timestamp(2026, Calendar.NOVEMBER, 2, 19, 0),
            ),
            weekdays.map(TvProgram::startMs),
        )

        val wednesday = programs.single { it.title == "Alma de Mujer" }
        assertEquals(
            "https://genteopa.com/wp-content/uploads/2026/10/alma-de-mujer.jpg",
            wednesday.iconUrl,
        )
        assertEquals(timestamp(2026, Calendar.OCTOBER, 28, 17, 0), wednesday.startMs)
        assertEquals(timestamp(2026, Calendar.OCTOBER, 28, 18, 0), wednesday.endMs)
    }

    @Test
    fun ignoresUnrecognizedOrUnboundedSchedules() {
        val html = """
            <div class="show-title">
                <p>Sin horario</p>
                <p>Disponible durante toda la semana.</p>
            </div>
            <div class="show-title">
                <p>Horario inválido</p>
                <p>Lunes a las 13:30 p.m.</p>
            </div>
        """.trimIndent()

        assertTrue(BrandScheduleParser.parseWeek(html, timestamp(2026, Calendar.OCTOBER, 27, 12, 0)).isEmpty())
    }

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(costaRica, Locale.ROOT).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
