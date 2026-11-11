# NexoIPTV

## Aplicación para Android TV

NexoIPTV comparte una aplicación Android TV configurable para distintas marcas, con variantes Teletica, FUTV y OPA. Ofrece reproducción HLS, guía XMLTV, búsqueda de programas, recordatorios y grabaciones desde una interfaz navegable con el mando.

El código original se distribuye bajo [MIT](LICENSE). Las atribuciones y licencias de material de terceros están en [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

Cada variante lleva su propio banner Leanback 16:9 e icono de launcher. Los
recursos de marca se encuentran bajo `app/src/teletica/res`, `app/src/futv/res` y
`app/src/opa/res`;
`app/src/main` contiene solo recursos y código compartidos.

### Ícono del launcher de Android TV

Los recursos del launcher se guardan dentro de cada variante: `mipmap-anydpi-v26`,
las carpetas `mipmap-*dpi`, y `drawable-nodpi/launcher_banner.*`. El foreground
adaptativo se centra con margen para que la máscara circular no recorte el arte.
En Teletica, el logo se obtuvo de [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Teletica_Logo.png),
donde figura como una marca simple publicada originalmente en teletica.com.

Al cambiar el logotipo, actualiza y revisa juntos estos recursos:

- `mipmap-anydpi-v26/ic_launcher.xml` y `ic_launcher_round.xml`: ambos deben usar
  el foreground y el fondo que correspondan a esa marca.
- Mantén los PNG de `mipmap-mdpi`, `hdpi`, `xhdpi`, `xxhdpi` y `xxxhdpi` con una
  escala visible coherente con el foreground adaptativo.
- `AndroidManifest.xml`: el ícono de `<application>` y de `MainActivity` debe ser
  `@mipmap/ic_launcher`. No agregues un `roundIcon` independiente con una escala
  distinta; la página principal puede consultar el ícono de la actividad.

El banner es un recurso separado y no sustituye el ícono circular. Después de
cambiar los recursos, compila e instala la variante correspondiente y comprueba
el resultado en el launcher principal de Android TV, no solo en “Todas las apps”.
Al publicar un cambio de ícono, aumenta `versionCode` para que la actualización
del paquete transporte la nueva identidad.

La fuente HLS inicial es:

`http://190.61.101.11:7050/play/a07t/index.m3u8`

La variante FUTV usa como fuente inicial:

`http://190.61.90.17:40000/play/a03v/index.m3u8`

La variante OPA usa por defecto la calidad 360p:

`https://5fc584f3f19c9.streamlock.net/genteopa/videogenteopa_360p/playlist.m3u8`

OPA obtiene su programación semanal de la página oficial `https://genteopa.com/programas/`;
los horarios recurrentes se proyectan para los próximos siete días. También se puede
configurar una fuente XMLTV desde Ajustes.

## Abrir y compilar

Requiere Android Studio con JDK 21 y un dispositivo Android TV con Android 6.0 (API 23) o posterior. El proyecto compila con SDK 37; actualmente declara `versionName` 1.0.6, `minSdk` 23 y `targetSdk` 37. Para programar grabaciones y recordatorios en Android 12+, concede a la aplicación el acceso especial a Alarmas y recordatorios cuando lo solicite. En Android 13+, permite notificaciones para ver el estado de grabación y recibir avisos. Para generar el APK de depuración desde Windows:

```powershell
.\utilities\gradle-local.bat assembleTeleticaDebug
```

### Variantes de marca

Teletica, FUTV y OPA se generan desde el mismo módulo y comparten el código de `app/src/main`.
Teletica conserva el identificador `teletica.iptv.player` para permitir actualizaciones;
FUTV usa `teletica.iptv.player.futv` y OPA usa `teletica.iptv.player.opa`; las tres pueden
instalarse al mismo tiempo. El nombre
visible de cada app se define en `app/src/<marca>/res/values/brand.xml`.

```powershell
.\utilities\gradle-local.bat assembleTeleticaDebug
.\utilities\gradle-local.bat assembleFutvDebug
.\utilities\gradle-local.bat assembleOpaDebug
.\utilities\gradle-local.bat assembleTeleticaRelease
.\utilities\gradle-local.bat assembleFutvRelease
.\utilities\gradle-local.bat assembleOpaRelease
```

Los recursos que todavía no tengan una versión por marca se comparten desde `main`.
Los logos, iconos y banners se encuentran en la carpeta `res` de cada variante.
Cada variante toma sus recursos de marca y comparte el resto del código.

Los APK se generan en `app/build/outputs/apk/<marca>/debug/`. Abre la carpeta del proyecto en Android Studio y elige la variante desde **Build Variants**.

Para ejecutar las pruebas unitarias disponibles:

```powershell
.\utilities\gradle-local.bat test
```

GitHub Actions ejecuta las pruebas unitarias, compila el APK de depuración y el APK de pruebas, y corre una prueba instrumental de humo en un emulador Android 15.

### Diagnóstico de Gradle en Windows: `Permission denied: getsockopt`

Si `Could not connect to the Gradle daemon` o `Permission denied: getsockopt` aparece antes de `:app:compileTeleticaDebugKotlin`, Kotlin todavía no empezó: el cliente no pudo conectar al daemon principal por TCP en `127.0.0.1`. Si aparece después de la tarea Kotlin, dentro de `:app:compileTeleticaDebugJavaWithJavac` o de pruebas y con `Failed to run Gradle Worker Daemon`, Kotlin pudo compilar; el socket bloqueado pertenece a un Worker Daemon separado. Son fallos de loopback local, no necesariamente errores de código.

La configuración permanente fija `org.gradle.jvmargs` en `gradle.properties` con los mismos argumentos que `DEFAULT_JVM_OPTS` de `gradlew.bat` y `JAVA_OPTS` de `compile-local.ps1`: `-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx512m -Dfile.encoding=UTF-8`. El script local conserva exactamente esa lista. No agregues opciones solo al cliente (por ejemplo, `-XX:HeapDumpPath`) ni cambies solo su `-Xmx`: Gradle puede detectar la diferencia y crear un daemon de un solo uso incluso con `--no-daemon`. Ese `HeapDumpPath` exclusivo del cliente fue la causa comprobada y se quitó el 2026-10-29. También se desactivan el daemon persistente, Kotlin daemon, paralelismo, configuration cache y file watching; Kotlin se ejecuta in-process. La caché de configuración sigue desactivada porque Kotlin con Java 21 falló al serializar `Collections$SingletonMap`. El Wrapper pide Gradle 9.8.2; su ZIP debe estar completo y coincidir con el `distributionSha256Sum` de `gradle/wrapper/gradle-wrapper.properties`.

**Validación (2026-10-29):** después de mover los paquetes, `:app:compileTeleticaDebugKotlin`, `:app:compileFutvDebugKotlin` y `:app:compileOpaDebugKotlin` terminaron con `BUILD SUCCESSFUL`. Antes de esos movimientos, el usuario había confirmado `:app:testFutvDebugUnitTest` con 45/45 pruebas aprobadas. No fue posible repetirla después del refactor desde la ejecución automatizada: `:app:compileFutvDebugJavaWithJavac` falló al conectar el Worker Daemon con loopback antes de compilar las fuentes unitarias. La alineación JVM resuelve el daemon principal, pero no anula firewall, aislamiento del entorno ni permisos de loopback para workers. Para validar javac/pruebas, usa la consola interactiva de la cuenta que compila. La reparación de Windows indicada abajo es independiente del proyecto, requiere administrador y puede requerir reinicio.

**Validación (2026-10-30):** `compile-local.cmd -Safe` terminó con `BUILD SUCCESSFUL` en modo offline y ejecutó `:app:clean :app:assembleTeleticaDebug`. Se generó `app/build/outputs/apk/teletica/debug/app-teletica-debug.apk` (13,701,997 bytes). Esta ejecución no confirma que el permiso de loopback quedara reparado: el preflight todavía reportó acceso denegado a `127.0.0.1`, y `:app:compileTeleticaDebugJavaWithJavac` apareció como `FROM-CACHE`, de modo que Gradle reutilizó la salida de Javac en vez de volver a ejecutarlo. El ensamblado y el empaquetado del APK sí terminaron correctamente.

Para reparar Windows, abre **PowerShell como administrador**, ejecuta `netsh winsock reset` y `netsh int ip reset`, y crea reglas de firewall limitadas a Java y a loopback IPv4:

```powershell
$java  = 'C:\Java\jdk-21.0.11+10\bin\java.exe'
$javaw = 'C:\Java\jdk-21.0.11+10\bin\javaw.exe'

foreach ($r in @(
  @{N='NexoTV-Gradle-Java-Loopback-In';   D='Inbound';  P=$java},
  @{N='NexoTV-Gradle-Java-Loopback-Out';  D='Outbound'; P=$java},
  @{N='NexoTV-Gradle-Javaw-Loopback-In';  D='Inbound';  P=$javaw},
  @{N='NexoTV-Gradle-Javaw-Loopback-Out'; D='Outbound'; P=$javaw}
)) {
  Remove-NetFirewallRule -DisplayName $r.N -ErrorAction SilentlyContinue
  New-NetFirewallRule -DisplayName $r.N -Direction $r.D -Program $r.P `
    -Protocol TCP -LocalAddress 127.0.0.1 -RemoteAddress 127.0.0.1 `
    -Action Allow -Enabled True -Profile Any -EdgeTraversalPolicy Block
}
```

Comprueba las reglas con `Get-NetFirewallRule -DisplayName 'NexoTV-Gradle-*'` y reinicia Windows. Si aparece `Acceso denegado` o `La operación solicitada requiere elevación`, la consola no está elevada y los cambios no se aplicaron. Antivirus, EDR o políticas corporativas también pueden bloquear `java.exe`; en ese caso permite `java.exe` y `javaw.exe` en loopback. Después prueba desde una consola del mismo usuario que usa Android Studio:

```powershell
cd C:\Users\johnny\Documents\NexoTV
.\utilities\gradle-local.bat :app:compileTeleticaDebugKotlin --offline --no-daemon --stacktrace
```

Si la ejecución automatizada usa otro perfil de Windows, su caché de Gradle (`%USERPROFILE%\.gradle`) y sus reglas pueden diferir de las de `johnny`; valida desde la consola interactiva del usuario que compila. Un error de socket **antes** de `> Task :app:compileTeleticaDebugKotlin` significa que Kotlin no empezó. Si sucede en `compileTeleticaDebugJavaWithJavac` o en un Worker Daemon de pruebas, Kotlin sí pudo terminar; revisa la tarea exacta antes de tratarlo como un error de código.

`compile-local.ps1` clasifica desde ahora los errores de loopback que incluyen `GradleWorkerMain` o `SystemApplicationClassLoaderWorker` como `FALLO_WORKER_LOOPBACK`, separados de `FALLO_CONEXION_DAEMON` del proceso principal.

Para firmar una versión de lanzamiento, proporciona estas variables de entorno al ejecutar Gradle: `TVIPTV_RELEASE_STORE_FILE`, `TVIPTV_RELEASE_STORE_PASSWORD`, `TVIPTV_RELEASE_KEY_ALIAS` y `TVIPTV_RELEASE_KEY_PASSWORD`. Las tareas de release fallan si falta alguna variable o el archivo de claves no existe. Mantén el keystore y sus contraseñas fuera del repositorio.

En Más → Ajustes → Imagen y sonido se puede cambiar la fuente por una URL HTTP/HTTPS compatible con HLS, añadir encabezados HTTP opcionales, probar el manifiesto, la variante de mayor bitrate y el acceso al primer segmento, y restaurar la predeterminada. La URL y los encabezados se usan en la reproducción interna y en nuevas grabaciones; las grabaciones activas conservan lo que recibieron al comenzar. Los reproductores externos podrían ignorar encabezados. La copia de ajustes incluye URL y encabezados (pueden contener credenciales); el informe de diagnóstico muestra solo el dominio para evitar compartir parámetros privados.

La aplicación usa Media3/ExoPlayer con soporte HLS, reproducción a pantalla completa y reconexión automática. Tras seis intentos rápidos con espera creciente (1 s a 30 s) continúa con un reintento lento de 60 s ±20 %: un corte prolongado del teléfono se recupera solo, sin reintentar cada 30 s. El contador de intentos se reinicia al recuperar señal. Detecta congelamientos y buffering atascado, vuelve al borde en vivo tras errores de ventana HLS, conserva el último cuadro al reintentar, y usa un buffer de recuperación mayor para soportar cortes breves. Si vuelve Internet tras una desconexión, reanuda sin destruir el reproductor cuando el buffer sigue sano. Activa la recuperación automática si un decodificador falla. También admite redirecciones entre HTTP/HTTPS, identifica el cliente como Android TV y muestra resolución/FPS/cuadros perdidos al presionar OK. Cuando la TV ofrece un modo de pantalla que coincide con los FPS, la app lo selecciona y lo restaura al salir o ir a segundo plano. La pantalla se mantiene activa solo mientras el reproductor está reproduciendo. Al volver de segundo plano restaura la reproducción si estaba activa; al reiniciar la app, vuelve a abrir la fuente configurada. Las grabaciones se detienen al quedar menos de 512 MB libres para proteger el almacenamiento del televisor.

El menú de opciones se abre con MENU/SETTINGS o manteniendo OK. La pantalla principal ofrece En vivo, Guía, Buscar, Mis grabaciones y Más, además de Grabar ahora; Mi lista está dentro de Guía. Los submenús se organizan por tarea: guía y programas, grabaciones, reproducción y sonido, ajustes, y diagnóstico. Los menús usan filas grandes y descripciones; Atrás recorre las pantallas visitadas y restaura el foco del mando. Los selectores y confirmaciones ajustan el ancho y altura para D-pad. Los campos de búsqueda, fuente XMLTV, User-Agent y restauración de ajustes tienen mayor tamaño y contraste. Calidad ofrece Auto y las pistas de video publicadas por el HLS. Los perfiles de buffer Small/Medium/Large, la preferencia Hardware/Software y passthrough surround se aplican al reconstruir el reproductor; los valores iniciales son Hotspot, Hardware y passthrough apagado. Surround automático intenta escoger la pista de audio con más canales si no elegiste una manualmente. La selección manual de pista de audio se conserva al reconectar y al volver a iniciar la app si el HLS vuelve a anunciar la misma pista; los subtítulos se pueden escoger o desactivar cuando el HLS los publica. Apariencia permite elegir el tiempo del panel (2/5/8/10 s), transparencia (0/10/25/50%), tamaño de texto (20/21/22/24 sp), reloj visible y formato horario de 12/24 h. La copia de ajustes exporta JSON compartible e importa desde texto pegado o desde un archivo JSON/texto elegido con el selector de documentos de Android; existe además un respaldo automático local opcional. Al pausar o reanudar con el mando aparece un panel con el programa actual, progreso, siguiente emisión y datos básicos de video/audio. El panel de estado incluye resolución, FPS, códec y bitrate declarado por el manifiesto, formato y canales de audio, buffer, decodificador, cuadros perdidos y causa del último reintento; durante reintentos muestra los segundos restantes. Media3 no expone un bitrate de red instantáneo fiable de forma general para este HLS, así que el dato se identifica como declarado, no medido. Exportar diagnóstico prepara un texto compartible con esos datos, versión de Android y modelo del dispositivo. PiP, selección de relación de aspecto, sincronización de audio, silenciamiento, reproductor externo y asignación de teclas del mando también se pueden configurar. Los controles Play, Pause y Play/Pause comparten la lógica de recuperación; OK o «Reintentar ahora» interrumpe la espera y vuelve a conectar.

El menú principal conserva la sección de origen para volver al mismo acceso; al salir del flujo, una segunda pulsación confirma la salida (comportamiento configurable). Los estados vacíos de Programaciones y resultados de búsqueda llevan directamente a la guía, a actualizarla o a repetir la búsqueda.

La grabación usa un servicio en primer plano con notificación para detenerla, sigue la variante HLS de mayor bitrate y concatena segmentos nuevos en orden (incluida la inicialización de fMP4). Si la lista HLS usa cifrado, rangos de bytes, discontinuidades, segmentos parciales o baja latencia que el capturador no puede conservar, la grabación se detiene con un aviso explícito en vez de producir una captura incompleta sin explicación. Se puede elegir la memoria del televisor o una carpeta USB como destino; la biblioteca muestra el espacio disponible y permite administrar las capturas del almacenamiento seleccionado. Al eliminar grabaciones multipartes comprueba el resultado de cada archivo y avisa si el acceso al USB impide completar la operación. El margen de las grabaciones programadas se puede configurar por separado antes y después del horario XMLTV (0–15 minutos por lado; inicialmente sin margen); las alarmas se actualizan al cambiarlo y los conflictos consideran la ventana ampliada. Si una grabación manual sigue activa cuando empieza una programada, la app conserva la grabación activa y avisa que no pudo iniciar la segunda. La grabación más reciente se puede abrir desde el menú Grabaciones o la biblioteca. Los archivos se guardan en el almacenamiento privado de la aplicación o en el destino seleccionado. Los saltos configurables solo se ofrecen cuando Media3 informa que el HLS actual tiene ventana seekable; si no hay DVR, no se finge que exista contenido anterior. «Abrir en otro reproductor» entrega la fuente configurada mediante `ACTION_VIEW` y pausa el reproductor interno.

La variante Teletica usa por defecto su programación oficial. FUTV carga primero la programación diaria de la portada y usa la tabla semanal de `https://futvcr.com/programacion/` como alternativa. FUTV también permite configurar una fuente XMLTV. La portada publica títulos y horarios; la tabla semanal publica títulos y franjas horarias, pero no descripción ni imagen por emisión; esos datos faltantes no se inventan y las emisiones ocupan los bloques horarios publicados. Esta lectura depende del formato HTML del sitio; si cambia, se puede usar una URL XMLTV como fuente. La guía acepta feeds comprimidos con GZIP o XZ; la frecuencia de actualización, los días pasados incluidos y la anticipación de recordatorios se pueden configurar. En Android TV 8.0 (API 26) o posterior, «Añadir al inicio» crea una fila con acceso directo a la emisión. El título muestra el programa actual según XMLTV y se actualiza al cambiar de programa, actualizar la guía, abrir la app o reiniciar el televisor. La función no está disponible en versiones anteriores.

La búsqueda de la guía acepta texto o voz desde «Buscar por voz» y conserva las ocho búsquedas recientes. La voz usa el reconocedor disponible en el televisor y pide permiso de micrófono la primera vez; si no hay reconocedor, sigue disponible la búsqueda escrita. Cada variante puede abrirse al encender o al despertar el televisor, y entrar en PiP al salir a la pantalla de inicio si se activa esa opción. Los estados vacíos de Programaciones y búsqueda ofrecen acciones directas para abrir la guía, actualizarla o repetir la búsqueda.

NexoIPTV reproduce un único canal a la vez y permite configurar la URL de su señal. Incluye guía XMLTV, búsqueda de programas por título o descripción, Mi lista para guardar emisiones, recordatorios, grabación inmediata y programada, biblioteca de grabaciones, PiP, controles de pistas, ajustes de reproducción y diagnóstico. La interfaz está pensada para televisor: usa pestañas amplias para cambiar de día, una tarjeta destacada para la emisión actual y filas de programas con horario, estado y descripción; el foco del mando queda marcado con borde y contraste. Al cambiar de fecha, el foco vuelve a la pestaña seleccionada aunque la guía reconstruya las filas. La tarjeta destacada actualiza cada 30 segundos el programa al aire, el siguiente y el progreso; el temporizador se cancela al cerrar el panel. El reloj y los avisos superpuestos usan superficies redondeadas y espaciado adaptado a la densidad de pantalla; los mensajes breves se muestran en una cápsula legible dentro de la app, con anuncio accesible y sin foco de mando. Si ya hay un panel de reproducción abierto, la cápsula se coloca arriba para no taparlo. La guía ofrece una agenda de siete días, resalta el programa al aire y el siguiente con una barra de progreso; la tarjeta destacada se selecciona con el mando para abrir detalles y acciones, y cada emisión permite guardar, activar un recordatorio o programar la grabación. Los resultados de búsqueda, Mi lista, programaciones, búsquedas recientes y grabaciones usan tarjetas grandes seleccionables con el mando y datos secundarios legibles; la biblioteca intenta mostrar el nombre del programa según la hora de inicio de cada captura. Mi lista conserva programas aunque se actualice la guía; buscar recorre los programas futuros disponibles en la guía descargada.

## Red LTE / hotspot medida

Las mediciones compartidas para el hotspot usado durante las pruebas indican un enlace Wi-Fi local de 2,4 GHz 802.11n, señal fuerte y cerca de 72 Mbps de velocidad de enlace. El gateway respondió sin pérdida, con unos 2 ms de media. Hacia Internet se midieron aproximadamente 5,5–6,3 Mbps de descarga y unos 114 ms de latencia en reposo a un servidor de prueba en Costa Rica. Durante una descarga, la latencia aumentó a cerca de 1,9 s de media, con picos de 3,4 s y jitter de hasta 250 ms. Son mediciones puntuales: pueden variar con la cobertura móvil, la congestión y otros dispositivos que compartan la conexión.

El proveedor no respondió a las pruebas ICMP externas, aunque TCP por el puerto 443 y HTTPS sí funcionaron. Por tanto, que `ping` o `pathping` no respondan fuera del gateway no demuestra por sí solo que Internet esté caído. Speedtest terminó con un error de socket, mientras que dos descargas HTTPS de prueba completaron cerca de 5,5–6,2 Mbps; el error de Speedtest no debe interpretarse automáticamente como pérdida total de conectividad.

El principal riesgo para IPTV en esta red es la latencia extrema bajo carga (bufferbloat), no la señal Wi-Fi local. La configuración Hotspot limita el bitrate de video a 2,2 Mbps y usa una estimación inicial conservadora de 800 kbps. Desde la ronda 2 los timeouts HTTP son adaptativos: conexión 3,5–5 s y lectura 4–5,5 s, calculados como `clamp(1,5 × RTT_p95 + margen, mínimo, máximo)`; sin muestras usa un RTT intermedio de 250 ms (3,5 s de conexión y 4 s de lectura). El máximo queda por debajo de la mitad de la ventana medida (~11,5 s) para que un segmento colgado no agote el buffer de 8 s. Los reintentos de segmento son cortos y conscientes de la ventana (250 ms y 900 ms; un solo reintento a 4 s para 401/403; ninguno para 404/410) y al agotarse escalan a una recarga reposicionada en el borde del directo. El backoff largo (1→30 s y luego 60 s) queda solo para esa recuperación completa.

### Inventario de los manifiestos (medido el 2026-10-30 desde un PC, no desde la TV)

| Marca | Variantes | `BANDWIDTH` | `AVERAGE-BANDWIDTH` | `EXT-X-TARGETDURATION` | Segmentos | Ventana |
|---|---:|---:|---:|---:|---|---:|
| Teletica (a07t) | 1 | 1,96–1,98 Mbps | 1,77 Mbps | 3 s | 4 × 2,77–2,97 s | ≈11,5 s |
| FUTV (a03v) | 1 | 1,47 Mbps | 1,38 Mbps | 3 s | 4 × 2,94 s | ≈11,7 s |
| OPA | 1 | 6,99 Mbps | — | 13 s | 3 × 10,01 s | ≈30,0 s |

- La fuente de ~1,89 Mbps que describía este README corresponde a Teletica; FUTV publica menos bitrate. **OPA publica hoy una sola rendición de 1080p, no tres**: un segmento real midió ~7,9 MB en 10 s (~6,3 Mbps), por encima de la capacidad medida del enlace. Con una sola variante no hay ABR ni piso de calidad al que bajar; el HUD escribe "sin variantes inferiores" en lugar de aparentar ayuda. El bloque de adaptación (ABR, degradación preventiva) no tiene efecto mientras el proveedor no publique más rendiciones.
- La variante Teletica caduca los segmentos en segundos: pasado ese punto el servidor puede responder `HTTP 200` con un cuerpo TS de ~188 bytes en lugar de un 404. La app no puede tratar "200 con cuerpo corto" como un 404 definitivo; el patrón se registra como fallo de segmento y, si una lista pequeña funcionó mientras el segmento se colgó tras pocos KB, se marca como **posible MTU/PMTUD** (sospecha, no diagnóstico).
- La ventana de OPA es de ~30 s; el perfil Hotspot mantiene su buffer de 8–10 s para no salir de la ventana de Teletica/FUTV, así que en OPA se descarta margen disponible. Es una decisión conservadora porque el perfil es compartido.

Las actualizaciones automáticas y manuales de la guía se aplazan mientras se reproduce para no competir por el enlace y se reanudan al pausar. La EPG solicita respuestas HTTP gzip y limita el tamaño descomprimido; las imágenes de guía y grabaciones comparten caché, deduplicación y una cola de descarga pequeña para no crear ráfagas paralelas. La grabación sigue consumiendo el mismo enlace (ver + grabar ≈ 2 × bitrate): la app no la pausa por su cuenta.

### Medición y recuperación desde la app (ronda 2)

- **Informe de sesión:** cada sesión guarda tiempo hasta el primer frame, rebuffers y su duración, ratio de rebuffer, stalls/hora, `BEHIND_LIVE_WINDOW`, recargas completas, MTTR (corte → 15 s estable), corte más largo superado sin rebuffer y bytes consumidos. Se conservan las últimas 20 sesiones (≤ 1 MiB) en `Android/data/<paquete>/files/diagnostics/sessions.json`, sin SSID/BSSID/ubicación y con el host como `host:puerto`. Se extrae con `adb pull` en el APK de depuración.
- **Medición por host:** ventanas móviles de 2 y 15 minutos de RTT/TTFB de listas y segmentos (p50/p95/p99), bytes/s sostenidos, relación descarga/duración del segmento y tasa de fallos. El HUD muestra capacidad sostenida estimada, RTT p95, holgura frente al bitrate declarado y los timeouts aplicados.
- **Reconexión no destructiva:** al volver una red validada, si el reproductor sigue en READY con ≥ 4 s de buffer no se toca; si está en BUFFERING con progreso reciente se hace `prepare` + `seekToDefaultPosition`; solo sin progreso durante 8 s se recarga completo. Hay un antirrebote de 1,5 s y un arbitraje que impide que el watchdog y la recuperación dupliquen tareas.
- **Red sin `VALIDATED`:** si Android informa transporte e INTERNET pero no valida la red, la app usa el reproductor como sonda cada 8 s tras 12 s de gracia, sin ICMP ni `StreamReachabilityProbe`. El HUD sigue diciendo "sin validación" para no ocultar el estado.
- **Laboratorio solo en depuración:** el APK debug acepta `am broadcast` para inyectar blackhole, reset, latencia fija, throttle, fallo de DNS y latencia que crece bajo carga, y para activar o desactivar las banderas de la ronda 2. El release no incluye el receptor ni el código de fallos.

Las mediciones se hicieron desde un PC conectado al mismo hotspot y orientan, pero no garantizan, el rendimiento de un Android TV: el dispositivo, la señal móvil y el tráfico compartido pueden ser distintos. Para comparar cambios, registra descarga y latencia en reposo y bajo carga, además de los rebuffers y el buffer que muestra el diagnóstico de la app. No uses la falta de respuesta ICMP como única señal para activar una recuperación de red.

## Alcance y aspectos pendientes

NexoIPTV reproduce un solo canal a la vez; no incluye playlists, grupos, zapping entre canales, multiview ni catch-up por URL de archivo. Los saltos hacia atrás solo están disponibles cuando el manifiesto HLS expone una ventana DVR reproducible. Las pruebas unitarias cubren ventanas de grabación y validación de playlists HLS; una prueba instrumental comprueba que la pantalla principal se crea sin conectar al canal. El repositorio tiene CI, licencia MIT, avisos de material de terceros y configuración para firmar lanzamientos, pero para firmar se necesita un keystore privado. Antes de distribuir, falta validar la app en televisores y unidades USB objetivo y revisar el inventario de licencias transitivas de cada versión.
