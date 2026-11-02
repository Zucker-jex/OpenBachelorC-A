package com.zucker.openbachelorc

import org.json.JSONObject

/**
 * Finds the download URL of the newest compatibility package.
 *
 * Asking the GitHub API beats a hard-coded URL: the release asset is named
 * `obs-compat-<version>.zip`, and the version changes with the content, so
 * `releases/latest/download/<name>` only works once a fixed-name alias has been
 * published. Reading the release's asset list works with either layout.
 */
object CompatRelease {

    private const val API_LATEST =
        "https://api.github.com/repos/Zucker-jex/OpenBachelorS-Compat/releases/latest"

    /** Stable alias uploaded by the release workflow, used as a fallback. */
    private const val ALIAS_URL = AppInfo.COMPAT_LATEST_ZIP

    fun latestZipUrl(): String {
        val fromApi = runCatching {
            val body = Downloader.fetchText(API_LATEST)
            val assets = JSONObject(body).optJSONArray("assets") ?: return@runCatching null

            var versioned: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                if (!name.endsWith(".zip", ignoreCase = true)) continue

                val url = asset.optString("browser_download_url")
                if (url.isBlank()) continue

                // Prefer the fixed-name alias when it exists.
                if (name.equals("obs-compat.zip", ignoreCase = true)) return@runCatching url
                if (name.startsWith("obs-compat-")) versioned = url
            }
            versioned
        }.getOrNull()

        return fromApi?.takeIf { it.isNotBlank() } ?: ALIAS_URL
    }
}
