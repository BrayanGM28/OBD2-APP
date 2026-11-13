package com.nguevara.obdpulse

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nguevara.obdpulse.bt.BleTransport
import com.nguevara.obdpulse.bt.ClassicTransport
import com.nguevara.obdpulse.bt.DemoTransport
import com.nguevara.obdpulse.bt.DeviceScanner
import com.nguevara.obdpulse.bt.LinkType
import com.nguevara.obdpulse.bt.ObdDevice
import com.nguevara.obdpulse.bt.ObdTransport
import com.nguevara.obdpulse.bt.TransportException
import com.nguevara.obdpulse.data.AppSettings
import com.nguevara.obdpulse.data.PowerCalcMethod
import com.nguevara.obdpulse.data.GarageStore
import com.nguevara.obdpulse.data.SettingsStore
import com.nguevara.obdpulse.data.VehicleDbStore
import com.nguevara.obdpulse.data.TripRecorder
import com.nguevara.obdpulse.data.TripRecordingService
import com.nguevara.obdpulse.data.TripState
import com.nguevara.obdpulse.obd.DtcInfo
import com.nguevara.obdpulse.obd.DtcItem
import com.nguevara.obdpulse.obd.DtcKind
import com.nguevara.obdpulse.obd.EcuModule
import com.nguevara.obdpulse.obd.Elm327
import com.nguevara.obdpulse.obd.FuelType
import com.nguevara.obdpulse.obd.FreezeFrame
import com.nguevara.obdpulse.obd.Metric
import com.nguevara.obdpulse.obd.Metrics
import com.nguevara.obdpulse.obd.OxygenSensorPosition
import com.nguevara.obdpulse.obd.ProtocolProbe
import com.nguevara.obdpulse.obd.TermLine
import com.nguevara.obdpulse.obd.VehicleInfo
import com.nguevara.obdpulse.obd.VehicleStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlinx.coroutines.yield
import com.nguevara.obdpulse.vehicle.OnlineService
import com.nguevara.obdpulse.vehicle.ProtocolAdvisor
import com.nguevara.obdpulse.vehicle.ProtocolRecommendation
import com.nguevara.obdpulse.vehicle.VehicleDb
import com.nguevara.obdpulse.vehicle.VehicleProfile
import com.nguevara.obdpulse.vehicle.VinDecoder
import com.nguevara.obdpulse.vehicle.VinInfo
import java.io.File

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val device: ObdDevice, val step: String) : ConnState
    data class Connected(val device: ObdDevice, val info: VehicleInfo) : ConnState
    data class Failed(val message: String, val device: ObdDevice?) : ConnState
}

data class Sample(val t: Long, val v: Double)

data class VehicleMetricAlert(
    val metricKey: String,
    val value: Double,
    val message: String,
    val vehicleName: String,
)

/** Verificación de módulos (solo lectura). */
data class ModuleScanState(
    val loading: Boolean = false,
    val step: String = "",
    val modules: List<EcuModule> = emptyList(),
    val readAt: Long? = null,
    val error: String? = null,
)

/** Escaneo de todos los protocolos. */
data class ProtocolScanState(
    val loading: Boolean = false,
    val step: String = "",
    val results: List<ProtocolProbe> = emptyList(),
    val error: String? = null,
)

data class DiagState(
    val loading: Boolean = false,
    val step: String = "",
    val status: VehicleStatus? = null,
    val statusSinceClear: VehicleStatus? = null,
    val oxygenSensorsPresent: List<OxygenSensorPosition>? = null,
    val fuelType: FuelType? = null,
    val dtcs: List<DtcItem> = emptyList(),
    val freeze: FreezeFrame? = null,
    val readAt: Long? = null,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    val settings: StateFlow<AppSettings> = settingsStore.state

    val scanner = DeviceScanner(app)
    private val tripRecorder = TripRecorder(File(app.filesDir, "trips"))
    private val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var tripLocationListener: LocationListener? = null

    // ---- Garaje y base de vehículos ----
    private val garage = GarageStore(app)
    val profiles: StateFlow<List<VehicleProfile>> = garage.profiles
    private val dbStore = VehicleDbStore(app)
    val vehicleDb: StateFlow<VehicleDb> = dbStore.db
    val dbOrigin: String get() = dbStore.origin

    /** Vehículo en uso (o null si no se eligió ninguno). */
    val activeProfile: StateFlow<VehicleProfile?> = combine(settings, profiles) { st, list ->
        list.firstOrNull { it.id == st.activeProfileId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, garage.get(settingsStore.current.activeProfileId))

    private val _dbStatus = MutableStateFlow<String?>(null)
    val dbStatus: StateFlow<String?> = _dbStatus.asStateFlow()
    private val _dbUpdating = MutableStateFlow(false)
    val dbUpdating: StateFlow<Boolean> = _dbUpdating.asStateFlow()
    private val _scannedVehiclePending = MutableStateFlow(false)
    val scannedVehiclePending: StateFlow<Boolean> = _scannedVehiclePending.asStateFlow()

    private val _modules = MutableStateFlow(ModuleScanState())
    val modules: StateFlow<ModuleScanState> = _modules.asStateFlow()

    private val _protoScan = MutableStateFlow(ProtocolScanState())
    val protoScan: StateFlow<ProtocolScanState> = _protoScan.asStateFlow()

    /** Mensajes cortos para mostrar como aviso emergente. */
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /** El sondeo en vivo toma este candado por ciclo; los escaneos lo toman para tener el bus solo para ellos. */
    private val cycleLock = Mutex()

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    /** Último valor de cada magnitud (unidades base). */
    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val values: StateFlow<Map<String, Double>> = _values.asStateFlow()
    private val _vehicleAlerts = MutableStateFlow<List<VehicleMetricAlert>>(emptyList())
    val vehicleAlerts: StateFlow<List<VehicleMetricAlert>> = _vehicleAlerts.asStateFlow()
    private val alertStreaks = mutableMapOf<String, Int>()

    /** Historial para las gráficas. [historyTick] cambia cada vez que llega un ciclo nuevo. */
    private val history = HashMap<String, ArrayDeque<Sample>>()
    private val _historyTick = MutableStateFlow(0L)
    val historyTick: StateFlow<Long> = _historyTick.asStateFlow()

    private val _trip = MutableStateFlow(TripState())
    val trip: StateFlow<TripState> = _trip.asStateFlow()

    private val _tripFiles = MutableStateFlow<List<File>>(emptyList())
    val tripFiles: StateFlow<List<File>> = _tripFiles.asStateFlow()
    private val _tripMetricKeys = MutableStateFlow(TRIP_KEYS.toSet())
    val tripMetricKeys: StateFlow<Set<String>> = _tripMetricKeys.asStateFlow()

    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorHandler = Handler(Looper.getMainLooper())
    private val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _imuCalibrated = MutableStateFlow(false)
    val imuCalibrated: StateFlow<Boolean> = _imuCalibrated.asStateFlow()
    private var phoneSensorsStarted = false
    private var lastLinearAcceleration: FloatArray? = null
    private var lastGyroscope: FloatArray? = null
    private val linearAccelerationBias = FloatArray(3)
    private val gyroscopeBias = FloatArray(3)
    private val phoneSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    lastLinearAcceleration = event.values.copyOf()
                    store(Metrics.IMU_ACCEL_X, (event.values[0] - linearAccelerationBias[0]).toDouble())
                    store(Metrics.IMU_ACCEL_Y, (event.values[1] - linearAccelerationBias[1]).toDouble())
                    store(Metrics.IMU_ACCEL_Z, (event.values[2] - linearAccelerationBias[2]).toDouble())
                }
                Sensor.TYPE_GYROSCOPE -> {
                    lastGyroscope = event.values.copyOf()
                    val degreesPerSecond = (180.0 / Math.PI)
                    store(Metrics.IMU_GYRO_X, (event.values[0] - gyroscopeBias[0]) * degreesPerSecond)
                    store(Metrics.IMU_GYRO_Y, (event.values[1] - gyroscopeBias[1]) * degreesPerSecond)
                    store(Metrics.IMU_GYRO_Z, (event.values[2] - gyroscopeBias[2]) * degreesPerSecond)
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotation = FloatArray(9)
                    val orientation = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    SensorManager.getOrientation(rotation, orientation)
                    val heading = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
                    store(Metrics.IMU_HEADING, heading)
                    store(Metrics.IMU_PITCH, Math.toDegrees(orientation[1].toDouble()))
                    store(Metrics.IMU_ROLL, Math.toDegrees(orientation[2].toDouble()))
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val _diag = MutableStateFlow(DiagState())
    val diag: StateFlow<DiagState> = _diag.asStateFlow()

    private val _terminal = MutableStateFlow<List<TermLine>>(emptyList())
    val terminal: StateFlow<List<TermLine>> = _terminal.asStateFlow()

    /** Magnitudes que la pantalla visible quiere ver. Solo esas se consultan. */
    private val watched = MutableStateFlow<Set<String>>(emptySet())

    @Volatile
    private var elm: Elm327? = null
    private var transport: ObdTransport? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var logJob: Job? = null
    private var scanJob: Job? = null
    private var scannedVehicleSaveJob: Job? = null

    init {
        refreshTripFiles()
    }

    // ------------------------------------------------------------------
    // Escaneo y conexión
    // ------------------------------------------------------------------

    fun startScan() {
        scanner.startScan()
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            delay(12_000)
            scanner.stopScan()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanner.stopScan()
    }

    fun loadBonded() = scanner.loadBonded()

    fun connectDemo() = connect(DEMO_DEVICE)

    fun connect(device: ObdDevice) {
        if (connectJob?.isActive == true) return
        scannedVehicleSaveJob?.cancel()
        scannedVehicleSaveJob = null
        _scannedVehiclePending.value = false
        stopScan()
        closeLink()
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            _conn.value = ConnState.Connecting(device, "Abriendo conexión…")
            val t: ObdTransport = try {
                createTransport(device)
            } catch (ex: TransportException) {
                _conn.value = ConnState.Failed(ex.message ?: "Error de Bluetooth", device)
                return@launch
            }
            transport = t
            try {
                t.open()
                val e = Elm327(t)
                elm = e
                startLogCollector(e)
                e.info("Conectado a ${device.name} (${device.type.label})")
                val profile = activeProfile.value
                val rec = profile?.let { recommendation(it) }
                val candidates = when {
                    device.type == LinkType.DEMO -> listOf("0")
                    rec != null -> rec.candidates
                    else -> listOf(settings.value.protocol)
                }
                if (profile != null && device.type != LinkType.DEMO) {
                    e.info("Vehículo: ${profile.title} ${profile.year} · orden de protocolos: ${candidates.joinToString(" → ")}")
                }
                val info = e.initialize(candidates, slowEcu = rec?.slowEcu == true && device.type != LinkType.DEMO) { step ->
                    _conn.value = ConnState.Connecting(device, step)
                }
                val shouldSaveScannedVehicle = device.type != LinkType.DEMO &&
                    info.ecuOk && info.vin?.let(VinDecoder::isValid) == true
                _scannedVehiclePending.value = shouldSaveScannedVehicle
                clearLive()
                _diag.value = DiagState()
                _modules.value = ModuleScanState()
                _conn.value = ConnState.Connected(device, info)
                startPhoneSensors()
                if (device.type != LinkType.DEMO) {
                    settingsStore.update { it.copy(lastDevice = device) }
                    if (profile != null && info.ecuOk) learnFromConnection(profile, info)
                }
                startPolling(e, info)
                if (shouldSaveScannedVehicle) {
                    scannedVehicleSaveJob = viewModelScope.launch(Dispatchers.IO) { saveScannedVehicle(info) }
                }
            } catch (ce: CancellationException) {
                t.close()
                throw ce
            } catch (ex: Exception) {
                t.close()
                elm = null
                transport = null
                _conn.value = ConnState.Failed(ex.message ?: "Error desconocido al conectar", device)
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        scannedVehicleSaveJob?.cancel()
        scannedVehicleSaveJob = null
        _scannedVehiclePending.value = false
        closeLink()
        _conn.value = ConnState.Idle
    }

    fun dismissError() {
        if (_conn.value is ConnState.Failed) _conn.value = ConnState.Idle
    }

    private fun createTransport(d: ObdDevice): ObdTransport {
        if (d.type == LinkType.DEMO) return DemoTransport()
        val adapter = scanner.adapter ?: throw TransportException("Este teléfono no tiene Bluetooth")
        if (!scanner.isBluetoothOn) throw TransportException("Activa el Bluetooth para conectar")
        return when (d.type) {
            LinkType.CLASSIC -> ClassicTransport(adapter, d)
            LinkType.BLE -> BleTransport(getApplication<Application>(), adapter, d)
            LinkType.DEMO -> DemoTransport()
        }
    }

    private fun closeLink() {
        pollJob?.cancel()
        pollJob = null
        stopPhoneSensors()
        if (tripRecorder.state.active) stopTrip()
        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null
        elm = null
    }

    private fun onLinkLost(message: String) {
        val device = (_conn.value as? ConnState.Connected)?.device
        closeLink()
        _conn.value = ConnState.Failed(message, device)
    }

    private fun startLogCollector(e: Elm327) {
        logJob?.cancel()
        logJob = viewModelScope.launch {
            e.log.collect { line -> _terminal.update { (it + line).takeLast(MAX_TERMINAL) } }
        }
    }

    // ------------------------------------------------------------------
    // Disponibilidad de magnitudes
    // ------------------------------------------------------------------

    private val connectedInfo: VehicleInfo?
        get() = (_conn.value as? ConnState.Connected)?.info

    fun isAvailable(key: String): Boolean {
        val info = connectedInfo ?: return false
        val s = info.supportedPids
        val hasProfilePowerSpecs = activeProfile.value?.let { it.hp != null || it.torqueNm != null } == true
        return when (key) {
            Metrics.IMU_ACCEL_X, Metrics.IMU_ACCEL_Y, Metrics.IMU_ACCEL_Z -> linearAccelerationSensor != null
            Metrics.IMU_GYRO_X, Metrics.IMU_GYRO_Y, Metrics.IMU_GYRO_Z -> gyroscopeSensor != null
            Metrics.IMU_HEADING, Metrics.IMU_PITCH, Metrics.IMU_ROLL -> rotationVectorSensor != null
            Metrics.VOLTAGE -> true
            Metrics.FUEL_RATE -> canComputeFuelRate(s)
            Metrics.ECONOMY -> canComputeFuelRate(s) && 0x0D in s
            Metrics.BOOST -> 0x0B in s || 0x87 in s || 0x70 in s
            Metrics.TORQUE, Metrics.POWER -> (0x62 in s && 0x63 in s) ||
                (0x0C in s && (hasProfilePowerSpecs || 0x04 in s || 0x10 in s || 0x0B in s || 0x5E in s))
            Metrics.ACCEL -> 0x0D in s
            Metrics.AFR -> 0x24 in s || 0x44 in s
            else -> Metrics[key]?.pid?.let { it in s } ?: false
        }
    }

    fun availableMetrics(): List<Metric> = Metrics.all.filter { isAvailable(it.key) }

    fun phoneImuAvailable(): Boolean =
        linearAccelerationSensor != null || gyroscopeSensor != null || rotationVectorSensor != null

    fun calibratePhoneImu() {
        val acceleration = lastLinearAcceleration
        val gyroscope = lastGyroscope
        if (acceleration == null && gyroscope == null) {
            _toasts.tryEmit("No hay lecturas IMU para calibrar todavía. Mantén el teléfono quieto y vuelve a intentar.")
            return
        }
        acceleration?.copyInto(linearAccelerationBias)
        gyroscope?.copyInto(gyroscopeBias)
        _imuCalibrated.value = true
        _toasts.tryEmit("IMU del teléfono calibrado en reposo. Esto no calibra sensores del vehículo.")
    }

    private fun startPhoneSensors() {
        if (phoneSensorsStarted) return
        var registered = false
        listOfNotNull(linearAccelerationSensor, gyroscopeSensor, rotationVectorSensor).forEach { sensor ->
            registered = sensorManager.registerListener(
                phoneSensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
                sensorHandler,
            ) || registered
        }
        phoneSensorsStarted = registered
    }

    private fun stopPhoneSensors() {
        if (!phoneSensorsStarted) return
        sensorManager.unregisterListener(phoneSensorListener)
        phoneSensorsStarted = false
    }

    fun setTripMetric(key: String, enabled: Boolean) {
        if (Metrics[key] == null || !isAvailable(key)) return
        _tripMetricKeys.update { current ->
            if (enabled) current + key else current - key
        }
    }

    /** PIDs que la ECU reporta pero la app no decodifica (se pueden leer en crudo). */
    fun undecodedPids(): List<Int> {
        val s = connectedInfo?.supportedPids ?: return emptyList()
        return s.filter { it % 0x20 != 0 && it !in Metrics.decodedPids }.sorted()
    }

    private fun canComputeFuelRate(s: Set<Int>): Boolean =
        0x5E in s || 0x10 in s || (0x0B in s && 0x0C in s && 0x0F in s)

    /** La pantalla visible declara qué quiere ver. */
    fun watch(keys: Set<String>) {
        watched.value = keys
    }

    /** Agrega las dependencias necesarias (p. ej. consumo → MAF + velocidad) y filtra lo no soportado. */
    private fun expand(keys: Set<String>, s: Set<Int>): List<Metric> {
        val out = linkedSetOf<String>()
        out += keys
        if (keys.isNotEmpty()) out += ALERT_MONITOR_KEYS
        val recording = tripRecorder.state.active
        if (recording) {
            out += tripRecorder.recordedKeys
            out += Metrics.SPEED
            out += Metrics.FUEL_RATE
        }
        if (Metrics.BOOST in out) {
            out += when {
                0x70 in s -> "0170"
                0x87 in s -> Metrics.MAP_EXT
                else -> Metrics.MAP
            }
            out += Metrics.BARO
        }
        if (Metrics.TORQUE in out || Metrics.POWER in out) {
            out += Metrics.TORQUE_ACTUAL
            out += Metrics.TORQUE_REF
            out += Metrics.RPM
            out += Metrics.LOAD
            if (0x10 in s) out += Metrics.MAF
            if (0x0B in s) {
                out += Metrics.MAP
                out += Metrics.IAT
            }
            if (0x5E in s) out += Metrics.PID_FUEL_RATE
            out += Metrics.SPEED
        }
        if (Metrics.ACCEL in out) out += Metrics.SPEED
        if (Metrics.AFR in out) out += if (0x24 in s) Metrics.LAMBDA_WIDE else Metrics.LAMBDA_CMD
        if (Metrics.FUEL_RATE in out || Metrics.ECONOMY in out) {
            out += Metrics.SPEED
            when {
                0x5E in s -> out += Metrics.PID_FUEL_RATE
                0x10 in s -> out += Metrics.MAF
                else -> {
                    out += Metrics.MAP
                    out += Metrics.RPM
                    out += Metrics.IAT
                }
            }
        }
        return out.mapNotNull { Metrics[it] }.filter { m ->
            m.key == Metrics.VOLTAGE ||
                (m.pid != null && m.pid in s) ||
                (m.key in IMU_KEYS && isAvailable(m.key))
        }
    }

    // ------------------------------------------------------------------
    // Sondeo en vivo
    // ------------------------------------------------------------------

    private fun startPolling(e: Elm327, info: VehicleInfo) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                pollLoop(e, info)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                onLinkLost(ex.message ?: "Se perdió la conexión con el adaptador")
            }
        }
    }

    private suspend fun pollLoop(e: Elm327, info: VehicleInfo) {
        var cycle = 0L
        var lastVoltageMs = 0L
        while (currentCoroutineContext().isActive) {
            val list = expand(watched.value, info.supportedPids)
            val pidMetrics = list.filter { it.pid != null }
            val wantsVoltage = list.any { it.key == Metrics.VOLTAGE }

            if (pidMetrics.isEmpty() && !wantsVoltage) {
                delay(300)
                continue
            }

            cycleLock.withLock {
                for (m in pidMetrics) {
                    // Las magnitudes lentas (temperaturas, nivel…) se leen 1 de cada 4 ciclos.
                    if (!m.fast && cycle % 4 != 0L && _values.value.containsKey(m.key)) continue
                    val v = e.readMetric(m) ?: continue
                    store(m.key, v)
                }

                val now = System.currentTimeMillis()
                if (wantsVoltage && now - lastVoltageMs > 3_000) {
                    e.readVoltage()?.let { store(Metrics.VOLTAGE, it) }
                    lastVoltageMs = now
                }
            }

            computeDerived(info.supportedPids)

            if (tripRecorder.state.active) {
                tripRecorder.onSample(_values.value)
                _trip.value = tripRecorder.state
            }

            _historyTick.update { it + 1 }

            if (e.consecutiveTimeouts >= 6) throw TransportException("El adaptador dejó de responder")

            cycle++
            val pause = settings.value.pollDelayMs
            when {
                pidMetrics.isEmpty() -> delay(500)
                pause > 0 -> delay(pause.toLong())
                else -> yield()
            }
        }
    }

    /**
     * Consumo de combustible:
     *  1) PID 5E (si la ECU lo da) → L/h directo.
     *  2) MAF: L/h = MAF[g/s] · 3600 / (AFR · densidad[g/L])
     *  3) Sin MAF ("speed-density"): se estima el MAF con MAP, RPM, temperatura de admisión
     *     y la cilindrada:  MAF = RPM/120 · VE · MAP·Vd / (R·T) · 28,97
     */
    private fun computeDerived(s: Set<Int>) {
        val v = _values.value
        val st = settings.value
        val afr = if (st.diesel) 14.5 else 14.7
        val density = if (st.diesel) 832.0 else 737.0 // g/L

        val ecuRate = v[Metrics.PID_FUEL_RATE]
        val maf = v[Metrics.MAF]
        val map = v[Metrics.MAP]
        val rpm = v[Metrics.RPM]
        val iat = v[Metrics.IAT]

        val litersPerHour: Double? = when {
            0x5E in s && ecuRate != null -> ecuRate
            0x10 in s && maf != null -> maf * 3600.0 / (afr * density)
            map != null && rpm != null && iat != null -> {
                val estimatedMaf = rpm / 120.0 * VOLUMETRIC_EFFICIENCY * map * st.displacementL /
                    (8.314 * (iat + 273.15)) * 28.97
                estimatedMaf * 3600.0 / (afr * density)
            }

            else -> null
        }

        if (litersPerHour != null) {
            store(Metrics.FUEL_RATE, litersPerHour)
            val speed = v[Metrics.SPEED]
            if (speed != null) {
                val kmPerL = if (speed > 3.0 && litersPerHour > 0.05) (speed / litersPerHour).coerceAtMost(99.0) else 0.0
                store(Metrics.ECONOMY, kmPerL)
            }
        }

        // Aceleración a partir de dos lecturas de velocidad consecutivas.
        val cur = lastSpeedSample
        val prev = prevSpeedSample
        if (cur != null && prev != null && cur.t != lastAccelT) {
            val dt = (cur.t - prev.t) / 1000.0
            if (dt in 0.1..3.0) {
                val g = (cur.v - prev.v) / 3.6 / dt / 9.81
                accelEma = 0.5 * accelEma + 0.5 * g
                store(Metrics.ACCEL, accelEma.coerceIn(-1.5, 1.5))
            }
            lastAccelT = cur.t
        }

        // Turbo: presión absoluta del múltiple − presión atmosférica (kPa → bar).
        val absolute = v["0170"] ?: v[Metrics.MAP_EXT] ?: v[Metrics.MAP]
        if (absolute != null && (0x0B in s || 0x87 in s || 0x70 in s)) {
            val baro = v[Metrics.BARO] ?: 101.3
            store(Metrics.BOOST, (absolute - baro) / 100.0)
        }

        // -------------------------------------------------------------
        // Cálculo avanzado de Torque y Potencia Instantánea
        // -------------------------------------------------------------
        val torquePct = v[Metrics.TORQUE_ACTUAL]
        val torqueRef = v[Metrics.TORQUE_REF]
        val loadPct = v[Metrics.LOAD]
        val redline = st.redlineRpm.toDouble().coerceAtLeast(4000.0)
        val userHp = (activeProfile.value?.hp ?: st.hp).toDouble().coerceIn(40.0, 1500.0)
        val profileSpecs = activeProfile.value
        val weightKg = (activeProfile.value?.weightKg ?: st.weightKg).toDouble().coerceIn(500.0, 5000.0)
        val effectiveMaf = maf ?: (if (map != null && rpm != null && iat != null) {
            rpm / 120.0 * VOLUMETRIC_EFFICIENCY * map * st.displacementL / (8.314 * (iat + 273.15)) * 28.97
        } else null)

        var calcNm: Double? = null
        var calcHp: Double? = null

        // 1. Datos nativos de la ECU si existen
        if (torquePct != null && torqueRef != null) {
            val nm = (torquePct / 100.0 * torqueRef).coerceAtLeast(0.0)
            calcNm = nm
            if (rpm != null && rpm > 100) {
                calcHp = nm * rpm / 9549.0 * 1.341
            }
        }

        val method = st.powerCalcMethod
        val useNative = (method == PowerCalcMethod.AUTO && calcNm != null)

        if (!useNative) {
            when (method) {
                PowerCalcMethod.SPEC_LOAD -> {
                    // Método Ficha Técnica (HP declarado) + Carga y RPM
                    val specEstimate = if (rpm != null && profileSpecs != null) {
                        estimateFromVehicleSpecs(profileSpecs, rpm, loadPct, redline)
                    } else {
                        null
                    }
                    if (specEstimate != null) {
                        calcNm = specEstimate.first
                        calcHp = specEstimate.second
                    } else if (rpm != null && loadPct != null && rpm > 350) {
                        val rpmFrac = (rpm / (redline * 0.90)).coerceIn(0.1, 1.05)
                        val curve = (1.8 * rpmFrac - 0.8 * rpmFrac * rpmFrac).coerceIn(0.15, 1.0)
                        val hp = (userHp * (loadPct / 100.0) * curve).coerceAtLeast(0.0)
                        calcHp = hp
                        calcNm = (hp * 7120.8 / rpm).coerceAtLeast(0.0)
                    }
                }
                PowerCalcMethod.MAF -> {
                    // Método Flujo Másico de Aire
                    if (effectiveMaf != null && rpm != null && rpm > 350) {
                        val factor = if (st.diesel) 1.15 else 1.32
                        val hp = (effectiveMaf * factor).coerceAtLeast(0.0)
                        calcHp = hp
                        calcNm = (hp * 7120.8 / rpm).coerceAtLeast(0.0)
                    }
                }
                PowerCalcMethod.FUEL_ENERGY -> {
                    // Método Conversión Térmica de Combustible inyectado
                    if (litersPerHour != null && rpm != null && rpm > 350) {
                        val energyKwh = if (st.diesel) 9.97 else 9.04
                        val eff = if (st.diesel) 0.38 else 0.30
                        val kw = (litersPerHour * energyKwh * eff).coerceAtLeast(0.0)
                        calcHp = kw * 1.341
                        calcNm = (kw * 9549.0 / rpm).coerceAtLeast(0.0)
                    }
                }
                PowerCalcMethod.ACCEL -> {
                    // Método Dinámica e Inercia (F = m·a + F_rod + F_aero)
                    val speed = v[Metrics.SPEED]
                    val accel = v[Metrics.ACCEL]
                    if (speed != null && accel != null && speed > 5.0 && accel > 0.02 && rpm != null && rpm > 350) {
                        val vMs = speed / 3.6
                        val aMs2 = accel * 9.81
                        val fInertia = weightKg * aMs2
                        val fRolling = weightKg * 9.81 * 0.015
                        val fAero = 0.5 * 1.2 * 0.75 * vMs * vMs
                        val pWatts = (fInertia + fRolling + fAero) * vMs
                        val kw = (pWatts / 1000.0 / 0.85).coerceAtLeast(0.0)
                        calcHp = kw * 1.341
                        calcNm = (kw * 9549.0 / rpm).coerceAtLeast(0.0)
                    }
                }
                PowerCalcMethod.AUTO -> {
                    // Cascada automática si la ECU no dio PIDs 62/63 nativos:
                    // Prioridad 1: HP/Nm de ficha técnica cuando existen.
                    val specEstimate = if (rpm != null && profileSpecs != null) {
                        estimateFromVehicleSpecs(profileSpecs, rpm, loadPct, redline)
                    } else {
                        null
                    }
                    if (specEstimate != null) {
                        calcNm = specEstimate.first
                        calcHp = specEstimate.second
                    } else if (rpm != null && loadPct != null && rpm > 350 && userHp > 45) {
                        val rpmFrac = (rpm / (redline * 0.90)).coerceIn(0.1, 1.05)
                        val curve = (1.8 * rpmFrac - 0.8 * rpmFrac * rpmFrac).coerceIn(0.15, 1.0)
                        val hp = (userHp * (loadPct / 100.0) * curve).coerceAtLeast(0.0)
                        calcHp = hp
                        calcNm = (hp * 7120.8 / rpm).coerceAtLeast(0.0)
                    } else if (effectiveMaf != null && rpm != null && rpm > 350) {
                        // Prioridad 2: Flujo de aire MAF
                        val factor = if (st.diesel) 1.15 else 1.32
                        val hp = (effectiveMaf * factor).coerceAtLeast(0.0)
                        calcHp = hp
                        calcNm = (hp * 7120.8 / rpm).coerceAtLeast(0.0)
                    } else if (litersPerHour != null && rpm != null && rpm > 350) {
                        // Prioridad 3: Caudal de combustible
                        val energyKwh = if (st.diesel) 9.97 else 9.04
                        val eff = if (st.diesel) 0.38 else 0.30
                        val kw = (litersPerHour * energyKwh * eff).coerceAtLeast(0.0)
                        calcHp = kw * 1.341
                        calcNm = (kw * 9549.0 / rpm).coerceAtLeast(0.0)
                    }
                }
            }
        }

        if (calcNm != null && !calcNm.isNaN() && !calcNm.isInfinite()) {
            store(Metrics.TORQUE, calcNm.coerceIn(0.0, 2500.0))
        }
        if (calcHp != null && !calcHp.isNaN() && !calcHp.isInfinite()) {
            store(Metrics.POWER, calcHp.coerceIn(0.0, 1500.0))
        }

        // Relación aire/combustible = lambda × estequiométrica.
        val lambda = v[Metrics.LAMBDA_WIDE] ?: v[Metrics.LAMBDA_CMD]
        if (lambda != null && lambda > 0.3) store(Metrics.AFR, lambda * afr)
    }

    private fun estimateFromVehicleSpecs(
        profile: VehicleProfile,
        rpm: Double,
        engineLoadPercent: Double?,
        redlineRpm: Double,
    ): Pair<Double, Double>? {
        if (rpm <= 350.0 || (profile.hp == null && profile.torqueNm == null)) return null
        val peakHp = profile.hp?.toDouble()
        val peakHpRpm = profile.hpRpm?.toDouble() ?: redlineRpm * 0.90
        val peakTorque = profile.torqueNm ?: peakHp?.let { it * 7120.8 / peakHpRpm }
        val peakTorqueRpm = profile.torqueRpm?.toDouble()
            ?: profile.hpRpm?.times(0.65)
            ?: redlineRpm * 0.60
        val loadScale = ((engineLoadPercent ?: 100.0) / 100.0).coerceIn(0.0, 1.0)

        val torqueCurve = peakTorque?.let { maxTorque ->
            when {
                rpm < peakTorqueRpm ->
                    maxTorque * (0.55 + 0.45 * (rpm / peakTorqueRpm).coerceIn(0.0, 1.0))
                peakHp != null && peakHpRpm > peakTorqueRpm && rpm <= peakHpRpm -> {
                    val torqueAtHpPeak = peakHp * 7120.8 / peakHpRpm
                    val progress = ((rpm - peakTorqueRpm) / (peakHpRpm - peakTorqueRpm)).coerceIn(0.0, 1.0)
                    maxTorque + (torqueAtHpPeak - maxTorque) * progress
                }
                else -> {
                    val falloffStart = if (peakHp != null) peakHpRpm else peakTorqueRpm
                    val progress = ((rpm - falloffStart) / (redlineRpm - falloffStart).coerceAtLeast(1.0))
                        .coerceIn(0.0, 1.0)
                    maxTorque * (1.0 - 0.35 * progress)
                }
            }
        }
        val hpCurve = peakHp?.let { maxHp ->
            val fraction = (rpm / peakHpRpm).coerceIn(0.0, 1.0)
            maxHp * (1.8 * fraction - 0.8 * fraction * fraction).coerceIn(0.0, 1.0)
        }
        val torqueFromHp = hpCurve?.let { it * 7120.8 / rpm }
        val estimatedTorque = when {
            torqueCurve != null && torqueFromHp != null -> minOf(torqueCurve, torqueFromHp)
            torqueCurve != null -> torqueCurve
            torqueFromHp != null -> torqueFromHp
            else -> return null
        } * loadScale
        return estimatedTorque to (estimatedTorque * rpm / 7120.8)
    }

    @Volatile private var lastSpeedSample: Sample? = null
    @Volatile private var prevSpeedSample: Sample? = null
    private var lastAccelT = 0L
    private var accelEma = 0.0

    private fun store(key: String, v: Double) {
        _values.update { it + (key to v) }
        updateVehicleAlert(key, v)
        val now = System.currentTimeMillis()
        if (key == Metrics.SPEED) {
            prevSpeedSample = lastSpeedSample
            lastSpeedSample = Sample(now, v)
        }
        synchronized(history) {
            val q = history.getOrPut(key) { ArrayDeque() }
            q.addLast(Sample(now, v))
            while (q.size > MAX_HISTORY || (q.isNotEmpty() && now - q.first().t > MAX_HISTORY_MS)) q.removeFirst()
        }
        if (key in IMU_KEYS) _historyTick.update { it + 1 }
    }

    private fun updateVehicleAlert(key: String, value: Double) {
        val metric = Metrics[key] ?: return
        val reason = when (key) {
            Metrics.COOLANT -> when {
                value > 110.0 -> "El refrigerante supera 110 °C; reduce la carga y detente en un lugar seguro si la temperatura sigue subiendo."
                value < -30.0 -> "La lectura de refrigerante parece fuera del rango físico esperado; verifica sensor y conexión."
                else -> null
            }
            Metrics.OIL_TEMP -> when {
                value > 140.0 -> "El aceite supera 140 °C; reduce la carga y revisa el sistema de lubricación/enfriamiento."
                value < -30.0 -> "La lectura de temperatura de aceite parece fuera del rango físico esperado."
                else -> null
            }
            Metrics.STFT1, Metrics.STFT2, Metrics.LTFT1, Metrics.LTFT2 ->
                if (kotlin.math.abs(value) >= 15.0) {
                    "${metric.short} está en ${String.format(Locale.US, "%.1f", value)}%; una corrección persistente mayor a ±15% puede indicar mezcla fuera de ajuste."
                } else {
                    null
                }
            Metrics.LAMBDA_WIDE, Metrics.LAMBDA_CMD -> {
                val load = _values.value[Metrics.LOAD] ?: 0.0
                when {
                    value > 1.25 || (value > 1.15 && load < 80.0) ->
                        "Lambda ${String.format(Locale.US, "%.2f", value)} indica mezcla muy pobre para la carga reportada; verifica trims y alimentación."
                    value < 0.70 && load < 80.0 ->
                        "Lambda ${String.format(Locale.US, "%.2f", value)} indica mezcla muy rica a carga baja; verifica sensores y control de combustible."
                    else -> null
                }
            }
            Metrics.AFR -> {
                val isDiesel = activeProfile.value?.isDiesel ?: settings.value.diesel
                val tooRich = value < 10.0
                val tooLean = value > if (isDiesel) 30.0 else 18.0
                when {
                    tooRich -> "AFR ${String.format(Locale.US, "%.1f", value)} es muy rico para el combustible configurado."
                    tooLean -> "AFR ${String.format(Locale.US, "%.1f", value)} es muy pobre para el combustible configurado."
                    else -> null
                }
            }
            else -> null
        }
        if (reason == null || value.isNaN() || value.isInfinite()) {
            alertStreaks.remove(key)
            _vehicleAlerts.update { alerts -> alerts.filterNot { it.metricKey == key } }
            return
        }
        val streak = (alertStreaks[key] ?: 0) + 1
        alertStreaks[key] = streak
        if (streak < ALERT_CONFIRMATION_SAMPLES) return
        val alert = VehicleMetricAlert(
            metricKey = key,
            value = value,
            message = reason,
            vehicleName = activeProfile.value?.let { "${it.title} ${it.year}" } ?: "vehículo conectado",
        )
        _vehicleAlerts.update { alerts ->
            (alerts.filterNot { it.metricKey == key } + alert).takeLast(MAX_ACTIVE_ALERTS)
        }
    }

    fun history(key: String): List<Sample> = synchronized(history) { history[key]?.toList() ?: emptyList() }

    private fun clearLive() {
        _values.value = emptyMap()
        _vehicleAlerts.value = emptyList()
        alertStreaks.clear()
        lastSpeedSample = null
        prevSpeedSample = null
        accelEma = 0.0
        synchronized(history) { history.clear() }
        _historyTick.update { it + 1 }
    }

    // ------------------------------------------------------------------
    // Diagnóstico
    // ------------------------------------------------------------------

    fun readDiagnostics() {
        val e = elm ?: return
        val info = connectedInfo ?: return
        if (_diag.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            _diag.update { it.copy(loading = true, step = "Leyendo estado del Check Engine…", message = null) }
            try {
                val status = e.readStatus()
                val statusSinceClear = if (0x41 in info.supportedPids) e.readStatusSinceClear() else null
                val oxygenSensors = if (0x13 in info.supportedPids) e.readOxygenSensorsPresent() else null
                val fuelType = if (0x51 in info.supportedPids) e.readFuelType() else null

                _diag.update { it.copy(step = "Leyendo códigos confirmados…") }
                val stored = e.readDtcs("03").map { DtcInfo.item(it, DtcKind.STORED) }

                _diag.update { it.copy(step = "Leyendo códigos pendientes…") }
                val pending = e.readDtcs("07").map { DtcInfo.item(it, DtcKind.PENDING) }

                _diag.update { it.copy(step = "Leyendo códigos permanentes…") }
                val permanent = e.readDtcs("0A").map { DtcInfo.item(it, DtcKind.PERMANENT) }

                val all = stored +
                    pending.filter { p -> stored.none { it.code == p.code } } +
                    permanent.filter { p -> stored.none { it.code == p.code } && pending.none { it.code == p.code } }

                var freeze: FreezeFrame? = null
                if (stored.isNotEmpty()) {
                    _diag.update { it.copy(step = "Leyendo cuadro congelado…") }
                    val metrics = FREEZE_KEYS.mapNotNull { Metrics[it] }
                        .filter { m -> m.pid != null && m.pid in info.supportedPids }
                    freeze = e.readFreezeFrame(metrics)
                }

                _diag.value = DiagState(
                    loading = false,
                    status = status,
                    statusSinceClear = statusSinceClear,
                    oxygenSensorsPresent = oxygenSensors,
                    fuelType = fuelType,
                    dtcs = all,
                    freeze = freeze,
                    readAt = System.currentTimeMillis(),
                    message = if (all.isEmpty()) "No hay códigos de falla registrados." else null,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                _diag.update { it.copy(loading = false, message = "Error al leer: ${ex.message}") }
                if (ex is TransportException) onLinkLost(ex.message ?: "Conexión perdida")
            }
        }
    }

    fun clearDtcs() {
        val e = elm ?: return
        if (_diag.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            _diag.update { it.copy(loading = true, step = "Borrando códigos…", message = null) }
            val ok = try {
                e.clearDtcs()
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                false
            }
            _diag.update {
                it.copy(
                    loading = false,
                    message = if (ok) "Códigos borrados. Leyendo de nuevo…" else "La ECU no aceptó el borrado. Pon el encendido en ON con el motor apagado e inténtalo otra vez.",
                )
            }
            if (ok) {
                delay(1_000)
                readDiagnostics()
            }
        }
    }

    // ------------------------------------------------------------------
    // Terminal
    // ------------------------------------------------------------------

    fun sendTerminal(command: String) {
        val cmd = command.trim().uppercase()
        if (cmd.isEmpty()) return
        val e = elm
        if (e == null) {
            _terminal.update { it + TermLine(TermLine.Dir.INFO, "No hay un adaptador conectado.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                e.send(cmd, 10_000, logIt = true)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                _terminal.update { it + TermLine(TermLine.Dir.INFO, "Error: ${ex.message}") }
                if (ex is TransportException) onLinkLost(ex.message ?: "Conexión perdida")
            }
        }
    }

    fun clearTerminal() {
        _terminal.value = emptyList()
    }

    // ------------------------------------------------------------------
    // Viajes
    // ------------------------------------------------------------------

    fun startTrip(recordGps: Boolean = false): Boolean {
        if (connectedInfo == null) return false
        val columns = Metrics.all.filter { it.key in tripMetricKeys.value && isAvailable(it.key) }
        if (columns.isEmpty()) {
            _toasts.tryEmit("Selecciona al menos una variable disponible para registrar el viaje.")
            return false
        }
        val locationProvider = if (recordGps) {
            val fineGranted = ContextCompat.checkSelfPermission(
                getApplication<Application>(),
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val coarseGranted = ContextCompat.checkSelfPermission(
                getApplication<Application>(),
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (!fineGranted && !coarseGranted) {
                _toasts.tryEmit("Concede permiso de ubicación para guardar la ruta, o desactiva la opción GPS.")
                return false
            }
            val providers = if (fineGranted) {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            } else {
                listOf(LocationManager.NETWORK_PROVIDER)
            }
            providers.firstOrNull { it in locationManager.getProviders(true) } ?: run {
                _toasts.tryEmit("Activa la ubicación del teléfono para registrar la ruta GPS.")
                return false
            }
        } else {
            null
        }

        try {
            TripRecordingService.start(getApplication(), recordGps = locationProvider != null)
        } catch (ex: IllegalStateException) {
            _toasts.tryEmit("Android no permitió mantener activo el registro: ${ex.message ?: "servicio en segundo plano no disponible"}.")
            return false
        } catch (ex: SecurityException) {
            _toasts.tryEmit("Falta un permiso requerido para mantener el registro activo: ${ex.message ?: "permiso denegado"}.")
            return false
        }

        val profile = activeProfile.value
        val vehicleFilename = profile?.let { "${it.title}_${it.year}" }
            ?: connectedInfo?.vin
            ?: "vehiculo_sin_registro"
        tripRecorder.start(columns, recordGps = locationProvider != null, vehicleName = vehicleFilename)
        if (locationProvider != null) {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    tripRecorder.updateLocation(
                        location.latitude,
                        location.longitude,
                        location.accuracy.takeIf { location.hasAccuracy() },
                    )
                }

                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
            try {
                locationManager.requestLocationUpdates(
                    locationProvider,
                    1_000L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
                tripLocationListener = listener
            } catch (ex: SecurityException) {
                tripRecorder.stop()
                _trip.value = tripRecorder.state
                TripRecordingService.stop(getApplication())
                _toasts.tryEmit("No se pudo iniciar el GPS: ${ex.message ?: "permiso de ubicación denegado"}.")
                return false
            }
        }
        _trip.value = tripRecorder.state
        return true
    }

    fun stopTrip() {
        stopTripLocationUpdates()
        tripRecorder.stop()
        TripRecordingService.stop(getApplication())
        _trip.value = tripRecorder.state
        refreshTripFiles()
    }

    private fun stopTripLocationUpdates() {
        val listener = tripLocationListener ?: return
        tripLocationListener = null
        try {
            locationManager.removeUpdates(listener)
        } catch (_: SecurityException) {
            _toasts.tryEmit("Se revocó el permiso de ubicación; se guardó el viaje sin más puntos GPS.")
        }
    }

    fun refreshTripFiles() {
        _tripFiles.value = tripRecorder.files()
    }

    fun deleteTrip(file: File) {
        file.delete()
        refreshTripFiles()
    }

    // ------------------------------------------------------------------
    // Garaje y protocolos
    // ------------------------------------------------------------------

    fun recommendation(p: VehicleProfile): ProtocolRecommendation = ProtocolAdvisor.recommend(p, vehicleDb.value)

    /** Guarda el perfil y, si es el activo (o [activate]), aplica sus datos al cálculo de consumo y tacómetro. */
    fun saveProfile(p: VehicleProfile, activate: Boolean) {
        val normalized = p.copy(make = vehicleDb.value.canonicalMake(p.make))
        garage.upsert(normalized)
        if (activate || settings.value.activeProfileId == normalized.id) applyProfile(normalized)
    }

    private suspend fun saveScannedVehicle(info: VehicleInfo) {
        try {
            val vin = info.vin?.takeIf(VinDecoder::isValid) ?: return
            val learnedProtocol = info.protocolNumber.takeIf { it.isNotBlank() && it != "0" }
            val savedByVin = garage.profiles.value.firstOrNull { it.vin.equals(vin, ignoreCase = true) }
            if (savedByVin != null) {
                val updated = savedByVin.copy(learnedProtocol = learnedProtocol ?: savedByVin.learnedProtocol)
                garage.upsert(updated)
                applyProfile(updated)
                _toasts.emit("Vehículo reconocido y perfil guardado: ${updated.title} ${updated.year}.")
                return
            }

            val decoded = try {
                OnlineService.decodeVin(vin)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                VinDecoder.decodeOffline(vin)
            }
            val make = decoded?.make?.takeIf { it.isNotBlank() }
            val model = decoded?.model?.takeIf { it.isNotBlank() }
            val year = decoded?.year
            if (decoded == null || make == null || model == null || year == null) {
                _toasts.emit("Se leyó el VIN, pero no se pudo identificar marca, modelo y año. Completa el registro para guardar el vehículo.")
                return
            }

            val canonicalMake = vehicleDb.value.canonicalMake(make)
            val template = vehicleDb.value.templates.firstOrNull {
                vehicleDb.value.canonicalMake(it.make).equals(canonicalMake, ignoreCase = true) &&
                    (it.model.equals(model, ignoreCase = true) ||
                        model.contains(it.model, ignoreCase = true) ||
                        it.model.contains(model, ignoreCase = true))
            }
            val existing = garage.profiles.value.firstOrNull {
                it.vin.isNullOrBlank() &&
                    vehicleDb.value.canonicalMake(it.make).equals(canonicalMake, ignoreCase = true) &&
                    it.model.equals(model, ignoreCase = true) &&
                    it.year == year
            }
            val defaults = existing ?: template ?: VehicleProfile()
            val profile = defaults.copy(
                make = canonicalMake,
                model = model,
                year = year,
                engine = decoded.engine ?: defaults.engine,
                displacementL = decoded.displacementL ?: defaults.displacementL,
                fuel = decoded.fuel ?: defaults.fuel,
                drive = decoded.drive ?: defaults.drive,
                transmission = decoded.transmission ?: defaults.transmission,
                hp = decoded.hp ?: defaults.hp,
                learnedProtocol = learnedProtocol ?: defaults.learnedProtocol,
                vin = vin,
            )
            garage.upsert(profile)
            applyProfile(profile)
            val incompleteSpecs = existing == null && template == null &&
                (decoded.fuel == null || decoded.displacementL == null)
            _toasts.emit(
                if (incompleteSpecs) {
                    "Vehículo guardado: ${profile.title} ${profile.year}. Verifica combustible y cilindrada en el perfil; el VIN no los identificó."
                } else {
                    "Vehículo escaneado y guardado: ${profile.title} ${profile.year}."
                },
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            _toasts.emit("No se pudo guardar el vehículo escaneado: ${ex.message ?: ex.javaClass.simpleName}.")
        } finally {
            if (currentCoroutineContext().isActive) _scannedVehiclePending.value = false
        }
    }

    fun selectProfile(id: String?) {
        val p = garage.get(id)
        if (p == null) {
            settingsStore.update { it.copy(activeProfileId = null) }
        } else {
            applyProfile(p)
        }
    }

    fun deleteProfile(id: String) {
        garage.delete(id)
        if (settings.value.activeProfileId == id) settingsStore.update { it.copy(activeProfileId = null) }
    }

    private fun applyProfile(p: VehicleProfile) {
        settingsStore.update {
            it.copy(
                activeProfileId = p.id,
                diesel = p.isDiesel,
                displacementL = p.displacementL,
                redlineRpm = p.redlineRpm,
                hp = p.hp ?: it.hp,
                weightKg = p.weightKg ?: it.weightKg,
            )
        }
    }

    /** Guarda en el perfil el protocolo con el que respondió. */
    private fun learnFromConnection(p: VehicleProfile, info: VehicleInfo) {
        val proto = info.protocolNumber.takeIf { it.isNotBlank() && it != "0" }
        val updated = p.copy(
            learnedProtocol = proto ?: p.learnedProtocol,
        )
        if (updated != p) {
            garage.upsert(updated)
            if (proto != null && proto != p.learnedProtocol) {
                _toasts.tryEmit("Protocolo aprendido para ${p.title}: ${Elm327.protocolName(proto)}")
            }
        }
    }

    /** Prueba todos los protocolos uno por uno (para vehículos que no están en la base). */
    fun scanAllProtocols() {
        val e = elm ?: return
        if (_protoScan.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            _protoScan.value = ProtocolScanState(loading = true, step = "Preparando…")
            try {
                val results = cycleLock.withLock {
                    e.scanProtocols { step -> _protoScan.update { it.copy(step = step) } }
                }
                _protoScan.value = ProtocolScanState(results = results)
                val ok = results.firstOrNull { it.ok }
                _toasts.tryEmit(
                    if (ok != null) "Tu vehículo responde con ${ok.name}. Toca «Usar» para reconectar con él."
                    else "Ningún protocolo respondió. Arranca el motor y vuelve a intentarlo.",
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                _protoScan.value = ProtocolScanState(error = ex.message ?: "Error en el escaneo")
                if (ex is TransportException) onLinkLost(ex.message ?: "Conexión perdida")
            }
        }
    }

    /** Usa un protocolo encontrado: lo guarda en el vehículo activo y reconecta. */
    fun useProtocol(code: String) {
        val p = activeProfile.value
        if (p != null) {
            garage.upsert(p.copy(protocolOverride = null, learnedProtocol = code))
        } else {
            settingsStore.update { it.copy(protocol = code) }
        }
        _protoScan.value = ProtocolScanState()
        val device = (_conn.value as? ConnState.Connected)?.device
        if (device != null) {
            closeLink()
            _conn.value = ConnState.Idle
            connect(device)
        }
    }

    // ------------------------------------------------------------------
    // Módulos (solo lectura)
    // ------------------------------------------------------------------

    fun scanModules() {
        val e = elm ?: return
        if (_modules.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            _modules.value = ModuleScanState(loading = true, step = "Preparando…")
            try {
                val list = cycleLock.withLock {
                    e.scanModules { step -> _modules.update { it.copy(step = step) } }
                }
                _modules.value = ModuleScanState(modules = list, readAt = System.currentTimeMillis(),
                    error = if (list.isEmpty()) "Ningún módulo respondió a la consulta." else null)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                _modules.value = ModuleScanState(error = ex.message ?: "Error al leer los módulos")
                if (ex is TransportException) onLinkLost(ex.message ?: "Conexión perdida")
            }
        }
    }

    /** Lee una vez un PID no decodificado y devuelve sus bytes en hexadecimal. */
    suspend fun readRawPid(pid: Int): String? {
        val e = elm ?: return null
        return withContext(Dispatchers.IO) {
            try {
                cycleLock.withLock {
                    when (pid) {
                        0x01 -> e.readStatus()?.let(::statusSummary)
                        0x41 -> e.readStatusSinceClear()?.let(::statusSummary)
                        0x13 -> e.readOxygenSensorsPresent()?.let { sensors ->
                            sensors.joinToString(", ") { "B${it.bank}S${it.position}" }
                                .ifBlank { "Ningún sensor marcado como presente" }
                        }
                        0x51 -> e.readFuelType()?.label
                        else -> e.rawMode01(pid)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                null
            }
        }
    }

    private fun statusSummary(status: VehicleStatus): String = buildString {
        append(if (status.milOn) "Check Engine encendido" else "Check Engine apagado")
        append(" · ${status.dtcCount} DTC · ")
        append(status.monitors.count { it.available && it.ready })
        append(" monitores listos")
    }

    // ------------------------------------------------------------------
    // Internet
    // ------------------------------------------------------------------

    fun updateVehicleDb() {
        if (_dbUpdating.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _dbUpdating.value = true
            _dbStatus.value = "Descargando…"
            _dbStatus.value = try {
                dbStore.update(settings.value.dbUrl)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                "No se pudo actualizar: ${ex.message ?: ex.javaClass.simpleName}. Revisa tu conexión a internet."
            }
            _dbUpdating.value = false
        }
    }

    /** Decodifica un VIN: primero en línea (NHTSA); si falla, sin internet. */
    suspend fun lookupVin(vin: String): Result<VinInfo> = withContext(Dispatchers.IO) {
        if (!VinDecoder.isValid(vin)) return@withContext Result.failure(IllegalArgumentException("El VIN debe tener 17 caracteres (sin I, O ni Q)."))
        try {
            Result.success(OnlineService.decodeVin(vin))
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            VinDecoder.decodeOffline(vin)?.let { Result.success(it.copy(warning = "Sin internet: solo marca, país y año. " + (it.warning ?: ""))) }
                ?: Result.failure(ex)
        }
    }

    /** VIN leído del vehículo conectado (si lo hay). */
    fun connectedVin(): String? = connectedInfo?.vin

    fun notify(message: String) {
        _toasts.tryEmit(message)
    }

    // ------------------------------------------------------------------
    // Ajustes
    // ------------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    override fun onCleared() {
        stopTripLocationUpdates()
        tripRecorder.stop()
        TripRecordingService.stop(getApplication())
        closeLink()
        scanner.release()
        super.onCleared()
    }

    companion object {
        val DEMO_DEVICE = ObdDevice("Simulador ELM327", "DEMO", LinkType.DEMO)

        private const val MAX_HISTORY = 4_000
        private const val MAX_HISTORY_MS = 10 * 60 * 1000L
        private const val MAX_TERMINAL = 400
        private const val ALERT_CONFIRMATION_SAMPLES = 3
        private const val MAX_ACTIVE_ALERTS = 5
        private const val VOLUMETRIC_EFFICIENCY = 0.85
        private val IMU_KEYS = setOf(
            Metrics.IMU_ACCEL_X, Metrics.IMU_ACCEL_Y, Metrics.IMU_ACCEL_Z,
            Metrics.IMU_GYRO_X, Metrics.IMU_GYRO_Y, Metrics.IMU_GYRO_Z,
            Metrics.IMU_HEADING, Metrics.IMU_PITCH, Metrics.IMU_ROLL,
        )
        private val ALERT_MONITOR_KEYS = setOf(
            Metrics.COOLANT, Metrics.OIL_TEMP, Metrics.STFT1, Metrics.LTFT1,
            Metrics.STFT2, Metrics.LTFT2, Metrics.LAMBDA_WIDE, Metrics.LAMBDA_CMD, Metrics.LOAD,
        )

        val TRIP_KEYS = listOf(
            Metrics.SPEED, Metrics.RPM, Metrics.COOLANT, Metrics.LOAD, Metrics.THROTTLE,
            Metrics.MAF, Metrics.MAP, Metrics.IAT, Metrics.FUEL_LEVEL,
            Metrics.FUEL_RATE, Metrics.ECONOMY, Metrics.VOLTAGE,
            Metrics.BOOST, Metrics.ACCEL, Metrics.TORQUE, Metrics.POWER,
            Metrics.RAIL_PRESSURE, Metrics.EGT, Metrics.EGR_CMD, Metrics.OIL_TEMP,
        )

        private val FREEZE_KEYS = listOf(
            Metrics.RPM, Metrics.SPEED, Metrics.COOLANT, Metrics.LOAD, Metrics.THROTTLE,
            Metrics.MAP, Metrics.IAT,
        )
    }
}
