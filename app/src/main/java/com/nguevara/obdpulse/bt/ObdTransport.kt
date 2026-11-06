package com.nguevara.obdpulse.bt

import kotlinx.coroutines.channels.Channel

/** Tipo de enlace físico con el adaptador. */
enum class LinkType(val label: String) {
    CLASSIC("Clásico"),
    BLE("BLE"),
    DEMO("Demo"),
}

/** Adaptador encontrado en el escaneo (o guardado como último usado). */
data class ObdDevice(
    val name: String,
    val address: String,
    val type: LinkType,
    val bonded: Boolean = false,
    val rssi: Int? = null,
) {
    /** Heurística: nombres típicos de adaptadores OBD-II. Se usan para ordenarlos primero. */
    val looksLikeObd: Boolean
        get() {
            val n = name.uppercase()
            return OBD_HINTS.any { n.contains(it) }
        }

    companion object {
        private val OBD_HINTS = listOf(
            "OBD", "ELM", "VLINK", "V-LINK", "VEEPEAK", "VGATE", "KONNWEI",
            "ICAR", "CARISTA", "OBDLINK", "SCAN", "KW90", "KW902", "BLE-OBD",
        )
    }
}

/**
 * Canal de bytes crudos con el adaptador ELM327.
 *
 * Cada implementación (Bluetooth Clásico, BLE o simulador) solo se encarga de mover bytes:
 *  - write():   envía bytes al adaptador (ej. "010C\r").
 *  - incoming:  entrega los bytes que responde el adaptador, en el orden en que llegan,
 *               sin interpretar nada. El protocolo ELM327 vive en [com.nguevara.obdpulse.obd.Elm327].
 *
 * Cuando el enlace se cae, [incoming] se cierra (opcionalmente con la causa).
 */
interface ObdTransport {
    val device: ObdDevice
    val incoming: Channel<ByteArray>
    val isOpen: Boolean

    suspend fun open()
    suspend fun write(data: ByteArray)
    fun close()
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
