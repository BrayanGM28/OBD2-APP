package com.nguevara.obdpulse.data

import android.content.Context
import com.nguevara.obdpulse.vehicle.OnlineService
import com.nguevara.obdpulse.vehicle.VehicleDb
import com.nguevara.obdpulse.vehicle.VehicleProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Garaje: los vehículos guardados por el usuario (JSON en SharedPreferences). */
class GarageStore(context: Context) {

    private val prefs = context.getSharedPreferences("obd_pulse_garage", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(VehicleProfile.listFromJson(prefs.getString("profiles", null)))
    val profiles: StateFlow<List<VehicleProfile>> = _profiles.asStateFlow()

    fun upsert(p: VehicleProfile) {
        val list = _profiles.value.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        save(list)
    }

    fun delete(id: String) = save(_profiles.value.filterNot { it.id == id })

    fun get(id: String?): VehicleProfile? = id?.let { i -> _profiles.value.firstOrNull { it.id == i } }

    private fun save(list: List<VehicleProfile>) {
        _profiles.value = list
        prefs.edit().putString("profiles", VehicleProfile.listToJson(list)).apply()
    }
}

/**
 * Base de vehículos: la que viene dentro de la app (assets/vehicles.json) o la descargada de internet,
 * la que tenga número de versión mayor.
 */
class VehicleDbStore(private val context: Context) {

    private val cacheFile = File(context.filesDir, "vehicles_db.json")

    // Declarado antes de _db: load() lo asigna durante la inicialización.
    /** "Incluida en la app" o "Descargada de internet". */
    var origin: String = ""
        private set

    private val _db = MutableStateFlow(load())
    val db: StateFlow<VehicleDb> = _db.asStateFlow()

    private fun load(): VehicleDb {
        val bundled = runCatching {
            context.assets.open("vehicles.json").bufferedReader(Charsets.UTF_8).use { VehicleDb.parse(it.readText()) }
        }.getOrDefault(VehicleDb.EMPTY)
        val cached = runCatching { if (cacheFile.exists()) VehicleDb.parse(cacheFile.readText(Charsets.UTF_8)) else null }.getOrNull()
        return if (cached != null && cached.version > bundled.version) {
            origin = "Descargada de internet"
            cached
        } else {
            origin = "Incluida en la app"
            bundled
        }
    }

    /**
     * Descarga la base desde [url] (por defecto el repositorio de GitHub de la app).
     * Devuelve un mensaje para mostrar. Debe llamarse desde un hilo de fondo.
     */
    fun update(url: String): String {
        val (remote, text) = OnlineService.fetchDatabase(url)
        val current = _db.value
        return if (remote.version > current.version) {
            cacheFile.writeText(text, Charsets.UTF_8)
            _db.value = remote
            origin = "Descargada de internet"
            "Base actualizada a la versión ${remote.version} (${remote.rules.size} reglas, ${remote.makes.size} marcas)."
        } else {
            "Ya tienes la versión más reciente (v${current.version})."
        }
    }
}
