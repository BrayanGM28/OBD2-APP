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
)

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
    suspend fun initialize(protocol: String, onStep: (String) -> Unit): VehicleInfo {
        onStep("Reiniciando el adaptador…")
        send("ATZ", 5_000, logIt = true)
        delay(400)

        onStep("Configurando el adaptador…")
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) send(cmd, 2_000, logIt = true)

        val atiLines = lines(send("ATI", 2_000, logIt = true))
        val version = atiLines.lastOrNull { it.contains("ELM", ignoreCase = true) }
            ?: atiLines.lastOrNull()
            ?: "ELM327"

        send("ATSP$protocol", 2_000, logIt = true)

        onStep("Buscando el protocolo del vehículo…")
        val first = send("0100", 15_000, logIt = true)
        val firstUpper = first.uppercase(Locale.US)
        val ecuOk = dataLines(first, "0100").any { it.hex.startsWith("4100") }

        val ecuMessage = if (ecuOk) {
            null
        } else when {
            "UNABLE" in firstUpper -> "El adaptador no logró comunicarse con la ECU. Pon el encendido en ON o arranca el motor."
            "CAN ERROR" in firstUpper -> "Error en el bus CAN. Prueba otro protocolo en Ajustes."
            "BUS INIT" in firstUpper && "ERROR" in firstUpper -> "Falló la inicialización del bus (K-Line). Revisa el encendido."
            "NO DATA" in firstUpper -> "La ECU no respondió. Pon el encendido en ON o arranca el motor."
            first.isBlank() -> "El adaptador no respondió a tiempo."
            else -> "Respuesta inesperada de la ECU: ${first.trim().take(60)}"
        }

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
        )
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
        val prefix = String.format(Locale.US, "41%02X", pid)
        val raw = send(cmd, timeoutMs)
        return dataLines(raw, cmd)
            .filter { it.hex.startsWith(prefix) }
            .map { hexToInts(it.hex.substring(4)) }
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

    companion object {
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
