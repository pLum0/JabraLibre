package com.plum0.jabralibre

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQ_BT = 1
        /** Shared by the mode cards and the battery row so they dim in step. */
        const val STALE_ALPHA = 0.45f
    }

    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var devicePill: TextView
    private lateinit var btnAction: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var diagPanel: View
    private lateinit var diagChevron: ImageView

    private lateinit var cardBox: View
    private lateinit var batteryRow: View
    private lateinit var batteryCells: List<Triple<TextView, View, View>>   // value, fill, rest

    private lateinit var cards: Map<AncMode, View>
    private lateinit var checks: Map<AncMode, View>

    /** `adb shell am start … -e probe 13be:00-2f` — runs once we are connected. */
    private var pendingProbe: String? = null

    private val onManagerChanged: () -> Unit = { render() }

    /** SDP results (after fetchUuidsWithSdp) -> log + connect retry. */
    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_UUID) return
            val uuids: Array<ParcelUuid>? =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                else
                    @Suppress("DEPRECATION", "UNCHECKED_CAST")
                    intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID) as? Array<ParcelUuid>
            val list = uuids?.map { it.uuid.toString() }.orEmpty()
            if (list.isNotEmpty()) {
                JabraManager.log("SDP UUIDs (fresh): ${list.joinToString(", ")}")
                if (!JabraManager.isConnected) JabraManager.connect(this@MainActivity)
            }
        }
    }

    /**
     * Keeps an open app in step with reality: taking the buds out of the case
     * reconnects on its own, putting them back shows it immediately instead of
     * waiting for a socket error.
     */
    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val dev: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val address = dev?.address ?: return
            if (JabraManager.bondedJabras(this@MainActivity).none { it.address == address }) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    JabraManager.log("earbuds came back — reconnecting")
                    // RFCOMM right at ACL-up tends to be refused
                    window.decorView.postDelayed({
                        if (!JabraManager.isConnected) JabraManager.connect(this@MainActivity, dev)
                    }, 1500)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    JabraManager.log("earbuds disconnected")
                    JabraManager.markUnavailable()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        JabraManager.init(this)

        status = findViewById(R.id.status)
        dot = findViewById(R.id.dot)
        devicePill = findViewById(R.id.devicePill)
        btnAction = findViewById(R.id.btnAction)
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        diagPanel = findViewById(R.id.diagPanel)
        diagChevron = findViewById(R.id.diagChevron)

        cardBox = findViewById(R.id.cardBox)
        batteryRow = findViewById(R.id.batteryRow)
        batteryCells = listOf(
            Triple(findViewById<TextView>(R.id.batLValue), findViewById<View>(R.id.batLFill), findViewById<View>(R.id.batLRest)),
            Triple(findViewById<TextView>(R.id.batRValue), findViewById<View>(R.id.batRFill), findViewById<View>(R.id.batRRest)),
            Triple(findViewById<TextView>(R.id.batCaseValue), findViewById<View>(R.id.batCaseFill), findViewById<View>(R.id.batCaseRest))
        )

        cards = mapOf(
            AncMode.ANC to findViewById(R.id.cardAnc),
            AncMode.HEARTHROUGH to findViewById(R.id.cardHt),
            AncMode.OFF to findViewById(R.id.cardOff)
        )
        checks = mapOf(
            AncMode.ANC to findViewById(R.id.checkAnc),
            AncMode.HEARTHROUGH to findViewById(R.id.checkHt),
            AncMode.OFF to findViewById(R.id.checkOff)
        )
        cards.forEach { (mode, card) ->
            card.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (ensurePermission()) JabraManager.applyMode(this, mode)
            }
        }

        devicePill.setOnClickListener { chooseDevice() }
        btnAction.setOnClickListener {
            if (ensurePermission()) JabraManager.connect(this)
        }

        findViewById<View>(R.id.diagHeader).setOnClickListener {
            setDiagnosticsOpen(diagPanel.visibility != View.VISIBLE)
        }
        findViewById<Button>(R.id.btnReadMode).setOnClickListener {
            JabraManager.readMode(); JabraManager.readBattery()
        }
        findViewById<Button>(R.id.btnScanChannels).setOnClickListener { JabraManager.channelScan() }
        findViewById<Button>(R.id.btnScanTopics).setOnClickListener { JabraManager.probeKnownTopics() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { JabraManager.clearLog() }

        setDiagnosticsOpen(JabraManager.diagnosticsOpen)
        handleProbeIntent(intent)

        register(uuidReceiver, IntentFilter(BluetoothDevice.ACTION_UUID))
        register(aclReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleProbeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        JabraManager.addListener(onManagerChanged)
        render()
        if (!ensurePermission()) return
        when (JabraManager.state) {
            ConnState.CONNECTED -> {
                JabraManager.readMode()      // refresh after the buds' own button
                JabraManager.readBattery()
            }
            ConnState.CONNECTING -> {}
            // reopening the app is a retry: the buds may be awake by now
            else -> JabraManager.connect(this)
        }
    }

    override fun onPause() {
        JabraManager.removeListener(onManagerChanged)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(uuidReceiver) }
        runCatching { unregisterReceiver(aclReceiver) }
        // the RFCOMM link deliberately outlives the activity: the Quick
        // Settings tile reuses it, so it is not closed here
        super.onDestroy()
    }

    // ---------- rendering ----------

    private fun render() {
        // everything we show about the buds is last-known once the link is gone
        val live = JabraManager.isConnected
        cardBox.alpha = if (live) 1f else STALE_ALPHA

        val m = JabraManager.mode
        val queued = JabraManager.queuedMode
        cards.forEach { (mode, card) ->
            val active = mode == m
            card.isActivated = active
            val check = checks[mode] ?: return@forEach
            // a queued mode is shown as pending, never as if it had been applied
            check.visibility = if (active || mode == queued) View.VISIBLE else View.INVISIBLE
            check.alpha = if (active) 1f else 0.4f
        }

        val hasDevice = JabraManager.bondedJabras(this).isNotEmpty()
        val (text, color) = when {
            !JabraManager.hasPermission(this) ->
                getString(R.string.state_no_permission) to R.color.dot_bad
            !JabraManager.isBluetoothOn(this) ->
                getString(R.string.state_bt_off) to R.color.dot_idle
            !hasDevice -> getString(R.string.state_no_device) to R.color.dot_idle
            else -> when (JabraManager.state) {
                ConnState.CONNECTED -> getString(R.string.state_connected) to R.color.dot_ok
                ConnState.CONNECTING -> getString(R.string.state_connecting) to R.color.dot_busy
                ConnState.FAILED -> getString(R.string.state_failed) to R.color.dot_bad
                ConnState.IDLE -> getString(R.string.state_idle) to R.color.dot_idle
                ConnState.UNAVAILABLE -> getString(R.string.state_unavailable) to R.color.dot_idle
            }
        }
        status.text = text
        dot.background.setTint(ContextCompat.getColor(this, color))

        btnAction.visibility = when (JabraManager.state) {
            ConnState.CONNECTED, ConnState.CONNECTING -> View.GONE
            else -> View.VISIBLE
        }
        btnAction.setText(
            if (!JabraManager.hasPermission(this)) R.string.action_grant else R.string.action_reconnect
        )

        renderBattery()

        // The pill doubles as the device picker, so it stays visible (and says
        // so) even when no name matched — otherwise renamed earbuds would be
        // unreachable.
        val name = JabraManager.deviceName
        val canChoose = JabraManager.selectableDevices(this).isNotEmpty()
        devicePill.visibility = if (name == null && !canChoose) View.GONE else View.VISIBLE
        devicePill.text = name ?: getString(R.string.action_choose_device)
        devicePill.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (canChoose) R.drawable.ic_chevron_down else 0, 0
        )
        devicePill.isClickable = canChoose
        devicePill.alpha = if (JabraManager.isConnected) 1f else STALE_ALPHA

        if (diagPanel.visibility == View.VISIBLE) {
            val atBottom = !logScroll.canScrollVertically(1)
            logView.text = JabraManager.logText()
            if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        pendingProbe?.let { spec ->
            if (JabraManager.isConnected) { pendingProbe = null; runProbe(spec) }
        }
    }

    /**
     * Component 04 -> left, component 05 -> right, component 15 -> case
     * (verified against Sound+ with three different levels).
     *
     * The cells are always on screen, showing placeholders until the first
     * record arrives, so nothing shifts around underneath the mode buttons.
     */
    private fun renderBattery() {
        val b = JabraManager.battery
        // stale once the link is gone: still the last known state, just not live
        batteryRow.alpha = if (JabraManager.isConnected && b != null) 1f else STALE_ALPHA
        listOf(b?.left, b?.right, b?.case).forEachIndexed { i, level ->
            val (value, fill, rest) = batteryCells[i]
            if (level == null) {
                value.text = getString(R.string.bat_unknown)
                setWeights(fill, rest, 0f)
            } else {
                value.text = getString(R.string.bat_percent, level)
                setWeights(fill, rest, level / 100f)
                fill.background.setTint(
                    ContextCompat.getColor(
                        this,
                        when {
                            level <= 15 -> R.color.dot_bad
                            level <= 30 -> R.color.dot_busy
                            else -> R.color.brand_green
                        }
                    )
                )
            }
        }
    }

    private fun setWeights(fill: View, rest: View, fraction: Float) {
        (fill.layoutParams as LinearLayout.LayoutParams).weight = fraction
        (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - fraction
        fill.requestLayout(); rest.requestLayout()
    }

    private fun setDiagnosticsOpen(open: Boolean) {
        diagPanel.visibility = if (open) View.VISIBLE else View.GONE
        diagChevron.rotation = if (open) 90f else 0f
        JabraManager.diagnosticsOpen = open
        if (open) {
            logView.text = JabraManager.logText()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---------- device picking ----------

    @SuppressLint("MissingPermission")
    private fun chooseDevice() {
        val devices = JabraManager.selectableDevices(this)
        if (devices.isEmpty()) return
        val labels = devices.map { "${it.name ?: "?"}  ·  ${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.action_choose_device)
            .setItems(labels) { _, i -> JabraManager.connect(this, devices[i]) }
            .show()
    }

    // ---------- permissions ----------

    private fun ensurePermission(): Boolean {
        if (JabraManager.hasPermission(this)) return true
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT
        )
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT &&
            grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            JabraManager.connect(this)
        }
        render()
    }

    // ---------- log export ----------

    private fun shareLog() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, getString(R.string.diag_share_title))
            putExtra(Intent.EXTRA_TEXT, JabraManager.logText())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.diag_share_chooser)))
    }

    // ---------- protocol exploration via adb ----------

    /**
     * Dev entry point, no UI:
     *   adb shell am start -n com.plum0.jabralibre/.MainActivity -e probe 13be:00-2f
     *   adb shell am start -n com.plum0.jabralibre/.MainActivity -e probe topics:1300-13ff
     *   adb shell am start -n com.plum0.jabralibre/.MainActivity -e probe dirs:0200-02ff
     */
    private fun handleProbeIntent(intent: Intent?) {
        val spec = intent?.getStringExtra("probe") ?: return
        JabraManager.log("probe requested: $spec")
        if (JabraManager.isConnected) runProbe(spec) else pendingProbe = spec
    }

    private fun runProbe(spec: String) {
        val parts = spec.split(":")
        if (parts.size != 2) { JabraManager.log("probe: bad spec '$spec'"); return }
        if (parts[0].equals("sub", true)) {
            val mask = parts[1].toLongOrNull(16)
                ?: return JabraManager.log("probe: bad mask '${parts[1]}'")
            return JabraManager.subscribe(mask)
        }
        val range = parts[1].split("-")
        val from = range.getOrNull(0)?.toIntOrNull(16)
        val to = range.getOrNull(1)?.toIntOrNull(16) ?: from
        if (from == null || to == null) { JabraManager.log("probe: bad range '$spec'"); return }
        if (parts[0].equals("topics", true)) {
            JabraManager.probeTopics(from, to)
        } else if (parts[0].equals("dirs", true)) {
            JabraManager.probeDirectories(from, to)
        } else {
            val topic = parts[0].toIntOrNull(16)
                ?: return JabraManager.log("probe: bad topic '${parts[0]}'")
            JabraManager.probeIndices(topic, from, to)
        }
    }
}
