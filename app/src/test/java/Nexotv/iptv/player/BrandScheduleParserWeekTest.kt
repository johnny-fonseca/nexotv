package Nexotv.iptv.player
import Nexotv.iptv.player.branding.BrandScheduleParser

import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandScheduleParserWeekTest {
    private val costaRica = TimeZone.getTimeZone("GMT-06:00")

    @Test
    fun parsesAllSevenDaysAndCoversTwentyFourHoursPerDay() {
        val html = resource("futv-programacion.html")
        val programs = BrandScheduleParser.parseWeek(html, fixedNow())

        if (BuildConfig.BRAND_KEY != "futv") {
            assertTrue(programs.isEmpty())
            return
        }

        assertEquals(7, programs.map(::dateKey).distinct().size)
        assertEquals(7 * 24L * 60L * 60L * 1_000L, programs.sumOf(TvProgram::durationMs))

        val mondayFirst = programs.minByOrNull(TvProgram::startMs)
        assertNotNull(mondayFirst)
        assertEquals("JUEGO: ALAJUELENSE-P. ZELEDÓN", mondayFirst!!.title)
        assertEquals(timestamp(2026, Calendar.OCTOBER, 26, 0, 0), mondayFirst.startMs)
        assertEquals(timestamp(2026, Calendar.OCTOBER, 26, 2, 0), mondayFirst.endMs)

        val countsByDay = programs.groupingBy(::dateKey).eachCount().toSortedMap()
        val durationByDay = programs.groupBy(::dateKey).mapValues { (_, dayPrograms) ->
            dayPrograms.sumOf(TvProgram::durationMs)
        }.toSortedMap()
        assertEquals(7, durationByDay.size)
        durationByDay.values.forEach { assertEquals(24L * 60L * 60L * 1_000L, it) }
        println("FUTV weekly total=${programs.size}; programsByDay=$countsByDay; durationByDayMs=$durationByDay")
    }

    @Test
    fun rejectsTableWithOneHeaderColumnMissing() {
        val document = Jsoup.parse(resource("futv-programacion.html"))
        val headerRow = document.selectFirst("table#positions-table tr")
        assertNotNull(headerRow)
        headerRow!!.children().last()?.remove()

        assertTrue(BrandScheduleParser.parseWeek(document.outerHtml(), fixedNow()).isEmpty())
    }

    @Test
    fun acceptsWeekWithAnEmptyCellInAColumn() {
        val document = Jsoup.parse(resource("futv-programacion.html"))
        val firstScheduleRow = document.select("table#positions-table tr")[1]
        val emptyProgramCell = firstScheduleRow.children()[1]
        emptyProgramCell.text("")

        val programs = BrandScheduleParser.parseWeek(document.outerHtml(), fixedNow())
        assertEquals(BuildConfig.BRAND_KEY == "futv", programs.isNotEmpty())
    }

    @Test
    fun capturedHomepageStillParsesAsDailyGuide() {
        val programs = BrandScheduleParser.parse(resource("futv-homepage.html"), fixedNow())
        assertEquals(BuildConfig.BRAND_KEY == "futv", programs.isNotEmpty())
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Falta recurso de prueba: $name" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun fixedNow(): Long = timestamp(2026, Calendar.OCTOBER, 26, 12, 0)

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(costaRica, Locale.ROOT).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private fun dateKey(program: TvProgram): String = Calendar.getInstance(costaRica, Locale.ROOT)
        .apply { timeInMillis = program.startMs }
        .let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.DAY_OF_YEAR)}" }
}
