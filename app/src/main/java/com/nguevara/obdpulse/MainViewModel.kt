package com.nguevara.obdpulse

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
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
import com.nguevara.obdpulse.data.GarageStore
import com.nguevara.obdpulse.data.SettingsStore
import com.nguevara.obdpulse.data.VehicleDbStore
import com.nguevara.obdpulse.data.TripRecorder
import com.nguevara.obdpulse.data.TripState
import com.nguevara.obdpulse.obd.DtcInfo
import com.nguevara.obdpulse.obd.DtcItem
import com.nguevara.obdpulse.obd.DtcKind
import com.nguevara.obdpulse.obd.EcuModule
import com.nguevara.obdpulse.obd.Elm327
import com.nguevara.obdpulse.obd.FreezeFrame
import com.nguevara.obdpulse.obd.Metric
import com.nguevara.obdpulse.obd.Metrics
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

/** Verificación de módulos (solo lectura). */
data class ModuleScanState(
    val loading: Boolean = false,
    val step: String = "",
    val modules: List<EcuModule> = emptyList(),
    val readAt: Long? = null,
    val error: String? = null,
)

data class PhoneImuCalibrationState(
    val calibrating: Boolean = false,
    val calibrated: Boolean = false,
    val message: String? = null,
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
    val dtcs: List<DtcItem> = emptyList(),
    val freeze: FreezeFrame? = null,
    val readAt: Long? = null,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app), SensorEventListener {

    private val settingsStore = SettingsStore(app)
    val settings: StateFlow<AppSettings> = settingsStore.state

    val scanner = DeviceScanner(app)
    private val tripRecorder = TripRecorder(File(app.filesDir, "trips"))
    private val sensorManager = app.getSystemService(Application.SENSOR_SERVICE) as SensorManager
    private val sensorHandler = Handler(Looper.getMainLooper())
    private val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var phoneSensorsRegistered = false
    private val phoneAccelOffset = FloatArray(3)
    private val phoneGyroOffset = FloatArray(3)
    private val calibrationLock = Any()
    private var calibrationAccelSamples: MutableList<FloatArray>? = null
    private var calibrationGyroSamples: MutableList<FloatArray>? = null
    private var imuCalibrationJob: Job? = null
    private val _imuCalibration = MutableStateFlow(PhoneImuCalibrationState())
    val imuCalibration: StateFlow<PhoneImuCalibrationState> = _imuCalibration.asStateFlow()

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

    /** Historial para las gráficas. [historyTick] cambia cada vez que llega un ciclo nuevo. */
    private val history = HashMap<String, ArrayDeque<Sample>>()
    private val _historyTick = MutableStateFlow(0L)
    val historyTick: StateFlow<Long> = _historyTick.asStateFlow()

    private val _trip = MutableStateFlow(TripState())
    val trip: StateFlow<TripState> = _trip.asStateFlow()

    private val _tripFiles = MutableStateFlow<List<File>>(emptyList())
    val tripFiles: StateFlow<List<File>> = _tripFiles.asStateFlow()

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
        sensorManager.unregisterListener(this)
        phoneSensorsRegistered = false
        imuCalibrationJob?.cancel()
        imuCalibrationJob = null
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
        if (Metrics.isPhoneSensor(key)) {
            if (connectedInfo == null) return false
            return when (key) {
                Metrics.PHONE_ACCEL_X, Metrics.PHONE_ACCEL_Y, Metrics.PHONE_ACCEL_Z, Metrics.PHONE_ACCEL_MAG ->
                    linearAccelerationSensor != null
                Metrics.PHONE_GYRO_X, Metrics.PHONE_GYRO_Y, Metrics.PHONE_YAW_RATE -> gyroscopeSensor != null
                Metrics.PHONE_YAW, Metrics.PHONE_PITCH, Metrics.PHONE_ROLL -> rotationSensor != null
                else -> false
            }
        }
        val info = connectedInfo ?: return false
        val s = info.supportedPids
        return when (key) {
            Metrics.VOLTAGE -> true
            Metrics.FUEL_RATE -> canComputeFuelRate(s)
            Metrics.ECONOMY -> canComputeFuelRate(s) && 0x0D in s
            Metrics.BOOST -> 0x0B in s || 0x87 in s || 0x70 in s
            Metrics.TORQUE -> 0x62 in s && 0x63 in s
            Metrics.POWER -> (0x62 in s && 0x63 in s && 0x0C in s) || (0x10 in s && !settings.value.diesel)
            Metrics.ACCEL -> 0x0D in s
            Metrics.AFR -> 0x24 in s || 0x44 in s
            else -> Metrics[key]?.pid?.let { it in s } ?: false
        }
    }

    fun availableMetrics(): List<Metric> = Metrics.all.filter { isAvailable(it.key) }

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
            if (Metrics.POWER in out && !(0x62 in s && 0x63 in s)) out += Metrics.MAF
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
            m.key == Metrics.VOLTAGE || Metrics.isPhoneSensor(m.key) || (m.pid != null && m.pid in s)
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
            val wantsPhoneSensors = list.any { Metrics.isPhoneSensor(it.key) }

            if (pidMetrics.isEmpty() && !wantsVoltage && !wantsPhoneSensors) {
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

        // Turbo: presión absoluta del múltiple − presión atmosférica (kPa → bar).
        val absolute = v["0170"] ?: v[Metrics.MAP_EXT] ?: v[Metrics.MAP]
        if (absolute != null && (0x0B in s || 0x87 in s || 0x70 in s)) {
            val baro = v[Metrics.BARO] ?: 101.3
            store(Metrics.BOOST, (absolute - baro) / 100.0)
        }

        // Torque y potencia: % de torque real × torque de referencia (PID 62 y 63).
        val torquePct = v[Metrics.TORQUE_ACTUAL]
        val torqueRef = v[Metrics.TORQUE_REF]
        if (torquePct != null && torqueRef != null) {
            val nm = (torquePct / 100.0 * torqueRef).coerceAtLeast(0.0)
            store(Metrics.TORQUE, nm)
            if (rpm != null) store(Metrics.POWER, nm * rpm / 9549.0 * 1.341) // kW → hp
        } else if (maf != null && !st.diesel && 0x10 in s) {
            // Regla práctica para gasolina en mezcla estequiométrica: ≈ 1,32 hp por g/s de aire.
            store(Metrics.POWER, maf * 1.32)
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

        // Relación aire/combustible = lambda × estequiométrica.
        val lambda = v[Metrics.LAMBDA_WIDE] ?: v[Metrics.LAMBDA_CMD]
        if (lambda != null && lambda > 0.3) store(Metrics.AFR, lambda * afr)
    }

    @Volatile private var lastSpeedSample: Sample? = null
    @Volatile private var prevSpeedSample: Sample? = null
    private var lastAccelT = 0L
    private var accelEma = 0.0

    private fun store(key: String, v: Double) {
        _values.update { it + (key to v) }
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
    }

    fun history(key: String): List<Sample> = synchronized(history) { history[key]?.toList() ?: emptyList() }

    private fun clearLive() {
        _values.value = emptyMap()
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

    fun startTrip() {
        startTrip(TRIP_KEYS.toSet())
    }

    fun startTrip(keys: Set<String>) {
        if (connectedInfo == null) return
        val columns = keys.mapNotNull { Metrics[it] }.filter { isAvailable(it.key) }
        if (columns.isEmpty()) {
            notify("Selecciona al menos una variable para el registro.")
            return
        }
        tripRecorder.start(columns)
        _trip.value = tripRecorder.state
    }

    private fun startPhoneSensors() {
        if (phoneSensorsRegistered) return
        val sensors = listOfNotNull(linearAccelerationSensor, gyroscopeSensor, rotationSensor)
        sensors.forEach {
            phoneSensorsRegistered = sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME,
                sensorHandler,
            ) || phoneSensorsRegistered
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                synchronized(calibrationLock) {
                    calibrationAccelSamples?.add(event.values.copyOf())
                }
                val a = FloatArray(3) { event.values[it] - phoneAccelOffset[it] }
                store(Metrics.PHONE_ACCEL_X, a[0].toDouble())
                store(Metrics.PHONE_ACCEL_Y, a[1].toDouble())
                store(Metrics.PHONE_ACCEL_Z, a[2].toDouble())
                val magnitude = vectorMagnitude(a) / 9.80665
                store(Metrics.PHONE_ACCEL_MAG, magnitude)
            }
            Sensor.TYPE_GYROSCOPE -> {
                synchronized(calibrationLock) {
                    calibrationGyroSamples?.add(event.values.copyOf())
                }
                val scale = 180.0 / Math.PI
                val g = DoubleArray(3) { (event.values[it] - phoneGyroOffset[it]) * scale }
                store(Metrics.PHONE_GYRO_X, g[0])
                store(Metrics.PHONE_GYRO_Y, g[1])
                store(Metrics.PHONE_YAW_RATE, g[2])
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val matrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                val degrees = 180.0 / Math.PI
                store(Metrics.PHONE_YAW, orientation[0] * degrees)
                store(Metrics.PHONE_PITCH, orientation[1] * degrees)
                store(Metrics.PHONE_ROLL, orientation[2] * degrees)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Calibra solo el sesgo del IMU del teléfono; el teléfono debe permanecer inmóvil. */
    fun calibratePhoneImu() {
        if (linearAccelerationSensor == null && gyroscopeSensor == null) {
            _imuCalibration.value = PhoneImuCalibrationState(message = "Este teléfono no informa acelerómetro lineal ni giroscopio.")
            return
        }
        if (!phoneSensorsRegistered) startPhoneSensors()
        if (imuCalibrationJob?.isActive == true) return
        synchronized(calibrationLock) {
            calibrationAccelSamples = mutableListOf()
            calibrationGyroSamples = mutableListOf()
        }
        _imuCalibration.value = PhoneImuCalibrationState(
            calibrating = true,
            message = "Deja el teléfono quieto y montado como se usará durante 3 segundos.",
        )
        imuCalibrationJob = viewModelScope.launch {
            delay(3_000)
            val (accelSamples, gyroSamples) = synchronized(calibrationLock) {
                val samples = calibrationAccelSamples.orEmpty().toList() to calibrationGyroSamples.orEmpty().toList()
                calibrationAccelSamples = null
                calibrationGyroSamples = null
                samples
            }
            val accelMean = average(accelSamples)
            val gyroMean = average(gyroSamples)
            val accelMotion = accelSamples.maxOfOrNull(::vectorMagnitude) ?: 0.0
            val gyroMotion = gyroSamples.maxOfOrNull(::vectorMagnitude) ?: 0.0
            if ((linearAccelerationSensor != null && accelSamples.size < 10) ||
                (gyroscopeSensor != null && gyroSamples.size < 10) ||
                accelMotion > 1.5 || gyroMotion > 0.25
            ) {
                _imuCalibration.value = PhoneImuCalibrationState(
                    message = "No se pudo calibrar: faltan lecturas o el teléfono se movió. Inmovilízalo y vuelve a intentar.",
                )
            } else {
                accelMean?.copyInto(phoneAccelOffset)
                gyroMean?.copyInto(phoneGyroOffset)
                _imuCalibration.value = PhoneImuCalibrationState(
                    calibrated = true,
                    message = "Sesgo del IMU del teléfono calibrado. Los ejes aún dependen de la orientación del soporte.",
                )
            }
        }
    }

    private fun average(samples: List<FloatArray>): FloatArray? {
        if (samples.isEmpty()) return null
        return FloatArray(3) { axis -> samples.sumOf { it[axis].toDouble() }.div(samples.size).toFloat() }
    }

    private fun vectorMagnitude(values: FloatArray): Double {
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        return kotlin.math.sqrt(x * x + y * y + z * z)
    }

    fun stopTrip() {
        tripRecorder.stop()
        _trip.value = tripRecorder.state
        refreshTripFiles()
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
        garage.upsert(p)
        if (activate || settings.value.activeProfileId == p.id) applyProfile(p)
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
            )
        }
    }

    /** Guarda en el perfil el protocolo con el que respondió (y el VIN si no lo tenía). */
    private fun learnFromConnection(p: VehicleProfile, info: VehicleInfo) {
        val proto = info.protocolNumber.takeIf { it.isNotBlank() && it != "0" }
        val updated = p.copy(
            learnedProtocol = proto ?: p.learnedProtocol,
            vin = p.vin ?: info.vin,
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
                cycleLock.withLock { e.rawMode01(pid) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                null
            }
        }
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
        closeLink()
        scanner.release()
        super.onCleared()
    }

    companion object {
        val DEMO_DEVICE = ObdDevice("Simulador ELM327", "DEMO", LinkType.DEMO)

        private const val MAX_HISTORY = 4_000
        private const val MAX_HISTORY_MS = 10 * 60 * 1000L
        private const val MAX_TERMINAL = 400
        private const val VOLUMETRIC_EFFICIENCY = 0.85

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
