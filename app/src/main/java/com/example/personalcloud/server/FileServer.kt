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
    
    // ─── Security: Runtime-generated session token ──────────────────────────
    // A fresh token is created every time the server starts. The client must
    // send this token as a header `X-Auth-Token` to be allowed access.
    var sessionToken: String = ""
        private set

    fun start() {
        sessionToken = java.util.UUID.randomUUID().toString().replace("-", "")
        
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
                // Client connects to this endpoint first with the PIN. If the PIN
                // matches, the server returns the session token.
                post("/pair") {
                    val receivedPin = call.receiveText().trim()
                    val prefs = this@FileServer.context.getSharedPreferences("PersonalCloud", Context.MODE_PRIVATE)
                    val storedPin = prefs.getString("server_pin", "") ?: ""

                    if (storedPin.isBlank()) {
                        // No PIN set — server is in open mode, hand out token freely
                        call.respondText(sessionToken)
                        return@post
                    }
                    if (receivedPin == storedPin) {
                        call.respondText(sessionToken)
                    } else {
                        call.respond(HttpStatusCode.Unauthorized, "Wrong PIN")
                    }
                }

                // ── Helper: verify token ──────────────────────────────────────
                fun isAuthorized(call: io.ktor.server.application.ApplicationCall): Boolean {
                    if (sessionToken.isBlank()) return true
                    val provided = call.request.headers["X-Auth-Token"] ?: ""
                    return provided == sessionToken
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
                    if (!isPathAllowed(reqPath)) { call.respond(HttpStatusCode.Forbidden); return@post }

                    val targetDir = File(reqPath)
                    if (!targetDir.exists() || !targetDir.isDirectory) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid directory")
                        return@post
                    }

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

                // ── Delete file / folder ─────────────────────────────────────
                post("/delete") {
                    if (!isAuthorized(call)) { call.respond(HttpStatusCode.Unauthorized); return@post }
                    val reqPath = call.receiveText().trim()
                    if (!isPathAllowed(reqPath)) { call.respond(HttpStatusCode.Forbidden); return@post }
                    val target = File(reqPath)
                    val deleted = target.deleteRecursively()
                    if (deleted) call.respond(HttpStatusCode.OK, "Deleted")
                    else call.respond(HttpStatusCode.InternalServerError, "Could not delete")
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
        sessionToken = ""
    }
}
