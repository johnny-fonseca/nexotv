package Nexotv.iptv.player

import androidx.media3.common.PlaybackException
import Nexotv.iptv.player.player.PlaybackFailureInput
import Nexotv.iptv.player.player.PlaybackFailureKind
import Nexotv.iptv.player.player.classifyPlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailureClassifierTest {

    private fun classify(
        errorCode: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        httpStatus: Int? = null,
        connectPhase: Boolean = false,
        timedOut: Boolean = false,
        causeNames: List<String> = emptyList(),
    ): PlaybackFailureKind = classifyPlaybackFailure(
        PlaybackFailureInput(
            errorCode = errorCode,
            httpStatus = httpStatus,
            connectPhase = connectPhase,
            timedOut = timedOut,
            causeNames = causeNames,
        ),
    )

    @Test
    fun dnsFailureIsDistinguishedFromTheRestOfTheNetworkErrors() {
        assertEquals(
            PlaybackFailureKind.DNS,
            classify(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                causeNames = listOf("HttpDataSourceException", "UnknownHostException"),
            ),
        )
    }

    @Test
    fun timeoutsAreSplitByConnectionPhase() {
        assertEquals(
            PlaybackFailureKind.CONNECT_TIMEOUT,
            classify(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                connectPhase = true,
                timedOut = true,
                causeNames = listOf("HttpDataSourceException", "SocketTimeoutException"),
            ),
        )
        assertEquals(
            PlaybackFailureKind.READ_TIMEOUT,
            classify(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                timedOut = true,
                causeNames = listOf("HttpDataSourceException", "SocketTimeoutException"),
            ),
        )
        // Un timeout puede llegar sin SocketTimeoutException en la cadena: manda el código.
        assertEquals(
            PlaybackFailureKind.READ_TIMEOUT,
            classify(errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, timedOut = true),
        )
    }

    @Test
    fun httpStatusSeparatesClientFromServerFailures() {
        assertEquals(
            PlaybackFailureKind.HTTP_CLIENT,
            classify(errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 404),
        )
        assertEquals(
            PlaybackFailureKind.HTTP_SERVER,
            classify(errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 503),
        )
        // Si el servidor respondió, el problema no es el timeout aunque haya uno en la cadena.
        assertEquals(
            PlaybackFailureKind.HTTP_SERVER,
            classify(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpStatus = 500,
                timedOut = true,
                causeNames = listOf("SocketTimeoutException"),
            ),
        )
    }

    @Test
    fun cleartextDecoderAndParsingHavePriorityOverNetworkRanges() {
        assertEquals(
            PlaybackFailureKind.CLEARTEXT,
            classify(errorCode = PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED),
        )
        assertEquals(
            PlaybackFailureKind.CLEARTEXT,
            classify(errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED, causeNames = listOf("CleartextHttpTrafficDisallowedException")),
        )
        assertEquals(
            PlaybackFailureKind.DECODER,
            classify(errorCode = 4001, causeNames = listOf("MediaCodecRenderer\$DecoderInitializationException")),
        )
        assertEquals(
            PlaybackFailureKind.PARSING,
            classify(errorCode = 3001, causeNames = listOf("HlsPlaylistParser\$ParserException")),
        )
    }

    @Test
    fun tlsAndGenericIoFallbacksAreClassified() {
        assertEquals(
            PlaybackFailureKind.TLS,
            classify(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                causeNames = listOf("SSLHandshakeException"),
            ),
        )
        assertEquals(
            PlaybackFailureKind.NETWORK,
            classify(errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            PlaybackFailureKind.OTHER,
            classify(errorCode = PlaybackException.ERROR_CODE_UNSPECIFIED),
        )
    }

    @Test
    fun labelsAreShortEnoughForTheHud() {
        PlaybackFailureKind.entries.forEach { kind ->
            assert(kind.label.isNotEmpty()) { "$kind sin etiqueta" }
            assert(kind.label.length <= 28) { "$kind tiene una etiqueta demasiado larga: ${kind.label}" }
        }
    }
}
