package com.nguevara.obdpulse.obd

import com.nguevara.obdpulse.bt.ObdTransport
import com.nguevara.obdpulse.bt.TransportException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import java.util.Locale

/** Línea de la terminal: lo que se envió (TX) o lo que respondió el adaptador (RX). */
data class TermLine(val dir: Dir, val text: String, val time: Long = System.currentTimeMillis()) {
    enum class Dir { TX, RX, INFO }
}

/** Datos del adaptador y del vehículo obtenidos al conectar. */
data class VehicleInfo(
    val elmVersion: String,
    val protocol: String,
    val protocolNumber: String,
    val ecuOk: Boolean,
    val ecuMessage: String?,
    val supportedPids: Set<Int>,
    val vin: String?,
    /** Protocolos probados al conectar, en orden: (código ATSP, resultado corto). */
    val attempts: List<Pair<String, String>> = emptyList(),
)

/** Resultado de probar un protocolo en el escaneo completo. */
data class ProtocolProbe(val code: String, val name: String, val ok: Boolean, val detail: String)

/** Un módulo (ECU) que respondió, con su información del modo 09. Solo lectura. */
data class EcuModule(
    val address: String,
    val name: String?,
    val calibrationIds: List<String>,
    val cvns: List<String>,
    val infoTypes: Set<Int>,
    val vin: String?,
    val supportsMode06: Boolean,
    val supportsMode08: Boolean,
    val pidCount: Int,
) {
    /** Tiene software con identificador de calibración: el fabricante puede reprogramarlo con su herramienta. */
    val hasCalibration: Boolean get() = calibrationIds.isNotEmpty() || cvns.isNotEmpty()
}

/**
 * Trama de datos ya limpia.
 * [index] es el número de trama ISO-TP ("0:", "1:"…) cuando la respuesta es multi-trama en CAN;
 * es null en respuestas de una sola línea.
 */
data class Frame(val index: Int?, val hex: String) {
    val bytes: IntArray by lazy { Elm327.hexToInts(hex) }
}

/**
 * Sesión con un adaptador ELM327.
 *
 * El ELM327 es un "intérprete": recibe texto ASCII terminado en '\r' y responde texto
 * terminado con el prompt '>'. Esta clase:
 *  1. Serializa los comandos (uno a la vez, protegido por [mutex]).
 *  2. Acumula los bytes hasta ver '>'.
 *  3. Limpia la respuesta (eco, "SEARCHING...", índices de trama) y la convierte en bytes.
 */
class Elm327(private val transport: ObdTransport) {

    private val mutex = Mutex()

    // replay: la terminal recibe también las líneas emitidas antes de suscribirse (p. ej. la inicialización).
    private val _log = MutableSharedFlow<TermLine>(
        replay = 100,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val log: SharedFlow<TermLine> = _log.asSharedFlow()

    /** Número de protocolo reportado por ATDPN, sin la "A" de automático (ej. "6"). */
    var protocolNumber: String = ""
        private set

    /** Comandos seguidos que terminaron sin el prompt '>'. Si crece, el adaptador está colgado. */
    @Volatile
    var consecutiveTimeouts: Int = 0
        private set

    /** Protocolos CAN: 6, 7, 8, 9 (y B, C configurables por el usuario). */
    val isCan: Boolean
        get() = protocolNumber in setOf("6", "7", "8", "9", "B", "C")

    // ------------------------------------------------------------------
    // Envío de comandos
    // ------------------------------------------------------------------

    /**
     * Envía [command] + '\r' y devuelve el texto recibido antes del prompt '>'.
     * Lanza [TransportException] si el enlace Bluetooth se cae.
     * Si se agota [timeoutMs], devuelve lo que haya llegado (posiblemente vacío).
     */
    suspend fun send(command: String, timeoutMs: Long = 3_000, logIt: Boolean = false): String = mutex.withLock {
        drainStale()
        if (logIt) _log.tryEmit(TermLine(TermLine.Dir.TX, command))

        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))

        val received = StringBuilder()
        val gotPrompt = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val chunk = try {
                    transport.incoming.receive()
                } catch (e: ClosedReceiveChannelException) {
                    throw TransportException("Se perdió la conexión con el adaptador", e)
                }
                // ISO-8859-1: cada byte se convierte en exactamente un carácter.
                received.append(String(chunk, Charsets.ISO_8859_1))
                if (received.indexOf(">") >= 0) break
            }
            true
        }

        val text = received.toString().substringBefore('>').replace("\u0000", "")
        if (gotPrompt == null) {
            consecutiveTimeouts++
            if (logIt) _log.tryEmit(TermLine(TermLine.Dir.RX, text.trim() + "  [sin respuesta: tiempo agotado]"))
            // Dar tiempo a que llegue el '>' rezagado y descartarlo para no contaminar el siguiente comando.
            delay(250)
            drainStale()
        } else {
            consecutiveTimeouts = 0
            if (logIt) _log.tryEmit(TermLine(TermLine.Dir.RX, text.trim().ifEmpty { "(vacío)" }))
        }
        text
    }

    fun info(text: String) {
        _log.tryEmit(TermLine(TermLine.Dir.INFO, text))
    }

    /** Descarta bytes que hayan quedado en el canal de una respuesta anterior. */
    private fun drainStale() {
        while (true) {
            val r = transport.incoming.tryReceive()
            if (r.isClosed) throw TransportException("Se perdió la conexión con el adaptador")
            if (r.isFailure) break
        }
    }

    // ------------------------------------------------------------------
    // Inicialización
    // ------------------------------------------------------------------

    /**
     * Secuencia de arranque:
     *  ATZ    reinicio
     *  ATE0   sin eco          ATL0  sin saltos de línea extra
     *  ATS0   sin espacios     ATH0  sin cabeceras CAN
     *  ATAT1  temporización adaptativa
     *  ATSPx  protocolo (0 = automático)
     *  0100   primera consulta: obliga al ELM a encontrar el protocolo del carro
     */
    suspend fun initialize(protocol: String, onStep: (String) -> Unit): VehicleInfo =
        initialize(listOf(protocol), slowEcu = false, onStep = onStep)

    /**
     * Igual que la anterior, pero prueba varios protocolos en orden hasta que la ECU responda.
     * [candidates] viene del perfil del vehículo (ver ProtocolAdvisor), p. ej. ["4", "5", "3", "0"]
     * para una Nissan diésel con K-Line. [slowEcu] amplía el tiempo de espera (ATST FF) para ECUs
     * diésel antiguas que tardan en contestar; con ATAT1 el ELM327 lo reduce solo cuando aprende.
     */
    suspend fun initialize(candidates: List<String>, slowEcu: Boolean, onStep: (String) -> Unit): VehicleInfo {
        onStep("Reiniciando el adaptador…")
        send("ATZ", 5_000, logIt = true)
        delay(400)

        onStep("Configurando el adaptador…")
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) send(cmd, 2_000, logIt = true)
        if (slowEcu) send("ATSTFF", 2_000, logIt = true)

        val atiLines = lines(send("ATI", 2_000, logIt = true))
        val version = atiLines.lastOrNull { it.contains("ELM", ignoreCase = true) }
            ?: atiLines.lastOrNull()
            ?: "ELM327"

        val list = candidates.map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { listOf("0") }
        val attempts = mutableListOf<Pair<String, String>>()
        var ecuOk = false
        var lastRaw = ""
        for ((i, p) in list.withIndex()) {
            val label = if (p == "0") "automático" else "${protocolName(p)} (ATSP$p)"
            onStep(if (list.size == 1) "Buscando el protocolo del vehículo…" else "Probando protocolo $label · ${i + 1}/${list.size}")
            if (i > 0) send("ATPC", 2_000, logIt = true) // cerrar el intento anterior
            send("ATSP$p", 2_000, logIt = true)
            lastRaw = send("0100", firstQueryTimeout(p), logIt = true)
            ecuOk = dataLines(lastRaw, "0100").any { it.hex.startsWith("4100") }
            attempts += p to (if (ecuOk) "Responde" else shortFailure(lastRaw))
            if (ecuOk) break
        }

        val ecuMessage = if (ecuOk) null else failureMessage(lastRaw, list)

        val dpn = lines(send("ATDPN", 2_000, logIt = true)).lastOrNull()?.trim().orEmpty()
        protocolNumber = dpn.uppercase(Locale.US).removePrefix("A")
        val protocolName = lines(send("ATDP", 2_000, logIt = true)).lastOrNull()?.trim().orEmpty()
            .ifEmpty { "Desconocido" }

        var supported: Set<Int> = emptySet()
        var vin: String? = null
        if (ecuOk) {
            onStep("Leyendo sensores disponibles…")
            supported = supportedPids()
            onStep("Leyendo VIN…")
            vin = readVin()
        }

        return VehicleInfo(
            elmVersion = version,
            protocol = protocolName,
            protocolNumber = protocolNumber,
            ecuOk = ecuOk,
            ecuMessage = ecuMessage,
            supportedPids = supported,
            vin = vin,
            attempts = attempts,
        )
    }

    /**
     * Escaneo completo: prueba TODOS los protocolos del ELM327 uno por uno y reporta cuáles
     * responden. Sirve para carros que no aparecen en la base de datos.
     */
    suspend fun scanProtocols(onStep: (String) -> Unit): List<ProtocolProbe> {
        val order = listOf("6", "8", "7", "9", "5", "4", "3", "1", "2", "A")
        val out = mutableListOf<ProtocolProbe>()
        for ((i, p) in order.withIndex()) {
            onStep("Probando ${protocolName(p)} · ${i + 1}/${order.size}")
            send("ATPC", 2_000, logIt = true)
            send("ATSP$p", 2_000, logIt = true)
            val raw = send("0100", firstQueryTimeout(p), logIt = true)
            val ok = dataLines(raw, "0100").any { it.hex.startsWith("4100") }
            out += ProtocolProbe(p, protocolName(p), ok, if (ok) "Responde" else shortFailure(raw))
        }
        send("ATPC", 2_000, logIt = true)
        return out
    }

    private fun firstQueryTimeout(p: String): Long = when (p) {
        "0" -> 20_000          // la búsqueda automática recorre varios protocolos
        "3", "4", "5" -> 12_000 // K-Line: el inicio lento a 5 baudios tarda ~3 s
        "1", "2" -> 8_000
        else -> 6_000           // CAN responde rápido
    }

    private fun shortFailure(raw: String): String {
        val u = raw.uppercase(Locale.US)
        return when {
            "BUS INIT" in u && "ERROR" in u -> "Error de inicio K-Line"
            "UNABLE" in u -> "Sin conexión con la ECU"
            "CAN ERROR" in u -> "Error de bus CAN"
            "BUS ERROR" in u -> "Error de bus"
            "NO DATA" in u -> "Sin datos"
            "STOPPED" in u -> "Interrumpido"
            raw.isBlank() -> "Sin respuesta"
            else -> raw.trim().replace("\r", " ").take(30)
        }
    }

    private fun failureMessage(raw: String, tried: List<String>): String {
        val u = raw.uppercase(Locale.US)
        val base = when {
            "UNABLE" in u -> "El adaptador no logró comunicarse con la ECU. Pon el encendido en ON o arranca el motor."
            "CAN ERROR" in u -> "Error en el bus CAN. Prueba otro protocolo o el escaneo completo."
            "BUS INIT" in u && "ERROR" in u -> "Falló la inicialización del bus K-Line. Arranca el motor e inténtalo de nuevo."
            "NO DATA" in u -> "La ECU no respondió. Pon el encendido en ON o arranca el motor."
            raw.isBlank() -> "El adaptador no respondió a tiempo."
            else -> "Respuesta inesperada de la ECU: ${raw.trim().take(60)}"
        }
        return if (tried.size > 1) "$base Se probaron ${tried.size} protocolos." else base
    }

    // ------------------------------------------------------------------
    // Modo 01: datos en vivo
    // ------------------------------------------------------------------

    /**
     * PIDs soportados. Se piden los mapas de bits 0100, 0120, 0140… Cada respuesta trae
     * 4 bytes (32 bits); el bit más significativo del byte A corresponde al PID base+1.
     *
     *   Ejemplo: 41 00 BE 3E B8 13
     *            │  │  └─ A=BE=1011 1110 → PIDs 01, 03, 04, 05, 06, 07
     *            │  └──── PID consultado (00)
     *            └─────── 0x40 + modo 01
     *
     * Si varias ECUs responden (motor y transmisión), se combinan sus mapas con OR.
     */
    suspend fun supportedPids(): Set<Int> {
        val result = sortedSetOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val responses = queryMode01(base, timeoutMs = 4_000)
            if (responses.isEmpty()) break
            for (d in responses) {
                if (d.size < 4) continue
                val mask: Long = (d[0].toLong() shl 24) or
                    (d[1].toLong() shl 16) or
                    (d[2].toLong() shl 8) or
                    d[3].toLong()
                for (i in 0 until 32) {
                    if (((mask ushr (31 - i)) and 1L) == 1L) result += base + i + 1
                }
            }
            // El último bit de cada mapa indica si existe el siguiente rango.
            if (!result.contains(base + 0x20)) break
            base += 0x20
        }
        return result
    }

    /** Devuelve los bytes de datos (después de "41 PID") de cada ECU que respondió. */
    suspend fun queryMode01(pid: Int, timeoutMs: Long = 2_000): List<IntArray> {
        val cmd = String.format(Locale.US, "01%02X", pid)
        val raw = send(cmd, timeoutMs)
        // Los PIDs largos (p. ej. 70 turbo, 7F tiempos) llegan en varias tramas ISO-TP: se juntan.
        return assemble(dataLines(raw, cmd))
            .filter { it.size >= 2 && it[0] == 0x41 && it[1] == pid }
            .map { it.copyOfRange(2, it.size) }
    }

    /** Respuesta cruda de cualquier PID del modo 01 (para explorar los no decodificados). */
    suspend fun rawMode01(pid: Int): String? {
        val d = queryMode01(pid, 3_000).firstOrNull() ?: return null
        return d.joinToString(" ") { String.format(Locale.US, "%02X", it) }
    }

    /** Lee una métrica del modo 01 y aplica su fórmula. null si no hubo datos válidos. */
    suspend fun readMetric(metric: Metric): Double? {
        val pid = metric.pid ?: return null
        val formula = metric.formula ?: return null
        val data = queryMode01(pid).firstOrNull { it.size >= metric.bytes } ?: return null
        val value = formula(data)
        return if (value.isNaN() || value.isInfinite()) null else value
    }

    /** Voltaje en el pin 16 del conector OBD (batería), medido por el propio adaptador. */
    suspend fun readVoltage(): Double? {
        val raw = send("ATRV", 2_000)
        val match = Regex("""(\d+(?:[.,]\d+)?)\s*V""", RegexOption.IGNORE_CASE).find(raw) ?: return null
        return match.groupValues[1].replace(',', '.').toDoubleOrNull()
    }

    // ------------------------------------------------------------------
    // Diagnóstico
    // ------------------------------------------------------------------

    /** 0101: estado del Check Engine (MIL), número de códigos y monitores de emisiones. */
    suspend fun readStatus(): VehicleStatus? {
        val d = queryMode01(0x01, 3_000).firstOrNull { it.size >= 4 } ?: return null
        return VehicleStatus.parse(d)
    }

    /**
     * Lee códigos de falla.
     *  modo "03" → almacenados (respuesta 43)
     *  modo "07" → pendientes  (respuesta 47)
     *  modo "0A" → permanentes (respuesta 4A)
     */
    suspend fun readDtcs(mode: String): List<String> {
        val raw = send(mode, 8_000, logIt = true)
        val responseByte = mode.toInt(16) + 0x40
        return DtcParser.parse(dataLines(raw, mode), responseByte, isCan)
    }

    /** Modo 04: borra códigos y apaga el Check Engine. Responde "44" si se aceptó. */
    suspend fun clearDtcs(): Boolean {
        val raw = send("04", 8_000, logIt = true)
        return dataLines(raw, "04").any { it.hex.startsWith("44") }
    }

    /**
     * VIN (modo 09, PID 02). En CAN llega en varias tramas:
     *   014                       ← longitud total (0x14 = 20 bytes)
     *   0: 49 02 01 39 42 57      ← 49 02 = respuesta, 01 = n.º de datos, luego "9BW"
     *   1: 5A 5A 5A 33 37 37 56   ← "ZZZ377V"
     *   2: 54 30 30 34 32 35 31   ← "T004251"
     * En protocolos no-CAN llegan 5 líneas "49 02 nn" + 4 bytes cada una.
     */
    suspend fun readVin(): String? {
        val raw = send("0902", 6_000, logIt = true)
        val collected = mutableListOf<Int>()
        for (frame in dataLines(raw, "0902")) {
            val b = frame.bytes
            if (frame.hex.startsWith("4902") && b.size >= 3) {
                collected += b.drop(3) // saltar 49, 02 y el contador
            } else {
                collected += b.toList()
            }
        }
        val text = collected
            .filter { it in 0x30..0x39 || it in 0x41..0x5A } // solo 0-9 y A-Z
            .map { it.toChar() }
            .joinToString("")
        if (text.length < 11) return null
        return if (text.length > 17) text.takeLast(17) else text
    }

    /**
     * Cuadro congelado (modo 02, cuadro 00): valores guardados en el instante en que
     * se registró el fallo. Respuesta: 42 PID 00 datos…
     */
    suspend fun readFreezeFrame(metrics: List<Metric>): FreezeFrame? {
        // PID 02 del modo 02: el código que provocó el cuadro congelado.
        val trigger = run {
            val raw = send("020200", 3_000)
            val d = dataLines(raw, "020200").firstOrNull { it.hex.startsWith("4202") }?.bytes
            if (d == null || d.size < 5) null
            else if (d[3] == 0 && d[4] == 0) null
            else DtcParser.decode(d[3], d[4])
        } ?: return null

        val values = linkedMapOf<String, Double>()
        for (m in metrics) {
            val pid = m.pid ?: continue
            val formula = m.formula ?: continue
            val cmd = String.format(Locale.US, "02%02X00", pid)
            val prefix = String.format(Locale.US, "42%02X", pid)
            val raw = send(cmd, 2_000)
            val d = dataLines(raw, cmd).firstOrNull { it.hex.startsWith(prefix) }?.bytes ?: continue
            if (d.size < 3 + m.bytes) continue
            values[m.key] = formula(d.copyOfRange(3, d.size)) // saltar 42, PID y n.º de cuadro
        }
        return FreezeFrame(trigger, values)
    }


    // ------------------------------------------------------------------
    // Módulos (ECUs) y calibraciones · SOLO LECTURA
    // ------------------------------------------------------------------

    /**
     * Detecta qué módulos responden y lee su identificación con el modo 09:
     *   0900 tipos de información soportados   0904 ID de calibración (CALID)
     *   0906 número de verificación (CVN)      090A nombre del módulo
     * También consulta si soporta el modo 06 (resultados de pruebas) y el modo 08 (pruebas activas).
     *
     * Ningún comando de esta función escribe en la ECU. Solo se cambian cabeceras del propio
     * adaptador (ATH1 / ATSH), que se restauran al terminar.
     */
    suspend fun scanModules(onStep: (String) -> Unit): List<EcuModule> {
        onStep("Buscando módulos que respondan…")
        val responders: List<String>
        try {
            send("ATH1", 2_000, logIt = true)
            val raw = send("0100", 6_000, logIt = true)
            responders = responderAddresses(raw)
        } finally {
            send("ATH0", 2_000, logIt = true)
        }
        if (responders.isEmpty()) return emptyList()

        val out = mutableListOf<EcuModule>()
        val canTargeted = isCan && responders.all { it.length == 3 || it.length == 8 }
        if (!canTargeted) {
            // K-Line / J1850: consulta funcional (responde el módulo de motor, normalmente).
            onStep("Leyendo identificación del módulo…")
            out += readModuleInfo(responders.joinToString(" / "))
            return out
        }

        try {
            for ((i, addr) in responders.withIndex()) {
                onStep("Leyendo módulo $addr · ${i + 1}/${responders.size}")
                send("ATSH${requestHeaderFor(addr)}", 2_000, logIt = true)
                out += readModuleInfo(addr)
            }
        } finally {
            // Volver a la cabecera funcional por defecto (todas las ECUs de emisiones).
            val functional = if (responders.first().length == 8) "18DB33F1" else "7DF"
            send("ATSH$functional", 2_000, logIt = true)
        }
        return out
    }

    private suspend fun readModuleInfo(address: String): EcuModule {
        val infoTypes = mode09Supported()
        val calIds = if (infoTypes.isEmpty() || 0x04 in infoTypes) {
            mode09Payload(0x04).chunked(16).map { ascii(it) }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val cvns = if (infoTypes.isEmpty() || 0x06 in infoTypes) {
            mode09Payload(0x06).chunked(4).filter { it.size == 4 && it.any { b -> b != 0 } }
                .map { c -> c.joinToString("") { String.format(Locale.US, "%02X", it) } }
        } else {
            emptyList()
        }
        val name = if (0x0A in infoTypes) ascii(mode09Payload(0x0A)).ifBlank { null } else null
        val vin = if (0x02 in infoTypes) readVin() else null
        val m06 = dataLines(send("0600", 3_000), "0600").any { it.hex.startsWith("4600") }
        val m08 = dataLines(send("0800", 3_000), "0800").any { it.hex.startsWith("4800") }
        val pidCount = queryMode01(0x00, 3_000).firstOrNull()?.let { d ->
            if (d.size < 4) 0 else d.take(4).sumOf { Integer.bitCount(it) } - (d[3] and 1)
        } ?: 0
        return EcuModule(
            address = address,
            name = name?.let { moduleNameFromCode(it) },
            calibrationIds = calIds,
            cvns = cvns,
            infoTypes = infoTypes,
            vin = vin,
            supportsMode06 = m06,
            supportsMode08 = m08,
            pidCount = pidCount,
        )
    }

    /** 0900: mapa de bits de los tipos de información del modo 09. */
    private suspend fun mode09Supported(): Set<Int> {
        val raw = send("0900", 4_000)
        val result = sortedSetOf<Int>()
        for (msg in assemble(dataLines(raw, "0900"))) {
            if (msg.size < 6 || msg[0] != 0x49 || msg[1] != 0x00) continue
            val d = msg.copyOfRange(msg.size - 4, msg.size) // los 4 bytes del mapa al final
            for (i in 0 until 32) if ((d[i / 8] shr (7 - i % 8)) and 1 == 1) result += i + 1
        }
        return result
    }

    /**
     * Datos de un tipo de información del modo 09, sin cabeceras.
     * CAN: un mensaje "49 tt nn datos…" (nn = cantidad de elementos).
     * K-Line: varias líneas "49 tt ss d1 d2 d3 d4" (ss = número de secuencia).
     * En ambos casos se descartan los 3 primeros bytes de cada mensaje.
     */
    private suspend fun mode09Payload(type: Int): List<Int> {
        val cmd = String.format(Locale.US, "09%02X", type)
        val raw = send(cmd, 6_000, logIt = true)
        val out = mutableListOf<Int>()
        for (msg in assemble(dataLines(raw, cmd))) {
            if (msg.size >= 3 && msg[0] == 0x49 && msg[1] == type) out += msg.drop(3)
        }
        return out
    }

    companion object {
        /** Nombres de los protocolos del comando ATSPx. */
        fun protocolName(code: String): String = when (code.uppercase(Locale.US)) {
            "0" -> "Automático"
            "1" -> "SAE J1850 PWM"
            "2" -> "SAE J1850 VPW"
            "3" -> "ISO 9141-2"
            "4" -> "ISO 14230-4 KWP (inicio lento)"
            "5" -> "ISO 14230-4 KWP (inicio rápido)"
            "6" -> "CAN 11 bit 500 kbps"
            "7" -> "CAN 29 bit 500 kbps"
            "8" -> "CAN 11 bit 250 kbps"
            "9" -> "CAN 29 bit 250 kbps"
            "A" -> "SAE J1939 CAN"
            else -> "Protocolo $code"
        }

        /**
         * Direcciones de las ECUs que respondieron a 0100 con cabeceras activadas (ATH1, ATS0):
         *   CAN 11 bit: "7E8064100BE3EB813"        → "7E8"
         *   CAN 29 bit: "18DAF110064100BE3EB813"   → "18DAF110"
         *   K-Line:     "486B104100BE3EB813C4"     → "10" (tercer byte = dirección de origen)
         */
        fun responderAddresses(raw: String): List<String> {
            val out = linkedSetOf<String>()
            for (original in lines(raw)) {
                val line = original.replace(" ", "").uppercase(Locale.US)
                if (!HEX_OR_ODD.matches(line)) continue
                when {
                    line.length >= 9 && line.startsWith("18DA") && line.length % 2 == 0 && line.substring(10).startsWith("4100") ->
                        out += line.substring(0, 8)
                    line.length >= 7 && line.length % 2 == 1 && line.substring(5).startsWith("4100") ->
                        out += line.substring(0, 3)
                    line.length >= 7 && line.length % 2 == 1 && line.substring(3).startsWith("4100") ->
                        out += line.substring(0, 3) // CAN 11 bit sin byte de longitud (ATCAF0)
                    line.length >= 10 && line.length % 2 == 0 && line.substring(6).startsWith("4100") ->
                        out += line.substring(4, 6)
                }
            }
            return out.toList()
        }

        /** Cabecera física para hablarle a un solo módulo: respuesta 7E8 → petición 7E0. */
        fun requestHeaderFor(responseAddress: String): String =
            if (responseAddress.length == 8) {
                "18DA" + responseAddress.substring(6, 8) + "F1"
            } else {
                String.format(Locale.US, "%03X", responseAddress.toInt(16) - 8)
            }

        /** Junta tramas ISO-TP ("0:", "1:"…) en mensajes completos. */
        fun assemble(frames: List<Frame>): List<IntArray> {
            val msgs = mutableListOf<MutableList<Int>>()
            for (f in frames) {
                if (f.index == null || f.index == 0 || msgs.isEmpty()) {
                    msgs += f.bytes.toMutableList()
                } else {
                    msgs.last() += f.bytes.toList()
                }
            }
            return msgs.map { it.toIntArray() }
        }

        /** Bytes → texto, solo caracteres imprimibles; los 0x00 de relleno se vuelven espacios. */
        fun ascii(bytes: List<Int>): String =
            bytes.map { if (it in 0x20..0x7E) it.toChar() else ' ' }.joinToString("")
                .replace(Regex("\\s+"), " ").trim()

        /** "ECM - EngineControl" → "Motor (ECM)". */
        fun moduleNameFromCode(raw: String): String {
            val code = raw.substringBefore(" ").substringBefore("-").trim().uppercase(Locale.US)
            val known = mapOf(
                "ECM" to "Motor", "PCM" to "Motor y transmisión", "TCM" to "Transmisión",
                "ABS" to "Frenos ABS", "BCM" to "Carrocería", "SRS" to "Airbags", "HCM" to "Sistema híbrido",
                "GPC" to "Control de bujías de precalentamiento", "DCU" to "Postratamiento diésel",
                "FICM" to "Inyectores", "BECM" to "Batería", "CHG" to "Cargador",
            )
            return known[code]?.let { "$it ($code)" } ?: raw
        }

        private val HEX_OR_ODD = Regex("^[0-9A-F]+$")
        private val HEX = Regex("^[0-9A-F]+$")
        private val FRAME_INDEX = Regex("^([0-9A-F]):(.*)$")

        fun lines(raw: String): List<String> =
            raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * Limpia una respuesta y deja solo las tramas de datos hexadecimales.
         *  - quita espacios y el eco del comando (por si ATE0 no se aplicó)
         *  - descarta "SEARCHING...", "BUS INIT: ...OK", "NO DATA", "?" y similares
         *  - separa el índice de trama "0:" / "1:" de las respuestas multi-trama
         *  - descarta la línea de longitud ISO-TP (3 dígitos, ej. "014")
         */
        fun dataLines(raw: String, command: String): List<Frame> {
            val cmd = command.replace(" ", "").uppercase(Locale.US)
            val out = mutableListOf<Frame>()
            for (original in lines(raw)) {
                var line = original.replace(" ", "").uppercase(Locale.US)
                if (line == cmd) continue
                var index: Int? = null
                val m = FRAME_INDEX.matchEntire(line)
                if (m != null) {
                    index = m.groupValues[1].toInt(16)
                    line = m.groupValues[2]
                }
                if (line.isEmpty() || !HEX.matches(line)) continue
                if (line.length % 2 != 0) continue // longitud ISO-TP u otra basura
                out += Frame(index, line)
            }
            return out
        }

        /** "410C1AF8" → [0x41, 0x0C, 0x1A, 0xF8] */
        fun hexToInts(hex: String): IntArray {
            val n = hex.length / 2
            return IntArray(n) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }
        }
    }
}

/** Resultado del cuadro congelado. */
data class FreezeFrame(val triggerDtc: String, val values: Map<String, Double>)

/** Estado de un monitor de emisiones (prueba de autodiagnóstico de la ECU). */
data class MonitorStatus(val name: String, val available: Boolean, val complete: Boolean)

/** Respuesta del PID 0101. */
data class VehicleStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<MonitorStatus>,
) {
    companion object {
        /**
         * Bytes de datos de 41 01 A B C D:
         *  A bit 7    = Check Engine (MIL) encendido
         *  A bits 0-6 = cantidad de códigos almacenados
         *  B bit 3    = 0 gasolina (chispa) / 1 diésel (compresión)
         *  B bits 0-2 = monitores comunes disponibles, bits 4-6 = incompletos
         *  C          = monitores específicos disponibles
         *  D          = monitores específicos incompletos (1 = aún no termina)
         */
        fun parse(d: IntArray): VehicleStatus {
            val a = d[0]
            val b = d[1]
            val c = d[2]
            val dd = d[3]
            val compression = (b and 0x08) != 0

            val monitors = mutableListOf<MonitorStatus>()
            val common = listOf("Fallos de encendido", "Sistema de combustible", "Componentes del motor")
            for (i in 0..2) {
                monitors += MonitorStatus(
                    name = common[i],
                    available = ((b shr i) and 1) == 1,
                    complete = ((b shr (i + 4)) and 1) == 0,
                )
            }

            val specific = if (!compression) {
                listOf(
                    "Catalizador", "Catalizador calentado", "Sistema evaporativo (EVAP)", "Aire secundario",
                    "Refrigerante del A/C", "Sensor de oxígeno", "Calentador del sensor O2", "EGR / VVT",
                )
            } else {
                listOf(
                    "Catalizador NMHC", "Catalizador NOx / SCR", "", "Presión de sobrealimentación",
                    "", "Sensor de gases de escape", "Filtro de partículas (DPF)", "EGR / VVT",
                )
            }
            for (i in 0..7) {
                if (specific[i].isEmpty()) continue
                monitors += MonitorStatus(
                    name = specific[i],
                    available = ((c shr i) and 1) == 1,
                    complete = ((dd shr i) and 1) == 0,
                )
            }

            return VehicleStatus(
                milOn = (a and 0x80) != 0,
                dtcCount = a and 0x7F,
                compressionIgnition = compression,
                monitors = monitors,
            )
        }
    }
}
