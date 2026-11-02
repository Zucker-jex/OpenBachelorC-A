package com.zucker.openbachelorc

/**
 * Static app metadata shown on the "关于" card, plus the download endpoints
 * used by the one-tap install buttons.
 */
object AppInfo {

    /** Maintainer of this Android client and the compatibility layer. */
    const val AUTHOR = "Zucker-jex"

    /** Author of the upstream client/server this project builds on. */
    const val UPSTREAM_AUTHOR = "pfyy"

    const val COMPAT_REPO = "https://github.com/Zucker-jex/OpenBachelorS-Compat"

    const val COMPAT_RELEASES = "$COMPAT_REPO/releases"

    /** Upstream server repository - where the OpenBachelorS package comes from. */
    const val OBS_REPO = "https://github.com/pfyy/OpenBachelorS"

    /** Upstream client repository the patched game is built with. */
    const val OBC_REPO = "https://github.com/pfyy/OpenBachelorC"

    /**
     * Always the current master branch of the server, as a zip.
     * GitHub answers this with a 302 to codeload, which the downloader follows.
     */
    const val OBS_ARCHIVE_ZIP =
        "https://github.com/pfyy/OpenBachelorS/archive/refs/heads/master.zip"

    /**
     * The newest compatibility package.
     *
     * `releases/latest/download/` needs a path that never changes, which is why
     * the workflow also publishes the build under the fixed name
     * `obs-compat.zip` alongside the versioned one.
     */
    const val COMPAT_LATEST_ZIP =
        "$COMPAT_REPO/releases/latest/download/obs-compat.zip"
}
