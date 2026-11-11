package Nexotv.iptv.player.recordings





import Nexotv.iptv.player.activity.*
import Nexotv.iptv.player.menu.*
import Nexotv.iptv.player.tv.*
import Nexotv.iptv.player.home.*
import Nexotv.iptv.player.settings.*
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
import Nexotv.iptv.player.player.*
import Nexotv.iptv.player.status.*
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import Nexotv.iptv.player.MainActivity.*

private const val LOW_SPACE_WARNING_BYTES = 2L * 1024 * 1024 * 1024

/**
 * Presenta y cambia el destino de almacenamiento de las grabaciones.
 *
 * La UI se mantiene en Views y en el hilo principal. El controlador conserva
 * temporalmente el host Activity para no cambiar contratos ni foco D-pad.
 */
internal class RecordingDestinationController(
    private val activity: MainActivity,
) {
    private fun MainActivity.recordingDialogMessage(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.tv_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(8), dp(10), dp(8), dp(10))
    }

    private fun MainActivity.recordingSectionLabel(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(getColor(R.color.tv_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.1f
    }

    fun showRecordingDestination() {
        with(activity) {

        if (isRecording()) {
            val message = recordingDialogMessage(
                "Detén la grabación antes de cambiar su destino. La captura actual continúa en el almacenamiento elegido al iniciarla.",
            )
            AlertDialog.Builder(this)
                .setView(createStyledFormPanel("Grabación en curso", "GRABACIONES", message))
                .setPositiveButton("Entendido", null)
                .showForTv()
            return
        }
        val prefs = recordingStorageController.recordingPrefs()
        val current = prefs.getString(MainActivity.KEY_RECORDING_TREE, null)
        val activeTreeUri = current?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val activeUsbRoot = activeTreeUri?.let { runCatching { DocumentFile.fromTreeUri(this, it) }.getOrNull() }
        val activeDestinationAvailable = current == null || activeUsbRoot?.canWrite() == true
        val activeTotalBytes = when {
            current == null -> recordingStorageController.recordingDirectory().totalSpace
            activeTreeUri != null -> runCatching { UsbCaptureOutput.totalSpace(this, activeTreeUri) }.getOrNull()
            else -> null
        }
        val activeFreeBytes = when {
            current == null -> recordingStorageController.recordingDirectory().usableSpace
            activeTreeUri != null -> runCatching { UsbCaptureOutput.freeSpace(this, activeTreeUri) }.getOrNull()
            else -> null
        }
        val activeSpaceLow = activeFreeBytes != null && activeFreeBytes < HlsCapture.MIN_FREE_SPACE_BYTES
        val activeSpaceUnknown = current != null && activeFreeBytes == null
        val activeDestinationName = when {
            current == null -> "Almacenamiento interno"
            else -> activeUsbRoot?.name?.takeIf { it.isNotBlank() } ?: "Carpeta USB seleccionada"
        }
        val activeFreeText = activeFreeBytes?.let { formatBytes(it) } ?: "espacio no disponible"
        val activeDestinationDetail = when {
            !activeDestinationAvailable -> "No se puede acceder a esta carpeta. Revisa la USB y sus permisos."
            activeSpaceLow -> "$activeFreeText libres · se necesitan ${formatBytes(HlsCapture.MIN_FREE_SPACE_BYTES)} como mínimo"
            activeFreeBytes != null && activeTotalBytes != null -> "${formatBytes(activeFreeBytes)} libres de ${formatBytes(activeTotalBytes)}"
            activeFreeBytes != null -> "${formatBytes(activeFreeBytes)} libres disponibles"
            else -> "Espacio libre no disponible"
        }
        val accessibilityState = when {
            !activeDestinationAvailable -> "Requiere revisión"
            activeSpaceLow -> "Espacio insuficiente"
            activeSpaceUnknown -> "Espacio no verificado"
            else -> "Disponible"
        }
        val badgeText = when {
            !activeDestinationAvailable -> "REVISAR"
            activeSpaceLow -> "ESPACIO BAJO"
            activeSpaceUnknown -> "ESPACIO DESCONOCIDO"
            else -> "EN USO"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(12))
            background = menuBackdrop(activity)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            background = menuMainPanel(activity)
        }
        root.addView(panel, LinearLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(18))
        }
        val headerIcon = FrameLayout(this).apply {
            background = menuPill(activity, 0xFF12263A.toInt(), 0xFF27415B.toInt())
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.tv_icon_recordings)
                imageTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        header.addView(headerIcon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "GRABACIONES"
                setTextColor(getColor(R.color.tv_turquoise))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.12f
            })
            addView(TextView(activity).apply {
                text = "Destino de grabación"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        header.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        val destinationContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        destinationContent.addView(
            recordingSectionLabel("DESTINO ACTUAL"),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )

        val activeDestination = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = menuCardSurface(activity, 18f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            contentDescription = "Destino actual: $activeDestinationName. $activeDestinationDetail. $accessibilityState."
        }
        val activeIcon = FrameLayout(this).apply {
            background = menuPill(activity, 0xFF10243A.toInt(), 0xFF263D57.toInt())
            addView(ImageView(activity).apply {
                setImageResource(if (current == null) R.drawable.tv_icon_database else R.drawable.tv_icon_recordings)
                imageTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        activeDestination.addView(activeIcon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        val activeLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = activeDestinationName
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = activeDestinationDetail
                setTextColor(getColor(R.color.tv_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            if (activeTotalBytes != null && activeTotalBytes > 0L && activeFreeBytes != null) {
                val usedPercent = (((activeTotalBytes - activeFreeBytes.coerceIn(0L, activeTotalBytes)) * 100L) / activeTotalBytes)
                    .toInt().coerceIn(0, 100)
                addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = usedPercent
                    progressTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
                    progressBackgroundTintList = ColorStateList.valueOf(0xFF1E2B3E.toInt())
                }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(10) })
            }
        }
        activeDestination.addView(activeLabels, LinearLayout.LayoutParams(0, -2, 1f))
        val activeBadge = TextView(this).apply {
            text = badgeText
            setTextColor(if (!activeDestinationAvailable) Color.WHITE else 0xFF07111D.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val badgeColor = getColor(when {
                !activeDestinationAvailable -> R.color.tv_error
                activeSpaceLow || activeSpaceUnknown -> R.color.tv_warning
                else -> R.color.tv_success
            })
            background = menuPill(activity, badgeColor, badgeColor)
        }
        activeDestination.addView(activeBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        destinationContent.addView(activeDestination, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        destinationContent.addView(
            recordingSectionLabel("ELIGE UN DESTINO"),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )

        fun optionRow(title: String, description: String, iconRes: Int, isCurrent: Boolean): LinearLayout {
            val titleView = TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val descriptionView = TextView(this).apply {
                text = description
                setTextColor(getColor(R.color.tv_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val currentBadge = TextView(this).apply {
                text = "ACTUAL"
                setTextColor(0xFF07111D.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                visibility = if (isCurrent) View.VISIBLE else View.GONE
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = menuPill(activity, getColor(R.color.tv_success), getColor(R.color.tv_success))
            }
            return LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                contentDescription = "$title. $description${if (isCurrent) ". Destino actual" else ""}"
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = menuRowBackground(activity)
                val iconBox = FrameLayout(activity).apply {
                    background = menuPill(activity, 0xFF132034.toInt(), 0xFF24374E.toInt())
                    addView(ImageView(activity).apply {
                        setImageResource(iconRes)
                        imageTintList = ColorStateList.valueOf(getColor(R.color.tv_turquoise))
                    }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }
                addView(iconBox, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
                val labels = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleView)
                    addView(descriptionView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                }
                addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                addView(currentBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                addView(TextView(activity).apply {
                    text = "›"
                    setTextColor(getColor(R.color.tv_turquoise))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(24), -1).apply { marginStart = dp(8) })
            }
        }

        val usbOption = optionRow(
            if (current == null) "Elegir carpeta USB" else "Cambiar carpeta USB",
            if (current == null) "Las grabaciones internas dejarán de aparecer hasta volver a este destino." else "Las grabaciones actuales dejarán de aparecer hasta volver a su carpeta.",
            R.drawable.tv_icon_recordings,
            isCurrent = current != null,
        )
        val internalOption = optionRow(
            "Almacenamiento interno",
            if (current == null) "Guardar las próximas grabaciones en el dispositivo." else "Las grabaciones de la USB dejarán de aparecer hasta volver a seleccionarla.",
            R.drawable.tv_icon_database,
            isCurrent = current == null,
        )
        destinationContent.addView(usbOption, LinearLayout.LayoutParams(-1, dp(88)).apply { bottomMargin = dp(10) })
        destinationContent.addView(internalOption, LinearLayout.LayoutParams(-1, dp(88)).apply { bottomMargin = dp(16) })

        val closeButton = TextView(this).apply {
            id = View.generateViewId()
            text = "Cerrar"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = menuActionButton(activity, isRed = false, isCyanAccent = false)
        }
        destinationContent.addView(TextView(this).apply {
            text = "El cambio se aplicará a las próximas grabaciones."
            setTextColor(getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val destinationScroll = ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            addView(destinationContent)
            scrollFocusedDescendantIntoView()
        }
        panel.addView(destinationScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(closeButton, LinearLayout.LayoutParams(dp(140), dp(44)).apply { gravity = Gravity.END })

        usbOption.nextFocusUpId = usbOption.id
        usbOption.nextFocusDownId = internalOption.id
        usbOption.nextFocusLeftId = usbOption.id
        usbOption.nextFocusRightId = usbOption.id
        internalOption.nextFocusUpId = usbOption.id
        internalOption.nextFocusDownId = closeButton.id
        internalOption.nextFocusLeftId = internalOption.id
        internalOption.nextFocusRightId = internalOption.id
        closeButton.nextFocusUpId = internalOption.id
        closeButton.nextFocusDownId = closeButton.id

        val destinationDialog = AlertDialog.Builder(this).setView(root).create()
        destinationDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        setTvDialogBounds(destinationDialog, 1f, 1f)
        closeButton.setOnClickListener { destinationDialog.dismiss() }
        usbOption.setOnClickListener {
            if (isRecording()) {
                showTvToast("Detén la grabación antes de cambiar el destino.", long = true)
            } else if (current != null) {
                val message = recordingDialogMessage(
                    "Las grabaciones de la carpeta USB actual permanecerán allí, pero ${BuildConfig.BRAND_NAME} dejará de mostrarlas hasta que vuelvas a seleccionar esa carpeta.",
                )
                AlertDialog.Builder(this)
                    .setView(createStyledFormPanel("Cambiar carpeta USB", "GRABACIONES", message))
                    .setNegativeButton("Seguir usando esta carpeta", null)
                    .setPositiveButton("Elegir otra carpeta") { _, _ ->
                        destinationDialog.dismiss()
                        openUsbFolderPicker()
                    }
                    .showForTv()
            } else {
                destinationDialog.dismiss()
                openUsbFolderPicker()
            }
        }
        internalOption.setOnClickListener {
            if (isRecording()) {
                showTvToast("Detén la grabación antes de cambiar el destino.", long = true)
            } else if (current != null) {
                val message = recordingDialogMessage(
                    "Las próximas grabaciones se guardarán en el almacenamiento interno. Los archivos que ya están en la USB permanecerán allí, pero ${BuildConfig.BRAND_NAME} dejará de mostrarlos hasta que vuelvas a seleccionar esa carpeta.",
                )
                AlertDialog.Builder(this)
                    .setView(createStyledFormPanel("Cambiar destino de grabación", "GRABACIONES", message))
                    .setNegativeButton("Seguir usando USB", null)
                    .setPositiveButton("Cambiar a almacenamiento interno") { _, _ ->
                        destinationDialog.dismiss()
                        val previousTree = prefs.getString(MainActivity.KEY_RECORDING_TREE, null)
                        prefs.edit().remove(MainActivity.KEY_RECORDING_TREE).apply()
                        releaseRecordingTreePermission(previousTree)
                        invalidateRecordingSummary()
                        HomeScreenChannel.syncRecordings(this)
                        showStatus("Grabaciones en almacenamiento del dispositivo")
                    }
                    .showForTv()
            } else {
                destinationDialog.dismiss()
            }
        }
        destinationDialog.setOnShowListener {
            animateDialogEntrance(destinationDialog)
            val initialFocus = if (current == null) internalOption else usbOption
            initialFocus.post { initialFocus.requestFocus() }
        }
        destinationDialog.show()
        }
    }

    fun openUsbFolderPicker() {
        with(activity) {

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        runCatching { launchRecordingTree(intent) }
            .onFailure { showTvToast("Este dispositivo no ofrece selector de carpetas USB", long = true) }
        }
    }

    fun confirmRecordingStorage(onContinue: () -> Unit) {
        with(activity) {

        val tree = recordingStorageController.recordingTreeUri()
        val free = if (tree != null) {
            runCatching { UsbCaptureOutput.freeSpace(this, tree) }.getOrNull()
        } else {
            runCatching { recordingStorageController.recordingDirectory().usableSpace }.getOrNull()
        }
        val minimum = HlsCapture.MIN_FREE_SPACE_BYTES
        if (free == null) {
            AlertDialog.Builder(this).setTitle("Espacio de la USB no disponible")
                .setMessage("El selector USB no informa el espacio libre. Confirma que el disco tenga al menos 512 MB disponibles.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Continuar") { _, _ -> onContinue() }.showForTv()
            return
        }
        if (free < minimum) {
            AlertDialog.Builder(this).setTitle("Espacio insuficiente")
                .setMessage("Quedan ${formatBytes(free)}. ${BuildConfig.BRAND_NAME} necesita al menos ${formatBytes(minimum)} libres para grabar.")
                .setPositiveButton("Entendido", null).showForTv()
        } else if (free < LOW_SPACE_WARNING_BYTES) {
            AlertDialog.Builder(this).setTitle("Poco espacio disponible")
                .setMessage("Quedan ${formatBytes(free)}. La grabación puede detenerse si el espacio baja de ${formatBytes(minimum)}.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Continuar") { _, _ -> onContinue() }.showForTv()
        } else onContinue()
        }
    }

    @Suppress("WrongConstant")
    fun acceptUsbFolder(data: Intent?) {
        with(activity) {

        if (data == null) return
        val uri = data.data ?: return
        if (isRecording()) {
            showTvToast("La grabación comenzó mientras elegías la carpeta. Deténla antes de cambiar el destino.", long = true)
            return
        }
        val access = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching {
            check(access and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                "El selector no concedió permiso de escritura para la carpeta"
            }
            val root = DocumentFile.fromTreeUri(this, uri) ?: error("Carpeta USB inválida")
            check(root.canWrite()) { "La carpeta seleccionada no permite escritura" }
            verifyUsbAppend(root)
            // El permiso nuevo se persiste solo tras validar; nunca se revoca ante un fallo
            // porque la URI elegida podría ser la que ya está activa.
            val prefs = recordingStorageController.recordingPrefs()
            val previousTree = prefs.getString(MainActivity.KEY_RECORDING_TREE, null)
            contentResolver.takePersistableUriPermission(uri, access)
            prefs.edit().putString(MainActivity.KEY_RECORDING_TREE, uri.toString()).apply()
            releaseRecordingTreePermission(previousTree, keep = uri)
            invalidateRecordingSummary()
            HomeScreenChannel.syncRecordings(this)
            showStatus("Destino USB listo: ${root.name ?: "carpeta seleccionada"}")
        }.onFailure {
            showTvToast("No se pudo usar esa carpeta: ${it.message ?: "acceso denegado"}", long = true)
        }
        }
    }
}

