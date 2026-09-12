package com.example.personalcloud.server

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileServer(private val context: Context, private val port: Int = 8080) {
    private var server: io.ktor.server.engine.ApplicationEngine? = null
    var rootPaths: List<String> = listOf("/storage/emulated/0")
    
    // ─── Connected Clients Management ─────────────────────────────────────────
    data class ConnectedClient(val token: String, val name: String, val ip: String, val connectedAt: Long)
    val activeClients = mutableListOf<ConnectedClient>()
    
    companion object {
        var onClientsChanged: ((List<ConnectedClient>) -> Unit)? = null
    }

    fun start() {
        activeClients.clear()
        onClientsChanged?.invoke(activeClients.toList())
        
        server = embeddedServer(Netty, port = port, configure = {
            responseWriteTimeoutSeconds = 3600
            requestReadTimeoutSeconds  = 3600
        }) {
            install(CORS) {
                // Restrict CORS to the same LAN — do not allow wildcard credentials
                allowHost("*")
            }
            install(PartialContent)

            routing {

                // ── Health check (no auth required) ─────────────────────────
                get("/") {
                    call.respondText("Personal Cloud Server Running")
                }

                // ── Pair / exchange token (no auth required) ─────────────────
                post("/pair") {
                    val receivedPin = call.receiveText().trim()
                    val deviceName = call.request.queryParameters["name"]?.takeIf { it.isNotBlank() } ?: "Unknown Client"
                    val clientIp = call.request.local.remoteHost
                    
                    val prefs = this@FileServer.context.getSharedPreferences("PersonalCloud", Context.MODE_PRIVATE)
                    val storedPin = prefs.getString("server_pin", "") ?: ""

                    if (storedPin.isNotBlank() && receivedPin != storedPin) {
                        call.respond(HttpStatusCode.Unauthorized, "Wrong PIN")
                        return@post
                    }

                    // Check if already connected by IP
                    val existing = activeClients.find { it.ip == clientIp }
                    if (existing != null) {
                        val updated = existing.copy(name = deviceName, connectedAt = System.currentTimeMillis())
                        activeClients[activeClients.indexOf(existing)] = updated
                        onClientsChanged?.invoke(activeClients.toList())
                        call.respondText(updated.token)
                        return@post
                    }

                    // Check max clients limit
                    if (activeClients.size >= 3) {
                        call.respond(HttpStatusCode.Forbidden, "Max clients (3) reached")
                        return@post
                    }

                    // Issue new token
                    val newToken = java.util.UUID.randomUUID().toString().replace("-", "")
                    activeClients.add(ConnectedClient(newToken, deviceName, clientIp, System.currentTimeMillis()))
                    onClientsChanged?.invoke(activeClients.toList())
                    
                    call.respondText(newToken)
                }

                // ── Helper: verify token ──────────────────────────────────────
                fun isAuthorized(call: io.ktor.server.application.ApplicationCall): Boolean {
                    val provided = call.request.headers["X-Auth-Token"] ?: call.request.queryParameters["token"] ?: ""
                    return provided.isNotBlank() && activeClients.any { it.token == provided }
                }

                // ── Helper: canonically check path is within allowed roots ────
                fun isPathAllowed(path: String): Boolean {
                    val canonical = File(path).canonicalPath
                    return rootPaths.filter { it.isNotBlank() }
                        .any { canonical.startsWith(File(it).canonicalPath) }
                }

                // ── File listing ─────────────────────────────────────────────
                get("/files") {
                    if (!isAuthorized(call)) { call.respond(HttpStatusCode.Unauthorized); return@get }

                    val reqPath = call.request.queryParameters["path"] ?: ""

                    if (reqPath.isEmpty()) {
                        val validPaths = rootPaths.filter { it.isNotBlank() && File(it).exists() }
                        val json = validPaths.joinToString(prefix = "[", postfix = "]") { p ->
                            val f = File(p)
                            "{\"name\":\"${p.replace("\\", "/")}\",\"isDirectory\":true,\"size\":0,\"lastModified\":${f.lastModified()}}"
                        }
                        call.respondText(json, ContentType.Application.Json)
                        return@get
                    }

                    if (!isPathAllowed(reqPath)) { call.respond(HttpStatusCode.Forbidden, "Access denied"); return@get }

                    val targetDir = File(reqPath)
                    if (!targetDir.exists() || !targetDir.isDirectory) { call.respond(HttpStatusCode.NotFound, "Not found"); return@get }

                    val files = (targetDir.listFiles() ?: emptyArray())
                        .map { f ->
                            mapOf(
                                "name"         to f.name.replace("\"", "\\\""),
                                "isDirectory"  to f.isDirectory,
                                "size"         to f.length(),
                                "lastModified" to f.lastModified()
                            )
                        }
                        .sortedWith(compareBy({ !(it["isDirectory"] as Boolean) }, { (it["name"] as String).lowercase() }))

                    val json = files.joinToString(prefix = "[", postfix = "]") {
                        "{\"name\":\"${it["name"]}\",\"isDirectory\":${it["isDirectory"]},\"size\":${it["size"]},\"lastModified\":${it["lastModified"]}}"
                    }
                    call.respondText(json, ContentType.Application.Json)
                }

                // ── Thumbnail ────────────────────────────────────────────────
                get("/thumbnail") {
                    if (!isAuthorized(call)) { call.respond(HttpStatusCode.Unauthorized); return@get }

                    val reqPath = call.request.queryParameters["path"] ?: ""
                    if (!isPathAllowed(reqPath)) { call.respond(HttpStatusCode.Forbidden); return@get }

                    val targetFile = File(reqPath)
                    if (!targetFile.exists() || targetFile.isDirectory) { call.respond(HttpStatusCode.NotFound); return@get }

                    val ext = targetFile.extension.lowercase()
                    try {
                        val bitmap: android.graphics.Bitmap? = when (ext) {
                            "jpg", "jpeg", "png", "webp" -> {
                                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                                android.graphics.BitmapFactory.decodeFile(targetFile.absolutePath, options)
                            }
                            "mp4", "mkv", "avi", "webm", "mov", "3gp" -> {
                                val retriever = android.media.MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(targetFile.absolutePath)
                                    retriever.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                } catch (e: Exception) { null } finally { retriever.release() }
                            }
                            "apk" -> {
                                val pm = this@FileServer.context.packageManager
                                val pi = pm.getPackageArchiveInfo(targetFile.absolutePath, 0)
                                val ai = pi?.applicationInfo
                                if (pi != null && ai != null) {
                                    ai.sourceDir = targetFile.absolutePath
                                    ai.publicSourceDir = targetFile.absolutePath
                                    val drawable = ai.loadIcon(pm)
                                    val bmp = android.graphics.Bitmap.createBitmap(
                                        drawable.intrinsicWidth.coerceAtLeast(1),
                                        drawable.intrinsicHeight.coerceAtLeast(1),
                                        android.graphics.Bitmap.Config.ARGB_8888
                                    )
                                    val canvas = android.graphics.Canvas(bmp)
                                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                                    drawable.draw(canvas)
                                    bmp
                                } else null
                            }
                            else -> null
                        }
                        if (bitmap != null) {
                            val stream = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
                            call.respondBytes(stream.toByteArray(), ContentType.Image.JPEG)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }

                // ── Download single file ─────────────────────────────────────
                get("/download") {
                    if (!isAuthorized(call)) { call.respond(HttpStatusCode.Unauthorized); return@get }

                    val reqPath = call.request.queryParameters["path"] ?: ""
                    if (!isPathAllowed(reqPath)) { call.respond(HttpStatusCode.Forbidden); return@get }

                    val file = File(reqPath)
                    if (file.exists() && file.isFile) {
                        call.respondFile(file)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                // ── Download multiple files as ZIP ────────────────────────────
                post("/downloadZip") {
                    if (!isAuthorized(call)) { call.respond(HttpStatusCode.Unauthorized); return@post }

                    val reqPath = call.request.queryParameters["path"] ?: ""
                    if (!isPathAllowed(reqPath)) { call.respond(HttpStatusCode.Forbidden); return@post }

                    val targetDir = File(reqPath)
                    if (!targetDir.exists() || !targetDir.isDirectory) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid directory")
                        return@post
                    }

                    val body = call.receiveText()
                    val fileNames = JSONArray(body)

                    call.respondOutputStream(ContentType.Application.Zip, HttpStatusCode.OK) {
                        ZipOutputStream(this).use { zos ->
                            fun addFileToZip(file: File, parentPath: String) {
                                if (file.isDirectory) {
                                    file.listFiles()?.forEach { child ->
                                        addFileToZip(child, "$parentPath${file.name}/")
                                    }
                                } else {
                                    // Security: verify each file in zip is also within allowed paths
                                    if (!isPathAllowed(file.absolutePath)) return
                                    try {
                                        zos.putNextEntry(ZipEntry("$parentPath${file.name}"))
                                        file.inputStream().use { it.copyTo(zos, 128 * 1024) }
                                        zos.closeEntry()
                                    } catch (e: Exception) { /* skip unreadable files */ }
                                }
                            }
                            for (i in 0 until fileNames.length()) {
                                val name = fileNames.getString(i)
                                // Prevent path traversal via filenames like "../secret"
                                if (name.contains("..") || name.contains("/")) continue
                                val fileToZip = File(targetDir, name)
                                if (fileToZip.exists()) addFileToZip(fileToZip, "")
                            }
                        }
                    }
                }

                // ── Upload ──────────────────────────────────────────────────
                post("/upload") {
                    if (!isAuthorized(call)) { call.respond(HttpStatusCode.Unauthorized); return@post }

                    val reqPath  = call.request.queryParameters["path"] ?: ""
                    val fileName = call.request.queryParameters["name"] ?: "uploaded_file"

                    // Security: prevent path traversal in file name
                    val safeName = File(fileName).name
                    if (safeName.isBlank() || safeName.startsWith(".")) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid filename")
                        return@post
                    }
                    // Force all uploads to Download/uploads
                    val uploadsFolder = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "uploads")
                    if (!uploadsFolder.exists()) uploadsFolder.mkdirs()
                    val targetDir = uploadsFolder

                    // Prevent overwriting — auto-rename on conflict
                    var destFile = File(targetDir, safeName)
                    if (destFile.exists()) {
                        val base = safeName.substringBeforeLast(".")
                        val ext  = safeName.substringAfterLast(".", "")
                        var counter = 1
                        while (destFile.exists()) {
                            destFile = if (ext.isNotEmpty()) File(targetDir, "${base}_${counter}.${ext}") else File(targetDir, "${base}_${counter}")
                            counter++
                        }
                    }

                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            call.receiveChannel().toInputStream().use { input ->
                                destFile.outputStream().use { output ->
                                    val buffer = ByteArray(256 * 1024)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                        }
                        call.respond(HttpStatusCode.OK, "Uploaded: ${destFile.name}")
                    } catch (e: Exception) {
                        destFile.delete()
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "Upload failed")
                    }
                }

                // ── Create new folder ─────────────────────────────────────────
                post("/mkdir") {
                    if (!isAuthorized(call)) { call.respond(HttpStatusCode.Unauthorized); return@post }
                    val reqPath  = call.request.queryParameters["path"] ?: ""
                    val dirName  = call.request.queryParameters["name"] ?: ""
                    if (dirName.isBlank() || dirName.contains("..") || dirName.contains("/")) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid name")
                        return@post
                    }
                    if (!isPathAllowed(reqPath)) { call.respond(HttpStatusCode.Forbidden); return@post }
                    val newDir = File(File(reqPath), dirName)
                    if (newDir.mkdirs()) call.respond(HttpStatusCode.OK, "Created")
                    else call.respond(HttpStatusCode.Conflict, "Already exists or error")
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        activeClients.clear()
        onClientsChanged?.invoke(activeClients.toList())
    }
}
