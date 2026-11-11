// RecordingArtwork.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import Nexotv.iptv.player.MainActivity.*

private const val MAX_ARTWORK_DOWNLOAD_BYTES = 2 * 1024 * 1024
private const val MAX_ARTWORK_EDGE = 960
private const val MAX_DISK_CACHE_FILES = 80
private const val ARTWORK_TIMEOUT_MS = 8_000
private const val EPG_FAILURE_COOLDOWN_MS = 5 * 60_000L
private const val JPEG_QUALITY = 88
private const val RECORDING_REQUEST_PREFIX = "rec|"
private const val GUIDE_REQUEST_PREFIX = "guide|"

private val artworkThreadCounter = AtomicInteger(0)

private val recordingArtworkExecutor = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue<Runnable>(8),
    ThreadFactory { runnable ->
        Thread(runnable, "recording-artwork-${artworkThreadCounter.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    },
)

private val artworkMainHandler = Handler(Looper.getMainLooper())
private val diskCacheLock = Any()

private val recordingArtworkCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.allocationByteCount
}

private class ArtworkTarget(view: ImageView, val expectedTag: String) {
    private val reference = WeakReference(view)
    fun view(): ImageView? = reference.get()
}

private val pendingArtworkRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<ArtworkTarget>>()
private val failedEpgArtworkAt = ConcurrentHashMap<String, Long>()

/** Registra un destino; devuelve true si es la primera solicitud y debe iniciarse el trabajo. */
private fun registerArtworkTarget(requestKey: String, target: ArtworkTarget): Boolean {
    val newTargets = CopyOnWriteArrayList<ArtworkTarget>().apply { add(target) }
    val existingTargets = pendingArtworkRequests.putIfAbsent(requestKey, newTargets)
    if (existingTargets == null) return true

    existingTargets.add(target)
    return false
}

private fun completeArtworkRequest(requestKey: String, bitmap: Bitmap?) {
    val targets = pendingArtworkRequests.remove(requestKey) ?: return
    if (bitmap == null) return
    targets.forEach { postArtwork(it, bitmap) }
}

/** Muestra la imagen XMLTV descargada sobre un recurso local, sin bloquear la UI. */
internal fun Activity.loadEpgProgramArtwork(
    imageView: ImageView,
    program: TvProgram?,
    userAgent: String,
    fallbackResource: Int = R.drawable.live_placeholder,
) {
    val imageUrl = program?.iconUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val viewKey = "guide|${program?.startMs}|${program?.endMs}|${imageUrl.orEmpty()}"
    if (imageView.tag == viewKey) return
    imageView.tag = viewKey
    imageView.setImageResource(fallbackResource)
    if (imageUrl == null) return

    val cacheKey = "epg|$imageUrl"
    recordingArtworkCache.get(cacheKey)?.let {
        imageView.setImageBitmap(it)
        return
    }
    val requestKey = GUIDE_REQUEST_PREFIX + cacheKey
    if (!registerArtworkTarget(requestKey, ArtworkTarget(imageView, viewKey))) return

    val appCacheDir = applicationContext.cacheDir
    try {
        recordingArtworkExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                bitmap = obtainEpgBitmap(appCacheDir, imageUrl, userAgent)
            } catch (error: Exception) {
                Log.w("RecordingArtwork", "No se pudo cargar la ilustración de la guía", error)
                bitmap = null
            } finally {
                completeArtworkRequest(requestKey, bitmap)
            }
        }
    } catch (_: Exception) {
        pendingArtworkRequests.remove(requestKey)
    }
}

/** Muestra primero la imagen de la guía, luego un fotograma de la grabación y por último el recurso local. */
internal fun MainActivity.loadRecordingArtwork(
    imageView: ImageView,
    entry: RecordingEntry,
    guideProgram: TvProgram?,
) {
    imageView.setImageResource(R.drawable.recording_placeholder)
    val epgUrl = guideProgram?.iconUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val cacheKey = "${entry.id}|${entry.modified}|${entry.bytes}|${guideProgram?.iconUrl.orEmpty()}"
    imageView.tag = cacheKey

    val cachedInMemory = epgUrl?.let { recordingArtworkCache.get("epg|$it") }
        ?: recordingArtworkCache.get("$cacheKey|frame")
    if (cachedInMemory != null) {
        imageView.setImageBitmap(cachedInMemory)
        return
    }

    val requestKey = RECORDING_REQUEST_PREFIX + cacheKey
    if (!registerArtworkTarget(requestKey, ArtworkTarget(imageView, cacheKey))) return

    val appContext = applicationContext
    val appCacheDir = appContext.cacheDir
    try {
        recordingArtworkExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                bitmap = resolveRecordingArtwork(appContext, entry, guideProgram?.iconUrl, appCacheDir, cacheKey)
            } catch (error: Exception) {
                Log.w("RecordingArtwork", "No se pudo cargar la ilustración de la grabación", error)
                bitmap = null
            } finally {
                completeArtworkRequest(requestKey, bitmap)
            }
        }
    } catch (_: Exception) {
        pendingArtworkRequests.remove(requestKey)
    }
}

private fun postArtwork(target: ArtworkTarget, bitmap: Bitmap) {
    artworkMainHandler.post {
        val view = target.view() ?: return@post
        if (view.tag != target.expectedTag) return@post
        val activity = view.context.findActivity()
        if (activity == null || (!activity.isFinishing && !activity.isDestroyed)) {
            view.setImageBitmap(bitmap)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun obtainEpgBitmap(cacheDir: File, url: String, userAgent: String?): Bitmap? {
    val epgKey = "epg|$url"
    recordingArtworkCache.get(epgKey)?.let { return it }
    loadDiskCachedBitmap(cacheDir, epgKey)?.let {
        recordingArtworkCache.put(epgKey, it)
        return it
    }
    val lastFailure = failedEpgArtworkAt[epgKey] ?: 0L
    if (System.currentTimeMillis() - lastFailure < EPG_FAILURE_COOLDOWN_MS) return null

    val downloaded = downloadRecordingArtwork(url, userAgent)
    if (downloaded == null) {
        failedEpgArtworkAt[epgKey] = System.currentTimeMillis()
        return null
    }
    failedEpgArtworkAt.remove(epgKey)
    recordingArtworkCache.put(epgKey, downloaded)
    saveDiskCachedBitmap(cacheDir, epgKey, downloaded)
    return downloaded
}

private fun resolveRecordingArtwork(
    context: Context,
    entry: RecordingEntry,
    imageUrl: String?,
    cacheDir: File,
    cacheKey: String,
): Bitmap? {
    val safeUrl = imageUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val epgBitmap = safeUrl?.let { obtainEpgBitmap(cacheDir, it, null) }
    if (epgBitmap != null) return epgBitmap

    val frameKey = "$cacheKey|frame"
    recordingArtworkCache.get(frameKey)?.let { return it }
    loadDiskCachedBitmap(cacheDir, frameKey)?.let {
        recordingArtworkCache.put(frameKey, it)
        return it
    }
    val frame = extractRecordingFrame(context, entry) ?: return null
    recordingArtworkCache.put(frameKey, frame)
    saveDiskCachedBitmap(cacheDir, frameKey, frame)
    return frame
}

private fun downloadRecordingArtwork(url: String, userAgent: String? = null): Bitmap? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        connection.connectTimeout = ARTWORK_TIMEOUT_MS
        connection.readTimeout = ARTWORK_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        userAgent?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("User-Agent", it) }
        if (URL(url).host.equals("futvcr.com", ignoreCase = true)) {
            connection.setRequestProperty("Referer", "https://futvcr.com/")
        }
        if (connection.responseCode !in 200..299) return null
        if (connection.contentLength > MAX_ARTWORK_DOWNLOAD_BYTES) return null
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_ARTWORK_DOWNLOAD_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.isEmpty()) null else decodeSampled(bytes)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        try { connection?.disconnect() } catch (_: Exception) { }
    }
}

private fun computeSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while ((width / sample > MAX_ARTWORK_EDGE || height / sample > MAX_ARTWORK_EDGE) && sample < (1 shl 30)) sample *= 2
    return sample
}

private fun decodeSampled(bytes: ByteArray): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun cacheFile(cacheDir: File, key: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val directory = File(cacheDir, "recording-artwork").apply { mkdirs() }
    return File(directory, "$digest.jpg")
}

private fun loadDiskCachedBitmap(cacheDir: File, key: String): Bitmap? {
    return try {
        val file = cacheFile(cacheDir, key)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            return null
        }
        val options = BitmapFactory.Options().apply { inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight) }
        BitmapFactory.decodeFile(file.absolutePath, options)
            ?.also { file.setLastModified(System.currentTimeMillis()) }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun saveDiskCachedBitmap(cacheDir: File, key: String, bitmap: Bitmap) {
    runCatching {
        synchronized(diskCacheLock) {
            val file = cacheFile(cacheDir, key)
            val temp = File(file.parentFile, "${file.name}.tmp")
            val written = FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            // Escritura atómica: evita dejar archivos truncados si el proceso se interrumpe
            if (!written || !temp.renameTo(file)) {
                temp.delete()
                return@runCatching
            }
            file.parentFile?.listFiles { candidate -> candidate.name.endsWith(".jpg") }
                ?.sortedByDescending(File::lastModified)
                .orEmpty()
                .drop(MAX_DISK_CACHE_FILES)
                .forEach(File::delete)
        }
    }
}

private fun extractRecordingFrame(context: Context, entry: RecordingEntry): Bitmap? {
    val candidateTimesUs = longArrayOf(20_000_000L, 5_000_000L, 0L)
    for (part in entry.parts) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, part.uri)
            for (timeUs in candidateTimesUs) {
                val frame = readScaledFrame(retriever, timeUs)
                if (frame != null) return frame
            }
        } catch (_: Exception) {
            // Se continúa con el siguiente segmento: algunas combinaciones de contenedor/códec no se pueden decodificar
        } catch (_: OutOfMemoryError) {
            return null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }
    return null
}

private fun readScaledFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val swap = rotation == 90 || rotation == 270
            val width = if (swap) rawHeight else rawWidth
            val height = if (swap) rawWidth else rawHeight
            if (width > 0 && height > 0) {
                // Se conserva la proporción original al escalar
                val scale = minOf(1f, MAX_ARTWORK_EDGE / width.toFloat(), MAX_ARTWORK_EDGE / height.toFloat())
                val targetWidth = maxOf(1, (width * scale).toInt())
                val targetHeight = maxOf(1, (height * scale).toInt())
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::resizeArtwork)
            }
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::resizeArtwork)
        }
    } catch (_: Exception) {
        null
    }
}

private fun resizeArtwork(bitmap: Bitmap): Bitmap {
    val scale = minOf(1f, MAX_ARTWORK_EDGE / bitmap.width.toFloat(), MAX_ARTWORK_EDGE / bitmap.height.toFloat())
    if (scale >= 1f) return bitmap
    val width = maxOf(1, (bitmap.width * scale).toInt())
    val height = maxOf(1, (bitmap.height * scale).toInt())
    val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
    if (resized !== bitmap) bitmap.recycle()
    return resized
}
