// CaptureOutput.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val COPY_BUFFER_SIZE = 64 * 1024
private val DEFAULT_STEM = "grabacion_${BuildConfig.BRAND_KEY}"
private val DEFAULT_DISPLAY_NAME = "Grabación ${BuildConfig.BRAND_NAME}"
private const val MIME_MP4 = "video/mp4"
private const val MIME_MP2T = "video/mp2t"
private val USB_TEMP_DIRECTORY = "${BuildConfig.BRAND_KEY}-usb-segments"
private val ILLEGAL_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

/** Append-only capture destination. SAF destinations avoid raw USB paths and persist grants. */
internal interface CaptureOutput {
    val id: String
    val displayName: String
    val bytes: Long
    val tempDirectory: File
    fun freeSpaceBytes(): Long?
    fun append(segment: File)
    fun delete()
}

internal class FileCaptureOutput(private val file: File) : CaptureOutput {
    override val id: String get() = file.absolutePath
    override val displayName: String get() = file.name
    override val bytes get() = file.length()
    override val tempDirectory: File
        get() {
            val directory = file.absoluteFile.parentFile ?: file.absoluteFile
            directory.mkdirs()
            return directory
        }

    @Suppress("UsableSpace")
    override fun freeSpaceBytes(): Long? = runCatching {
        var directory: File? = file.absoluteFile.parentFile
        while (directory != null && !directory.exists()) directory = directory.parentFile
        directory?.usableSpace?.takeIf { it >= 0L }
    }.getOrNull()

    override fun append(segment: File) {
        requireReadableSegment(segment)
        try {
            file.absoluteFile.parentFile?.mkdirs()
            segment.inputStream().use { input ->
                FileOutputStream(file, true).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                    output.flush()
                }
            }
        } catch (e: CaptureDestinationException) {
            throw e
        } catch (e: Exception) {
            throw CaptureDestinationException("No se pudo escribir en el archivo: ${e.message ?: "error de E/S"}", e)
        }
    }

    override fun delete() {
        runCatching { file.delete() }
    }
}

internal class UsbCaptureOutput private constructor(
    context: Context,
    private val document: DocumentFile,
    private val rootId: String,
) : CaptureOutput {
    @Volatile private var writtenBytes = 0L
    private val appContext: Context = context.applicationContext ?: context

    override val id get() = document.uri.toString()
    override val displayName get() = document.name ?: DEFAULT_DISPLAY_NAME
    override val bytes get() = writtenBytes
    override val tempDirectory: File
        get() = File(appContext.cacheDir, USB_TEMP_DIRECTORY).apply { mkdirs() }

    override fun freeSpaceBytes(): Long? = runCatching {
        usbRoot(appContext, rootId)?.let { StatFs(it.path).availableBytes }?.takeIf { it >= 0L }
    }.getOrNull()

    override fun append(segment: File) {
        requireReadableSegment(segment)
        try {
            val output = openAppendStream() ?: throw IOException("USB no permite anexar datos")
            segment.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                output.buffered(COPY_BUFFER_SIZE).use { sink ->
                    copyCounting(input, sink)
                    sink.flush()
                }
            }
        } catch (e: CaptureDestinationException) {
            throw e
        } catch (e: Exception) {
            throw CaptureDestinationException("No se pudo escribir en la USB: ${e.message ?: "dispositivo desconectado"}", e)
        }
    }

    override fun delete() {
        runCatching { document.delete() }
    }

    private fun openAppendStream(): OutputStream? {
        val resolver = appContext.contentResolver
        // Algunos proveedores no soportan "wa"; se reintenta con "a"
        return runCatching { resolver.openOutputStream(document.uri, "wa") }.getOrNull()
            ?: resolver.openOutputStream(document.uri, "a")
    }

    private fun copyCounting(input: InputStream, sink: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            sink.write(buffer, 0, read)
            writtenBytes += read
        }
    }

    companion object {
        fun create(context: Context, tree: Uri, extension: String, stem: String): UsbCaptureOutput {
            val appContext = context.applicationContext ?: context
            val rootId = treeRootId(tree)
            val root = DocumentFile.fromTreeUri(appContext, tree) ?: error("No se puede abrir la carpeta USB")
            check(root.exists()) { "La carpeta USB no está disponible" }
            check(root.canWrite()) { "La carpeta USB es de solo lectura" }
            val safeExtension = sanitizeFileName(extension).trim('.').ifBlank { "ts" }
            val safeStem = sanitizeFileName(stem).ifBlank { DEFAULT_STEM }
            val mimeType = if (safeExtension.equals("mp4", ignoreCase = true)) MIME_MP4 else MIME_MP2T
            val document = root.createFile(mimeType, "$safeStem.$safeExtension")
                ?: error("No se pudo crear el archivo en la USB")
            return UsbCaptureOutput(appContext, document, rootId)
        }

        fun freeSpace(context: Context, tree: Uri): Long? = runCatching {
            usbRoot(context, treeRootId(tree))?.let { StatFs(it.path).availableBytes }?.takeIf { it >= 0L }
        }.getOrNull()

        fun totalSpace(context: Context, tree: Uri): Long? = runCatching {
            usbRoot(context, treeRootId(tree))?.let { StatFs(it.path).totalBytes }?.takeIf { it >= 0L }
        }.getOrNull()

        private fun treeRootId(tree: Uri): String =
            DocumentsContract.getTreeDocumentId(tree).substringBefore(':')

        private fun usbRoot(context: Context, rootId: String): File? {
            if (rootId.isBlank()) return null
            return context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
                .filterNotNull()
                .firstOrNull { it.absolutePath.contains(rootId, ignoreCase = true) }
                ?.let { path -> File(path.absolutePath.substringBefore("/Android/")) }
                ?.takeIf { it.exists() }
        }
    }
}

internal class CaptureDestinationException(message: String, cause: Throwable? = null) : IOException(message, cause)

private fun requireReadableSegment(segment: File) {
    if (!segment.isFile || !segment.canRead()) {
        throw CaptureDestinationException("Segmento no disponible: ${segment.name}")
    }
}

private fun sanitizeFileName(value: String): String =
    value.replace(ILLEGAL_FILE_NAME_CHARS, "_").trim()