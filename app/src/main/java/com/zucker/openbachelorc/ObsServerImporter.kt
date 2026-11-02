package com.zucker.openbachelorc

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Imports a downloaded OpenBachelorS package (the `OpenBachelorS-master.zip`
 * straight from GitHub) into `Download/OpenBachelorS`.
 *
 * Behaviour is a **merge**, not a replace: every file in the zip overwrites its
 * counterpart on disk, but anything the server created at runtime — `sav/`,
 * `tmp/`, `multi_sav/`, downloaded assets — is left alone. Replacing the folder
 * outright would wipe the player's save data.
 *
 * GitHub zips wrap everything in a single `OpenBachelorS-master/` directory, so
 * that wrapper is stripped while extracting; the target directory already
 * carries the right name.
 */
object ObsServerImporter {

    private const val ROOT_DIR = "OpenBachelorS"

    /** Files that hold user settings and are backed up before being overwritten. */
    private val BACKUP_BEFORE_OVERWRITE = setOf("conf/config.json")

    private const val PROGRESS_EVERY = 40

    data class Result(
        val target: File,
        val files: Int,
        val bytes: Long,
        val backedUp: List<String>,
        /** False when the extracted tree has no `src/openbachelors`. */
        val looksValid: Boolean,
    )

    fun target(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        ROOT_DIR
    )

    /** True once a usable checkout exists on disk. */
    fun isInstalled(): Boolean = File(target(), "src/openbachelors").isDirectory

    fun fileCount(): Int = runCatching {
        target().walkTopDown().count { it.isFile }
    }.getOrDefault(0)

    /**
     * Streams the zip straight to disk; nothing is buffered in memory, so a
     * 400 MB package is fine.
     */
    fun import(
        context: Context,
        uri: Uri,
        onProgress: (files: Int, bytes: Long, name: String) -> Unit = { _, _, _ -> },
    ): Result = importFrom(
        context,
        open = { context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件") },
        onProgress = onProgress,
    )

    /** Same as [import], but fed from any stream (a downloaded file, for example). */
    fun importFrom(
        context: Context,
        open: () -> InputStream,
        onProgress: (files: Int, bytes: Long, name: String) -> Unit = { _, _, _ -> },
    ): Result {
        if (!StoragePermission.isGranted()) {
            error("需要「所有文件访问权限」才能写入 Download")
        }

        val root = target()
        root.mkdirs()

        var files = 0
        var bytes = 0L
        val backedUp = mutableListOf<String>()

        open().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var rootPrefix: String? = null
                var prefixDecided = false

                while (true) {
                    val entry = zip.nextEntry ?: break

                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    var name = entry.name.replace('\\', '/')

                    // Decide once, from the first real entry, whether the zip
                    // wraps everything in a single top-level directory.
                    if (!prefixDecided) {
                        prefixDecided = true
                        if (name.contains('/')) {
                            rootPrefix = name.substringBefore('/') + "/"
                        }
                    }

                    rootPrefix?.let { prefix ->
                        if (name.startsWith(prefix)) name = name.removePrefix(prefix)
                    }

                    if (name.isEmpty() ||
                        name.startsWith("/") ||
                        name.split('/').any { it == ".." }
                    ) {
                        zip.closeEntry()
                        continue
                    }

                    val destination = File(root, name)
                    destination.parentFile?.mkdirs()

                    if (name in BACKUP_BEFORE_OVERWRITE && destination.isFile) {
                        runCatching {
                            File(destination.parentFile, "${destination.name}.obc-bak")
                                .writeBytes(destination.readBytes())
                        }
                        backedUp += name
                    }

                    val out = destination.outputStream()
                    out.use {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            it.write(buffer, 0, read)
                            bytes += read
                        }
                    }

                    files++
                    if (files % PROGRESS_EVERY == 0) onProgress(files, bytes, name)
                    zip.closeEntry()
                }
            }
        }

        onProgress(files, bytes, "完成")

        return Result(
            target = root,
            files = files,
            bytes = bytes,
            backedUp = backedUp,
            looksValid = File(root, "src/openbachelors").isDirectory,
        )
    }
}
