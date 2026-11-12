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
    val description: String = "",
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
    const val IMU_ACCEL_X = "IMU_ACCEL_X"
    const val IMU_ACCEL_Y = "IMU_ACCEL_Y"
    const val IMU_ACCEL_Z = "IMU_ACCEL_Z"
    const val IMU_GYRO_X = "IMU_GYRO_X"
    const val IMU_GYRO_Y = "IMU_GYRO_Y"
    const val IMU_GYRO_Z = "IMU_GYRO_Z"
    const val IMU_HEADING = "IMU_HEADING"
    const val IMU_PITCH = "IMU_PITCH"
    const val IMU_ROLL = "IMU_ROLL"

    // Claves de PIDs usados con frecuencia
    const val FUEL_SYSTEM = "0103"
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
    const val STFT2 = "0108"
    const val LTFT2 = "0109"
    const val O2_B1S1 = "0114"
    const val O2_B1S2 = "0115"
    const val O2_B2S1 = "0118"
    const val O2_B2S2 = "0119"
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
        description: String = "",
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
        description = description,
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
        pid(0x0C, "Revoluciones del motor", "RPM", "rpm", 0.0, 8000.0, 2, fast = true,
            description = "Velocidad de giro del cigüeñal en revoluciones por minuto. En ralentí caliente suele oscilar entre 650 y 850 rpm.") { ab(it) / 4.0 },

        pid(0x04, "Carga calculada del motor", "Carga", "%", 0.0, 100.0, 1, fast = true,
            description = "Porcentaje de flujo de aire actual respecto al flujo de aire máximo disponible a las RPM actuales. En ralentí suele estar entre 15% y 30%; a aceleración a fondo se acerca al 95-100%.") { pct(it[0]) },

        pid(0x43, "Carga absoluta", "Carga abs.", "%", 0.0, 200.0, 2, fast = true,
            description = "Eficiencia volumétrica normalizada del cilindro. En motores atmosféricos llega hasta aprox. 95%; en motores turboalimentados con sobrealimentación supera el 100% (hasta 150-220%).") { ab(it) * 100.0 / 255.0 },

        pid(0x0E, "Avance de encendido", "Avance", "°", -64.0, 64.0, 1, decimals = 1, fast = true,
            description = "Ángulo de encendido de la chispa respecto al punto muerto superior (PMS) del cilindro 1. Valores positivos indican encendido adelantado (BTDC). La ECU retrasa el avance si detecta pistoneo/detonación.") { it[0] / 2.0 - 64.0 },

        pid(0x5D, "Tiempo de inyección", "Inyección", "°", -210.0, 302.0, 2, decimals = 1,
            description = "Momento del ciclo en que inicia la inyección de combustible relativo al cigüeñal.") { ab(it) / 128.0 - 210.0 },

        pid(0x61, "Torque solicitado por el conductor", "Torque pedido", "%", -125.0, 130.0, 1, fast = true,
            description = "Porcentaje de torque que el conductor demanda a través del pedal del acelerador respecto al torque de referencia.") { it[0] - 125.0 },

        pid(0x62, "Torque real del motor", "Torque real", "%", -125.0, 130.0, 1, fast = true,
            description = "Porcentaje de par motor efectivo que la ECU está entregando en el eje en este instante.") { it[0] - 125.0 },

        pid(0x63, "Torque de referencia del motor", "Torque ref.", "Nm", 0.0, 2000.0, 2,
            description = "Valor base de par máximo que utiliza la ECU para calibrar los porcentajes de torque (100% de torque = este valor en Nm).") { ab(it) },

        // ---------------- Aire y turbo ----------------
        pid(0x10, "Flujo de aire (MAF)", "MAF", "g/s", 0.0, 250.0, 2, decimals = 1, fast = true, category = C_AIR,
            description = "Sensor de flujo másico de aire en gramos por segundo. En ralentí caliente una regla aproximada es 1 g/s por cada litro de cilindrada (un 1.6L marca aprox. 1.6-2.2 g/s). A fondo escala según la potencia.") { ab(it) / 100.0 },

        pid(0x0B, "Presión del múltiple (MAP)", "MAP", "kPa", 0.0, 255.0, 1, fast = true, category = C_AIR,
            description = "Presión absoluta dentro del múltiple de admisión. Con motor apagado marca la presión atmosférica (~101 kPa a nivel del mar). En ralentí baja a 28-40 kPa por el vacío de admisión.") { it[0].toDouble() },

        pid(0x87, "Presión del múltiple (rango turbo)", "MAP turbo", "kPa", 0.0, 400.0, 3, decimals = 1, fast = true, category = C_AIR,
            description = "Lectura de MAP de alta presión para motores turbo (hasta 400 kPa / 4 bar absolutos).") { d ->
            ifPresent(d, 0) { bc(d) / 32.0 }
        },

        pid(0x70, "Presión de sobrealimentación real", "Turbo real", "kPa", 0.0, 400.0, 5, decimals = 1, fast = true, category = C_AIR,
            description = "Presión absoluta de sobrealimentación reportada por el sensor del turbo (PID 70).") { d ->
            if ((d[0] shr 1) and 1 == 1) (d[3] * 256.0 + d[4]) / 32.0 else Double.NaN
        },

        pid(0x33, "Presión barométrica", "Barométrica", "kPa", 0.0, 255.0, 1, category = C_AIR,
            description = "Presión atmosférica ambiental. A nivel del mar ronda los 101.3 kPa; en Bogotá (2600 msnm) baja a ~75 kPa.") { it[0].toDouble() },

        pid(0x11, "Posición del acelerador", "Acelerador", "%", 0.0, 100.0, 1, fast = true, category = C_AIR,
            description = "Apertura de la mariposa de admisión. En reposo marca un valor base para ralentí (alrededor de 10-18%); a fondo alcanza 80-99%.") { pct(it[0]) },

        pid(0x45, "Posición relativa del acelerador", "Acel. relativo", "%", 0.0, 100.0, 1, fast = true, category = C_AIR,
            description = "Posición de mariposa calibrada: 0% en reposo absoluto de ralentí.") { pct(it[0]) },

        pid(0x47, "Posición absoluta del acelerador B", "Acelerador B", "%", 0.0, 100.0, 1, category = C_AIR,
            description = "Segundo sensor de seguridad de la mariposa electrónica (TPS 2).") { pct(it[0]) },

        pid(0x48, "Posición absoluta del acelerador C", "Acelerador C", "%", 0.0, 100.0, 1, category = C_AIR,
            description = "Tercer sensor redundante de posición de acelerador en cuerpos de mariposa de alta seguridad.") { pct(it[0]) },

        pid(0x4C, "Actuador del acelerador comandado", "Actuador acel.", "%", 0.0, 100.0, 1, category = C_AIR,
            description = "Posición objetivo que la ECU ordena al servomotor de la mariposa de admisión.") { pct(it[0]) },

        // ---------------- Combustible y mezcla ----------------
        pid(0x03, "Estado del sistema de combustible", "Lazo comb.", "", 0.0, 16.0, 2, category = C_FUEL,
            description = "Estado del control de inyección: Lazo Cerrado (Closed Loop = la ECU ajusta la mezcla con el sensor O2/Lambda); Lazo Abierto (Open Loop = motor frío o aceleración a fondo 'WOT', ignora el sensor O2).") { it[0].toDouble() },

        pid(0x5E, "Consumo del motor (ECU)", "Consumo ECU", "L/h", 0.0, 50.0, 2, decimals = 2, fast = true, category = C_FUEL,
            description = "Caudal de combustible inyectado reportado directamente por la computadora en litros por hora.") { ab(it) / 20.0 },

        pid(0x2F, "Nivel de combustible", "Combustible", "%", 0.0, 100.0, 1, category = C_FUEL,
            description = "Porcentaje de llenado del tanque de combustible medido por el aforador/flotador.") { pct(it[0]) },

        pid(0x0A, "Presión de combustible", "Pres. combustible", "kPa", 0.0, 765.0, 1, category = C_FUEL,
            description = "Presión en la línea de combustible de baja (tanque a bomba de alta). Típicamente entre 300 y 450 kPa (3-4.5 bar).") { it[0] * 3.0 },

        pid(0x22, "Presión del riel (relativa al vacío)", "Riel rel.", "kPa", 0.0, 5177.0, 2, decimals = 1, category = C_FUEL,
            description = "Presión de inyección en riel común relativa al múltiple de admisión.") { ab(it) * 0.079 },

        pid(0x23, "Presión del riel de inyección", "Riel", "bar", 0.0, 2500.0, 2, fast = true, category = C_FUEL,
            description = "Presión de combustible en riel común (Common Rail diésel o inyección directa GDI/TSI). En ralentí diésel ronda 250-400 bar; a plena carga puede superar 1600-2200 bar.") { ab(it) / 10.0 },

        pid(0x59, "Presión absoluta del riel", "Riel abs.", "bar", 0.0, 2500.0, 2, fast = true, category = C_FUEL,
            description = "Presión absoluta de inyección directa de alta presión.") { ab(it) / 10.0 },

        pid(0x06, "Ajuste de combustible corto B1", "STFT B1", "%", -25.0, 25.0, 1, decimals = 1, fast = true, category = C_FUEL,
            description = "Ajuste de combustible a corto plazo (Banco 1). Corrección inmediata que la ECU aplica al tiempo de inyección según la lectura del sensor de oxígeno. Rango normal: -5% a +5%. Valores positivos (+) indican que la ECU detecta mezcla pobre y añade combustible. Valores negativos (-) indican que la ECU detecta mezcla rica y resta combustible. Desviaciones persistentes mayores a ±10% advierten fallas de vacío o alimentación.") { trim(it[0]) },

        pid(0x07, "Ajuste de combustible largo B1", "LTFT B1", "%", -25.0, 25.0, 1, decimals = 1, category = C_FUEL,
            description = "Ajuste de combustible a largo plazo (Banco 1). Corrección permanente aprendida y grabada en memoria por la ECU. Si el ajuste corto (STFT) permanece desviado mucho tiempo, la ECU lo absorbe aquí. Rango normal: -8% a +8%. Valores > +15% activan fallo P0171 (mezcla pobre por fugas de aire o bomba débil); valores < -15% activan fallo P0172 (mezcla rica por inyector goteando o cánister saturado).") { trim(it[0]) },

        pid(0x08, "Ajuste de combustible corto B2", "STFT B2", "%", -25.0, 25.0, 1, decimals = 1, fast = true, category = C_FUEL,
            description = "Ajuste de combustible a corto plazo en motores en V o boxer para los cilindros del Banco 2.") { trim(it[0]) },

        pid(0x09, "Ajuste de combustible largo B2", "LTFT B2", "%", -25.0, 25.0, 1, decimals = 1, category = C_FUEL,
            description = "Ajuste de combustible a largo plazo para el Banco 2 en motores con dos bancadas.") { trim(it[0]) },

        pid(0x14, "Sensor de oxígeno B1S1", "O2 B1S1", "V", 0.0, 1.275, 2, decimals = 3, fast = true, category = C_FUEL,
            description = "Sensor de O2 pre-catalizador convencional (Zirconio). En lazo cerrado oscila rápidamente entre 0.1 V (mezcla pobre) y 0.9 V (mezcla rica) unas 1 a 3 veces por segundo.") { it[0] / 200.0 },

        pid(0x15, "Sensor de oxígeno B1S2", "O2 B1S2", "V", 0.0, 1.275, 2, decimals = 3, category = C_FUEL,
            description = "Sensor de O2 post-catalizador. Monitorea el estado del catalizador. Si el catalizador está sano, su voltaje es estable (alrededor de 0.6 a 0.75 V) y casi no oscila.") { it[0] / 200.0 },

        pid(0x18, "Sensor de oxígeno B2S1", "O2 B2S1", "V", 0.0, 1.275, 2, decimals = 3, fast = true, category = C_FUEL,
            description = "Sensor de O2 pre-catalizador de la segunda bancada (motores en V o boxer).") { it[0] / 200.0 },

        pid(0x19, "Sensor de oxígeno B2S2", "O2 B2S2", "V", 0.0, 1.275, 2, decimals = 3, category = C_FUEL,
            description = "Sensor de O2 post-catalizador de la segunda bancada.") { it[0] / 200.0 },

        pid(0x24, "Lambda sonda B1S1 (banda ancha)", "λ B1S1", "λ", 0.0, 2.0, 4, decimals = 3, fast = true, category = C_FUEL,
            description = "Sonda de oxígeno de banda ancha (Wideband / A/F). Mide el coeficiente de aire λ real. λ = 1.000 representa la mezcla estequiométrica exacta (14.7:1 en gasolina). λ < 1.000 (0.85-0.90) es mezcla RICA de máxima potencia. λ > 1.000 (1.05-1.15) es mezcla POBRE de crucero económico.") { ab(it) * 2.0 / 65536.0 },

        pid(0x25, "Lambda sonda B1S2 (banda ancha)", "λ B1S2", "λ", 0.0, 2.0, 4, decimals = 3, category = C_FUEL,
            description = "Sonda lambda de banda ancha secundaria post-catalizador Banco 1.") { ab(it) * 2.0 / 65536.0 },

        pid(0x28, "Lambda sonda B2S1 (banda ancha)", "λ B2S1", "λ", 0.0, 2.0, 4, decimals = 3, fast = true, category = C_FUEL,
            description = "Sonda lambda de banda ancha Banco 2 Sensor 1 en motores en V o boxer.") { ab(it) * 2.0 / 65536.0 },

        pid(0x34, "Lambda sonda B1S1 (corriente)", "λ corriente", "mA", -128.0, 127.0, 4, decimals = 3, category = C_FUEL,
            description = "Corriente de bombeo iónica de la sonda de banda ancha en miliamperios. 0 mA = estequiométrico; corriente positiva = mezcla pobre; corriente negativa = mezcla rica.") { (ab(it) - 32768.0) / 256.0 },

        pid(0x44, "Lambda comandada", "Lambda cmd", "λ", 0.0, 2.0, 2, decimals = 3, category = C_FUEL,
            description = "Valor de Lambda objetivo que la ECU busca alcanzar en este instante según su mapa interno. En aceleración a fondo se comanda enriquecimiento (0.85-0.92); en desaceleración con corte de inyección sube a 1.99+. Comparar Lambda comandada vs Lambda real permite verificar si el sistema responde fielmente a lo pedido.") { ab(it) / 32768.0 },

        pid(0x52, "Porcentaje de etanol", "Etanol", "%", 0.0, 100.0, 1, category = C_FUEL,
            description = "Porcentaje de alcohol etílico en el combustible medido por el sensor de combustible flexible (Flex-Fuel).") { pct(it[0]) },

        // ---------------- Temperaturas ----------------
        pid(0x05, "Temperatura del refrigerante", "Refrigerante", "°C", -40.0, 130.0, 1, kind = TEMP, category = C_TEMP,
            description = "Temperatura del líquido refrigerante del motor (ECT). Rango de trabajo normal: 85°C a 102°C. Valores por encima de 108°C indican sobrecalentamiento inminente.") { it[0] - 40.0 },

        pid(0x67, "Temperatura del refrigerante (sensor 1)", "Refrig. S1", "°C", -40.0, 130.0, 2, kind = TEMP, category = C_TEMP,
            description = "Lectura del sensor de temperatura de refrigerante de alta resolución.") { d ->
            ifPresent(d, 0) { d[1] - 40.0 }
        },

        pid(0x0F, "Temperatura aire de admisión", "Admisión", "°C", -40.0, 90.0, 1, kind = TEMP, category = C_TEMP,
            description = "Temperatura del aire que entra al motor (IAT). Aire más frío es más denso y genera más potencia. Suele estar entre 5°C y 25°C por encima de la temperatura ambiente.") { it[0] - 40.0 },

        pid(0x68, "Temperatura de admisión (sensor 1)", "Admisión S1", "°C", -40.0, 90.0, 2, kind = TEMP, category = C_TEMP,
            description = "Sensor 1 de temperatura de aire en el conducto de admisión.") { d ->
            ifPresent(d, 0) { d[1] - 40.0 }
        },

        pid(0x5C, "Temperatura del aceite", "Aceite", "°C", -40.0, 150.0, 1, kind = TEMP, category = C_TEMP,
            description = "Temperatura del lubricante en el cárter. Rango óptimo de viscosidad: 90°C a 115°C.") { it[0] - 40.0 },

        pid(0x46, "Temperatura ambiente", "Ambiente", "°C", -40.0, 60.0, 1, kind = TEMP, category = C_TEMP,
            description = "Temperatura exterior del entorno medida en la carrocería/paragolpes.") { it[0] - 40.0 },

        pid(0x3C, "Temperatura del catalizador B1S1", "Catalizador", "°C", -40.0, 900.0, 2, kind = TEMP, category = C_TEMP,
            description = "Temperatura dentro del convertidor catalítico Banco 1. Temperatura de encendido químico: > 400°C; operación típica: 500°C a 800°C. Si supera los 950°C el catalizador sufre daño por fusión.") { ab(it) / 10.0 - 40.0 },

        pid(0x3E, "Temperatura del catalizador B1S2", "Catalizador S2", "°C", -40.0, 900.0, 2, kind = TEMP, category = C_TEMP,
            description = "Temperatura en la salida del catalizador Banco 1.") { ab(it) / 10.0 - 40.0 },

        pid(0x3D, "Temperatura del catalizador B2S1", "Catalizador B2", "°C", -40.0, 900.0, 2, kind = TEMP, category = C_TEMP,
            description = "Temperatura del catalizador de la bancada 2.") { ab(it) / 10.0 - 40.0 },

        pid(0x3F, "Temperatura del catalizador B2S2", "Cat. B2S2", "°C", -40.0, 900.0, 2, kind = TEMP, category = C_TEMP,
            description = "Temperatura del segundo catalizador en la bancada 2.") { ab(it) / 10.0 - 40.0 },

        pid(0x6B, "Temperatura de gases EGR", "Temp. EGR", "°C", -40.0, 300.0, 2, kind = TEMP, category = C_TEMP,
            description = "Temperatura de los gases de escape recirculados hacia la admisión por la válvula EGR.") { d ->
            ifPresent(d, 0) { d[1] - 40.0 }
        },

        pid(0x77, "Temperatura del enfriador de aire (intercooler)", "Temp. CAC", "°C", -40.0, 150.0, 3, kind = TEMP, category = C_TEMP,
            description = "Temperatura del aire comprimido a la salida del intercooler (Charge Air Cooler).") { d ->
            ifPresent(d, 0) { d[1] - 40.0 }
        },

        pid(0x78, "Temperatura de gases de escape (EGT) B1", "EGT B1", "°C", -40.0, 1000.0, 3, kind = TEMP, category = C_TEMP,
            description = "Temperatura en el múltiple de escape antes del turbo o filtro de partículas (EGT). Crítica en motores diésel para evitar daño térmico.") { d ->
            ifPresent(d, 0) { bc(d) / 10.0 - 40.0 }
        },

        pid(0x79, "Temperatura de gases de escape (EGT) B2", "EGT B2", "°C", -40.0, 1000.0, 3, kind = TEMP, category = C_TEMP,
            description = "Temperatura de escape en la bancada 2.") { d ->
            ifPresent(d, 0) { bc(d) / 10.0 - 40.0 }
        },

        pid(0x7C, "Temperatura del filtro de partículas (DPF)", "Temp. DPF", "°C", -40.0, 900.0, 3, kind = TEMP, category = C_TEMP,
            description = "Temperatura dentro del filtro cerámico DPF. En regeneración activa sube a 550°C - 650°C para incinerar el hollín.") { d ->
            ifPresent(d, 0) { bc(d) / 10.0 - 40.0 }
        },

        pid(0x7B, "Temperatura del DPF banco 2", "Temp. DPF B2", "°C", -40.0, 900.0, 3, kind = TEMP, category = C_TEMP,
            description = "Temperatura del filtro de partículas diésel en motores con doble escape.") { d ->
            ifPresent(d, 0) { bc(d) / 10.0 - 40.0 }
        },

        // ---------------- Emisiones ----------------
        pid(0x2C, "EGR comandada", "EGR", "%", 0.0, 100.0, 1, category = C_EMIS,
            description = "Apertura solicitada por la ECU para la válvula de recirculación de gases de escape (EGR). Reduce emisiones de óxidos de nitrógeno (NOx).") { pct(it[0]) },

        pid(0x2D, "Error de la EGR", "Error EGR", "%", -100.0, 100.0, 1, decimals = 1, category = C_EMIS,
            description = "Diferencia entre la posición real y la comandada de la válvula EGR. Desviaciones persistentes indican atascamiento por carbón.") { trim(it[0]) },

        pid(0x2E, "Purga evaporativa comandada", "Purga EVAP", "%", 0.0, 100.0, 1, category = C_EMIS,
            description = "Porcentaje de apertura de la válvula cánister que aspira los vapores del tanque de gasolina para quemarlos en el motor.") { pct(it[0]) },

        pid(0x32, "Presión de vapor del sistema EVAP", "Vapor EVAP", "Pa", -8192.0, 8192.0, 2, decimals = 1, category = C_EMIS,
            description = "Presión de vapor en el tanque de combustible. Utilizada en los autodiagnósticos de fugas evaporativas (EVAP leaks).") { d ->
            (d[0] * 256 + d[1]).toShort() / 4.0
        },

        pid(0x53, "Presión absoluta del sistema EVAP", "EVAP abs.", "kPa", 0.0, 330.0, 2, decimals = 2, category = C_EMIS,
            description = "Presión absoluta medida en el sistema de combustible y cánister.") { ab(it) / 200.0 },

        pid(0x54, "Presión de vapor EVAP (rango amplio)", "Vapor amplio", "Pa", -32768.0, 32767.0, 2, decimals = 1, category = C_EMIS,
            description = "Sensor de presión diferencial EVAP de escala amplia.") { d ->
            (d[0] * 256.0 + d[1]) - 32767.0
        },

        pid(0x7A, "Presión diferencial del DPF", "Δp DPF", "kPa", 0.0, 100.0, 3, decimals = 2, category = C_EMIS,
            description = "Diferencia de presión entre la entrada y salida del filtro DPF. A mayor restricción por hollín, mayor es esta caída de presión.") { d ->
            ifPresent(d, 0) { bc(d) / 100.0 }
        },

        pid(0x73, "Presión de escape", "Pres. escape", "kPa", 0.0, 655.0, 3, decimals = 1, category = C_EMIS,
            description = "Presión absoluta de contrapresión en el sistema de escape antes de los catalizadores.") { d ->
            ifPresent(d, 0) { bc(d) / 100.0 }
        },

        pid(0x85, "Nivel de reactivo de emisiones (AdBlue / DEF)", "Nivel AdBlue", "%", 0.0, 100.0, 2, decimals = 1, category = C_EMIS,
            description = "Nivel del tanque de urea / AdBlue para el sistema de reducción catalítica selectiva (SCR) en vehículos diésel.") { d ->
            ifPresent(d, 0) { d[1] * 100.0 / 255.0 }
        },

        // ---------------- Conducción ----------------
        pid(0x0D, "Velocidad del vehículo", "Velocidad", "km/h", 0.0, 260.0, 1, fast = true, category = C_DRIVE,
            description = "Velocidad del vehículo leída del sensor de velocidad de salida de transmisión (VSS) o del módulo de frenos ABS.") { it[0].toDouble() },

        pid(0x49, "Posición del pedal del acelerador", "Pedal", "%", 0.0, 100.0, 1, fast = true, category = C_DRIVE,
            description = "Grado de presión física que el pie del conductor ejerce sobre el pedal del acelerador (0% = pedal suelto, 100% = a fondo).") { pct(it[0]) },

        pid(0x4A, "Posición del pedal E", "Pedal E", "%", 0.0, 100.0, 1, category = C_DRIVE,
            description = "Sensor secundario del pedal del acelerador (APP 2).") { pct(it[0]) },

        pid(0x5A, "Posición relativa del pedal", "Pedal rel.", "%", 0.0, 100.0, 1, fast = true, category = C_DRIVE,
            description = "Posición relativa normalizada del pedal.") { pct(it[0]) },

        pid(0xA4, "Relación / marcha de transmisión", "Marcha", "", 0.0, 10.0, 4, decimals = 2, category = C_DRIVE,
            description = "Relación de transmisión engranada reportada por el módulo TCM.") { d ->
            (d[0] * 256.0 + d[1]) / 1000.0
        },

        // ---------------- Eléctrico ----------------
        pid(0x42, "Voltaje del módulo de control", "Voltaje ECU", "V", 0.0, 18.0, 2, decimals = 2, category = C_ELEC,
            description = "Tensión de alimentación interna recibida por la computadora del motor. Con motor encendido debe marcar entre 13.5 y 14.6 V (alternador cargando).") { ab(it) / 1000.0 },

        pid(0x5B, "Batería del sistema híbrido", "Batería híbrida", "%", 0.0, 100.0, 1, category = C_ELEC,
            description = "Estado de carga (SOC) del paquete de baterías de tracción de alto voltaje en vehículos híbridos o enchufables.") { pct(it[0]) },

        pid(0x1C, "Norma OBD del vehículo", "Norma OBD", "", 0.0, 35.0, 1, category = C_ELEC,
            description = "Estándar de diagnóstico que cumple la ECU (OBD-II federal, EOBD europeo, OBD-I, etc.).") { it[0].toDouble() },

        // ---------------- Contadores ----------------
        pid(0x1F, "Tiempo desde el arranque", "Tiempo motor", "s", 0.0, 65535.0, 2, category = C_COUNT,
            description = "Segundos transcurridos de forma continua desde que el motor fue puesto en marcha en este ciclo.") { ab(it) },

        pid(0x21, "Distancia con Check Engine encendido", "Dist. con MIL", "km", 0.0, 65535.0, 2, category = C_COUNT,
            description = "Kilómetros recorridos por el vehículo con el testigo de falla encendido.") { ab(it) },

        pid(0x31, "Distancia desde borrado de códigos", "Dist. desde borrado", "km", 0.0, 65535.0, 2, category = C_COUNT,
            description = "Kilómetros recorridos desde que se ejecutó por última vez un borrado de códigos DTC.") { ab(it) },

        pid(0x30, "Calentamientos desde borrado de códigos", "Calentamientos", "", 0.0, 255.0, 1, category = C_COUNT,
            description = "Ciclos de calentamiento (subida de al menos 22°C alcanzando mínimo 70°C) completados desde el último borrado.") { it[0].toDouble() },

        pid(0x4D, "Tiempo con Check Engine encendido", "Tiempo con MIL", "min", 0.0, 65535.0, 2, category = C_COUNT,
            description = "Minutos de funcionamiento acumulados con la luz de Check Engine activa.") { ab(it) },

        pid(0x4E, "Tiempo desde borrado de códigos", "Tiempo desde borrado", "min", 0.0, 65535.0, 2, category = C_COUNT,
            description = "Minutos acumulados con el motor encendido desde que se borraron los códigos de error.") { ab(it) },

        pid(0xA6, "Odómetro", "Odómetro", "km", 0.0, 999999.0, 4, decimals = 1, category = C_COUNT,
            description = "Kilometraje total acumulado del vehículo reportado por la ECU.") { d ->
            ((d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()) / 10.0
        },

        // ---------------- Adaptador y calculadas ----------------
        Metric(VOLTAGE, "Voltaje de la batería", "Batería", "V", 10.0, 16.0, decimals = 1, category = C_ELEC,
            description = "Tensión medida directamente en el pin 16 del conector OBD por el chip ELM327. Permite vigilar el estado de la batería y la carga del alternador."),

        Metric(FUEL_RATE, "Consumo instantáneo", "Consumo", "L/h", 0.0, 40.0, decimals = 2, fast = true, category = C_FUEL,
            description = "Litros de combustible consumidos por hora. En ralentí caliente suele oscilar entre 0.6 y 1.2 L/h. Si no hay sensor nativo, se calcula a partir del MAF o Speed-Density."),

        Metric(ECONOMY, "Rendimiento instantáneo", "Rendimiento", "km/L", 0.0, 40.0, decimals = 1, fast = true, kind = Metric.Kind.ECONOMY, category = C_FUEL,
            description = "Kilómetros recorridos por unidad de combustible. Calculado combinando velocidad y consumo instantáneo en tiempo real."),

        Metric(BOOST, "Presión de turbo (calculada)", "Turbo", "bar", -1.0, 2.5, decimals = 2, fast = true, category = C_AIR,
            description = "Presión relativa en el múltiple de admisión (Presión absoluta MAP − Presión barométrica atmosférica). Valores negativos indican vacío; valores positivos indican sobrealimentación por turbo."),

        Metric(TORQUE, "Torque del motor (estimado)", "Torque", "Nm", 0.0, 800.0, decimals = 0, fast = true,
            description = "Par motor instantáneo en Newton-metro. Calculado a partir de los PIDs 62/63 nativos, o modelado según la potencia máxima declarada del vehículo, el porcentaje de carga y el flujo de aire/combustible."),

        Metric(POWER, "Potencia estimada", "Potencia", "hp", 0.0, 500.0, decimals = 0, fast = true,
            description = "Caballos de fuerza instantáneos al motor. Calculados a partir del par motor y las RPM, o mediante flujo de combustible, masa de aire MAF o aceleración inercial."),

        Metric(ACCEL, "Aceleración longitudinal", "Aceleración", "g", -1.0, 1.0, decimals = 2, fast = true, category = C_DRIVE,
            description = "Aceleración del vehículo calculada derivada de la variación de velocidad en el tiempo."),

        Metric(AFR, "Relación aire/combustible", "AFR", ":1", 8.0, 25.0, decimals = 1, fast = true, category = C_FUEL,
            description = "Relación másica aire-combustible en tiempo real (Lambda × factor estequiométrico: 14.7 en gasolina, 14.5 en diésel). Mezcla rica < 14.7; mezcla pobre > 14.7."),

        Metric(IMU_ACCEL_X, "Aceleración del teléfono · eje X", "IMU X", "m/s²", -40.0, 40.0, decimals = 2, fast = true, category = C_DRIVE,
            description = "Aceleración lineal del teléfono en su eje X, medida por el IMU del celular. Depende de la orientación y fijación del teléfono; no es una lectura de la ECU ni del sensor de estabilidad del vehículo."),
        Metric(IMU_ACCEL_Y, "Aceleración del teléfono · eje Y", "IMU Y", "m/s²", -40.0, 40.0, decimals = 2, fast = true, category = C_DRIVE,
            description = "Aceleración lineal del teléfono en su eje Y, medida por el IMU del celular. Depende de la orientación y fijación del teléfono; no es una lectura de la ECU ni del sensor de estabilidad del vehículo."),
        Metric(IMU_ACCEL_Z, "Aceleración del teléfono · eje Z", "IMU Z", "m/s²", -40.0, 40.0, decimals = 2, fast = true, category = C_DRIVE,
            description = "Aceleración lineal del teléfono en su eje Z, medida por el IMU del celular. Depende de la orientación y fijación del teléfono; no es una lectura de la ECU ni del sensor de estabilidad del vehículo."),
        Metric(IMU_GYRO_X, "Giro del teléfono · eje X", "Giro X", "°/s", -2000.0, 2000.0, decimals = 1, fast = true, category = C_DRIVE,
            description = "Velocidad angular del teléfono alrededor de su eje X, convertida de radianes a grados por segundo. No equivale al ángulo del timón ni al sensor yaw del ABS/ESP."),
        Metric(IMU_GYRO_Y, "Giro del teléfono · eje Y", "Giro Y", "°/s", -2000.0, 2000.0, decimals = 1, fast = true, category = C_DRIVE,
            description = "Velocidad angular del teléfono alrededor de su eje Y, convertida de radianes a grados por segundo. No equivale al ángulo del timón ni al sensor yaw del ABS/ESP."),
        Metric(IMU_GYRO_Z, "Giro del teléfono · eje Z", "Giro Z", "°/s", -2000.0, 2000.0, decimals = 1, fast = true, category = C_DRIVE,
            description = "Velocidad angular del teléfono alrededor de su eje Z, convertida de radianes a grados por segundo. No equivale al ángulo del timón ni al sensor yaw del ABS/ESP."),
        Metric(IMU_HEADING, "Orientación del teléfono · rumbo", "Rumbo", "°", 0.0, 360.0, decimals = 1, fast = true, category = C_DRIVE,
            description = "Rumbo estimado por el vector de rotación del teléfono. Puede depender del magnetómetro y no representa el rumbo del vehículo con precisión de navegación."),
        Metric(IMU_PITCH, "Orientación del teléfono · inclinación", "Inclinación", "°", -180.0, 180.0, decimals = 1, fast = true, category = C_DRIVE,
            description = "Inclinación adelante/atrás calculada por el vector de rotación del teléfono. Describe la orientación del celular, no la inclinación certificada del chasis."),
        Metric(IMU_ROLL, "Orientación del teléfono · balanceo", "Balanceo", "°", -180.0, 180.0, decimals = 1, fast = true, category = C_DRIVE,
            description = "Inclinación lateral calculada por el vector de rotación del teléfono. Describe la orientación del celular, no el ángulo de giro del timón."),
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
