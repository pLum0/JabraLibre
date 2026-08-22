package com.plum0.jabralibre

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

enum class AncMode(val wire: Int) { OFF(0x01), HEARTHROUGH(0x02), ANC(0x04) }

class JabraRfcomm(
    private val log: (String) -> Unit,
    private val onMode: (AncMode) -> Unit
) {
    companion object {
        // aus dem HCI-Capture: DLCI 0x3a → Server-Kanal 29
        private val CHANNELS = intArrayOf(29, 28)
        private val TOPIC_MODE = byteArrayOf(0x13, 0xbe.toByte())
    }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var reader: Thread? = null
    private val seq = AtomicInteger(0x09)
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var closing = false

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * createInsecureRfcommSocket(int) ist eine versteckte Android-API
     * (nicht im öffentlichen SDK) → per Reflection aufrufen.
     * Standard-Trick für Geräte ohne SPP-Service-Eintrag, klappt seit Jahren.
     */
    private fun openSocket(device: BluetoothDevice, channel: Int): BluetoothSocket? =
        try {
            val m = device.javaClass.getMethod(
                "createInsecureRfcommSocket", Int::class.javaPrimitiveType
            )
            m.invoke(device, channel) as? BluetoothSocket
        } catch (e: Exception) {
            log("Kanal $channel nicht öffnbar: ${e.message}")
            null
        }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        closing = false
        Thread {
            for (ch in CHANNELS) {
                try {
                    log("verbinde RFCOMM Kanal $ch …")
                    val s = openSocket(device, ch) ?: continue
                    s.connect()   // blockiert, wirft bei Fehler
                    socket = s
                    out = s.outputStream
                    log("verbunden (Kanal $ch)")
                    startReader(s.inputStream)
                    initSession()
                    readMode()
                    return@Thread
                } catch (e: Exception) {
                    log("Kanal $ch: ${e.message}")
                    runCatching { socket?.close() }
                }
            }
            log("Verbindung fehlgeschlagen (Buds gekoppelt und wach?)")
        }.apply { name = "jl-connect" }.start()
    }

    // ---- Protokoll ----

    private fun nextSeq(): Int {
        var s: Int
        do { s = seq.incrementAndGet() and 0xff } while (s == 0x00)
        return s
    }

    private fun send(type: Int, topic: ByteArray, payload: ByteArray = byteArrayOf()) {
        val o = out ?: run { log("nicht verbunden"); return }
        val pkt = ByteArray(3 + 2 + payload.size)
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
        send(0x47, byteArrayOf(0x02, 0x28), byteArrayOf(0xff.toByte()))                     // ff
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
                        val topic = (data[i+4].toInt() and 0xff) shl 8 or (data[i+5].toInt() and 0xff)
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
                        if ((data[i+4].toInt() and 0xff) == 0x0d && (data[i+5].toInt() and 0xff) == 0x4c
                            && (data[i+6].toInt() and 0xff) == 0x09 && (data[i+7].toInt() and 0xff) == 0x01) {
                            val v = data[i + 8].toInt() and 0xff
                            AncMode.entries.firstOrNull { it.wire == v }
                                ?.let { m -> ui.post { onMode(m) } }
                        }
                        i += 9
                    } else { carry = data.copyOfRange(i, data.size); return }
                }
                else -> i += 4   // Ack/sonstiges: übergehen (steht im Log)
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
