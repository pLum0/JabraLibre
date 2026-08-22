package com.plum0.jabralibre

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var jabra: JabraRfcomm? = null
    private var device: BluetoothDevice? = null

    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    /** SDP-Ergebnisse (nach fetchUuidsWithSdp) → loggen + Connect-Retry. */
    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_UUID) return
            val uuids: Array<ParcelUuid>? =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                else
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID) as? Array<ParcelUuid>
            val list = uuids?.map { it.uuid.toString() }.orEmpty()
            if (list.isNotEmpty()) {
                log("SDP-UUIDs (frisch): ${list.joinToString(", ")}")
                device?.let { d -> jabra?.connect(d) }   // Retry mit neuem Cache
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.log)
        status = findViewById(R.id.status)
        val deviceList = findViewById<ListView>(R.id.devices)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        deviceList.adapter = adapter

        findViewById<Button>(R.id.btnDiag).setOnClickListener {
            val d = device ?: run { log("erst Gerät wählen/verbinden"); return@setOnClickListener }
            jabra?.diagnose(d)
        }
        findViewById<Button>(R.id.btnAnc).setOnClickListener { setMode(AncMode.ANC) }
        findViewById<Button>(R.id.btnHt).setOnClickListener { setMode(AncMode.HEARTHROUGH) }
        findViewById<Button>(R.id.btnOff).setOnClickListener { setMode(AncMode.OFF) }

        deviceList.setOnItemClickListener { _, _, position, _ ->
            (adapter.getItem(position) as? String)?.let { entry ->
                btAdapter?.bondedDevices?.firstOrNull {
                    entry.startsWith(it.name ?: "?")
                }?.let { connect(it) }
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(
                this, uuidReceiver,
                IntentFilter(BluetoothDevice.ACTION_UUID),
                ContextCompat.RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(uuidReceiver, IntentFilter(BluetoothDevice.ACTION_UUID))
        }

        listBonded()
    }

    @SuppressLint("MissingPermission")
    private fun listBonded() {
        if (!ensurePermissions()) return
        val devices = btAdapter?.bondedDevices
            ?.filter { (it.name ?: "").contains("jabra", true) || (it.name ?: "").contains("elite", true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        adapter.clear()
        devices.forEach { adapter.add("${it.name}  [${it.address}]") }
        if (devices.isEmpty()) log("keine gekoppelten Jabra-Geräte gefunden")
        if (devices.size == 1) connect(devices[0])   // Direktstart
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        device = dev
        log("wähle ${dev.name}")
        jabra?.close()
        jabra = JabraRfcomm(
            log = { msg -> runOnUiThread { log(msg) } },
            onMode = { m -> runOnUiThread { showMode(m) } }
        )
        jabra?.connect(dev)
    }

    private fun setMode(m: AncMode) {
        if (jabra?.isConnected != true) { log("erst verbinden!"); return }
        jabra?.setMode(m)
        showMode(m)
    }

    private fun showMode(m: AncMode) {
        status.text = "Modus: ${m.name}"
    }

    private fun ensurePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val need = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = need.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) listBonded()
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.append("[$ts] $msg\n")
        (logView.parent as? ScrollView)?.let { sv -> sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(uuidReceiver) }
        jabra?.close()
        super.onDestroy()
    }
}
