package com.obd.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class ObdData(
    val coolant: Int? = null,
    val intake: Int? = null,
    val rpm: Int? = null,
    val status: String = "Déconnecté"
)

/** Client ELM327 Bluetooth classique (SPP). */
class ObdClient(private val onData: (ObdData) -> Unit) {
    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private var data = ObdData()

    private fun push(d: ObdData) { data = d; onData(d) }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, device: BluetoothDevice) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    push(data.copy(status = "Connexion…"))
                    val s = device.createRfcommSocketToServiceRecord(
                        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                    )
                    s.connect()
                    socket = s
                    val inp = s.inputStream
                    val out = s.outputStream
                    for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")) send(out, inp, c)
                    send(out, inp, "0100", 12000) // détection du protocole
                    push(data.copy(status = "Connecté"))
                    while (isActive) {
                        val w = query(out, inp, "0105")?.firstOrNull()?.let { it - 40 }
                        val a = query(out, inp, "010F")?.firstOrNull()?.let { it - 40 }
                        val r = query(out, inp, "010C")?.takeIf { it.size >= 2 }
                            ?.let { (it[0] * 256 + it[1]) / 4 }
                        push(data.copy(coolant = w, intake = a, rpm = r, status = "Connecté"))
                        delay(150)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    push(data.copy(status = "Reconnexion…"))
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
                delay(3000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        try { socket?.close() } catch (_: Exception) {}
    }

    private suspend fun send(out: OutputStream, inp: InputStream, cmd: String, timeout: Long = 4000): String {
        out.write((cmd + "\r").toByteArray()); out.flush()
        val sb = StringBuilder()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeout) {
            if (inp.available() > 0) {
                val ch = inp.read().toChar()
                if (ch == '>') break
                sb.append(ch)
            } else delay(10)
        }
        return sb.toString()
    }

    /** Renvoie les octets de données de la réponse (ex. "41057B" -> [0x7B]). */
    private suspend fun query(out: OutputStream, inp: InputStream, pid: String): List<Int>? {
        val r = send(out, inp, pid).filter { !it.isWhitespace() }.uppercase()
        val i = r.indexOf("41" + pid.substring(2))
        if (i < 0) return null
        return r.substring(i + 4).chunked(2).filter { it.length == 2 }
            .mapNotNull { it.toIntOrNull(16) }.takeIf { it.isNotEmpty() }
    }
}
