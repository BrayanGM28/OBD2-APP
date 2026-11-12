package com.nguevara.obdpulse.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nguevara.obdpulse.ConnState
import com.nguevara.obdpulse.MainViewModel
import com.nguevara.obdpulse.data.Units
import com.nguevara.obdpulse.obd.DtcItem
import com.nguevara.obdpulse.obd.DtcKind
import com.nguevara.obdpulse.obd.Metrics
import com.nguevara.obdpulse.obd.MonitorStatus
import com.nguevara.obdpulse.obd.Severity
import com.nguevara.obdpulse.ui.components.EmptyState
import com.nguevara.obdpulse.ui.components.FuelTrimLambdaGuideDialog
import com.nguevara.obdpulse.ui.components.InfoRow
import com.nguevara.obdpulse.ui.components.Pill
import com.nguevara.obdpulse.ui.components.SectionCard
import com.nguevara.obdpulse.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(vm: MainViewModel, conn: ConnState, onGoConnect: () -> Unit) {
    val connected = conn as? ConnState.Connected
    if (connected == null || !connected.info.ecuOk) {
        EmptyState(
            icon = Icons.Rounded.Build,
            title = "Diagnóstico del motor",
            message = "Lee y borra códigos de falla (Check Engine), revisa los monitores de emisiones y el cuadro congelado.",
            primaryLabel = "Ir a conexión",
            onPrimary = onGoConnect,
            secondaryLabel = "Probar modo demo",
            onSecondary = { vm.connectDemo() },
        )
        return
    }

    val diag by vm.diag.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var showFuelGuide by remember { mutableStateOf(false) }
    var showEngineInfo by remember { mutableStateOf(false) }
    var selectedMonitor by remember { mutableStateOf<MonitorStatus?>(null) }
    var selectedDtc by remember { mutableStateOf<DtcItem?>(null) }
    val moduleScan by vm.modules.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.watch(emptySet()) // libera el bus para los comandos de diagnóstico
        if (diag.readAt == null && !diag.loading) vm.readDiagnostics()
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Check Engine ----
        item {
            val status = diag.status
            val milOn = status?.milOn == true
            val color = when {
                status == null -> Palette.TextSecondary
                milOn -> Palette.Amber
                else -> Palette.Green
            }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (milOn) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                status == null -> "Estado desconocido"
                                milOn -> "Check Engine encendido"
                                else -> "Check Engine apagado"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.TextPrimary,
                        )
                        val sub = buildString {
                            if (status != null) append("${status.dtcCount} código(s) almacenado(s)")
                            diag.readAt?.let {
                                if (isNotEmpty()) append(" · ")
                                append("leído a las ")
                                append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)))
                            }
                        }
                        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = Palette.TextSecondary)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { vm.readDiagnostics() },
                    enabled = !diag.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (diag.loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Background)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (diag.loading) diag.step.ifEmpty { "Leyendo…" } else "Leer diagnóstico")
                }
                TextButton(onClick = { showEngineInfo = true }) {
                    Text("¿Qué revisa este diagnóstico?")
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Palette.Cyan.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Palette.Cyan.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showFuelGuide = true },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, contentDescription = null, tint = Palette.Cyan)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Diagnóstico de mezcla: STFT, LTFT y Lambda",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Palette.TextPrimary,
                        )
                        Text(
                            "Aprende a interpretar ajustes de combustible pobre/rico y sondas lambda para códigos P0171, P0172, P0420.",
                            fontSize = 11.sp,
                            color = Palette.TextSecondary,
                        )
                    }
                    TextButton(onClick = { showFuelGuide = true }) {
                        Text("Ver guía", color = Palette.Cyan)
                    }
                }
            }
        }

        diag.message?.let { msg ->
            item { Notice(msg, if (diag.dtcs.isEmpty() && !msg.startsWith("Error")) Palette.Green else Palette.Amber) }
        }

        // ---- Códigos ----
        if (diag.dtcs.isNotEmpty()) {
            item {
                Text(
                    "CÓDIGOS DE FALLA (${diag.dtcs.size})",
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            items(diag.dtcs, key = { "${it.kind}-${it.code}" }) {
                DtcCard(it, onClick = { selectedDtc = it })
            }
            item {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !diag.loading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Red),
                    border = BorderStroke(1.dp, Palette.Red.copy(alpha = 0.5f)),
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Borrar códigos y apagar Check Engine")
                }
            }
        }

        // ---- Cuadro congelado ----
        diag.freeze?.let { ff ->
            item {
                SectionCard(title = "Cuadro congelado · ${ff.triggerDtc}") {
                    Text(
                        "Valores registrados por la ECU en el instante en que apareció el fallo.",
                        fontSize = 12.sp,
                        color = Palette.TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    ff.values.forEach { (key, v) ->
                        val m = Metrics[key] ?: return@forEach
                        InfoRow(m.name, "${Units.display(m, v, settings)} ${Units.unit(m, settings)}")
                    }
                }
            }
        }

        // ---- Monitores ----
        diag.status?.let { st ->
            item {
                SectionCard(title = "Monitores de emisiones") {
                    Text(
                        if (st.compressionIgnition) "Motor diésel (encendido por compresión)" else "Motor a gasolina (encendido por chispa)",
                        fontSize = 12.sp,
                        color = Palette.TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    st.monitors.forEach { mon ->
                        val (icon, color, label) = when {
                            !mon.available -> Triple(Icons.Rounded.RemoveCircleOutline, Palette.TextMuted, "No aplica")
                            mon.complete -> Triple(Icons.Rounded.CheckCircle, Palette.Green, "Listo")
                            else -> Triple(Icons.Rounded.HourglassEmpty, Palette.Amber, "Incompleto")
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedMonitor = mon }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(mon.name, fontSize = 14.sp, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
                            Text(label, fontSize = 13.sp, color = color)
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Módulos detectados y límites del adaptador") {
                Text(
                    "La búsqueda consulta módulos que responden al protocolo OBD-II estándar. Que un módulo no aparezca no confirma que el vehículo no lo tenga: ABS/ESP, airbags (SRS), dirección asistida (EPS) y transmisión (TCM) suelen requerir consultas y protocolos propios del fabricante.",
                    fontSize = 13.sp,
                    color = Palette.TextSecondary,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                if (moduleScan.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Cyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(moduleScan.step, fontSize = 13.sp, color = Palette.TextSecondary)
                    }
                } else {
                    OutlinedButton(
                        onClick = { vm.scanModules() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (moduleScan.readAt == null) "Buscar módulos que responden" else "Volver a buscar módulos")
                    }
                }
                moduleScan.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = Palette.Amber)
                }
                moduleScan.modules.forEach { module ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${module.name ?: "Módulo ${module.address}"} · dirección ${module.address}",
                        fontSize = 13.sp,
                        color = Palette.TextPrimary,
                    )
                }
                if (moduleScan.modules.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "El escaneo ELM327 puede identificar solo la ECU de motor, según el protocolo y el adaptador. La ausencia de ABS/TCM/SRS requiere un escáner multimarca con cobertura específica para confirmarse.",
                        fontSize = 12.sp,
                        color = Palette.TextMuted,
                        lineHeight = 17.sp,
                    )
                }
            }
        }

        // ---- Vehículo ----
        item {
            SectionCard(title = "Vehículo") {
                InfoRow("VIN", connected.info.vin ?: "No disponible")
                InfoRow("Protocolo", connected.info.protocol)
                InfoRow("Adaptador", connected.info.elmVersion)
                InfoRow("PIDs soportados", connected.info.supportedPids.count { it % 0x20 != 0 }.toString())
                Spacer(Modifier.height(10.dp))
                Text(
                    if (0x5C in connected.info.supportedPids) {
                        "Temperatura de aceite: la ECU anuncia el PID estándar 01 5C. Si no aparece un valor, puede no responder a la consulta o no entregar un dato válido en este vehículo."
                    } else {
                        "Temperatura de aceite: esta ECU no anuncia el PID estándar 01 5C. Aunque el Subaru tenga sensor/indicador de aceite, puede usar datos propietarios que un ELM327 genérico no consulta."
                    },
                    fontSize = 12.sp,
                    color = Palette.TextSecondary,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Freno: el estado del pedal, presión hidráulica y datos ABS/ESP no tienen una lectura universal OBD-II de motor. Se requiere acceso al módulo y parámetros específicos Subaru; este resultado no permite concluir que el sensor o los frenos estén fallando.",
                    fontSize = 12.sp,
                    color = Palette.TextSecondary,
                    lineHeight = 17.sp,
                )
            }
        }
    }

    selectedMonitor?.let { monitor ->
        AlertDialog(
            onDismissRequest = { selectedMonitor = null },
            title = { Text(monitor.name) },
            text = { Text(monitorExplanation(monitor)) },
            confirmButton = {
                TextButton(onClick = { selectedMonitor = null }) { Text("Entendido") }
            },
            containerColor = Palette.SurfaceHigh,
        )
    }

    if (showEngineInfo) {
        AlertDialog(
            onDismissRequest = { showEngineInfo = false },
            title = { Text("Check Engine y monitores") },
            text = {
                Text(
                    "Esta lectura consulta la ECU de motor mediante OBD-II genérico: estado de la luz Check Engine, códigos de falla, cuadro congelado y preparación de pruebas de emisiones. No es un escaneo completo de todos los módulos del vehículo ni verifica por sí sola ABS, airbags, transmisión o frenos.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showEngineInfo = false }) { Text("Entendido") }
            },
            containerColor = Palette.SurfaceHigh,
        )
    }

    selectedDtc?.let { dtc ->
        AlertDialog(
            onDismissRequest = { selectedDtc = null },
            title = { Text("${dtc.code} · ${dtc.kind.label}") },
            text = {
                Text(
                    "${dtc.description}\n\nSistema: ${dtc.system}.\nSeveridad orientativa: ${dtc.severity.label.lowercase()}.\n\nEl código describe una condición detectada por la ECU; no identifica por sí solo la pieza que debe reemplazarse.",
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedDtc = null }) { Text("Entendido") }
            },
            containerColor = Palette.SurfaceHigh,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = Palette.Amber) },
            title = { Text("¿Borrar códigos de falla?") },
            text = {
                Text(
                    "Se apagará el Check Engine y se reiniciarán los monitores de emisiones y el cuadro congelado. " +
                        "Si la falla sigue presente, el código volverá a aparecer.\n\n" +
                        "Hazlo con el encendido en ON y el motor apagado.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearDtcs()
                }) { Text("Borrar", color = Palette.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancelar") }
            },
            containerColor = Palette.SurfaceHigh,
        )
    }

    if (showFuelGuide) {
        FuelTrimLambdaGuideDialog(onDismiss = { showFuelGuide = false })
    }
}

@Composable
private fun DtcCard(item: DtcItem, onClick: () -> Unit) {
    val sevColor: Color = when (item.severity) {
        Severity.HIGH -> Palette.Red
        Severity.MEDIUM -> Palette.Amber
        Severity.LOW -> Palette.Cyan
    }
    val kindColor = when (item.kind) {
        DtcKind.STORED -> Palette.Red
        DtcKind.PENDING -> Palette.Amber
        DtcKind.PERMANENT -> Palette.Violet
    }
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.code,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = sevColor,
                modifier = Modifier.weight(1f),
            )
            Pill(item.kind.label, kindColor)
            Spacer(Modifier.width(6.dp))
            Pill("Severidad ${item.severity.label.lowercase()}", sevColor)
        }

        Spacer(Modifier.height(6.dp))
        Text(item.description, fontSize = 15.sp, color = Palette.TextPrimary, lineHeight = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(item.system, fontSize = 12.sp, color = Palette.TextMuted)
    }
}

private fun monitorExplanation(monitor: MonitorStatus): String {
    val purpose = when (monitor.name) {
        "Fallos de encendido" -> "Comprueba si hay combustiones irregulares que puedan aumentar emisiones o dañar el catalizador."
        "Sistema de combustible" -> "Verifica el control de mezcla y suministro de combustible dentro de las condiciones de autodiagnóstico."
        "Componentes del motor" -> "Agrupa componentes relacionados con emisiones cuyo fallo puede encender Check Engine."
        "Catalizador" -> "Evalúa la eficiencia del catalizador comparando el comportamiento de los sensores de oxígeno."
        "Catalizador calentado" -> "Comprueba el catalizador con calentamiento eléctrico, si el vehículo está equipado con él."
        "Sistema evaporativo (EVAP)" -> "Busca fugas en el sistema que controla vapores de combustible."
        "Aire secundario" -> "Comprueba el sistema que inyecta aire al escape durante determinadas condiciones de arranque."
        "Refrigerante del A/C" -> "Comprueba el refrigerante/emisiones del sistema de aire acondicionado cuando el vehículo implementa este monitor."
        "Sensor de oxígeno" -> "Evalúa la respuesta de las sondas de oxígeno usadas para controlar mezcla y emisiones."
        "Calentador del sensor O2" -> "Comprueba los calentadores de las sondas de oxígeno para que alcancen su temperatura de trabajo."
        "EGR / VVT" -> "Evalúa recirculación de gases (EGR) o sincronización variable de válvulas (VVT), según el motor."
        "Catalizador NMHC" -> "Evalúa el catalizador de hidrocarburos no metánicos en motores diésel compatibles."
        "Catalizador NOx / SCR" -> "Comprueba el control de óxidos de nitrógeno, incluido SCR cuando está instalado."
        "Presión de sobrealimentación" -> "Comprueba el control de presión del turbo o sobrealimentación."
        "Sensor de gases de escape" -> "Evalúa los sensores de gases de escape que intervienen en el control de emisiones diésel."
        "Filtro de partículas (DPF)" -> "Comprueba el filtro de partículas diésel y su sistema de regeneración."
        else -> "Es una prueba de autodiagnóstico de emisiones reportada por la ECU."
    }
    val status = when {
        !monitor.available ->
            "No aplica: la ECU indica que esta prueba no está soportada o no corresponde a la configuración del motor. No significa que la prueba haya fallado."
        !monitor.complete ->
            "Incompleto: la prueba sí aplica, pero la ECU aún no la ha ejecutado desde el último borrado de códigos/reinicio, o todavía no se cumplieron sus condiciones de conducción y temperatura."
        else ->
            "Listo: la ECU completó esta prueba y no reporta una falla mediante este monitor. No sustituye una inspección ni descarta fallas fuera del sistema de emisiones."
    }
    return "$purpose\n\n$status"
}
