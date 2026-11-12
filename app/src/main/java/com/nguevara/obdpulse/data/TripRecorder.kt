package com.nguevara.obdpulse.data

import com.nguevara.obdpulse.obd.Metric
import com.nguevara.obdpulse.obd.Metrics
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TripState(
    val active: Boolean = false,
    val startMs: Long = 0L,
    val elapsedS: Long = 0L,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val maxSpeed: Double = 0.0,
    val avgSpeed: Double = 0.0,
    val rows: Int = 0,
    val gpsEnabled: Boolean = false,
) {
    /** Rendimiento promedio del viaje en km/L (null si aún no hay datos suficientes). */
    val avgKmPerL: Double?
        get() = if (fuelL > 0.005 && distanceKm > 0.05) distanceKm / fuelL else null
}

/**
 * Registra un viaje en CSV (separado por comas, punto decimal) dentro de files/trips/.
 * Integra velocidad → distancia y consumo (L/h) → litros usados.
 */
class TripRecorder(private val dir: File) {

    var state = TripState()
        private set

    private var writer: BufferedWriter? = null
    private var columns: List<Metric> = emptyList()
    private var lastSampleMs = 0L
    private var speedTimeSum = 0.0
    private var movingSeconds = 0.0
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var lastAccuracyMeters: Float? = null

    val recordedKeys: Set<String>
        @Synchronized get() = columns.mapTo(linkedSetOf()) { it.key }

    @Synchronized
    fun start(columns: List<Metric>, recordGps: Boolean = false) {
        stop()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "viaje_$stamp.csv")
        this.columns = columns
        writer = file.bufferedWriter().also { w ->
            val header = listOf("fecha_hora", "segundos") +
                columns.map { "${it.short} (${it.unit})" } +
                if (recordGps) listOf("gps_latitud", "gps_longitud", "gps_precision_m") else emptyList()
            w.write(header.joinToString(","))
            w.newLine()
        }
        lastLatitude = null
        lastLongitude = null
        lastAccuracyMeters = null
        val now = System.currentTimeMillis()
        lastSampleMs = 0L
        speedTimeSum = 0.0
        movingSeconds = 0.0
        state = TripState(active = true, startMs = now, gpsEnabled = recordGps)
    }

    @Synchronized
    fun updateLocation(latitude: Double, longitude: Double, accuracyMeters: Float?) {
        if (!state.active || !state.gpsEnabled) return
        lastLatitude = latitude
        lastLongitude = longitude
        lastAccuracyMeters = accuracyMeters
    }

    @Synchronized
    fun onSample(values: Map<String, Double>) {
        if (!state.active) return
        val now = System.currentTimeMillis()
        val dt = if (lastSampleMs == 0L) 0.0 else (now - lastSampleMs) / 1000.0
        lastSampleMs = now

        val speed = values[Metrics.SPEED] ?: 0.0
        val fuelRate = values[Metrics.FUEL_RATE] ?: 0.0
        var distance = state.distanceKm
        var fuel = state.fuelL
        // Ignorar saltos largos (p. ej. reconexión) para no inflar la distancia.
        if (dt in 0.0..5.0) {
            distance += speed * dt / 3600.0
            fuel += fuelRate * dt / 3600.0
            if (speed > 1.0) {
                speedTimeSum += speed * dt
                movingSeconds += dt
            }
        }

        val elapsed = (now - state.startMs) / 1000.0
        writer?.let { w ->
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))
            val cells = mutableListOf(time, String.format(Locale.US, "%.2f", elapsed))
            for (m in columns) {
                val v = values[m.key]
                cells += if (v == null) "" else String.format(Locale.US, "%.${m.decimals + 1}f", v)
            }
            if (state.gpsEnabled) {
                cells += lastLatitude?.let { String.format(Locale.US, "%.7f", it) } ?: ""
                cells += lastLongitude?.let { String.format(Locale.US, "%.7f", it) } ?: ""
                cells += lastAccuracyMeters?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            }
            w.write(cells.joinToString(","))
            w.newLine()
        }

        state = state.copy(
            elapsedS = elapsed.toLong(),
            distanceKm = distance,
            fuelL = fuel,
            maxSpeed = maxOf(state.maxSpeed, speed),
            avgSpeed = if (movingSeconds > 0) speedTimeSum / movingSeconds else 0.0,
            rows = state.rows + 1,
        )
    }

    @Synchronized
    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        lastLatitude = null
        lastLongitude = null
        lastAccuracyMeters = null
        if (state.active) state = state.copy(active = false)
    }

    fun files(): List<File> =
        dir.listFiles()?.filter { it.extension == "csv" }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
