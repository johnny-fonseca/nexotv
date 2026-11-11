// RecordingBrowserFocusNavigation.kt
package Nexotv.iptv.player.recordings
import Nexotv.iptv.player.recordings.*
import Nexotv.iptv.player.*
/**
 * Navegación D-pad estática (compatible con control remoto Xiaomi / Google TV).
 * La navegación dinámica de las filas se recablea en cada refresco
 * (ver wireRecordingBrowserCatalogFocus en RecordingBrowserBindings.kt).
 */
internal fun MainActivity.bindRecordingBrowserFocusNavigation(views: RecordingBrowserViews) {
    val sortPill = views.topBar.sortPill
    val searchPill = views.topBar.searchPill
    val btnPlay = views.detail.btnPlay
    val btnPlayStart = views.detail.btnPlayStart
    val btnDelete = views.detail.btnDelete
    val btnKeep = views.deleteBanner.btnKeep
    val btnConfirmDelete = views.deleteBanner.btnConfirmDelete

    sortPill.nextFocusUpId = sortPill.id
    sortPill.nextFocusLeftId = sortPill.id
    sortPill.nextFocusRightId = searchPill.id

    searchPill.nextFocusUpId = searchPill.id
    searchPill.nextFocusDownId = btnPlay.id
    searchPill.nextFocusLeftId = sortPill.id
    searchPill.nextFocusRightId = searchPill.id

    btnPlay.nextFocusUpId = searchPill.id
    btnPlay.nextFocusDownId = btnPlayStart.id
    btnPlay.nextFocusRightId = btnPlay.id

    btnPlayStart.nextFocusUpId = btnPlay.id
    btnPlayStart.nextFocusDownId = btnDelete.id
    btnPlayStart.nextFocusRightId = btnPlayStart.id

    btnDelete.nextFocusUpId = btnPlayStart.id
    btnDelete.nextFocusDownId = btnDelete.id
    btnDelete.nextFocusRightId = btnDelete.id

    btnKeep.nextFocusUpId = btnDelete.id
    btnKeep.nextFocusDownId = btnKeep.id
    btnKeep.nextFocusLeftId = btnKeep.id
    btnKeep.nextFocusRightId = btnConfirmDelete.id

    btnConfirmDelete.nextFocusUpId = btnDelete.id
    btnConfirmDelete.nextFocusDownId = btnConfirmDelete.id
    btnConfirmDelete.nextFocusLeftId = btnKeep.id
    btnConfirmDelete.nextFocusRightId = btnConfirmDelete.id
}
