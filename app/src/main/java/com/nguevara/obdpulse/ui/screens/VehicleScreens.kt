package com.nguevara.obdpulse.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nguevara.obdpulse.MainViewModel
import com.nguevara.obdpulse.obd.Elm327
import com.nguevara.obdpulse.ui.components.Pill
import com.nguevara.obdpulse.ui.components.SectionCard
import com.nguevara.obdpulse.ui.theme.NumberStyle
import com.nguevara.obdpulse.ui.theme.Palette
import com.nguevara.obdpulse.vehicle.Confidence
import com.nguevara.obdpulse.vehicle.Drive
import com.nguevara.obdpulse.vehicle.Fuel
import com.nguevara.obdpulse.vehicle.Market
import com.nguevara.obdpulse.vehicle.OnlineService
import com.nguevara.obdpulse.vehicle.ProtocolRecommendation
import com.nguevara.obdpulse.vehicle.Transmission
import com.nguevara.obdpulse.vehicle.VehicleProfile
import com.nguevara.obdpulse.vehicle.VinInfo
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Protocolos del comando ATSPx con una descripción para el usuario. */
val PROTOCOL_OPTIONS = listOf(
    "0" to "Automático",
    "1" to "SAE J1850 PWM (Ford antiguos de EE. UU.)",
    "2" to "SAE J1850 VPW (GM antiguos de EE. UU.)",
    "3" to "ISO 9141-2 (K-Line)",
    "4" to "ISO 14230-4 KWP, inicio lento (K-Line)",
    "5" to "ISO 14230-4 KWP, inicio rápido (K-Line)",
    "6" to "ISO 15765-4 CAN 11 bit, 500 kbps",
    "7" to "ISO 15765-4 CAN 29 bit, 500 kbps",
    "8" to "ISO 15765-4 CAN 11 bit, 250 kbps",
    "9" to "ISO 15765-4 CAN 29 bit, 250 kbps",
    "A" to "SAE J1939 CAN (camiones)",
)

/** Nombre corto de un protocolo para chips y listas. */
fun shortProtocol(code: String): String = when (code.uppercase(Locale.US)) {
    "0" -> "Auto"
    "1" -> "J1850 PWM"
    "2" -> "J1850 VPW"
    "3" -> "ISO 9141"
    "4" -> "KWP lento"
    "5" -> "KWP rápido"
    "6" -> "CAN 11/500"
    "7" -> "CAN 29/500"
    "8" -> "CAN 11/250"
    "9" -> "CAN 29/250"
    "A" -> "J1939"
    else -> code
}

private fun confidenceColor(c: Confidence): Color = when (c) {
    Confidence.HIGH -> Palette.Green
    Confidence.MEDIUM -> Palette.Amber
    Confidence.LOW -> Palette.Red
}

// ======================================================================
// Tarjeta del vehículo activo (pantalla de conexión)
// ======================================================================

@Composable
fun ActiveVehicleCard(vm: MainViewModel, profile: VehicleProfile?, onOpenGarage: () -> Unit) {
    val db by vm.vehicleDb.collectAsStateWithLifecycle()
    SectionCard(
        title = "Mi vehículo",
        trailing = {
            TextButton(onClick = onOpenGarage) { Text(if (profile == null) "Elegir" else "Cambiar") }
        },
    ) {
        if (profile == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarBadge(Palette.TextSecondary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Elige marca, modelo, año y motor para que la app pruebe primero el protocolo correcto de tu carro.",
                    fontSize = 13.sp,
                    color = Palette.TextSecondary,
                    lineHeight = 18.sp,
                )
            }
            return@SectionCard
        }
        val rec = remember(profile, db) { vm.recommendation(profile) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CarBadge(Palette.Cyan)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${profile.title} ${profile.year}", fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary, maxLines = 1)
                Text(profile.subtitle, fontSize = 12.sp, color = Palette.TextMuted, maxLines = 2)
            }
        }
        Spacer(Modifier.height(10.dp))
        ProtocolOrder(rec)
    }
}

@Composable
private fun CarBadge(color: Color) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = color)
    }
}

/** "Orden de prueba: 1 KWP lento → 2 KWP rápido → …" con la confianza de la recomendación. */
@Composable
fun ProtocolOrder(rec: ProtocolRecommendation) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Orden de prueba", fontSize = 12.sp, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
        Pill("Confianza ${rec.confidence.label.lowercase(Locale.getDefault())}", confidenceColor(rec.confidence))
    }
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        rec.candidates.forEachIndexed { i, code ->
            Pill("${i + 1}. ${shortProtocol(code)}", if (i == 0) Palette.Cyan else Palette.TextSecondary)
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(rec.source, fontSize = 11.sp, color = Palette.TextMuted)
}

// ======================================================================
// Garaje
// ======================================================================

/** Ventana completa con el garaje. */
@Composable
fun GarageDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<Pair<VehicleProfile, Boolean>?>(null) }
    FullScreenDialog(title = "Mis vehículos", onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            GarageContent(vm, onEdit = { p, isNew -> editing = p to isNew }, onSelected = onDismiss)
        }
    }
    editing?.let { (p, isNew) ->
        VehicleEditorDialog(vm, p, isNew, onDismiss = { editing = null })
    }
}

/** Lista de vehículos guardados, plantillas y botón para agregar. */
@Composable
fun GarageContent(vm: MainViewModel, onEdit: (VehicleProfile, Boolean) -> Unit, onSelected: () -> Unit = {}) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val db by vm.vehicleDb.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<VehicleProfile?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (profiles.isEmpty()) {
            Text(
                "Aún no tienes vehículos guardados. Agrega uno o parte de un ejemplo.",
                fontSize = 14.sp,
                color = Palette.TextSecondary,
            )
        }
        profiles.forEach { p ->
            val active = p.id == settings.activeProfileId
            val rec = remember(p, db) { vm.recommendation(p) }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Palette.Surface,
                border = BorderStroke(1.dp, if (active) Palette.Cyan.copy(alpha = 0.6f) else Palette.Outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        vm.selectProfile(p.id)
                        onSelected()
                    },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (active) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (active) "En uso" else "Usar",
                        tint = if (active) Palette.Cyan else Palette.TextMuted,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${p.title} ${p.year}", fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary, maxLines = 1)
                        Text(p.subtitle, fontSize = 12.sp, color = Palette.TextMuted, maxLines = 2)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(shortProtocol(rec.primary), Palette.Cyan)
                            if (p.learnedProtocol != null) Pill("Aprendido", Palette.Green)
                        }
                    }
                    IconButton(onClick = { onEdit(p, false) }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Editar", tint = Palette.TextSecondary)
                    }
                    IconButton(onClick = { toDelete = p }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Eliminar", tint = Palette.TextSecondary)
                    }
                }
            }
        }

        Button(
            onClick = { onEdit(VehicleProfile(year = Calendar.getInstance().get(Calendar.YEAR) - 5), true) },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Agregar vehículo")
        }

        if (db.templates.isNotEmpty()) {
            Text("EJEMPLOS RÁPIDOS", fontSize = 12.sp, color = Palette.TextSecondary, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 8.dp))
            db.templates.forEach { t ->
                OutlinedButton(
                    onClick = { onEdit(t.copy(id = UUID.randomUUID().toString()), true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${t.make} ${t.model} ${t.version} ${t.year}".replace("  ", " "), maxLines = 1)
                }
            }
        }

        if (settings.activeProfileId != null) {
            TextButton(onClick = {
                vm.selectProfile(null)
                onSelected()
            }) { Text("No usar ningún vehículo (protocolo de Ajustes)") }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Eliminar vehículo?") },
            text = { Text("Se borrará ${p.title} ${p.year} y el protocolo que la app aprendió para él.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProfile(p.id)
                    toDelete = null
                }) { Text("Eliminar", color = Palette.Red) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

// ======================================================================
// Editor de vehículo
// ======================================================================

@Composable
fun VehicleEditorDialog(vm: MainViewModel, initial: VehicleProfile, isNew: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db by vm.vehicleDb.collectAsStateWithLifecycle()
    var p by remember { mutableStateOf(initial) }
    var vinText by remember { mutableStateOf(initial.vin.orEmpty()) }
    var vinBusy by remember { mutableStateOf(false) }
    var vinResult by remember { mutableStateOf<String?>(null) }
    var showProtocols by remember { mutableStateOf(false) }
    val rec = remember(p, db) { vm.recommendation(p) }
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

    fun save() {
        if (p.make.isBlank()) {
            vm.notify("Escribe al menos la marca del vehículo.")
            return
        }
        vm.saveProfile(
            p.copy(
                make = p.make.trim(),
                model = p.model.trim(),
                version = p.version.trim(),
                engine = p.engine.trim(),
                vin = vinText.trim().uppercase(Locale.US).ifBlank { null },
            ),
            activate = true,
        )
        onDismiss()
    }

    fun decodeVin() {
        scope.launch {
            vinBusy = true
            vinResult = null
            val r = vm.lookupVin(vinText)
            vinBusy = false
            r.onSuccess { info ->
                p = merge(p, info)
                vinResult = buildString {
                    append("Datos tomados de: ${info.source}.")
                    listOfNotNull(info.make, info.model, info.year?.toString(), info.country).takeIf { it.isNotEmpty() }?.let {
                        append(" ").append(it.joinToString(" · "))
                    }
                    info.warning?.let { append("\n").append(it) }
                }
            }.onFailure { vinResult = it.message ?: "No se pudo decodificar el VIN." }
        }
    }

    FullScreenDialog(
        title = if (isNew) "Nuevo vehículo" else "Editar vehículo",
        onDismiss = onDismiss,
        action = { TextButton(onClick = { save() }) { Text("Guardar", fontWeight = FontWeight.SemiBold) } },
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- VIN ----
            item {
                SectionCard(title = "Identificar por VIN (opcional)") {
                    Text(
                        "El VIN (17 caracteres) está en la tarjeta de propiedad, el parabrisas o el marco de la puerta. " +
                            "Con internet se consulta la base pública de NHTSA; sin internet se deduce marca, país y año.",
                        fontSize = 12.sp,
                        color = Palette.TextSecondary,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vinText,
                        onValueChange = { vinText = it.uppercase(Locale.US).filter { c -> c.isLetterOrDigit() }.take(17) },
                        label = { Text("VIN") },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        supportingText = { Text("${vinText.length}/17") },
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { decodeVin() }, enabled = vinText.length == 17 && !vinBusy) {
                            if (vinBusy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Background)
                            } else {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Decodificar")
                        }
                        val carVin = vm.connectedVin()
                        if (carVin != null && carVin != vinText) {
                            TextButton(onClick = { vinText = carVin }) { Text("Usar VIN del carro") }
                        }
                    }
                    vinResult?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 12.sp, color = Palette.TextSecondary, lineHeight = 16.sp)
                    }
                }
            }

            // ---- Datos principales ----
            item {
                SectionCard(title = "Vehículo") {
                    SuggestField("Marca", p.make, db.makeNames()) { p = p.copy(make = it) }
                    SuggestField("Modelo", p.model, db.modelsFor(p.make)) { p = p.copy(model = it) }
                    SuggestField("Versión / línea (ej. 2.0i, 16V, NP300 4x4)", p.version, emptyList()) { p = p.copy(version = it) }
                    SuggestField("Motor (ej. FB20, K4M, YD25)", p.engine, db.enginesFor(p.make)) { p = p.copy(engine = it) }
                    SuggestField("Apodo (opcional)", p.nickname, emptyList()) { p = p.copy(nickname = it) }

                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Año modelo", fontSize = 14.sp, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { p = p.copy(year = (p.year - 1).coerceAtLeast(1980)) }) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Restar un año", tint = Palette.Cyan)
                        }
                        Text(p.year.toString(), style = NumberStyle, fontSize = 22.sp, color = Palette.TextPrimary)
                        IconButton(onClick = { p = p.copy(year = (p.year + 1).coerceAtMost(currentYear + 1)) }) {
                            Icon(Icons.Rounded.Add, contentDescription = "Sumar un año", tint = Palette.Cyan)
                        }
                    }
                    Slider(
                        value = p.year.toFloat(),
                        onValueChange = { p = p.copy(year = it.roundToInt()) },
                        valueRange = 1980f..(currentYear + 1).toFloat(),
                        colors = sliderColors(),
                    )
                }
            }

            // ---- Motor y transmisión ----
            item {
                SectionCard(title = "Motor y transmisión") {
                    FieldLabel("Combustible")
                    ChoiceChips(Fuel.entries, p.fuel, { it.label }) { p = p.copy(fuel = it) }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FieldLabel("Cilindrada", Modifier.weight(1f))
                        Text(String.format(Locale.getDefault(), "%.1f L", p.displacementL), style = NumberStyle, color = Palette.Cyan)
                    }
                    Slider(
                        value = p.displacementL.toFloat(),
                        onValueChange = { p = p.copy(displacementL = (it * 10).roundToInt() / 10.0) },
                        valueRange = 0.6f..7.0f,
                        steps = 63,
                        colors = sliderColors(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FieldLabel("Zona roja del tacómetro", Modifier.weight(1f))
                        Text("${p.redlineRpm} rpm", style = NumberStyle, color = Palette.Cyan)
                    }
                    Slider(
                        value = p.redlineRpm.toFloat(),
                        onValueChange = { p = p.copy(redlineRpm = (it / 250).roundToInt() * 250) },
                        valueRange = 3000f..9000f,
                        steps = 23,
                        colors = sliderColors(),
                    )
                    FieldLabel("Tracción")
                    ChoiceChips(Drive.entries, p.drive, { it.label }) { p = p.copy(drive = it) }
                    Spacer(Modifier.height(8.dp))
                    FieldLabel("Transmisión")
                    ChoiceChips(Transmission.entries, p.transmission, { it.label }) { p = p.copy(transmission = it) }
                    Spacer(Modifier.height(8.dp))
                    FieldLabel("Mercado de origen")
                    ChoiceChips(Market.entries, p.market, { it.label }) { p = p.copy(market = it) }
                    Text(
                        "Un mismo modelo puede usar otro protocolo según el país para el que se fabricó.",
                        fontSize = 11.sp,
                        color = Palette.TextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ---- Protocolo recomendado ----
            item {
                SectionCard(title = "Protocolo OBD") {
                    ProtocolOrder(rec)
                    Spacer(Modifier.height(8.dp))
                    Text(rec.reason, fontSize = 13.sp, color = Palette.TextPrimary, lineHeight = 18.sp)
                    rec.note?.let {
                        Spacer(Modifier.height(8.dp))
                        Notice(it, Palette.Amber)
                    }
                    if (rec.slowEcu) {
                        Spacer(Modifier.height(8.dp))
                        Notice("ECU lenta: la app esperará hasta 1 s por cada respuesta en la primera conexión.", Palette.Cyan)
                    }
                    p.learnedProtocol?.let { learned ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Aprendido en este carro", fontSize = 13.sp, color = Palette.Green)
                                Text(Elm327.protocolName(learned), fontSize = 12.sp, color = Palette.TextSecondary)
                            }
                            TextButton(onClick = { p = p.copy(learnedProtocol = null) }) { Text("Olvidar") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showProtocols = true }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Elegir protocolo a mano", fontSize = 14.sp, color = Palette.TextPrimary)
                            Text(
                                p.protocolOverride?.let { Elm327.protocolName(it) } ?: "No: usar el orden recomendado",
                                fontSize = 12.sp,
                                color = Palette.TextSecondary,
                            )
                        }
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = Palette.TextSecondary)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OnlineService.protocolSearchUrl(p))))
                            } catch (_: Exception) {
                                vm.notify("No hay un navegador disponible.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Buscar el protocolo en internet")
                    }
                    p.learnedProtocol?.let { learned ->
                        OutlinedButton(
                            onClick = {
                                val text = OnlineService.contributionText(p, learned, Elm327.protocolName(learned))
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Protocolo OBD · ${p.title} ${p.year}")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                try {
                                    context.startActivity(Intent.createChooser(send, "Compartir resultado"))
                                } catch (_: Exception) {
                                    vm.notify("No se pudo compartir.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Compartir resultado para la base")
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { save() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .navigationBarsPadding(),
                ) { Text("Guardar y usar este vehículo") }
            }
        }
    }

    if (showProtocols) {
        AlertDialog(
            onDismissRequest = { showProtocols = false },
            title = { Text("Protocolo a mano") },
            text = {
                LazyColumn(Modifier.height(380.dp)) {
                    item {
                        ProtocolRow("Usar el orden recomendado", p.protocolOverride == null) {
                            p = p.copy(protocolOverride = null)
                            showProtocols = false
                        }
                    }
                    items(PROTOCOL_OPTIONS.drop(1), key = { it.first }) { (code, label) ->
                        ProtocolRow(label, p.protocolOverride == code) {
                            p = p.copy(protocolOverride = code)
                            showProtocols = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProtocols = false }) { Text("Cerrar") } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

/** Completa el perfil con lo que se obtuvo del VIN (sin borrar lo que el usuario ya escribió). */
private fun merge(p: VehicleProfile, info: VinInfo): VehicleProfile = p.copy(
    make = info.make ?: p.make,
    model = info.model ?: p.model,
    year = info.year ?: p.year,
    engine = if (p.engine.isBlank()) info.engine ?: p.engine else p.engine,
    displacementL = info.displacementL ?: p.displacementL,
    fuel = info.fuel ?: p.fuel,
    drive = info.drive ?: p.drive,
    market = if (info.country?.let { it == "EE. UU." || it.contains("UNITED STATES", ignoreCase = true) } == true) Market.US else p.market,
    vin = info.vin,
)

@Composable
private fun ProtocolRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Palette.Cyan))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, color = Palette.TextPrimary)
    }
}

// ======================================================================
// Piezas del formulario
// ======================================================================

/** Campo de texto con sugerencias tocables debajo (marcas, modelos, motores de la base). */
@Composable
private fun SuggestField(label: String, value: String, suggestions: List<String>, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        colors = fieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    )
    val matches = remember(value, suggestions) {
        if (suggestions.any { it.equals(value.trim(), ignoreCase = true) }) {
            emptyList()
        } else {
            suggestions.filter { value.isBlank() || it.contains(value.trim(), ignoreCase = true) }.take(12)
        }
    }
    if (matches.isNotEmpty()) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            matches.forEach { s ->
                SuggestionChip(
                    onClick = { onChange(s) },
                    label = { Text(s) },
                    colors = SuggestionChipDefaults.suggestionChipColors(labelColor = Palette.TextSecondary),
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.Cyan.copy(alpha = 0.18f),
                    selectedLabelColor = Palette.Cyan,
                    labelColor = Palette.TextSecondary,
                ),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 14.sp, color = Palette.TextPrimary, modifier = modifier.padding(bottom = 4.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Cyan,
    unfocusedBorderColor = Palette.Outline,
    focusedLabelColor = Palette.Cyan,
)

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Palette.Cyan,
    activeTrackColor = Palette.Cyan,
    inactiveTrackColor = Palette.Track,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

/** Ventana a pantalla completa con barra superior (cerrar, título, acción). */
@Composable
fun FullScreenDialog(
    title: String,
    onDismiss: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Palette.Background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver", tint = Palette.TextPrimary)
                    }
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
                    action?.invoke()
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
