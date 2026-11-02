package com.zucker.openbachelorc

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Mirrors the original `conf/config.json` handling (src/launcher/openbachelorc/config.py).
 *
 * The Python version mutated the JSON file with `sed` from start.sh; here the
 * file lives in the app's private storage and is rewritten properly.
 */
class ObcConfig(private val context: Context) {

    companion object {
        const val FILE_NAME = "config.json"

        const val DEFAULT_HOST = "10.0.0.1"
        const val DEFAULT_PORT = 8443
        const val DEFAULT_MULTIPLAYER_PORT = 8543
        const val DEFAULT_ICEBREAKER_PORT = 8544
        const val DEFAULT_FRIDA_PORT = 9443
        const val DEFAULT_GADGET_PORT = 10443
        const val DEFAULT_PROCESS_NAME = "Gadget"
        const val DEFAULT_GAME_PACKAGE = "com.zucker.arknights"
        const val TEST_SERVER_PORT = 8553

        const val PRESET_ONLINE = "online"
        const val PRESET_TEST = "test"
        const val PRESET_LOCAL = "local"
    }

    /** config.json lives beside the app's private files, like the Python CWD. */
    val file: File get() = File(context.filesDir, FILE_NAME)

    var host: String = DEFAULT_HOST
    var port: Int = DEFAULT_PORT

    /** A named server slot the user can edit and switch between. */
    data class Preset(var host: String, var port: Int)

    /**
     * The three quick-switch slots shown above the address fields.
     *
     * They are persisted so a custom address survives a restart; the built-in
     * defaults are only the initial values.
     */
    val presets: LinkedHashMap<String, Preset> = linkedMapOf(
        PRESET_ONLINE to Preset(DEFAULT_HOST, DEFAULT_PORT),
        PRESET_TEST to Preset(DEFAULT_HOST, TEST_SERVER_PORT),
        PRESET_LOCAL to Preset("127.0.0.1", DEFAULT_PORT),
    )

    /** Which slot the address fields currently belong to. */
    var activePreset: String = PRESET_ONLINE

    fun presetLabel(id: String): String = Lang.t(
        when (id) {
            PRESET_ONLINE -> "在线"
            PRESET_TEST -> "测试服"
            PRESET_LOCAL -> "本地"
            else -> id
        }
    )

    var multiplayerPort: Int = DEFAULT_MULTIPLAYER_PORT
    var icebreakerPort: Int = DEFAULT_ICEBREAKER_PORT
    var noProxy: Boolean = false
    var fridaPort: Int = DEFAULT_FRIDA_PORT
    var gadgetPort: Int = DEFAULT_GADGET_PORT
    var useSu: Boolean = false
    var useGadget: Boolean = true
    var useEmulatedRealm: Boolean = false
    var noSpawn: Boolean = true
    var enableExtra: Boolean = true
    var enableTrainer: Boolean = false

    /** Upstream added this to let native.js settle before its il2cpp work. */
    var nativeDelay: Int = 0

    /**
     * Not a config.json field: set by the UI when the user has switched any
     * trainer on, so trainer.js still gets loaded while `enable_trainer` stays
     * at its shipped default of false.
     */
    var trainerWanted: Boolean = false

    var pauseDeploy: Boolean = true
    var speed3x: Boolean = true
    var vision: Boolean = true
    var visionFontSize: Int = 22

    var dumpJson: Boolean = false

    var gamePackage: String = DEFAULT_GAME_PACKAGE
    var processName: String = DEFAULT_PROCESS_NAME

    /** Loads from the private file, seeding it from assets on first run. */
    fun load() {
        if (!file.isFile) {
            runCatching {
                context.assets.open(FILE_NAME).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }.onFailure { it.printStackTrace() }
        }

        if (!file.isFile) return

        runCatching {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            host = obj.optString("host", DEFAULT_HOST)
            port = obj.optInt("port", DEFAULT_PORT)
            multiplayerPort = obj.optInt("multiplayer_port", DEFAULT_MULTIPLAYER_PORT)
            icebreakerPort = obj.optInt("icebreaker_port", DEFAULT_ICEBREAKER_PORT)
            noProxy = obj.optBoolean("no_proxy", false)
            fridaPort = obj.optInt("frida_port", DEFAULT_FRIDA_PORT)
            gadgetPort = obj.optInt("gadget_port", DEFAULT_GADGET_PORT)
            useSu = obj.optBoolean("use_su", false)
            useGadget = obj.optBoolean("use_gadget", true)
            useEmulatedRealm = obj.optBoolean("use_emulated_realm", false)
            noSpawn = obj.optBoolean("no_spawn", true)
            nativeDelay = obj.optInt("native_delay", 0)
            enableExtra = obj.optBoolean("enable_extra", true)
            enableTrainer = obj.optBoolean("enable_trainer", false)

            val extra = obj.optJSONObject("extra_config")
            if (extra != null) {
                pauseDeploy = extra.optBoolean("pause_deploy", true)
                speed3x = extra.optBoolean("3x_speed", true)
                vision = extra.optBoolean("vision", true)
                visionFontSize = extra.optInt("vision_font_size", 22)
            }

            val trainer = obj.optJSONObject("trainer_config")
            if (trainer != null) {
                dumpJson = trainer.optBoolean("dump_json", false)
            }

            // Saved server slots. Missing keys simply keep their defaults, so a
            // config written by an older build still loads.
            val savedPresets = obj.optJSONObject("presets")
            if (savedPresets != null) {
                for (id in presets.keys.toList()) {
                    val saved = savedPresets.optJSONObject(id) ?: continue
                    val slot = presets.getValue(id)
                    slot.host = saved.optString("host", slot.host)
                    slot.port = saved.optInt("port", slot.port)
                }
            }

            activePreset = obj.optString("active_preset", PRESET_ONLINE)
                .takeIf { presets.containsKey(it) } ?: PRESET_ONLINE
        }.onFailure { it.printStackTrace() }
    }

    /** Serialises back out in the same shape as the original config.json. */
    fun save() {
        val obj = JSONObject().apply {
            put("host", host)
            put("port", port)
            put("multiplayer_port", multiplayerPort)
            put("icebreaker_port", icebreakerPort)
            put("no_proxy", noProxy)
            put("attach_pc", false)
            put("frida_port", fridaPort)
            put("gadget_port", gadgetPort)
            put("use_su", useSu)
            put("use_gadget", useGadget)
            put("use_emulated_realm", useEmulatedRealm)
            put("no_spawn", noSpawn)
            put("native_delay", nativeDelay)
            put("enable_extra", enableExtra)
            put("extra_config", JSONObject().apply {
                put("pause_deploy", pauseDeploy)
                put("3x_speed", speed3x)
                put("vision", vision)
                put("vision_font_size", visionFontSize)
            })
            put("enable_trainer", enableTrainer)
            put("trainer_config", JSONObject().apply {
                put("dump_json", dumpJson)
            })
            put("active_preset", activePreset)
            put("presets", JSONObject().apply {
                presets.forEach { (id, slot) ->
                    put(id, JSONObject().apply {
                        put("host", slot.host)
                        put("port", slot.port)
                    })
                }
            })
        }

        runCatching {
            file.writeText(obj.toString(4), Charsets.UTF_8)
        }.onFailure { it.printStackTrace() }
    }
}
