package com.nguevara.obdpulse.obd

import java.util.Locale

enum class DtcKind(val label: String) {
    STORED("Confirmado"),
    PENDING("Pendiente"),
    PERMANENT("Permanente"),
}

enum class Severity(val label: String) {
    HIGH("Alta"),
    MEDIUM("Media"),
    LOW("Baja"),
}

data class DtcItem(
    val code: String,
    val kind: DtcKind,
    val description: String,
    val system: String,
    val severity: Severity,
)

object DtcParser {

    /**
     * Cada código ocupa 2 bytes. Los 2 bits altos del primer byte dan la letra:
     *
     *   byte A = 0x03 → 00 00 0011      byte B = 0x01
     *            ││ ││ └┴┴┴─ 3.er dígito (hex): 3
     *            ││ └┴────── 2.º dígito: 0
     *            └┴───────── letra: 00=P, 01=C, 10=B, 11=U
     *   Resultado: P0301
     */
    fun decode(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 0x03]
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        return String.format(Locale.US, "%c%d%X%02X", letter, d1, d2, b)
    }

    /**
     * Convierte las tramas de una respuesta 03/07/0A en códigos.
     *
     * CAN (ISO 15765):     43 02 03 01 04 20     → byte de cantidad (02) + pares
     *                      (multi-trama si son muchos: "0:", "1:"…, ya separados en [Frame.index])
     * No-CAN (K-Line/PWM): 43 03 01 04 20 00 00  → 3 códigos por línea, relleno 00 00, sin cantidad
     */
    fun parse(frames: List<Frame>, responseByte: Int, isCan: Boolean): List<String> {
        val codes = linkedSetOf<String>()

        if (isCan) {
            // Agrupar tramas en mensajes: una trama sin índice o con índice 0 abre un mensaje
            // nuevo (cada ECU que responde envía el suyo); las tramas 1, 2… lo continúan.
            val messages = mutableListOf<MutableList<Int>>()
            for (f in frames) {
                if (f.index == null || f.index == 0 || messages.isEmpty()) {
                    messages += f.bytes.toMutableList()
                } else {
                    messages.last() += f.bytes.toList()
                }
            }
            for (msg in messages) {
                if (msg.isEmpty() || msg[0] != responseByte) continue
                val rest = msg.drop(1)                     // quitar 43 / 47 / 4A
                val hasCount = rest.size % 2 == 1          // cantidad + pares = impar
                val count = if (hasCount) rest[0] else rest.size / 2
                val pairs = if (hasCount) rest.drop(1) else rest
                for (i in 0 until minOf(count, pairs.size / 2)) {
                    val a = pairs[i * 2]
                    val b = pairs[i * 2 + 1]
                    if (a == 0 && b == 0) continue
                    codes += decode(a, b)
                }
            }
        } else {
            for (f in frames) {
                val bytes = f.bytes
                if (bytes.isEmpty() || bytes[0] != responseByte) continue
                var i = 1
                while (i + 1 < bytes.size) {
                    val a = bytes[i]
                    val b = bytes[i + 1]
                    if (!(a == 0 && b == 0)) codes += decode(a, b)
                    i += 2
                }
            }
        }
        return codes.toList()
    }
}

/** Descripciones en español de los códigos genéricos (SAE J2012) más frecuentes. */
object DtcInfo {

    fun system(code: String): String = when (code.firstOrNull()) {
        'P' -> "Motor y transmisión"
        'C' -> "Chasis"
        'B' -> "Carrocería"
        'U' -> "Red de comunicación"
        else -> "Desconocido"
    }

    fun describe(code: String): String = KNOWN[code] ?: generic(code)

    fun severity(code: String): Severity {
        if (code in HIGH) return Severity.HIGH
        if (code.startsWith("P03") && code <= "P0312") return Severity.HIGH // fallos de encendido
        if (code.startsWith("U01")) return Severity.HIGH
        if (code in LOW || code.startsWith("P044") || code.startsWith("P045")) return Severity.LOW
        return Severity.MEDIUM
    }

    fun item(code: String, kind: DtcKind) = DtcItem(code, kind, describe(code), system(code), severity(code))

    private fun generic(code: String): String {
        if (code.length < 5) return "Código no reconocido"
        if (code[0] != 'P') {
            return "Código de ${system(code).lowercase()}. Consulta el manual del fabricante."
        }
        val manufacturer = code[1] == '1' || code[1] == '3'
        val area = when (code[2]) {
            '0' -> "Medición de combustible/aire y emisiones auxiliares"
            '1', '2' -> "Medición de combustible y aire"
            '3' -> "Sistema de encendido o fallos de encendido"
            '4' -> "Control auxiliar de emisiones"
            '5' -> "Control de velocidad y de ralentí"
            '6' -> "Computadora y circuitos de salida"
            '7', '8', '9' -> "Transmisión"
            'A', 'B', 'C' -> "Sistema de propulsión híbrida"
            else -> "Tren motriz"
        }
        return if (manufacturer) "$area (código específico del fabricante)" else area
    }

    private val HIGH = setOf("P0217", "P0218", "P0219", "P0335", "P0340", "P0562", "P0600", "P0601", "P0604", "P0605", "P0606", "P0230", "P0520", "P0524")
    private val LOW = setOf("P0420", "P0430", "P0128", "P0455", "P0456", "P0457", "P0442")

    private val KNOWN: Map<String, String> = buildMap {
        put("P0010", "Actuador de posición del árbol de levas \"A\" — circuito (banco 1)")
        put("P0011", "Sincronización del árbol de levas \"A\" demasiado adelantada (banco 1)")
        put("P0012", "Sincronización del árbol de levas \"A\" demasiado retrasada (banco 1)")
        put("P0016", "Correlación cigüeñal / árbol de levas (banco 1, sensor A)")
        put("P0030", "Calentador del sensor de O2 — circuito de control (banco 1, sensor 1)")
        put("P0036", "Calentador del sensor de O2 — circuito de control (banco 1, sensor 2)")
        put("P0100", "Sensor de flujo de aire (MAF) — circuito")
        put("P0101", "Sensor de flujo de aire (MAF) — rango/desempeño")
        put("P0102", "Sensor de flujo de aire (MAF) — señal baja")
        put("P0103", "Sensor de flujo de aire (MAF) — señal alta")
        put("P0105", "Sensor de presión del múltiple (MAP) — circuito")
        put("P0106", "Sensor de presión del múltiple (MAP) — rango/desempeño")
        put("P0107", "Sensor de presión del múltiple (MAP) — señal baja")
        put("P0108", "Sensor de presión del múltiple (MAP) — señal alta")
        put("P0110", "Sensor de temperatura del aire de admisión — circuito")
        put("P0112", "Sensor de temperatura del aire de admisión — señal baja")
        put("P0113", "Sensor de temperatura del aire de admisión — señal alta")
        put("P0115", "Sensor de temperatura del refrigerante — circuito")
        put("P0116", "Sensor de temperatura del refrigerante — rango/desempeño")
        put("P0117", "Sensor de temperatura del refrigerante — señal baja")
        put("P0118", "Sensor de temperatura del refrigerante — señal alta")
        put("P0120", "Sensor de posición del acelerador (TPS) — circuito")
        put("P0121", "Sensor de posición del acelerador (TPS) — rango/desempeño")
        put("P0122", "Sensor de posición del acelerador (TPS) — señal baja")
        put("P0123", "Sensor de posición del acelerador (TPS) — señal alta")
        put("P0125", "Temperatura del refrigerante insuficiente para control en lazo cerrado")
        put("P0128", "Termostato: el motor no alcanza la temperatura de operación")
        put("P0130", "Sensor de O2 — circuito (banco 1, sensor 1)")
        put("P0131", "Sensor de O2 — voltaje bajo (banco 1, sensor 1)")
        put("P0132", "Sensor de O2 — voltaje alto (banco 1, sensor 1)")
        put("P0133", "Sensor de O2 — respuesta lenta (banco 1, sensor 1)")
        put("P0134", "Sensor de O2 — sin actividad (banco 1, sensor 1)")
        put("P0135", "Calentador del sensor de O2 — circuito (banco 1, sensor 1)")
        put("P0136", "Sensor de O2 — circuito (banco 1, sensor 2)")
        put("P0137", "Sensor de O2 — voltaje bajo (banco 1, sensor 2)")
        put("P0138", "Sensor de O2 — voltaje alto (banco 1, sensor 2)")
        put("P0140", "Sensor de O2 — sin actividad (banco 1, sensor 2)")
        put("P0141", "Calentador del sensor de O2 — circuito (banco 1, sensor 2)")
        put("P0171", "Mezcla demasiado pobre (banco 1): posible fuga de vacío, MAF sucio o baja presión de combustible")
        put("P0172", "Mezcla demasiado rica (banco 1): posibles inyectores goteando o sensor defectuoso")
        put("P0174", "Mezcla demasiado pobre (banco 2)")
        put("P0175", "Mezcla demasiado rica (banco 2)")
        put("P0200", "Circuito de inyectores")
        for (n in 1..8) put("P020$n", "Inyector del cilindro $n — circuito")
        put("P0217", "Sobrecalentamiento del motor")
        put("P0219", "Sobrerrevolución del motor")
        put("P0230", "Bomba de combustible — circuito primario")
        put("P0261", "Inyector del cilindro 1 — señal baja")
        put("P0300", "Fallos de encendido aleatorios o en varios cilindros")
        for (n in 1..9) put("P030$n", "Fallo de encendido detectado en el cilindro $n")
        for (n in 10..12) put("P03$n", "Fallo de encendido detectado en el cilindro $n")
        put("P0325", "Sensor de detonación 1 — circuito (banco 1)")
        put("P0327", "Sensor de detonación 1 — señal baja (banco 1)")
        put("P0328", "Sensor de detonación 1 — señal alta (banco 1)")
        put("P0335", "Sensor de posición del cigüeñal (CKP) — circuito")
        put("P0336", "Sensor de posición del cigüeñal (CKP) — rango/desempeño")
        put("P0340", "Sensor de posición del árbol de levas (CMP) — circuito")
        put("P0341", "Sensor de posición del árbol de levas (CMP) — rango/desempeño")
        for (n in 1..8) put("P035$n", "Bobina de encendido ${('A' + n - 1)} — circuito primario/secundario")
        put("P0400", "Recirculación de gases de escape (EGR) — flujo")
        put("P0401", "Recirculación de gases de escape (EGR) — flujo insuficiente")
        put("P0402", "Recirculación de gases de escape (EGR) — flujo excesivo")
        put("P0403", "Recirculación de gases de escape (EGR) — circuito")
        put("P0420", "Eficiencia del catalizador por debajo del umbral (banco 1)")
        put("P0430", "Eficiencia del catalizador por debajo del umbral (banco 2)")
        put("P0440", "Sistema de control de emisiones evaporativas (EVAP)")
        put("P0441", "EVAP — flujo de purga incorrecto")
        put("P0442", "EVAP — fuga pequeña detectada")
        put("P0443", "EVAP — válvula de purga, circuito")
        put("P0446", "EVAP — control de ventilación")
        put("P0455", "EVAP — fuga grande detectada")
        put("P0456", "EVAP — fuga muy pequeña detectada")
        put("P0457", "EVAP — fuga: tapa del combustible floja o ausente")
        put("P0500", "Sensor de velocidad del vehículo (VSS)")
        put("P0505", "Sistema de control del ralentí (IAC)")
        put("P0506", "Ralentí más bajo de lo esperado")
        put("P0507", "Ralentí más alto de lo esperado")
        put("P0520", "Sensor de presión de aceite — circuito")
        put("P0524", "Presión de aceite del motor demasiado baja")
        put("P0560", "Voltaje del sistema")
        put("P0562", "Voltaje del sistema bajo")
        put("P0563", "Voltaje del sistema alto")
        put("P0600", "Enlace de comunicación serial del módulo de control")
        put("P0601", "Error de memoria interna del módulo de control (checksum)")
        put("P0604", "Error de memoria RAM del módulo de control")
        put("P0605", "Error de memoria ROM del módulo de control")
        put("P0606", "Falla del procesador del módulo de control")
        put("P0700", "Sistema de control de la transmisión (hay códigos en el TCM)")
        put("P0705", "Sensor de rango de la transmisión — circuito")
        put("P0715", "Sensor de velocidad de entrada / turbina — circuito")
        put("P0720", "Sensor de velocidad de salida — circuito")
        put("P0730", "Relación de marcha incorrecta")
        put("P0740", "Embrague del convertidor de par — circuito")
        put("P0750", "Solenoide de cambios \"A\"")
        put("P0755", "Solenoide de cambios \"B\"")
        put("U0001", "Bus CAN de alta velocidad")
        put("U0073", "Bus de comunicación del módulo de control apagado")
        put("U0100", "Pérdida de comunicación con el ECM/PCM")
        put("U0101", "Pérdida de comunicación con el módulo de transmisión (TCM)")
        put("U0121", "Pérdida de comunicación con el módulo ABS")
        put("U0140", "Pérdida de comunicación con el módulo de carrocería (BCM)")
        put("U0155", "Pérdida de comunicación con el tablero de instrumentos")
        put("C0035", "Sensor de velocidad de rueda delantera izquierda")
        put("C0040", "Sensor de velocidad de rueda delantera derecha")
        put("C0045", "Sensor de velocidad de rueda trasera izquierda")
        put("C0050", "Sensor de velocidad de rueda trasera derecha")
    }
}
