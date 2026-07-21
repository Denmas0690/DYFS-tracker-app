package com.boldstudio.dyfs

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import java.util.*

class MainActivity : AppCompatActivity() {

private val serviceUuid = "12345678-1234-1234-1234-123456789abc"
private val characteristicUuid = "87654321-4321-4321-4321-cba987654321"
private val prefsName = "dyfs_tracker_prefs"
private val keyTrackerAddress = "tracker_address"

private var bluetoothGatt: BluetoothGatt? = null
private var rememberedTrackerAddress: String? = null
private var pendingTrackerAddress: String? = null

private lateinit var txtStatus: TextView
private lateinit var txtLocation: TextView
private lateinit var btnShowMap: MaterialButton

private lateinit var fusedLocationClient: FusedLocationProviderClient
private var locationCallback: LocationCallback? = null

private var lastLat: Double? = null
private var lastLng: Double? = null

private val handler = Handler(Looper.getMainLooper())
private var rssiRunnable: Runnable? = null

private val bluetoothAdapter: BluetoothAdapter? by lazy {
val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
manager.adapter
}

private val scanner get() = bluetoothAdapter?.bluetoothLeScanner

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContentView(R.layout.activity_main)

txtStatus = findViewById(R.id.txtStatus)
txtLocation = findViewById(R.id.txtLocation)
btnShowMap = findViewById(R.id.btnShowMap)

fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
rememberedTrackerAddress = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
.getString(keyTrackerAddress, null)

findViewById<MaterialButton>(R.id.btnPing).setOnClickListener { sendCommand("PING") }
findViewById<MaterialButton>(R.id.btnBuzzOn).setOnClickListener { sendCommand("BUZZ_ON") }
findViewById<MaterialButton>(R.id.btnBuzzOff).setOnClickListener { sendCommand("BUZZ_OFF") }
findViewById<MaterialButton>(R.id.btnSleep).setOnClickListener { sendCommand("SLEEP") }

txtStatus.setOnLongClickListener {
getSharedPreferences(prefsName, Context.MODE_PRIVATE)
.edit()
.remove(keyTrackerAddress)
.apply()
rememberedTrackerAddress = null
pendingTrackerAddress = null
txtStatus.text = "Tracker reset"
startScan()
true
}

btnShowMap.setOnClickListener {
lastLat?.let { lat ->
lastLng?.let { lng ->
val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
startActivity(Intent(Intent.ACTION_VIEW, uri))
}
}
}

ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
insets
}

checkPermissionsAndStart()
}

private fun startRssiUpdates() {
rssiRunnable = object : Runnable {
@SuppressLint("MissingPermission")
override fun run() {
bluetoothGatt?.readRemoteRssi()
handler.postDelayed(this, 2000)
}
}
handler.post(rssiRunnable!!)
}

private fun getDistanceLabel(rssi: Int): String {
return when {
rssi >= -60 -> "Very Close"
rssi >= -75 -> "Nearby"
rssi >= -90 -> "Far"
else -> "Very Far"
}
}

private val scanCallback = object : ScanCallback() {
@SuppressLint("MissingPermission")
override fun onScanResult(callbackType: Int, result: ScanResult) {
val name = result.device.name ?: ""
val savedAddress = rememberedTrackerAddress

if (savedAddress != null && result.device.address != savedAddress) {
return
}

if (savedAddress != null || name.contains("DYFS-Tracker", true)) {
scanner?.stopScan(this)
pendingTrackerAddress = result.device.address
bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
}
}
}

private val gattCallback = object : BluetoothGattCallback() {

@SuppressLint("MissingPermission")
override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
runOnUiThread {
if (newState == BluetoothProfile.STATE_CONNECTED) {
txtStatus.text = "Connected"
btnShowMap.visibility = View.GONE

startLocationUpdates()
startRssiUpdates()

Handler(Looper.getMainLooper()).postDelayed({
gatt.discoverServices()
}, 1000)

} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
txtStatus.text = "Disconnected"

lastLat?.let {
txtLocation.text = String.format(Locale.US, "Lost near: %.4f, %.4f", lastLat, lastLng)
btnShowMap.visibility = View.VISIBLE
}

bluetoothGatt = null
rssiRunnable?.let { handler.removeCallbacks(it) }
stopLocationUpdates()
startScan()
}
}
}

@SuppressLint("MissingPermission")
override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
if (status == BluetoothGatt.GATT_SUCCESS) {
val trackerService = gatt.getService(UUID.fromString(serviceUuid))

if (trackerService == null) {
runOnUiThread { txtStatus.text = "Wrong device ignored" }
gatt.disconnect()
return
}

pendingTrackerAddress?.let { address ->
if (rememberedTrackerAddress == null) {
getSharedPreferences(prefsName, Context.MODE_PRIVATE)
.edit()
.putString(keyTrackerAddress, address)
.apply()
rememberedTrackerAddress = address
}
}

runOnUiThread { txtStatus.text = "Ready" }
}
}

override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
if (status == BluetoothGatt.GATT_SUCCESS) {
runOnUiThread {
txtStatus.text = getDistanceLabel(rssi)
}
}
}
}

@SuppressLint("MissingPermission")
private fun startScan() {
if (bluetoothAdapter?.isEnabled == true) {
val settings = ScanSettings.Builder()
.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
.build()

scanner?.startScan(null, settings, scanCallback)
txtStatus.text = if (rememberedTrackerAddress == null) {
"Searching new tracker..."
} else {
"Searching saved tracker..."
}
} else {
txtStatus.text = "Bluetooth OFF"
}
}

@SuppressLint("MissingPermission")
private fun startLocationUpdates() {
val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()

locationCallback = object : LocationCallback() {
override fun onLocationResult(result: LocationResult) {
result.lastLocation?.let {
lastLat = it.latitude
lastLng = it.longitude
}
}
}

fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
}

private fun stopLocationUpdates() {
locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
}

@SuppressLint("MissingPermission")
private fun sendCommand(cmd: String) {
val gatt = bluetoothGatt ?: return
val service = gatt.getService(UUID.fromString(serviceUuid))
val characteristic = service?.getCharacteristic(UUID.fromString(characteristicUuid))

characteristic?.let {
val data = cmd.toByteArray()
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
gatt.writeCharacteristic(it, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
} else {
it.value = data
gatt.writeCharacteristic(it)
}
}
}

private fun checkPermissionsAndStart() {
val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
permissions.add(Manifest.permission.BLUETOOTH_SCAN)
permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
}

val notGranted = permissions.filter {
ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
}

if (notGranted.isEmpty()) {
startScan()
} else {
ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1)
}
}

override fun onRequestPermissionsResult(
requestCode: Int,
permissions: Array<out String>,
grantResults: IntArray
) {
super.onRequestPermissionsResult(requestCode, permissions, grantResults)

if (requestCode == 1 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
startScan()
} else if (requestCode == 1) {
txtStatus.text = "Permission needed"
}
}

@SuppressLint("MissingPermission")
override fun onDestroy() {
super.onDestroy()
rssiRunnable?.let { handler.removeCallbacks(it) }
stopLocationUpdates()
try {
bluetoothGatt?.close()
} catch (_: Exception) {}
}
}
