package com.zucker.openbachelorc

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Owns the Termux compatibility package (`obs-compat.zip`).
 *
 * The package lives *inside the app* and is what gets deployed to the OBS
 * folder. That split is deliberate:
 *
 *   import  - the user drops in a newer zip to replace the app's copy, so the
 *             compatibility layer can be updated without shipping a new APK.
 *   deploy  - that stored copy is unpacked into Download/OpenBachelorS, where
 *             the user can still edit requirements.txt and the shims.
 *
 * The zip is also the version unit: COMPAT_VERSION is derived from the content,
 * so "is the deployed copy current?" is an exact comparison, not a guess.
 */
object ObsOverlay {

    /** Zip shipped inside the APK (packed by scripts/build-android.ps1). */
    private const val ASSET_ZIP = "obs-compat.zip"

    /** Entry holding the content-derived version string. */
    private const val VERSION_ENTRY = "COMPAT_VERSION"

    /** The active package, in app-private storage so it survives updates. */
    private const val ACTIVE_FILE = "obs-compat.zip"

    /** Previous package, kept so a bad import can be undone by hand. */
    private const val PREVIOUS_FILE = "obs-compat.previous.zip"

    /** Present once the user has imported their own package. */
    private const val CUSTOM_MARKER = "obs-compat.custom"

    /** Version marker written next to the OBS checkout after a deploy. */
    private const val MARKER = ".obc-overlay.version"

    /** Entries a valid package must contain - guards against importing junk. */
    private val REQUIRED_ENTRIES = setOf(VERSION_ENTRY, "setup.sh", "start.py", "requirements.txt")

    /**
     * Files whose local edits must survive a deploy.
     *
     * `requirements.txt` is exactly the file the user is expected to update
     * when dependencies change, so it is only created when missing.
     */
    private val PRESERVE_LOCAL_EDITS = setOf("requirements.txt")

    data class Target(val dir: File, val isProjectRoot: Boolean)

    data class Status(
        val target: Target,
        /** Version of the package currently stored in the app. */
        val activeVersion: String?,
        /** Version bundled with this APK. */
        val bundledVersion: String?,
        /** Version currently deployed into the OBS folder. */
        val deployedVersion: String?,
        val termuxInstalled: Boolean,
        val canWrite: Boolean,
    ) {
        /** True when the app still uses the version that shipped with it. */
        val usingBundled: Boolean
            get() = activeVersion != null && activeVersion == bundledVersion

        /** True when the OBS folder already holds the active package. */
        val deployedCurrent: Boolean
            get() = activeVersion != null && deployedVersion == activeVersion
    }

    data class ImportResult(
        val version: String,
        val replacedVersion: String?,
    )

    data class DeployResult(
        val version: String,
        val written: Int,
        val skipped: Int,
        val backedUp: List<String>,
        val target: Target,
    )

    // ------------------------------------------------------------------
    // package storage
    // ------------------------------------------------------------------

    private fun activeFile(context: Context) = File(context.filesDir, ACTIVE_FILE)

    private fun previousFile(context: Context) = File(context.filesDir, PREVIOUS_FILE)

    private fun customMarker(context: Context) = File(context.filesDir, CUSTOM_MARKER)

    /**
     * Returns the active package, seeding it from the APK when needed.
     *
     * A package the user imported always wins. Otherwise the APK is
     * authoritative, so installing a new app version refreshes the layer
     * without the user having to do anything.
     */
    fun ensureActive(context: Context): File {
        val active = activeFile(context)

        if (active.isFile && active.length() > 0) {
            if (customMarker(context).isFile) return active

            val current = runCatching { versionOf(active.readBytes()) }.getOrNull()
            if (current != null && current == bundledVersion(context)) return active
        }

        context.assets.open(ASSET_ZIP).use { input ->
            active.outputStream().use { input.copyTo(it) }
        }
        return active
    }

    fun activeVersion(context: Context): String? = runCatching {
        versionOf(ensureActive(context).readBytes())
    }.getOrNull()

    fun bundledVersion(context: Context): String? = runCatching {
        context.assets.open(ASSET_ZIP).use { versionOf(it.readBytes()) }
    }.getOrNull()

    fun target(): Target {
        val project = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "OpenBachelorS"
        )
        if (project.isDirectory) return Target(project, isProjectRoot = true)
        return Target(File(project.parentFile, "OpenBachelorC/obs-overlay"), isProjectRoot = false)
    }

    fun isTermuxInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    fun deployedVersion(context: Context, target: Target = target()): String? {
        val marker = File(target.dir, MARKER)
        if (!marker.isFile) return null
        return runCatching { marker.readText().trim() }.getOrNull()?.ifEmpty { null }
    }

    fun status(context: Context): Status {
        val target = target()
        return Status(
            target = target,
            activeVersion = activeVersion(context),
            bundledVersion = bundledVersion(context),
            deployedVersion = deployedVersion(context, target),
            termuxInstalled = isTermuxInstalled(context),
            canWrite = StoragePermission.isGranted(),
        )
    }

    // ------------------------------------------------------------------
    // import (user -> app)
    // ------------------------------------------------------------------

    /**
     * Replaces the app's stored package with the one at [uri].
     *
     * The incoming zip is validated before anything is touched, and the
     * previous package is kept as `obs-compat.previous.zip`.
     */
    fun importPackage(context: Context, uri: Uri): Result<ImportResult> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取所选文件")
        importBytes(context, bytes)
    }

    /** Same as [importPackage], but from a file the app downloaded itself. */
    fun importPackage(context: Context, file: File): Result<ImportResult> =
        runCatching { importBytes(context, file.readBytes()) }

    private fun importBytes(context: Context, bytes: ByteArray): ImportResult {
        val version = versionOf(bytes) ?: error("压缩包缺少 $VERSION_ENTRY，不是有效的兼容层")

        val entries = runCatching { entryNames(bytes) }.getOrElse { error("压缩包已损坏：${it.message}") }
        val missing = REQUIRED_ENTRIES - entries
        if (missing.isNotEmpty()) {
            error("压缩包缺少必要文件：${missing.joinToString(", ")}")
        }

        val active = ensureActive(context)
        val replaced = activeVersion(context)

        if (active.isFile) {
            runCatching { active.copyTo(previousFile(context), overwrite = true) }
        }
        active.writeBytes(bytes)

        // Marks the layer as user-controlled so an app update will not
        // overwrite it again.
        customMarker(context).writeText(version)

        return ImportResult(version, replaced)
    }

    /** Restores the package that shipped with this APK. */
    fun resetToBundled(context: Context): Result<String> = runCatching {
        val active = activeFile(context)
        if (active.isFile) {
            runCatching { active.copyTo(previousFile(context), overwrite = true) }
        }
        context.assets.open(ASSET_ZIP).use { input ->
            active.outputStream().use { input.copyTo(it) }
        }
        customMarker(context).delete()
        versionOf(active.readBytes()) ?: error("内置兼容层损坏")
    }

    // ------------------------------------------------------------------
    // deploy (app -> OBS folder)
    // ------------------------------------------------------------------

    fun deploy(context: Context): Result<DeployResult> = runCatching {
        if (!StoragePermission.isGranted()) {
            error("需要「所有文件访问权限」才能写入 Download")
        }

        val bytes = ensureActive(context).readBytes()
        val version = versionOf(bytes) ?: error("兼容层缺少 $VERSION_ENTRY")

        val target = target()
        target.dir.mkdirs()

        var written = 0
        var skipped = 0
        val backedUp = mutableListOf<String>()

        openZip(bytes).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')

                if (entry.isDirectory || name.startsWith("/") || name.split('/').any { it == ".." }) {
                    zip.closeEntry()
                    continue
                }

                val content = zip.readBytes()

                if (name == VERSION_ENTRY) {
                    zip.closeEntry()
                    continue
                }

                val destination = File(target.dir, name)
                destination.parentFile?.mkdirs()

                if (destination.isFile) {
                    val existing = runCatching { destination.readBytes() }.getOrNull()

                    if (existing != null && existing.contentEquals(content)) {
                        skipped++
                        zip.closeEntry()
                        continue
                    }

                    if (name.substringAfterLast('/') in PRESERVE_LOCAL_EDITS) {
                        skipped++
                        zip.closeEntry()
                        continue
                    }

                    if (existing != null) {
                        runCatching {
                            File(destination.parentFile, "${destination.name}.obc-bak")
                                .writeBytes(existing)
                        }
                        backedUp += name
                    }
                }

                if (runCatching { destination.writeBytes(content) }.isSuccess) written++ else skipped++
                zip.closeEntry()
            }
        }

        File(target.dir, MARKER).writeText(version)
        DeployResult(version, written, skipped, backedUp, target)
    }

    /** Copies the active package out so the user can inspect or share it. */
    fun exportActive(context: Context): File? {
        if (!StoragePermission.isGranted()) return null

        val active = ensureActive(context)
        val version = activeVersion(context) ?: return null

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "OpenBachelorC"
        )
        dir.mkdirs()

        val out = File(dir, "obs-compat-$version.zip")
        return runCatching {
            active.copyTo(out, overwrite = true)
            out
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // zip helpers
    // ------------------------------------------------------------------

    private fun openZip(bytes: ByteArray): ZipInputStream =
        ZipInputStream(bytes.inputStream().buffered())

    private fun versionOf(bytes: ByteArray): String? = openZip(bytes).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: return null
            if (entry.name == VERSION_ENTRY) {
                return String(zip.readBytes(), Charsets.UTF_8).trim().ifEmpty { null }
            }
            zip.closeEntry()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun entryNames(bytes: ByteArray): Set<String> = buildSet {
        openZip(bytes).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry.name.replace('\\', '/'))
                zip.closeEntry()
            }
        }
    }

    @Suppress("unused")
    private fun readAll(stream: InputStream): ByteArray = stream.use { it.readBytes() }
}
