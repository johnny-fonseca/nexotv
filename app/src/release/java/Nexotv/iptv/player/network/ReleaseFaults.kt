package Nexotv.iptv.player.network

import androidx.media3.datasource.DataSource

/**
 * En release no hay inyección de fallos: la fuente medida se devuelve sin envolver y la
 * clase del laboratorio ni siquiera se compila en esta variante.
 */
internal fun decorateWithFaults(source: DataSource): DataSource = source
