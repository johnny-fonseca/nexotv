// ============================================================================
// Archivo: RecordingLibraryFocusNavigation.kt
// ============================================================================
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
/**
 * 8. NAVEGACIÓN CON D-PAD (control remoto Xiaomi / Google TV)
 *
 * Debe invocarse después de crear todas las vistas y antes de mostrar el diálogo,
 * igual que en la versión original.
 */
internal fun wireRecordingLibraryFocus(
    topBar: RecordingLibraryTopBar,
    leftPanel: RecordingLibraryLeftPanel,
    hero: RecordingLibraryHeroCard,
    lowerCards: RecordingLibraryLowerCards,
    deleteBanner: RecordingLibraryDeleteBanner,
) {
    val storagePill = topBar.storagePill
    val openLibraryPill = topBar.openLibraryPill

    val rowLibraryView = leftPanel.rowLibraryView
    val rowScheduledView = leftPanel.rowScheduledView
    val rowStorageView = leftPanel.rowStorageView
    val recordRow = leftPanel.recordRow

    val playButton = hero.playButton

    val btnPlayPrimary = lowerCards.btnPlayPrimary
    val btnPlayStart = lowerCards.btnPlayStart
    val btnDelete = lowerCards.btnDelete
    val btnOpenFullLibrary = lowerCards.btnOpenFullLibrary
    val btnChangeUsb = lowerCards.btnChangeUsb

    val btnKeep = deleteBanner.btnKeep
    val btnConfirmDelete = deleteBanner.btnConfirmDelete

    listOf(rowLibraryView, rowScheduledView, rowStorageView, recordRow).forEach { v ->
        val prev = v.onFocusChangeListener
        v.setOnFocusChangeListener { view, focused ->
            prev?.onFocusChange(view, focused)
            if (focused) {
                playButton.nextFocusLeftId = view.id
                btnPlayPrimary.nextFocusLeftId = view.id
            }
        }
    }

    storagePill.nextFocusUpId = storagePill.id
    storagePill.nextFocusDownId = rowLibraryView.id
    storagePill.nextFocusLeftId = storagePill.id
    storagePill.nextFocusRightId = openLibraryPill.id

    openLibraryPill.nextFocusUpId = openLibraryPill.id
    openLibraryPill.nextFocusDownId = playButton.id
    openLibraryPill.nextFocusLeftId = storagePill.id
    openLibraryPill.nextFocusRightId = openLibraryPill.id

    rowLibraryView.nextFocusUpId = storagePill.id
    rowLibraryView.nextFocusDownId = rowScheduledView.id
    rowLibraryView.nextFocusLeftId = rowLibraryView.id
    rowLibraryView.nextFocusRightId = playButton.id

    rowScheduledView.nextFocusUpId = rowLibraryView.id
    rowScheduledView.nextFocusDownId = rowStorageView.id
    rowScheduledView.nextFocusLeftId = rowScheduledView.id
    rowScheduledView.nextFocusRightId = playButton.id

    rowStorageView.nextFocusUpId = rowScheduledView.id
    rowStorageView.nextFocusDownId = recordRow.id
    rowStorageView.nextFocusLeftId = rowStorageView.id
    rowStorageView.nextFocusRightId = btnOpenFullLibrary.id

    recordRow.nextFocusUpId = rowStorageView.id
    recordRow.nextFocusDownId = recordRow.id
    recordRow.nextFocusLeftId = recordRow.id
    recordRow.nextFocusRightId = btnChangeUsb.id

    playButton.nextFocusUpId = openLibraryPill.id
    playButton.nextFocusDownId = btnPlayPrimary.id
    playButton.nextFocusLeftId = rowLibraryView.id
    playButton.nextFocusRightId = btnPlayPrimary.id

    btnPlayPrimary.nextFocusUpId = playButton.id
    btnPlayPrimary.nextFocusDownId = btnOpenFullLibrary.id
    btnPlayPrimary.nextFocusLeftId = rowLibraryView.id
    btnPlayPrimary.nextFocusRightId = btnPlayStart.id

    btnPlayStart.nextFocusUpId = playButton.id
    btnPlayStart.nextFocusDownId = btnOpenFullLibrary.id
    btnPlayStart.nextFocusLeftId = btnPlayPrimary.id
    btnPlayStart.nextFocusRightId = btnDelete.id

    btnDelete.nextFocusUpId = playButton.id
    btnDelete.nextFocusDownId = btnChangeUsb.id
    btnDelete.nextFocusLeftId = btnPlayStart.id
    btnDelete.nextFocusRightId = btnOpenFullLibrary.id

    btnOpenFullLibrary.nextFocusUpId = btnPlayPrimary.id
    btnOpenFullLibrary.nextFocusDownId = btnOpenFullLibrary.id
    btnOpenFullLibrary.nextFocusLeftId = btnDelete.id
    btnOpenFullLibrary.nextFocusRightId = btnChangeUsb.id

    btnChangeUsb.nextFocusUpId = btnPlayPrimary.id
    btnChangeUsb.nextFocusDownId = btnChangeUsb.id
    btnChangeUsb.nextFocusLeftId = btnOpenFullLibrary.id
    btnChangeUsb.nextFocusRightId = btnChangeUsb.id

    btnKeep.nextFocusLeftId = btnKeep.id
    btnKeep.nextFocusRightId = btnConfirmDelete.id
    btnKeep.nextFocusUpId = btnDelete.id
    btnKeep.nextFocusDownId = btnOpenFullLibrary.id

    btnConfirmDelete.nextFocusLeftId = btnKeep.id
    btnConfirmDelete.nextFocusRightId = btnConfirmDelete.id
    btnConfirmDelete.nextFocusUpId = btnDelete.id
    btnConfirmDelete.nextFocusDownId = btnChangeUsb.id
}
