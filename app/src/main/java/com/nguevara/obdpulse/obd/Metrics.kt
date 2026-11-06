package com.nguevara.obdpulse.obd

/**
 * Una magnitud que la app puede mostrar.
 *
 * - Si [pid] no es null, se lee con el modo 01 del estándar SAE J1979: se envía "01" + PID
 *   y la ECU responde "41" + PID + bytes de datos (A, B, C, D…). [formula] convierte esos bytes.
 *   Si la fórmula devuelve NaN (p. ej. el bit de "sensor presente" está apagado), el dato se descarta.
 * - Si [pid] es null, es una magnitud del adaptador (voltaje) o calculada (consumo, turbo, potencia…).
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
    val category: Category = Category.ENGINE,
    val formula: ((IntArray) -> Double)? = null,
) {
    enum class Kind { TEMPERATURE, ECONOMY, OTHER }

    enum class Category(val label: String) {
        ENGINE("Motor"),
        AIR("Aire y turbo"),
        FUEL("Combustible y mezcla"),
        TEMPERATURE("Temperaturas"),
        EMISSIONS("Emisiones"),
        DRIVING("Conducción"),
        ELECTRIC("Eléctrico"),
        COUNTERS("Contadores"),
    }
}

object Metrics {

    // Claves de magnitudes no-PID (adaptador o calculadas)
    const val VOLTAGE = "ATRV"
    const val FUEL_RATE = "FUEL_RATE"
    const val ECONOMY = "ECON"
    const val BOOST = "BOOST"
    const val TORQUE = "TORQUE_NM"
    const val POWER = "POWER"
    const val ACCEL = "ACCEL"
    const val AFR = "AFR"

    // Claves de PIDs usados con frecuencia
    const val RPM = "010C"
    const val SPEED = "010D"
    const val COOLANT = "0105"
    const val LOAD = "0104"
    const val THROTTLE = "0111"
    const val MAF = "0110"
    const val MAP = "010B"
    const val MAP_EXT = "0187"
    const val IAT = "010F"
    const val BARO = "0133"
    const val FUEL_LEVEL = "012F"
    const val PID_FUEL_RATE = "015E"
    const val OIL_TEMP = "015C"
    const val LAMBDA_CMD = "0144"
    const val LAMBDA_WIDE = "0124"
    const val TORQUE_ACTUAL = "0162"
    const val TORQUE_REF = "0163"
    const val RAIL_PRESSURE = "0123"
    const val RAIL_PRESSURE_ABS = "0159"
    const val EGR_CMD = "012C"
    const val EGT = "0178"
    const val DPF_TEMP = "017C"
    const val STFT1 = "0106"
    const val LTFT1 = "0107"
    const val O2_B1S1 = "0114"
    const val TIMING = "010E"
    const val PEDAL = "0149"
    const val AMBIENT = "0146"
    const val CAT_TEMP = "013C"

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
        category: Metric.Category = Metric.Category.ENGINE,
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
        category = category,
        formula = formula,
    )

    // Notación: d[0] = A, d[1] = B, d[2] = C, d[3] = D… (bytes de datos de la respuesta)
    private fun ab(d: IntArray) = d[0] * 256.0 + d[1]
    private fun bc(d: IntArray) = d[1] * 256.0 + d[2]
    private fun pct(x: Int) = x * 100.0 / 255.0
    private fun trim(x: Int) = (x - 128) * 100.0 / 128.0

    /** Para PIDs con byte A de "sensores presentes": NaN si el bit [bit] está apagado. */
    private fun ifPresent(d: IntArray, bit: Int, value: () -> Double): Double =
        if ((d[0] shr bit) and 1 == 1) value() else Double.NaN

    private val TEMP = Metric.Kind.TEMPERATURE
    private val C_AIR = Metric.Category.AIR
    private val C_FUEL = Metric.Category.FUEL
    private val C_TEMP = Metric.Category.TEMPERATURE
    private val C_EMIS = Metric.Category.EMISSIONS
    private val C_DRIVE = Metric.Category.DRIVING
    private val C_ELEC = Metric.Category.ELECTRIC
    private val C_COUNT = Metric.Category.COUNTERS

    val all: List<Metric> = listOf(
        // ---------------- Motor ----------------
        pid(0x0C, "Revoluciones del motor", "RPM", "rpm", 0.0, 8000.0, 2, fast = true) { ab(it) / 4.0 },
        pid(0x04, "Carga calculada del motor", "Carga", "%", 0.0, 100.0, 1, fast = true) { pct(it[0]) },
        pid(0x43, "Carga absoluta", "Carga abs.", "%", 0.0, 200.0, 2, fast = true) { ab(it) * 100.0 / 255.0 },
        pid(0x0E, "Avance de encendido", "Avance", "°", -64.0, 64.0, 1, decimals = 1, fast = true) { it[0] / 2.0 - 64.0 },
        pid(0x5D, "Tiempo de inyección", "Inyección", "°", -210.0, 302.0, 2, decimals = 1) { ab(it) / 128.0 - 210.0 },
        pid(0x61, "Torque solicitado por el conductor", "Torque pedido", "%", -125.0, 130.0, 1, fast = true) { it[0] - 125.0 },
        pid(0x62, "Torque real del motor", "Torque real", "%", -125.0, 130.0, 1, fast = true) { it[0] - 125.0 },
        pid(0x63, "Torque de referencia del motor", "Torque ref.", "Nm", 0.0, 2000.0, 2) { ab(it) },

        // ---------------- Aire y turbo ----------------
        pid(0x10, "Flujo de aire (MAF)", "MAF", "g/s", 0.0, 250.0, 2, decimals = 1, fast = true, category = C_AIR) { ab(it) / 100.0 },
        pid(0x0B, "Presión del múltiple (MAP)", "MAP", "kPa", 0.0, 255.0, 1, fast = true, category = C_AIR) { it[0].toDouble() },
        pid(0x87, "Presión del múltiple (rango turbo)", "MAP turbo", "kPa", 0.0, 400.0, 3, decimals = 1, fast = true, category = C_AIR) { d ->
            ifPresent(d, 0) { bc(d) / 32.0 }
        },
        pid(0x70, "Presión de sobrealimentación real", "Turbo real", "kPa", 0.0, 400.0, 5, decimals = 1, fast = true, category = C_AIR) { d ->
            // A = bits de soporte; B-C = comandada A; D-E = real A (1/32 kPa)
            if ((d[0] shr 1) and 1 == 1) (d[3] * 256.0 + d[4]) / 32.0 else Double.NaN
        },
        pid(0x33, "Presión barométrica", "Barométrica", "kPa", 0.0, 255.0, 1, category = C_AIR) { it[0].toDouble() },
        pid(0x11, "Posición del acelerador", "Acelerador", "%", 0.0, 100.0, 1, fast = true, category = C_AIR) { pct(it[0]) },
        pid(0x45, "Posición relativa del acelerador", "Acel. relativo", "%", 0.0, 100.0, 1, fast = true, category = C_AIR) { pct(it[0]) },
        pid(0x47, "Posición absoluta del acelerador B", "Acelerador B", "%", 0.0, 100.0, 1, category = C_AIR) { pct(it[0]) },
        pid(0x48, "Posición absoluta del acelerador C", "Acelerador C", "%", 0.0, 100.0, 1, category = C_AIR) { pct(it[0]) },
        pid(0x4C, "Actuador del acelerador comandado", "Actuador acel.", "%", 0.0, 100.0, 1, category = C_AIR) { pct(it[0]) },

        // ---------------- Combustible y mezcla ----------------
        pid(0x5E, "Consumo del motor (ECU)", "Consumo ECU", "L/h", 0.0, 40.0, 2, decimals = 2, fast = true, category = C_FUEL) { ab(it) / 20.0 },
        pid(0x2F, "Nivel de combustible", "Combustible", "%", 0.0, 100.0, 1, category = C_FUEL) { pct(it[0]) },
        pid(0x0A, "Presión de combustible", "Pres. combustible", "kPa", 0.0, 765.0, 1, category = C_FUEL) { it[0] * 3.0 },
        pid(0x22, "Presión del riel (relativa al vacío)", "Riel rel.", "kPa", 0.0, 5177.0, 2, decimals = 1, category = C_FUEL) { ab(it) * 0.079 },
        pid(0x23, "Presión del riel de inyección", "Riel", "bar", 0.0, 2500.0, 2, fast = true, category = C_FUEL) { ab(it) / 10.0 },
        pid(0x59, "Presión absoluta del riel", "Riel abs.", "bar", 0.0, 2500.0, 2, fast = true, category = C_FUEL) { ab(it) / 10.0 },
        pid(0x06, "Ajuste de combustible corto B1", "STFT B1", "%", -25.0, 25.0, 1, decimals = 1, fast = true, category = C_FUEL) { trim(it[0]) },
        pid(0x07, "Ajuste de combustible largo B1", "LTFT B1", "%", -25.0, 25.0, 1, decimals = 1, category = C_FUEL) { trim(it[0]) },
        pid(0x08, "Ajuste de combustible corto B2", "STFT B2", "%", -25.0, 25.0, 1, decimals = 1, fast = true, category = C_FUEL) { trim(it[0]) },
        pid(0x09, "Ajuste de combustible largo B2", "LTFT B2", "%", -25.0, 25.0, 1, decimals = 1, category = C_FUEL) { trim(it[0]) },
        pid(0x14, "Sensor de oxígeno B1S1", "O2 B1S1", "V", 0.0, 1.275, 2, decimals = 3, fast = true, category = C_FUEL) { it[0] / 200.0 },
        pid(0x15, "Sensor de oxígeno B1S2", "O2 B1S2", "V", 0.0, 1.275, 2, decimals = 3, category = C_FUEL) { it[0] / 200.0 },
        pid(0x24, "Lambda sonda B1S1 (banda ancha)", "λ B1S1", "λ", 0.0, 2.0, 4, decimals = 3, fast = true, category = C_FUEL) { ab(it) * 2.0 / 65536.0 },
        pid(0x34, "Lambda sonda B1S1 (corriente)", "λ corriente", "λ", 0.0, 2.0, 4, decimals = 3, category = C_FUEL) { ab(it) * 2.0 / 65536.0 },
        pid(0x44, "Lambda comandada", "Lambda", "λ", 0.0, 2.0, 2, decimals = 3, category = C_FUEL) { ab(it) / 32768.0 },
        pid(0x52, "Porcentaje de etanol", "Etanol", "%", 0.0, 100.0, 1, category = C_FUEL) { pct(it[0]) },

        // ---------------- Temperaturas ----------------
        pid(0x05, "Temperatura del refrigerante", "Refrigerante", "°C", -40.0, 130.0, 1, kind = TEMP, category = C_TEMP) { it[0] - 40.0 },
        pid(0x67, "Temperatura del refrigerante (sensor 1)", "Refrig. S1", "°C", -40.0, 130.0, 2, kind = TEMP, category = C_TEMP) { d ->
            ifPresent(d, 0) { d[1] - 40.0 }
        },
        pid(0x0F, "Temperatura aire de admisión", "Admisión", "°C", -40.0, 90.0, 1, kind = TEMP, category = C_TEMP) { it[0] - 40.0 },
        pid(0x68, "Temperatura de admisión (sensor 1)", "Admisión S1", "°C", -40.0, 90.0, 2, kind = TEMP, category = C_TEMP) { d ->
            ifPresent(d, 0) { d[1] - 40.0 }
        },
        pid(0x5C, "Temperatura del aceite", "Aceite", "°C", -40.0, 150.0, 1, kind = TEMP, category = C_TEMP) { it[0] - 40.0 },
        pid(0x46, "Temperatura ambiente", "Ambiente", "°C", -40.0, 60.0, 1, kind = TEMP, category = C_TEMP) { it[0] - 40.0 },
        pid(0x3C, "Temperatura del catalizador B1S1", "Catalizador", "°C", -40.0, 900.0, 2, kind = TEMP, category = C_TEMP) { ab(it) / 10.0 - 40.0 },
        pid(0x3E, "Temperatura del catalizador B1S2", "Catalizador S2", "°C", -40.0, 900.0, 2, kind = TEMP, category = C_TEMP) { ab(it) / 10.0 - 40.0 },
        pid(0x3D, "Temperatura del catalizador B2S1", "Catalizador B2", "°C", -40.0, 900.0, 2, kind = TEMP, category = C_TEMP) { ab(it) / 10.0 - 40.0 },
        pid(0x78, "Temperatura de gases de escape (EGT) B1S1", "EGT", "°C", -40.0, 1000.0, 3, kind = TEMP, category = C_TEMP) { d ->
            ifPresent(d, 0) { bc(d) / 10.0 - 40.0 }
        },
        pid(0x7C, "Temperatura del filtro de partículas (DPF)", "Temp. DPF", "°C", -40.0, 900.0, 3, kind = TEMP, category = C_TEMP) { d ->
            ifPresent(d, 0) { bc(d) / 10.0 - 40.0 }
        },

        // ---------------- Emisiones ----------------
        pid(0x2C, "EGR comandada", "EGR", "%", 0.0, 100.0, 1, category = C_EMIS) { pct(it[0]) },
        pid(0x2D, "Error de la EGR", "Error EGR", "%", -100.0, 100.0, 1, decimals = 1, category = C_EMIS) { trim(it[0]) },
        pid(0x2E, "Purga evaporativa comandada", "Purga EVAP", "%", 0.0, 100.0, 1, category = C_EMIS) { pct(it[0]) },
        pid(0x32, "Presión de vapor del sistema EVAP", "Vapor EVAP", "Pa", -8192.0, 8192.0, 2, decimals = 1, category = C_EMIS) { d ->
            (d[0] * 256 + d[1]).toShort() / 4.0
        },
        pid(0x53, "Presión absoluta del sistema EVAP", "EVAP abs.", "kPa", 0.0, 330.0, 2, decimals = 2, category = C_EMIS) { ab(it) / 200.0 },
        pid(0x7A, "Presión diferencial del DPF", "Δp DPF", "kPa", 0.0, 100.0, 3, decimals = 2, category = C_EMIS) { d ->
            ifPresent(d, 0) { bc(d) / 100.0 }
        },
        pid(0x73, "Presión de escape", "Pres. escape", "kPa", 0.0, 655.0, 3, decimals = 1, category = C_EMIS) { d ->
            ifPresent(d, 0) { bc(d) / 100.0 }
        },

        // ---------------- Conducción ----------------
        pid(0x0D, "Velocidad del vehículo", "Velocidad", "km/h", 0.0, 220.0, 1, fast = true, category = C_DRIVE) { it[0].toDouble() },
        pid(0x49, "Posición del pedal del acelerador", "Pedal", "%", 0.0, 100.0, 1, fast = true, category = C_DRIVE) { pct(it[0]) },
        pid(0x4A, "Posición del pedal E", "Pedal E", "%", 0.0, 100.0, 1, category = C_DRIVE) { pct(it[0]) },
        pid(0x5A, "Posición relativa del pedal", "Pedal rel.", "%", 0.0, 100.0, 1, fast = true, category = C_DRIVE) { pct(it[0]) },

        // ---------------- Eléctrico ----------------
        pid(0x42, "Voltaje del módulo de control", "Voltaje ECU", "V", 0.0, 18.0, 2, decimals = 2, category = C_ELEC) { ab(it) / 1000.0 },
        pid(0x5B, "Batería del sistema híbrido", "Batería híbrida", "%", 0.0, 100.0, 1, category = C_ELEC) { pct(it[0]) },

        // ---------------- Contadores ----------------
        pid(0x1F, "Tiempo desde el arranque", "Tiempo motor", "s", 0.0, 65535.0, 2, category = C_COUNT) { ab(it) },
        pid(0x21, "Distancia con Check Engine encendido", "Dist. con MIL", "km", 0.0, 65535.0, 2, category = C_COUNT) { ab(it) },
        pid(0x31, "Distancia desde borrado de códigos", "Dist. desde borrado", "km", 0.0, 65535.0, 2, category = C_COUNT) { ab(it) },
        pid(0x30, "Calentamientos desde borrado de códigos", "Calentamientos", "", 0.0, 255.0, 1, category = C_COUNT) { it[0].toDouble() },
        pid(0x4D, "Tiempo con Check Engine encendido", "Tiempo con MIL", "min", 0.0, 65535.0, 2, category = C_COUNT) { ab(it) },
        pid(0x4E, "Tiempo desde borrado de códigos", "Tiempo desde borrado", "min", 0.0, 65535.0, 2, category = C_COUNT) { ab(it) },
        pid(0xA6, "Odómetro", "Odómetro", "km", 0.0, 999999.0, 4, decimals = 1, category = C_COUNT) { d ->
            ((d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()) / 10.0
        },

        // ---------------- Adaptador y calculadas ----------------
        Metric(VOLTAGE, "Voltaje de la batería", "Batería", "V", 10.0, 16.0, decimals = 1, category = C_ELEC),
        Metric(FUEL_RATE, "Consumo instantáneo", "Consumo", "L/h", 0.0, 30.0, decimals = 2, fast = true, category = C_FUEL),
        Metric(ECONOMY, "Rendimiento instantáneo", "Rendimiento", "km/L", 0.0, 40.0, decimals = 1, fast = true, kind = Metric.Kind.ECONOMY, category = C_FUEL),
        Metric(BOOST, "Presión de turbo (calculada)", "Turbo", "bar", -1.0, 2.5, decimals = 2, fast = true, category = C_AIR),
        Metric(TORQUE, "Torque del motor (calculado)", "Torque", "Nm", 0.0, 800.0, decimals = 0, fast = true),
        Metric(POWER, "Potencia estimada", "Potencia", "hp", 0.0, 400.0, decimals = 0, fast = true),
        Metric(ACCEL, "Aceleración longitudinal", "Aceleración", "g", -1.0, 1.0, decimals = 2, fast = true, category = C_DRIVE),
        Metric(AFR, "Relación aire/combustible", "AFR", ":1", 8.0, 25.0, decimals = 1, fast = true, category = C_FUEL),
    )

    val byKey: Map<String, Metric> = all.associateBy { it.key }

    operator fun get(key: String): Metric? = byKey[key]

    /** PIDs del catálogo (con fórmula). */
    val decodedPids: Set<Int> = all.mapNotNull { it.pid }.toSet()

    /** Magnitudes calculadas y de qué dependen (para pedir solo lo necesario). */
    val DERIVED_KEYS = setOf(FUEL_RATE, ECONOMY, BOOST, TORQUE, POWER, ACCEL, AFR)
}

/**
 * Nombres de los PIDs del modo 01 (SAE J1979) para mostrar los sensores que tu vehículo reporta
 * aunque la app aún no sepa decodificarlos. Así se pueden explorar desde la terminal.
 */
object PidNames {
    private val names = mapOf(
        0x01 to "Estado de monitores y Check Engine",
        0x02 to "Código que generó el cuadro congelado",
        0x03 to "Estado del sistema de combustible",
        0x12 to "Estado del aire secundario",
        0x13 to "Sensores de oxígeno presentes (2 bancos)",
        0x16 to "Sensor de oxígeno B1S3",
        0x17 to "Sensor de oxígeno B1S4",
        0x18 to "Sensor de oxígeno B2S1",
        0x19 to "Sensor de oxígeno B2S2",
        0x1A to "Sensor de oxígeno B2S3",
        0x1B to "Sensor de oxígeno B2S4",
        0x1C to "Norma OBD del vehículo",
        0x1D to "Sensores de oxígeno presentes (4 bancos)",
        0x1E to "Estado de toma de fuerza (PTO)",
        0x25 to "Lambda sonda B1S2 (banda ancha)",
        0x26 to "Lambda sonda B1S3 (banda ancha)",
        0x27 to "Lambda sonda B1S4 (banda ancha)",
        0x28 to "Lambda sonda B2S1 (banda ancha)",
        0x29 to "Lambda sonda B2S2 (banda ancha)",
        0x2A to "Lambda sonda B2S3 (banda ancha)",
        0x2B to "Lambda sonda B2S4 (banda ancha)",
        0x35 to "Lambda sonda B1S2 (corriente)",
        0x36 to "Lambda sonda B1S3 (corriente)",
        0x37 to "Lambda sonda B1S4 (corriente)",
        0x38 to "Lambda sonda B2S1 (corriente)",
        0x39 to "Lambda sonda B2S2 (corriente)",
        0x3A to "Lambda sonda B2S3 (corriente)",
        0x3B to "Lambda sonda B2S4 (corriente)",
        0x3F to "Temperatura del catalizador B2S2",
        0x41 to "Estado de monitores en este ciclo",
        0x4B to "Posición del pedal F",
        0x4F to "Valores máximos de lambda, voltaje, corriente y MAP",
        0x50 to "Valor máximo del sensor MAF",
        0x51 to "Tipo de combustible",
        0x54 to "Presión de vapor EVAP (rango amplio)",
        0x55 to "Ajuste corto del sensor O2 secundario B1/B3",
        0x56 to "Ajuste largo del sensor O2 secundario B1/B3",
        0x57 to "Ajuste corto del sensor O2 secundario B2/B4",
        0x58 to "Ajuste largo del sensor O2 secundario B2/B4",
        0x5F to "Norma de emisiones del vehículo",
        0x64 to "Puntos de porcentaje de torque del motor",
        0x65 to "Entradas/salidas auxiliares",
        0x66 to "Sensor de flujo de aire (MAF) A/B",
        0x69 to "EGR comandada y error (extendido)",
        0x6A to "Admisión de aire diésel comandada",
        0x6B to "Temperatura de recirculación de gases (EGR)",
        0x6C to "Actuador del acelerador comandado (extendido)",
        0x6D to "Sistema de control de presión de combustible",
        0x6E to "Sistema de control de presión de inyección",
        0x6F to "Presión de entrada del compresor del turbo",
        0x71 to "Control de geometría variable del turbo (VGT)",
        0x72 to "Control de la válvula de alivio (wastegate)",
        0x74 to "Revoluciones del turbo",
        0x75 to "Temperatura del turbo A",
        0x76 to "Temperatura del turbo B",
        0x77 to "Temperatura del enfriador de aire (intercooler)",
        0x79 to "Temperatura de gases de escape (EGT) banco 2",
        0x7B to "Filtro de partículas (DPF) banco 2",
        0x7D to "Estado de área de control NOx (NTE)",
        0x7E to "Estado de área de control de partículas (NTE)",
        0x7F to "Tiempo de funcionamiento del motor",
        0x81 to "Tiempo de funcionamiento AECD #1-#5",
        0x82 to "Tiempo de funcionamiento AECD #6-#10",
        0x83 to "Sensor de NOx",
        0x84 to "Temperatura de la superficie del múltiple",
        0x85 to "Sistema de reactivo NOx (urea / AdBlue)",
        0x86 to "Sensor de material particulado (PM)",
        0x88 to "Sistema de inducción SCR",
        0x8B to "Datos del postratamiento diésel",
        0x8C to "Sensor de O2 (banda ancha)",
        0x8D to "Posición del acelerador G",
        0x8E to "Torque de fricción del motor",
        0x8F to "Sensor de PM banco 1 y 2",
        0x90 to "Información del sistema WWH-OBD",
        0x92 to "Estado del módulo de control de combustible",
        0x94 to "Sistema de advertencia y aviso NOx",
        0x98 to "Temperatura de gases de escape banco 1",
        0x99 to "Temperatura de gases de escape banco 2",
        0x9A to "Datos del sistema híbrido / eléctrico",
        0x9B to "Sensor de calidad de reactivo (DEF)",
        0x9C to "Datos del sensor O2",
        0x9D to "Tasa de combustible del motor",
        0x9E to "Flujo de gases de escape",
        0x9F to "Porcentaje de uso del sistema de combustible",
        0xA0 to "Datos del sensor de NOx corregidos",
        0xA1 to "Concentración del sensor de NOx corregida",
        0xA2 to "Tasa de combustible por cilindro",
        0xA3 to "Presión del sistema de evaporación",
        0xA4 to "Marcha actual de la transmisión",
        0xA5 to "Dosificación de reactivo (DEF)",
        0xA7 to "Sensor de NOx (concentración)",
        0xA8 to "Sensor de NOx (corregido)",
        0xA9 to "Estado del freno ABS",
    )

    fun name(pid: Int): String = names[pid]
        ?: Metrics.all.firstOrNull { it.pid == pid }?.name
        ?: String.format(java.util.Locale.US, "PID %02X (específico del fabricante o reciente)", pid)
}
