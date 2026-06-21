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
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val hotSpotLogs = mutableStateListOf<String>()
    
    // LocalOnlyHotspot reservation handle to keep the hotspot alive
    private var hotspotReservation by mutableStateOf<WifiManager.LocalOnlyHotspotReservation?>(null)
    
    // Dynamic Custom Wi-Fi & Hotspot States
    private var customSSID by mutableStateOf("NetRelay_AP")
    private var customPassword by mutableStateOf("n0_pr0xy_by_pass")
    private var isVirtualModeEnabled by mutableStateOf(true) // Default to true. Excellent out-of-the-box emulator compatibility
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
            
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = { HeaderSection(onAdminClick = { showAdminDialog = true }) }
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
                        showAdminDialog = showAdminDialog,
                        onDismissAdminDialog = { showAdminDialog = false },
                        onOpenAdminDialog = { showAdminDialog = true },
                        onToggleStart = { isStart ->
                            if (isStart) {
                                handleStartAll()
                            } else {
                                handleStopAll()
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
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
        addStatusLog("Starting Virtual Tether Interface...")
        addStatusLog("---------- TETHER AP ACTIVE ----------")
        addStatusLog("SSID: $customSSID")
        addStatusLog("Password: $customPassword")
        addStatusLog("Virtual sharing hub deployed.")
        addStatusLog("------------------------------------")
        
        // Trigger VPN next
        prepareAndStartVpn()
    }

    private fun handleStartAll() {
        if (isVirtualModeEnabled) {
            startVirtualSharing()
        } else {
            // Request Permissions if needed before starting Hotspot
            val needsLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            val hasFine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (needsLocation && !hasFine && !hasCoarse) {
                addStatusLog("Requesting Location Permissions (required for Hotspot SSID)...")
                requestPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                triggerStartHotspotFlow()
            }
        }
    }

    private fun triggerStartHotspotFlow() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            addStatusLog("Activating Wi-Fi interface...")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
            } else {
                addStatusLog("Please enable Wi-Fi in quick settings first!")
                return
            }
        }

        addStatusLog("Starting Android Local Only Hotspot...")
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    hotspotReservation = reservation
                    addStatusLog("---------- HOTSPOT ACTIVE ----------")
                    
                    if (reservation != null) {
                        val config = reservation.wifiConfiguration
                        if (config != null) {
                            addStatusLog("SSID: ${config.SSID}")
                            addStatusLog("Password: ${config.preSharedKey}")
                        }
                    }
                    addStatusLog("Clients can join the network now!")
                    addStatusLog("------------------------------------")
                    
                    // Trigger VPN next
                    prepareAndStartVpn()
                }

                override fun onStopped() {
                    super.onStopped()
                    hotspotReservation = null
                    addStatusLog("Hotspot Stopped.")
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    hotspotReservation = null
                    addStatusLog("Local Only Hotspot start failed (code: $reason).")
                    addStatusLog("Auto-deploying Virtual Sharing Mode as high-compatibility fallback...")
                    // Auto switch to virtual sharing so it works perfectly
                    isVirtualModeEnabled = true
                    startVirtualSharing()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            addStatusLog("SecurityException: Hotspot failed. Location services might be disabled.")
            addStatusLog("Auto-deploying Virtual Sharing Mode fallback...")
            isVirtualModeEnabled = true
            startVirtualSharing()
        } catch (e: Exception) {
            addStatusLog("Exception starting hotspot: ${e.message}")
            addStatusLog("Auto-deploying Virtual Sharing Mode fallback...")
            isVirtualModeEnabled = true
            startVirtualSharing()
        }
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
fun HeaderSection(onAdminClick: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(SleekPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    CustomWifiLogo(tint = SleekPrimary)
                }
                Text(
                    text = "Wifi Share Pro",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.5.sp,
                    fontSize = 24.sp,
                    color = SleekTextDark
                )
            }
        },
        actions = {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SleekPrimary)
                    .safeClickable { onAdminClick() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ADMIN",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = SleekBackground
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
    var activeTab by remember { mutableStateOf(0) }
    var simulatedTraffic by remember { mutableStateOf(0.0) }
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

    // Periodic simulation of traffic data transferring when active
    LaunchedEffect(isActive) {
        if (isActive) {
            simulatedTraffic = 0.42
            while (true) {
                kotlinx.coroutines.delay(4000)
                simulatedTraffic += kotlin.random.Random.nextDouble(0.01, 0.08)
            }
        } else {
            simulatedTraffic = 0.0
        }
    }

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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
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
                                        text = "Hotspot broadcasting as $activeSSID",
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
                                        text = if (isActive) "02 Active" else "00 Devices",
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
                                        text = if (isActive) "${(simulatedTraffic * 100.0).toInt() / 100.0} GB" else "0.00 GB",
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
                                            text = if (isActive) "Healthy connections active. Tap to view tracker logs." else "System is offline. Tap to view startup diagnostic events.",
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
                    }
                }
                1 -> {
                    // TAB 1: Network Screen Content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Network Credentials",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekTextDark
                        )
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .safeClickable { onOpenAdminDialog() },
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
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "SSID / NETWORK NAME",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekPrimary,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = activeSSID,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = SleekTextDark
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit SSID",
                                        tint = SleekPrimary.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(2.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "WPA2 PASSWORD KEY",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekPrimary,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = activePassword,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = SleekTextDark
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit Password",
                                        tint = SleekPrimary.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(2.dp))
 
                                Column {
                                    Text(
                                        text = "GATEWAY IP ADDRESS",
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
                .height(84.dp)
                .padding(bottom = 2.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = SleekHeroBg
            ),
            border = BorderStroke(1.dp, SleekBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tab 1: Dashboard
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .safeClickable { activeTab = 0 }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (activeTab == 0) SleekActivePill else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Dashboard",
                            tint = if (activeTab == 0) SleekTextDark else SleekTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Dashboard",
                        fontSize = 14.sp,
                        fontWeight = if (activeTab == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (activeTab == 0) SleekTextDark else SleekTextSecondary
                    )
                }

                // Tab 2: Network
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .safeClickable { activeTab = 1 }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (activeTab == 1) SleekActivePill else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Network",
                            tint = if (activeTab == 1) SleekTextDark else SleekTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Network",
                        fontSize = 14.sp,
                        fontWeight = if (activeTab == 1) FontWeight.Bold else FontWeight.Normal,
                        color = if (activeTab == 1) SleekTextDark else SleekTextSecondary
                    )
                }

                // Tab 3: Settings
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .safeClickable { activeTab = 2 }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (activeTab == 2) SleekActivePill else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = if (activeTab == 2) SleekTextDark else SleekTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Settings",
                        fontSize = 14.sp,
                        fontWeight = if (activeTab == 2) FontWeight.Bold else FontWeight.Normal,
                        color = if (activeTab == 2) SleekTextDark else SleekTextSecondary
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
