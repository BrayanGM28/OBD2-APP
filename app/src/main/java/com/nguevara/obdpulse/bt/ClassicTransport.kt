package com.nguevara.obdpulse.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Enlace por Bluetooth Clásico (perfil SPP / RFCOMM).
 * Es el que usan la mayoría de los ELM327 "Mini" azules.
 */
@SuppressLint("MissingPermission")
class ClassicTransport(
    private val adapter: BluetoothAdapter,
    override val device: ObdDevice,
) : ObdTransport {

    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var open = false

    override val isOpen: Boolean
        get() = open

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val remote: BluetoothDevice = adapter.getRemoteDevice(device.address)

            // El descubrimiento activo hace muy lenta (o imposible) la conexión RFCOMM.
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
            }

            // Tres estrategias, en orden. Los clones baratos a veces solo aceptan la tercera.
            val strategies: List<() -> BluetoothSocket> = listOf(
                { remote.createRfcommSocketToServiceRecord(SPP_UUID) },
                { remote.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                {
                    // Truco conocido para clones ELM327: abrir directamente el canal RFCOMM 1.
                    val method = remote.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(remote, 1) as BluetoothSocket
                },
            )

            var lastError: Exception? = null
            for (make in strategies) {
                val candidate: BluetoothSocket = try {
                    make()
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
                try {
                    candidate.connect()
                    socket = candidate
                    break
                } catch (e: Exception) {
                    lastError = e
                    try {
                        candidate.close()
                    } catch (_: Exception) {
                    }
                }
            }

            val connected = socket ?: throw TransportException(
                "No se pudo conectar por Bluetooth Clásico. ¿Está el adaptador conectado al carro y emparejado? " +
                    "(${lastError?.message ?: "error desconocido"})",
                lastError,
            )
            open = true
            startReader(connected)
        }
    }

    /** Hilo dedicado que lee el InputStream y entrega cada bloque de bytes al canal. */
    private fun startReader(s: BluetoothSocket) {
        thread(name = "obd-spp-reader", isDaemon = true) {
            val buffer = ByteArray(1024)
            var cause: Exception? = null
            try {
                val input = s.inputStream
                while (open) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) incoming.trySend(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                cause = e
            } finally {
                if (open) {
                    open = false
                    incoming.close(TransportException("Se perdió la conexión Bluetooth con el adaptador", cause))
                }
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val s = socket ?: throw TransportException("Sin conexión con el adaptador")
            try {
                s.outputStream.write(data)
                s.outputStream.flush()
            } catch (e: IOException) {
                throw TransportException("Error al enviar datos: ${e.message}", e)
            }
        }
    }

    override fun close() {
        open = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        incoming.close()
    }

    companion object {
        /** UUID estándar del Serial Port Profile. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
