package com.nguevara.obdpulse.bt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Simulador de un ELM327 conectado a un carro a gasolina con CAN 11 bit / 500 kbps.
 * Responde a los mismos comandos que un adaptador real, así toda la app (parser incluido)
 * se puede probar sin estar en el carro.
 */
class DemoTransport : ObdTransport {

    override val device = ObdDevice("Simulador ELM327", "DEMO", LinkType.DEMO)
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var open = false
    override val isOpen: Boolean
        get() = open

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = StringBuilder()
    private val car = CarSimulator()

    // Estado configurable del "chip" ELM327
    private var echo = true
    private var spaces = true

    // Códigos de falla simulados (2 bytes cada uno, en hex)
    private val storedDtcs = mutableListOf("0301", "0420") // P0301, P0420
    private val pendingDtcs = mutableListOf("0171")        // P0171

    override suspend fun open() {
        delay(700)
        open = true
    }

    override suspend fun write(data: ByteArray) {
        if (!open) throw TransportException("Simulador cerrado")
        pending.append(String(data, Charsets.US_ASCII))
        while (true) {
            val idx = pending.indexOf("\r")
            if (idx < 0) break
            val raw = pending.substring(0, idx)
            pending.delete(0, idx + 1)
            val cmd = raw.trim().uppercase(Locale.US).replace(" ", "")
            if (cmd.isEmpty()) continue
            val reply = respond(cmd)
            val echoed = if (echo) "$raw\r" else ""
            val latency = if (cmd.startsWith("AT")) 12L else 35L + Random.nextLong(0, 25)
            scope.launch {
                delay(latency)
                incoming.trySend("$echoed$reply\r\r>".toByteArray(Charsets.US_ASCII))
            }
        }
    }

    override fun close() {
        open = false
        scope.cancel()
        incoming.close()
    }

    // ------------------------------------------------------------------
    // Respuestas
    // ------------------------------------------------------------------

    private fun respond(cmd: String): String {
        if (cmd.startsWith("AT")) return respondAt(cmd.substring(2))
        car.update()
        return when {
            cmd.length >= 4 && cmd.startsWith("01") -> mode01(cmd.substring(2, 4).toInt(16))
            cmd.length >= 4 && cmd.startsWith("02") -> mode02(cmd.substring(2, 4).toInt(16))
            cmd == "03" -> dtcResponse("43", storedDtcs)
            cmd == "07" -> dtcResponse("47", pendingDtcs)
            cmd == "0A" -> "4A00".spaced()
            cmd == "04" -> {
                storedDtcs.clear()
                pendingDtcs.clear()
                "44"
            }

            cmd == "0902" -> vinResponse()
            else -> "NO DATA"
        }
    }

    private fun respondAt(at: String): String = when {
        at == "Z" -> {
            echo = true
            spaces = true
            "\r\rELM327 v1.5"
        }

        at == "I" -> "ELM327 v1.5"
        at == "@1" -> "OBDII to RS232 Interpreter"
        at == "E0" -> { echo = false; "OK" }
        at == "E1" -> { echo = true; "OK" }
        at == "S0" -> { spaces = false; "OK" }
        at == "S1" -> { spaces = true; "OK" }
        at == "RV" -> String.format(Locale.US, "%.1fV", car.voltage)
        at == "DPN" -> "A6"
        at == "DP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        else -> "OK"
    }

    /** PIDs que el carro simulado "soporta". */
    private val supported = sortedSetOf(
        0x01, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x14, 0x15,
        0x1F, 0x21, 0x2F, 0x31, 0x33, 0x3C, 0x42, 0x44, 0x45, 0x46, 0x49, 0x5C,
    )

    private fun mode01(pid: Int): String {
        if (pid % 0x20 == 0) return ("41" + hex(pid) + bitmap(pid)).spaced()
        if (pid !in supported) return "NO DATA"
        val data = pidData(pid) ?: return "NO DATA"
        return ("41" + hex(pid) + data).spaced()
    }

    private fun mode02(pid: Int): String {
        return when (pid) {
            0x02 -> ("4202" + "00" + (storedDtcs.firstOrNull() ?: "0000")).spaced()
            0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x11 -> {
                // Cuadro congelado fijo: el momento en que apareció el fallo
                val data = when (pid) {
                    0x04 -> hex(140)
                    0x05 -> hex(88 + 40)
                    0x0B -> hex(62)
                    0x0C -> hex4(2350 * 4)
                    0x0D -> hex(54)
                    0x0F -> hex(30 + 40)
                    else -> hex(52)
                }
                ("42" + hex(pid) + "00" + data).spaced()
            }

            else -> "NO DATA"
        }
    }

    /** Mapa de bits de PIDs soportados para el rango base+1 .. base+0x20. */
    private fun bitmap(base: Int): String {
        var mask = 0L
        for (i in 0 until 32) {
            val pid = base + i + 1
            val hasPid = pid in supported
            val hasNextRange = pid == base + 0x20 && supported.any { it > base + 0x20 }
            if (hasPid || hasNextRange) mask = mask or (1L shl (31 - i))
        }
        return String.format(Locale.US, "%08X", mask)
    }

    private fun pidData(pid: Int): String? = with(car) {
        when (pid) {
            0x01 -> {
                val a = (if (storedDtcs.isNotEmpty()) 0x80 else 0) or storedDtcs.size
                hex(a) + hex(0x07) + hex(0x65) + hex(0x01)
            }

            0x04 -> hex((load * 2.55).toInt())
            0x05 -> hex((coolant + 40).toInt())
            0x06 -> hex(128 + Random.nextInt(-6, 7))
            0x07 -> hex(131)
            0x0B -> hex(map.toInt())
            0x0C -> hex4((rpm * 4).toInt())
            0x0D -> hex(speed.toInt())
            0x0E -> hex(((timing + 64) * 2).toInt())
            0x0F -> hex((intakeTemp + 40).toInt())
            0x10 -> hex4((maf * 100).toInt())
            0x11 -> hex((throttle * 2.55).toInt())
            0x14 -> hex((o2 * 200).toInt()) + "FF"
            0x15 -> hex((0.65 + Random.nextDouble(-0.03, 0.03)) * 200) + "FF"
            0x1F -> hex4(runtime.toInt())
            0x21 -> hex4(0)
            0x2F -> hex((fuel * 2.55).toInt())
            0x31 -> hex4(1834)
            0x33 -> hex(82) // ~1.700 m de altura
            0x3C -> hex4(((catalyst + 40) * 10).toInt())
            0x42 -> hex4((voltage * 1000).toInt())
            0x44 -> hex4((lambda * 32768).toInt())
            0x45 -> hex((throttle * 0.9 * 2.55).toInt())
            0x46 -> hex(22 + 40)
            0x49 -> hex((pedal * 2.55).toInt())
            0x5C -> hex((oilTemp + 40).toInt())
            else -> null
        }
    }

    /** Formato CAN: 43 + cantidad + pares de bytes. */
    private fun dtcResponse(prefix: String, codes: List<String>): String {
        val body = prefix + hex(codes.size) + codes.joinToString("")
        return body.spaced()
    }

    /** VIN en multi-trama ISO-TP: longitud, luego tramas 0:, 1:, 2: */
    private fun vinResponse(): String {
        val vin = "9BWZZZ377VT004251"
        val payload = "490201" + vin.map { hex(it.code) }.joinToString("")
        val bytes = payload.chunked(2)
        val frames = mutableListOf<String>()
        frames += String.format(Locale.US, "%03X", bytes.size)
        frames += "0:" + bytes.take(6).joinToString(if (spaces) " " else "", prefix = if (spaces) " " else "")
        var rest = bytes.drop(6)
        var index = 1
        while (rest.isNotEmpty()) {
            val frame = rest.take(7)
            frames += "${index % 16}:" + frame.joinToString(if (spaces) " " else "", prefix = if (spaces) " " else "")
            rest = rest.drop(7)
            index++
        }
        return frames.joinToString("\r")
    }

    private fun String.spaced(): String = if (spaces) chunked(2).joinToString(" ") else this

    private fun hex(v: Int): String = String.format(Locale.US, "%02X", v.coerceIn(0, 255))
    private fun hex(v: Double): String = hex(v.toInt())
    private fun hex4(v: Int): String = String.format(Locale.US, "%04X", v.coerceIn(0, 65535))
}

/** Pequeño modelo físico: un ciclo de conducción urbano de 70 s que se repite. */
private class CarSimulator {
    private val start = System.currentTimeMillis()
    private var last = start

    var speed = 0.0
    var rpm = 820.0
    var throttle = 14.0
    var pedal = 0.0
    var load = 22.0
    var map = 32.0
    var maf = 2.6
    var coolant = 38.0
    var oilTemp = 36.0
    var intakeTemp = 26.0
    var timing = 12.0
    var fuel = 63.0
    var voltage = 14.1
    var o2 = 0.45
    var lambda = 1.0
    var catalyst = 320.0
    var runtime = 0.0

    private val gearRatios = doubleArrayOf(0.0, 105.0, 62.0, 44.0, 34.0, 28.0) // rpm por km/h

    fun update() {
        val now = System.currentTimeMillis()
        val dt = ((now - last) / 1000.0).coerceIn(0.0, 1.0)
        last = now
        runtime = (now - start) / 1000.0
        val t = runtime % 70.0

        val target = when {
            t < 4 -> 0.0
            t < 18 -> (t - 4) * 5.6
            t < 40 -> 78 + 7 * sin(runtime / 2.7)
            t < 52 -> 78 - (t - 40) * 6.5
            else -> 0.0
        }.coerceAtLeast(0.0)

        val accelerating = target > speed + 1
        speed += (target - speed) * min(1.0, dt * 1.6)
        if (speed < 0.5) speed = 0.0

        pedal = when {
            accelerating -> 35 + Random.nextDouble(0.0, 15.0)
            speed > 5 && target >= speed - 1 -> 16 + Random.nextDouble(0.0, 4.0)
            else -> 0.0
        }
        throttle = max(14.0, pedal * 0.85 + 12)

        val gear = when {
            speed < 1 -> 0
            speed < 18 -> 1
            speed < 34 -> 2
            speed < 52 -> 3
            speed < 70 -> 4
            else -> 5
        }
        val targetRpm = if (gear == 0) 820.0 else max(1000.0, speed * gearRatios[gear]) + pedal * 12
        rpm += (targetRpm - rpm) * min(1.0, dt * 3.0) + Random.nextDouble(-15.0, 15.0)

        load = (18 + pedal * 1.1 + rpm / 200).coerceIn(10.0, 100.0)
        map = (28 + pedal * 0.9).coerceIn(25.0, 82.0)
        maf = rpm / 120.0 * 0.85 * (map * 1.6) / (8.314 * (intakeTemp + 273.15)) * 28.97
        timing = 8 + rpm / 400 - pedal / 10
        coolant = min(90.0, coolant + dt * 0.9)
        oilTemp = min(98.0, oilTemp + dt * 0.75)
        intakeTemp = 26 + speed / 40
        catalyst = min(560.0, catalyst + dt * 3)
        fuel = max(5.0, fuel - maf * dt / 14.7 / 737 / 45 * 100)
        voltage = 13.9 + Random.nextDouble(0.0, 0.35)
        o2 = if (Random.nextBoolean()) 0.15 + Random.nextDouble(0.0, 0.2) else 0.65 + Random.nextDouble(0.0, 0.2)
        lambda = if (pedal > 45) 0.92 else 1.0
    }
}
