package Nexotv.iptv.player

import Nexotv.iptv.player.home.*
import java.text.Normalizer
import java.util.Locale

/** Curated FUTV program art. The channel's weekly schedule itself contains no image URLs. */
internal object FutvArtworkCatalog {
    private val artworkByTitle = mapOf(
        "los 11 titulares" to "https://futvcr.com/wp-content/uploads/2023/08/Color.png",
        "la jornada" to "https://futvcr.com/wp-content/uploads/2023/07/pro16%402x.png",
        "a fondo con" to "https://futvcr.com/wp-content/uploads/2023/07/pro15%402x.png",
        "acercandonos" to "https://futvcr.com/wp-content/uploads/2023/07/pro11%402x.png",
        "tiempo final" to "https://futvcr.com/wp-content/uploads/2025/08/LOGO-TRANSPARENTE-con-borde.png",
        "los saprissa" to "https://futvcr.com/wp-content/uploads/2023/07/pro8%402x.png",
        "somos el team" to "https://futvcr.com/wp-content/uploads/2023/07/pro14%402x.png",
        "el negocio del futbol" to "https://futvcr.com/wp-content/uploads/2023/07/pro2%402x.png",
        "mundo de futbol menor" to "https://futvcr.com/wp-content/uploads/2023/07/pro12%402x.png",
        "futbol tv y mas" to "https://futvcr.com/wp-content/uploads/2023/07/pro7%402x.png",
        "futbol tv mas" to "https://futvcr.com/wp-content/uploads/2023/07/pro7%402x.png",
        "futbol amp" to "https://futvcr.com/wp-content/uploads/2023/07/pro4%402x.png",
        "un recuerdo maravilloso" to "https://futvcr.com/wp-content/uploads/2023/08/RM.jpeg",
        "unicamente futbol" to "https://futvcr.com/wp-content/uploads/2024/11/logo_unicamente-futbol.png",
        "360 futsal" to "https://futvcr.com/wp-content/uploads/2026/06/LOGO-360-2.png",
    )
    private val titlesByLength = artworkByTitle.keys.sortedByDescending(String::length)

    fun imageFor(title: String): String {
        val normalized = normalize(title)
        return artworkByTitle[normalized]
            ?: titlesByLength.firstOrNull { key -> normalized.contains(key) }
                ?.let(artworkByTitle::get)
            .orEmpty()
    }

    private fun normalize(value: String): String = value
        .replace(Regex("""(?i)^\s*(?:en vivo|repetici[oó]n|repeticion)\s*[:\-–]?\s*"""), "")
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&#038;", "&", ignoreCase = true)
        .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
