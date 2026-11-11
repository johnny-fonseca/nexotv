// XmlTvParser.kt
@file:Suppress("PackageName")

package Nexotv.iptv.player.guide
import Nexotv.iptv.player.branding.BrandScheduleParser


import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.guide.GuideHttpClient

import android.util.Xml
import org.tukaani.xz.XZInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

internal object XmlTvParser {
    private const val SNIFF_BYTES = 64
    private const val XZ_MEMORY_LIMIT_KIB = 131_072
    private const val MAX_DECOMPRESSED_XML_BYTES = 64L * 1024L * 1024L
    private const val MAX_PROGRAMS = 100_000
    private const val MAX_FIELD_CHARS = 64 * 1024
    private const val MAX_CHANNEL_ID_CHARS = 1_024
    private const val MAX_TIMESTAMP_CHARS = 40
    private const val MAX_URL_CHARS = 8_192
    private const val MAX_CHANNEL_MEMO_ENTRIES = 16_384
    private const val MAX_FORMAT_CACHE_ENTRIES = 64
    private const val MAX_ZONE_HOURS = 23
    private const val MAX_ZONE_MINUTES = 59
    private const val MIN_VALID_EPOCH_MS = 0L
    private const val MAX_VALID_EPOCH_MS = 4_102_444_800_000L

    private const val TAG_PROGRAMME = "programme"
    private const val TAG_TITLE = "title"
    private const val TAG_DESC = "desc"
    private const val TAG_CATEGORY = "category"
    private const val TAG_ICON = "icon"
    private const val TAG_RATING = "rating"
    private const val TAG_VALUE = "value"

    private const val ATTR_CHANNEL = "channel"
    private const val ATTR_START = "start"
    private const val ATTR_STOP = "stop"
    private const val ATTR_SRC = "src"

    private const val PATTERN_SECONDS = "yyyyMMddHHmmss"
    private const val PATTERN_MINUTES = "yyyyMMddHHmm"
    private const val TIMESTAMP_WITH_SECONDS_LENGTH = 14

    private const val MSG_EMPTY_DOCUMENT = "Documento XMLTV vacío"
    private const val MSG_MALFORMED_DOCUMENT = "Documento XMLTV malformado"
    private const val MSG_TRUNCATED_PROGRAMME = "Documento XMLTV truncado dentro de un programa"
    private const val MSG_TRUNCATED_ELEMENT = "Documento XMLTV truncado dentro de un elemento"
    private const val MSG_TRUNCATED_RATING = "Documento XMLTV truncado dentro de una clasificación"
    private const val MSG_TOO_MANY_PROGRAMS = "La guía supera el máximo de programas permitido"
    private const val MSG_FIELD_TOO_LARGE = "Un campo de la guía supera el tamaño máximo permitido"
    private const val MSG_DECOMPRESSED_TOO_LARGE = "La guía descomprimida supera el tamaño máximo permitido"
    private const val MSG_MARK_UNSUPPORTED = "mark/reset no soportado"

    private val GZIP_MAGIC = byteArrayOf(0x1f, 0x8b.toByte())
    private val XZ_MAGIC = byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00)
    private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private val JSON_OPENERS = setOf('{'.code.toByte(), '['.code.toByte())
    private val JSON_WHITESPACE = setOf(' '.code.toByte(), '\t'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

    internal fun isConfiguredChannelId(id: String, markers: List<String>): Boolean {
        if (id.isEmpty()) return false
        val normalized = id.lowercase(Locale.ROOT)
        return markers.any { rawMarker ->
            val marker = rawMarker.trim().lowercase(Locale.ROOT)
            when {
                marker.isBlank() -> false
                marker.all { it.isDigit() } -> normalized.containsNumericToken(marker)
                else -> normalized.contains(marker)
            }
        }
    }

    private fun String.containsNumericToken(token: String): Boolean {
        if (token.isEmpty()) return false
        var start = indexOf(token)
        while (start >= 0) {
            val end = start + token.length
            val leftIsPartOfId = start > 0 && this[start - 1].isLetterOrDigit()
            val rightIsPartOfId = end < length && this[end].isLetterOrDigit()
            if (!leftIsPartOfId && !rightIsPartOfId) return true
            start = indexOf(token, start + 1)
        }
        return false
    }

    private val CHANNEL_MARKERS = BuildConfig.XMLTV_CHANNEL_MARKERS
        .split(',')
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter(String::isNotBlank)
        .distinct()

    private val XMLTV_TIMESTAMP =
        Regex("""(\d{12}|\d{14})(?:\s*(Z|UTC|GMT|[+-]\d{2}:?\d{2}))?""", RegexOption.IGNORE_CASE)

    private val URL_SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*):""")

    private val formatCache = object : ThreadLocal<HashMap<String, SimpleDateFormat>>() {
        override fun initialValue(): HashMap<String, SimpleDateFormat> = HashMap()
    }

    fun parse(input: InputStream): List<TvProgram> =
        BufferedInputStream(input).use { buffered ->
            buffered.mark(SNIFF_BYTES)
            val header = ByteArray(SNIFF_BYTES)
            val headerSize = readUpTo(buffered, header)
            buffered.reset()
            when {
                headerSize == 0 -> throw XmlPullParserException(MSG_EMPTY_DOCUMENT)
                looksLikeJson(header, headerSize) ->
                    BrandScheduleParser.parse(
                        GuideHttpClient.readText(
                            BufferedInputStream(LimitedInputStream(buffered, MAX_DECOMPRESSED_XML_BYTES))
                        ),
                        System.currentTimeMillis()
                    )
                header.startsWith(GZIP_MAGIC, headerSize) ->
                    GZIPInputStream(buffered).use { parseXml(LimitedInputStream(it, MAX_DECOMPRESSED_XML_BYTES)) }
                header.startsWith(XZ_MAGIC, headerSize) ->
                    XZInputStream(buffered, XZ_MEMORY_LIMIT_KIB).use { parseXml(LimitedInputStream(it, MAX_DECOMPRESSED_XML_BYTES)) }
                else -> parseXml(LimitedInputStream(buffered, MAX_DECOMPRESSED_XML_BYTES))
            }
        }

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: XmlPullParserException) {
            throw e
        } catch (e: RuntimeException) {
            throw XmlPullParserException(MSG_MALFORMED_DOCUMENT, null, e)
        }

    private fun XmlPullParser.trySetFeature(name: String, enabled: Boolean) {
        try {
            setFeature(name, enabled)
        } catch (ignored: XmlPullParserException) {
            // Feature not supported by this parser implementation.
        }
    }

    private fun parseXml(stream: InputStream): List<TvProgram> = guarded {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            trySetFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(stream, null)
        }
        val programsByKey = LinkedHashMap<ProgramKey, TvProgram>()
        val channelMemo = HashMap<String, Boolean>()
        var acceptedPrograms = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == TAG_PROGRAMME) {
                readProgram(parser, channelMemo)?.let { program ->
                    if (acceptedPrograms >= MAX_PROGRAMS) throw XmlPullParserException(MSG_TOO_MANY_PROGRAMS)
                    acceptedPrograms++
                    val key = ProgramKey(program.channelId, program.startMs, program.title)
                    if (!programsByKey.containsKey(key)) programsByKey[key] = program
                }
            }
            event = parser.next()
        }
        programsByKey.values.sortedWith(compareBy<TvProgram> { it.startMs }.thenBy { it.endMs })
    }

    private fun readProgram(parser: XmlPullParser, channelMemo: MutableMap<String, Boolean>): TvProgram? {
        val channel = parser.getAttributeValue(null, ATTR_CHANNEL).orEmpty()

        if (channel.length > MAX_CHANNEL_ID_CHARS || !isConfiguredChannel(channel, channelMemo)) {
            skipElement(parser)
            return null
        }

        val start = parseTime(parser.getAttributeValue(null, ATTR_START))
        val end = parseTime(parser.getAttributeValue(null, ATTR_STOP))

        if (start == null || end == null || end <= start) {
            skipElement(parser)
            return null
        }

        var title = ""
        var description = ""
        var category = ""
        var rating = ""
        var iconUrl = ""

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == TAG_PROGRAMME)) {
            if (event == XmlPullParser.END_DOCUMENT) {
                throw XmlPullParserException(MSG_TRUNCATED_PROGRAMME)
            }
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    TAG_TITLE -> if (title.isEmpty()) title = readElementText(parser) else skipElement(parser)
                    TAG_DESC -> if (description.isEmpty()) description = readElementText(parser) else skipElement(parser)
                    TAG_CATEGORY -> if (category.isEmpty()) category = readElementText(parser) else skipElement(parser)
                    TAG_ICON -> {
                        if (iconUrl.isEmpty()) iconUrl = sanitizeUrl(parser.getAttributeValue(null, ATTR_SRC))
                        skipElement(parser)
                    }
                    TAG_RATING -> {
                        val value = readRating(parser)
                        if (value.isNotEmpty()) rating = value
                    }
                    else -> skipElement(parser)
                }
            }
            event = parser.next()
        }

        if (title.isBlank()) return null
        return TvProgram(channel, title, description, start, end, category, rating, iconUrl)
    }

    private fun readElementText(parser: XmlPullParser): String {
        val builder = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> if (depth == 1) {
                    val chunk = parser.text.orEmpty()
                    if (builder.length > MAX_FIELD_CHARS - chunk.length) {
                        throw XmlPullParserException(MSG_FIELD_TOO_LARGE)
                    }
                    builder.append(chunk)
                }
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException(MSG_TRUNCATED_ELEMENT)
            }
        }
        return builder.toString().trim()
    }

    private fun readRating(parser: XmlPullParser): String {
        var value = ""
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG ->
                    if (parser.name == TAG_VALUE) {
                        val text = readElementText(parser)
                        if (text.isNotEmpty()) value = text
                    } else {
                        depth++
                    }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException(MSG_TRUNCATED_RATING)
            }
        }
        return value
    }

    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException(MSG_TRUNCATED_PROGRAMME)
            }
        }
    }

    private fun isConfiguredChannel(id: String, memo: MutableMap<String, Boolean>): Boolean {
        memo[id]?.let { return it }
        val result = isConfiguredChannelId(id, CHANNEL_MARKERS)
        if (memo.size >= MAX_CHANNEL_MEMO_ENTRIES) memo.clear()
        memo[id] = result
        return result
    }

    private fun sanitizeUrl(raw: String?): String {
        val value = raw.orEmpty().trim()
        if (value.isEmpty() || value.length > MAX_URL_CHARS) return ""
        if (value.any { it.isISOControl() }) return ""
        val scheme = URL_SCHEME.find(value)?.groupValues?.get(1) ?: return value
        return if (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) value else ""
    }

    private fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        if (trimmed.length > MAX_TIMESTAMP_CHARS) return null
        val match = XMLTV_TIMESTAMP.matchEntire(trimmed) ?: return null
        val timestamp = match.groupValues[1]
        val zone = match.groupValues[2]

        if (!isValidOffset(zone)) return null

        val pattern = if (timestamp.length == TIMESTAMP_WITH_SECONDS_LENGTH) PATTERN_SECONDS else PATTERN_MINUTES
        val position = ParsePosition(0)
        val parsed = try {
            formatFor(pattern, resolveZoneId(zone)).parse(timestamp, position)
        } catch (e: RuntimeException) {
            null
        }
        if (parsed == null || position.index != timestamp.length) return null
        val epochMs = parsed.time
        return if (epochMs in MIN_VALID_EPOCH_MS..MAX_VALID_EPOCH_MS) epochMs else null
    }

    private fun isValidOffset(zone: String): Boolean {
        if (!zone.startsWith('+') && !zone.startsWith('-')) return true
        val digits = zone.substring(1).replace(":", "")
        val hours = digits.take(2).toIntOrNull() ?: return false
        val minutes = digits.drop(2).ifEmpty { "0" }.toIntOrNull() ?: return false
        return hours <= MAX_ZONE_HOURS && minutes <= MAX_ZONE_MINUTES
    }

    private fun resolveZoneId(zone: String): String = when {
        zone.isEmpty() -> TimeZone.getDefault().id
        zone.equals("Z", ignoreCase = true) ||
                zone.equals("UTC", ignoreCase = true) ||
                zone.equals("GMT", ignoreCase = true) -> "GMT"
        else -> "GMT" + zone.replace(":", "")
    }

    private fun formatFor(pattern: String, zoneId: String): SimpleDateFormat {
        val cache = formatCache.get() ?: HashMap<String, SimpleDateFormat>().also(formatCache::set)
        val key = "$pattern|$zoneId"
        cache[key]?.let { return it }
        if (cache.size >= MAX_FORMAT_CACHE_ENTRIES) cache.clear()
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone(zoneId)
        }
        cache[key] = format
        return format
    }

    private fun readUpTo(input: InputStream, target: ByteArray): Int {
        var count = 0
        while (count < target.size) {
            val read = input.read(target, count, target.size - count)
            if (read <= 0) break
            count += read
        }
        return count
    }

    private fun ByteArray.startsWith(prefix: ByteArray, available: Int): Boolean {
        return available >= prefix.size && size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }

    private class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
        private var bytesRead = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) charge(1L)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) charge(count.toLong())
            return count
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0L) charge(skipped)
            return skipped
        }

        override fun markSupported(): Boolean = false

        override fun mark(readlimit: Int) = Unit

        override fun reset() {
            throw IOException(MSG_MARK_UNSUPPORTED)
        }

        private fun charge(amount: Long) {
            bytesRead += amount
            if (bytesRead > maxBytes) throw IOException(MSG_DECOMPRESSED_TOO_LARGE)
        }
    }

    private fun looksLikeJson(header: ByteArray, size: Int): Boolean {
        var index = if (header.startsWith(UTF8_BOM, size)) UTF8_BOM.size else 0
        while (index < size && header[index] in JSON_WHITESPACE) index++
        return index < size && header[index] in JSON_OPENERS
    }

    private data class ProgramKey(val channelId: String, val startMs: Long, val title: String)
}