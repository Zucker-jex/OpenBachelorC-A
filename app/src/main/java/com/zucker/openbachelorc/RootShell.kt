package com.zucker.openbachelorc

import java.util.concurrent.TimeUnit

/**
 * Minimal `su` helper.
 *
 * Needed because Android 11+ blocks access to another app's `Android/data`
 * directory for *every* app, including ones holding MANAGE_EXTERNAL_STORAGE —
 * only root (or a PC-side `adb pull`) can read it.
 */
object RootShell {

    /** Shell-quotes a path so names containing spaces or parentheses survive. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Runs a command as root; null when `su` is missing or timed out. */
    fun run(command: String, timeoutSeconds: Long = 30): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching null
        }
        output
    }.getOrNull()

    fun available(): Boolean = probe().granted

    /**
     * Runs `id` and reports both the verdict and the raw output.
     *
     * The output matters: "permission denied" means the root manager has not
     * approved this app yet, while "not found" means `su` is missing entirely —
     * two very different messages for the user.
     */
    fun probe(): Probe {
        val output = run("id", timeoutSeconds = 10)
        return Probe(
            granted = output?.contains("uid=0") == true,
            output = output?.trim().orEmpty(),
        )
    }

    data class Probe(val granted: Boolean, val output: String)
}
