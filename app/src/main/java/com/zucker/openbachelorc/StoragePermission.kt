package com.zucker.openbachelorc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * Small wrapper around the one special permission this app needs.
 *
 * `MANAGE_EXTERNAL_STORAGE` is what allows writing the Termux overlay into
 * `Download/`, which is the whole point of exporting it — the files have to be
 * reachable by a file manager and by Termux.
 *
 * It is *not* used for the dump feature: this permission cannot read another
 * app's `Android/data` directory (an Android 11+ platform restriction), which
 * is why that path goes through root instead.
 */
object StoragePermission {

    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    /**
     * Opens the most specific "All files access" screen this ROM supports.
     *
     * Some OEM builds resolve the per-app intent to the Settings root, which
     * looks like nothing happened, so the resolved target is checked first.
     */
    fun openSettings(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")

        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(packageUri)

        val resolved = runCatching {
            context.packageManager.resolveActivity(perApp, 0)?.activityInfo?.name.orEmpty()
        }.getOrDefault("")

        val candidates = buildList {
            if (resolved.contains("ExternalStorage", ignoreCase = true)) add(perApp)
            add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }

        for (intent in candidates) {
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return true
        }
        return false
    }
}
