package com.nguevara.obdpulse.vehicle

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

/** Lo que se pudo deducir de un VIN (sin internet o con la API de NHTSA). */
data class VinInfo(
    val vin: String,
    val make: String?,
    val model: String?,
    val year: Int?,
    val engine: String?,
    val displacementL: Double?,
    val fuel: Fuel?,
    val drive: Drive?,
    val country: String?,
    val source: String,
    val warning: String? = null,
)

/**
 * Decodificación del VIN sin internet:
 *  - posiciones 1-3 (WMI): fabricante y país
 *  - posición 10: año modelo (ciclo de 30 años; se toma el más reciente que no sea futuro)
 */
object VinDecoder {

    private val VALID = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    fun isValid(vin: String): Boolean = VALID.matches(vin.trim().uppercase(Locale.US))

    /** Fabricantes frecuentes en Colombia y Latinoamérica (código WMI → marca, país). */
    private val WMI = mapOf(
        // Nissan
        "JN1" to ("Nissan" to "Japón"), "JN6" to ("Nissan" to "Japón"), "JN8" to ("Nissan" to "Japón"),
        "3N1" to ("Nissan" to "México"), "3N6" to ("Nissan" to "México"), "3N8" to ("Nissan" to "México"),
        "MNT" to ("Nissan" to "Tailandia"), "VSK" to ("Nissan" to "España"), "94D" to ("Nissan" to "Brasil"),
        "1N4" to ("Nissan" to "EE. UU."), "1N6" to ("Nissan" to "EE. UU."), "5N1" to ("Nissan" to "EE. UU."),
        // Subaru
        "JF1" to ("Subaru" to "Japón"), "JF2" to ("Subaru" to "Japón"), "4S3" to ("Subaru" to "EE. UU."), "4S4" to ("Subaru" to "EE. UU."),
        // Renault
        "9FB" to ("Renault" to "Colombia"), "93Y" to ("Renault" to "Brasil"), "8A1" to ("Renault" to "Argentina"),
        "VF1" to ("Renault" to "Francia"), "UU1" to ("Dacia" to "Rumania"), "VF6" to ("Renault" to "Francia"),
        // Chevrolet / GM
        "9GA" to ("Chevrolet" to "Colombia"), "9BG" to ("Chevrolet" to "Brasil"), "8AG" to ("Chevrolet" to "Argentina"),
        "KL1" to ("Chevrolet" to "Corea"), "KLA" to ("Chevrolet" to "Corea"), "3G1" to ("Chevrolet" to "México"),
        "1G1" to ("Chevrolet" to "EE. UU."), "1GC" to ("Chevrolet" to "EE. UU."), "MPA" to ("Isuzu" to "Tailandia"),
        "8LB" to ("Chevrolet" to "Ecuador"),
        // Toyota
        "JTD" to ("Toyota" to "Japón"), "JTE" to ("Toyota" to "Japón"), "JTM" to ("Toyota" to "Japón"), "JT2" to ("Toyota" to "Japón"),
        "MR0" to ("Toyota" to "Tailandia"), "MR2" to ("Toyota" to "Tailandia"), "8AJ" to ("Toyota" to "Argentina"),
        "9BR" to ("Toyota" to "Brasil"), "5TD" to ("Toyota" to "EE. UU."), "4T1" to ("Toyota" to "EE. UU."), "AHT" to ("Toyota" to "Sudáfrica"),
        // Mazda
        "JM1" to ("Mazda" to "Japón"), "JM3" to ("Mazda" to "Japón"), "JM7" to ("Mazda" to "Japón"), "3MZ" to ("Mazda" to "México"),
        "MM0" to ("Mazda" to "Tailandia"), "9FC" to ("Mazda" to "Colombia"),
        // Hyundai / Kia
        "KMH" to ("Hyundai" to "Corea"), "KM8" to ("Hyundai" to "Corea"), "MALA" to ("Hyundai" to "India"),
        "KNA" to ("Kia" to "Corea"), "KND" to ("Kia" to "Corea"), "KNE" to ("Kia" to "Corea"), "3KP" to ("Kia" to "México"),
        // Volkswagen
        "9BW" to ("Volkswagen" to "Brasil"), "3VW" to ("Volkswagen" to "México"), "WVW" to ("Volkswagen" to "Alemania"),
        "WVG" to ("Volkswagen" to "Alemania"), "8AW" to ("Volkswagen" to "Argentina"),
        // Ford
        "1FA" to ("Ford" to "EE. UU."), "1FT" to ("Ford" to "EE. UU."), "1FM" to ("Ford" to "EE. UU."), "3FA" to ("Ford" to "México"),
        "9BF" to ("Ford" to "Brasil"), "8AF" to ("Ford" to "Argentina"), "MNB" to ("Ford" to "Tailandia"), "WF0" to ("Ford" to "Alemania"),
        // Otros
        "JHM" to ("Honda" to "Japón"), "1HG" to ("Honda" to "EE. UU."), "93H" to ("Honda" to "Brasil"),
        "JMB" to ("Mitsubishi" to "Japón"), "MMB" to ("Mitsubishi" to "Tailandia"), "JA3" to ("Mitsubishi" to "Japón"),
        "JS2" to ("Suzuki" to "Japón"), "JS3" to ("Suzuki" to "Japón"), "MA3" to ("Suzuki" to "India"), "TSM" to ("Suzuki" to "Hungría"),
        "VF3" to ("Peugeot" to "Francia"), "VF7" to ("Citroën" to "Francia"), "8AD" to ("Peugeot" to "Argentina"), "936" to ("Peugeot" to "Brasil"),
        "WDB" to ("Mercedes-Benz" to "Alemania"), "WDD" to ("Mercedes-Benz" to "Alemania"), "WBA" to ("BMW" to "Alemania"),
        "WAU" to ("Audi" to "Alemania"), "ZFA" to ("Fiat" to "Italia"), "9BD" to ("Fiat" to "Brasil"), "8AP" to ("Fiat" to "Argentina"),
        "1C4" to ("Jeep" to "EE. UU."), "1J4" to ("Jeep" to "EE. UU."), "1J8" to ("Jeep" to "EE. UU."), "1C6" to ("RAM" to "EE. UU."),
        "JAA" to ("Isuzu" to "Japón"), "LGW" to ("Great Wall" to "China"), "LVV" to ("Chery" to "China"), "LJ1" to ("JAC" to "China"),
        "LGX" to ("BYD" to "China"), "LSJ" to ("MG" to "China"), "LB3" to ("Geely" to "China"),
    )

    fun decodeOffline(raw: String, nowYear: Int = Calendar.getInstance().get(Calendar.YEAR)): VinInfo? {
        val vin = raw.trim().uppercase(Locale.US)
        if (!isValid(vin)) return null
        val wmi = WMI[vin.substring(0, 3)] ?: WMI[vin.substring(0, 4)]
        return VinInfo(
            vin = vin,
            make = wmi?.first,
            model = null,
            year = modelYear(vin, nowYear),
            engine = null,
            displacementL = null,
            fuel = null,
            drive = null,
            country = wmi?.second ?: country(vin[0]),
            source = "VIN (sin internet)",
            warning = "Algunos fabricantes de fuera de EE. UU. no codifican el año en la posición 10; revísalo.",
        )
    }

    /** Año modelo por la posición 10: el ciclo se repite cada 30 años, se toma el más reciente posible. */
    fun modelYear(vin: String, nowYear: Int): Int? {
        val idx = YEAR_CODES.indexOf(vin[9])
        if (idx < 0) return null
        var year = 1980 + idx
        while (year + 30 <= nowYear + 1) year += 30
        return year
    }

    private fun country(c: Char): String? = when (c) {
        in 'A'..'H' -> "África"
        'J' -> "Japón"
        'K' -> "Corea"
        'L' -> "China"
        'M' -> "India / Tailandia / Indonesia"
        in 'S'..'Z' -> "Europa"
        in '1'..'5' -> "Norteamérica"
        '6', '7' -> "Oceanía"
        '8' -> "Argentina / Chile / Ecuador / Venezuela"
        '9' -> "Brasil / Colombia"
        else -> null
    }
}

/** Funciones que usan internet. Llamar SIEMPRE desde un hilo de fondo (Dispatchers.IO). */
object OnlineService {

    /** Base de vehículos publicada en el repositorio de la app (se actualiza con cada commit). */
    const val DEFAULT_DB_URL = "https://raw.githubusercontent.com/BrayanGM28/OBD2-APP/feature/obd-pulse-v1.1/app/src/main/assets/vehicles.json"

    private const val USER_AGENT = "OBDPulse/1.1 (Android)"

    fun httpGet(url: String, timeoutMs: Int = 12_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("El servidor respondió $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Descarga y valida la base de vehículos. Lanza excepción si no es válida. */
    fun fetchDatabase(url: String): Pair<VehicleDb, String> {
        val text = httpGet(url)
        val db = VehicleDb.parse(text)
        if (db.rules.isEmpty()) throw IOException("La base descargada no contiene reglas")
        return db to text
    }

    /**
     * Decodifica el VIN con la API pública de NHTSA (vPIC, gratuita y sin clave).
     * Funciona muy bien con vehículos para EE. UU.; con otros mercados suele dar marca y año.
     */
    @Suppress("UNCHECKED_CAST")
    fun decodeVin(vin: String): VinInfo {
        val clean = vin.trim().uppercase(Locale.US)
        val text = httpGet("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/$clean?format=json")
        val root = MiniJson.parse(text) as Map<String, Any?>
        val r = (root.list("Results").firstOrNull() as? Map<String, Any?>) ?: throw IOException("Respuesta vacía")
        fun f(k: String) = (r[k] as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "Not Applicable" && it != "0" }

        val fuelText = f("FuelTypePrimary")?.lowercase(Locale.US).orEmpty()
        val fuel = when {
            "diesel" in fuelText -> Fuel.DIESEL
            "electric" in fuelText && "gasoline" in fuelText -> Fuel.HYBRID
            "flex" in fuelText || "ethanol" in fuelText -> Fuel.FLEX
            "natural gas" in fuelText || "propane" in fuelText -> Fuel.GAS
            "gasoline" in fuelText -> Fuel.GASOLINE
            else -> null
        }
        val driveText = f("DriveType")?.lowercase(Locale.US).orEmpty()
        val drive = when {
            "awd" in driveText || "all-wheel" in driveText -> Drive.AWD
            "4wd" in driveText || "4x4" in driveText || "4-wheel" in driveText -> Drive.FOUR_WD
            "rwd" in driveText || "rear" in driveText -> Drive.RWD
            "fwd" in driveText || "front" in driveText -> Drive.FWD
            else -> null
        }
        val engine = listOfNotNull(f("EngineModel"), f("EngineConfiguration"), f("EngineCylinders")?.let { "$it cil." })
            .joinToString(" ").ifBlank { null }
        val errorText = f("ErrorText")
        val offline = VinDecoder.decodeOffline(clean)
        return VinInfo(
            vin = clean,
            make = f("Make")?.lowercase(Locale.US)?.replaceFirstChar { it.titlecase(Locale.US) } ?: offline?.make,
            model = f("Model"),
            year = f("ModelYear")?.toIntOrNull() ?: offline?.year,
            engine = engine,
            displacementL = f("DisplacementL")?.toDoubleOrNull(),
            fuel = fuel,
            drive = drive,
            country = f("PlantCountry") ?: offline?.country,
            source = "NHTSA vPIC (internet)",
            warning = if (errorText != null && !errorText.startsWith("0")) {
                "NHTSA no reconoce todos los datos de este VIN (común en vehículos fuera de EE. UU.)."
            } else {
                null
            },
        )
    }

    /** Búsqueda web del protocolo de un vehículo (se abre en el navegador). */
    fun protocolSearchUrl(p: VehicleProfile): String {
        val q = listOf(p.make, p.model, p.version, p.year.toString(), p.engine, if (p.isDiesel) "diesel" else "", "protocolo OBD2 ELM327")
            .filter { it.isNotBlank() }.joinToString(" ")
        return "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    }

    /** Texto para compartir un resultado confirmado y agregarlo a la base del repositorio. */
    fun contributionText(p: VehicleProfile, protocol: String, protocolName: String): String {
        val rule = linkedMapOf<String, Any?>(
            "make" to p.make,
            "model" to p.model,
            "engine" to p.engine.ifBlank { null },
            "fuel" to p.fuel.code,
            "from" to p.year,
            "to" to p.year,
            "protocols" to listOf(protocol),
            "confidence" to "alta",
            "note" to "Confirmado con OBD Pulse: $protocolName",
        ).filterValues { it != null }
        return "Vehículo verificado con OBD Pulse\n${p.title} ${p.year} · ${p.engine}\nProtocolo: $protocolName (ATSP$protocol)\n\n" +
            "Regla para vehicles.json:\n" + MiniJson.stringify(rule)
    }
}
