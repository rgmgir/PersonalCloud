package com.example.personalcloud.server

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.ktor.server.application.install
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileServer(private val context: Context, private val port: Int = 8080) {
    private var server: io.ktor.server.engine.ApplicationEngine? = null
    var rootPaths: List<String> = listOf("/storage/emulated/0")

    fun start() {
        server = embeddedServer(Netty, port = port, configure = {
            responseWriteTimeoutSeconds = 3600 // 1 hour for big zips
            requestReadTimeoutSeconds = 3600
        }) {
            install(CORS) {
                anyHost()
            }
            install(PartialContent)
            
            routing {
                get("/") { call.respondText("Running") }
                
                get("/files") {
                    val reqPath = call.request.queryParameters["path"] ?: ""
                    
                    if (reqPath.isEmpty()) {
                        val validPaths = rootPaths.filter { it.isNotBlank() && File(it).exists() }
                        val virtualRoots = validPaths.map { p ->
                            mapOf("name" to p, "isDirectory" to true, "size" to 0L, "lastModified" to File(p).lastModified())
                        }
                        val json = virtualRoots.joinToString(prefix = "[", postfix = "]") {
                            "{\"name\":\"${it["name"]}\",\"isDirectory\":${it["isDirectory"]},\"size\":${it["size"]},\"lastModified\":${it["lastModified"]}}"
                        }
                        call.respondText(json, io.ktor.http.ContentType.Application.Json)
                        return@get
                    }

                    val targetDir = File(reqPath)
                    val isAllowed = rootPaths.filter { it.isNotBlank() }.any { targetDir.canonicalPath.startsWith(File(it).canonicalPath) }
                    
                    if (!isAllowed || !targetDir.exists() || !targetDir.isDirectory) {
                        call.respond(HttpStatusCode.NotFound, "Invalid directory")
                        return@get
                    }
                    
                    val files = targetDir.listFiles()?.map { f ->
                        mapOf("name" to f.name, "isDirectory" to f.isDirectory, "size" to f.length(), "lastModified" to f.lastModified())
                    } ?: emptyList()
                    val sortedFiles = files.sortedWith(compareBy({ !(it["isDirectory"] as Boolean) }, { (it["name"] as String).lowercase() }))
                    val json = sortedFiles.joinToString(prefix = "[", postfix = "]") {
                        "{\"name\":\"${it["name"]}\",\"isDirectory\":${it["isDirectory"]},\"size\":${it["size"]},\"lastModified\":${it["lastModified"]}}"
                    }
                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                }
                
                
                get("/thumbnail") {
                    val reqPath = call.request.queryParameters["path"] ?: ""
                    val targetFile = File(reqPath)
                    val isAllowed = rootPaths.filter { it.isNotBlank() }.any { targetFile.canonicalPath.startsWith(File(it).canonicalPath) }
                    
                    if (!isAllowed || !targetFile.exists() || targetFile.isDirectory) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    
                    val ext = targetFile.extension.lowercase()
                    try {
                        val bitmap = when (ext) {
                            "jpg", "jpeg", "png", "webp" -> {
                                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                                android.graphics.BitmapFactory.decodeFile(targetFile.absolutePath, options)
                            }
                            "mp4", "mkv", "avi", "webm" -> {
                                val retriever = android.media.MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(targetFile.absolutePath)
                                    val frame = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    frame
                                } catch (e: Exception) { null } finally { retriever.release() }
                            }
                            "apk" -> {
                                val pm = this@FileServer.context.packageManager
                                val pi = pm.getPackageArchiveInfo(targetFile.absolutePath, 0)
                                if (pi != null) {
                                    pi.applicationInfo.sourceDir = targetFile.absolutePath
                                    pi.applicationInfo.publicSourceDir = targetFile.absolutePath
                                    val drawable = pi.applicationInfo.loadIcon(pm)
                                    val bmp = android.graphics.Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
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
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                            call.respondBytes(stream.toByteArray(), ContentType.Image.JPEG)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }

                get("/download") {
                    val reqPath = call.request.queryParameters["path"] ?: ""
                    val file = File(reqPath)
                    val isAllowed = rootPaths.filter { it.isNotBlank() }.any { file.canonicalPath.startsWith(File(it).canonicalPath) }
                    if (isAllowed && file.exists() && file.isFile) {
                        call.respondFile(file)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                post("/downloadZip") {
                    val reqPath = call.request.queryParameters["path"] ?: ""
                    val body = call.receiveText()
                    val fileNames = JSONArray(body)
                    
                    val targetDir = File(reqPath)
                    val isAllowed = rootPaths.filter { it.isNotBlank() }.any { targetDir.canonicalPath.startsWith(File(it).canonicalPath) }
                    if (!isAllowed || !targetDir.exists() || !targetDir.isDirectory) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid directory")
                        return@post
                    }

                    call.respondOutputStream(ContentType.Application.Zip, HttpStatusCode.OK) {
                        ZipOutputStream(this).use { zos ->
                            for (i in 0 until fileNames.length()) {
                                val name = fileNames.getString(i)
                                val fileToZip = File(targetDir, name)
                                
                                fun addFileToZip(file: File, parentPath: String) {
                                    if (file.isDirectory) {
                                        file.listFiles()?.forEach { child ->
                                            addFileToZip(child, "$parentPath${file.name}/")
                                        }
                                    } else {
                                        try {
                                            zos.putNextEntry(ZipEntry("$parentPath${file.name}"))
                                            file.inputStream().use { it.copyTo(zos, 128 * 1024) }
                                            zos.closeEntry()
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                                if (fileToZip.exists()) addFileToZip(fileToZip, "")
                            }
                        }
                    }
                }

                post("/upload") {
                    val reqPath = call.request.queryParameters["path"] ?: ""
                    val fileName = call.request.queryParameters["name"] ?: "uploaded_file"
                    val targetDir = File(reqPath)
                    val isAllowed = rootPaths.filter { it.isNotBlank() }.any { targetDir.canonicalPath.startsWith(File(it).canonicalPath) }
                    
                    if (!isAllowed || !targetDir.exists() || !targetDir.isDirectory) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid directory")
                        return@post
                    }
                    try {
                        val file = File(targetDir, fileName)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            call.receiveChannel().toInputStream().use { input ->
                                file.outputStream().use { output ->
                                    val buffer = ByteArray(256 * 1024)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                        }
                        call.respond(HttpStatusCode.OK, "Uploaded successfully")
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "Upload failed")
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
