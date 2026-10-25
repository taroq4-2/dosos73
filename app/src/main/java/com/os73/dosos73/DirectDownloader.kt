package com.os73.dosos73

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DirectDownloader(private val client: OkHttpClient = secureClient()) {
    @Volatile private var activeCall: okhttp3.Call? = null
    fun cancel() { activeCall?.cancel() }

    private fun request(url: String, head: Boolean = false, probe: Boolean = false): Response {
        var current = Safety.url(url)
        repeat(6) {
            val request = Request.Builder().url(current).header("Accept-Encoding", "identity")
                .header("User-Agent", "dosos73/${BuildConfig.VERSION_NAME} (Android)")
                .apply { if (head) head(); if(probe) header("Range", "bytes=0-0") }.build()
            val call = client.newCall(request)
            activeCall = call
            val response = call.execute()
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                response.close()
                if (next == null || (current.startsWith("https:") && next.scheme != "https")) throw IOException("Invalid redirect")
                current = Safety.url(next.toString())
            } else return response
        }
        throw IOException("Too many redirects")
    }

    suspend fun analyze(url: String): Analysis? = withContext(Dispatchers.IO) {
        val head = request(url, true)
        val candidate = if (head.code in setOf(403,405,501)) {
            head.close()
            request(url, probe = true)
        } else head
        candidate.use { response ->
            if (!response.isSuccessful) return@withContext null
            val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val ext = response.request.url.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase().orEmpty()
            // HTML and unknown responses must go through the extractor, not be saved as media.
            if (mime.startsWith("text/") || mime.contains("json") || mime.isBlank()) return@withContext null
            val kind = when { mime.startsWith("video/") -> Kind.VIDEO; mime.startsWith("audio/") -> Kind.AUDIO; else -> Kind.FILE }
            if (!(mime.startsWith("video/") || mime.startsWith("audio/") || mime == "application/octet-stream" ||
                mime == "application/pdf" || mime == "application/zip")) return@withContext null
            val safeExt = ext.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: when(kind) { Kind.VIDEO -> "mp4"; Kind.AUDIO -> "m4a"; else -> "bin" }
            Analysis(url, response.request.url.pathSegments.lastOrNull()?.substringBeforeLast('.')?.take(100).orEmpty().ifBlank { "download" },
                listOf(Choice("direct", kind, safeExt, "Original")), true,
                if (response.code == 206) Regex("bytes 0-0/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                else response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 })
        }
    }

    suspend fun download(url: String, destination: File, progress: (Long, Long?, Long) -> Unit) = withContext(Dispatchers.IO) {
        request(url).use { response ->
            if (!response.isSuccessful || response.code == 206) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val mime = response.header("Content-Type").orEmpty()
            if (mime.startsWith("text/html", true)) throw IOException("HTML is not a file")
            val total = body.contentLength().takeIf { it >= 0 }
            var count = 0L
            var lastBytes = 0L
            var last = System.nanoTime()
            body.byteStream().use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n == -1) break
                    count += n
                    output.write(buffer, 0, n)
                    val now = System.nanoTime()
                    if (now - last > 250_000_000) {
                        progress(count, total, ((count-lastBytes).toDouble()*1_000_000_000/(now-last)).toLong())
                        last = now; lastBytes = count
                    }
                }
                output.fd.sync()
            } }
            if (count == 0L || (total != null && count != total)) throw IOException("Incomplete transfer")
            progress(count, total, 0)
        }
    }
    companion object {
        fun secureClient() = OkHttpClient.Builder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = Safety.resolve(hostname)
        })
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.HOURS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }
}
