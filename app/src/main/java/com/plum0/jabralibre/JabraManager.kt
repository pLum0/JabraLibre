package com.plum0.jabralibre

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide owner of the RFCOMM link. The activity and the Quick Settings
 * tile both talk to this object, so the socket survives leaving the UI and a
 * tile tap can reuse a connection the app already opened.
 *
 * All listener callbacks are delivered on the main thread.
 */
object JabraManager {

    private const val TAG = "JabraLibre"
    private const val PREFS = "jabralibre"
    private const val KEY_DEVICE = "last_device"
    private const val KEY_DIAG = "diag_open"
    private const val MAX_LINES = 600

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var appContext: Context? = null
    private var rfcomm: JabraRfcomm? = null
    private var pendingMode: AncMode? = null

    @Volatile var state: ConnState = ConnState.IDLE; private set
    @Volatile var mode: AncMode? = null; private set
    @Volatile var battery: Battery? = null; private set

    /** Mode waiting for the connection to come up, for UI feedback. */
    val queuedMode: AncMode? get() = pendingMode
    @Volatile var device: BluetoothDevice? = null; private set
    @Volatile var deviceName: String? = null; private set

    val isConnected: Boolean get() = rfcomm?.isConnected == true

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    // ---------- observers ----------

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    // ---------- log ----------

    fun log(msg: String) {
        Log.d(TAG, msg)                          // mirrored to: adb logcat -s JabraLibre
        synchronized(lines) {
            lines.addLast("[${stamp.format(Date())}] $msg")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        notifyChanged()
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clearLog() {
        synchronized(lines) { lines.clear() }
        notifyChanged()
    }

    // ---------- devices ----------

    private fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothOn(ctx: Context): Boolean = adapter(ctx)?.isEnabled == true

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun bondedJabras(ctx: Context): List<BluetoothDevice> {
        if (!hasPermission(ctx)) return emptyList()
        return adapter(ctx)?.bondedDevices
            ?.filter { d ->
                val n = d.name ?: ""
                n.contains("jabra", true) || n.contains("elite", true)
            }
            ?.sortedBy { it.name ?: it.address }
            ?: emptyList()
    }

    /** The remembered device if it is still paired, otherwise the first Jabra. */
    @SuppressLint("MissingPermission")
    fun preferredDevice(ctx: Context): BluetoothDevice? {
        val all = bondedJabras(ctx)
        val remembered = prefs(ctx).getString(KEY_DEVICE, null)
        return all.firstOrNull { it.address == remembered } ?: all.firstOrNull()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var diagnosticsOpen: Boolean
        get() = appContext?.let { prefs(it).getBoolean(KEY_DIAG, false) } ?: false
        set(v) { appContext?.let { prefs(it).edit().putBoolean(KEY_DIAG, v).apply() } }

    // ---------- connection ----------

    @SuppressLint("MissingPermission")
    fun connect(ctx: Context, dev: BluetoothDevice? = null) {
        init(ctx)
        val target = dev ?: device ?: preferredDevice(ctx) ?: run {
            log("no paired Jabra device found")
            setState(ConnState.UNAVAILABLE)
            return
        }
        if (target.address != device?.address) {
            rfcomm?.close()
            rfcomm = null
        }
        device = target
        deviceName = target.name ?: target.address
        prefs(ctx).edit().putString(KEY_DEVICE, target.address).apply()

        if (isConnected) { notifyChanged(); return }

        val link = rfcomm ?: JabraRfcomm(
            log = { msg -> log(msg) },
            onMode = { m -> mode = m; notifyChanged() },
            onState = { s -> main.post { onLinkState(s) } },
            onBattery = { b ->
                // the buds repeat the record several times per change
                if (b != battery) {
                    battery = b
                    log("battery: left(04)=${b.left ?: "?"}% right(05)=${b.right ?: "?"}% " +
                        "case(15)=${b.case ?: "?"}%")
                    notifyChanged()
                }
            }
        ).also { rfcomm = it }

        log("selecting ${deviceName}")
        link.connect(target)
    }

    private fun onLinkState(s: ConnState) {
        setState(s)
        if (s == ConnState.CONNECTED) {
            pendingMode?.let { m ->
                pendingMode = null
                // give the session init frames a moment to go out first
                main.postDelayed({ if (isConnected) setMode(m) }, 400)
            }
        } else if (s != ConnState.CONNECTING) {
            // nothing got applied, so do not leave a queued mode behind
            pendingMode = null
        }
    }

    private fun setState(s: ConnState) {
        if (state == s) return
        // the socket error that follows a disconnect must not downgrade the
        // more specific state we already learned from the ACL broadcast
        if (s == ConnState.IDLE && state == ConnState.UNAVAILABLE) return
        state = s
        notifyChanged()
    }

    /**
     * Cheap re-check of whether the buds are reachable at all, without opening
     * a socket. The tile calls this every time the shade opens, so a cached
     * UNAVAILABLE cannot outlive the buds coming back.
     */
    fun refreshAvailability(ctx: Context) {
        init(ctx)
        if (isConnected) return
        val dev = device ?: preferredDevice(ctx)
        setState(
            if (dev != null && hasPermission(ctx) && isBluetoothOn(ctx) &&
                JabraRfcomm.hasBluetoothLink(dev)
            ) ConnState.IDLE else ConnState.UNAVAILABLE
        )
    }

    /** The phone lost its Bluetooth link to the buds (case closed, powered off). */
    fun markUnavailable() {
        rfcomm?.close()
        rfcomm = null
        // after close()'s own IDLE callback, which is posted before this one
        main.post { setState(ConnState.UNAVAILABLE) }
    }

    fun disconnect() {
        rfcomm?.close()
        rfcomm = null
        setState(ConnState.IDLE)
    }

    // ---------- modes ----------

    /** Switch mode, connecting first if the link is not up yet. */
    fun applyMode(ctx: Context, m: AncMode) {
        init(ctx)
        if (isConnected) { setMode(m); return }
        // Deliberately no optimistic update here: if the connect fails, the UI
        // and the tile must not claim a mode the buds never heard about.
        pendingMode = m
        notifyChanged()
        connect(ctx)
    }

    private fun setMode(m: AncMode) {
        mode = m                       // optimistic: the read-back confirms
        notifyChanged()
        rfcomm?.setMode(m)
    }

    /** Tile behaviour: ANC -> HearThrough -> Off -> ANC. */
    fun cycleMode(ctx: Context) {
        // while a connect is still running, cycle on from what is queued so
        // repeated taps keep advancing instead of re-picking the same mode
        val next = when (pendingMode ?: mode) {
            AncMode.ANC -> AncMode.HEARTHROUGH
            AncMode.HEARTHROUGH -> AncMode.OFF
            AncMode.OFF -> AncMode.ANC
            null -> AncMode.ANC
        }
        applyMode(ctx, next)
    }

    fun readMode() { rfcomm?.readMode() }

    fun readBattery() { rfcomm?.readBattery() }

    // ---------- diagnostics ----------

    @SuppressLint("MissingPermission")
    fun channelScan() {
        val d = device ?: run { log("pick a device first"); return }
        rfcomm?.diagnose(d)
    }

    fun probeKnownTopics() {
        if (!isConnected) { log("connect first"); return }
        rfcomm?.probeKnownTopics()
    }

    fun probeIndices(topic: Int, from: Int, to: Int) {
        if (!isConnected) { log("connect first"); return }
        rfcomm?.probeIndices(topic, from..to)
    }

    fun probeTopics(from: Int, to: Int, index: Int = 0x01) {
        if (!isConnected) { log("connect first"); return }
        rfcomm?.probeTopics(from, to, index)
    }

    fun probeDirectories(from: Int, to: Int) {
        if (!isConnected) { log("connect first"); return }
        rfcomm?.probeDirectories(from, to)
    }

    fun subscribe(mask: Long) {
        if (!isConnected) { log("connect first"); return }
        rfcomm?.subscribe(mask)
    }
}
