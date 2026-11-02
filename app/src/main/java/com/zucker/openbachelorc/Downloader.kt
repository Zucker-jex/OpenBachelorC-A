package com.zucker.openbachelorc

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny HTTP downloader for the two packages the app can fetch itself.
 *
 * Deliberately dependency-free (no OkHttp) because the whole point is that a
 * user with nothing but this APK can bootstrap the server side: download the
 * upstream OpenBachelorS archive and the latest compatibility package.
 */
object Downloader {

    private const val USER_AGENT = "OpenBachelorC-Android"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    data class Progress(val bytes: Long, val total: Long) {
        val percent: Int
            get() = if (total <= 0) 0 else ((bytes * 100) / total).toInt().coerceIn(0, 100)
    }

    /** Fetches a small text resource (used for the GitHub release API). */
    fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code ${connection.responseMessage ?: ""}".trim())
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams [url] into [target]. Follows redirects (GitHub hands archive URLs
     * off to codeload), and writes to a `.part` file first so a failed download
     * never leaves a truncated zip behind.
     */
    fun download(
        url: String,
        target: File,
        onProgress: (Progress) -> Unit = {},
    ): File {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code ${connection.responseMessage ?: ""}".trim())
            }

            val total = connection.contentLengthLong

            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(Progress(copied, total))
                    }
                }
            }

            if (part.length() == 0L) throw IOException("下载内容为空")

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return target
        } finally {
            connection.disconnect()
            runCatching { part.delete() }
        }
    }
}
