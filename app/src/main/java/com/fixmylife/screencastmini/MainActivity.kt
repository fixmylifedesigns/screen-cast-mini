package com.fixmylife.screencastmini

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var deviceLabel: TextView
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cast", Context.MODE_PRIVATE) }
    private var deviceMac: String?
        get() = prefs.getString("mac", null)
        set(v) { prefs.edit().putString("mac", v).apply() }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val intent = Intent(this, CastService::class.java)
            .putExtra(CastService.EXTRA_CODE, result.resultCode)
            .putExtra(CastService.EXTRA_DATA, data)
            .putExtra(CastService.EXTRA_MAC, deviceMac)
        ContextCompat.startForegroundService(this, intent)
        main.postDelayed({ refresh() }, 1200)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestPermissions() {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        val missing = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.rgb(20, 90, 140))
        }
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
            setBackgroundColor(Color.rgb(10, 12, 20))
        }
        val title = TextView(this).apply {
            text = "Screen Cast Mini"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }
        deviceLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, 0, 0, dp(16))
        }

        val pick = button("Pick monitor") { scan() }
        val start = button("Start casting") { startCasting() }
        val stop = button("Stop") {
            startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
            main.postDelayed({ refresh() }, 600)
        }

        val help = TextView(this).apply {
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, dp(24), 0, 0)
            text = "1. Pick your monitor over Bluetooth.\n" +
                "2. Tap Start casting and allow screen capture.\n" +
                "3. A cyan box appears on screen. Drag it over the Maps directions, " +
                "resize with the corner dot.\n" +
                "4. Tap LOCK so the box stops catching taps; the region keeps streaming " +
                "while you use Maps normally.\n\n" +
                "Bluetooth LE is slow, so expect a few frames per second \u2014 fine for " +
                "directions, not for video."
        }

        val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        root.addView(title)
        root.addView(status)
        root.addView(deviceLabel)
        root.addView(pick, lp)
        root.addView(start, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(stop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(help)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun refresh() {
        val svc = CastService.instance
        status.text = when {
            svc == null -> "Not casting"
            svc.connected -> svc.status
            else -> "${svc.status} (monitor not connected)"
        }
        deviceLabel.text = deviceMac?.let { "Monitor: $it" } ?: "No monitor picked yet"
    }

    private fun startCasting() {
        if (deviceMac == null) {
            Toast.makeText(this, "Pick your monitor first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow drawing over other apps, then tap Start again", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun scan() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Turn on Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        val found = LinkedHashMap<String, ScanResult>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found[result.device.address] = result
            }
        }
        status.text = "Scanning\u2026"
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        main.postDelayed({
            scanner.stopScan(cb)
            showPicker(found.values.toList())
        }, 5000)
    }

    private fun isUart(r: ScanResult) =
        r.scanRecord?.serviceUuids?.any { it.uuid == BleLink.SERVICE } == true

    private fun showPicker(results: List<ScanResult>) {
        val list = results
            .filter { isUart(it) || !it.device.name.isNullOrBlank() }
            .sortedWith(compareByDescending<ScanResult> { isUart(it) }.thenByDescending { it.rssi })
        if (list.isEmpty()) {
            status.text = "No devices found. Is the monitor on?"
            return
        }
        val labels = list.map { r ->
            val star = if (isUart(r)) "\u2605 " else ""
            "$star${r.device.name ?: "(no name)"}  ${r.device.address}  ${r.rssi}dBm"
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Pick the mini monitor (\u2605 = likely match)")
            .setItems(labels) { _, which ->
                deviceMac = list[which].device.address
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
