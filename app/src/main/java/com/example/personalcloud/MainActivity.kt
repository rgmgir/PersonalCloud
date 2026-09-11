package com.example.personalcloud
import androidx.compose.foundation.combinedClickable
import okhttp3.MediaType.Companion.toMediaTypeOrNull

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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.ImageLoader
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import com.example.personalcloud.server.CloudServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType
import okio.BufferedSink

data class FileItem(val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

fun getLocalIpAddress(context: Context): String {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    val ipAddress = wifiManager.connectionInfo.ipAddress
    return android.text.format.Formatter.formatIpAddress(ipAddress)
}


@Composable
fun TransferDialog(
    session: TransferSession,
    onHide: () -> Unit,
    onCancel: () -> Unit
) {
    if (session.isHidden) return
    
    androidx.compose.ui.window.Dialog(onDismissRequest = onHide) {
        androidx.compose.material3.Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (session.isUploading) Icons.Default.CloudUpload else Icons.Default.CloudDownload, 
                        contentDescription = null, 
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    androidx.compose.material3.Text(
                        if (session.isUploading) "Uploading File..." else "Downloading File...", 
                        fontSize = 20.sp, 
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Paths
                val sourceLabel = if (session.isUploading) "From phone:" else "From server:"
                val destLabel = if (session.isUploading) "To server:" else "To phone:"
                androidx.compose.material3.Text(sourceLabel, fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                androidx.compose.material3.Text(session.sourcePath, fontSize = 13.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                
                Spacer(modifier = Modifier.height(8.dp))
                
                androidx.compose.material3.Text(destLabel, fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                androidx.compose.material3.Text(session.destPath, fontSize = 13.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Progress stats
                val percent = if (session.totalBytes > 0) (session.currentBytes.toDouble() / session.totalBytes * 100) else 0.0
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    androidx.compose.material3.Text(String.format(java.util.Locale.getDefault(), "%.1f%%", percent), fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                    androidx.compose.material3.Text("${formatSize(session.speed)}/s", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val progressFloat = if (session.totalBytes > 0) (session.currentBytes.toFloat() / session.totalBytes.toFloat()) else 0f
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progressFloat,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    trackColor = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    androidx.compose.material3.Text("${formatSize(session.currentBytes)} / ${formatSize(session.totalBytes)}", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    androidx.compose.material3.Text(session.timeRemainingStr, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                }
                
                Spacer(modifier = Modifier.height(28.dp))
                
                // Actions
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    androidx.compose.material3.TextButton(onClick = onHide) {
                        androidx.compose.material3.Text("Hide", color = androidx.compose.material3.MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.Button(
                        onClick = onCancel,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer, contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer)
                    ) {
                        androidx.compose.material3.Text("Cancel")
                    }
                }
            }
        }
    }
}
fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}


class TransferSession(
    val id: String,
    val isUploading: Boolean,
    val sourcePath: String,
    val destPath: String,
    val totalBytes: Long,
    val totalFiles: Int
) {
    var currentBytes by mutableStateOf(0L)
    var speed by mutableStateOf(0L)
    var timeRemainingStr by mutableStateOf("0:00:00")
    var currentIndex by mutableStateOf(1)
    var isHidden by mutableStateOf(false)
    var cancelJob: kotlinx.coroutines.Job? = null
    var httpCall: okhttp3.Call? = null
}

class MainActivity : ComponentActivity() {
    private var isServerRunning by mutableStateOf(false)
    private var hasStorageAccess by mutableStateOf(false)

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkStorageAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkStorageAccess()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        setContent {
            // Vibrant Colors
            val vibrantColorScheme = lightColorScheme(
                primary = Color(0xFF6200EA), // Deep Purple
                secondary = Color(0xFF00BFA5), // Teal
                tertiary = Color(0xFFFF4081), // Pink/Magenta
                background = Color(0xFFF3E5F5), // Light purple background
                surface = Color.White
            )

            MaterialTheme(colorScheme = vibrantColorScheme) {
                var currentTab by remember { mutableStateOf(1) } // Default to Client (1)
                
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(icon = { Icon(Icons.Default.Share, null) }, label = { Text("Server Mode") }, selected = currentTab == 0, onClick = { currentTab = 0 })
                            NavigationBarItem(icon = { Icon(Icons.Default.Cloud, null) }, label = { Text("Client Mode") }, selected = currentTab == 1, onClick = { currentTab = 1 })
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        if (currentTab == 0) ServerScreen(hasStorageAccess, isServerRunning, { requestStoragePermission() }, { s, paths -> if (s) startServer(paths) else stopServer() })
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
                intent.addCategory("android.intent.category.DEFAULT")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    hasStorageAccess: Boolean,
    isServerRunning: Boolean,
    onRequestStorage: () -> Unit,
    onToggleServer: (Boolean, List<String>) -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("PersonalCloud", Context.MODE_PRIVATE)
    
    var path1 by remember { mutableStateOf(prefs.getString("path1", "/storage/emulated/0") ?: "/storage/emulated/0") }
    var path2 by remember { mutableStateOf(prefs.getString("path2", "") ?: "") }
    var path3 by remember { mutableStateOf(prefs.getString("path3", "") ?: "") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Provider (Server Mode)") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                Text(buildAnnotatedString {
                    append("Developed by ")
                    withStyle(SpanStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)) {
                        append("Dastgir Siddiq")
                    }
                })
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            
            if (!hasStorageAccess) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Missing Storage Access", fontWeight = FontWeight.Bold)
                        Text("To share folders, allow All Files Access.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onRequestStorage) { Text("Grant Permission") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            OutlinedTextField(value = path1, onValueChange = { path1 = it }, label = { Text("Path 1 (Default)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = path2, onValueChange = { path2 = it }, label = { Text("Path 2 (Optional)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = path3, onValueChange = { path3 = it }, label = { Text("Path 3 (Optional)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))

            if (isServerRunning) {
                Text("Server IP: ${getLocalIpAddress(LocalContext.current)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            Button(
                onClick = {
                    prefs.edit().putString("path1", path1).putString("path2", path2).putString("path3", path3).apply()
                    onToggleServer(!isServerRunning, listOf(path1, path2, path3))
                },
                enabled = hasStorageAccess,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (isServerRunning) "Stop Server" else "Start Server", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("PersonalCloud", Context.MODE_PRIVATE)
    var ipAddress by remember { mutableStateOf(prefs.getString("last_ip", "192.168.") ?: "192.168.") }
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showIpDialog by remember { mutableStateOf(false) }
    
    val activeTransfers = remember { androidx.compose.runtime.mutableStateListOf<TransferSession>() }
    
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    
    val coroutineScope = rememberCoroutineScope()
    val imageLoader = remember {
        coil.ImageLoader.Builder(context)
            .okHttpClient {
                okhttp3.OkHttpClient.Builder()
                    .proxy(java.net.Proxy.NO_PROXY)
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            }
            .build()
    }
    
    if (isSelectionMode) {
        androidx.activity.compose.BackHandler {
            isSelectionMode = false
            selectedFiles = emptySet()
        }
    } else if (currentPath.isNotEmpty()) {
        androidx.activity.compose.BackHandler {
            currentPath = currentPath.substringBeforeLast("/", "")
        }
    }

    fun fetchFiles(path: String) {
        isLoading = true
        errorMsg = null
        isSelectionMode = false
        selectedFiles = emptySet()
        prefs.edit().putString("last_ip", ipAddress).apply()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val url = "http://$ipAddress:8080/files?path=${Uri.encode(path)}"
                val request = okhttp3.Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val jsonArray = JSONArray(body)
                    val parsedFiles = mutableListOf<FileItem>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        parsedFiles.add(FileItem(obj.getString("name"), obj.getBoolean("isDirectory"), obj.getLong("size"), obj.getLong("lastModified")))
                    }
                    withContext(Dispatchers.Main) {
                        files = parsedFiles
                        currentPath = path
                        isLoading = false
                    }
                } else {
                    withContext(Dispatchers.Main) { errorMsg = "Server error: ${response.code}"; isLoading = false }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMsg = "Connection failed: ${e.message}"; isLoading = false }
            }
        }
    }

    LaunchedEffect(currentPath) {
        if (!isLoading) fetchFiles(currentPath)
    }

    fun doTransferLoop(input: java.io.InputStream, output: java.io.OutputStream, length: Long, session: TransferSession) {
        val buffer = ByteArray(1024 * 1024)
        var bytesRead: Int
        var totalRead = 0L
        var lastUpdateTime = System.currentTimeMillis()
        var lastBytes = 0L
        
        while (input.read(buffer).also { bytesRead = it } != -1) {
            if (session.cancelJob?.isActive == false) throw kotlinx.coroutines.CancellationException("Cancelled by user")
            
            output.write(buffer, 0, bytesRead)
            output.flush()
            totalRead += bytesRead
            
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime > 500) {
                val timeDiff = (now - lastUpdateTime) / 1000.0
                val bytesDiff = totalRead - lastBytes
                val currentSpeed = (bytesDiff / timeDiff).toLong()
                
                val timeStr = if (length > 0 && currentSpeed > 0) {
                    val secondsRemaining = (length - totalRead) / currentSpeed
                    String.format("%d:%02d:%02d", secondsRemaining / 3600, (secondsRemaining % 3600) / 60, secondsRemaining % 60)
                } else "0:00:00"
                
                coroutineScope.launch(Dispatchers.Main) {
                    session.currentBytes = totalRead
                    session.speed = currentSpeed
                    session.timeRemainingStr = timeStr
                }
                lastUpdateTime = now
                lastBytes = totalRead
            }
        }
        coroutineScope.launch(Dispatchers.Main) { session.currentBytes = length }
    }

    fun downloadSelected() {
        if (selectedFiles.isEmpty()) return
        
        val itemsToDownload = files.filter { selectedFiles.contains(it.name) }
        val requiresZip = itemsToDownload.size > 1 || itemsToDownload.any { it.isDirectory }
        
        isSelectionMode = false
        selectedFiles = emptySet()
        
        if (!requiresZip) {
            val file = itemsToDownload.first()
            val remotePath = if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}"
            val destFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), file.name)
            
            val session = TransferSession(file.name, false, remotePath, destFile.absolutePath, file.size, 1)
            activeTransfers.add(session)
            
            session.cancelJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    val fileUrl = "http://$ipAddress:8080/download?path=${Uri.encode(remotePath)}"
                    val client = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder().url(fileUrl).build()
                    val call = client.newCall(request)
                        session.httpCall = call
                        val response = call.execute()
                    if (response.isSuccessful) {
                        response.body?.let { body ->
                            val length = body.contentLength()
                            withContext(Dispatchers.Main) { session.totalBytes.let { if (it == 0L) session.currentBytes = 0L } } // update length if unknown
                            
                            body.byteStream().use { input ->
                                destFile.outputStream().use { output ->
                                    doTransferLoop(input, output, length, session)
                                }
                            }
                        }
                        withContext(Dispatchers.Main) { Toast.makeText(context, "Downloaded ${file.name}", Toast.LENGTH_SHORT).show() }
                    }
                } catch(e: kotlinx.coroutines.CancellationException) {
                    destFile.delete()
                } catch(e: Exception) {
                    e.printStackTrace()
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        withContext(Dispatchers.Main) { activeTransfers.remove(session) }
                    }
                }
            }
        } else {
            val zipName = "Archive_${System.currentTimeMillis()}.zip"
            val destFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), zipName)
            val session = TransferSession(zipName, false, "Multiple Files", destFile.absolutePath, 0L, itemsToDownload.size)
            activeTransfers.add(session)
            
            session.cancelJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    val fileUrl = "http://$ipAddress:8080/downloadZip?path=${Uri.encode(currentPath)}"
                    val client = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    
                    val jsonArray = JSONArray()
                    itemsToDownload.forEach { jsonArray.put(it.name) }
                    val requestBody = okhttp3.RequestBody.Companion.create("application/json".toMediaTypeOrNull(), jsonArray.toString())
                    
                    val request = okhttp3.Request.Builder().url(fileUrl).post(requestBody).build()
                    val call = client.newCall(request)
                        session.httpCall = call
                        val response = call.execute()
                    if (response.isSuccessful) {
                        response.body?.let { body ->
                            val length = body.contentLength()
                            body.byteStream().use { input ->
                                destFile.outputStream().use { output ->
                                    doTransferLoop(input, output, length, session)
                                }
                            }
                        }
                        withContext(Dispatchers.Main) { Toast.makeText(context, "Downloaded $zipName", Toast.LENGTH_SHORT).show() }
                    }
                } catch(e: kotlinx.coroutines.CancellationException) {
                    destFile.delete()
                } catch(e: Exception) {
                    e.printStackTrace()
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        withContext(Dispatchers.Main) { activeTransfers.remove(session) }
                    }
                }
            }
        }
    }

    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            var fileName = "uploaded_file"
            var fileLength = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) fileLength = cursor.getLong(sizeIndex)
                }
            }
            
            val destPath = if (currentPath.isEmpty()) fileName else "$currentPath/$fileName"
            val session = TransferSession(fileName, true, fileName, destPath, fileLength, 1)
            activeTransfers.add(session)
            
            session.cancelJob = coroutineScope.launch {
                try {
                    val uploadUrl = "http://$ipAddress:8080/upload?path=${Uri.encode(currentPath)}&name=${Uri.encode(fileName)}"
                    
                    withContext(Dispatchers.IO) {
                        val client = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
                            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                            
                        val requestBody = object : okhttp3.RequestBody() {
                            override fun contentType(): okhttp3.MediaType? = null
                            override fun contentLength(): Long = -1L
                            override fun writeTo(sink: okio.BufferedSink) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val buffer = ByteArray(256 * 1024)
                                    var bytesRead: Int
                                    var totalRead = 0L
                                    var lastUpdateTime = System.currentTimeMillis()
                                    var lastBytes = 0L
                                    
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        if (session.cancelJob?.isActive == false) throw java.io.IOException("Cancelled by user")
                                        
                                        sink.write(buffer, 0, bytesRead)
                                        sink.flush()
                                        totalRead += bytesRead
                                        
                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdateTime > 500) {
                                            val timeDiff = (now - lastUpdateTime) / 1000.0
                                            val currentSpeed = ((totalRead - lastBytes) / timeDiff).toLong()
                                            
                                            val timeStr = if (fileLength > 0 && currentSpeed > 0) {
                                                val secondsRemaining = (fileLength - totalRead) / currentSpeed
                                                String.format("%d:%02d:%02d", secondsRemaining / 3600, (secondsRemaining % 3600) / 60, secondsRemaining % 60)
                                            } else "0:00:00"
                                            
                                            coroutineScope.launch(Dispatchers.Main) {
                                                session.currentBytes = totalRead
                                                session.speed = currentSpeed
                                                session.timeRemainingStr = timeStr
                                            }
                                            lastUpdateTime = now
                                            lastBytes = totalRead
                                        }
                                    }
                                    coroutineScope.launch(Dispatchers.Main) { session.currentBytes = fileLength }
                                }
                            }
                        }
                        val request = okhttp3.Request.Builder().url(uploadUrl).post(requestBody).build()
                        val call = client.newCall(request)
                        session.httpCall = call
                        val response = call.execute()
                        if (!response.isSuccessful) throw Exception("Server returned ${response.code}")
                    }
                    Toast.makeText(context, "Uploaded successfully", Toast.LENGTH_SHORT).show()
                    fetchFiles(currentPath)
                } catch(e: kotlinx.coroutines.CancellationException) {
                    // Upload cancelled
                } catch (e: Exception) {
                    Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                    android.util.Log.e("UPLOAD_ERROR", "Upload failed", e)
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        activeTransfers.remove(session)
                    }
                }
            }
        }
    }
    
    // Render the dialogs
    activeTransfers.forEach { session ->
        TransferDialog(
            session = session,
            onHide = { session.isHidden = true },
            onCancel = { 
                session.httpCall?.cancel()
                session.cancelJob?.cancel() 
            }
        )
    }

    if (showIpDialog) {
        AlertDialog(
            onDismissRequest = { showIpDialog = false },
            title = { Text("Connect to Server") },
            text = { OutlinedTextField(value = ipAddress, onValueChange = { ipAddress = it }, label = { Text("Server IP Address") }) },
            confirmButton = { Button(onClick = { showIpDialog = false; fetchFiles(currentPath) }) { Text("Connect") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (isSelectionMode) {
                        Text("${selectedFiles.size} selected")
                    } else {
                        Text(if (currentPath.isEmpty()) "Home" else currentPath.substringAfterLast("/"))
                    }
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { isSelectionMode = false; selectedFiles = emptySet() }) {
                            Icon(Icons.Default.Close, "Cancel")
                        }
                    }
                },
                actions = {
                    val hiddenTransfers = activeTransfers.filter { it.isHidden }
                    if (hiddenTransfers.isNotEmpty()) {
                        val allUploads = hiddenTransfers.all { it.isUploading }
                        val allDownloads = hiddenTransfers.all { !it.isUploading }
                        val icon = when {
                            allUploads -> Icons.Default.CloudUpload
                            allDownloads -> Icons.Default.CloudDownload
                            else -> Icons.Default.CloudSync
                        }
                        IconButton(onClick = { hiddenTransfers.forEach { it.isHidden = false } }) {
                            Icon(icon, "Active Transfers", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (isSelectionMode) {
                        IconButton(onClick = { 
                            if (selectedFiles.size == files.size) selectedFiles = emptySet()
                            else selectedFiles = files.map { it.name }.toSet()
                        }) { Icon(Icons.Default.SelectAll, "Select All") }
                    } else {
                        IconButton(onClick = { showIpDialog = true }) { Icon(Icons.Default.SettingsEthernet, "Set IP") }
                        IconButton(onClick = { fetchFiles(currentPath) }) { Icon(Icons.Default.Refresh, "Refresh") }
                    }
                }
            )
        },
        floatingActionButton = {
            if (isSelectionMode && selectedFiles.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { downloadSelected() },
                    icon = { Icon(Icons.Default.Download, "Download") },
                    text = { Text("Download") }
                )
            } else if (!isSelectionMode && errorMsg == null && ipAddress.isNotBlank() && currentPath.isNotEmpty()) {
                FloatingActionButton(onClick = { filePicker.launch("*/*") }) { Icon(Icons.Default.UploadFile, "Upload") }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
            else if (errorMsg != null) {
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                Button(onClick = { showIpDialog = true }, modifier = Modifier.padding(16.dp)) { Text("Check Connection") }
            }
            else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        val isSelected = selectedFiles.contains(file.name)
                        // Get progress from active transfer if any
                        val activeSession = activeTransfers.find { it.id == file.name }
                        val progress = if (activeSession != null && activeSession.totalBytes > 0) {
                            activeSession.currentBytes.toFloat() / activeSession.totalBytes.toFloat()
                        } else null
                        
                        FileItemRow(
                            file = file,
                            ipAddress = ipAddress,
                            currentPath = currentPath,
                            context = context,
                            imageLoader = imageLoader,
                            transferProgress = progress,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onLongClick = {
                                isSelectionMode = true
                                selectedFiles = selectedFiles + file.name
                            },
                            onSelectToggle = {
                                selectedFiles = if (isSelected) selectedFiles - file.name else selectedFiles + file.name
                            },
                            onDownload = {
                                selectedFiles = setOf(file.name)
                                downloadSelected()
                            },
                            onClick = {
                                if (file.isDirectory) {
                                    val nextPath = if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}"
                                    fetchFiles(nextPath)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileItemRow(
    file: FileItem, 
    ipAddress: String, 
    currentPath: String, 
    context: Context,
    imageLoader: coil.ImageLoader,
    transferProgress: Float?, 
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onLongClick: () -> Unit,
    onSelectToggle: () -> Unit,
    onDownload: () -> Unit, 
    onClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    val filePath = if (currentPath.isEmpty()) file.name else "$currentPath/${file.name}"
    val fileUrl = "http://$ipAddress:8080/download?path=${Uri.encode(filePath)}"
    
    val animatedProgress by animateFloatAsState(targetValue = transferProgress ?: 0f, label = "progress")
    
    Box(modifier = Modifier.background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)) {
        if (transferProgress != null) {
            Box(modifier = Modifier.matchParentSize().fillMaxWidth(animatedProgress).background(Color(0x8000FF00)))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (isSelectionMode) onSelectToggle()
                        else if (file.isDirectory) onClick()
                        else expanded = true
                    },
                    onLongClick = {
                        if (!isSelectionMode) onLongClick()
                        else onSelectToggle()
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onSelectToggle() })
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (file.isDirectory) {
                Icon(Icons.Default.Folder, null, tint = Color(0xFFFFB300), modifier = Modifier.size(40.dp))
            } else {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                if (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "mp4", "mkv", "avi", "webm", "apk")) {
                    coil.compose.AsyncImage(
                        model = "http://$ipAddress:8080/thumbnail?path=${Uri.encode(filePath)}",
                        imageLoader = imageLoader,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image)
                    )
                } else {
                    val icon = when (ext) {
                        "pdf" -> Icons.Default.PictureAsPdf
                        "txt", "csv", "md" -> Icons.Default.Description
                        "py", "kt", "java", "html", "xml", "json" -> Icons.Default.Code
                        "mp3", "wav", "flac", "ogg", "m4a" -> Icons.Default.AudioFile
                        "docx", "doc", "rtf" -> Icons.Default.Description
                        "xlsx", "xls" -> Icons.Default.GridOn
                        else -> Icons.Default.InsertDriveFile
                    }
                    val iconTint = when (ext) {
                        "pdf" -> Color(0xFFD32F2F)
                        "mp3", "wav" -> Color(0xFF1976D2)
                        "docx", "doc" -> Color(0xFF1976D2)
                        "xlsx", "xls" -> Color(0xFF388E3C)
                        "py", "kt", "java" -> Color(0xFF512DA8)
                        else -> Color.Gray
                    }
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("${sdf.format(Date(file.lastModified))}  •  ${if (file.isDirectory) "Folder" else formatSize(file.size)}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Open / Play") }, onClick = {
                expanded = false
                val ext = file.name.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
                    "mp4", "mkv", "avi", "webm" -> "video/*"
                    "pdf" -> "application/pdf"
                    "jpg", "jpeg", "png", "gif" -> "image/*"
                    "txt", "csv" -> "text/plain"
                    "doc" -> "application/msword"
                    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    else -> "*/*"
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(fileUrl), mime)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                try {
                    context.startActivity(Intent.createChooser(intent, "Open with..."))
                } catch (e: Exception) {
                    Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                }
            })
            DropdownMenuItem(text = { Text("Download") }, onClick = {
                expanded = false
                onDownload()
            })
        }
    }
}
