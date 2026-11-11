// HlsCapture.kt
package Nexotv.iptv.player.player
import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

internal class UnsupportedHlsFeatureException(message: String) : IOException(message)

internal enum class CaptureTermination {
    COMPLETED,
    STOPPED,
    FAILED,
}

internal object HlsPlaylistSupport {
    private val KEY_METHOD = Regex("(?:^|,)METHOD=([^,]+)")

    fun validate(text: String) {
        text.lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("#EXT-X-BYTERANGE") -> throw UnsupportedHlsFeatureException(
                    "Esta señal usa segmentos por rangos de bytes; ${BuildConfig.BRAND_NAME} todavía no puede grabarla correctamente.",
                )
                line == "#EXT-X-DISCONTINUITY" -> throw UnsupportedHlsFeatureException(
                    "Esta señal cambia de formato durante la emisión; ${BuildConfig.BRAND_NAME} todavía no puede grabarla correctamente.",
                )
                line.startsWith("#EXT-X-PART:") || line.startsWith("#EXT-X-PRELOAD-HINT:") ->
                    throw UnsupportedHlsFeatureException(
                        "Esta señal usa HLS de baja latencia; ${BuildConfig.BRAND_NAME} todavía no puede grabarla correctamente.",
                    )
                line.startsWith("#EXT-X-SKIP:") -> throw UnsupportedHlsFeatureException(
                    "Esta señal usa listas HLS parciales; ${BuildConfig.BRAND_NAME} todavía no puede grabarla correctamente.",
                )
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val method = KEY_METHOD
                        .find(line.substringAfter(':'))?.groupValues?.get(1)?.trim()?.trim('"')?.uppercase()
                    if (method != "NONE") throw UnsupportedHlsFeatureException(
                        "Esta señal HLS está cifrada; ${BuildConfig.BRAND_NAME} no puede grabar emisiones cifradas.",
                    )
                }
            }
        }
    }
}

/** Captura de segmentos HLS: sigue la variante de mayor ancho de banda y añade cada secuencia una sola vez. */
internal class HlsCapture(
    private val source: String,
    private val output: File,
    private val outputFactory: ((isMp4: Boolean, part: Int) -> CaptureOutput)? = null,
    private val onOutput: (CaptureOutput) -> Unit = {},
    private val onDestinationFailure: (String) -> Unit = {},
    private val userAgent: String = TV_USER_AGENT,
    private val onStorageLimit: () -> Unit = {},
    private val onProgress: (CaptureOutput) -> Unit = {},
    private val requestHeaders: Map<String, String> = emptyMap(),
) {
    @Volatile var stopped = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var stopRequested = false

    fun stop() {
        stopRequested = true
        stopped = true
        activeConnection?.disconnect()
    }

    fun run(): CaptureTermination {
        var mediaUrl = source
        var targetDelay: Long
        var captureOutput: CaptureOutput? = null
        var partNumber = 1
        var sequence = -1L
        var wroteInit = false
        var retries = 0
        var termination = CaptureTermination.COMPLETED
        while (!stopped) {
            val cycleStartMs = System.nanoTime() / 1_000_000L
            try {
                var text = fetch(mediaUrl)
                lastFetchedUrl?.let { mediaUrl = it }
                if (text.contains("#EXT-X-STREAM-INF")) {
                    HlsPlaylistSupport.validate(text)
                    mediaUrl = variants(text, mediaUrl).maxByOrNull { (_, bandwidth) -> bandwidth }?.first
                        ?: error("La lista HLS no contiene variantes")
                    text = fetch(mediaUrl)
                    lastFetchedUrl?.let { mediaUrl = it }
                }
                HlsPlaylistSupport.validate(text)
                targetDelay = targetDuration(text).coerceIn(1_000L, 30_000L)
                val map = initSegment(text, mediaUrl)
                val target = captureOutput ?: (outputFactory?.invoke(map != null, partNumber) ?: FileCaptureOutput(
                    File(output.parentFile, output.nameWithoutExtension + if (map == null) ".ts" else ".mp4"),
                )).also { captureOutput = it; onOutput(it) }
                val playlistSegments = segments(text, mediaUrl)
                if (playlistSegments.isEmpty() && !text.lineSequence().any { it.trim() == "#EXT-X-ENDLIST" }) {
                    throw IOException("La lista HLS no contiene segmentos")
                }
                val firstSequence = sequenceStart(text)
                if (sequence < 0) {
                    sequence = firstSequence - 1
                } else if (playlistSegments.isNotEmpty() && (firstSequence + playlistSegments.size - 1 < sequence)) {
                    // El servidor reinició la numeración de secuencias
                    sequence = firstSequence - 1
                }
                if (!wroteInit && map != null) {
                    captureOutput = append(map, target, canRotate = false, null) { partNumber }
                    wroteInit = true
                }
                for ((number, uri) in playlistSegments.filter { it.first > sequence }) {
                    if (stopped) break
                    captureOutput = append(uri, captureOutput ?: target, true, map) { ++partNumber }
                    sequence = number
                }
                retries = 0
                if (text.lineSequence().any { it.trim() == "#EXT-X-ENDLIST" }) break
                val elapsedMs = System.nanoTime() / 1_000_000L - cycleStartMs
                pause((targetDelay - elapsedMs).coerceAtLeast(MIN_CYCLE_PAUSE_MS))
            } catch (failure: Exception) {
                when (failure) {
                    is UnsupportedHlsFeatureException -> throw failure
                    is StorageLimitReached -> {
                        stopped = true
                        termination = CaptureTermination.FAILED
                        onStorageLimit()
                        break
                    }
                    is CaptureDestinationException -> {
                        stopped = true
                        termination = CaptureTermination.FAILED
                        onDestinationFailure(failure.message ?: "No se pudo escribir en el destino")
                        break
                    }
                    is InterruptedException -> {
                        stopped = true
                        termination = if (stopRequested) CaptureTermination.STOPPED else CaptureTermination.FAILED
                        break
                    }
                }
                if (Thread.currentThread().isInterrupted) {
                    stopped = true
                    termination = if (stopRequested) CaptureTermination.STOPPED else CaptureTermination.FAILED
                    break
                }
                if (stopped) break
                if (retries >= MAX_RETRIES) throw failure
                try {
                    pause(RETRY_DELAYS[retries++])
                } catch (_: InterruptedException) {
                    stopped = true
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return if (stopRequested) CaptureTermination.STOPPED else termination
    }

    private fun pause(millis: Long) {
        var remaining = millis
        while (remaining > 0 && !stopped) {
            val slice = minOf(remaining, SLEEP_SLICE_MS)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    @Volatile private var lastFetchedUrl: String? = null

    private fun fetch(url: String): String {
        val connection = request(url)
        lastFetchedUrl = connection.url.toString()
        return try {
            val text = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (buffer.size() + count > MAX_PLAYLIST_BYTES) {
                        throw IOException("La lista HLS excede el tamaño máximo permitido")
                    }
                    buffer.write(chunk, 0, count)
                }
                buffer.toString(Charsets.UTF_8.name())
            }
            if (!text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")) {
                throw IOException("La respuesta no es una lista HLS válida")
            }
            text
        } finally {
            connection.disconnect()
            activeConnection = null
        }
    }

    private fun append(
        url: String,
        initialTarget: CaptureOutput,
        canRotate: Boolean,
        initSegment: String?,
        nextPart: () -> Int,
    ): CaptureOutput {
        val connection = request(url)
        var segment: File? = null
        var target = initialTarget
        try {
            val free = target.freeSpaceBytes()
            val segmentBytes = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (free != null && (free <= MIN_FREE_SPACE_BYTES || (segmentBytes >= 0 && segmentBytes > free - MIN_FREE_SPACE_BYTES))) {
                throw StorageLimitReached()
            }
            if (target.tempDirectory.usableSpace <= MIN_TEMP_SPACE_BYTES + segmentBytes.coerceAtLeast(0L)) {
                throw CaptureDestinationException("No hay espacio temporal suficiente para procesar el segmento")
            }
            val temporary = File.createTempFile("${BuildConfig.BRAND_KEY}-segment-", ".part", target.tempDirectory)
                .also { segment = it }
            connection.inputStream.use { input ->
                FileOutputStream(temporary).buffered().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var bytesSinceSpaceCheck = 0L
                    var bytesSinceTempSpaceCheck = 0L
                    while (true) {
                        if (stopped) throw IOException("Captura detenida")
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytesSinceSpaceCheck += count
                        bytesSinceTempSpaceCheck += count
                        if (bytesSinceSpaceCheck >= FREE_SPACE_CHECK_INTERVAL_BYTES) {
                            if (target.freeSpaceBytes()?.let { it <= MIN_FREE_SPACE_BYTES + FREE_SPACE_CHECK_INTERVAL_BYTES } == true) {
                                throw StorageLimitReached()
                            }
                            bytesSinceSpaceCheck = 0L
                        }
                        if (bytesSinceTempSpaceCheck >= TEMP_SPACE_CHECK_INTERVAL_BYTES) {
                            if (target.tempDirectory.usableSpace <= MIN_TEMP_SPACE_BYTES) {
                                throw CaptureDestinationException("Se agotó el espacio temporal al grabar")
                            }
                            bytesSinceTempSpaceCheck = 0L
                        }
                        out.write(buffer, 0, count)
                    }
                }
            }
            if (target.freeSpaceBytes()?.let { it <= MIN_FREE_SPACE_BYTES + temporary.length() } == true) {
                throw StorageLimitReached()
            }
            if (canRotate && target.bytes > 0L && target.bytes + temporary.length() > MAX_PART_BYTES) {
                val part = nextPart()
                target = outputFactory?.invoke(initSegment != null, part)
                    ?: FileCaptureOutput(
                        File(
                            output.parentFile,
                            "${output.nameWithoutExtension}-part${part.toString().padStart(2, '0')}.${if (initSegment == null) "ts" else "mp4"}",
                        ),
                    )
                onOutput(target)
                if (initSegment != null) append(initSegment, target, false, null) { part }
            }
            target.append(temporary)
            onProgress(target)
            return target
        } catch (failure: CaptureDestinationException) {
            throw failure
        } catch (failure: IOException) {
            val message = failure.message
            if (message?.contains("space", ignoreCase = true) == true || message?.contains("espacio", ignoreCase = true) == true) {
                throw StorageLimitReached()
            }
            throw failure
        } finally {
            segment?.delete()
            connection.disconnect()
            activeConnection = null
        }
    }

    private fun request(url: String): HttpURLConnection {
        var currentUrl = url
        var redirects = 0
        val origin = URL(url)
        while (true) {
            val parsed = URL(currentUrl)
            if (parsed.protocol != "http" && parsed.protocol != "https") {
                throw MalformedURLException("Protocolo no soportado: ${parsed.protocol}")
            }
            if (parsed.protocol == "http" && (requestHeaders.isNotEmpty() || (redirects > 0 && origin.protocol == "https"))) {
                throw CleartextHeadersOnHttpException()
            }
            val connection = parsed.openConnection() as HttpURLConnection
            activeConnection = connection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                if (parsed.host.equals(origin.host, ignoreCase = true) && parsed.port == origin.port) {
                    requestHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                }
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    activeConnection = null
                    if (location.isNullOrBlank() || ++redirects > MAX_REDIRECTS) {
                        error("Demasiadas redirecciones o destino inválido al grabar HLS")
                    }
                    currentUrl = resolve(currentUrl, location.trim())
                    continue
                }
                if (code !in 200..299) {
                    connection.disconnect()
                    activeConnection = null
                    error("HTTP $code al grabar HLS")
                }
                return connection
            } catch (failure: Exception) {
                connection.disconnect()
                activeConnection = null
                throw failure
            }
        }
    }

    private fun resolve(base: String, value: String) = URL(URL(base), value).toString()

    private fun variants(text: String, base: String): List<Pair<String, Long>> {
        var bandwidth = 0L
        return text.lineSequence().map(String::trim).mapNotNull { line ->
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                bandwidth = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                null
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                (resolve(base, line) to bandwidth).also { bandwidth = 0L }
            } else {
                null
            }
        }.toList()
    }

    private fun sequenceStart(text: String) =
        MEDIA_SEQUENCE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun targetDuration(text: String) =
        (TARGET_DURATION.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 6L) * 1_000L

    private fun initSegment(text: String, base: String): String? =
        INIT_MAP.find(text)?.groupValues?.get(1)?.let { resolve(base, it) }

    private fun segments(text: String, base: String): List<Pair<Long, String>> {
        var index = sequenceStart(text)
        return text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { index++ to resolve(base, it) }.toList()
    }

    companion object {
        private const val MIN_CYCLE_PAUSE_MS = 250L
        internal const val MIN_FREE_SPACE_BYTES = 512L * 1024 * 1024
        /** Deja margen bajo el límite de 4 GiB por archivo de FAT32. */
        private const val MAX_PART_BYTES = 3_800_000_000L
        private const val MIN_TEMP_SPACE_BYTES = 64L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 128 * 1024
        private const val FREE_SPACE_CHECK_INTERVAL_BYTES = 1024L * 1024
        private const val TEMP_SPACE_CHECK_INTERVAL_BYTES = 1024L * 1024
        private const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
        private const val MAX_RETRIES = 6
        private const val MAX_REDIRECTS = 5
        private const val SLEEP_SLICE_MS = 250L
        private val RETRY_DELAYS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val BANDWIDTH = Regex("(?<![A-Za-z-])BANDWIDTH=(\\d+)")
        private val MEDIA_SEQUENCE = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)")
        private val TARGET_DURATION = Regex("#EXT-X-TARGETDURATION:(\\d+)")
        private val INIT_MAP = Regex("#EXT-X-MAP:.*?URI=\"([^\"]+)\"")
    }

    private class StorageLimitReached : IOException()
}
