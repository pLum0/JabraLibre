package com.plum0.jabralibre

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AncMode(val wire: Int) { OFF(0x01), HEARTHROUGH(0x02), ANC(0x04) }

class JabraRfcomm(
    private val log: (String) -> Unit,
    private val onMode: (AncMode) -> Unit
) {
    companion object {
        // aus dem HCI-Capture: DLCI 0x3a → Server-Kanal 29
        private val TOPIC_MODE = byteArrayOf(0x13, 0xbe.toByte())
        private val CHANNELS = intArrayOf(29, 28, 30)
    }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var reader: Thread? = null
    private val seq = AtomicInteger(0x09)
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var closing = false
    private val connecting = AtomicBoolean(false)

    val isConnected: Boolean get() = socket?.isConnected == true

    // ---------- Socket-Beschaffung ----------

    /**
     * createInsecureRfcommSocket(int)/createRfcommSocket(int) sind versteckte
     * Android-APIs (nicht im SDK) → per Reflection. null, falls nicht aufrufbar.
     */
    private fun openSocket(device: BluetoothDevice, channel: Int, insecure: Boolean = true): BluetoothSocket? =
        runCatching {
            val name = if (insecure) "createInsecureRfcommSocket" else "createRfcommSocket"
            val m = device.javaClass.getMethod(name, Int::class.javaPrimitiveType)
            m.invoke(device, channel) as? BluetoothSocket
        }.getOrNull()

    private fun cachedUuids(device: BluetoothDevice): List<ParcelUuid>? =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            device.javaClass.getMethod("getUuids").invoke(device) as? Array<ParcelUuid>
        }.getOrNull()?.toList()

    /** Öffentlichen Weg: SDP-Service-Record → Android löst den Kanal selbst auf. */
    private fun uuidSocket(device: BluetoothDevice, uuid: UUID): BluetoothSocket? =
        runCatching { device.createRfcommSocketToServiceRecord(uuid) }.getOrNull()

    // ---------- Verbinden ----------

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!connecting.compareAndSet(false, true)) return
        closing = false
        Thread {
            try {
                cachedUuids(device)?.let {
                    log("SDP-Cache: ${if (it.isEmpty()) "(leer)" else it.joinToString { p -> p.uuid.toString() }}")
                }

                // Strategie 1: direkte Kanäle (29 = Capture-Befund), je 2 Versuche
                for (ch in CHANNELS) {
                    for (attempt in 1..2) {
                        val s = openSocket(device, ch)
                            ?: openSocket(device, ch, insecure = false)
                            ?: run { log("Reflection nicht verfügbar"); return@Thread }
                        try {
                            s.connect()
                            socket = s; out = s.outputStream
                            log("verbunden (Kanal $ch)")
                            startReader(s.inputStream); initSession(); readMode()
                            return@Thread
                        } catch (e: Exception) {
                            log("Kanal $ch (Versuch $attempt): ${e.message}")
                            runCatching { s.close() }
                            runCatching { Thread.sleep(1500) }   // Slot kann kurz belegt sein
                        }
                    }
                }

                // Strategie 2: proprietäre SDP-UUIDs → Service-Record-Connect
                val customs = cachedUuids(device).orEmpty().map { it.uuid }
                    .filterNot { it.toString().substring(0, 8).matches(Regex("0000(11|18)[0-9a-f]{2}")) }
                if (customs.isEmpty()) {
                    log("keine proprietären SDP-UUIDs im Cache")
                } else for (u in customs) {
                    val s = uuidSocket(device, u) ?: continue
                    try {
                        s.connect()
                        socket = s; out = s.outputStream
                        log("verbunden (UUID $u)")
                        startReader(s.inputStream); initSession(); readMode()
                        return@Thread
                    } catch (e: Exception) {
                        log("UUID …${u.toString().takeLast(12)}: ${e.message}")
                        runCatching { s.close() }
                    }
                }

                log("VERBINDUNG FEHLGESCHLAGEN.")
                log("→ Jabra Sound+ in App-Info »Beenden erzwingen«, Buds wach, erneut versuchen")
                log("→ dann Diagnose-Scan laufen lassen und Log durchsehen")
            } finally { connecting.set(false) }
        }.apply { name = "jl-connect" }.start()
    }

    /** Debugging: SDP neu abfragen + Kanäle 1–31 durchprobieren. */
    @SuppressLint("MissingPermission")
    fun diagnose(device: BluetoothDevice) {
        Thread {
            runCatching {
                val ok = device.javaClass.getMethod("fetchUuidsWithSdp").invoke(device) as? Boolean ?: false
                if (ok) log("SDP-Neuabfrage gestellt – UUIDs kommen gleich ins Log …")
            }
            log("— Diagnose: scanne Kanäle 1–31 (dauert ~30 s) —")
            var open = 0
            for (ch in 1..31) {
                val s = openSocket(device, ch) ?: continue
                try {
                    s.connect()
                    log("Kanal $ch: OFFEN ✓")
                    open++
                    runCatching { s.close() }
                } catch (_: Exception) { /* zu erwarten */ }
            }
            log("Scan fertig: $open offene Kanäle (siehe ✓-Zeilen).")
        }.apply { name = "jl-diag" }.start()
    }

    // ---- Protokoll ----

    private fun nextSeq(): Int {
        var s: Int
        do { s = seq.incrementAndGet() and 0xff } while (s == 0x00)
        return s
    }

    private fun send(type: Int, topic: ByteArray, payload: ByteArray = byteArrayOf()) {
        val o = out ?: run { log("nicht verbunden"); return }
        val pkt = ByteArray(6 + payload.size)
        pkt[0] = 0x04; pkt[1] = 0x09
        pkt[2] = nextSeq().toByte()
        pkt[3] = type.toByte()
        pkt[4] = topic[0]; pkt[5] = topic[1]
        payload.copyInto(pkt, 6)
        try {
            o.write(pkt); o.flush()
            log(">> ${pkt.toHexString()}")
        } catch (e: Exception) { log("senden fehlgeschlagen: ${e.message}") }
    }

    private fun initSession() {
        send(0x8a, byteArrayOf(0x0d, 0x4c), byteArrayOf(0x00, 0x00, 0x02, 0x96.toByte())) // 00000296
        send(0x47, byteArrayOf(0x02, 0x28), byteArrayOf(0xff.toByte()))                    // ff
        send(0x47, byteArrayOf(0x02, 0x28), byteArrayOf(0x04))
        send(0x47, byteArrayOf(0x02, 0x28), byteArrayOf(0x05))
        send(0x47, byteArrayOf(0x02, 0x28), byteArrayOf(0x15))
    }

    fun setMode(mode: AncMode) {
        send(0x88, TOPIC_MODE, byteArrayOf(0x01, mode.wire.toByte()))
    }

    fun readMode() { send(0x47, TOPIC_MODE, byteArrayOf(0x01)) }

    // ---- Empfang ----

    private fun startReader(input: InputStream) {
        reader = Thread {
            val buf = ByteArray(1024)
            try {
                while (!closing) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) handle(buf.copyOf(n))
                }
            } catch (e: Exception) {
                if (!closing) ui.post { log("Verbindung getrennt: ${e.message}") }
            }
        }.apply { name = "jl-reader" }
        reader?.start()
    }

    /** Wireshark-Verifikation: 09 04 00 09 0d4c 09 01 <mode> */
    private var carry = ByteArray(0)

    private fun handle(chunk: ByteArray) {
        var data = carry + chunk
        log("<< ${chunk.toHexString()}")
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] != 0x09.toByte() || data[i + 1] != 0x04.toByte()) { i++; continue }
            val type = data[i + 3].toInt() and 0xff
            when (type) {
                0xc7 -> {                       // 09 04 seq c7 13be <wert>
                    if (i + 6 < data.size) {
                        val topic = (data[i + 4].toInt() and 0xff) shl 8 or (data[i + 5].toInt() and 0xff)
                        if (topic == 0x13be) {
                            val v = data[i + 6].toInt() and 0xff
                            AncMode.entries.firstOrNull { it.wire == v }
                                ?.let { m -> ui.post { onMode(m) } }
                        }
                        i += 7
                    } else { carry = data.copyOfRange(i, data.size); return }
                }
                0x09 -> {                        // Push: 09 04 00 09 0d4c 09 01 <modus>
                    if (i + 8 < data.size) {
                        if ((data[i + 4].toInt() and 0xff) == 0x0d && (data[i + 5].toInt() and 0xff) == 0x4c
                            && (data[i + 6].toInt() and 0xff) == 0x09 && (data[i + 7].toInt() and 0xff) == 0x01) {
                            val v = data[i + 8].toInt() and 0xff
                            AncMode.entries.firstOrNull { it.wire == v }
                                ?.let { m -> ui.post { onMode(m) } }
                        }
                        i += 9
                    } else { carry = data.copyOfRange(i, data.size); return }
                }
                else -> i += 4   // Ack/sonstiges: steht im Log
            }
        }
        carry = if (i < data.size) data.copyOfRange(i, data.size) else ByteArray(0)
    }

    fun close() {
        closing = true
        runCatching { socket?.close() }
        socket = null; out = null
    }
}

private fun ByteArray.toHexString() = joinToString(" ") { "%02x".format(it) }
