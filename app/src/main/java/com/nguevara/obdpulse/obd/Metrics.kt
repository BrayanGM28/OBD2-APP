package com.nguevara.obdpulse.obd

/**
 * Una magnitud que la app puede mostrar.
 *
 * - Si [pid] no es null, se lee con el modo 01 del estándar SAE J1979: se envía "01" + PID
 *   y la ECU responde "41" + PID + bytes de datos (A, B, C, D). [formula] convierte esos bytes.
 * - Si [pid] es null, es una magnitud del adaptador (voltaje) o calculada (consumo).
 *
 * Los valores se guardan siempre en unidades base (°C, km/h, km/L…). La conversión a
 * °F, km/gal, etc. ocurre solo al mostrar (ver [com.nguevara.obdpulse.data.Units]).
 */
data class Metric(
    val key: String,
    val name: String,
    val short: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    val pid: Int? = null,
    val bytes: Int = 1,
    /** true = se consulta en cada ciclo; false = cada 4 ciclos (temperaturas, nivel, etc.). */
    val fast: Boolean = false,
    val kind: Kind = Kind.OTHER,
    val formula: ((IntArray) -> Double)? = null,
) {
    enum class Kind { TEMPERATURE, ECONOMY, OTHER }
}

object Metrics {

    // Claves de magnitudes no-PID
    const val VOLTAGE = "ATRV"
    const val FUEL_RATE = "FUEL_RATE"
    const val ECONOMY = "ECON"

    // Claves de PIDs usados con frecuencia
    const val RPM = "010C"
    const val SPEED = "010D"
    const val COOLANT = "0105"
    const val LOAD = "0104"
    const val THROTTLE = "0111"
    const val MAF = "0110"
    const val MAP = "010B"
    const val IAT = "010F"
    const val FUEL_LEVEL = "012F"
    const val PID_FUEL_RATE = "015E"
    const val OIL_TEMP = "015C"

    private fun pid(
        pid: Int,
        name: String,
        short: String,
        unit: String,
        min: Double,
        max: Double,
        bytes: Int,
        decimals: Int = 0,
        fast: Boolean = false,
        kind: Metric.Kind = Metric.Kind.OTHER,
        formula: (IntArray) -> Double,
    ) = Metric(
        key = String.format(java.util.Locale.US, "01%02X", pid),
        name = name,
        short = short,
        unit = unit,
        min = min,
        max = max,
        decimals = decimals,
        pid = pid,
        bytes = bytes,
        fast = fast,
        kind = kind,
        formula = formula,
    )

    // Notación: d[0] = A, d[1] = B, d[2] = C, d[3] = D (bytes de datos de la respuesta)
    private fun ab(d: IntArray) = d[0] * 256.0 + d[1]

    val all: List<Metric> = listOf(
        pid(0x0C, "Revoluciones del motor", "RPM", "rpm", 0.0, 8000.0, 2, fast = true) { ab(it) / 4.0 },
        pid(0x0D, "Velocidad del vehículo", "Velocidad", "km/h", 0.0, 220.0, 1, fast = true) { it[0].toDouble() },
        pid(0x11, "Posición del acelerador", "Acelerador", "%", 0.0, 100.0, 1, fast = true) { it[0] * 100.0 / 255.0 },
        pid(0x04, "Carga calculada del motor", "Carga", "%", 0.0, 100.0, 1, fast = true) { it[0] * 100.0 / 255.0 },
        pid(0x10, "Flujo de aire (MAF)", "MAF", "g/s", 0.0, 250.0, 2, decimals = 1, fast = true) { ab(it) / 100.0 },
        pid(0x0B, "Presión del múltiple (MAP)", "MAP", "kPa", 0.0, 255.0, 1, fast = true) { it[0].toDouble() },
        pid(0x05, "Temperatura del refrigerante", "Refrigerante", "°C", -40.0, 130.0, 1, kind = Metric.Kind.TEMPERATURE) { it[0] - 40.0 },
        pid(0x0F, "Temperatura aire de admisión", "Admisión", "°C", -40.0, 90.0, 1, kind = Metric.Kind.TEMPERATURE) { it[0] - 40.0 },
        pid(0x5C, "Temperatura del aceite", "Aceite", "°C", -40.0, 150.0, 1, kind = Metric.Kind.TEMPERATURE) { it[0] - 40.0 },
        pid(0x46, "Temperatura ambiente", "Ambiente", "°C", -40.0, 60.0, 1, kind = Metric.Kind.TEMPERATURE) { it[0] - 40.0 },
        pid(0x3C, "Temperatura del catalizador B1S1", "Catalizador", "°C", -40.0, 900.0, 2, kind = Metric.Kind.TEMPERATURE) { ab(it) / 10.0 - 40.0 },
        pid(0x2F, "Nivel de combustible", "Combustible", "%", 0.0, 100.0, 1) { it[0] * 100.0 / 255.0 },
        pid(0x5E, "Consumo del motor (ECU)", "Consumo ECU", "L/h", 0.0, 40.0, 2, decimals = 2, fast = true) { ab(it) / 20.0 },
        pid(0x0E, "Avance de encendido", "Avance", "°", -64.0, 64.0, 1, decimals = 1, fast = true) { it[0] / 2.0 - 64.0 },
        pid(0x06, "Ajuste de combustible corto B1", "STFT B1", "%", -25.0, 25.0, 1, decimals = 1, fast = true) { (it[0] - 128) * 100.0 / 128.0 },
        pid(0x07, "Ajuste de combustible largo B1", "LTFT B1", "%", -25.0, 25.0, 1, decimals = 1) { (it[0] - 128) * 100.0 / 128.0 },
        pid(0x08, "Ajuste de combustible corto B2", "STFT B2", "%", -25.0, 25.0, 1, decimals = 1, fast = true) { (it[0] - 128) * 100.0 / 128.0 },
        pid(0x09, "Ajuste de combustible largo B2", "LTFT B2", "%", -25.0, 25.0, 1, decimals = 1) { (it[0] - 128) * 100.0 / 128.0 },
        pid(0x14, "Sensor de oxígeno B1S1", "O2 B1S1", "V", 0.0, 1.275, 2, decimals = 3, fast = true) { it[0] / 200.0 },
        pid(0x15, "Sensor de oxígeno B1S2", "O2 B1S2", "V", 0.0, 1.275, 2, decimals = 3) { it[0] / 200.0 },
        pid(0x44, "Lambda comandada", "Lambda", "λ", 0.0, 2.0, 2, decimals = 3) { ab(it) / 32768.0 },
        pid(0x0A, "Presión de combustible", "Pres. combustible", "kPa", 0.0, 765.0, 1) { it[0] * 3.0 },
        pid(0x33, "Presión barométrica", "Barométrica", "kPa", 0.0, 255.0, 1) { it[0].toDouble() },
        pid(0x42, "Voltaje del módulo de control", "Voltaje ECU", "V", 0.0, 18.0, 2, decimals = 2) { ab(it) / 1000.0 },
        pid(0x43, "Carga absoluta", "Carga abs.", "%", 0.0, 200.0, 2, fast = true) { ab(it) * 100.0 / 255.0 },
        pid(0x45, "Posición relativa del acelerador", "Acel. relativo", "%", 0.0, 100.0, 1, fast = true) { it[0] * 100.0 / 255.0 },
        pid(0x49, "Posición del pedal del acelerador", "Pedal", "%", 0.0, 100.0, 1, fast = true) { it[0] * 100.0 / 255.0 },
        pid(0x4C, "Actuador del acelerador comandado", "Actuador acel.", "%", 0.0, 100.0, 1) { it[0] * 100.0 / 255.0 },
        pid(0x1F, "Tiempo desde el arranque", "Tiempo motor", "s", 0.0, 65535.0, 2) { ab(it) },
        pid(0x21, "Distancia con Check Engine encendido", "Dist. con MIL", "km", 0.0, 65535.0, 2) { ab(it) },
        pid(0x31, "Distancia desde borrado de códigos", "Dist. desde borrado", "km", 0.0, 65535.0, 2) { ab(it) },

        // Magnitudes no-PID
        Metric(VOLTAGE, "Voltaje de la batería", "Batería", "V", 10.0, 16.0, decimals = 1),
        Metric(FUEL_RATE, "Consumo instantáneo", "Consumo", "L/h", 0.0, 30.0, decimals = 2, fast = true),
        Metric(ECONOMY, "Rendimiento instantáneo", "Rendimiento", "km/L", 0.0, 40.0, decimals = 1, fast = true, kind = Metric.Kind.ECONOMY),
    )

    val byKey: Map<String, Metric> = all.associateBy { it.key }

    operator fun get(key: String): Metric? = byKey[key]
}
