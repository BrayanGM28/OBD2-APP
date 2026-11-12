package com.nguevara.obdpulse.vehicle

import java.util.Locale
import java.util.UUID

enum class Fuel(val label: String, val code: String) {
    GASOLINE("Gasolina", "gasolina"),
    DIESEL("Diésel", "diesel"),
    HYBRID("Híbrido", "hibrido"),
    GAS("Gas (GNV/GLP)", "gas"),
    FLEX("Flex / etanol", "flex");

    companion object {
        fun from(code: String?): Fuel = entries.firstOrNull { it.code.equals(code, true) || it.name.equals(code, true) } ?: GASOLINE
    }
}

enum class Drive(val label: String, val code: String) {
    FWD("4x2 delantera", "fwd"),
    RWD("4x2 trasera", "rwd"),
    AWD("AWD", "awd"),
    FOUR_WD("4x4", "4x4");

    companion object {
        fun from(code: String?): Drive = entries.firstOrNull { it.code.equals(code, true) || it.name.equals(code, true) } ?: FWD
    }
}

enum class Transmission(val label: String, val code: String) {
    MANUAL("Manual", "manual"),
    AUTOMATIC("Automática", "automatica"),
    CVT("CVT", "cvt");

    companion object {
        fun from(code: String?): Transmission = entries.firstOrNull { it.code.equals(code, true) || it.name.equals(code, true) } ?: MANUAL
    }
}

/** Mercado para el que se fabricó el carro: cambia mucho la norma OBD que cumple. */
enum class Market(val label: String, val code: String) {
    LATAM("Colombia / Latinoamérica", "latam"),
    US("EE. UU. / Canadá", "us"),
    EU("Europa", "eu"),
    OTHER("Asia / otro", "other");

    companion object {
        fun from(code: String?): Market = entries.firstOrNull { it.code.equals(code, true) || it.name.equals(code, true) } ?: LATAM
    }
}

/** Un vehículo del garaje. */
data class VehicleProfile(
    val id: String = UUID.randomUUID().toString(),
    val make: String = "",
    val model: String = "",
    /** Versión o línea: "2.0i", "16V", "NP300 4x4"… */
    val version: String = "",
    val year: Int = 2015,
    /** Código o descripción del motor: "FB20", "K4M 1.6 16V", "YD25"… */
    val engine: String = "",
    val fuel: Fuel = Fuel.GASOLINE,
    val displacementL: Double = 1.6,
    val drive: Drive = Drive.FWD,
    val transmission: Transmission = Transmission.MANUAL,
    val market: Market = Market.LATAM,
    val redlineRpm: Int = 6000,
    /** Potencia máxima declarada en caballos de fuerza (HP) para estimación precisa de par/potencia. */
    val hp: Int? = null,
    /** Peso en orden de marcha estimado en kg (para estimación inercial de potencia). */
    val weightKg: Int? = null,
    /** Protocolo elegido a mano (null = usar la recomendación). */
    val protocolOverride: String? = null,
    val learnedProtocol: String? = null,
    val vin: String? = null,
    val nickname: String = "",
) {
    val title: String
        get() = nickname.ifBlank { listOf(make, model, version).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Vehículo sin nombre" } }

    val subtitle: String
        get() = listOfNotNull(
            year.toString(),
            engine.takeIf { it.isNotBlank() },
            String.format(Locale.US, "%.1f L", displacementL),
            hp?.let { "$it hp" },
            fuel.label,
            transmission.label,
            drive.label,
        ).joinToString(" · ")

    val isDiesel: Boolean get() = fuel == Fuel.DIESEL

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "make" to make, "model" to model, "version" to version, "year" to year,
        "engine" to engine, "fuel" to fuel.code, "displacement" to displacementL, "drive" to drive.code,
        "transmission" to transmission.code, "market" to market.code, "redline" to redlineRpm,
        "hp" to hp, "weightKg" to weightKg,
        "protocolOverride" to protocolOverride, "learnedProtocol" to learnedProtocol, "vin" to vin,
        "nickname" to nickname,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): VehicleProfile = VehicleProfile(
            id = m.str("id") ?: UUID.randomUUID().toString(),
            make = m.str("make").orEmpty(),
            model = m.str("model").orEmpty(),
            version = m.str("version").orEmpty(),
            year = m.int("year") ?: 2015,
            engine = m.str("engine").orEmpty(),
            fuel = Fuel.from(m.str("fuel")),
            displacementL = m.dbl("displacement") ?: 1.6,
            drive = Drive.from(m.str("drive")),
            transmission = Transmission.from(m.str("transmission")),
            market = Market.from(m.str("market")),
            redlineRpm = m.int("redline") ?: 6000,
            hp = m.int("hp"),
            weightKg = m.int("weightKg"),
            protocolOverride = m.str("protocolOverride"),
            learnedProtocol = m.str("learnedProtocol"),
            vin = m.str("vin"),
            nickname = m.str("nickname").orEmpty(),
        )

        fun listToJson(list: List<VehicleProfile>): String = MiniJson.stringify(list.map { it.toMap() })

        @Suppress("UNCHECKED_CAST")
        fun listFromJson(json: String?): List<VehicleProfile> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                (MiniJson.parse(json) as List<Any?>).mapNotNull { (it as? Map<String, Any?>)?.let(::fromMap) }
            }.getOrDefault(emptyList())
        }
    }
}

// ======================================================================
// Base de datos de protocolos
// ======================================================================

enum class Confidence(val label: String) {
    HIGH("Alta"),
    MEDIUM("Media"),
    LOW("Baja");

    companion object {
        fun from(s: String?): Confidence = when (s?.lowercase(Locale.US)) {
            "alta", "high" -> HIGH
            "baja", "low" -> LOW
            else -> MEDIUM
        }
    }
}

/**
 * Regla de la base: si el vehículo coincide, se prueban [protocols] en ese orden.
 * [make], [model] y [engine] aceptan alternativas separadas por "|" (p. ej. "Frontier|NP300|Navara").
 * Marca: coincidencia exacta (sin mayúsculas). Modelo y motor: el texto del perfil CONTIENE la alternativa.
 */
data class ProtocolRule(
    val make: String?,
    val model: String?,
    val engine: String?,
    val fuel: String?,
    val market: String?,
    val from: Int?,
    val to: Int?,
    val protocols: List<String>,
    val confidence: Confidence,
    val slowEcu: Boolean,
    val note: String?,
) {
    private fun norm(s: String): String = s.trim().lowercase(Locale.US)
        .replace("ë", "e")
        .replace("é", "e")
        .replace("mercedez", "mercedes")
        .replace("bwm", "bmw")
        .replace("-benz", "")

    private fun alts(s: String?): List<String> = s?.split('|')?.map { norm(it) }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Puntaje de coincidencia, o null si no aplica. Más alto = regla más específica. */
    fun score(p: VehicleProfile): Int? {
        var score = 0
        if (make != null) {
            val targetMake = norm(p.make)
            if (alts(make).none { it == targetMake || (it.length >= 3 && targetMake.contains(it)) || (targetMake.length >= 3 && it.contains(targetMake)) }) return null
            score += 10
        }
        if (model != null) {
            val text = (p.model + " " + p.version).lowercase(Locale.US)
            if (alts(model).none { text.contains(it) }) return null
            score += 20
        }
        if (engine != null) {
            val text = (p.engine + " " + p.version).lowercase(Locale.US).replace(" ", "")
            if (alts(engine).none { text.contains(it.replace(" ", "")) }) return null
            score += 20
        }
        if (fuel != null) {
            if (alts(fuel).none { it == p.fuel.code }) return null
            score += 5
        }
        if (market != null) {
            if (alts(market).none { it == p.market.code }) return null
            score += 5
        }
        if (from != null && p.year < from) return null
        if (to != null && p.year > to) return null
        if (from != null || to != null) score += 1
        return score
    }

    companion object {
        fun fromMap(m: Map<String, Any?>): ProtocolRule = ProtocolRule(
            make = m.str("make"),
            model = m.str("model"),
            engine = m.str("engine"),
            fuel = m.str("fuel"),
            market = m.str("market"),
            from = m.int("from"),
            to = m.int("to"),
            protocols = m.strings("protocols").map { it.uppercase(Locale.US) },
            confidence = Confidence.from(m.str("confidence")),
            slowEcu = m.bool("slowEcu"),
            note = m.str("note"),
        )
    }
}

/** Marca con sus modelos y motores (para sugerencias al escribir). */
data class MakeEntry(val make: String, val models: List<String>, val engines: List<String>)

data class VehicleDb(
    val version: Int,
    val updated: String,
    val makes: List<MakeEntry>,
    val rules: List<ProtocolRule>,
    val templates: List<VehicleProfile>,
) {
    fun makeNames(): List<String> = makes.map { it.make }.sorted()

    fun modelsFor(make: String): List<String> =
        makeEntry(make)?.models.orEmpty()

    fun enginesFor(make: String): List<String> =
        makeEntry(make)?.engines.orEmpty()

    fun canonicalMake(make: String): String {
        val alias = when (make.trim().lowercase(Locale.US)) {
            "mercedes" -> "Mercedes-Benz"
            "vw" -> "Volkswagen"
            else -> make.trim()
        }
        return makes.firstOrNull { it.make.equals(alias, ignoreCase = true) }?.make ?: alias
    }

    private fun makeEntry(make: String): MakeEntry? =
        makes.firstOrNull { it.make.equals(canonicalMake(make), ignoreCase = true) }

    companion object {
        val EMPTY = VehicleDb(0, "", emptyList(), emptyList(), emptyList())

        @Suppress("UNCHECKED_CAST")
        fun parse(json: String): VehicleDb {
            val root = MiniJson.parse(json) as? Map<String, Any?> ?: throw IllegalArgumentException("La base no es un objeto JSON")
            val makes = root.list("makes").mapNotNull { it as? Map<String, Any?> }.mapNotNull { m ->
                val name = m.str("make") ?: return@mapNotNull null
                MakeEntry(name, m.strings("models"), m.strings("engines"))
            }
            val rules = root.list("rules").mapNotNull { it as? Map<String, Any?> }
                .map { ProtocolRule.fromMap(it) }
                .filter { it.protocols.isNotEmpty() }
            val templates = root.list("templates").mapNotNull { it as? Map<String, Any?> }.map { VehicleProfile.fromMap(it) }
            return VehicleDb(
                version = root.int("version") ?: 0,
                updated = root.str("updated").orEmpty(),
                makes = makes,
                rules = rules,
                templates = templates,
            )
        }
    }
}

// ======================================================================
// Recomendación de protocolo
// ======================================================================

data class ProtocolRecommendation(
    /** Orden en que se probarán los protocolos al conectar (siempre termina en "0" = automático). */
    val candidates: List<String>,
    val confidence: Confidence,
    val source: String,
    val reason: String,
    val note: String?,
    val slowEcu: Boolean,
) {
    val primary: String get() = candidates.first()
}

object ProtocolAdvisor {

    fun recommend(p: VehicleProfile, db: VehicleDb): ProtocolRecommendation {
        val best = db.rules
            .mapIndexedNotNull { i, r -> r.score(p)?.let { Triple(r, it, i) } }
            .sortedWith(compareByDescending<Triple<ProtocolRule, Int, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()?.first

        val base: ProtocolRecommendation = if (best != null) {
            ProtocolRecommendation(
                candidates = best.protocols,
                confidence = best.confidence,
                source = "Base de vehículos v${db.version}",
                reason = describeRule(best),
                note = best.note,
                slowEcu = best.slowEcu || (p.isDiesel && best.protocols.firstOrNull() in K_LINE),
            )
        } else {
            generic(p)
        }

        // Prioridad: elegido a mano > aprendido en este carro > base de datos > genérico.
        val ordered = mutableListOf<String>()
        val source: String
        val confidence: Confidence
        when {
            p.protocolOverride != null -> {
                ordered += p.protocolOverride
                p.learnedProtocol?.let { ordered += it }
                source = "Elegido manualmente"
                confidence = base.confidence
            }
            p.learnedProtocol != null -> {
                ordered += p.learnedProtocol
                source = "Aprendido: este carro respondió así antes"
                confidence = Confidence.HIGH
            }
            else -> {
                source = base.source
                confidence = base.confidence
            }
        }
        ordered += base.candidates
        ordered += "0"
        return base.copy(
            candidates = ordered.map { it.uppercase(Locale.US) }.distinct(),
            source = source,
            confidence = confidence,
        )
    }

    private fun describeRule(r: ProtocolRule): String {
        val who = listOfNotNull(
            r.make?.replace("|", "/"),
            r.model?.replace("|", "/"),
            r.engine?.let { "motor " + it.replace("|", "/") },
            r.fuel?.let { if (it.contains("diesel")) "diésel" else it },
        ).joinToString(" ")
        val years = when {
            r.from != null && r.to != null -> "${r.from}–${r.to}"
            r.from != null -> "desde ${r.from}"
            r.to != null -> "hasta ${r.to}"
            else -> ""
        }
        return "Regla para $who $years".trim()
    }

    /** Sin regla específica: se decide por año y mercado (normas OBD-II / EOBD / ISO 15765). */
    private fun generic(p: VehicleProfile): ProtocolRecommendation {
        val y = p.year
        return when {
            y < 1996 -> ProtocolRecommendation(
                listOf("3", "5", "4", "1", "2"), Confidence.LOW, "Regla general por año",
                "Antes de 1996 el OBD-II no era obligatorio.",
                "Es posible que este vehículo no tenga OBD-II (podría usar OBD-I propietario). Si no responde, el adaptador ELM327 no le sirve.",
                p.isDiesel,
            )
            p.market == Market.US && y >= 2008 -> ProtocolRecommendation(
                listOf("6", "8", "7", "9"), Confidence.HIGH, "Regla general por año",
                "En EE. UU. todos los vehículos desde 2008 usan CAN (ISO 15765-4).", null, false,
            )
            p.market == Market.US -> ProtocolRecommendation(
                listOf("3", "5", "2", "1", "6"), Confidence.LOW, "Regla general por año",
                "En EE. UU. 1996–2007: GM usaba J1850 VPW, Ford J1850 PWM, asiáticos y europeos ISO 9141/KWP.", null, false,
            )
            y >= 2012 -> ProtocolRecommendation(
                listOf("6", "8", "7", "9", "5", "4", "3"), Confidence.MEDIUM, "Regla general por año",
                "La mayoría de vehículos desde 2012 usan CAN (ISO 15765-4).",
                if (p.isDiesel && p.market == Market.LATAM) "Algunos diésel para Latinoamérica no cumplen OBD-II genérico." else null,
                false,
            )
            y >= 2005 -> ProtocolRecommendation(
                listOf("6", "5", "4", "3", "8", "7"), Confidence.LOW, "Regla general por año",
                "Entre 2005 y 2011 se mezclan CAN y K-Line (ISO 14230 KWP / ISO 9141).",
                if (p.isDiesel) "Los diésel de esta época suelen usar K-Line y responder lento." else null,
                p.isDiesel,
            )
            else -> ProtocolRecommendation(
                listOf("3", "5", "4", "6"), Confidence.LOW, "Regla general por año",
                "Antes de 2005 predominan ISO 9141-2 y ISO 14230-4 KWP (K-Line).",
                if (p.isDiesel && p.market != Market.EU) "Muchos diésel de esta época fuera de Europa no tienen OBD-II genérico." else null,
                p.isDiesel,
            )
        }
    }

    private val K_LINE = setOf("3", "4", "5")
}
