package com.zucker.openbachelorc

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Kotlin replacement for the Python launcher (src/launcher/openbachelorc/).
 *
 * Original pieces and their fate:
 *   adb.py::get_running_emulators / connect_to_emulator   -> deleted (device is local)
 *   adb.py::start_reverse_proxy / *_forward_proxy         -> deleted (same-device loopback)
 *   adb.py::upload_frida_server_if_necessary              -> deleted (Gadget ships inside the game)
 *   adb.py::start_apk                                     -> launchGame()
 *   adb.py::kill_root_process / kill_frida_server         -> killStaleGadgetProcesses()
 *   inject.py::load_script / start_game                   -> start()
 *   inject.py::exec_trainer_command                       -> execTrainerCommand()
 *   inject.py::test_remote_port_loop                      -> waitForGadgetPort()
 */
class FridaClient(
    private val context: Context,
    private val config: ObcConfig,
    private val listener: Listener,
) {

    interface Listener {
        fun onLog(message: String)
        fun onNativeMessage(script: String, payload: String)
    }

    companion object {
        private const val TAG = "OpenBachelorC"

        /** Agents are named to match the Python client's load order. */
        const val SCRIPT_JAVA = "java.js"
        const val SCRIPT_NATIVE = "native.js"
        const val SCRIPT_EXTRA = "extra.js"
        const val SCRIPT_TRAINER = "trainer.js"

        /** Mirrors inject.py::test_remote_port_loop — 100 tries, 100 ms apart. */
        private const val PORT_PROBE_ATTEMPTS = 100
        private const val PORT_PROBE_INTERVAL_MS = 100L

        init {
            System.loadLibrary("obc")
        }
    }

    /** One-shot gate used to emulate the Python `test_remote_port_loop()` wait. */
    private class Signal {
        @Volatile private var fired = false

        fun fire() { fired = true }

        fun reset() { fired = false }

        fun isFired() = fired
    }

    private val scriptLoaded = Signal()
    private val nativeReady = Signal()

    private var active = false
    val isActive: Boolean get() = active

    // ------------------------------------------------------------------
    // native surface (implemented in jni/obc_jni.c)
    // ------------------------------------------------------------------

    private external fun nativeInit(listener: Listener)
    private external fun nativeDeinit()
    private external fun nativeVersion(): String
    private external fun nativeConnect(host: String, port: Int): String
    private external fun nativeAttach(processName: String): String
    private external fun nativeLoadScript(name: String, source: String): String
    private external fun nativePostConf(name: String, key: String, jsonValue: String): String
    private external fun nativeUnloadScript(name: String): String
    private external fun nativeResume(): String
    private external fun nativeDetach(): String

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun log(message: String) {
        Log.i(TAG, message)
        listener.onLog(message)
    }

    /** Called from the native message callback (Frida worker thread). */
    @Suppress("unused")
    private fun onNativeMessage(script: String, payload: String) {
        scriptLoaded.fire()
        listener.onNativeMessage(script, payload)
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun quote(value: String): String = JSONObject.quote(value)

    private fun proxyUrl(): String = "http://${config.host}:${config.port}"

    // ------------------------------------------------------------------
    // port probing / game launch
    // ------------------------------------------------------------------

    private fun probePort(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 300)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Equivalent of inject.py::test_remote_port_loop(). */
    suspend fun waitForGadgetPort(): Boolean = withContext(Dispatchers.IO) {
        repeat(PORT_PROBE_ATTEMPTS) {
            if (probePort("127.0.0.1", config.gadgetPort)) return@withContext true
            delay(PORT_PROBE_INTERVAL_MS)
        }
        false
    }

    fun isGameInstalled(): Boolean =
        context.packageManager.getLaunchIntentForPackage(config.gamePackage) != null

    /** Equivalent of adb.py::start_apk() (`monkey -p <pkg> -c LAUNCHER 1`). */
    fun launchGame(): Boolean {
        val intent: Intent = context.packageManager
            .getLaunchIntentForPackage(config.gamePackage)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: run {
                log("err: cannot resolve launch intent for ${config.gamePackage}")
                return false
            }
        context.startActivity(intent)
        log("info: launched ${config.gamePackage}")
        return true
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    suspend fun start(launchFirst: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (active) {
            log("warn: already running")
            return@withContext true
        }

        nativeInit(listener)
        log("info: frida-core ${nativeVersion()}")

        if (!isGameInstalled()) {
            log("err: ${config.gamePackage} is not installed (build it with OpenBachelorG first)")
            return@withContext false
        }

        if (launchFirst && !launchGame()) {
            return@withContext false
        }

        log("info: waiting for Gadget on 127.0.0.1:${config.gadgetPort}")
        if (!waitForGadgetPort()) {
            log("err: Gadget not reachable on port ${config.gadgetPort}")
            return@withContext false
        }
        log("info: Gadget is listening")

        val connectResult = nativeConnect("127.0.0.1", config.gadgetPort)
        log("info: $connectResult")
        if (connectResult.startsWith("err")) return@withContext false

        val attachResult = nativeAttach(config.processName)
        log("info: $attachResult")
        if (attachResult.startsWith("err")) return@withContext false

        // ---- java.js : proxy + Java layer hooks -------------------------
        if (!loadScript(SCRIPT_JAVA)) return@withContext false
        postConf(SCRIPT_JAVA, "proxy_url", quote(proxyUrl()))
        postConf(SCRIPT_JAVA, "no_proxy", config.noProxy.toString())

        // ---- native.js : needs the emulated realm when configured -------
        if (!loadScript(SCRIPT_NATIVE)) return@withContext false
        postConf(SCRIPT_NATIVE, "proxy_url", quote(proxyUrl()))
        postConf(SCRIPT_NATIVE, "no_proxy", config.noProxy.toString())

        // ---- extra.js : pause_deploy / 3x_speed / vision ----------------
        // extra.js waits for libil2cpp.so (100 x 100 ms) and only then applies
        // its patches, so a failure here shows up as "il2cpp not found" on the
        // console bridge rather than as an error return.
        if (config.enableExtra) {
            if (!loadScript(SCRIPT_EXTRA)) return@withContext false
            postConf(SCRIPT_EXTRA, "pause_deploy", config.pauseDeploy.toString())
            postConf(SCRIPT_EXTRA, "3x_speed", config.speed3x.toString())
            postConf(SCRIPT_EXTRA, "vision", config.vision.toString())
            postConf(SCRIPT_EXTRA, "vision_font_size", config.visionFontSize.toString())
        } else {
            log("info: extra.js disabled by config")
        }

        // ---- trainer.js : the 16 runtime commands ----------------------
        // config.json ships `"enable_trainer": false`, which is what the Python
        // launcher uses to decide whether to *load* the script at all. Honouring
        // that alone would make the UI toggles silently do nothing, because the
        // agent would never be present to receive `invoke` messages.
        //
        // Loading trainer.js is free: it only records the commands it can run
        // and activates nothing until an `invoke` arrives, so it is loaded
        // whenever the user actually wants trainers.
        if (config.enableTrainer || config.trainerWanted) {
            if (!loadScript(SCRIPT_TRAINER)) return@withContext false
            postConf(SCRIPT_TRAINER, "dump_json", config.dumpJson.toString())
            log("info: trainer.js loaded — toggles are live")
        } else {
            log("warn: trainer.js 未加载：config.enable_trainer=false 且尚未勾选任何功能")
            log("warn: 勾选任一功能开关后重新启动会话即可生效")
        }

        // The Gadget was built with "on_load": "wait", so the game stays
        // blocked until we attach. Attaching above already released it.
        active = true
        log("info: OpenBachelorC is running")
        true
    }

    private fun loadScript(name: String): Boolean {
        scriptLoaded.reset()

        val source = try {
            readAsset(name)
        } catch (e: Exception) {
            log("err: cannot read asset $name: ${e.message}")
            return false
        }

        val result = nativeLoadScript(name, source)
        log("info: $result")
        if (result.startsWith("err")) return false

        loadedScripts += name
        return true
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!active) return@withContext
        nativeDetach().also { log("info: $it") }
        nativeDeinit()
        active = false
        loadedScripts.clear()
        log("info: stopped")
    }

    suspend fun resume() = withContext(Dispatchers.IO) {
        nativeResume().also { log("info: $it") }
    }

    /**
     * Equivalent of inject.py::Game::exec_trainer_command().
     * `command` looks like "enable:zero_cost" / "disable:all".
     */
    fun execTrainerCommand(command: String) {
        if (!active) {
            log("err: not running")
            return
        }
        if (!loadedScripts.contains(SCRIPT_TRAINER)) {
            log("err: trainer.js is not loaded — $command dropped")
            return
        }
        postConf(SCRIPT_TRAINER, "invoke", quote(command))
    }

    /** Scripts successfully loaded into the current session. */
    private val loadedScripts = mutableSetOf<String>()

    /** True when the named agent is live and can receive `invoke` messages. */
    fun hasScript(name: String): Boolean = active && loadedScripts.contains(name)

    /**
     * Posts an arbitrary conf key/value pair to a loaded script.
     *
     * Each post is logged: the original Python launcher printed nothing here,
     * which made a wrong key name or a missed script impossible to spot.
     */
    fun postConf(script: String, key: String, jsonValue: String): String {
        val result = nativePostConf(script, key, jsonValue)
        log("conf -> [$script] $key = $jsonValue ($result)")
        return result
    }

    // ------------------------------------------------------------------
    // dump support (dump.py)
    // ------------------------------------------------------------------

    val dumpDir: File get() = File(context.getExternalFilesDir(null), "dump")
}
