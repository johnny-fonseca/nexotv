// File: Nexotv/iptv/player/activity/UiAvailability.kt
package Nexotv.iptv.player.activity

import Nexotv.iptv.player.MainActivity

/** Predicado compartido: la Activity todavía admite cambios de UI. */
internal fun MainActivity.isUiAvailable(): Boolean =
    try {
        !isFinishing && !isDestroyed
    } catch (_: Throwable) {
        false
    }