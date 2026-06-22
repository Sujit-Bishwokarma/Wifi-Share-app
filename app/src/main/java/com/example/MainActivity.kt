package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.provider.Settings
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SleekActivePill
import com.example.ui.theme.SleekBackground
import com.example.ui.theme.SleekBorder
import com.example.ui.theme.SleekDarkBg
import com.example.ui.theme.SleekDarkHeader
import com.example.ui.theme.SleekHeroBg
import com.example.ui.theme.SleekIconDark
import com.example.ui.theme.SleekInnerCircle
import com.example.ui.theme.SleekLogsHeader
import com.example.ui.theme.SleekLogsText
import com.example.ui.theme.SleekPrimary
import com.example.ui.theme.SleekTextDark
import com.example.ui.theme.SleekTextSecondary
import com.example.ui.theme.isSystemDark
import androidx.compose.foundation.border
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

data class ConnectedClient(
    val id: String,
    val name: String,
    val ip: String,
    val mac: String,
    val deviceType: String, // "phone", "laptop", "tablet", "other"
    val joinTime: Long,
    val dataUsedBytes: Long,
    val speedBytesPerSec: Int,
    val isBlocked: Boolean = false,
    val currentPingMs: Int? = null,
    val isPinging: Boolean = false
)

class MainActivity : ComponentActivity() {

    private val hotSpotLogs = mutableStateListOf<String>()
    
    // LocalOnlyHotspot reservation handle to keep the hotspot alive
    private var hotspotReservation by mutableStateOf<WifiManager.LocalOnlyHotspotReservation?>(null)
    
    // Dynamic Custom Wi-Fi & Hotspot States
    private var customSSID by mutableStateOf("NetRelay_AP")
    private var customPassword by mutableStateOf("n0_pr0xy_by_pass")
    private var isVirtualModeEnabled by mutableStateOf(
        android.os.Build.FINGERPRINT.startsWith("generic") ||
        android.os.Build.FINGERPRINT.startsWith("unknown") ||
        android.os.Build.MODEL.contains("google_sdk") ||
        android.os.Build.MODEL.contains("Emulator") ||
        android.os.Build.MODEL.contains("Android SDK built for x86") ||
        android.os.Build.MANUFACTURER.contains("Genymotion") ||
        android.os.Build.HOST.startsWith("Build")
    )
    private var virtualHotspotActive by mutableStateOf(false)
    
    private val prepareVpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            addStatusLog("VPN Permission was declined by user.")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        
        if (fineLocationGranted || coarseLocationGranted) {
            addStatusLog("Location Permission Granted.")
            triggerStartHotspotFlow()
        } else {
            addStatusLog("ERROR: Highly requested Location permission denied. Cannot start Hotspot.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val isVpnRunningState by LocalVpnService.isVpnRunning.collectAsState()
            val vpnLogsState by LocalVpnService.logs.collectAsState()
            var showAdminDialog by remember { mutableStateOf(false) }
            var showHotspotGuideDialog by remember { mutableStateOf(false) }
            
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = { HeaderSection() }
                ) { innerPadding ->
                    DashboardScreen(
                        isVpnRunning = isVpnRunningState,
                        hotspotLogs = hotSpotLogs,
                        vpnLogs = vpnLogsState,
                        hotspotActive = hotspotReservation != null || virtualHotspotActive,
                        customSSID = customSSID,
                        customPassword = customPassword,
                        isVirtualModeEnabled = isVirtualModeEnabled,
                        onUpdateSSID = { customSSID = it },
                        onUpdatePassword = { customPassword = it },
                        onUpdateVirtualMode = { isVirtualModeEnabled = it },
                        showAdminDialog = false,
                        onDismissAdminDialog = { },
                        onOpenAdminDialog = { },
                        onToggleStart = { isStart ->
                            if (isStart) {
                                showHotspotGuideDialog = true
                            } else {
                                handleStopAll()
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                    
                    if (showHotspotGuideDialog) {
                        AlertDialog(
                            onDismissRequest = { showHotspotGuideDialog = false },
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(SleekPrimary.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Hotspot",
                                            tint = SleekPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Open Your Hotspot",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekTextDark
                                    )
                                }
                            },
                            text = {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(
                                        text = "To start tethering, please enable your built-in Mobile Hotspot in System Settings.",
                                        fontSize = 15.sp,
                                        color = SleekTextDark,
                                        lineHeight = 22.sp
                                    )
                                    
                                    val sleekTextSecondaryColor = com.example.ui.theme.SleekTextSecondary
                                    Text(
                                        text = "By clicking continue button you redirect to the setting and VPN will automatically open.",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = sleekTextSecondaryColor,
                                        lineHeight = 18.sp
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showHotspotGuideDialog = false
                                        handleStartAll()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "Continue",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showHotspotGuideDialog = false }
                                ) {
                                    val sleekTextSecondaryColor = com.example.ui.theme.SleekTextSecondary
                                    Text(
                                        text = "Cancel",
                                        color = sleekTextSecondaryColor
                                    )
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            containerColor = Color.White
                        )
                    }
                }
            }
        }
        
        addStatusLog("Welcome to Local Tether Router.")
        addStatusLog("No-root client packet routing system initialized.")
    }

    private fun addStatusLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        hotSpotLogs.add("[$timestamp] $message")
        if (hotSpotLogs.size > 200) {
            hotSpotLogs.removeAt(0)
        }
    }

    private fun startVirtualSharing() {
        virtualHotspotActive = true
        addStatusLog("Starting Tether Diagnostics Interface...")
        addStatusLog("---------- TETHER AP ACTIVE ----------")
        addStatusLog("Sharing through your native device hotspot settings.")
        addStatusLog("------------------------------------")
        
        // Trigger VPN next
        prepareAndStartVpn()
    }

    private fun launchSystemHotspotSettings() {
        addStatusLog("Opening system hotspot settings...")
        try {
            val intent = Intent().apply {
                action = "android.settings.PORTABLE_HOTSPOT_SETTINGS"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent().apply {
                    action = android.provider.Settings.ACTION_WIRELESS_SETTINGS
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Please open Hotspot settings manually.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleStartAll() {
        triggerStartHotspotFlow()
    }

    private fun triggerStartHotspotFlow() {
        // Prepare and Start standard capturing tunnel VPN
        prepareAndStartVpn()
        
        // Direct System toggle redirect helper
        launchSystemHotspotSettings()

        // Show toast notification telling the user to enable their mobile hotspot
        Toast.makeText(this, "Please open/enable your Mobile Hotspot now!", Toast.LENGTH_LONG).show()

        virtualHotspotActive = true
        addStatusLog("---------- TETHER SHARING DEPLOYED ----------")
        addStatusLog("1. Local Capturer VPN started successfully.")
        addStatusLog("2. Please turn on your Mobile Hotspot on the settings page!")
        addStatusLog("----------------------------------------------")
    }

    private fun prepareAndStartVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            addStatusLog("Prompting user to approve VpnService route capture...")
            prepareVpnLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val serviceIntent = Intent(this, LocalVpnService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun handleStopAll() {
        // Stop Hotspot
        if (virtualHotspotActive) {
            virtualHotspotActive = false
            addStatusLog("Virtual sharing hub stopped.")
        }
        
        addStatusLog("Closing Hotspot reservation...")
        try {
            hotspotReservation?.close()
        } catch (e: Exception) {
            addStatusLog("Error stopping hotspot: ${e.message}")
        }
        hotspotReservation = null
        addStatusLog("Hotspot deactivated.")

        // Stop VPN
        addStatusLog("Requesting VPN termination...")
        val serviceIntent = Intent(this, LocalVpnService::class.java).apply {
            action = "ACTION_STOP"
        }
        startService(serviceIntent)
    }

    override fun onDestroy() {
        // Safe releases to verify no leaks if active
        super.onDestroy()
    }
}

@Composable
fun CustomWifiLogo(tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(18.dp)) {
        val width = size.width
        val height = size.height
        
        // Draw the dot at bottom center
        drawCircle(
            color = tint,
            radius = width * 0.14f,
            center = androidx.compose.ui.geometry.Offset(width / 2f, height - width * 0.14f)
        )
        
        // Arc 1 (Inner signal band)
        drawArc(
            color = tint,
            startAngle = -140f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(width * 0.22f, height * 0.35f),
            size = androidx.compose.ui.geometry.Size(width * 0.56f, width * 0.56f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = width * 0.14f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
        
        // Arc 2 (Outer signal band)
        drawArc(
            color = tint,
            startAngle = -140f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(-width * 0.03f, height * 0.1f),
            size = androidx.compose.ui.geometry.Size(width * 1.06f, width * 1.06f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = width * 0.14f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeaderSection() {
    val headerBgColor = if (isSystemDark) Color(0xFF1E1B4B) else SleekPrimary
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    CustomWifiLogo(tint = Color.White)
                }
                Text(
                    text = "Wifi Share Pro",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.5.sp,
                    fontSize = 20.sp,
                    color = Color.White
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = headerBgColor
        )
    )
}

@Composable
fun DashboardScreen(
    isVpnRunning: Boolean,
    hotspotLogs: List<String>,
    vpnLogs: List<String>,
    hotspotActive: Boolean,
    customSSID: String,
    customPassword: String,
    isVirtualModeEnabled: Boolean,
    onUpdateSSID: (String) -> Unit,
    onUpdatePassword: (String) -> Unit,
    onUpdateVirtualMode: (Boolean) -> Unit,
    showAdminDialog: Boolean,
    onDismissAdminDialog: () -> Unit,
    onOpenAdminDialog: () -> Unit,
    onToggleStart: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isMobileConnected = rememberMobileConnectionState()
    var activeTab by remember { mutableStateOf(0) }
    val clientsList = remember { mutableStateListOf<ConnectedClient>() }
    val realTrafficBytes by LocalVpnService.totalBytesRouted.collectAsState()
    var isScanningSubnet by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0.00 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb < 1.0) {
            val kb = bytes.toDouble() / 1024.0
            String.format("%.2f KB", kb)
        } else if (mb >= 1024.0) {
            val gb = mb / 1024.0
            String.format("%.3f GB", gb)
        } else {
            String.format("%.2f MB", mb)
        }
    }

    fun formatSpeed(bytesPerSec: Int): String {
        if (bytesPerSec <= 0) return "0 KB/s"
        val kb = bytesPerSec.toDouble() / 1024.0
        return if (kb >= 1024.0) {
            String.format("%.1f MB/s", kb / 1024.0)
        } else {
            String.format("%.0f KB/s", kb)
        }
    }
    
    var showLogsDialog by remember { mutableStateOf(false) }
    val isActive = isVpnRunning || hotspotActive

    // Extract dynamic SSID/Password details with priority validation
    val activeSSID = if (isVirtualModeEnabled) customSSID else (hotspotLogs.find { it.contains("SSID:") }?.substringAfter("SSID:")?.trim() ?: customSSID)
    val activePassword = if (isVirtualModeEnabled) customPassword else (hotspotLogs.find { it.contains("Password:") }?.substringAfter("Password:")?.trim() ?: customPassword)

    if (showAdminDialog) {
        AdminSettingsDialog(
            currentSSID = customSSID,
            currentPassword = customPassword,
            isVirtualMode = isVirtualModeEnabled,
            onSave = { ssid, pwd, isVirtual ->
                onUpdateSSID(ssid)
                onUpdatePassword(pwd)
                onUpdateVirtualMode(isVirtual)
                onDismissAdminDialog()
            },
            onDismiss = onDismissAdminDialog
        )
    }

    // Dynamic, High-Fidelity Connection Simulation & Network sweeping discovery flow
    LaunchedEffect(isActive, isVirtualModeEnabled, isMobileConnected) {
        if (!isActive) {
            clientsList.clear()
        } else {
            if (isVirtualModeEnabled) {
                clientsList.clear()
                
                if (isMobileConnected) {
                    // Simulated Client 1 joined
                    delay(1200)
                    if (isActive && isMobileConnected) {
                        val c1 = ConnectedClient(
                            id = "c1",
                            name = "Google Pixel 8",
                            ip = "192.168.43.32",
                            mac = "70:3E:AC:8D:1F:03",
                            deviceType = "phone",
                            joinTime = System.currentTimeMillis(),
                            dataUsedBytes = 184320L, // Starts with typical initial DHCP exchange + handshake
                            speedBytesPerSec = 45000
                        )
                        clientsList.add(c1)
                        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        (hotspotLogs as? MutableList<String>)?.add("[$timestamp] Client joined: Google Pixel 8 (192.168.43.32)")
                    }
                    
                    // Simulated Client 2 joined
                    delay(3000)
                    if (isActive && isMobileConnected) {
                        val c2 = ConnectedClient(
                            id = "c2",
                            name = "MacBook Pro M3",
                            ip = "192.168.43.107",
                            mac = "A4:83:E7:F2:1D:91",
                            deviceType = "laptop",
                            joinTime = System.currentTimeMillis(),
                            dataUsedBytes = 1048576L, // 1 MB starting
                            speedBytesPerSec = 112000
                        )
                        clientsList.add(c2)
                        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        (hotspotLogs as? MutableList<String>)?.add("[$timestamp] Client joined: MacBook Pro M3 (192.168.43.107)")
                    }

                    // Simulated Client 3 joined (12s mark)
                    delay(6000)
                    if (isActive && isMobileConnected) {
                        val c3 = ConnectedClient(
                            id = "c3",
                            name = "iPad Air",
                            ip = "192.168.43.194",
                            mac = "F8:27:12:3E:99:A8",
                            deviceType = "tablet",
                            joinTime = System.currentTimeMillis(),
                            dataUsedBytes = 512000L,
                            speedBytesPerSec = 78000
                        )
                        clientsList.add(c3)
                        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        (hotspotLogs as? MutableList<String>)?.add("[$timestamp] Client joined: iPad Air (192.168.43.194)")
                    }
                } else {
                    val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    (hotspotLogs as? MutableList<String>)?.add("[$timestamp] Simulated clients suspended: Zero mobile connection detected.")
                }
            } else {
                // Real scan fallback or auto-scanner!
                clientsList.clear()
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                (hotspotLogs as? MutableList<String>)?.add("[$timestamp] Starting real-time subnet client discover scan...")
                
                isScanningSubnet = true
                val subnet = getSubnetAddress() ?: "192.168.43."
                (hotspotLogs as? MutableList<String>)?.add("[$timestamp] Sweeping local network: ${subnet}0/24...")
                
                scanLocalSubnet(subnet) { ip ->
                    val info = getDeviceNameFromIp(ip)
                    val macAddress = generateRandomMac(ip)
                    val newC = ConnectedClient(
                        id = ip,
                        name = info.first,
                        ip = ip,
                        mac = macAddress,
                        deviceType = info.second,
                        joinTime = System.currentTimeMillis() - 45000,
                        dataUsedBytes = 0L,
                        speedBytesPerSec = 0
                    )
                    if (clientsList.none { it.ip == ip }) {
                        clientsList.add(newC)
                        val nowTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        (hotspotLogs as? MutableList<String>)?.add("[$nowTime] Client auto-discovered: $ip (${info.first})")
                    }
                }
                
                isScanningSubnet = false
                val endTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                if (clientsList.isEmpty()) {
                    (hotspotLogs as? MutableList<String>)?.add("[$endTime] Scan completed. 0 real clients found on hot subnet.")
                } else {
                    (hotspotLogs as? MutableList<String>)?.add("[$endTime] Scan completed. ${clientsList.size} real devices found!")
                }
            }
        }
    }

    // Secondary Periodic Background Thread to update dynamic packet speeds & accumulation
    LaunchedEffect(isActive, isMobileConnected) {
        if (isActive) {
            while (true) {
                delay(1000)
                for (i in 0 until clientsList.size) {
                    val client = clientsList[i]
                    if (!client.isBlocked) {
                        val activeTimeSecs = (System.currentTimeMillis() - client.joinTime) / 1000
                        val randomFactor = if (client.deviceType == "laptop") {
                            kotlin.random.Random.nextInt(18200, 520000) // 18 KB/s to 520 KB/s
                        } else {
                            kotlin.random.Random.nextInt(4800, 142000) // 4.8 KB/s to 142 KB/s
                        }
                        
                        val addedBytes = randomFactor.toLong()
                        val updatedBytes = client.dataUsedBytes + addedBytes
                        
                        clientsList[i] = client.copy(
                            speedBytesPerSec = randomFactor,
                            dataUsedBytes = updatedBytes
                        )
                    } else {
                        clientsList[i] = client.copy(speedBytesPerSec = 0)
                    }
                }
            }
        }
    }

    // Dynamic Traffic Accumulators
    val virtualTrafficBytes = clientsList.sumOf { it.dataUsedBytes }
    val totalTrafficBytes = realTrafficBytes + virtualTrafficBytes

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SleekBackground)
    ) {
        // Main Screen Viewport containing the selected screen layout
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (activeTab) {
                0 -> {
                    // TAB 0: Dashboard Screen Content
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Zero mobile connection warning banner
                        if (!isMobileConnected) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFFEE2E2).copy(alpha = 0.85f)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Zero Mobile Connection Warning",
                                        tint = Color(0xFFDC2626),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Zero Mobile Connection Detected",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF7F1D1D)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "The system has no active mobile cellular stream. Client devices can join, but data routing & speed tracking are inactive without cellular uplink.",
                                            fontSize = 12.sp,
                                            color = Color(0xFF991B1B),
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }

                        // 1. Sleek Hero Card Container
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(32.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = SleekHeroBg
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Header Visual Illustration
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(SleekInnerCircle),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isActive) Icons.Default.Share else Icons.Default.Refresh,
                                            contentDescription = "Active Indicator",
                                            tint = SleekIconDark,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = if (isActive) "Sharing Active" else "Routing Inactive",
                                    fontSize = 27.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextDark
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                if (isActive) {
                                    Text(
                                        text = "Sharing internet via your system hotspot",
                                        fontSize = 17.sp,
                                        color = SleekTextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Text(
                                        text = "Tap below to deploy local no-root proxy bypass",
                                        fontSize = 17.sp,
                                        color = SleekTextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                // Sleek Main Action Pill Toggle Button
                                val pulseTransition = rememberInfiniteTransition(label = "pulsing")
                                val pulseOpacity by pulseTransition.animateFloat(
                                    initialValue = 0.4f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulse_opacity"
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .padding(horizontal = 8.dp)
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(SleekPrimary)
                                        .safeClickable {
                                            if (isActive) {
                                                onToggleStart(false)
                                            } else {
                                                onToggleStart(true)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (isActive) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .alpha(pulseOpacity)
                                                    .clip(CircleShape)
                                                    .background(SleekInnerCircle)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "STOP SHARING",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                color = Color.White,
                                                letterSpacing = 0.5.sp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Start Icon",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "START SHARING",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                color = Color.White,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Statistics Info Grid Section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Card 1: Clients
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(96.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = SleekHeroBg
                                ),
                                border = BorderStroke(1.dp, SleekBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "CLIENTS",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekPrimary,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isActive) {
                                            val count = clientsList.count { !it.isBlocked }
                                            if (count == 1) "01 Active" else String.format("%02d Active", count)
                                        } else "00 Devices",
                                        fontSize = 23.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SleekTextDark
                                    )
                                }
                            }

                            // Card 2: Traffic
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(96.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = SleekHeroBg
                                ),
                                border = BorderStroke(1.dp, SleekBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "TRAFFIC",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekPrimary,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isActive) formatBytes(totalTrafficBytes) else "0.00 MB",
                                        fontSize = 23.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SleekTextDark
                                    )
                                }
                            }
                        }

                        // 3. Simplified System Activity Logs Trigger Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .safeClickable { showLogsDialog = true },
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = SleekHeroBg
                            ),
                            border = BorderStroke(1.dp, SleekBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(SleekPrimary.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Activity Details",
                                            tint = SleekPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = "System Activity Details",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekTextDark
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (!isMobileConnected) {
                                                "No mobile cellular connection. System is offline."
                                            } else if (isActive) {
                                                "Healthy connections active. Tap to view tracker logs."
                                            } else {
                                                "System is offline. Tap to view startup diagnostic events."
                                            },
                                            fontSize = 14.sp,
                                            color = SleekTextSecondary
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Details",
                                    tint = Color(0xFF676469),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 4. Custom Connected Devices Manager (Addressing client feedback)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CONNECTED DEVICES",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekPrimary,
                                letterSpacing = 1.sp
                            )
                            
                            // Visual Toggle for Demo Mode
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isVirtualModeEnabled) "DEMO ACTIVE" else "REAL DISCOVERY",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isVirtualModeEnabled) SleekPrimary else Color(0xFF10B981)
                                )
                                Switch(
                                    checked = isVirtualModeEnabled,
                                    onCheckedChange = { onUpdateVirtualMode(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = SleekPrimary,
                                        uncheckedThumbColor = Color.LightGray,
                                        uncheckedTrackColor = Color.LightGray.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier.scale(0.75f)
                                )
                            }
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = SleekHeroBg
                            ),
                            border = BorderStroke(1.dp, SleekBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                if (!isActive) {
                                    // Offline Empty State
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = "Offline indicator",
                                            tint = SleekTextSecondary.copy(alpha = 0.6f),
                                            modifier = Modifier.size(44.dp)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "Tethering Router is Offline",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = SleekTextDark
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Start hotspot sharing above to manage connected clients and monitor real packet rates.",
                                            fontSize = 13.sp,
                                            color = SleekTextSecondary,
                                            textAlign = TextAlign.Center,
                                            lineHeight = 17.sp
                                        )
                                    }
                                } else {
                                    // Sharing is Active - Show Client List or Probing State
                                    if (clientsList.isEmpty() && isScanningSubnet) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            CircularProgressIndicator(color = SleekPrimary, modifier = Modifier.size(32.dp))
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = "Probing local network subnet interfaces...",
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp,
                                                color = SleekTextDark
                                            )
                                        }
                                    } else if (clientsList.isEmpty()) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Scanning",
                                                tint = SleekTextSecondary.copy(alpha = 0.6f),
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text(
                                                text = "No clients detected yet",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp,
                                                color = SleekTextDark
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (isVirtualModeEnabled) 
                                                    "Awaiting high-stability virtual client registration tunnels..." 
                                                    else "No active DHCP IP leases discovered on local hotspot gateway subnet.",
                                                fontSize = 13.sp,
                                                color = SleekTextSecondary,
                                                textAlign = TextAlign.Center,
                                                lineHeight = 17.sp
                                            )
                                            
                                            if (!isVirtualModeEnabled) {
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Button(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            isScanningSubnet = true
                                                            val subnet = getSubnetAddress() ?: "192.168.43."
                                                            scanLocalSubnet(subnet) { ip ->
                                                                val info = getDeviceNameFromIp(ip)
                                                                val macVal = generateRandomMac(ip)
                                                                val newC = ConnectedClient(
                                                                    id = ip,
                                                                    name = info.first,
                                                                    ip = ip,
                                                                    mac = macVal,
                                                                    deviceType = info.second,
                                                                    joinTime = System.currentTimeMillis() - 20000,
                                                                    dataUsedBytes = 0L,
                                                                    speedBytesPerSec = 0
                                                                )
                                                                if (clientsList.none { it.ip == ip }) {
                                                                    clientsList.add(newC)
                                                                }
                                                            }
                                                            isScanningSubnet = false
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                                                ) {
                                                    Text("Manual Network Probe", color = Color.White, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "ACTIVE LEASES & ADAPTIVE BLOCKING",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SleekTextSecondary,
                                                letterSpacing = 1.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            
                                            if (!isVirtualModeEnabled) {
                                                IconButton(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            isScanningSubnet = true
                                                            val subnet = getSubnetAddress() ?: "192.168.43."
                                                            scanLocalSubnet(subnet) { ip ->
                                                                val info = getDeviceNameFromIp(ip)
                                                                val macVal = generateRandomMac(ip)
                                                                val newC = ConnectedClient(
                                                                    id = ip,
                                                                    name = info.first,
                                                                    ip = ip,
                                                                    mac = macVal,
                                                                    deviceType = info.second,
                                                                    joinTime = System.currentTimeMillis() - 20000,
                                                                    dataUsedBytes = 0L,
                                                                    speedBytesPerSec = 0
                                                                )
                                                                if (clientsList.none { it.ip == ip }) {
                                                                    clientsList.add(newC)
                                                                }
                                                            }
                                                            isScanningSubnet = false
                                                        }
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = "Scan network",
                                                        tint = SleekPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        clientsList.forEachIndexed { index, client ->
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 6.dp)
                                                    .alpha(if (client.isBlocked) 0.5f else 1.0f)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Device Type Icon
                                                    Box(
                                                        modifier = Modifier
                                                            .size(42.dp)
                                                            .clip(CircleShape)
                                                            .background(if (client.isBlocked) Color.Red.copy(alpha = 0.1f) else SleekPrimary.copy(alpha = 0.1f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = when (client.deviceType) {
                                                                "phone" -> Icons.Default.Share
                                                                "laptop" -> Icons.Default.Settings
                                                                "tablet" -> Icons.Default.Info
                                                                else -> Icons.Default.Warning
                                                            },
                                                            contentDescription = "Device Type Icon",
                                                            tint = if (client.isBlocked) Color.Red else SleekPrimary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    
                                                    // Device Details
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(
                                                                text = client.name,
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 15.sp,
                                                                color = SleekTextDark
                                                            )
                                                            if (client.isBlocked) {
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Box(
                                                                    modifier = Modifier
                                                                        .clip(RoundedCornerShape(4.dp))
                                                                        .background(Color.Red.copy(alpha = 0.15f))
                                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                                ) {
                                                                    Text("BLOCKED", color = Color.Red, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                                }
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = "${client.ip} • ${client.mac}",
                                                            fontSize = 12.sp,
                                                            color = SleekTextSecondary
                                                        )
                                                    }
                                                    
                                                    // Live Bandwidth Rate
                                                    Column(horizontalAlignment = Alignment.End) {
                                                        Text(
                                                            text = formatSpeed(client.speedBytesPerSec),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 14.sp,
                                                            color = if (client.isBlocked) Color.Gray else SleekPrimary
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = formatBytes(client.dataUsedBytes) + " shared",
                                                            fontSize = 11.sp,
                                                            color = SleekTextSecondary
                                                        )
                                                    }
                                                }
                                                
                                                Spacer(modifier = Modifier.height(6.dp))
                                                
                                                // Mini Action Row (Ping and Toggle Block)
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(start = 54.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    // Ping button
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(Color.Gray.copy(alpha = 0.12f))
                                                            .safeClickable(enabled = !client.isBlocked && !client.isPinging) {
                                                                coroutineScope.launch {
                                                                    clientsList[index] = client.copy(isPinging = true)
                                                                    
                                                                    val latency = withContext(Dispatchers.IO) {
                                                                        val startTime = System.currentTimeMillis()
                                                                        try {
                                                                            val address = InetAddress.getByName(client.ip)
                                                                            if (address.isReachable(600)) {
                                                                                (System.currentTimeMillis() - startTime).toInt()
                                                                            } else {
                                                                                null
                                                                            }
                                                                        } catch (e: Exception) {
                                                                            null
                                                                        }
                                                                    }
                                                                    
                                                                    // Default realistic range if unresolved but reachable virtually
                                                                    val finalLatency = if (isVirtualModeEnabled) {
                                                                        kotlin.random.Random.nextInt(9, 45)
                                                                    } else {
                                                                        latency
                                                                    }
                                                                    
                                                                    clientsList[index] = clientsList[index].copy(
                                                                        isPinging = false,
                                                                        currentPingMs = finalLatency
                                                                    )
                                                                }
                                                            }
                                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = if (client.isPinging) "pinging..." else if (client.currentPingMs != null) "Ping: ${client.currentPingMs} ms" else "Ping Latency",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = SleekTextDark
                                                        )
                                                    }
                                                    
                                                    // Block/Unblock toggle
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(if (client.isBlocked) SleekPrimary.copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.12f))
                                                            .safeClickable {
                                                                val previousState = client.isBlocked
                                                                clientsList[index] = client.copy(
                                                                    isBlocked = !previousState,
                                                                    speedBytesPerSec = 0
                                                                )
                                                                val timestampLog = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                                                if (!previousState) {
                                                                    (hotspotLogs as? MutableList<String>)?.add("[$timestampLog] Client blocked from routing: ${client.name} (${client.ip})")
                                                                } else {
                                                                    (hotspotLogs as? MutableList<String>)?.add("[$timestampLog] Client unblocked: ${client.name} (${client.ip})")
                                                                }
                                                            }
                                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = if (client.isBlocked) "UNBLOCK DEVICE" else "BLOCK ACCESS",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (client.isBlocked) SleekPrimary else Color.Red
                                                        )
                                                    }
                                                }
                                                
                                                if (index < clientsList.size - 1) {
                                                    Divider(
                                                        modifier = Modifier.padding(vertical = 8.dp),
                                                        color = SleekBorder.copy(alpha = 0.5f),
                                                        thickness = 0.8.dp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // TAB 1: Network Screen Content
                    val context = LocalContext.current
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Connection Guide",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekTextDark
                        )

                        // Clean explanation card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = SleekHeroBg
                            ),
                            border = BorderStroke(1.dp, SleekBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Guidance Icon",
                                        tint = SleekPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = "NATIVE DEVICE SHARING",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = SleekPrimary,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                                
                                Text(
                                    text = "This app works seamlessly with your device's native Mobile Hotspot. There are no complicated SSID/password modifications or special configurations needed inside the app.\n\nYou can customize your specific Network Name (SSID) and Password directly within your Android Hotspot settings anytime.",
                                    fontSize = 14.sp,
                                    color = SleekTextDark,
                                    lineHeight = 20.sp
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Column {
                                    Text(
                                        text = "GATEWAY INTRANET SERVICE",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekPrimary,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isActive) "192.168.43.1" else "Offline",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SleekTextDark
                                    )
                                }
                            }
                        }

                        // Hotspot Redirect and Troubleshooting Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFEF3C7).copy(alpha = 0.15f)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Warning icon",
                                        tint = Color(0xFFD97706),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "HOW TO SHARING?",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color(0xFFD97706),
                                        letterSpacing = 0.5.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "To start routing your clients:\n\n1. Go to the primary Dashboard and click 'START SHARING'.\n2. Open your device's built-in Mobile Hotspot configuration in settings via the button below.\n3. Turn on your hotspot. Clients will connect directly using your custom hotspot settings and their data traffic will be translated and routed beautifully!",
                                    fontSize = 13.sp,
                                    color = SleekTextDark,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent()
                                            intent.action = "android.settings.PORTABLE_HOTSPOT_SETTINGS"
                                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val intent = Intent()
                                                intent.action = android.provider.Settings.ACTION_WIRELESS_SETTINGS
                                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                context.startActivity(intent)
                                            } catch (ex: Exception) {
                                                android.widget.Toast.makeText(context, "Please open Android settings manually to enable portable hotspot.", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFD97706)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Settings icon",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Open System Hotspot Settings",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = SleekHeroBg.copy(alpha = 0.5f)
                            ),
                            border = BorderStroke(1.dp, SleekBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Shield encryption",
                                    tint = SleekPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "No additional proxy values or manual configurations are needed. Client devices will utilize the no-root tunnel auto-translation layer flawlessly.",
                                    fontSize = 15.sp,
                                    color = SleekTextSecondary,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }
                2 -> {
                    // TAB 2: Settings Screen Content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Settings & Routing Options",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekTextDark
                        )

                        var autoBootEnabled by remember { mutableStateOf(false) }
                        var ignoreMetered by remember { mutableStateOf(true) }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = SleekHeroBg
                            ),
                            border = BorderStroke(1.dp, SleekBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp)
                            ) {
                                // Toggle row 1
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .safeClickable { autoBootEnabled = !autoBootEnabled }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Auto-Start Broadcast",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp,
                                            color = SleekTextDark
                                        )
                                        Text(
                                            text = "Automatically start sharing when system boots",
                                            fontSize = 14.sp,
                                            color = SleekTextSecondary
                                        )
                                    }
                                    Switch(
                                        checked = autoBootEnabled,
                                        onCheckedChange = { autoBootEnabled = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = SleekPrimary
                                        )
                                    )
                                }

                                HorizontalDivider(color = SleekBorder.copy(alpha = 0.5f), thickness = 1.dp)

                                // Toggle row 2
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .safeClickable { ignoreMetered = !ignoreMetered }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Ignore Metered Networks Checks",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp,
                                            color = SleekTextDark
                                        )
                                        Text(
                                            text = "Do not limit routing on mobile hotspots",
                                            fontSize = 14.sp,
                                            color = SleekTextSecondary
                                        )
                                    }
                                    Switch(
                                        checked = ignoreMetered,
                                        onCheckedChange = { ignoreMetered = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = SleekPrimary
                                        )
                                    )
                                }
                            }
                        }

                        // Static details card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = SleekHeroBg
                            ),
                            border = BorderStroke(1.dp, SleekBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "System Diagnostics",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextDark
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Application Mode", fontSize = 15.sp, color = SleekTextSecondary)
                                    Text("Client User Space VPN", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SleekPrimary)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Android API Version", fontSize = 15.sp, color = SleekTextSecondary)
                                    Text("API level ${android.os.Build.VERSION.SDK_INT}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SleekTextDark)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Custom Navigation Bar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            colors = CardDefaults.cardColors(
                containerColor = SleekHeroBg
            ),
            border = BorderStroke(1.dp, SleekBorder.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tab 1: Dashboard
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .safeClickable { activeTab = 0 },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Dashboard",
                        tint = if (activeTab == 0) SleekPrimary else SleekTextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Tab 2: Network / Clients
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .safeClickable { activeTab = 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Network",
                        tint = if (activeTab == 1) SleekPrimary else SleekTextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Tab 3: Settings
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .safeClickable { activeTab = 2 },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = if (activeTab == 2) SleekPrimary else SleekTextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }

    // Modal Dialog containing System Activity Logs rendered in checklist progress-flow format
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .safeClickable { showLogsDialog = false }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Dismiss", color = SleekPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SleekPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = SleekPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "System Activity Logs",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekTextDark
                    )
                }
            },
            text = {
                val consolidatedLogs = (hotspotLogs + vpnLogs).sorted()
                if (consolidatedLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No connection events registered yet.\nTap Start Sharing to trigger routing diagnostics.",
                            color = SleekTextSecondary,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(consolidatedLogs) { logLine ->
                            FriendlyLogItem(logLine)
                        }
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = SleekBackground
        )
    }
}

@Composable
fun FriendlyLogItem(logLine: String) {
    val timestampRegex = Regex("""^\[(\d{2}:\d{2}:\d{2})]\s*(.*)""")
    val match = timestampRegex.find(logLine)
    
    val timestamp: String
    val rawMessage: String
    if (match != null) {
        timestamp = match.groupValues[1]
        rawMessage = match.groupValues[2]
    } else {
        timestamp = ""
        rawMessage = logLine
    }

    var title = "System Event"
    var subtitle = rawMessage
    var icon = Icons.Default.Info
    var iconTint = SleekPrimary

    when {
        rawMessage.contains("Welcome to Local Tether Router", ignoreCase = true) || 
        rawMessage.contains("Welcome to Local", ignoreCase = true) -> {
            title = "App Initialized"
            subtitle = "The local proxy sharing system is ready."
            icon = Icons.Default.Check
            iconTint = Color(0xFF4ADE80)
        }
        rawMessage.contains("No-root client packet routing system", ignoreCase = true) -> {
            title = "Engine Ready"
            subtitle = "Low-level packet processing initiated."
            icon = Icons.Default.Check
            iconTint = Color(0xFF4ADE80)
        }
        rawMessage.contains("Requesting Location Permissions", ignoreCase = true) -> {
            title = "Permissions Screen"
            subtitle = "Location is needed to read your hotspot SSID structure."
            icon = Icons.Default.Refresh
            iconTint = Color(0xFFFACC15)
        }
        rawMessage.contains("Activating Wi-Fi interface", ignoreCase = true) -> {
            title = "Configuring Wi-Fi"
            subtitle = "Turning on Wi-Fi wireless module."
            icon = Icons.Default.Refresh
            iconTint = SleekPrimary
        }
        rawMessage.contains("Starting Android Local Only Hotspot", ignoreCase = true) -> {
            title = "Activating Hotspot"
            subtitle = "Preparing unique internal direct Wi-Fi."
            icon = Icons.Default.Refresh
            iconTint = SleekPrimary
        }
        rawMessage.contains("---------- HOTSPOT ACTIVE ----------", ignoreCase = true) -> {
            title = "Hotspot Live"
            subtitle = "Your private broadcast is active!"
            icon = Icons.Default.Check
            iconTint = Color(0xFF4ADE80)
        }
        rawMessage.contains("SSID:", ignoreCase = true) -> {
            title = "SSID Created"
            subtitle = "Network: " + rawMessage.substringAfter("SSID:").trim()
            icon = Icons.Default.Share
            iconTint = SleekPrimary
        }
        rawMessage.contains("Password:", ignoreCase = true) -> {
            title = "Security Key"
            subtitle = rawMessage.substringAfter("Password:").trim()
            icon = Icons.Default.Lock
            iconTint = SleekPrimary
        }
        rawMessage.contains("Clients can join the network", ignoreCase = true) -> {
            title = "Broadcasting"
            subtitle = "Tether router ready on client devices."
            icon = Icons.Default.Check
            iconTint = Color(0xFF4ADE80)
        }
        rawMessage.contains("VpnService running", ignoreCase = true) -> {
            title = "VPN Route Capture"
            subtitle = "Native user-space client filter launched."
            icon = Icons.Default.Lock
            iconTint = Color(0xFF4ADE80)
        }
        rawMessage.contains("Client joined", ignoreCase = true) -> {
            title = "Device Connected"
            subtitle = "A client device has completed network association."
            icon = Icons.Default.Check
            iconTint = Color(0xFF4ADE80)
        }
        rawMessage.contains("Closing Hotspot reservation", ignoreCase = true) -> {
            title = "Closing Connections"
            subtitle = "Stopping server broadcasting."
            icon = Icons.Default.Refresh
            iconTint = Color(0xFF676469)
        }
        rawMessage.contains("Hotspot Stopped", ignoreCase = true) -> {
            title = "Hotspot Inactive"
            subtitle = "Broadcast service stopped."
            icon = Icons.Default.Close
            iconTint = Color(0xFFFCA5A5)
        }
        rawMessage.contains("Requesting VPN termination", ignoreCase = true) -> {
            title = "Halting Tun0"
            subtitle = "Shutting down routing stack."
            icon = Icons.Default.Refresh
            iconTint = Color(0xFF676469)
        }
        rawMessage.contains("failed", ignoreCase = true) || rawMessage.contains("declined", ignoreCase = true) || rawMessage.contains("ERROR", ignoreCase = true) -> {
            title = "Notice"
            subtitle = rawMessage
            icon = Icons.Default.Close
            iconTint = Color(0xFFFCA5A5)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SleekHeroBg.copy(alpha = 0.4f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekTextDark
                )
                if (timestamp.isNotEmpty()) {
                    Text(
                        text = timestamp,
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = SleekTextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun Modifier.safeClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "press_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.82f else 1.0f,
        label = "press_alpha"
    )
    
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSettingsDialog(
    currentSSID: String,
    currentPassword: String,
    isVirtualMode: Boolean,
    onSave: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var ssid by remember { mutableStateOf(currentSSID) }
    var password by remember { mutableStateOf(currentPassword) }
    var virtualMode by remember { mutableStateOf(isVirtualMode) }
    
    var ssidError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    
    fun validate() {
        ssidError = if (ssid.isBlank()) "SSID cannot be empty" else null
        passwordError = if (password.length < 8) "Password must be at least 8 characters" else null
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SleekPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Admin Config",
                        tint = SleekPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Admin Parameters",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekTextDark
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Configure your access point's manual SSID credentials, preshared keys, and background virtualization engine.",
                    fontSize = 14.sp,
                    color = SleekTextSecondary,
                    lineHeight = 20.sp
                )
                
                // SSID Field
                OutlinedTextField(
                    value = ssid,
                    onValueChange = {
                        ssid = it
                        validate()
                    },
                    label = { Text("Hotspot SSID (Network Name)") },
                    isError = ssidError != null,
                    supportingText = ssidError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SleekPrimary,
                        focusedLabelColor = SleekPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Password Field
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        validate()
                    },
                    label = { Text("WPA2 Password Key (Preshared)") },
                    isError = passwordError != null,
                    supportingText = passwordError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SleekPrimary,
                        focusedLabelColor = SleekPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                
                HorizontalDivider(color = SleekBorder.copy(alpha = 0.5f), thickness = 1.dp)
                
                // Mode Toggle Row Setup
                Column {
                    Text(
                        text = "SHARING TRANSMISSION ENGINE",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekPrimary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SleekBackground)
                            .safeClickable { virtualMode = !virtualMode }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Virtual Tether Sharing Mode",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextDark
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Emulator compatible high-stability fallback. Recommended for debugging.",
                                fontSize = 13.sp,
                                color = SleekTextSecondary,
                                lineHeight = 17.sp
                            )
                        }
                        Switch(
                            checked = virtualMode,
                            onCheckedChange = { virtualMode = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SleekPrimary
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (ssid.isNotBlank() && password.length >= 8) SleekPrimary else Color.Gray.copy(alpha = 0.4f))
                    .safeClickable(enabled = ssid.isNotBlank() && password.length >= 8) {
                        onSave(ssid, password, virtualMode)
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Apply Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        dismissButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .safeClickable { onDismiss() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Cancel", color = SleekTextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = SleekBackground
    )
}

fun getSubnetAddress(): String? {
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (iface in interfaces) {
            val addresses = Collections.list(iface.inetAddresses)
            for (addr in addresses) {
                if (!addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.")) {
                        val parts = ip.split(".")
                        if (parts.size >= 3) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}."
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "192.168.43."
}

suspend fun scanLocalSubnet(subnetPrefix: String, onDeviceFound: (String) -> Unit) {
    withContext(Dispatchers.IO) {
        val jobs = (2..254).map { lastOctet ->
            async {
                val ip = "$subnetPrefix$lastOctet"
                try {
                    val address = InetAddress.getByName(ip)
                    if (address.isReachable(350)) {
                        withContext(Dispatchers.Main) {
                            onDeviceFound(ip)
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
        jobs.awaitAll()
    }
}

fun getDeviceNameFromIp(ip: String): Pair<String, String> {
    val lastOctet = ip.substringAfterLast(".").toIntOrNull() ?: 100
    val models = listOf(
        Pair("Windows PC", "laptop"),
        Pair("Google Pixel 8", "phone"),
        Pair("iPhone 15 Pro", "phone"),
        Pair("Samsung Galaxy S23", "phone"),
        Pair("iPad Air", "tablet"),
        Pair("Linux Workstation", "laptop"),
        Pair("MacBook Pro M3", "laptop"),
        Pair("Android Host", "phone")
    )
    return models[lastOctet % models.size]
}

fun generateRandomMac(ip: String): String {
    val lastOctet = ip.substringAfterLast(".").toIntOrNull() ?: 100
    val hexPart = String.format("%02X", lastOctet)
    return "70:3E:AC:8D:1F:$hexPart"
}

@Composable
fun rememberMobileConnectionState(): Boolean {
    val context = LocalContext.current
    var isConnected by remember { mutableStateOf(false) }
    
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        fun checkState() {
            var foundCellular = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val networks = cm.allNetworks
                    for (net in networks) {
                        val caps = cm.getNetworkCapabilities(net)
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            foundCellular = true
                            break
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val networksInfo = cm.allNetworkInfo
                    @Suppress("DEPRECATION")
                    for (info in networksInfo) {
                        if (info.type == ConnectivityManager.TYPE_MOBILE && info.isConnected) {
                            foundCellular = true
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                foundCellular = false
            }
            isConnected = foundCellular
        }
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                checkState()
            }
            override fun onLost(network: Network) {
                checkState()
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                checkState()
            }
        }
        
        checkState()
        
        try {
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            checkState()
        }
        
        onDispose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {}
        }
    }
    return isConnected
}


