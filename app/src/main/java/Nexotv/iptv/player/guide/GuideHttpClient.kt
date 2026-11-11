// app/src/main/java/Nexotv/iptv/player/guide/GuideHttpClient.kt
package Nexotv.iptv.player.guide
import Nexotv.iptv.player.activity.*

import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.activity.TV_USER_AGENT
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.zip.GZIPInputStream

internal object GuideHttpClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val TOTAL_TIMEOUT_MS = 180_000L
    private const val BUFFER_SIZE = 8 * 1024
    private const val MAX_TEXT_BYTES = 16 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val MAX_URL_LENGTH = 8 * 1024
    private const val MAX_ACCEPT_LENGTH = 256
    private const val BYTE_ORDER_MARK = "\uFEFF"
    private const val HEADER_LOCATION = "Location"
    private const val HEADER_USER_AGENT = "User-Agent"
    private const val HEADER_ACCEPT = "Accept"
    private const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
    private const val HEADER_CONTENT_ENCODING = "Content-Encoding"
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val SIZE_EXCEEDED_MESSAGE = "La respuesta supera el tamaño máximo permitido"
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    private val CONTROL_CHARS = Regex("[\\p{Cntrl}\\u0085\\u2028\\u2029]+")

    fun <T> request(url: String, accept: String? = null, consume: (InputStream) -> T): T {
        val deadlineNanos = System.nanoTime() + TOTAL_TIMEOUT_MS * NANOS_PER_MILLI
        val visited = HashSet<String>()
        var currentUrl = url
        var redirects = 0
        while (true) {
            ensureNotExpired(deadlineNanos)
            if (!visited.add(currentUrl.trim())) {
                throw IOException("Bucle de redirecciones detectado")
            }
            val connection = openConnection(currentUrl)
            try {
                configure(connection, accept)
                val status = connection.responseCode
                if (status in REDIRECT_STATUSES) {
                    val location = connection.getHeaderField(HEADER_LOCATION)
                    if (!location.isNullOrBlank()) {
                        if (redirects >= MAX_REDIRECTS) {
                            throw IOException("Demasiadas redirecciones (máximo $MAX_REDIRECTS)")
                        }
                        currentUrl = resolveRedirect(connection.url, location)
                        redirects++
                        continue
                    }
                }
                check(status in 200..299) { "HTTP $status" }
                val gzip = isGzip(connection)
                if (!gzip && connection.contentLengthLong > MAX_TEXT_BYTES) {
                    throw IOException(SIZE_EXCEEDED_MESSAGE)
                }
                val responseStream = connection.inputStream
                    ?: throw IOException("La respuesta no contiene cuerpo")
                val decodedStream = if (gzip) {
                    try {
                        GZIPInputStream(responseStream)
                    } catch (error: IOException) {
                        runCatching { responseStream.close() }
                        throw error
                    }
                } else {
                    responseStream
                }
                return LimitedInputStream(decodedStream, MAX_TEXT_BYTES.toLong(), deadlineNanos).use(consume)
            } finally {
                closeErrorStream(connection)
                runCatching { connection.disconnect() }
            }
        }
    }

    fun readText(input: InputStream, maxBytes: Int = MAX_TEXT_BYTES): String {
        require(maxBytes > 0) { "El tamaño máximo debe ser mayor que cero" }
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Lectura interrumpida")
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw IOException(SIZE_EXCEEDED_MESSAGE)
            output.write(buffer, 0, read)
        }
        return String(output.toByteArray(), Charsets.UTF_8).removePrefix(BYTE_ORDER_MARK)
    }

    private fun ensureNotExpired(deadlineNanos: Long) {
        if (System.nanoTime() - deadlineNanos >= 0L) {
            throw IOException("Tiempo total de la solicitud excedido")
        }
    }

    private fun isGzip(connection: HttpURLConnection): Boolean =
        connection.getHeaderField(HEADER_CONTENT_ENCODING)
            ?.split(',')
            ?.any { it.trim().equals("gzip", ignoreCase = true) } == true

    private fun configure(connection: HttpURLConnection, accept: String?) {
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty(HEADER_USER_AGENT, TV_USER_AGENT)
        connection.setRequestProperty(HEADER_ACCEPT_ENCODING, "gzip")
        val safeAccept = accept.orEmpty().replace(CONTROL_CHARS, " ").trim().take(MAX_ACCEPT_LENGTH)
        if (safeAccept.isNotEmpty()) connection.setRequestProperty(HEADER_ACCEPT, safeAccept)
    }

    private class LimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
        private val deadlineNanos: Long,
    ) : FilterInputStream(input) {
        private var bytesRead = 0L

        private fun guard() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Lectura interrumpida")
            ensureNotExpired(deadlineNanos)
        }

        private fun account(count: Long) {
            bytesRead += count
            if (bytesRead > maxBytes) throw IOException(SIZE_EXCEEDED_MESSAGE)
        }

        override fun read(): Int {
            guard()
            val value = super.read()
            if (value >= 0) account(1L)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            guard()
            val count = super.read(buffer, offset, length)
            if (count > 0) account(count.toLong())
            return count
        }

        override fun skip(n: Long): Long {
            if (n <= 0L) return 0L
            guard()
            val skipped = super.skip(n)
            if (skipped > 0L) account(skipped)
            return skipped
        }

        override fun markSupported(): Boolean = false

        override fun mark(readlimit: Int) {
        }

        override fun reset() {
            throw IOException("mark/reset no soportado")
        }
    }

    // Permite redirecciones http -> https y bloquea la degradación https -> http
    private fun resolveRedirect(base: URL, location: String): String {
        val trimmedLocation = location.trim()
        if (trimmedLocation.length > MAX_URL_LENGTH) {
            throw IOException("Redirección inválida: URL demasiado larga")
        }
        val target = try {
            URL(base, trimmedLocation)
        } catch (error: MalformedURLException) {
            throw IOException("Redirección inválida: ${trimmedLocation.take(MAX_ACCEPT_LENGTH)}", error)
        }
        val downgrade = base.protocol.equals("https", ignoreCase = true) &&
                target.protocol.equals("http", ignoreCase = true)
        if (downgrade) throw IOException("Redirección insegura de HTTPS a HTTP rechazada")
        return target.toString()
    }

    private fun closeErrorStream(connection: HttpURLConnection) {
        try {
            connection.errorStream?.close()
        } catch (_: Exception) {
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) throw MalformedURLException("La URL está vacía")
        if (trimmed.length > MAX_URL_LENGTH) throw MalformedURLException("La URL es demasiado larga")
        val target = URL(trimmed)
        val protocol = target.protocol.lowercase()
        if (protocol != "http" && protocol != "https") {
            throw MalformedURLException("Protocolo no soportado: ${target.protocol}")
        }
        if (target.host.isNullOrEmpty()) {
            throw MalformedURLException("La URL no contiene un host válido")
        }
        return target.openConnection() as? HttpURLConnection
            ?: throw IOException("La conexión no es HTTP")
    }
}