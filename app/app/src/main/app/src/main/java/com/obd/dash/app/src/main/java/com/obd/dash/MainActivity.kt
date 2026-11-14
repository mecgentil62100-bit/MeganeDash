package com.obd.dash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import kotlin.math.roundToInt

private val Orange = Color(0xFFE07A10)
private val Paper = Color(0xFFF1F1EC)
private val Ink = Color(0xFF111111)
private val Ghost = Color(0x14000000)

class MainActivity : ComponentActivity() {
    private var obd by mutableStateOf(ObdData())
    private var speed by mutableStateOf<Int?>(null)
    private var devices by mutableStateOf(listOf<BluetoothDevice>())
    private var chosen by mutableStateOf(false)
    private val client = ObdClient { obd = it }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val gps = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { speed = if (it.hasSpeed()) (it.speed * 3.6f).roundToInt() else null }
        }
    }
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val need = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.launch(need.toTypedArray())
        setContent { if (chosen) Dash(obd, speed) else Picker(devices) { pick(it) } }
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        try {
            devices = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    private fun pick(d: BluetoothDevice) {
        chosen = true
        client.start(lifecycleScope, d)
        try {
            fused.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build(), gps, mainLooper
            )
        } catch (_: SecurityException) {}
    }

    override fun onDestroy() {
        client.stop(); fused.removeLocationUpdates(gps)
        super.onDestroy()
    }
}

@SuppressLint("MissingPermission")
@Composable
fun Picker(devices: List<BluetoothDevice>, onPick: (BluetoothDevice) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Paper).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Choisis ton ELM327", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (devices.isEmpty()) Text(
            "Aucun appareil appairé. Appaire l'ELM327 dans les réglages Bluetooth (code 1234 ou 0000), puis relance l'app.",
            color = Ink
        )
        devices.forEach {
            Button(onClick = { onPick(it) }, colors = ButtonDefaults.buttonColors(containerColor = Orange)) {
                Text(it.name ?: it.address)
            }
        }
    }
}

@Composable
fun Dash(obd: ObdData, speed: Int?) {
    Box(Modifier.fillMaxSize().background(Ink).padding(8.dp)) {
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Paper).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(obd.status, color = Orange, fontSize = 12.sp)
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Item("Air °C", obd.intake, 3, 56.dp)
                    Item("tr/min", obd.rpm, 4, 56.dp)
                }
                Item("Eau °C", obd.coolant, 3, 90.dp)
                Item("km/h", speed, 3, 140.dp)
            }
        }
    }
}

@Composable
fun Item(label: String, value: Int?, digits: Int, height: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Orange, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Seven(value, digits, height)
    }
}

private val SEG = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)

/** Afficheur 7 segments dessiné en Canvas (segments éteints visibles en fantôme, comme un LCD). */
@Composable
fun Seven(value: Int?, digits: Int, height: Dp) {
    Canvas(Modifier.size(height * 0.62f * digits, height)) {
        val dw = size.width / digits
        val t = size.height * 0.11f
        val txt = value?.coerceAtLeast(0)?.toString()?.padStart(digits, ' ')?.takeLast(digits)
        for (i in 0 until digits) {
            val mask = when {
                txt == null -> 0x40 // "---" quand pas de donnée
                txt[i] == ' ' -> 0
                else -> SEG[txt[i] - '0']
            }
            drawDigit(i * dw + t * 0.5f, dw - t, size.height, t, mask)
        }
    }
}

private fun DrawScope.drawDigit(x0: Float, w: Float, h: Float, t: Float, mask: Int) {
    val g = t * 0.25f
    val vh = (h - 3 * t) / 2 - 2 * g
    fun seg(x: Float, y: Float, sw: Float, sh: Float, bit: Int) =
        drawRoundRect(if (mask and bit != 0) Ink else Ghost, Offset(x, y), Size(sw, sh), CornerRadius(t / 2))
    val hw = w - 2 * t - 2 * g
    seg(x0 + t + g, 0f, hw, t, 1)                      // a
    seg(x0 + t + g, h / 2 - t / 2, hw, t, 64)          // g
    seg(x0 + t + g, h - t, hw, t, 8)                   // d
    seg(x0 + w - t, t + g, t, vh, 2)                   // b
    seg(x0 + w - t, h / 2 + t / 2 + g, t, vh, 4)       // c
    seg(x0, h / 2 + t / 2 + g, t, vh, 16)              // e
    seg(x0, t + g, t, vh, 32)                          // f
}
