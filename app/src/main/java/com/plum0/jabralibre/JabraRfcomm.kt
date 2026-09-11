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
import java.util.concurrent.atomic.AtomicLong

enum class AncMode(val wire: Int) { OFF(0x01), HEARTHROUGH(0x02), ANC(0x04) }

enum class ConnState {
    IDLE,
    CONNECTING,
    CONNECTED,
    /** Tried and rejected — buds awake but the channel would not open. */
    FAILED,
    /** The buds are not connected to the phone at all (in the case, or off). */
    UNAVAILABLE
}

class JabraRfcomm(
    private val log: (String) -> Unit,
    private val onMode: (AncMode) -> Unit,
    private val onState: (ConnState) -> Unit = {},
    private val onBattery: (Battery) -> Unit = {}
) {
    companion object {
        // from the HCI capture: DLCI 0x3a -> server channel 29
        private val TOPIC_MODE = byteArrayOf(0x13, 0xbe.toByte())
        private val TOPIC_BATTERY = byteArrayOf(0x12, 0x02)
        /** Length of the topic-1202 battery record (header 5 + 2 entries x 3). */
        private const val BATTERY_LEN = 11
        private val CHANNELS = intArrayOf(29, 28, 30)

        /**
         * Whether the phone currently holds a Bluetooth link to the buds.
         * Hidden API, so treat "cannot tell" as "go ahead and try" — the retry
         * ladder is what makes this app connect where Sound+ often does not.
         */
        fun hasBluetoothLink(device: BluetoothDevice): Boolean =
            runCatching {
                device.javaClass.getMethod("isConnected").invoke(device) as? Boolean
            }.getOrNull() ?: true

        /** Topics seen in the capture — starting points for the probe. */
        val KNOWN_TOPICS = intArrayOf(0x13be, 0x1202, 0x0d4c, 0x0228)
    }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var reader: Thread? = null
    private val seq = AtomicInteger(0x09)
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var closing = false
    private val connecting = AtomicBoolean(false)

    /** Timestamp of the last mode write — transition pushes follow it. */
    private val lastModeWrite = AtomicLong(0)

    val isConnected: Boolean get() = socket?.isConnected == true

    // ---------- obtaining a socket ----------

    /**
     * createInsecureRfcommSocket(int)/createRfcommSocket(int) are hidden
     * Android APIs (not in the SDK) -> reflection. null if not callable.
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

    /** Public path: SDP service record -> Android resolves the channel itself. */
    private fun uuidSocket(device: BluetoothDevice, uuid: UUID): BluetoothSocket? =
        runCatching { device.createRfcommSocketToServiceRecord(uuid) }.getOrNull()

    // ---------- connecting ----------

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!connecting.compareAndSet(false, true)) return
        closing = false
        if (!hasBluetoothLink(device)) {
            // Without an ACL link every channel attempt fails on read anyway,
            // and the ladder would spend ~45 s doing it.
            log("earbuds are not connected to the phone — nothing to talk to")
            connecting.set(false)
            onState(ConnState.UNAVAILABLE)
            return
        }
        onState(ConnState.CONNECTING)
        Thread {
            var ok = false
            try {
                cachedUuids(device)?.let {
                    log("SDP cache: ${if (it.isEmpty()) "(empty)" else it.joinToString { p -> p.uuid.toString() }}")
                }

                // strategy 1: direct channels (29 = capture finding), 2 tries each
                for (ch in CHANNELS) {
                    for (attempt in 1..2) {
                        val s = openSocket(device, ch)
                            ?: openSocket(device, ch, insecure = false)
                            ?: run { log("reflection unavailable"); return@Thread }
                        try {
                            s.connect()
                            socket = s; out = s.outputStream
                            log("connected (channel $ch)")
                            ok = true
                            onState(ConnState.CONNECTED)
                            startReader(s.inputStream); initSession(); readMode(); readBattery()
                            return@Thread
                        } catch (e: Exception) {
                            log("channel $ch (try $attempt): ${e.message}")
                            runCatching { s.close() }
                            runCatching { Thread.sleep(1500) }   // slot may be busy briefly
                        }
                    }
                }

                // strategy 2: proprietary SDP UUIDs -> service record connect
                val customs = cachedUuids(device).orEmpty().map { it.uuid }
                    .filterNot { it.toString().substring(0, 8).matches(Regex("0000(11|18)[0-9a-f]{2}")) }
                if (customs.isEmpty()) {
                    log("no proprietary SDP UUIDs in the cache")
                } else for (u in customs) {
                    val s = uuidSocket(device, u) ?: continue
                    try {
                        s.connect()
                        socket = s; out = s.outputStream
                        log("connected (UUID $u)")
                        ok = true
                        onState(ConnState.CONNECTED)
                        startReader(s.inputStream); initSession(); readMode(); readBattery()
                        return@Thread
                    } catch (e: Exception) {
                        log("UUID …${u.toString().takeLast(12)}: ${e.message}")
                        runCatching { s.close() }
                    }
                }

                log("CONNECTION FAILED.")
                log("→ force-stop Jabra Sound+ in app info, wake the buds, try again")
                log("→ then run the channel scan and read through the log")
            } finally {
                connecting.set(false)
                if (!ok) onState(ConnState.FAILED)
            }
        }.apply { name = "jl-connect" }.start()
    }

    /** Debugging: re-query SDP + try channels 1-31. */
    @SuppressLint("MissingPermission")
    fun diagnose(device: BluetoothDevice) {
        Thread {
            runCatching {
                val ok = device.javaClass.getMethod("fetchUuidsWithSdp").invoke(device) as? Boolean ?: false
                if (ok) log("SDP re-query sent – UUIDs will show up in the log shortly …")
            }
            log("— diagnostics: scanning channels 1-31 (takes ~30 s) —")
            var open = 0
            for (ch in 1..31) {
                val s = openSocket(device, ch) ?: continue
                try {
                    s.connect()
                    log("channel $ch: OPEN ✓")
                    open++
                    runCatching { s.close() }
                } catch (_: Exception) { /* expected */ }
            }
            log("scan done: $open open channels (see the ✓ lines).")
        }.apply { name = "jl-diag" }.start()
    }

    // ---- protocol ----

    private fun nextSeq(): Int {
        var s: Int
        do { s = seq.incrementAndGet() and 0xff } while (s == 0x00)
        return s
    }

    @Synchronized private fun send(
        type: Int, topic: ByteArray, payload: ByteArray = byteArrayOf(), quiet: Boolean = false
    ) {
        val o = out ?: run { log("not connected"); return }
        val pkt = ByteArray(6 + payload.size)
        pkt[0] = 0x04; pkt[1] = 0x09
        pkt[2] = nextSeq().toByte()
        pkt[3] = type.toByte()
        pkt[4] = topic[0]; pkt[5] = topic[1]
        payload.copyInto(pkt, 6)
        try {
            o.write(pkt); o.flush()
            if (!quiet) log(">> ${pkt.toHexString()}")
        } catch (e: Exception) { log("send failed: ${e.message}") }
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
        // After a write the buds first push a transition status (the old value);
        // the real mode only comes on request -> read back like the original app.
        lastModeWrite.set(System.currentTimeMillis())
        ui.postDelayed({ readMode() }, 600)
    }

    fun readMode() { send(0x47, TOPIC_MODE, byteArrayOf(0x01)) }

    fun readBattery() { send(0x47, TOPIC_BATTERY, byteArrayOf(0x01)) }

    // ---- protocol exploration (battery / EQ hunting) ----

    /**
     * Read-only sweep. The reply echoes the topic (`09 04 <seq> c7 <topic> …`),
     * so everything that answers shows up in the raw `<<` lines — no request
     * bookkeeping needed. Only command type 0x47 (read) is used.
     */
    fun probeIndices(topic: Int, indices: IntRange = 0x00..0x2f, delayMs: Long = 60) {
        Thread {
            log("— probe: topic ${"%04x".format(topic)} indices " +
                "${"%02x".format(indices.first)}…${"%02x".format(indices.last)} —")
            val t = byteArrayOf((topic shr 8).toByte(), (topic and 0xff).toByte())
            for (i in indices) {
                if (!isConnected) { log("probe aborted: not connected"); return@Thread }
                send(0x47, t, byteArrayOf(i.toByte()))   // logged: the seq maps reply -> index
                runCatching { Thread.sleep(delayMs) }
            }
            log("— probe done (topic ${"%04x".format(topic)}) —")
        }.apply { name = "jl-probe-idx" }.start()
    }

    /** Sweeping the topic id with a fixed index. */
    fun probeTopics(from: Int, to: Int, index: Int = 0x01, delayMs: Long = 40) {
        Thread {
            log("— probe: topics ${"%04x".format(from)}…${"%04x".format(to)} index ${"%02x".format(index)} —")
            for (t in from..to) {
                if (!isConnected) { log("probe aborted: not connected"); return@Thread }
                send(0x47, byteArrayOf((t shr 8).toByte(), (t and 0xff).toByte()), byteArrayOf(index.toByte()))
                runCatching { Thread.sleep(delayMs) }
            }
            log("— probe done (topics ${"%04x".format(from)}…${"%04x".format(to)}) —")
        }.apply { name = "jl-probe-topic" }.start()
    }

    /**
     * Index `ff` asks a topic for its directory: topic 0228 answers
     * `c9 02 28 04 05 15`, i.e. "my valid indices are 04, 05, 15".
     * Sweeping `ff` is therefore the cheapest way to find which topics exist.
     */
    fun probeDirectories(from: Int, to: Int, delayMs: Long = 40) {
        Thread {
            log("— probe: directories ${"%04x".format(from)}…${"%04x".format(to)} (index ff) —")
            for (t in from..to) {
                if (!isConnected) { log("probe aborted: not connected"); return@Thread }
                send(0x47, byteArrayOf((t shr 8).toByte(), (t and 0xff).toByte()), byteArrayOf(0xff.toByte()))
                runCatching { Thread.sleep(delayMs) }
            }
            log("— probe done (directories ${"%04x".format(from)}…${"%04x".format(to)}) —")
        }.apply { name = "jl-probe-dir" }.start()
    }

    /**
     * Re-subscribe with a different notification mask. Session init uses
     * `00000296`; the mode pushes arrive as class `09` inside that mask, so a
     * wider mask is the cheapest way to find out what else the buds volunteer.
     */
    fun subscribe(mask: Long) {
        send(0x8a, byteArrayOf(0x0d, 0x4c), byteArrayOf(
            (mask shr 24 and 0xff).toByte(), (mask shr 16 and 0xff).toByte(),
            (mask shr 8 and 0xff).toByte(), (mask and 0xff).toByte()
        ))
        log("subscription mask set to ${"%08x".format(mask)}")
    }

    /** Default sweep: directory + every index of the topics we already know. */
    fun probeKnownTopics() {
        Thread {
            for (t in KNOWN_TOPICS) {
                if (!isConnected) return@Thread
                send(0x47, byteArrayOf((t shr 8).toByte(), (t and 0xff).toByte()), byteArrayOf(0xff.toByte()))
                runCatching { Thread.sleep(400) }
                probeIndices(t, 0x00..0x2f)
                runCatching { Thread.sleep(0x30 * 60L + 600) }
            }
        }.apply { name = "jl-probe-all" }.start()
    }

    // ---- receiving ----

    private fun startReader(input: InputStream) {
        reader = Thread {
            val buf = ByteArray(1024)
            try {
                while (!closing) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) handle(buf.copyOf(n))
                }
                if (!closing) { log("connection closed by the buds"); onState(ConnState.IDLE) }
            } catch (e: Exception) {
                if (!closing) {
                    log("connection lost: ${e.message}")
                    onState(ConnState.IDLE)
                }
            }
        }.apply { name = "jl-reader" }
        reader?.start()
    }

    /** Wireshark-verified: 09 04 00 09 0d4c 09 01 <mode> */
    private var carry = ByteArray(0)

    private fun handle(chunk: ByteArray) {
        var data = carry + chunk
        log("<< ${chunk.toHexString()}")
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] != 0x09.toByte() || data[i + 1] != 0x04.toByte()) { i++; continue }
            val type = data[i + 3].toInt() and 0xff
            when (type) {
                0xc7 -> {                       // 09 04 seq c7 13be <value>
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
                0x09 -> {                        // push: 09 04 00 09 0d4c 09 01 <mode>
                    if (i + 8 < data.size) {
                        if ((data[i + 4].toInt() and 0xff) == 0x0d && (data[i + 5].toInt() and 0xff) == 0x4c
                            && (data[i + 6].toInt() and 0xff) == 0x09 && (data[i + 7].toInt() and 0xff) == 0x01) {
                            val v = data[i + 8].toInt() and 0xff
                            val sinceOwnWrite = System.currentTimeMillis() - lastModeWrite.get()
                            if (sinceOwnWrite < 1500) {
                                log("push ignored (transition status after our own write)")
                            } else {
                                AncMode.entries.firstOrNull { it.wire == v }
                                    ?.let { m -> ui.post { onMode(m) } }
                            }
                        }
                        i += 9
                    } else { carry = data.copyOfRange(i, data.size); return }
                }
                0xd1, 0x11 -> {                  // battery: 09 04 seq <d1|11> 1202 <11 bytes>
                    if (i + 5 < data.size) {
                        val topic = (data[i + 4].toInt() and 0xff) shl 8 or (data[i + 5].toInt() and 0xff)
                        if (topic == 0x1202) {
                            if (i + 6 + BATTERY_LEN > data.size) {
                                carry = data.copyOfRange(i, data.size); return
                            }
                            Battery.parse(data.copyOfRange(i + 6, i + 6 + BATTERY_LEN))
                                ?.let { b -> ui.post { onBattery(b) } }
                            i += 6 + BATTERY_LEN
                        } else i += 4
                    } else { carry = data.copyOfRange(i, data.size); return }
                }
                else -> i += 4   // ack/other: it is in the log
            }
        }
        carry = if (i < data.size) data.copyOfRange(i, data.size) else ByteArray(0)
    }

    fun close() {
        closing = true
        runCatching { socket?.close() }
        socket = null; out = null
        onState(ConnState.IDLE)
    }
}

private fun ByteArray.toHexString() = joinToString(" ") { "%02x".format(it) }
