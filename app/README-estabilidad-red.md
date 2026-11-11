# Arquitectura de estabilidad de red y perfil Hotspot LTE

## Hardware y escenario objetivo

- **TV:** Xiaomi TV A Pro 2026 de 43" (Google TV), referencias L43MB-APEU / ELA5956EU.
- **Panel:** QLED 4K (3840×2160) a 60 Hz con MEMC. El stream no necesita 4K: el panel reescala.
- **SoC:** 4× Cortex-A55, GPU Mali-G52 MC1, **2 GB de RAM** y **8 GB de almacenamiento** (el
  espacio útil para el usuario es bastante menor).
- **Red:** la TV no tiene SIM. Se conecta por Wi-Fi al hotspot de un **Moto G60s** con datos LTE
  compartidos. Cadena completa: proveedor HLS → LTE → teléfono (hotspot) → Wi-Fi → TV.
- **Mando:** Bluetooth, solo D-pad y OK (sin pantalla táctil).

**No confirmado en el código ni en este documento:** el número de antenas y la versión exacta de
Android bajo Google TV, y qué códecs decodifica el hardware (HEVC, VP9, AV1). El código no asume
ninguno de los tres: no pide códecs concretos y deja que `MediaCodec` decida por pista. El **estándar
Wi-Fi** (5 vs 6) tampoco se asume, pero el HUD lo muestra cuando Android lo informa, junto con la
banda, la frecuencia, el RSSI y la velocidad de enlace (ver *Observabilidad*).

La app solo controla la TV. **No** configura el módem, el APN, la señal celular ni el punto de
acceso, y tampoco puede forzar la banda Wi-Fi ni el handoff entre antenas LTE. Cuando el problema
está por encima de la TV, la app solo puede cambiar de calidad, espaciar reintentos y hacer visible
la causa.

## Alcance

Este documento resume cómo el módulo Android (`app/`) intenta mantener la reproducción HLS ante
variaciones de conectividad, especialmente en una red móvil compartida por hotspot LTE. Describe la
implementación inspeccionada; no implica que la app controle el módem, el punto de acceso, la señal
celular ni la selección de red del sistema operativo.

## Resumen del flujo

1. **Permisos y política de tráfico.** `src/main/AndroidManifest.xml` declara `INTERNET`,
   `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` (solo lectura del estado del Wi-Fi para el HUD: no toca
   la red ni la ubicación, y no se consultan SSID/BSSID) y `WAKE_LOCK`. El tráfico HTTP sin cifrar se
   deshabilita globalmente mediante `usesCleartextTraffic="false"` y una configuración de seguridad. Las
   variantes FUTV y Teletica permiten HTTP únicamente para sus respectivas IP predeterminadas
   (`src/futv/res/xml/network_security_config.xml` y `src/teletica/res/xml/network_security_config.xml`).
2. **Observación de la red del sistema.** `player/PlaybackNetworkController.kt` registra un
   `ConnectivityManager.NetworkCallback` para la red predeterminada (API 24+) o una solicitud de red
   en versiones anteriores (`minSdk 23`). Solo notifica disponibilidad cuando la red activa informa
   `INTERNET` y `VALIDATED`. `status/ConnectionStatusClassifier.kt` registra el controlador y
   clasifica fallos; `status/PlayerConnectionStatus.kt` marca pérdida/cambio y pide reconexión al
   retomar una red validada, además de reevaluar la red por cuenta propia cuando el sistema no
   avisa (ver *Detección y recuperación*).
3. **Configuración del reproductor.** `player/PlayerFactory.kt` crea Media3/ExoPlayer, configura
   HTTP, política de errores, buffer, medidor de ancho de banda, `WAKE_MODE_NETWORK` y el
   estabilizador adaptativo, según el perfil seleccionado en `player/PlaybackNetworkPolicy.kt` y
   `player/PlayerTuning.kt`.
4. **Recuperación.** `player/PlaybackLifecycle.kt` gestiona estados del reproductor, errores,
   pérdida/restablecimiento de red y vigilancia de reproducción. `player/PlayerPlayback.kt` programa
   la recuperación con backoff. `player/PlaybackRecoveryController.kt` conserva el estado, calcula
   esperas y cancela tareas al detener o destruir la actividad. `activity/MainActivityLifecycle.kt`
   registra y limpia los callbacks durante el ciclo de vida.
5. **Medición y diagnóstico.** `network/MeteredNetworkUsageController.kt` detecta si Android reporta
   la red como medida y acumula bytes recibidos por el UID. `network/WifiPlaybackLock.kt` evita que
   el Wi-Fi entre en ahorro de energía durante la reproducción.
   `diagnostics/PlaybackDiagnostics.kt` y `diagnostics/DiagnosticTracker.kt` mantienen los datos del
   HUD; los eventos de red y reproducción se registran con `DiagnosticTracker`.

## Perfil `Hotspot`

`player/PlayerTuning.kt` define `Hotspot` como perfil predeterminado (`DEFAULT_PROFILE`). Valores
relevantes:

| Parámetro | Valor | Efecto |
|---|---:|---|
| Bitrate máximo de video | 2.200.000 bps (2,2 Mbps) | Limita la selección de video para el perfil y para redes que Android marca como medidas. |
| Estimación inicial de ancho de banda | 800.000 bps | Da una estimación conservadora inicial al medidor de ancho de banda. |
| Buffer mínimo / máximo | 8.000 / 10.000 ms | Mantiene una reserva moderada para streaming en vivo. |
| Buffer para iniciar | 3.000 ms | Favorece un inicio relativamente rápido. |
| Buffer tras rebuffer | 5.000 ms | Exige más contenido almacenado después de una interrupción. |
| Timeout de conexión HTTP | 20.000 ms | Tiempo de espera para establecer conexión. |
| Timeout de lectura HTTP | 25.000 ms | Permite lecturas más largas durante fluctuaciones de la red. |
| Offset en vivo objetivo | 8.000 ms | Mantiene margen respecto al borde del directo. |
| Offset mínimo / máximo en vivo | 3.000 / 9.500 ms | Acota la posición dentro de la ventana publicada; se adapta a la ventana disponible. |
| Velocidad de reproducción | 0,90x–1,00x | Permite ralentizar en descensos, pero **no** acelerar para alcanzar el directo. |
| Back buffer | 10.000 ms | Conserva margen para búsquedas hacia atrás. |

Los parámetros del perfil se aplican al construir el reproductor; el límite de bitrate también se
recalcula cuando cambia el estado de red medida. El código configura además
`DefaultLoadErrorHandlingPolicy(4)` con espera exponencial y jitter para errores de carga de Media3.

**Consecuencia conocida del rango de velocidad:** con `maxPlaybackSpeed = 1,00` el reproductor no
recupera el retraso acumulado tras un corte. Como el objetivo está a 8 s del borde en una ventana de
unos 11,3 s, cualquier pausa que acumule más de ~3,3 s de retraso deja la posición fuera de la
ventana y termina en `ERROR_CODE_BEHIND_LIVE_WINDOW` → reinicio del stream. Es el precio de no
acelerar el audio/video; permitir una corrección suave (1,00–1,06x) queda como propuesta pendiente de
validar en campo.

## Detección y recuperación

- Al perderse la red predeterminada, `handlePlayerNetworkLost()` marca la interrupción, registra el
  momento y presenta el estado de conexión. No programa intentos de stream mientras la red siga
  marcada como perdida.
- Cuando Android vuelve a informar una red activa con Internet validado,
  `handlePlayerNetworkAvailable()` llama a `reconnectOnNetworkReturn()`. Desde la ronda 2 la
  reanudación **no es destructiva**: espera 1,5 s de antirrebote y luego decide. Si el
  reproductor sigue en `READY` con ≥ 4 s de buffer, no lo toca; si está en `BUFFERING` o
  `READY` con poco buffer y hubo progreso en los últimos 8 s, hace `prepare()` +
  `seekToDefaultPosition()` sobre la misma fuente; solo sin progreso durante 8 s recarga
  completo. Un flag `networkResumePending` impide que el watchdog o `recoverStream()`
  dupliquen la recuperación durante el antirrebote.
- **Si Android no avisa del regreso.** Un hotspot puede volver a funcionar sin que el sistema emita
  `onCapabilitiesChanged` (el Wi-Fi nunca perdió la validación). El watchdog de 2 s llama a
  `recheckNetworkLoss()`: cuando pasaron **20 s** (`NetworkTuning.LOST_RECOVERY_RECHECK_MS`) y
  `hasValidatedInternet()` confirma Internet, se reutiliza el flujo normal de reconexión. Sin esta
  comprobación el estado de pérdida podía quedar pegado y la recuperación no se reintentaba nunca.
- **Red con transporte pero sin `VALIDATED` (bloque 1.6).** Si Android informa `INTERNET` pero no
  `VALIDATED` (la sonda del sistema puede fallar en un enlace lento o tras el proxy DNS del
  teléfono), tras 12 s de gracia la app usa el propio reproductor como sonda cada 8 s. No se
  reintroduce `StreamReachabilityProbe` ni se usa ICMP; si el stream llega a `READY`, se descarta el
  estado de red perdida y se registra `NETWORK_RESUME_UNVALIDATED`.
- **BEHIND_LIVE_WINDOW.** Antes de reconstruir se intenta un reposicionamiento liviano
  (`seekToDefaultPosition` + `prepare`, como máximo uno cada 30 s); si no basta, se recupera el
  stream.
- **Inicio explícito del stream.** `startStream()` descarta el estado de red perdida
  (`clearNetworkLostState()`), porque un reintento manual, una reanudación o la restauración tras
  `onStop` ya son decisiones de la app y deben volver a habilitar la recuperación automática.
- Los errores de reproducción y el buffering atascado pasan por `recoverStream()`.
  `player/PlaybackFailureClassifier.kt` clasifica la causa (DNS, TLS, timeout de conexión, timeout de
  lectura, HTTP 4xx/5xx, decodificador, formato, HTTP bloqueado, red) y esa etiqueta se guarda en el
  HUD y en el registro `PLAYER_ERROR`.
- La espera del reinicio completo usa backoff exponencial con jitter: base 1 s, tope 30 s. La
  interfaz permite reintento manual y muestra causa/espera.
- **Después de los seis intentos rápidos no se abandona.** `BufferSizes.streamRetryDelayMs()` pasa a
  un reintento lento de **60 s ±20 %** y sigue intentándolo: un corte prolongado del teléfono se
  recupera solo, pero sin reintentar cada 30 s ni consumir datos de más. El contador de intentos se
  muestra sin techo a partir del sexto.
- Hay un reposicionamiento suave tras cuatro muestras de buffering a intervalos de 2 s (aprox. 8 s).
  La vigilancia escala a recuperación completa si el buffering continúa 15 muestras (aprox. 30 s) o
  si la posición de reproducción se congela 5 muestras (aprox. 10 s) con el reproductor informando
  que reproduce.
- Los errores `BEHIND_LIVE_WINDOW` provocan recuperación del stream para reposicionarse en la ventana
  HLS actual.
- La recuperación automática se reinicia tras reproducción estable (15 s), retorno de red o acciones
  manuales pertinentes.
- Durante pausa o destrucción, las tareas y callbacks se cancelan/desregistran para evitar
  recuperaciones tardías. Al reanudar se registra de nuevo el callback.

## Qué significa “hotspot” para la app

La implementación no inspecciona SSID ni fuerza la conexión a un hotspot concreto. Observa la **red
predeterminada de Android**, sus capacidades y el estado de validación. Hay dos límites que se
combinan por el mínimo:

1. El del **perfil elegido**: `Hotspot` impone 2,2 Mbps aunque Android informe `NOT_METERED`. Por eso
   un hotspot que el teléfono no anuncia como medido sigue limitado mientras se use ese perfil.
2. El de **red medida**: con `NET_CAPABILITY_NOT_METERED` ausente se aplica el mismo tope de 2,2 Mbps
   aunque el perfil sea `Large`, `Medium` o `Small`.

No hay administración directa de Wi-Fi, LTE, APN, intensidad de señal o handoff entre módems. El
`WifiLock` de `network/WifiPlaybackLock.kt` solo evita el ahorro de energía del Wi-Fi mientras se
reproduce; no cambia de banda ni de red.

## Archivos fuente principales

- `src/main/AndroidManifest.xml` — permisos y configuración global de seguridad.
- `src/main/java/Nexotv/iptv/player/player/PlaybackNetworkController.kt` — callback de red predeterminada.
- `src/main/java/Nexotv/iptv/player/status/ConnectionStatusClassifier.kt` — registro del callback y validación/clasificación.
- `src/main/java/Nexotv/iptv/player/status/PlayerConnectionStatus.kt` — pérdida, cambio, retorno y revalidación de red.
- `src/main/java/Nexotv/iptv/player/player/PlayerFactory.kt` — configuración HTTP, Media3, buffers y wake mode.
- `src/main/java/Nexotv/iptv/player/player/PlayerTuning.kt` — perfiles, timeouts, offsets, backoff y reglas de red.
- `src/main/java/Nexotv/iptv/player/player/PlaybackNetworkPolicy.kt` — selección de perfil y política para redes medidas.
- `src/main/java/Nexotv/iptv/player/player/PlaybackLifecycle.kt` — estados, errores, reconexión y watchdog.
- `src/main/java/Nexotv/iptv/player/player/PlayerPlayback.kt` — recuperación y programación de intentos.
- `src/main/java/Nexotv/iptv/player/player/PlaybackRecoveryController.kt` — contadores y tareas de recuperación.
- `src/main/java/Nexotv/iptv/player/player/PlaybackFailureClassifier.kt` — clasificación de la causa del fallo.
- `src/main/java/Nexotv/iptv/player/player/PlaybackAnalytics.kt` — offsets HLS dinámicos y métricas de carga.
- `src/main/java/Nexotv/iptv/player/player/HotspotStabilitySupport.kt` — niveles de estabilidad y topes de calidad.
- `src/main/java/Nexotv/iptv/player/player/OffsetRenderersFactory.kt` — sink de audio con offset y fallback de decodificadores.
- `src/main/java/Nexotv/iptv/player/activity/MainActivityLifecycle.kt` — ciclo de vida de la actividad.
- `src/main/java/Nexotv/iptv/player/network/MeteredNetworkUsageController.kt` — red medida y tráfico acumulado.
- `src/main/java/Nexotv/iptv/player/network/WifiPlaybackLock.kt` — bloqueo de Wi-Fi durante la reproducción.
- `src/main/java/Nexotv/iptv/player/network/WifiLinkInfo.kt` — banda, RSSI, enlace y estándar del Wi-Fi para el HUD.
- `src/main/java/Nexotv/iptv/player/guide/GuideHttpClient.kt` — descargas de EPG con límites de stream.
- `src/main/java/Nexotv/iptv/player/recordings/HlsRecordingService.kt` — servicio en primer plano para grabaciones HLS.
- `src/futv/res/xml/network_security_config.xml`, `src/teletica/res/xml/network_security_config.xml` — excepciones HTTP por marca.

## Pruebas relacionadas

`src/test/java/Nexotv/iptv/player/PlayerTuningTest.kt` comprueba rangos de buffers/offsets, backoff y
reintento lento, timeouts, estimación inicial, límites de bitrate y la regla de revalidación de red.
`HotspotStabilitySupportTest.kt` comprueba el escalado limitado por evaluación, la expiración de
eventos, la relajación gradual, la monotonía de los niveles y la guarda del tope de altura.
`PlaybackFailureClassifierTest.kt` cubre la clasificación de fallos. `WifiPlaybackLockTest.kt` cubre
la selección del modo de `WifiLock` por API. `WifiLinkInfoTest.kt` cubre la banda por frecuencia, los
umbrales de calidad del RSSI, la traducción del estándar Wi-Fi y el texto del HUD cuando faltan datos.
`PlayerConnectionPresentationTest.kt` comprueba la clasificación y presentación de condiciones de
conexión. Estas pruebas unitarias verifican reglas de configuración y presentación; no sustituyen una
prueba de campo con pérdidas de paquetes, latencia variable y handoffs reales de LTE.

## Guía breve de diagnóstico

1. Confirmar que Android mantiene el hotspot como red predeterminada y que reporta Internet validado.
2. Revisar el HUD: buffer, retraso en vivo, rebuffers, bitrate estimado, **bits/s recibidos**,
   **duración y tamaño del último segmento**, **enlace Wi-Fi** (banda, RSSI, velocidad y estándar),
   **nivel de estabilidad** y **causa clasificada** del último fallo.
3. Si el HUD muestra "Sin validación de internet" pero el teléfono sí tiene datos, comparar con la
   línea `Wi-Fi:`: un RSSI débil o un enlace negociado lento apuntan a la TV↔teléfono; un enlace sano
   con cortes apunta al LTE o al proveedor.
4. Si la línea `Wi-Fi:` dice "datos no disponibles" estando conectado por Wi-Fi, el sistema no está
   entregando esos campos (limitación de Android 12+ o del fabricante); no es un fallo de la app.
5. Revisar URL, protocolo HTTP/HTTPS, redirecciones y encabezados del proveedor; HTTP solo está
   permitido por las excepciones declaradas.
6. Probar con el perfil `Hotspot` y observar si el buffering excede la ventana HLS del proveedor. El
   tamaño de buffer no puede superar el contenido realmente publicado por el stream en vivo.
7. Para reproducibilidad, registrar modelo/Android, variante instalada, hora del corte y eventos
   `NETWORK_LOST`, `NETWORK_AVAILABLE`, `NETWORK_RECHECK`, `NETWORK_LOST_CLEARED`, `NETWORK_RESUME`,
   `PLAYER_ERROR` (incluye la causa clasificada), `SOFT_RECOVERY`, `RETRY_SCHEDULED` y
   `STABILITY_LEVEL`.

## Parámetros exactos de espera y recuperación

### 1. Timeouts de cada operación HTTP

En `player/PlayerFactory.kt`, los valores del perfil se aplican a `DefaultHttpDataSource.Factory`:

| Perfil | Conexión (`connectTimeoutMs`) | Lectura (`readTimeoutMs`) |
|---|---:|---:|
| `Hotspot` | 3.500–5.000 ms (adaptativo) | 4.000–5.500 ms (adaptativo) |
| Otros perfiles | 8.000 ms | 8.000 ms |

En `Hotspot` los valores se recalculan al crear el reproductor como
`clamp(1,5 × RTT_p95 + margen, mínimo, máximo)` con el RTT p95 que mide la app
(`NetworkLoadStats`). Sin muestras se usa un RTT intermedio de 250 ms: 3,5 s de conexión y
4 s de lectura. Con el RTT medido en reposo (~114 ms) mandan los mínimos; con los picos de
~3,4 s bajo carga se llega a 5 s / 5,5 s, que siguen por debajo de la mitad de la ventana
medida (~11,5 s). Los demás perfiles conservan 8 s / 8 s. Los timeouts se aplican al
construir el reproductor (arranque, recarga y reconstrucción), no en caliente.

Los valores se validan en el rango de 1.000 a 120.000 ms; si un valor no es válido, se usa el
default correspondiente. El timeout de conexión se refiere al establecimiento de una conexión HTTP y
el de lectura al tiempo de espera de datos. No son una duración total máxima de reproducción ni
garantizan que el proveedor entregue contenido dentro de esos plazos.

El cliente es `DefaultHttpDataSource` (HTTP/1.1 sobre `HttpURLConnection`): no configura DNS propio,
ni pool de conexiones, ni `retryOnConnectionFailure`, ni HTTP/2. El proyecto declara
`androidx.media3:media3-datasource-okhttp` pero **no lo usa** todavía; ver *Propuestas pendientes*.

### 2. Reintentos de carga dentro de Media3

Con el perfil `Hotspot` y la bandera `PlayerTuning.inWindowRetries` activa, el
`DefaultMediaSourceFactory` recibe un `LiveLoadErrorHandlingPolicy` en lugar de
`DefaultLoadErrorHandlingPolicy(4)`. La política nueva clasifica el fallo y aplica un
presupuesto corto, pensado para una ventana de ~11,5 s:

| Fallo | Reintentos | Espera |
|---|---:|---|
| 404 / 410 u otro 4xx | 0 | escala de inmediato a `recoverStream()` |
| 401 / 403 (posible cambio de IP de salida) | 1 | 4 s ±20 % |
| Timeout de conexión o lectura | 1 | 250 ms ±20 % |
| Reset / DNS / red | 2 | 250 ms y 900 ms, ±20 % |
| 5xx | 1 | 900 ms ±20 % |

Al agotar el presupuesto la política devuelve `C.TIME_UNSET`, Media3 abandona esa carga y la
app escala a `recoverStream()`, que reposiciona en el borde del directo. Los backoff largos
de la ronda 1 (1→2→4→8 s por segmento, hasta 30 s) ya no se usan. El contador `errorCount`
de Media3 y el contador de reintentos de stream completo siguen siendo independientes.

### 3. Reinicio del stream completo

Cuando un error de reproducción, un buffering atascado u otra condición invoca `recoverStream()`
(`player/PlayerPlayback.kt`), la app programa un reinicio completo del stream salvo que la red esté
marcada como perdida. Para esta recuperación:

- **Intentos rápidos:** 6 (`MainActivity.MAX_RETRIES`).
- **Espera nominal por intento:** 1 = 1 s; 2 = 2 s; 3 = 4 s; 4 = 8 s; 5 = 16 s; 6 = 30 s (tope; el
  valor exponencial de 32 s queda limitado).
- **Después del sexto:** espera lenta de **60 s** (`BufferSizes.SLOW_RETRY_DELAY_MS`) y la
  recuperación continúa de forma indefinida. No hay un "se rinde": si el teléfono estuvo caído
  varios minutos, el stream se recupera al volver el enlace.
- **Jitter:** cada espera puede variar ±20 %.
- **Estado mostrado:** se guarda el instante objetivo con reloj monotónico (`elapsedRealtime`) y el
  panel puede mostrar el número de intento y segundos restantes.
- **Cancelación:** se cancela la tarea si la actividad deja de ser válida, el reproductor no está
  disponible o se detecta pérdida de red. Los reintentos de stream quedan en espera hasta que
  retorne Internet validado o el watchdog confirme la red (`NETWORK_RECHECK`).
- **Reinicio del contador:** al volver una red validada que permite reanudar, al lograr reproducción
  estable durante 15 s o ante acciones manuales pertinentes.

El contador de reintentos del stream completo y el `errorCount` de la política interna de Media3 son
independientes.

### 4. Vigilancia de buffering y congelamiento

`player/PlaybackLifecycle.kt` toma muestras de salud cada **2.000 ms** (`HEALTH_SAMPLE_SECONDS = 2`).
Los umbrales relevantes son:

- **Recuperación suave:** 4 muestras consecutivas en `BUFFERING` (aprox. **8 s**) antes de hacer
  `seekToDefaultPosition()` una vez. No reinicia inmediatamente el reproductor.
- **Buffering atascado:** `BUFFERING_LIMIT = 15` muestras (aprox. **30 s**) antes de escalar a
  `recoverStream()`, si no se activó ya una recuperación pendiente.
- **Posición congelada:** `FROZEN_LIMIT = 5` muestras (aprox. **10 s**) sin avance mientras el
  reproductor informa que está reproduciendo, antes de escalar a recuperación.
- **Ventana HLS expirada:** `ERROR_CODE_BEHIND_LIVE_WINDOW` provoca recuperación para volver a una
  posición válida del directo.

Los umbrales se cuentan como muestras de salud, no como temporizadores independientes de precisión
absoluta: el tiempo real puede variar por la planificación del hilo principal y por el estado del
reproductor.

### Secuencia resumida

1. Media3 realiza la carga HTTP; cada operación está sujeta a los timeouts de conexión/lectura del
   perfil.
2. Si falla una carga, la política de errores de Media3 calcula su espera con backoff/jitter.
3. Si el fallo escala a error de reproducción o el watchdog detecta buffering/congelamiento
   sostenido, la app programa recuperación completa, con su propio contador y su propio backoff.
4. Si Android comunica que se perdió la red predeterminada, se evita seguir lanzando reinicios
   durante la caída; el retorno de Internet validado —o, si el sistema no avisa, la comprobación de
   20 s del watchdog— dispara el flujo de reconexión.

Para comprobar o ajustar estos valores, revisar conjuntamente `player/PlayerTuning.kt` (constantes y
fórmulas), `player/PlayerFactory.kt` (aplicación de timeouts y política Media3),
`player/PlayerPlayback.kt` (recuperación completa), `player/PlaybackLifecycle.kt` (umbrales de salud),
`player/PlaybackRecoveryController.kt` (esperas) y `MainActivity.kt` (límites globales).

## Mecanismos avanzados de resiliencia en tiempo de ejecución

### 1. Adaptación dinámica de la ventana HLS en tiempo de ejecución

En `player/PlaybackAnalytics.kt`, el listener de analítica intercepta el evento `onTimelineChanged`.
Cuando el manifest HLS publica la duración real de la ventana de transmisión
(`liveWindow.durationMs`), la app evalúa si la ventana publicada es menor al objetivo teórico
configurado en el perfil:

- Calcula automáticamente el tríptico de offsets seguros (`minMs`, `targetMs`, `maxMs`) mediante
  `BufferSizes.offsetsForWindow(durations, windowMs)`.
- Si los offsets difieren de los actuales, invoca `player.replaceMediaItem(...)` reconfigurando la
  `MediaItem.LiveConfiguration` sobre el ítem en reproducción.
- Esto evita de forma proactiva el fallo de ventana expirada (`ERROR_CODE_BEHIND_LIVE_WINDOW`) cuando
  el proveedor reduce dinámicamente el número de segmentos en vivo.

Limitación conocida: el ajuste se hace **una sola vez por stream** (`liveWindowAdjustedForItem`) y
siempre sobre la ventana del primer ítem del timeline. Si el proveedor encoge la ventana a mitad de
sesión, no se recalcula hasta el siguiente `startStream()`.

### 2. Decodificación: fallback y escalado previo

En `player/OffsetRenderersFactory.kt`, los renderizadores de Media3 se instancian con
`setEnableDecoderFallback(true)`, y `PreferenceMediaCodecSelector` reordena —**nunca elimina**—
códecs para que el hardware siga primero.

- En conexiones inestables, la pérdida parcial de paquetes o las fluctuaciones de tasa pueden
  provocar fotogramas corruptos; el reproductor puede conmutar a un decodificador alternativo sin
  destruir la sesión.
- **Riesgo real en este SoC:** el fallback también puede elegir un decodificador por software. En un
  Cortex-A55 con 2 GB de RAM eso es inviable a 1080p. Por eso, cuando la causa clasificada del fallo
  es el decodificador, la app **baja un nivel de calidad antes** de reconstruir
  (`HotspotStabilizer.forceEscalate()`), de modo que el reproductor nuevo reciba una variante más
  ligera (`STABILITY_LEVEL` queda en el registro).
- Si el fallo decodificador persiste, `rebuildPlayer()` libera y reconstruye la instancia completa de
  `ExoPlayer` para limpiar buffers de GPU/códec corruptos, en lugar de una recarga liviana.

La app no consulta `MediaCodecList` para decidir qué códecs puede decodificar el hardware: no
restringe pistas por códec, solo por bitrate y altura.

### 3. Amortiguación (*debouncing*) durante handoffs y micro-cortes de red

En `network/MeteredNetworkUsageController.kt`, la pérdida de red reportada por `onLost(network)`
incluye un retardo intencional de 1.000 ms (`handler.postDelayed`) antes de retirar la política de
red medida:

- Ese margen evita alternar la calidad de video durante traspasos rápidos de interfaz o
  reconexiones breves del punto de acceso LTE.
- Si Android registra una nueva red activa válida dentro del segundo de gracia, la restricción de
  ancho de banda se mantiene estable y no provoca oscilaciones del ABR.

### 4. Aislamiento del tráfico secundario (EPG y grabaciones HLS)

- **Descargas EPG (`guide/GuideHttpClient.kt`):** buffers acotados (8 KB), timeouts independientes
  (15 s conexión / 30 s lectura / 180 s total), tope de 16 MiB con inspección de gzip y
  redirecciones validadas. Además, `GuideRefreshController.refreshGuide()` **aplaza** la
  actualización automática mientras la reproducción está activa (`deferWhilePlaying`).
- **Grabaciones HLS (`recordings/HlsRecordingService.kt`):** se ejecutan en un `ForegroundService`
  con tipo `dataSync` y su propio hilo. **No hay priorización de tráfico ni QoS:** una grabación en
  curso compite con el video por el mismo LTE y, con 1–5 Mbps, puede provocar rebuffers. La app sí
  limita el daño con timeouts propios (10 s conexión / 15 s lectura) y 6 reintentos con espera
  creciente (1, 2, 4, 8, 16 y 30 s).

## Mejoras adaptativas integradas

`player/HotspotStabilitySupport.kt` complementa el perfil estático y el watchdog. `HotspotStabilizer`
observa rebuffers, errores de E/S del reproductor y duración de cortes de red. En respuesta, reduce
gradualmente el tope de selección de video y la altura máxima, sin reconstruir el reproductor ni
reemplazar el MediaItem.

| Nivel | Tope de bitrate | Altura máxima |
|---|---:|---:|
| `NORMAL` | sin tope propio (manda el perfil/red medida) | 1080p |
| `CAUTIOUS` | 1.500.000 bps | 540p |
| `DEGRADED` | 1.000.000 bps | 480p |
| `SURVIVAL` | 600.000 bps | 480p |

- El gobernador puntúa eventos en una ventana de 90 s, escala como máximo un nivel por evaluación,
  aplica un periodo de enfriamiento de 15 s para escalar y un periodo de calma de 90 s para
  recuperar niveles (recupera más despacio de lo que degrada).
- El tope adaptable se combina con el límite base (perfil o red medida) mediante el mínimo; nunca
  relaja los límites existentes.
- **Tope de 1080p:** el panel es 4K y reescala, y decodificar 4K en este SoC no aporta nada; una
  variante 4K sin `BANDWIDTH` declarado se escaparía además del tope de bitrate. Si el proveedor
  publica **solo** variantes por encima de 1080p, el tope de altura se omite para no dejar el video
  sin pista seleccionable (se registra una advertencia).
- **`SURVIVAL` mantiene 480p:** en 43" a 3 m, 360p se nota mucho más que el ahorro que aporta. El
  consumo lo reduce el tope de bitrate, no bajar más la resolución.
- La actividad adjunta el listener al crear cada `ExoPlayer`, lo desanexa antes de liberarlo y le
  notifica pérdidas/retornos de red. Los cambios de red medida y el inicio de cada stream reaplican
  el límite efectivo. Los cambios de nivel quedan en el registro como `STABILITY_LEVEL`.

## Ahorro de energía y red del televisor

- `PlayerFactory.kt` configura `C.WAKE_MODE_NETWORK`, que mantiene la CPU despierta durante la
  reproducción. `WAKE_LOCK` ya estaba declarado en el manifiesto.
- `network/WifiPlaybackLock.kt` adquiere además un `WifiLock` mientras la reproducción puede consumir
  red (reproduciendo, conectando o esperando un reintento). Usa `WIFI_MODE_FULL_LOW_LATENCY` desde
  Android 10 y `WIFI_MODE_FULL_HIGH_PERF` en versiones anteriores. Se libera al pausar, al detener,
  al liberar el reproductor en `onStop` y al destruir la actividad; en PiP se mantiene porque el
  video sigue reproduciéndose. No requiere permisos nuevos: `WifiLock` exige `WAKE_LOCK`, ya
  declarado.
- El bloqueo **no** controla la banda ni la potencia de la señal: solo evita que el firmware del
  Wi-Fi entre en ahorro de energía, que es lo que añade latencia en modo ambiente o con la pantalla
  apagada.
- La banda, la frecuencia, el RSSI, la velocidad de enlace y el estándar **se muestran en el HUD**
  (no en pantalla normal). La lectura usa `ACCESS_WIFI_STATE`, un permiso normal (sin diálogo), y no
  consulta SSID ni BSSID, así que no requiere permiso de ubicación. En Android 12+ algunos equipos
  entregan estos datos redactados o incompletos: el HUD escribe "datos no disponibles" u omite los
  campos que el sistema no informe, en lugar de mostrar un valor inventado. La app sigue sin poder
  cambiar de banda ni de red.

## Observabilidad

`diagnostics/DiagnosticTracker.kt` mantiene el HUD en vivo con:

- buffer total, retraso respecto al directo y velocidad de reproducción;
- número y duración acumulada de rebuffers;
- bitrate estimado por Media3;
- **bits/s realmente recibidos por la app**, medidos con `TrafficStats.getUidRxBytes(Process.myUid())`
  entre muestras (sin permisos nuevos);
- **duración y tamaño del último segmento/lista cargados** (`onLoadCompleted`);
- **enlace Wi-Fi**: banda, frecuencia, RSSI con calidad en palabras ("buena", "regular", "débil",
  "muy débil"), velocidad de enlace y estándar (Wi-Fi 4/5/6). Se lee de
  `NetworkCapabilities.transportInfo` (Android 10+) con respaldo en `WifiManager.getConnectionInfo()`,
  como máximo cada 2 s, y solo cuando la red activa es Wi-Fi; por cable la línea no aparece. Si el
  sistema no entrega un campo, se omite en lugar de rellenarlo;
- **nivel de estabilidad actual**;
- **causa clasificada** del último fallo y el último error de carga con su recurso y hora;
- **enlace medido por la app**: capacidad sostenida estimada (p50 de bytes/s de segmentos),
  RTT p95, holgura frente al bitrate declarado, relación descarga/duración (p95) y tasa de
  fallos en ventanas de 2 y 15 min, desglosados por `host:puerto`;
- **timeouts HTTP aplicados** y el RTT p95 con el que se calcularon;
- **variantes publicadas**: con una sola escribe "sin variantes inferiores" en lugar de
  aparentar ABR;
- **sesiones**: últimos 20 informes con KPIs (ver más abajo).

La línea del HUD se ve así:

```
Wi-Fi: 5 GHz · 5180 MHz · -58 dBm (buena) · 390 Mbps · Wi-Fi 5 (11ac)
Enlace: cap 5.8 Mbps · RTT p95 1.9s · holgura 2.9x · desc/seg 0.42 · fallos 3% · t/o 4.4s/5.4s
Variantes: 1 (sin variantes inferiores)  ·  Estabilidad: NORMAL
```

### Informe de sesión y medición por host

`diagnostics/SessionReport.kt` guarda, al terminar cada sesión, tiempo hasta el primer frame,
nº y duración de rebuffers, ratio de rebuffer, stalls/hora, nº de `BEHIND_LIVE_WINDOW`, nº de
recargas completas, MTTR (corte → 15 s de reproducción estable), corte más largo superado sin
rebuffer y bytes consumidos por el UID. Se conservan las últimas 20 sesiones en
`Android/data/<paquete>/files/diagnostics/sessions.json` (rotativo, ≤ 1 MiB). No se guardan
SSID, BSSID ni ubicación; el host se reduce a `host:puerto` y las URLs nunca incluyen ruta ni
query. El diálogo de diagnóstico muestra un resumen y el archivo se extrae con `adb pull` en
el APK de depuración.

`network/NetworkLoadStats.kt` envuelve la fuente HTTP (`MeasuredDataSource`) y mide TTFB,
bytes y duración de cada carga. La relación descarga/duración se toma de `onLoadCompleted`
con la duración real del segmento. Las muestras se recortan por tiempo (15 min) y por número
(600), y no crecen sin límite.

Sirve para separar un problema **TV↔teléfono** (RSSI bajo, enlace negociado lento, 2,4 GHz saturada)
de un problema **LTE**: si el enlace Wi-Fi está sano y el video se corta, el cuello de botella está por
encima del hotspot.

El registro de eventos (200 entradas en memoria, exportable desde el diálogo de diagnóstico) incluye
`NETWORK_LOST`, `NETWORK_AVAILABLE`, `NETWORK_CHANGED`, `NETWORK_RECHECK`,
`NETWORK_RECHECK_UNVALIDATED`, `NETWORK_LOST_CLEARED`, `NETWORK_RESUME`,
`NETWORK_RESUME_SCHEDULED`, `NETWORK_RESUME_DECISION`, `NETWORK_RESUME_UNVALIDATED`,
`PLAYER_ERROR`, `LOAD_ERROR`, `SEGMENT_RETRY`, `MTU_SUSPECT`, `BEHIND_LIVE_WINDOW`,
`BEHIND_LIVE_WINDOW_LIGHT`, `REBUFFER_START`/`REBUFFER_END`, `RECOVER`, `RECOVERY_DEFERRED`,
`RETRY_SCHEDULED`, `RETRY_STABLE`, `SOFT_RECOVERY`, `STABILITY_LEVEL`, `SESSION_KPI`,
`METERED_NETWORK` y `CHANNEL_START`. En el APK de depuración se añaden `DEBUG_FAULT`,
`DEBUG_FLAG` y `DEBUG_RESET`.

## Propuestas pendientes (no implementadas)

1. **DataSource OkHttp.** `media3-datasource-okhttp` sigue declarado y sin uso. Se evaluó en
   la ronda 2 para el perfil Hotspot: permitiría timeouts por fase, `retryOnConnectionFailure`
   y un pool corto, pero no hay forma de expulsar el pool de `HttpURLConnection` sin OkHttp.
   No se migró porque (a) no se puede demostrar una mejora medible sin la TV, (b) con un
   túnel compartido abrir otra conexión no esquiva un túnel atascado y (c) el valor real
   —descartar sockets muertos— queda en parte cubierto por los timeouts adaptativos y el
   presupuesto de reintentos. Queda como A/B de campo con `debuggable` y la misma inyección
   de fallos.
2. **Ventana de recepción TCP (`SO_RCVBUF`).** Solo es viable con OkHttp (socketFactory antes
   de `connect`). La ventana necesaria para no limitar el caudal es
   `W ≥ 2 × bitrate × RTT_base`; con 1,89 Mbps y RTT base de 0,1–0,5 s da ~50–250 KB. Los
   matices: fijarla desactiva el auto-tuning de ese socket y, si el túnel termina el TCP en
   el teléfono, la ventana de la TV no regula el tramo LTE. Sin poder medir el RTT p95 bajo
   carga en la TV, no se implementó; el plan A/B está en el informe de la ronda 2.
3. **Corrección suave del retraso en vivo.** Permitir `maxPlaybackSpeed` entre 1,00 y ~1,06
   para absorber cortes de 5–15 s sin salir de la ventana HLS. Requiere validar que el audio
   no se note; es el bloque 3.1, pendiente de la pausa de campo.
4. **Pausar tráfico secundario por nivel.** Diferir o pausar EPG y grabaciones cuando el
   estabilizador esté en `CAUTIOUS` o peor. No se hizo para grabaciones porque perder un
   programa programado es peor que un rebuffer; necesita una decisión de producto.
5. **Aviso previo a la descarga de EPG grande.** El tope actual es de 16 MiB por respuesta; en un
   enlace de 1 Mbps son ~2 minutos de tráfico que compite con el video si el usuario actualiza la
   guía a mano durante la reproducción.
   
# Informe de la ronda 2 — verificación y cambios (bloques 0 y 1)

Fecha: 2026-10-30. Rama: `codex/redisenio-tv`. Alcance de esta entrega: **bloques 0 y 1**,
con la pausa pedida antes de los bloques 2–5.

Todo lo marcado **sin probar en dispositivo** se validó solo con el código, pruebas JVM y
mediciones externas. No hay TV ni hotspot conectados a esta sesión.

## 1. Tabla de hipótesis

| # | Hipótesis | Veredicto | Evidencia |
|---|---|---|---|
| H1 | La capacidad media (~6 Mbps) es ~3× el stream; el problema son la latencia bajo carga y los micro-cortes frente a una ventana de ~11 s | **Confirmada para Teletica, parcial para el resto** | Teletica declara 1,96 Mbps → 6/1,96 = 3,06×. FUTV declara 1,47 → 4,1×. OPA declara 6,99 y mide ~6,3 reales → **0,95×**, no hay margen. Medición de manifiestos del 2026-10-30 (ver §2) |
| H2 | Con una sola variante, ABR, estabilidad y pisos de calidad no tienen a dónde bajar; el HUD debe decirlo | **Confirmada** | Los tres maestros publican **una** variante (§2). `PlaybackAnalytics.kt:89` cuenta grupos de video; `DiagnosticTracker.kt:477` escribe "sin variantes inferiores" con 1 |
| H3 | Los timeouts deben convivir con picos de ~3,4 s y con una ventana de ~11 s | **Confirmada** | Los 20 s/25 s de la ronda 1 (`PlayerTuning.kt:376`) permiten 45 s por intento. Ahora `PlayerTuning.kt:131` calcula 3,5–5 s / 4–5,5 s |
| H4 | El Wi-Fi local no es el cuello de botella | **Sostenida por medición externa; sin probar en la TV** | Enlace 802.11n ~72 Mbps, gateway ~2 ms y 0 % pérdida; el HUD ahora muestra banda/RSSI/enlace para confirmarlo en campo |
| H5 | DNS y TLS solo importan en OPA | **Confirmada** | Teletica/FUTV son HTTP a IP (`build.gradle.kts:42,62`); OPA es host + TLS (`:81`). `network_security_config` solo abre HTTP a esas IP |
| H6 | Ver y grabar a la vez ≈ 2 × bitrate ≈ 60–70 % de la capacidad | **Confirmada en Teletica; inviable en OPA** | Teletica 2 × 1,89 = 3,78 Mbps ≈ 63 % de 6. FUTV menor. OPA 2 × 6,3 = 12,6 Mbps > capacidad. `HlsCapture.kt`/`HlsRecordingService.kt` no priorizan tráfico |
| H7 | ICMP no es señal válida de red caída | **Confirmada** | El proveedor no responde ICMP y TCP/443 sí; la app no usa ICMP ni `StreamReachabilityProbe` (grep sin coincidencias) |
| H8a | Reconexiones = blackhole de segundos con TCP aparentemente vivo | **Parcial / no verificable desde la app** | `DefaultHttpDataSource` solo detecta por timeout de lectura; el nuevo presupuesto lo acota a ~11 s (`SegmentRetryPolicy.kt:130`) |
| H8b | MTU efectiva menor: listas OK y segmentos colgados tras pocos KB | **Parcial: detector implementado, sin evidencia de dispositivo** | `PlaybackFailureClassifier.kt:88` (`isMtuSuspect`) exige lista OK reciente, 1 B–64 KB y ≥ 2 s; se registra `MTU_SUSPECT` |
| H8c | La IP de salida puede cambiar tras reconectar (401/403) | **Parcial** | `SegmentRetryPolicy` da **un** reintento a 4 s para 401/403. No existe renovación de URL/token que usar: `currentStreamUrl()` solo lee preferencias; no se inventó |
| H8d | El DNS de la TV pasa por el proxy DNS del teléfono | **No verificable desde la app** | La app no cambia DNS ni lo consulta; sería visible en el teléfono |
| H8e | UDP/QUIC y probablemente IPv6 no pasan por el túnel | **Confirmada por diseño, no medida** | El reproductor usa HTTP/1.1 sobre TCP (`DefaultHttpDataSource`), sin QUIC y sin preferencia IPv6 |
| H9 | Una conexión keep-alive zombi puede colgarse hasta el timeout de lectura | **No descartable; no se migró** | `DefaultHttpDataSource` usa el pool de `HttpURLConnection`, sin API para expulsarlo. OkHttp permitiría expulsar el pool, pero no se puede medir la mejora sin la TV y el túnel compartido no se esquiva con otra conexión |
| H10 | Limitar `SO_RCVBUF` reduce el bufferbloat | **No probada; no implementada** | Solo es viable con OkHttp (socketFactory antes de `connect`) y, si el túnel termina el TCP en el teléfono, la ventana de la TV no regula el tramo LTE. Sin A/B en la TV no se activa |

## 2. Inventario real de los manifiestos

Medido el 2026-10-30 desde un PC (no desde la TV ni el hotspot), con `curl`/Python:

| Marca | Variantes | `BANDWIDTH` | `AVERAGE-BANDWIDTH` | `TARGETDURATION` | Segmentos | Ventana |
|---|---:|---:|---:|---:|---|---:|
| Teletica (a07t) | 1 | 1,96–1,98 Mbps | 1,77 Mbps | 3 s | 4 × 2,77–2,97 s | ≈11,5 s |
| FUTV (a03v) | 1 | 1,47 Mbps | 1,38 Mbps | 3 s | 4 × 2,94 s | ≈11,7 s |
| OPA | **1** | 6,99 Mbps | — | 13 s | 3 × 10,01 s | ≈30,0 s |

Un segmento real de OPA midió 7.878.516 bytes en 10,01 s ≈ **6,3 Mbps**. El maestro de OPA
publica **una** rendición, no tres. Consecuencia: el bloque 2 (ABR) no tiene efecto hoy en
ninguna marca; el HUD lo dice.

Dato adicional: un segmento caducado de Teletica puede responder `HTTP 200` con un cuerpo TS
de ~188 bytes en lugar de 404. Por eso la política no trata "200 corto" como definitivo; el
patrón lista OK / segmento corto se clasifica como sospecha de MTU, no como 404.

## 3. Presupuesto del peor caso de un segmento colgado

La ventana medida es ~11,5 s y el buffer del perfil es 8–10 s; el corte tolerable sin recarga
dura es aproximadamente la ventana (buffer + margen).

| Escenario | Antes (ronda 1) | Después (ronda 2) |
|---|---:|---:|
| Solo timeout de conexión | 5 × 20 s + backoff 15 s = **115 s** | 2 × 5 s + 0,25 s = **10,25 s** |
| Solo timeout de lectura | 5 × (20+25) s + 15 s = **240 s** | 2 × 5,5 s + 0,25 s = **11,25 s** |
| Conexión + lectura | **240 s** | 2 × 10,5 s + 0,25 s = **21,25 s** (caso raro: conecta y luego se cuelga el cuerpo) |
| 404/410 | backoff de Media3 antes de rendirse | **0 s** (escala de inmediato) |
| Reset / DNS | 5 intentos con 1→8 s | 2 intentos, 0,25 + 0,9 s |

Después del presupuesto, `recoverStream()` programa el primer intento en ~1 s y `startStream()`
crea un ítem nuevo en el borde del directo: no se produce `BEHIND_LIVE_WINDOW`, el costo es un
rebuffer de ~11–14 s. Antes, un segmento colgado podía consumir 240 s, ~20 veces la ventana.

Los timeouts se aplican al **crear** el reproductor; un cambio de RTT a mitad de sesión se usa
en la siguiente recarga/reconstrucción, no en caliente.

## 4. Cambios por commit

| Commit | Contenido |
|---|---|
| `edcae48b` | Instrumentación de red, timeouts adaptativos y reintentos de segmento (0.1, 0.5, 0.6, 0.7, 1.1, 1.4) |
| `e0a200dd` | Reconexión no destructiva y sonda sin `VALIDATED` (1.5, 1.6) |
| `6d54d107` | Conmutador de fallos solo en el APK de depuración (0.2, 0.4) |
| `c7b3c361` | README: inventario real y recuperación de la ronda 2 |

### Banderas (`PlayerTuning.kt`)

| Bandera | Valor por defecto | Por qué |
|---|---:|---|
| `adaptiveTimeouts` | **ON** | 20/25 s fijos permitían superar la ventana; el rango 3,5–5,5 s cubre RTT 114 ms y picos de 3,4 s |
| `inWindowRetries` | **ON** | El backoff 1→30 s por segmento no cabe en una ventana de 11,5 s |
| `nonDestructiveResume` | **ON** | El `stop()` + recarga siempre tiraba el buffer que aún servía |
| `unvalidatedRetry` | **ON** | La validación del sistema puede fallar con el proxy DNS del teléfono; el reproductor es la sonda |
| Laboratorio de fallos | **OFF** (`FaultMode.NONE`) | Solo existe en debug y se activa por `adb` |

### Conmutador de depuración

```powershell
adb shell am broadcast -a Nexotv.iptv.player.DEBUG.FAULT --es mode blackhole --el value 20000
adb shell am broadcast -a Nexotv.iptv.player.DEBUG.FAULT --es mode loadlatency --es host 190.61
adb shell am broadcast -a Nexotv.iptv.player.DEBUG.FAULT --es mode throttle --el value 700000
adb shell am broadcast -a Nexotv.iptv.player.DEBUG.FLAG --es flag adaptiveTimeouts --ez enabled false
adb shell am broadcast -a Nexotv.iptv.player.DEBUG.RESET
```

Modos: `none`, `blackhole`, `reset`, `latency`, `throttle`, `dns`, `loadlatency` (RTT 0,1 → 2 →
3,5 s según los bytes del último segundo). El estado se pierde al cerrar la app.

## 5. Lo que NO se implementó y por qué

- **1.2 `SO_RCVBUF`**: requiere OkHttp y un `socketFactory` antes de `connect`; además, si el
  túnel termina el TCP en el teléfono, la ventana de la TV no regula el LTE. Sin A/B en la TV no
  se activa. La ventana objetivo calculada es `W ≥ 2 × 1,89 Mbps × RTT_base`; con RTT base de
  0,1–0,5 s da ~50–250 KB, pero fijarla desactiva el auto-tuning del socket.
- **1.3 OkHttp**: no se migró. Valor real = descartar sockets muertos; el túnel compartido no se
  esquiva abriendo otra conexión. Sin medición en dispositivo no se puede demostrar mejora frente
  a `DefaultHttpDataSource`. R8 y el tamaño del release quedan sin cambio por esta decisión
  (release 5,17 MB).
- **1.7 DNS propio (OPA)**: dependía de 1.3; no se hizo. No se cambió el DNS del sistema.
- **Bloques 2–5**: quedan para después de la pausa. En particular, el bloque 2 no tiene efecto
  mientras los tres maestros publiquen una sola variante.
- **Sección opcional de MSS**: no se propone todavía. 0.5 no mostró el patrón en dispositivo
  (no hay TV conectada); la app ya lo detecta y lo registra. Si aparece en campo, el único ajuste
  a evaluar es un clamp de MSS en el reenvío del teléfono, sin tocar las reglas de VPN Hotspot.

## 6. Compilación y pruebas

| Variante | Pruebas | Resultado |
|---|---:|---|
| `teletica` debug | 92 | 92 OK, 0 fallos |
| `futv` debug | 92 | 92 OK, 0 fallos |
| `opa` debug | 92 | 92 OK, 0 fallos |

- APK debug: Teletica 13.897.767 B; FUTV 9.797.667 B; OPA 9.798.986 B.
- Release Teletica (sin firma, porque no hay keystore en la sesión): 5.170.546 B.
- **Release verificado**: el APK no contiene `DebugFaultReceiver`, `DebugFaultState` ni las
  cadenas `blackhole de laboratorio` / `reset de laboratorio`; R8 las eliminó. El manifiesto
  release no declara el receptor.
- `lintVital` no se pudo ejecutar en modo offline: falta `com.android.tools.lint:lint-gradle:32.4.0`
  en la caché. Se compiló con `-x lintVitalAnalyze*`; no es un fallo de código. El ensamblado,
  R8 y el empaquetado terminaron correctamente.
- Las pruebas cubren las reglas puras (timeouts, reintentos, decisión de reanudación, sondeo,
  MTU, percentiles, KPIs y arbitraje). **No** sustituyen una prueba instrumentada del `Handler`
  ni una prueba de campo.

## 7. Plan de campo (TV + Moto G60s)

### Preparación

1. Instalar el APK debug de la marca y activar el HUD en Ajustes → Diagnóstico.
2. `adb connect <IP-TV>` y `adb shell run-as teletica.iptv.player ls files/diagnostics` para
   confirmar el archivo; al final, `adb pull /sdcard/Android/data/<paquete>/files/diagnostics/sessions.json`.
3. Anotar modelo, versión de Android, banda/RSSI/enlace del HUD y el perfil activo.
4. Línea base: mismo APK con `adaptiveTimeouts=false`, `inWindowRetries=false`,
   `nonDestructiveResume=false` y `unvalidatedRetry=false` (A/B real). Después repetir todo con
   las banderas ON.

### Escenarios (cada uno 10–15 min, dos veces: hora valle y hora pico)

| Escenario | Cómo | Qué mirar |
|---|---|---|
| Corte de 5 s | `blackhole` 5.000 ms o apagar hotspot 5 s | ¿rebuffer?, MTTR, ¿BEHIND? |
| Corte de 10 s | idem 10.000 ms | ¿el buffer de 8 s lo absorbe? |
| Corte de 15 s | idem 15.000 ms | ¿recarga o recupera? |
| Corte de 30 s | idem 30.000 ms | MTTR y recargas |
| Corte de 2 min | idem 120.000 ms | reintento lento 60 s, sin rendirse |
| Hotspot off/on | apagar y encender el hotspot del teléfono | antirrebote, reanudación no destructiva |
| Reconexión manual del túnel | cerrar/abrir HTTP Injector | 401/403, 5xx, blackhole, DNS |
| Modo ambiente | reproducir con la pantalla apagada / PiP | WifiLock, stalls/hora |
| Ver + grabar | grabar 10 min mientras se ve | rebuffers, ratio, ¿aviso? |
| Latencia bajo carga | dos descargas HTTPS en paralelo + stream | RTT p95, holgura, timeouts espurios |
| `loadlatency` | inyección 0,1→2→3,5 s | falsos positivos de timeout |

### Criterios comparables con la línea base

- **stalls/hora** y **ratio de rebuffer**: bajar ≥ 30 % sin subir el ratio.
- **MTTR** (corte → 15 s estable): ≤ 15 s en cortes de 5–15 s; ≤ 90 s en el corte de 2 min.
- **BEHIND_LIVE_WINDOW** y **recargas completas**: no aumentar; idealmente bajar.
- **Corte más largo superado sin rebuffer**: ≥ 8 s (buffer) y acercarse a 11 s.
- **RTT p95 bajo carga**: no empeorar; si se prueba `SO_RCVBUF`, debe bajar sin perder caudal.
- **Timeouts espurios**: 0 en los cortes de 5/10/15 s con RTT 0,1–1 s; con RTT 3,5 s, que el
  máximo de 5,5 s no corte una descarga que avanza.

### Lectura del HUD

```
Enlace: cap 5.8 Mbps · RTT p95 1.9s · holgura 2.9x · desc/seg 0.42 · fallos 3% · t/o 4.4s/5.4s
Variantes: 1 (sin variantes inferiores)  ·  Estabilidad: NORMAL
```

`holgura < 1` con OPA significa que el único perfil publicado no cabe en el enlace: no es un
fallo de la app y no hay calidad inferior que elegir. `desc/seg > 1` de forma sostenida es la
señal temprana de que el segmento tarda más que su duración.

## 8. Límites y honestidad

- La app no controla el módem, el túnel, la banda Wi-Fi ni el teléfono. Si el túnel entra en
  blackhole, la app solo puede detectarlo por timeout, acortar el reintento y reponer el directo.
- Si el proveedor publica una sola variante por encima de la capacidad (OPA), ninguna mejora de
  ABR lo arregla; hace falta que el proveedor publique una rendición menor o que el enlace suba.
- Todas las cifras de RTT, capacidad y holgura del HUD son mediciones de la app en la TV; las de
  este informe son externas y solo orientan.
- Nada de esta ronda se probó todavía en la Xiaomi TV A Pro ni en el Moto G60s.

## Correcciones a este documento (revisión 2026-10-30)

- Se corrigió la afirmación "máximo de seis intentos": el reinicio del stream no se detenía al
  sexto; ahora continúa con espera lenta de 60 s.
- Se corrigió la afirmación de que el tope de 2,2 Mbps depende solo de `NOT_METERED`: el perfil
  `Hotspot` también lo impone.
- Se corrigió la afirmación de que las grabaciones "no interfieren" con el video: no hay
  priorización de tráfico.
- Se documentó que el `WifiLock` no existía antes de esta revisión y que `WAKE_MODE_NETWORK` por sí
  solo no impide el ahorro de energía del Wi-Fi.
- El HUD incorpora el estado del enlace Wi-Fi (banda, frecuencia, RSSI, velocidad y estándar), que en
  la revisión anterior figuraba como propuesta pendiente.

## Correcciones de la ronda 2 (2026-10-30, tarde)

- Timeouts del perfil Hotspot: ya no son fijos de 20 s/25 s; son adaptativos (3,5–5 s de
  conexión, 4–5,5 s de lectura). Los 20 s/25 s quedan solo con la bandera `adaptiveTimeouts`
  en OFF.
- Reintentos de segmento: se sustituyó el backoff 1→30 s por un presupuesto corto
  (250 ms/900 ms, 4 s para 401/403, ninguno para 404/410). El backoff largo queda solo en
  `recoverStream()`.
- Vuelta de red: ya no hace `stop()` + recarga completa siempre; decide entre no tocar el
  reproductor, `prepare`+`seek` liviano o recarga, con antirrebote de 1,5 s.
- El sondeo de red ya no exige `VALIDATED`: con transporte e INTERNET usa el reproductor como
  sonda cada 8 s tras 12 s de gracia.
- Se midió el inventario real: Teletica y FUTV publican **una** variante y OPA **una**
  (no tres). Con una sola variante el ABR y los pisos de calidad no tienen efecto; el HUD lo
  dice.
- Se documentó el patrón "playlist OK / segmento colgado tras pocos KB" como sospecha de
  MTU/PMTUD (`MTU_SUSPECT`), sin cambiar la red de la TV.
- Se añadieron el informe de sesión, la medición por host y el laboratorio de fallos, que
  solo existe en el APK de depuración.

