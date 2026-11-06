package com.nguevara.obdpulse.data

import android.content.Context
import com.nguevara.obdpulse.bt.LinkType
import com.nguevara.obdpulse.bt.ObdDevice
import com.nguevara.obdpulse.obd.Metric
import com.nguevara.obdpulse.vehicle.OnlineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class EconUnit(val label: String) {
    KM_PER_GAL("km/gal"),
    KM_PER_L("km/L"),
    L_PER_100KM("L/100 km"),
    MPG("mpg (EE. UU.)"),
}

data class AppSettings(
    val econUnit: EconUnit = EconUnit.KM_PER_GAL,
    val fahrenheit: Boolean = false,
    val diesel: Boolean = false,
    val displacementL: Double = 1.6,
    val redlineRpm: Int = 6000,
    val protocol: String = "0",
    val pollDelayMs: Int = 0,
    val keepScreenOn: Boolean = true,
    val lastDevice: ObdDevice? = null,
    /** Vehículo del garaje en uso (null = ninguno: se usan los valores de arriba). */
    val activeProfileId: String? = null,
    /** De dónde se descarga la base de vehículos actualizada. */
    val dbUrl: String = OnlineService.DEFAULT_DB_URL,
)

/** Persistencia sencilla en SharedPreferences. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("obd_pulse", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings
        get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val lastName = prefs.getString("last_name", null)
        val lastAddress = prefs.getString("last_address", null)
        val lastType = prefs.getString("last_type", null)
        val last = if (lastName != null && lastAddress != null && lastType != null) {
            runCatching { ObdDevice(lastName, lastAddress, LinkType.valueOf(lastType)) }.getOrNull()
        } else {
            null
        }
        return AppSettings(
            econUnit = runCatching { EconUnit.valueOf(prefs.getString("econ_unit", EconUnit.KM_PER_GAL.name)!!) }
                .getOrDefault(EconUnit.KM_PER_GAL),
            fahrenheit = prefs.getBoolean("fahrenheit", false),
            diesel = prefs.getBoolean("diesel", false),
            displacementL = prefs.getFloat("displacement", 1.6f).toDouble(),
            redlineRpm = prefs.getInt("redline", 6000),
            protocol = prefs.getString("protocol", "0") ?: "0",
            pollDelayMs = prefs.getInt("poll_delay", 0),
            keepScreenOn = prefs.getBoolean("keep_screen", true),
            lastDevice = last,
            activeProfileId = prefs.getString("active_profile", null),
            dbUrl = prefs.getString("db_url", null)?.takeIf { it.isNotBlank() } ?: OnlineService.DEFAULT_DB_URL,
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit().apply {
            putString("econ_unit", s.econUnit.name)
            putBoolean("fahrenheit", s.fahrenheit)
            putBoolean("diesel", s.diesel)
            putFloat("displacement", s.displacementL.toFloat())
            putInt("redline", s.redlineRpm)
            putString("protocol", s.protocol)
            putInt("poll_delay", s.pollDelayMs)
            putBoolean("keep_screen", s.keepScreenOn)
            putString("active_profile", s.activeProfileId)
            // Solo se guarda si el usuario la cambió: así una versión nueva de la app puede cambiar la URL por defecto.
            if (s.dbUrl == OnlineService.DEFAULT_DB_URL) remove("db_url") else putString("db_url", s.dbUrl)
            val d = s.lastDevice
            if (d != null && d.type != LinkType.DEMO) {
                putString("last_name", d.name)
                putString("last_address", d.address)
                putString("last_type", d.type.name)
            }
        }.apply()
    }
}

/** Conversión de unidades base → unidades de visualización según los ajustes. */
object Units {

    private const val LITERS_PER_US_GALLON = 3.785411784
    private const val KM_PER_MILE = 1.609344

    fun convert(metric: Metric, value: Double, s: AppSettings): Double = when (metric.kind) {
        Metric.Kind.TEMPERATURE -> if (s.fahrenheit) value * 9.0 / 5.0 + 32.0 else value
        Metric.Kind.ECONOMY -> econ(value, s.econUnit)
        Metric.Kind.OTHER -> value
    }

    fun unit(metric: Metric, s: AppSettings): String = when (metric.kind) {
        Metric.Kind.TEMPERATURE -> if (s.fahrenheit) "°F" else "°C"
        Metric.Kind.ECONOMY -> s.econUnit.label.substringBefore(" (")
        Metric.Kind.OTHER -> metric.unit
    }

    fun range(metric: Metric, s: AppSettings): Pair<Double, Double> = when (metric.kind) {
        Metric.Kind.TEMPERATURE -> convert(metric, metric.min, s) to convert(metric, metric.max, s)
        Metric.Kind.ECONOMY -> when (s.econUnit) {
            EconUnit.KM_PER_GAL -> 0.0 to 150.0
            EconUnit.KM_PER_L -> 0.0 to 40.0
            EconUnit.L_PER_100KM -> 0.0 to 30.0
            EconUnit.MPG -> 0.0 to 90.0
        }
        Metric.Kind.OTHER -> metric.min to metric.max
    }

    /** [kmPerL] → unidad elegida. */
    fun econ(kmPerL: Double, unit: EconUnit): Double = when (unit) {
        EconUnit.KM_PER_L -> kmPerL
        EconUnit.KM_PER_GAL -> kmPerL * LITERS_PER_US_GALLON
        EconUnit.L_PER_100KM -> if (kmPerL > 0.01) 100.0 / kmPerL else 0.0
        EconUnit.MPG -> kmPerL * LITERS_PER_US_GALLON / KM_PER_MILE
    }

    fun format(value: Double?, decimals: Int): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "—"
        return String.format(Locale.getDefault(), "%.${decimals}f", value)
    }

    fun display(metric: Metric, value: Double?, s: AppSettings): String =
        format(value?.let { convert(metric, it, s) }, metric.decimals)
}
