package com.example.personalcloud

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.ImageLoader
import coil.compose.AsyncImage
import com.example.personalcloud.server.CloudServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────

data class FileItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

class TransferSession(
    val id: String,
    val isUploading: Boolean,
    val sourcePath: String,
    val destPath: String,
    totalBytesInit: Long,
    val totalFiles: Int
) {
    var totalBytes    by mutableStateOf(totalBytesInit)
    var currentBytes  by mutableStateOf(0L)
    var speed         by mutableStateOf(0L)
    var timeRemainingStr by mutableStateOf("--:--")
    var isHidden      by mutableStateOf(false)
    var cancelJob: kotlinx.coroutines.Job?    = null
    var httpCall: okhttp3.Call?               = null
}

// ─────────────────────────────────────────────────────────────────────────────
// Utilities
// ─────────────────────────────────────────────────────────────────────────────

fun getLocalIpAddress(context: Context): String {
    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    @Suppress("DEPRECATION")
    return android.text.format.Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 4)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun buildOkHttpClient(token: String = "") = okhttp3.OkHttpClient.Builder()
    .proxy(java.net.Proxy.NO_PROXY)
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
    .apply {
        if (token.isNotBlank()) addInterceptor { chain ->
            val newReq = chain.request().newBuilder().addHeader("X-Auth-Token", token).build()
            chain.proceed(newReq)
        }
    }
    .build()

// ─────────────────────────────────────────────────────────────────────────────
// Transfer Dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TransferDialog(session: TransferSession, onHide: () -> Unit, onCancel: () -> Unit) {
    if (session.isHidden) return

    Dialog(onDismissRequest = onHide) {
        Card(
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {

                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (session.isUploading) Icons.Default.CloudUpload else Icons.Default.CloudDownload,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            if (session.isUploading) "Uploading" else "Downloading",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            session.id,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Speed & percent row
                val percent = if (session.totalBytes > 0) (session.currentBytes * 100f / session.totalBytes) else 0f
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format(Locale.getDefault(), "%.1f%%", percent),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${formatSize(session.speed)}/s", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.secondary)
                        Text(session.timeRemainingStr, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Liquid green progress bar
                val progressAnim by animateFloatAsState(
                    targetValue = if (session.totalBytes > 0) session.currentBytes.toFloat() / session.totalBytes else 0f,
                    label = "progress"
                )
                Box(modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(modifier = Modifier.fillMaxWidth(progressAnim).fillMaxHeight()
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF00C853), Color(0xFF69F0AE))
                            )
                        )
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatSize(session.currentBytes)} / ${formatSize(session.totalBytes)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (session.totalFiles > 1) Text("${session.totalFiles} files", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(24.dp))

                // Paths
                Text(if (session.isUploading) "FROM DEVICE" else "FROM SERVER", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(session.sourcePath, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(if (session.isUploading) "TO SERVER" else "SAVING TO", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(session.destPath, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

                Spacer(Modifier.height(24.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onHide) {
                        Text("Hide", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { Text("Cancel", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private var isServerRunning by mutableStateOf(false)
    private var hasStorageAccess by mutableStateOf(false)

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { checkStorageAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkStorageAccess()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        setContent {
            val colorScheme = lightColorScheme(
                primary           = Color(0xFF5E35B1),
                primaryContainer  = Color(0xFFEDE7F6),
                secondary         = Color(0xFF00897B),
                secondaryContainer= Color(0xFFE0F2F1),
                tertiary          = Color(0xFFE91E63),
                background        = Color(0xFFF3E5F5),
                surface           = Color(0xFFFFFFFF),
                surfaceVariant    = Color(0xFFEEEEEE),
                error             = Color(0xFFB00020),
                errorContainer    = Color(0xFFFFDAD6),
                onErrorContainer  = Color(0xFF410002)
            )
            MaterialTheme(colorScheme = colorScheme) {
                var currentTab by remember { mutableStateOf(1) }
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Share, null) },
                                label = { Text("Server") },
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Cloud, null) },
                                label = { Text("Client") },
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 }
                            )
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        if (currentTab == 0)
                            ServerScreen(hasStorageAccess, isServerRunning, { requestStoragePermission() }) { s, paths ->
                                if (s) startServer(paths) else stopServer()
                            }
                        else ClientScreen()
                    }
                }
            }
        }
    }

    private fun checkStorageAccess() {
        hasStorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${applicationContext.packageName}")
                storagePermissionLauncher.launch(intent)
            } catch (e: Exception) {
                storagePermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 102)
    }

    private fun startServer(paths: List<String>) {
        val intent = Intent(this, CloudServerService::class.java).apply {
            action = CloudServerService.ACTION_START
            putStringArrayListExtra(CloudServerService.EXTRA_PATHS, ArrayList(paths))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        isServerRunning = true
    }

    private fun stopServer() {
        startService(Intent(this, CloudServerService::class.java).apply { action = CloudServerService.ACTION_STOP })
        isServerRunning = false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    hasStorageAccess: Boolean,
    isServerRunning: Boolean,
    onRequestStorage: () -> Unit,
    onToggleServer: (Boolean, List<String>) -> Unit
) {
    val context = LocalContext.current
    val prefs   = context.getSharedPreferences("PersonalCloud", Context.MODE_PRIVATE)

    var path1  by remember { mutableStateOf(prefs.getString("path1", "/storage/emulated/0") ?: "/storage/emulated/0") }
    var path2  by remember { mutableStateOf(prefs.getString("path2", "") ?: "") }
    var path3  by remember { mutableStateOf(prefs.getString("path3", "") ?: "") }

    // PIN management
    var pin         by remember { mutableStateOf(prefs.getString("server_pin", "") ?: "") }
    var pinInput    by remember { mutableStateOf("") }
    var showPin     by remember { mutableStateOf(false) }
    var showPinEdit by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Mode", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // Branding
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    Text(buildAnnotatedString {
                        append("Developed by ")
                        withStyle(SpanStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.ExtraBold)) {
                            append("Dastgir Siddiq")
                        }
                    }, fontSize = 13.sp)
                }
            }

            item {
                // Big icon with status
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(100.dp).clip(CircleShape)
                            .background(if (isServerRunning) Color(0xFF00C853).copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CloudSync, null,
                            modifier = Modifier.size(64.dp),
                            tint = if (isServerRunning) Color(0xFF00C853) else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isServerRunning) "Server is Running" else "Server is Stopped",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = if (isServerRunning) Color(0xFF00C853) else MaterialTheme.colorScheme.onSurface
                    )
                    if (isServerRunning) {
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                "IP: ${getLocalIpAddress(context)}:8080",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            if (!hasStorageAccess) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text("Storage Permission Required", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Grant All Files Access to share your folders.", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onRequestStorage) { Text("Grant Permission") }
                        }
                    }
                }
            }

            item {
                // Shared paths
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SHARED FOLDERS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        OutlinedTextField(value = path1, onValueChange = { path1 = it }, label = { Text("Path 1 (Default)") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Folder, null) }, singleLine = true)
                        OutlinedTextField(value = path2, onValueChange = { path2 = it }, label = { Text("Path 2 (Optional)") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, singleLine = true)
                        OutlinedTextField(value = path3, onValueChange = { path3 = it }, label = { Text("Path 3 (Optional)") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, singleLine = true)
                    }
                }
            }

            item {
                // Security / PIN card
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Security PIN", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { showPinEdit = !showPinEdit }) {
                                Text(if (pin.isBlank()) "Set PIN" else "Change PIN")
                            }
                        }

                        if (pin.isBlank()) {
                            Text("⚠️ No PIN set — anyone on your Wi-Fi can connect.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("✅ PIN is set. Clients must enter it to connect.", fontSize = 12.sp, color = Color(0xFF388E3C))
                        }

                        AnimatedVisibility(visible = showPinEdit) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = pinInput,
                                    onValueChange = { if (it.length <= 8) pinInput = it },
                                    label = { Text("New PIN (4-8 digits)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showPin = !showPin }) {
                                            Icon(if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        pin = ""
                                        pinInput = ""
                                        showPinEdit = false
                                        prefs.edit().putString("server_pin", "").apply()
                                        Toast.makeText(context, "PIN removed", Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.weight(1f)) { Text("Remove PIN") }
                                    Button(onClick = {
                                        if (pinInput.length >= 4) {
                                            pin = pinInput
                                            pinInput = ""
                                            showPinEdit = false
                                            prefs.edit().putString("server_pin", pin).apply()
                                            Toast.makeText(context, "PIN saved!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                                        }
                                    }, modifier = Modifier.weight(1f)) { Text("Save PIN") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        prefs.edit().putString("path1", path1).putString("path2", path2).putString("path3", path3).apply()
                        onToggleServer(!isServerRunning, listOf(path1, path2, path3))
                    },
                    enabled = hasStorageAccess,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServerRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(if (isServerRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isServerRunning) "Stop Server" else "Start Server", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Client Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen() {
    val context        = LocalContext.current
    val prefs          = context.getSharedPreferences("PersonalCloud", Context.MODE_PRIVATE)

    var ipAddress      by remember { mutableStateOf(prefs.getString("last_ip", "192.168.") ?: "192.168.") }
    var authToken      by remember { mutableStateOf("") }
    var isPaired       by remember { mutableStateOf(false) }

    var currentPath    by remember { mutableStateOf("") }
    var files          by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var errorMsg       by remember { mutableStateOf<String?>(null) }
    var isLoading      by remember { mutableStateOf(false) }

    var showConnDialog by remember { mutableStateOf(false) }
    var showMkdirDialog by remember { mutableStateOf(false) }

    val activeTransfers = remember { androidx.compose.runtime.mutableStateListOf<TransferSession>() }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFiles   by remember { mutableStateOf<Set<String>>(emptySet()) }

    val coroutineScope = rememberCoroutineScope()

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .okHttpClient { buildOkHttpClient() }
            .build()
    }

    // ── Fetch file listing ────────────────────────────────────────────────────
    fun fetchFiles(path: String) {
        isLoading = true; errorMsg = null; isSelectionMode = false; selectedFiles = emptySet()
        prefs.edit().putString("last_ip", ipAddress).apply()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val client = buildOkHttpClient(authToken)
                val url = "http://$ipAddress:8080/files?path=${Uri.encode(path)}"
                val response = client.newCall(okhttp3.Request.Builder().url(url).build()).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val arr  = JSONArray(body)
                    val list = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        FileItem(o.getString("name"), o.getBoolean("isDirectory"), o.getLong("size"), o.getLong("lastModified"))
                    }
                    withContext(Dispatchers.Main) { files = list; currentPath = path; isLoading = false }
                } else if (response.code == 401) {
                    withContext(Dispatchers.Main) { isPaired = false; errorMsg = "Authentication required — connect again."; isLoading = false }
                } else if (response.code == 403 && path.isNotEmpty()) {
                    withContext(Dispatchers.Main) { fetchFiles("") }
                } else {
                    withContext(Dispatchers.Main) { 
                        if (files.isEmpty()) errorMsg = "Server error: ${response.code}"
                        else Toast.makeText(context, "Server error: ${response.code}", Toast.LENGTH_SHORT).show()
                        isLoading = false 
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    if (files.isEmpty()) errorMsg = "Connection failed: ${e.message}"
                    else Toast.makeText(context, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    isLoading = false 
                }
            }
        }
    }

    // Back navigation
    if (isSelectionMode) {
        androidx.activity.compose.BackHandler { isSelectionMode = false; selectedFiles = emptySet() }
    } else if (currentPath.isNotEmpty()) {
        androidx.activity.compose.BackHandler { fetchFiles(currentPath.substringBeforeLast("/", "")) }
    }

    // ── Open / Play File ───────────────────────────────────────────────────────
    fun openFile(file: FileItem) {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "mp4", "mkv", "avi", "webm", "mov" -> "video/*"
            "pdf"  -> "application/pdf"
            "jpg", "jpeg", "png", "gif", "webp" -> "image/*"
            "txt", "csv" -> "text/plain"
            "doc"  -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "apk"  -> "application/vnd.android.package-archive"
            else   -> "*/*"
        }

        val remotePath = if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}"

        if (mime.startsWith("video/")) {
            val fileUrl = "http://$ipAddress:8080/download?path=${Uri.encode(remotePath)}&token=${Uri.encode(authToken)}"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(fileUrl), mime)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            try { context.startActivity(Intent.createChooser(intent, "Open with…")) }
            catch (e: Exception) { Toast.makeText(context, "No app found for this file type", Toast.LENGTH_SHORT).show() }
        } else {
            Toast.makeText(context, "Fetching file...", Toast.LENGTH_SHORT).show()
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val destFile = File(context.cacheDir, file.name)
                    val client = buildOkHttpClient(authToken)
                    val call = client.newCall(okhttp3.Request.Builder().url("http://$ipAddress:8080/download?path=${Uri.encode(remotePath)}").build())
                    val response = call.execute()
                    if (response.isSuccessful) {
                        response.body?.let { body ->
                            body.byteStream().use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
                        }
                        withContext(Dispatchers.Main) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                            }
                            try { context.startActivity(Intent.createChooser(intent, "Open with…")) }
                            catch (e: Exception) { Toast.makeText(context, "No app found for this file type", Toast.LENGTH_SHORT).show() }
                        }
                    } else {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to fetch file", Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // ── Pair with server (exchange PIN for token) ─────────────────────────────
    fun pairWithServer(ip: String, pin: String) {
        isLoading = true; errorMsg = null
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val client = buildOkHttpClient()
                val body = okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), pin)
                val response = client.newCall(okhttp3.Request.Builder().url("http://$ip:8080/pair").post(body).build()).execute()
                when {
                    response.isSuccessful -> {
                        val token = response.body?.string()?.trim() ?: ""
                        withContext(Dispatchers.Main) {
                            authToken = token
                            ipAddress = ip
                            isPaired = true
                            showConnDialog = false
                            isLoading = false
                            fetchFiles("")
                        }
                    }
                    response.code == 401 -> withContext(Dispatchers.Main) { errorMsg = "Wrong PIN"; isLoading = false }
                    else -> withContext(Dispatchers.Main) { errorMsg = "Server error: ${response.code}"; isLoading = false }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMsg = "Could not connect: ${e.message}"; isLoading = false; showConnDialog = true }
            }
        }
    }

    // ── Transfer loop ──────────────────────────────────────────────────────────
    fun doTransferLoop(input: java.io.InputStream, output: java.io.OutputStream, length: Long, session: TransferSession) {
        val buffer = ByteArray(512 * 1024)
        var totalRead = 0L; var lastTime = System.currentTimeMillis(); var lastBytes = 0L
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            if (session.cancelJob?.isActive == false) throw kotlinx.coroutines.CancellationException("Cancelled")
            output.write(buffer, 0, bytesRead); output.flush()
            totalRead += bytesRead
            val now = System.currentTimeMillis()
            if (now - lastTime > 500) {
                val sp = ((totalRead - lastBytes) / ((now - lastTime) / 1000.0)).toLong()
                val tr = if (length > 0 && sp > 0) {
                    val s = (length - totalRead) / sp
                    if (s > 3600) String.format("%dh%02dm", s / 3600, (s % 3600) / 60)
                    else String.format("%d:%02d", s / 60, s % 60)
                } else "--:--"
                coroutineScope.launch(Dispatchers.Main) { session.currentBytes = totalRead; session.speed = sp; session.timeRemainingStr = tr }
                lastTime = now; lastBytes = totalRead
            }
        }
        coroutineScope.launch(Dispatchers.Main) { session.currentBytes = if (length > 0) length else totalRead }
    }

    // ── Download selected files ────────────────────────────────────────────────
    fun downloadSelected() {
        if (selectedFiles.isEmpty()) return
        val items = files.filter { selectedFiles.contains(it.name) }
        isSelectionMode = false; selectedFiles = emptySet()
        val requiresZip = items.size > 1 || items.any { it.isDirectory }

        if (!requiresZip) {
            val file = items.first()
            val remotePath = if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}"
            val downloadsFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "downloads")
            if (!downloadsFolder.exists()) downloadsFolder.mkdirs()
            val destFile   = File(downloadsFolder, file.name)
            val session    = TransferSession(file.name, false, remotePath, destFile.absolutePath, file.size, 1)
            activeTransfers.add(session)

            session.cancelJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    val client   = buildOkHttpClient(authToken)
                    val call     = client.newCall(okhttp3.Request.Builder().url("http://$ipAddress:8080/download?path=${Uri.encode(remotePath)}").build())
                    session.httpCall = call
                    val response = call.execute()
                    if (response.isSuccessful) {
                        response.body?.let { body ->
                            body.byteStream().use { i -> destFile.outputStream().use { o -> doTransferLoop(i, o, body.contentLength(), session) } }
                        }
                        withContext(Dispatchers.Main) { Toast.makeText(context, "Downloaded: ${file.name}", Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    destFile.delete()
                } catch (e: Exception) {
                    destFile.delete()
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) { withContext(Dispatchers.Main) { activeTransfers.remove(session) } }
                }
            }
        } else {
            val zipName  = "Archive_${System.currentTimeMillis()}.zip"
            val downloadsFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "downloads")
            if (!downloadsFolder.exists()) downloadsFolder.mkdirs()
            val destFile = File(downloadsFolder, zipName)
            val session  = TransferSession(zipName, false, "Multiple files (${items.size})", destFile.absolutePath, 0L, items.size)
            activeTransfers.add(session)

            session.cancelJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    val client   = buildOkHttpClient(authToken)
                    val jsonBody = JSONArray().also { arr -> items.forEach { arr.put(it.name) } }.toString()
                    val reqBody  = okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody)
                    val call     = client.newCall(okhttp3.Request.Builder().url("http://$ipAddress:8080/downloadZip?path=${Uri.encode(currentPath)}").post(reqBody).build())
                    session.httpCall = call
                    val response = call.execute()
                    if (response.isSuccessful) {
                        response.body?.let { body ->
                            body.byteStream().use { i -> destFile.outputStream().use { o -> doTransferLoop(i, o, body.contentLength(), session) } }
                        }
                        withContext(Dispatchers.Main) { Toast.makeText(context, "Downloaded: $zipName", Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { destFile.delete()
                } catch (e: Exception) {
                    destFile.delete()
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) { withContext(Dispatchers.Main) { activeTransfers.remove(session) } }
                }
            }
        }
    }

    // ── New Folder ────────────────────────────────────────────────────────────
    fun makeDir(name: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val client = buildOkHttpClient(authToken)
                val response = client.newCall(
                    okhttp3.Request.Builder()
                        .url("http://$ipAddress:8080/mkdir?path=${Uri.encode(currentPath)}&name=${Uri.encode(name)}")
                        .post(okhttp3.RequestBody.create(null, ByteArray(0))).build()
                ).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) { fetchFiles(currentPath) }
                    else Toast.makeText(context, "Could not create folder", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show() } }
        }
    }

    // ── File picker / upload ─────────────────────────────────────────────────
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            var fileName = "uploaded_file"; var fileLength = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { fileName = c.getString(it) }
                    c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let { fileLength = c.getLong(it) }
                }
            }
            val destPath = if (currentPath.isEmpty()) fileName else "$currentPath/$fileName"
            val session  = TransferSession(fileName, true, fileName, destPath, fileLength, 1)
            activeTransfers.add(session)

            session.cancelJob = coroutineScope.launch {
                try {
                    val uploadUrl = "http://$ipAddress:8080/upload?path=${Uri.encode(currentPath)}&name=${Uri.encode(fileName)}"
                    withContext(Dispatchers.IO) {
                        val client = buildOkHttpClient(authToken)
                        val requestBody = object : okhttp3.RequestBody() {
                            override fun contentType() = null
                            override fun contentLength() = -1L
                            override fun writeTo(sink: okio.BufferedSink) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val buffer = ByteArray(256 * 1024)
                                    var totalRead = 0L; var lastTime = System.currentTimeMillis(); var lastBytes = 0L
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n == -1) break
                                        if (session.cancelJob?.isActive == false) throw java.io.IOException("Cancelled")
                                        sink.write(buffer, 0, n); sink.flush()
                                        totalRead += n
                                        val now = System.currentTimeMillis()
                                        if (now - lastTime > 500) {
                                            val sp = ((totalRead - lastBytes) / ((now - lastTime) / 1000.0)).toLong()
                                            val tr = if (fileLength > 0 && sp > 0) { val s = (fileLength - totalRead) / sp; String.format("%d:%02d", s / 60, s % 60) } else "--:--"
                                            coroutineScope.launch(Dispatchers.Main) { session.currentBytes = totalRead; session.speed = sp; session.timeRemainingStr = tr }
                                            lastTime = now; lastBytes = totalRead
                                        }
                                    }
                                    coroutineScope.launch(Dispatchers.Main) { session.currentBytes = fileLength }
                                }
                            }
                        }
                        val call = client.newCall(okhttp3.Request.Builder().url(uploadUrl).post(requestBody).build())
                        session.httpCall = call
                        val resp = call.execute()
                        if (!resp.isSuccessful) throw Exception("Server error ${resp.code}")
                    }
                    Toast.makeText(context, "Upload complete!", Toast.LENGTH_SHORT).show()
                    fetchFiles(currentPath)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cancelled cleanly
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                        android.util.Log.e("UPLOAD", "Upload failed", e)
                    }
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) { activeTransfers.remove(session) }
                }
            }
        }
    }

    // ── Auto-connect on first open ────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (ipAddress.isNotBlank() && ipAddress != "192.168." && !isPaired) {
            pairWithServer(ipAddress, "")
        }
    }

    // ── Dialogs: Transfer ─────────────────────────────────────────────────────
    activeTransfers.forEach { session ->
        TransferDialog(
            session  = session,
            onHide   = { session.isHidden = true },
            onCancel = { session.httpCall?.cancel(); session.cancelJob?.cancel() }
        )
    }

    // ── Dialog: Connect / PIN ────────────────────────────────────────────────
    if (showConnDialog) {
        var tempIp  by remember { mutableStateOf(ipAddress) }
        var tempPin by remember { mutableStateOf("") }
        var showPinV by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showConnDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Connect to Server", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tempIp, onValueChange = { tempIp = it },
                        label = { Text("Server IP Address") },
                        leadingIcon = { Icon(Icons.Default.Wifi, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tempPin, onValueChange = { tempPin = it },
                        label = { Text("Server PIN (leave blank if none)") },
                        visualTransformation = if (showPinV) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        trailingIcon = { IconButton(onClick = { showPinV = !showPinV }) { Icon(if (showPinV) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showConnDialog = false; pairWithServer(tempIp, tempPin) }) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { showConnDialog = false }) { Text("Cancel") } }
        )
    }

    // ── Dialog: Create Folder ─────────────────────────────────────────────────
    if (showMkdirDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMkdirDialog = false },
            title = { Text("New Folder") },
            text = { OutlinedTextField(value = folderName, onValueChange = { folderName = it }, label = { Text("Folder name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) },
            confirmButton = { Button(onClick = { if (folderName.isNotBlank()) { showMkdirDialog = false; makeDir(folderName) } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showMkdirDialog = false }) { Text("Cancel") } }
        )
    }

    // ── Main scaffold ─────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) Text("${selectedFiles.size} selected", fontWeight = FontWeight.Bold)
                    else Text(if (currentPath.isEmpty()) "Client Mode" else currentPath.substringAfterLast("/"), fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    if (isSelectionMode) IconButton(onClick = { isSelectionMode = false; selectedFiles = emptySet() }) { Icon(Icons.Default.Close, "Cancel") }
                    else if (currentPath.isNotEmpty()) IconButton(onClick = { fetchFiles(currentPath.substringBeforeLast("/", "")) }) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    // Hidden transfer restore icon
                    val hiddenTransfers = activeTransfers.filter { it.isHidden }
                    if (hiddenTransfers.isNotEmpty()) {
                        val icon = when {
                            hiddenTransfers.all { it.isUploading }  -> Icons.Default.CloudUpload
                            hiddenTransfers.all { !it.isUploading } -> Icons.Default.CloudDownload
                            else                                     -> Icons.Default.CloudSync
                        }
                        IconButton(onClick = { hiddenTransfers.forEach { it.isHidden = false } }) {
                            BadgedBox(badge = { Badge { Text("${hiddenTransfers.size}") } }) {
                                Icon(icon, "Active Transfers", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            selectedFiles = if (selectedFiles.size == files.size) emptySet() else files.map { it.name }.toSet()
                        }) { Icon(Icons.Default.SelectAll, "Select All") }
                    } else {
                        if (isPaired && currentPath.isNotEmpty()) {
                            IconButton(onClick = { showMkdirDialog = true }) { Icon(Icons.Default.CreateNewFolder, "New Folder") }
                        }
                        IconButton(onClick = { showConnDialog = true }) { Icon(Icons.Default.SettingsEthernet, "Connect") }
                        if (isPaired) IconButton(onClick = { fetchFiles(currentPath) }) { Icon(Icons.Default.Refresh, "Refresh") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (isSelectionMode && selectedFiles.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingActionButton(
                        onClick = { isSelectionMode = false; selectedFiles = emptySet() },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp)
                    ) { Icon(Icons.Default.Close, "Cancel") }
                    ExtendedFloatingActionButton(
                        onClick = { downloadSelected() },
                        icon = { Icon(Icons.Default.Download, null) },
                        text = { Text("Download ${selectedFiles.size}") },
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (!isSelectionMode && isPaired && currentPath.isNotEmpty()) {
                FloatingActionButton(onClick = { filePicker.launch("*/*") }, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.UploadFile, "Upload", tint = Color.White)
                }
            } else if (!isPaired) {
                ExtendedFloatingActionButton(
                    onClick = { showConnDialog = true },
                    icon = { Icon(Icons.Default.Wifi, null) },
                    text = { Text("Connect to Server") },
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text("Connecting...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                !isPaired -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(16.dp))
                            Text("Not Connected", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(8.dp))
                            Text(errorMsg ?: "Tap the button below to connect to a server device.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                errorMsg != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Icon(Icons.Default.WifiOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            Text(errorMsg!!, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { fetchFiles(currentPath) }) { Text("Retry") }
                        }
                    }
                }
                files.isEmpty() && !isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(12.dp))
                            Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(files, key = { it.name }) { file ->
                            val isSelected     = selectedFiles.contains(file.name)
                            val activeSession  = activeTransfers.find { it.id == file.name }
                            val progress       = if (activeSession != null && activeSession.totalBytes > 0)
                                activeSession.currentBytes.toFloat() / activeSession.totalBytes.toFloat() else null

                            FileItemRow(
                                file            = file,
                                ipAddress       = ipAddress,
                                authToken       = authToken,
                                currentPath     = currentPath,
                                context         = context,
                                imageLoader     = imageLoader,
                                transferProgress= progress,
                                isSelectionMode = isSelectionMode,
                                isSelected      = isSelected,
                                onLongClick     = { isSelectionMode = true; selectedFiles = selectedFiles + file.name },
                                onSelectToggle  = { selectedFiles = if (isSelected) selectedFiles - file.name else selectedFiles + file.name },
                                onDownload      = { selectedFiles = setOf(file.name); downloadSelected() },
                                onOpen          = { openFile(file) },
                                onClick         = { if (file.isDirectory) fetchFiles(if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}") }
                            )
                            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// File Item Row
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileItemRow(
    file: FileItem,
    ipAddress: String,
    authToken: String,
    currentPath: String,
    context: Context,
    imageLoader: ImageLoader,
    transferProgress: Float?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onLongClick: () -> Unit,
    onSelectToggle: () -> Unit,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sdf      = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault())
    val filePath = if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}"
    val fileUrl  = "http://$ipAddress:8080/download?path=${Uri.encode(filePath)}"
    val ext      = file.name.substringAfterLast('.', "").lowercase()

    val animatedProgress by animateFloatAsState(targetValue = transferProgress ?: 0f, label = "progress")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
    ) {
        // Green liquid transfer progress underlay
        if (transferProgress != null) {
            Box(
                modifier = Modifier.matchParentSize().fillMaxWidth(animatedProgress)
                    .background(Brush.horizontalGradient(colors = listOf(Color(0xFF00C853).copy(0.18f), Color(0xFF69F0AE).copy(0.18f))))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick    = { if (isSelectionMode) onSelectToggle() else if (file.isDirectory) onClick() else expanded = true },
                    onLongClick = { if (!isSelectionMode) onLongClick() else onSelectToggle() }
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onSelectToggle() })
                Spacer(Modifier.width(4.dp))
            }

            // Icon / thumbnail
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))) {
                if (file.isDirectory) {
                    Box(Modifier.fillMaxSize().background(Color(0xFFFFF8E1)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFFFFB300), modifier = Modifier.size(28.dp))
                    }
                } else if (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "mp4", "mkv", "avi", "webm", "mov", "apk")) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data("http://$ipAddress:8080/thumbnail?path=${Uri.encode(filePath)}")
                            .addHeader("X-Auth-Token", authToken)
                            .build(),
                        imageLoader = imageLoader,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image)
                    )
                } else {
                    val (icon, bg, tint) = when (ext) {
                        "pdf"                          -> Triple(Icons.Default.PictureAsPdf,  Color(0xFFFFEBEE), Color(0xFFD32F2F))
                        "doc", "docx", "rtf"           -> Triple(Icons.Default.Description,   Color(0xFFE3F2FD), Color(0xFF1565C0))
                        "xls", "xlsx"                  -> Triple(Icons.Default.GridOn,         Color(0xFFE8F5E9), Color(0xFF2E7D32))
                        "ppt", "pptx"                  -> Triple(Icons.Default.Slideshow,      Color(0xFFFFF3E0), Color(0xFFE65100))
                        "txt", "csv", "md", "log"      -> Triple(Icons.Default.Description,   Color(0xFFF3E5F5), Color(0xFF6A1B9A))
                        "py", "kt", "java", "js", "ts",
                        "html", "xml", "json", "cpp",
                        "c", "swift"                   -> Triple(Icons.Default.Code,           Color(0xFFEDE7F6), Color(0xFF4527A0))
                        "mp3", "wav", "flac", "ogg",
                        "m4a", "aac"                   -> Triple(Icons.Default.AudioFile,      Color(0xFFE0F7FA), Color(0xFF00838F))
                        "zip", "rar", "7z", "tar", "gz"-> Triple(Icons.Default.Archive,        Color(0xFFFFF9C4), Color(0xFFF9A825))
                        else                           -> Triple(Icons.Default.InsertDriveFile,Color(0xFFF5F5F5), Color(0xFF757575))
                    }
                    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(sdf.format(Date(file.lastModified)))
                        append("  •  ")
                        append(if (file.isDirectory) "Folder" else formatSize(file.size))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            if (!isSelectionMode && file.isDirectory) {
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }

        // File action menu
        if (!file.isDirectory) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Open / Play") },
                    leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
                    onClick = { expanded = false; onOpen() }
                )
                DropdownMenuItem(
                    text = { Text("Download") },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = { expanded = false; onDownload() }
                )
            }
        } else if (!isSelectionMode) {
            // Folder context menu
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Download as ZIP") },
                    leadingIcon = { Icon(Icons.Default.Archive, null) },
                    onClick = { expanded = false; onDownload() }
                )
            }
        }
    }
}
