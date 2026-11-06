package com.nguevara.obdpulse

import android.app.Application
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
import com.nguevara.obdpulse.data.SettingsStore
import com.nguevara.obdpulse.data.TripRecorder
import com.nguevara.obdpulse.data.TripState
import com.nguevara.obdpulse.obd.DtcInfo
import com.nguevara.obdpulse.obd.DtcItem
import com.nguevara.obdpulse.obd.DtcKind
import com.nguevara.obdpulse.obd.Elm327
import com.nguevara.obdpulse.obd.FreezeFrame
import com.nguevara.obdpulse.obd.Metric
import com.nguevara.obdpulse.obd.Metrics
import com.nguevara.obdpulse.obd.TermLine
import com.nguevara.obdpulse.obd.VehicleInfo
import com.nguevara.obdpulse.obd.VehicleStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val device: ObdDevice, val step: String) : ConnState
    data class Connected(val device: ObdDevice, val info: VehicleInfo) : ConnState
    data class Failed(val message: String, val device: ObdDevice?) : ConnState
}

data class Sample(val t: Long, val v: Double)

data class DiagState(
    val loading: Boolean = false,
    val step: String = "",
    val status: VehicleStatus? = null,
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
                val info = e.initialize(settings.value.protocol) { step ->
                    _conn.value = ConnState.Connecting(device, step)
                }
                clearLive()
                _diag.value = DiagState()
                _conn.value = ConnState.Connected(device, info)
                if (device.type != LinkType.DEMO) settingsStore.update { it.copy(lastDevice = device) }
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
        return when (key) {
            Metrics.VOLTAGE -> true
            Metrics.FUEL_RATE -> canComputeFuelRate(s)
            Metrics.ECONOMY -> canComputeFuelRate(s) && 0x0D in s
            else -> Metrics[key]?.pid?.let { it in s } ?: false
        }
    }

    fun availableMetrics(): List<Metric> = Metrics.all.filter { isAvailable(it.key) }

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
        if (recording) out += TRIP_KEYS
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
            m.key == Metrics.VOLTAGE || (m.pid != null && m.pid in s)
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
    }

    private fun store(key: String, v: Double) {
        _values.update { it + (key to v) }
        val now = System.currentTimeMillis()
        synchronized(history) {
            val q = history.getOrPut(key) { ArrayDeque() }
            q.addLast(Sample(now, v))
            while (q.size > MAX_HISTORY || (q.isNotEmpty() && now - q.first().t > MAX_HISTORY_MS)) q.removeFirst()
        }
    }

    fun history(key: String): List<Sample> = synchronized(history) { history[key]?.toList() ?: emptyList() }

    private fun clearLive() {
        _values.value = emptyMap()
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
        if (connectedInfo == null) return
        val columns = TRIP_KEYS.mapNotNull { Metrics[it] }.filter { isAvailable(it.key) }
        tripRecorder.start(columns)
        _trip.value = tripRecorder.state
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

        private const val MAX_HISTORY = 1_500
        private const val MAX_HISTORY_MS = 10 * 60 * 1000L
        private const val MAX_TERMINAL = 400
        private const val VOLUMETRIC_EFFICIENCY = 0.85

        val TRIP_KEYS = listOf(
            Metrics.SPEED, Metrics.RPM, Metrics.COOLANT, Metrics.LOAD, Metrics.THROTTLE,
            Metrics.MAF, Metrics.MAP, Metrics.IAT, Metrics.FUEL_LEVEL,
            Metrics.FUEL_RATE, Metrics.ECONOMY, Metrics.VOLTAGE,
        )

        private val FREEZE_KEYS = listOf(
            Metrics.RPM, Metrics.SPEED, Metrics.COOLANT, Metrics.LOAD, Metrics.THROTTLE,
            Metrics.MAP, Metrics.IAT,
        )
    }
}
