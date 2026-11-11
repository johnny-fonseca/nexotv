@file:UnstableApi
@file:Suppress("PackageNaming", "PackageDirectoryMismatch", "PackageName", "SetTextI18n", "ConstantConditions", "ConstantConditionIf", "SimplifyBooleanWithConstants", "KotlinConstantConditions", "SimplifyBoolean")

package Nexotv.iptv.player.settings




import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.diagnostics.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import Nexotv.iptv.player.guide.*
import android.app.AlertDialog
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import org.json.JSONObject

private const val MAX_USER_AGENT_LENGTH = 512
private const val PROBE_TIMEOUT_MS = 8_000
private const val PROBE_MAX_MANIFEST_BYTES = 1_048_576

private data class HlsProbeResponse(val finalUrl: String, val body: String)

// ---------- User-Agent ----------

internal fun MainActivity.currentUserAgent(): String =
    if (usesBuiltInStream()) TV_USER_AGENT
    else appPreferences()
        .safeString(MainActivity.KEY_USER_AGENT, null)?.trim()?.takeIf { it.isNotEmpty() }
        ?: TV_USER_AGENT

internal fun MainActivity.showUserAgentEditor() {
    val prefs = appPreferences()
    val builtIn = usesBuiltInStream()
    val input = EditText(this).apply {
        isSingleLine = true
        hint = TV_USER_AGENT
        setText(if (builtIn) "" else prefs.safeString(MainActivity.KEY_USER_AGENT, ""))
    }
    styleTvInput(input)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(TextView(this@showUserAgentEditor).apply {
            text = if (builtIn) {
                "La señal ${BuildConfig.BRAND_NAME} usa el User-Agent Android TV predeterminado. Los valores personalizados no se aplican."
            } else {
                "Vacío usa el User-Agent Android TV predeterminado. Se aplica al reiniciar la reproducción."
            }
            setTextColor(getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(12))
        })
        addView(input, LinearLayout.LayoutParams(-1, -2))
    }
    AlertDialog.Builder(this)
        .setView(createStyledFormPanel("User-Agent del stream", "REPRODUCCIÓN", content))
        .setNegativeButton("Cancelar", null)
        .setNeutralButton("Predeterminado") { _, _ ->
            prefs.edit { remove(MainActivity.KEY_USER_AGENT) }
            rebuildPlayer("User-Agent predeterminado aplicado")
        }
        .setPositiveButton("Guardar") { _, _ ->
            val value = input.text.toString().filterNot { it.isISOControl() }.trim()
                .take(MAX_USER_AGENT_LENGTH)
            if (builtIn || value.isEmpty()) prefs.edit { remove(MainActivity.KEY_USER_AGENT) }
            else prefs.edit { putString(MainActivity.KEY_USER_AGENT, value) }
            rebuildPlayer("User-Agent actualizado")
        }.showForTv()
}

// ---------- Fuentes de guía y señal ----------

@UnstableApi
@Suppress("ConstantConditionIf", "SimplifyBooleanWithConstants")
internal fun MainActivity.showEpgSourceEditor() {
    val prefs = appPreferences()
    val input = EditText(this).apply {
        isSingleLine = true
        hint = XmlTvGuide.DEFAULT_URL
        setText(prefs.safeString(MainActivity.KEY_EPG_URL, XmlTvGuide.DEFAULT_URL))
    }
    styleTvInput(input)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(TextView(this@showEpgSourceEditor).apply {
            text = if (BuildConfig.BRAND_KEY == "futv") {
                "Por defecto se lee la programación diaria publicada en futvcr.com. También puedes ingresar una fuente XMLTV (.xml o .xml.gz). La página no ofrece duración ni descripción por emisión; el fin se estima con el siguiente horario."
            } else if (BuildConfig.BRAND_KEY == "opa") {
                "Por defecto se lee el horario semanal publicado en genteopa.com. También puedes ingresar una fuente XMLTV (.xml o .xml.gz)."
            } else if (XmlTvGuide.DEFAULT_URL.isBlank()) {
                "No hay una programación predefinida para esta variante. Ingresa una fuente XMLTV (.xml o .xml.gz)."
            } else {
                val defaultHost = XmlTvGuide.DEFAULT_URL.toUri().host
                "Por defecto se utiliza la programación oficial en vivo de $defaultHost. También puedes ingresar una URL XMLTV (.xml o .xml.gz)."
            }
            setTextColor(getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(12))
        })
        addView(input, LinearLayout.LayoutParams(-1, -2))
    }
    AlertDialog.Builder(this)
        .setView(createStyledFormPanel("Fuente de la guía de programación", "GUÍA", content))
        .setNegativeButton("Cancelar", null)
        .setNeutralButton("Predeterminada") { _, _ ->
            prefs.edit { putString(MainActivity.KEY_EPG_URL, XmlTvGuide.DEFAULT_URL) }
            refreshGuide()
        }
        .setPositiveButton("Guardar") { _, _ ->
            val url = input.text.toString().trim()
            if (isValidHttpUrl(url)) {
                prefs.edit { putString(MainActivity.KEY_EPG_URL, url) }
                refreshGuide()
            } else {
                showTvToast("Ingresa una URL HTTP o HTTPS", long = true)
            }
        }.showForTv()
}

private fun MainActivity.describeProbeFailure(failure: Exception, headersConfigured: Boolean): String {
    if (failure is CleartextHeadersOnHttpException) return getString(R.string.source_http_headers_not_tested)
    cleartextErrorMessageResId(failure, headersConfigured)?.let { return getString(it) }
    tlsErrorMessageResId(failure)?.let { return getString(it) }
    return when (failure) {
    is IllegalStateException -> failure.message ?: "La fuente no pasó la comprobación."
    is IllegalArgumentException -> failure.message ?: "Los datos de la fuente no son válidos."
    is SocketTimeoutException -> "El servidor tardó demasiado en responder."
    is UnknownHostException -> "No se pudo resolver el servidor; revisa la dirección y la conexión."
    is SSLException -> "No se pudo establecer una conexión segura con el servidor."
    is MalformedURLException -> "La dirección del manifiesto o de un segmento no es válida."
    is IOException -> "No se pudo conectar con el servidor (${failure.javaClass.simpleName})."
    else -> "No se pudo comprobar el enlace (${failure.javaClass.simpleName})."
    }
}

internal fun MainActivity.showStreamSourceEditor() {
    val prefs = appPreferences()
    lateinit var sourceDialog: AlertDialog
    lateinit var testButton: Button
    val testGeneration = AtomicInteger(0)
    val input = EditText(this).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        hint = DEFAULT_STREAM_URL
        setText(prefs.safeString(MainActivity.KEY_STREAM_URL, DEFAULT_STREAM_URL))
    }
    styleTvInput(input)
    val sourceHeadersInput = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 2
        maxLines = 4
        hint = "Authorization: Bearer …\nReferer: https://…"
        setText(
            currentStreamHeaders().entries.joinToString("\n") { (name, value) -> "$name: $value" },
        )
    }
    styleTvInput(sourceHeadersInput, 92)
    val testStatus = TextView(this).apply {
        setTextColor(getColor(R.color.tv_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(8), 0, 0)
        visibility = View.GONE
    }
    testButton = Button(this).apply {
        text = "Probar fuente"
        minHeight = dp(52)
        setOnClickListener {
            val url = input.text.toString().trim()
            if (!isValidHttpUrl(url)) {
                input.error = "Ingresa una URL HTTP o HTTPS válida"
                input.requestFocus()
                return@setOnClickListener
            }
            val headers = try {
                parseStreamHeaders(sourceHeadersInput.text.toString())
            } catch (failure: IllegalArgumentException) {
                sourceHeadersInput.error = failure.message
                sourceHeadersInput.requestFocus()
                return@setOnClickListener
            }
            if (isCleartextUrl(url) && headers.isNotEmpty()) {
                testStatus.visibility = View.VISIBLE
                testStatus.text = getString(R.string.source_http_headers_not_tested)
                return@setOnClickListener
            }
            isEnabled = false
            testStatus.visibility = View.VISIBLE
            testStatus.setTextColor(getColor(R.color.tv_text_secondary))
            testStatus.text = "Probando manifiesto HLS y primer segmento…"
            val useBuiltInDefaults = url == DEFAULT_STREAM_URL
            val userAgent = if (useBuiltInDefaults) TV_USER_AGENT else currentUserAgent()
            val requestHeaders = if (useBuiltInDefaults) emptyMap() else headers
            val requestGeneration = testGeneration.incrementAndGet()
            Thread({
                val result = try {
                    probeHlsSource(url, userAgent, requestHeaders)
                } catch (failure: Exception) {
                    describeProbeFailure(failure, requestHeaders.isNotEmpty())
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && sourceDialog.isShowing &&
                        requestGeneration == testGeneration.get() &&
                        input.text.toString().trim() == url
                    ) {
                        testStatus.text = result
                        testStatus.setTextColor(
                            getColor(
                                if (result.startsWith("Fuente accesible")) R.color.tv_turquoise
                                else R.color.tv_text_secondary,
                            ),
                        )
                        testButton.isEnabled = true
                    }
                }
            }, "${BuildConfig.BRAND_NAME}-source-check").apply { isDaemon = true }.start()
        }
    }
    val resetProbe = {
        testGeneration.incrementAndGet()
        testStatus.visibility = View.GONE
        testButton.isEnabled = true
    }
    input.addTextChangedListener(simpleTextWatcher(resetProbe))
    sourceHeadersInput.addTextChangedListener(simpleTextWatcher(resetProbe))
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(TextView(this@showStreamSourceEditor).apply {
            text = "Ingresa la URL HTTP o HTTPS de la señal en vivo (por ejemplo, un enlace HLS .m3u8). Se usará para reproducir, abrir en otro reproductor y realizar grabaciones."
            setTextColor(getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(12))
        })
        addView(input, LinearLayout.LayoutParams(-1, -2))
        addView(TextView(this@showStreamSourceEditor).apply {
            text = getString(R.string.stream_http_headers_help, BuildConfig.BRAND_NAME)
            setTextColor(getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(12), 0, dp(4))
        })
        addView(sourceHeadersInput, LinearLayout.LayoutParams(-1, -2))
        addView(testButton, LinearLayout.LayoutParams(-1, -2))
        addView(testStatus, LinearLayout.LayoutParams(-1, -2))
    }
    sourceDialog = AlertDialog.Builder(this)
        .setView(createStyledFormPanel("Fuente de la señal de TV", "REPRODUCCIÓN", content))
        .setNegativeButton("Cancelar", null)
        .setNeutralButton("Predeterminada") { _, _ ->
            prefs.edit(commit = false) {
                remove(MainActivity.KEY_STREAM_URL)
                remove(MainActivity.KEY_STREAM_HEADERS)
                remove(MainActivity.KEY_USER_AGENT)
            }
            rebuildPlayer("Fuente predeterminada restaurada")
            showTvToast("Fuente predeterminada restaurada")
        }
        .setPositiveButton("Guardar", null)
        .showForTv()
    sourceDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
        val url = input.text.toString().trim()
        val headers = try {
            parseStreamHeaders(sourceHeadersInput.text.toString())
        } catch (failure: IllegalArgumentException) {
            sourceHeadersInput.error = failure.message
            sourceHeadersInput.requestFocus()
            return@setOnClickListener
        }
        if (!isValidHttpUrl(url)) {
            input.error = "Ingresa una URL HTTP o HTTPS válida"
            input.requestFocus()
            return@setOnClickListener
        }
        if (isCleartextUrl(url) && headers.isNotEmpty()) {
            sourceHeadersInput.error = getString(R.string.stream_http_headers_suppressed)
            sourceHeadersInput.requestFocus()
            return@setOnClickListener
        }
        val headerJson = JSONObject().apply {
            headers.forEach { (name, value) -> put(name, value) }
        }
        prefs.edit {
            putString(MainActivity.KEY_STREAM_URL, url)
            if (url == DEFAULT_STREAM_URL) remove(MainActivity.KEY_STREAM_HEADERS)
            else putString(MainActivity.KEY_STREAM_HEADERS, headerJson.toString())
        }
        testGeneration.incrementAndGet()
        sourceDialog.dismiss()
        rebuildPlayer("Fuente guardada")
        showTvToast(
            if (isCleartextUrl(url) && headers.isNotEmpty()) {
                getString(R.string.stream_http_headers_suppressed)
            } else {
                "Fuente guardada; conectando señal"
            },
        )
    }
}

private fun probeHlsSource(sourceUrl: String, userAgent: String, headers: Map<String, String>): String {
    fun openConnection(url: String): HttpURLConnection {
        check(isValidHttpUrl(url)) { "La dirección del manifiesto no es una URL HTTP o HTTPS válida." }
        check(!(isCleartextUrl(url) && !isCleartextUrl(sourceUrl))) { "Redirección de HTTPS a HTTP rechazada." }
        if (isCleartextUrl(url) && headers.isNotEmpty()) throw CleartextHeadersOnHttpException()
        val origin = URL(sourceUrl)
        val target = URL(url)
        val sameOrigin = origin.host.equals(target.host, ignoreCase = true) && origin.port == target.port
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            if (sameOrigin) headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
    }

    fun followRedirects(url: String, configure: (HttpURLConnection) -> Unit): HttpURLConnection {
        var currentUrl = url
        var redirects = 0
        while (true) {
            val connection = openConnection(currentUrl)
            configure(connection)
            val code = try {
                connection.responseCode
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
            val location = connection.getHeaderField("Location")
            if (code !in setOf(301, 302, 303, 307, 308) || location.isNullOrBlank()) return connection
            connection.disconnect()
            check(redirects < 20) { "Demasiadas redirecciones al comprobar la fuente." }
            currentUrl = URL(URL(currentUrl), location.trim()).toString()
            redirects++
        }
    }

    fun fetchManifest(url: String): HlsProbeResponse {
        val connection = followRedirects(url) {
            it.setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
        }
        try {
            val code = connection.responseCode
            check(code in 200..299) { "El servidor respondió HTTP $code." }
            val bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var remaining = PROBE_MAX_MANIFEST_BYTES
                while (remaining > 0) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.toByteArray()
            }
            return HlsProbeResponse(connection.url.toString(), bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    fun checkSegment(url: String) {
        val connection = followRedirects(url) { it.setRequestProperty("Range", "bytes=0-0") }
        try {
            val code = connection.responseCode
            check(code in 200..299) { "El manifiesto responde, pero el segmento respondió HTTP $code." }
            check(connection.inputStream.use { it.read() } >= 0) { "El primer segmento HLS está vacío." }
        } finally {
            connection.disconnect()
        }
    }

    fun isHlsManifest(body: String): Boolean =
        body.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            ?.removePrefix("\uFEFF")
            ?.startsWith("#EXTM3U") == true

    var manifest = fetchManifest(sourceUrl)
    check(isHlsManifest(manifest.body)) { "El enlace respondió, pero no contiene un manifiesto HLS." }
    val lines = manifest.body.lineSequence().map(String::trim).toList()
    val variants = lines.indices.mapNotNull { index ->
        val line = lines[index]
        if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapNotNull null
        val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return@mapNotNull null
        val bandwidth = Regex("(?:^|,)BANDWIDTH=(\\d+)")
            .find(line.substringAfter(':'))?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        bandwidth to uri
    }
    if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
        val variant = variants.maxByOrNull { it.first }?.second
            ?: error("La lista maestra HLS no publica una variante reproducible.")
        manifest = fetchManifest(URL(URL(manifest.finalUrl), variant).toString())
        check(isHlsManifest(manifest.body)) { "La variante del manifiesto no respondió con HLS válido." }
    }
    val mediaLines = manifest.body.lineSequence().map(String::trim).toList()
    check(mediaLines.any { it.startsWith("#EXTINF:") }) { "El manifiesto HLS no publica segmentos de video." }
    val segmentUri = mediaLines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: error("El manifiesto HLS no publica una dirección de segmento.")
    checkSegment(URL(URL(manifest.finalUrl), segmentUri).toString())
    return "Fuente accesible: manifiesto HLS y primer segmento responden correctamente."
}

internal fun MainActivity.showGuideSettings() {
    GuideSettingsDialog.show(
        activity = this,
        onRefresh = { silent -> refreshGuide(silent) },
        onRetentionChanged = { loadCachedGuide() },
        onStatus = { message -> showStatus(message) },
        hideStatus = { hideStatusAfterDelay() },
    )
}




