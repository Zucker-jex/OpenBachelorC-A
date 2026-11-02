package com.zucker.openbachelorc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), FridaClient.Listener {

    private lateinit var config: ObcConfig
    private lateinit var client: FridaClient
    private lateinit var dumpExporter: DumpExporter

    private val ui = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ---- views -------------------------------------------------------
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var resumeButton: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logPanel: LinearLayout
    private lateinit var logToggle: TextView

    /** Whether the log body is currently expanded. */
    private var logVisible = true

    /**
     * Backing store for the log panel.
     *
     * Kept as a field rather than read back from `logView.text`: a fresh
     * SpannableStringBuilder is created only once, so every line accumulates on
     * top of the previous ones instead of replacing them.
     */
    private val logBuilder = SpannableStringBuilder()
    private lateinit var trainerMaster: SwitchCompat
    private lateinit var trainerHint: TextView
    private lateinit var trainerSection: LinearLayout
    private lateinit var scriptRow: LinearLayout
    private lateinit var dumpAccessHint: TextView

    // ---- OBS compatibility layer -------------------------------------
    private lateinit var obsServerStatus: TextView
    private lateinit var obsServerButton: Button
    private lateinit var obsServerDownloadButton: Button
    private lateinit var obsStatus: TextView
    private lateinit var obsImportButton: Button
    private lateinit var obsCompatDownloadButton: Button

    // Shared progress row inside the OBS card: only one transfer runs at a time.
    private lateinit var obsProgressBox: LinearLayout
    private lateinit var obsProgress: android.widget.ProgressBar
    private lateinit var obsProgressText: TextView
    private lateinit var obsDeployButton: Button
    private lateinit var obsResetButton: Button
    private lateinit var obsPermissionButton: Button

    /** Picks an `OpenBachelorS-master.zip` to (re)install the server itself. */
    private val serverPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportServer(uri) }

    /** Picks an `obs-compat-*.zip` to replace the package stored in the app. */
    private val overlayPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportOverlay(uri) }

    /** Cached result of [DumpExporter.detectAccess]; null until probed. */
    private var dumpAccess: DumpExporter.Access? = null

    private lateinit var aboutCompatVersion: TextView

    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var gadgetField: EditText

    /** Server-slot buttons, keyed by [ObcConfig.PRESET_ONLINE] etc. */
    private val presetButtons = LinkedHashMap<String, Button>()
    private lateinit var savePresetButton: Button

    private val commandSwitches = LinkedHashMap<String, SwitchCompat>()

    /** Trainer commands the user has switched on. */
    private val enabledCommands = linkedSetOf<String>()

    /** Auto-scroll is dropped as soon as the user scrolls up to read history. */
    private var stickToBottom = true

    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) appendLog(t("warn: 未授予通知权限，前台服务通知可能不显示"))
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must happen before the UI is built: every label goes through Lang.t.
        Lang.load(this)

        config = ObcConfig(this).also { it.load() }
        client = FridaClient(this, config, this)
        dumpExporter = DumpExporter(this, config.gamePackage)

        setContentView(buildUi())
        restoreCommandState()

        // Remember whether the user collapsed the log last time.
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LOG_VISIBLE, true)) {
            setLogVisible(false)
        }

        runCatching { File(getExternalFilesDir(null), "session.log").delete() }
        logBuilder.clear()
        logBuilder.clearSpans()

        appendLog(t("info: OpenBachelorC 就绪 · 配置 %s").format(config.file.absolutePath))
        appendLog(t("info: 会话日志 → %s/session.log").format(getExternalFilesDir(null)))
        refreshStatus()
        requestNotificationPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        config.save()
    }

    override fun onResume() {
        super.onResume()
        // Root may have been granted/revoked while we were in the background.
        if (::dumpAccessHint.isInitialized) refreshDumpAccess()
        if (::obsStatus.isInitialized) refreshObsState()
    }

    // ------------------------------------------------------------------
    // FridaClient.Listener
    // ------------------------------------------------------------------

    override fun onLog(message: String) {
        appendLog(message)
    }

    /**
     * Anything a script sends with `send()` lands here. Our console shim turns
     * agent console.log/console.error and top-level exceptions into such
     * messages, so they are rendered instead of being dropped on the floor.
     */
    override fun onNativeMessage(script: String, payload: String) {
        appendLog("[$script] ${describeMessage(payload)}")
    }

    /**
     * Renders a Frida message envelope as one readable line.
     *
     * Frida nests what the agent passed to `send()` under a `payload` key, so a
     * shim message arrives as
     *   {"type":"send","payload":{"type":"console","level":"log","text":"..."}}
     * and the interesting fields are one level down.
     */
    private fun describeMessage(payload: String): String {
        val obj = runCatching { org.json.JSONObject(payload) }.getOrNull()
            ?: return payload

        val inner = obj.optJSONObject("payload") ?: obj

        return when (inner.optString("type")) {
            "console" -> {
                val level = inner.optString("level", "log")
                val marker = if (level == "error") "err" else level
                "$marker: ${inner.optString("text")}"
            }
            "exception" -> "EXCEPTION: ${inner.optString("text")}"
            else -> {
                val text = inner.optString("text")
                if (text.isNotEmpty()) text else inner.toString()
            }
        }
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
            fitsSystemWindows = true
        }

        root.addView(buildStatusBar())
        root.addView(buildContent(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(buildLogPanel())

        return root
    }

    /** Fixed header: identity on the left, live session state on the right. */
    private fun buildStatusBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }

        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "OpenBachelorC"
                setTextColor(color(R.color.text))
                textSize = 19f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@MainActivity).apply {
                text = t("明日方舟 · 本机注入客户端")
                setTextColor(color(R.color.text_faint))
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_status_pill)
            setPadding(dp(12), dp(7), dp(14), dp(7))
        }

        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.text_faint))
            }
        }
        pill.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginEnd = dp(8)
        })

        statusText = TextView(this).apply {
            text = t("已停止")
            setTextColor(color(R.color.text_dim))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        pill.addView(statusText)

        // Language switcher lives in the header so a first-time user who
        // cannot read the current language still finds it. The icon is drawn
        // as text ("文/A") to avoid shipping an icon font.
        bar.addView(TextView(this).apply {
            text = "文/A"
            setTextColor(color(R.color.text_dim))
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_status_pill)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = "Language"
            setOnClickListener { showLanguageDialog() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })

        bar.addView(pill)
        return bar
    }

    private fun buildContent(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }

        column.addView(buildSessionCard())
        column.addView(buildEndpointCard())
        column.addView(buildModulesCard())
        column.addView(buildTrainerCard())
        column.addView(buildObsCard())
        column.addView(buildDataCard())
        column.addView(buildAboutCard())

        return NestedScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
    }

    // ---- session -----------------------------------------------------

    private fun buildSessionCard(): View {
        val card = card()

        card.addView(label(t("会话")))
        card.addView(TextView(this).apply {
            text = t("启动游戏并在同一进程内建立 Frida 会话")
            setTextColor(color(R.color.text_dim))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(14))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = primaryButton(t("启动并注入")) { onStartClicked() }
        actions.addView(startButton, weight(1f))
        stopButton = ghostButton(t("停止")) { onStopClicked() }
        actions.addView(stopButton, weight(1f).apply { marginStart = dp(8) })
        resumeButton = ghostButton(t("恢复游戏")) {
            lifecycleScope.launch { client.resume() }
        }
        actions.addView(resumeButton, weight(1f).apply { marginStart = dp(8) })
        card.addView(actions)

        statusDetail = TextView(this).apply {
            setTextColor(color(R.color.text_faint))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(14), 0, 0)
        }
        card.addView(statusDetail)

        return card
    }

    // ---- endpoint ----------------------------------------------------

    private fun buildEndpointCard(): View {
        val card = card()

        card.addView(label(t("服务器与端口")))
        card.addView(TextView(this).apply {
            text = t("客户端与游戏同机运行，Gadget 走本机回环，无需 adb 转发")
            setTextColor(color(R.color.text_dim))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(14))
        })

        val fields = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hostField = field(t("服务器地址"), config.host, numeric = false)
        fields.addView(hostField, weight(1.4f))
        portField = field(t("端口"), config.port.toString(), numeric = true)
        fields.addView(portField, weight(1f).apply { marginStart = dp(8) })
        gadgetField = field("Gadget", config.gadgetPort.toString(), numeric = true)
        fields.addView(gadgetField, weight(1f).apply { marginStart = dp(8) })
        card.addView(fields)

        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        config.presets.keys.forEachIndexed { index, id ->
            val button = ghostButton(config.presetLabel(id)) { selectPreset(id) }
            presetButtons[id] = button
            presets.addView(button, weight(1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        card.addView(presets)

        // Saves whatever is in the fields into the slot that is currently
        // selected, so a custom address survives a restart.
        savePresetButton = ghostButton(t("保存")) { saveCurrentPreset() }
        card.addView(savePresetButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        updatePresetHighlight()

        return card
    }

    /** Loads a saved slot into the address fields and makes it the active one. */
    private fun selectPreset(id: String) {
        val slot = config.presets[id] ?: return

        hostField.setText(slot.host)
        portField.setText(slot.port.toString())

        config.activePreset = id
        config.host = slot.host
        config.port = slot.port
        config.save()

        updatePresetHighlight()
        refreshStatus()
        appendLog(t("info: 切换预设「%s」→ %s:%d").format(config.presetLabel(id), slot.host, slot.port))
    }

    /** Writes the current field values into the selected slot. */
    private fun saveCurrentPreset() {
        val host = hostField.text.toString().trim().ifEmpty { ObcConfig.DEFAULT_HOST }
        val port = portField.text.toString().trim().toIntOrNull() ?: ObcConfig.DEFAULT_PORT
        val gadget = gadgetField.text.toString().trim().toIntOrNull() ?: ObcConfig.DEFAULT_GADGET_PORT

        val id = config.activePreset
        config.presets[id]?.let {
            it.host = host
            it.port = port
        }

        config.host = host
        config.port = port
        config.gadgetPort = gadget
        config.save()

        updatePresetHighlight()
        refreshStatus()
        appendLog(t("info: 已保存预设「%s」→ %s:%d (Gadget %d)").format(config.presetLabel(id), host, port, gadget))
        toast(t("已保存到「%s」").format(config.presetLabel(id)))
    }

    private fun updatePresetHighlight() {
        for ((id, button) in presetButtons) {
            val active = id == config.activePreset
            button.setTextColor(color(if (active) R.color.accent else R.color.text))
            button.alpha = if (active) 1f else 0.7f
        }
        if (::savePresetButton.isInitialized) {
            savePresetButton.text = t("保存到「%s」").format(config.presetLabel(config.activePreset))
        }
    }

    // ---- modules -----------------------------------------------------

    /** Live per-agent status, so a missing script is obvious instead of silent. */
    private fun buildModulesCard(): View {
        val card = card()

        card.addView(label(t("注入模块")))
        card.addView(TextView(this).apply {
            text = t("会话建立后自动加载；控制台与异常会回传到日志")
            setTextColor(color(R.color.text_dim))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(14))
        })

        scriptRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(scriptRow)
        renderScripts()

        return card
    }

    private fun renderScripts() {
        scriptRow.removeAllViews()
        for ((script, purpose) in SCRIPTS) {
            val loaded = client.hasScript(script)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }

            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(if (loaded) R.color.ok else R.color.text_faint))
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(10)
            })

            row.addView(TextView(this).apply {
                text = script
                setTextColor(color(if (loaded) R.color.text else R.color.text_faint))
                textSize = 13f
                typeface = Typeface.MONOSPACE
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(TextView(this).apply {
                text = t(purpose)
                setTextColor(color(R.color.text_faint))
                textSize = 11f
            })

            scriptRow.addView(row)
        }
    }

    // ---- trainer -----------------------------------------------------

    private fun buildTrainerCard(): View {
        val card = card()

        card.addView(label(t("功能模块（trainer）")))

        trainerMaster = SwitchCompat(this).apply {
            text = t("启用 trainer.js")
            setTextColor(color(R.color.text))
            textSize = 14f
            isChecked = config.enableTrainer
            setPadding(0, dp(10), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                config.enableTrainer = checked
                config.save()
                updateTrainerEnabledState()
                appendLog(
                    if (checked) t("info: trainer 已启用，将在下次启动会话时加载")
                    else t("info: trainer 已停用")
                )
            }
        }
        card.addView(trainerMaster)

        trainerHint = TextView(this).apply {
            setTextColor(color(R.color.text_faint))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(6))
        }
        card.addView(trainerHint)

        trainerSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        for ((command, label) in TRAINER_COMMANDS) {
            val sw = SwitchCompat(this).apply {
                text = t(label)
                setTextColor(color(R.color.text))
                textSize = 14f
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, checked -> onCommandToggled(command, checked) }
            }
            commandSwitches[command] = sw
            trainerSection.addView(sw)
        }

        card.addView(trainerSection)
        updateTrainerEnabledState()

        return card
    }

    /**
     * The 16 switches are meaningless without trainer.js, so they are disabled
     * (and the hint says why) until the module is switched on.
     */
    private fun updateTrainerEnabledState() {
        val on = config.enableTrainer
        trainerSection.alpha = if (on) 1f else 0.4f
        commandSwitches.values.forEach { it.isEnabled = on }
        trainerHint.text = if (on) {
            t("勾选的功能会在会话建立后自动应用")
        } else {
            t("未启用：功能开关不会生效（config.enable_trainer = false）")
        }
    }

    // ---- data --------------------------------------------------------

    // ---- OBS compatibility layer -------------------------------------

    private fun buildObsCard(): View {
        val card = card()

        card.addView(label(t("OBS 服务端（Termux）")))
        card.addView(TextView(this).apply {
            text = t("在手机上跑本地服务端：先导入 OpenBachelorS 本体，再部署兼容层")
            setTextColor(color(R.color.text_dim))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(12))
        })

        // ---- 进度 ----
        obsProgressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(12))
        }
        obsProgress = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        obsProgressBox.addView(obsProgress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
        ))
        obsProgressText = TextView(this).apply {
            setTextColor(color(R.color.text_dim))
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        }
        obsProgressBox.addView(obsProgressText)
        card.addView(obsProgressBox)

        // ---- 本体 -----------------------------------------------------
        card.addView(TextView(this).apply {
            text = t("① 服务端本体")
            setTextColor(color(R.color.text))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(6))
        })

        obsServerStatus = TextView(this).apply {
            textSize = 11f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(obsServerStatus)

        obsServerButton = primaryButton(t("导入 OpenBachelorS 包")) { pickServerZip() }
        card.addView(obsServerButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        obsServerDownloadButton = ghostButton(t("下载最新 OpenBachelorS")) { onDownloadServer() }
        card.addView(obsServerDownloadButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        card.addView(linkText("没有本体包？从 ${AppInfo.UPSTREAM_AUTHOR}/OpenBachelorS 下载") {
            openUrl(AppInfo.OBS_REPO)
        })

        // ---- 兼容层 ---------------------------------------------------
        card.addView(TextView(this).apply {
            text = t("② 兼容层")
            setTextColor(color(R.color.text))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(16), 0, dp(6))
        })

        obsStatus = TextView(this).apply {
            textSize = 11f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(obsStatus)

        obsImportButton = ghostButton(t("导入兼容层 ZIP")) { pickOverlayZip() }
        card.addView(obsImportButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        obsCompatDownloadButton = ghostButton(t("下载最新兼容层")) { onDownloadCompat() }
        card.addView(obsCompatDownloadButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        obsDeployButton = ghostButton(t("部署兼容层")) { onDeployOverlay() }
        actions.addView(obsDeployButton, weight(1f))
        actions.addView(ghostButton(t("导出当前包")) { onExportActive() },
            weight(1f).apply { marginStart = dp(8) })
        card.addView(actions)

        val links = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        links.addView(ghostButton(t("安装教程")) { showObsGuide() }, weight(1f))
        links.addView(ghostButton(t("兼容层仓库")) { openCompatRepo() },
            weight(1f).apply { marginStart = dp(8) })
        card.addView(links)

        obsResetButton = ghostButton(t("恢复内置版本")) { onResetBundled() }
        card.addView(obsResetButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        card.addView(TextView(this).apply {
            text = t("兼容层会随应用一起发布；也可以在兼容层仓库的 Releases 里下载新版，") +
                t("再回到上面点「导入兼容层 ZIP」更新，无需重装应用。")
            setTextColor(color(R.color.text_faint))
            textSize = 10.5f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(10), 0, 0)
        })

        obsPermissionButton = ghostButton(t("授予所有文件访问权限")) {
            if (!StoragePermission.openSettings(this)) {
                toast("请手动前往：设置 → 特殊权限 → 所有文件访问")
            }
        }
        card.addView(obsPermissionButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        refreshObsState()
        return card
    }

    private fun pickServerZip() {
        runCatching { serverPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            .onFailure { appendLog(t("err: 无法打开文件选择器：%s").format(it.message)) }
    }

    private fun pickOverlayZip() {
        runCatching {
            overlayPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }.onFailure { appendLog(t("err: 无法打开文件选择器：%s").format(it.message)) }
    }

    private fun refreshObsState() {
        if (!::obsStatus.isInitialized) return

        obsStatus.text = t("正在检查…")
        obsStatus.setTextColor(color(R.color.text_faint))

        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { ObsOverlay.status(this@MainActivity) }
            val serverInstalled = withContext(Dispatchers.IO) { ObsServerImporter.isInstalled() }
            val serverFiles = withContext(Dispatchers.IO) {
                if (serverInstalled) ObsServerImporter.fileCount() else 0
            }

            obsServerStatus.text = if (serverInstalled) {
                t("已安装 · Download/OpenBachelorS（%d 个文件）").format(serverFiles)
            } else {
                t("未安装 —— 首次使用请选择一个 OpenBachelorS 包")
            }
            obsServerStatus.setTextColor(color(if (serverInstalled) R.color.ok else R.color.warn))
            obsServerButton.text =
                if (serverInstalled) t("更新 OpenBachelorS 包") else t("导入 OpenBachelorS 包")

            val active = status.activeVersion ?: t("未知")
            val source = if (status.usingBundled) t("内置") else t("已导入")

            obsStatus.text = buildList {
                add(
                    if (status.usingBundled) t("版本 %s（%s）").format(active, source)
                    else t("版本 %s（%s，内置 %s）").format(active, source, status.bundledVersion ?: "?")
                )
                add(
                    t("已部署：") + (status.deployedVersion ?: t("未部署")) +
                        if (status.deployedCurrent) " ✓" else ""
                )
                if (!serverInstalled) add(t("部署前需要先导入本体"))
                if (!status.canWrite) add(t("需要「所有文件访问权限」才能写入 Download"))
            }.joinToString("\n")

            obsStatus.setTextColor(color(if (status.canWrite) R.color.text_dim else R.color.warn))
            obsDeployButton.text = if (status.deployedCurrent) t("重新部署") else t("部署兼容层")
            obsResetButton.visibility = if (status.usingBundled) View.GONE else View.VISIBLE
            obsPermissionButton.visibility = if (status.canWrite) View.GONE else View.VISIBLE

            if (::aboutCompatVersion.isInitialized) {
                val source = if (status.usingBundled) t("内置") else t("已导入")
                aboutCompatVersion.text = t("兼容层 %s（%s）")
                    .format(status.activeVersion ?: t("未知"), source)
            }
        }
    }

    /** Downloads the current OpenBachelorS master archive and imports it. */
    private fun onDownloadServer() {
        lifecycleScope.launch {
            if (!StoragePermission.isGranted()) {
                appendLog(t("warn: 下载本体需要「所有文件访问权限」才能写入 Download"))
                toast("请先授予所有文件访问权限")
                StoragePermission.openSettings(this@MainActivity)
                return@launch
            }

            obsServerDownloadButton.isEnabled = false
            appendLog(t("info: 正在下载 OpenBachelorS（master.zip）…"))
            progressDeterminate(t("准备下载…"), 0)

            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    var lastPercent = -1

                    val file = Downloader.download(
                        AppInfo.OBS_ARCHIVE_ZIP,
                        File(cacheDir, "OpenBachelorS-master.zip")
                    ) { progress ->
                        val percent = progress.percent
                        val mb = progress.bytes / 1024 / 1024
                        val totalMb = if (progress.total > 0) progress.total / 1024 / 1024 else 0

                        progressDeterminate(
                            if (totalMb > 0) t("下载 OpenBachelorS · %d%% · %d / %d MB").format(percent, mb, totalMb)
                            else t("下载 OpenBachelorS · %d MB").format(mb),
                            percent
                        )

                        if (percent >= lastPercent + 25) {
                            lastPercent = percent
                            appendLog(t("  下载 %d%% · %d MB").format(percent, mb))
                        }
                    }

                    appendLog(t("info: 下载完成，开始解包…"))
                    progressIndeterminate(t("解包中…"))

                    ObsServerImporter.importFrom(
                        this@MainActivity,
                        open = { file.inputStream() }
                    ) { files, bytes, _ ->
                        progressIndeterminate(t("解包中 · %d 个文件 · %d MB").format(files, bytes / 1024 / 1024))
                        if (files % 200 == 0) {
                            appendLog(t("  已解包 %d 个文件 · %d MB").format(files, bytes / 1024 / 1024))
                        }
                    }
                }
            }

            outcome.onSuccess { result ->
                appendLog(t("info: 本体已就绪：%d 个文件，%d MB → %s").format(result.files, result.bytes / 1024 / 1024, result.target))
                if (!result.looksValid) {
                    appendLog(t("warn: 没找到 src/openbachelors，下载内容可能不是 OpenBachelorS"))
                } else {
                    appendLog(t("info: 下一步 → 点「部署兼容层」"))
                }
                toast(t("已导入 %d 个文件").format(result.files))
            }.onFailure { error ->
                appendLog(t("err: 下载/导入失败：%s").format(error.message))
                toast("下载失败")
            }

            obsServerDownloadButton.isEnabled = true
            progressHide()
            refreshObsState()
        }
    }

    /** Downloads the newest compatibility package and stores it in the app. */
    private fun onDownloadCompat() {
        lifecycleScope.launch {
            obsCompatDownloadButton.isEnabled = false
            appendLog(t("info: 正在下载最新兼容层…"))
            progressIndeterminate(t("查找最新版本…"))

            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val url = CompatRelease.latestZipUrl()
                    appendLog(t("info: 兼容层地址 %s").format(url))

                    val file = Downloader.download(url, File(cacheDir, "obs-compat.zip")) { progress ->
                        progressDeterminate(
                            t("下载兼容层 · %d%% · %d KB").format(progress.percent, progress.bytes / 1024),
                            progress.percent
                        )
                    }

                    progressIndeterminate(t("导入中…"))
                    ObsOverlay.importPackage(this@MainActivity, file).getOrThrow()
                }
            }

            outcome.onSuccess { result ->
                appendLog(t("info: 兼容层已更新：%s → %s").format(result.replacedVersion ?: t("无"), result.version))
                appendLog(t("info: 旧版本保留为 obs-compat.previous.zip"))
                toast(t("已导入 %s").format(result.version))
            }.onFailure { error ->
                appendLog(t("err: 下载/导入失败：%s").format(error.message))
                appendLog(t("warn: 若提示 HTTP 404，说明兼容层仓库还没有发布 Release"))
                toast("下载失败")
            }

            obsCompatDownloadButton.isEnabled = true
            progressHide()
            refreshObsState()
        }
    }

    private fun onImportServer(uri: android.net.Uri) {
        lifecycleScope.launch {
            if (!StoragePermission.isGranted()) {
                appendLog(t("warn: 导入本体需要「所有文件访问权限」"))
                toast("请先授予所有文件访问权限")
                StoragePermission.openSettings(this@MainActivity)
                return@launch
            }

            obsServerButton.isEnabled = false
            appendLog(t("info: 开始导入 OpenBachelorS 本体（覆盖式更新，保留 sav/ 等运行数据）…"))

            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    ObsServerImporter.import(this@MainActivity, uri) { files, bytes, name ->
                        if (files % 200 == 0) {
                            appendLog(t("  已解包 %d 个文件 · %d MB").format(files, bytes / 1024 / 1024))
                        }
                    }
                }
            }

            outcome.onSuccess { result ->
                val mb = result.bytes / 1024 / 1024
                appendLog(t("info: 本体导入完成：%d 个文件，%d MB → %s").format(result.files, mb, result.target))
                if (result.backedUp.isNotEmpty()) {
                    appendLog(t("info: 已备份 %s（*.obc-bak）").format(result.backedUp.joinToString(", ")))
                }
                if (!result.looksValid) {
                    appendLog(t("warn: 没有找到 src/openbachelors，这个 ZIP 可能不是 OpenBachelorS 包"))
                } else {
                    appendLog(t("info: 下一步 → 点「部署兼容层」，然后在 Termux 里运行 setup.sh"))
                }
                toast(t("已导入 %d 个文件").format(result.files))
            }.onFailure { error ->
                appendLog(t("err: 导入本体失败：%s").format(error.message))
                toast("导入失败")
            }

            obsServerButton.isEnabled = true
            refreshObsState()
        }
    }

    private fun onImportOverlay(uri: android.net.Uri) {
        lifecycleScope.launch {
            appendLog(t("info: 正在导入兼容层 ZIP…"))

            val outcome = withContext(Dispatchers.IO) {
                ObsOverlay.importPackage(this@MainActivity, uri)
            }

            outcome.onSuccess { result ->
                appendLog(t("info: 兼容层已更新：%s → %s").format(result.replacedVersion ?: t("无"), result.version))
                appendLog(t("info: 旧版本保留为 obs-compat.previous.zip"))
                toast(t("已导入 %s").format(result.version))
            }.onFailure { error ->
                appendLog(t("err: 导入失败：%s").format(error.message))
                toast("导入失败")
            }

            refreshObsState()
        }
    }

    private fun onDeployOverlay() {
        lifecycleScope.launch {
            if (!StoragePermission.isGranted()) {
                appendLog(t("warn: 部署兼容层需要「所有文件访问权限」"))
                toast("请先授予所有文件访问权限")
                StoragePermission.openSettings(this@MainActivity)
                return@launch
            }

            val serverInstalled = withContext(Dispatchers.IO) { ObsServerImporter.isInstalled() }
            if (!serverInstalled) {
                appendLog(t("err: 还没有导入 OpenBachelorS 本体，请先点「导入 OpenBachelorS 包」"))
                toast("请先导入本体")
                return@launch
            }

            appendLog(t("info: 正在部署兼容层…"))
            val outcome = withContext(Dispatchers.IO) { ObsOverlay.deploy(this@MainActivity) }

            outcome.onSuccess { result ->
                appendLog(t("info: 已部署 %s：写入 %d，跳过 %d").format(result.version, result.written, result.skipped))
                appendLog(t("info: 目标 %s").format(result.target.dir))
                if (result.backedUp.isNotEmpty()) {
                    appendLog(t("info: 覆盖前已备份 %d 个文件（*.obc-bak）").format(result.backedUp.size))
                }
                appendLog(t("info: 现在打开 Termux：cd ~/storage/downloads/OpenBachelorS && bash setup.sh"))
                toast(t("已部署 %d 个文件").format(result.written))
            }.onFailure { error ->
                appendLog(t("err: 部署失败：%s").format(error.message))
                toast("部署失败")
            }

            refreshObsState()
        }
    }

    private fun onExportActive() {
        lifecycleScope.launch {
            if (!StoragePermission.isGranted()) {
                appendLog(t("warn: 导出需要「所有文件访问权限」"))
                StoragePermission.openSettings(this@MainActivity)
                return@launch
            }

            val file = withContext(Dispatchers.IO) { ObsOverlay.exportActive(this@MainActivity) }
            if (file != null) {
                appendLog(t("info: 当前兼容层已导出 → %s").format(file))
                toast("已导出到 Download/OpenBachelorC")
            } else {
                appendLog(t("err: 导出失败"))
            }
        }
    }

    private fun onResetBundled() {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { ObsOverlay.resetToBundled(this@MainActivity) }

            outcome.onSuccess { version ->
                appendLog(t("info: 已恢复内置兼容层 %s").format(version))
                toast("已恢复内置版本")
            }.onFailure { error ->
                appendLog(t("err: 恢复失败：%s").format(error.message))
            }

            refreshObsState()
        }
    }

    /**
     * Shows the Termux guide in a styled dialog.
     *
     * The guide is structured text (title / section heading / prose / command),
     * so it is rendered as such instead of dumping one monospace blob: shell
     * commands get a terminal-like panel, prose stays readable.
     */
    private fun showObsGuide() {
        val text = ObsGuide.build()

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(22), dp(20), dp(22), dp(14))
        }

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        renderGuide(text, body)

        root.addView(
            ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        footer.addView(ghostButton(t("复制全文")) {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText(t("Termux 安装教程"), text)
            )
            toast(t("已复制到剪贴板"))
        }, weight(1f))
        footer.addView(ghostButton(t("关闭")) { dialog.dismiss() },
            weight(1f).apply { marginStart = dp(8) })
        root.addView(footer)

        dialog.setContentView(root)
        dialog.show()

        val metrics = resources.displayMetrics
        dialog.window?.setLayout(
            metrics.widthPixels - dp(28),
            (metrics.heightPixels * 0.86f).toInt()
        )
    }

    /** Turns the plain-text guide into headings, notes and command panels. */
    private fun renderGuide(guide: String, parent: LinearLayout) {
        val lines = guide.lines()
        val looksLikeShell = Regex(
            """^\s*(bash|cd|rm|pkg|pip|python|export|termux-|OBC_|adb|pkill)\b|&&|${'$'}PREFIX"""
        )
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)

            when {
                line.isBlank() -> i++

                // Title: underlined with ═
                next != null && next.trimStart().startsWith("═") -> {
                    parent.addView(TextView(this).apply {
                        this.text = line.trim()
                        setTextColor(color(R.color.text))
                        textSize = 18f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setPadding(0, 0, 0, dp(2))
                    })
                    i += 2
                }

                // Section heading: underlined with ─
                next != null && next.trimStart().startsWith("─") -> {
                    parent.addView(TextView(this).apply {
                        this.text = line.trim()
                        setTextColor(color(R.color.accent))
                        textSize = 13f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setPadding(0, dp(20), 0, dp(6))
                    })
                    i += 2
                }

                // Indented block: split it so only actual commands get a
                // terminal panel; explanatory lines stay as notes.
                line.startsWith("   ") || line.startsWith("\t") -> {
                    val block = mutableListOf<String>()
                    while (i < lines.size &&
                        (lines[i].startsWith("   ") || lines[i].startsWith("\t")) &&
                        lines[i].isNotBlank()
                    ) {
                        block += lines[i].trimEnd()
                        i++
                    }

                    var k = 0
                    while (k < block.size) {
                        if (looksLikeShell.containsMatchIn(block[k])) {
                            val commands = mutableListOf<String>()
                            while (k < block.size && looksLikeShell.containsMatchIn(block[k])) {
                                commands += block[k]
                                k++
                            }
                            addCommandPanel(parent, commands.joinToString("\n").trim())
                        } else {
                            val notes = mutableListOf<String>()
                            while (k < block.size && !looksLikeShell.containsMatchIn(block[k])) {
                                notes += block[k]
                                k++
                            }
                            addGuideNote(parent, notes.joinToString("\n").trimStart())
                        }
                    }
                }

                line.trimStart().startsWith("·") -> {
                    parent.addView(TextView(this).apply {
                        this.text = line.trim()
                        setTextColor(color(R.color.text))
                        textSize = 12.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setPadding(0, dp(14), 0, dp(2))
                    })
                    i++
                }

                else -> {
                    parent.addView(TextView(this).apply {
                        this.text = line.trim()
                        setTextColor(color(R.color.text_dim))
                        textSize = 12.5f
                        setLineSpacing(0f, 1.3f)
                        setPadding(0, dp(2), 0, dp(2))
                    })
                    i++
                }
            }
        }
    }

    private fun addCommandPanel(parent: LinearLayout, command: String) {
        parent.addView(TextView(this).apply {
            text = command
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(color(R.color.log_ok))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_log)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(4) })
    }

    private fun addGuideNote(parent: LinearLayout, note: String) {
        parent.addView(TextView(this).apply {
            text = note
            setTextColor(color(R.color.text_faint))
            textSize = 11.5f
            setLineSpacing(0f, 1.3f)
            setPadding(dp(6), dp(4), 0, dp(2))
        })
    }

    private fun buildDataCard(): View {
        val card = card()

        card.addView(label(t("数据导出")))
        card.addView(TextView(this).apply {
            text = t("从游戏外部数据目录导出 %d 个数据库文件").format(DumpExporter.FILE_MAPPING.size)
            setTextColor(color(R.color.text_dim))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        })

        dumpAccessHint = TextView(this).apply {
            textSize = 11f
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(dumpAccessHint)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ghostButton(t("拉取 DUMP")) { onPullDump() }, weight(1f))
        actions.addView(ghostButton(t("清空游戏端")) { onClearDump() },
            weight(1f).apply { marginStart = dp(8) })
        card.addView(actions)

        card.addView(TextView(this).apply {
            text = t("Android 11+ 不允许任何应用（即使拥有「所有文件访问权限」）读取其他应用的 ") +
                t("Android/data，所以这里改用 Root 读取。")
            setTextColor(color(R.color.text_faint))
            textSize = 10.5f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(10), 0, 0)
        })

        refreshDumpAccess()

        return card
    }

    /** Probes how the game's directory can be reached; runs off the UI thread. */
    private fun refreshDumpAccess() {
        if (!::dumpAccessHint.isInitialized) return

        dumpAccessHint.text = t("正在检测访问方式…")
        dumpAccessHint.setTextColor(color(R.color.text_faint))

        lifecycleScope.launch {
            val access = withContext(Dispatchers.IO) { dumpExporter.detectAccess() }
            dumpAccess = access

            val (text, colorRes) = when (access) {
                DumpExporter.Access.ROOT ->
                    t("Root 已授权 · 可直接读取游戏数据目录") to R.color.ok
                DumpExporter.Access.DIRECT ->
                    t("可直接读取游戏数据目录（无需 Root）") to R.color.ok
                DumpExporter.Access.NONE ->
                    t("未检测到 Root · 无法读取其他应用的 Android/data") to R.color.err
            }
            dumpAccessHint.text = text
            dumpAccessHint.setTextColor(color(colorRes))

            if (access == DumpExporter.Access.NONE) {
                // Surfaced so "not granted" can be told apart from "no su binary".
                val probe = withContext(Dispatchers.IO) { RootShell.probe() }
                appendLog(t("warn: su 探测结果: %s").format(probe.output.ifBlank { t("（无输出）") }))
            }
        }
    }

    // ---- OBS progress ------------------------------------------------

    /** Determinate bar: used while bytes are flowing and the total is known. */
    private fun progressDeterminate(text: String, percent: Int) {
        ui.post {
            if (!::obsProgressBox.isInitialized) return@post
            obsProgressBox.visibility = View.VISIBLE
            obsProgress.isIndeterminate = false
            obsProgress.progress = percent.coerceIn(0, 100)
            obsProgressText.text = text
        }
    }

    /** Indeterminate bar: used while unzipping, where there is no byte total. */
    private fun progressIndeterminate(text: String) {
        ui.post {
            if (!::obsProgressBox.isInitialized) return@post
            obsProgressBox.visibility = View.VISIBLE
            obsProgress.isIndeterminate = true
            obsProgressText.text = text
        }
    }

    private fun progressHide() {
        ui.post {
            if (!::obsProgressBox.isInitialized) return@post
            obsProgressBox.visibility = View.GONE
            obsProgress.isIndeterminate = false
            obsProgress.progress = 0
        }
    }

    // ---- about -------------------------------------------------------

    private fun buildAboutCard(): View {
        val card = card()

        card.addView(label(t("关于")))

        card.addView(TextView(this).apply {
            text = t("OpenBachelorC %s\n本应用作者 %s").format(appVersionText(), AppInfo.AUTHOR)
            setTextColor(color(R.color.text_dim))
            textSize = 12f
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(8), 0, dp(10))
        })

        aboutCompatVersion = TextView(this).apply {
            setTextColor(color(R.color.text_faint))
            textSize = 11f
            setPadding(0, 0, 0, dp(14))
        }
        card.addView(aboutCompatVersion)

        // ---- 上游 ----
        card.addView(TextView(this).apply {
            text = t("上游项目")
            setTextColor(color(R.color.text))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(6))
        })

        card.addView(TextView(this).apply {
            text = t("OpenBachelorC 客户端与 OpenBachelorS 服务端由 %s 开发，本应用是在其基础上的安卓封装。")
                    .format(AppInfo.UPSTREAM_AUTHOR)
            setTextColor(color(R.color.text_faint))
            textSize = 10.5f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(10))
        })

        card.addView(ghostButton(t("获取 OBS 服务端包")) { openUrl(AppInfo.OBS_REPO) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

        card.addView(TextView(this).apply {
            text = "${AppInfo.OBS_REPO}\n" +
                t("OBS 本体包（OpenBachelorS-master.zip）就在这个仓库，下载后回到") +
                t("「OBS 服务端」卡片点「导入 OpenBachelorS 包」即可。")
            setTextColor(color(R.color.text_faint))
            textSize = 10.5f
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(8), 0, dp(14))
        })

        // ---- 兼容层 ----
        card.addView(TextView(this).apply {
            text = t("兼容层")
            setTextColor(color(R.color.text))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(6))
        })

        card.addView(ghostButton(t("打开兼容层仓库")) { openUrl(AppInfo.COMPAT_REPO) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

        card.addView(TextView(this).apply {
            text = t("兼容层仓库的 Releases 页面提供独立兼容层包（obs-compat-*.zip），") +
                t("下载后在「OBS 服务端」卡片里点「导入兼容层 ZIP」即可更新。")
            setTextColor(color(R.color.text_faint))
            textSize = 10.5f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(10), 0, 0)
        })

        return card
    }

    /**
     * Language picker.
     *
     * A custom card rather than a stock list dialog: each row shows the language
     * in its own script, the active one is highlighted, and the whole thing uses
     * the same surface as the rest of the app.
     */
    private fun showLanguageDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Language"
            setTextColor(color(R.color.text))
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = t("选择界面语言")
            setTextColor(color(R.color.text_faint))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(14))
        })

        val current = Lang.mode(this)
        val options = listOf(
            LangMode.SYSTEM to t("跟随系统"),
            LangMode.ZH to "中文",
            LangMode.EN to "English",
        )

        options.forEach { (mode, label) ->
            val selected = mode == current

            root.addView(TextView(this).apply {
                text = if (selected) "✓   $label" else "      $label"
                setTextColor(color(if (selected) R.color.accent else R.color.text))
                textSize = 15f
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (selected) R.drawable.bg_field else R.drawable.bg_status_pill
                )
                setOnClickListener {
                    if (mode == current) {
                        dialog.dismiss()
                    } else {
                        Lang.setMode(this@MainActivity, mode)
                        dialog.dismiss()
                        recreate()
                    }
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }

        root.addView(ghostButton(t("取消")) { dialog.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })

        dialog.setContentView(root)
        dialog.show()

        val metrics = resources.displayMetrics
        dialog.window?.setLayout((metrics.widthPixels * 0.82f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun appVersionText(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.versionCode})"
    }.getOrDefault("未知")

    private fun openCompatRepo() = openUrl(AppInfo.COMPAT_REPO)

    private fun openUrl(url: String) {
        val opened = runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            )
            true
        }.getOrDefault(false)

        if (!opened) {
            toast(t("没有可用的浏览器：%s").format(url))
            appendLog("info: $url")
        }
    }

    // ---- log ---------------------------------------------------------

    private fun buildLogPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        header.addView(label(t("日志")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Collapses the log body so the whole screen goes to the cards. The
        // label carries the hidden-line count so a collapsed panel still shows
        // that something is being logged.
        logToggle = TextView(this).apply {
            text = t("隐藏")
            setTextColor(color(R.color.accent))
            textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { toggleLogVisibility() }
        }
        header.addView(logToggle)

        header.addView(TextView(this).apply {
            text = t("清空")
            setTextColor(color(R.color.accent))
            textSize = 12f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener {
                logBuilder.clear()
                logBuilder.clearSpans()
                logView.text = logBuilder
                stickToBottom = true
            }
        })
        panel.addView(header)

        // No ScrollingMovementMethod and no text selection: the ScrollView owns
        // the gesture, otherwise the TextView tries to handle it itself.
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(color(R.color.log_info))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_log)
            text = logBuilder
        }

        logScroll = NestedScrollableScrollView(this).apply {
            addView(logView)
            isVerticalScrollBarEnabled = true
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = getChildAt(0) ?: return@setOnScrollChangeListener
                stickToBottom = scrollY >= child.height - height - dp(24)
            }
        }

        logPanel = panel
        panel.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(190)
        ))

        return panel
    }

    /**
     * Shows or hides the log body.
     *
     * The panel is the last child of the root column, so hiding its ScrollView
     * lets the content area take the reclaimed space.
     */
    private fun toggleLogVisibility() {
        setLogVisible(!logVisible)
    }

    private fun setLogVisible(visible: Boolean) {
        logVisible = visible
        logScroll.visibility = if (visible) View.VISIBLE else View.GONE
        logPanel.setPadding(
            dp(16), dp(6), dp(16), if (visible) dp(16) else dp(6)
        )
        logToggle.text = if (visible) t("隐藏") else t("显示")
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_LOG_VISIBLE, visible)
            .apply()
        if (visible && stickToBottom) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---- small builders ---------------------------------------------

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_faint))
        textSize = 11f
        letterSpacing = 0.09f
        isAllCaps = true
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
        setOnClickListener { onClick() }
    }

    private fun ghostButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(color(R.color.text))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_ghost)
        setOnClickListener { onClick() }
    }

    /** Inline link-styled text, for "download it from upstream" hints. */
    private fun linkText(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.accent))
        textSize = 11f
        setPadding(0, dp(8), 0, 0)
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String, numeric: Boolean) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(color(R.color.text))
        setHintTextColor(color(R.color.text_faint))
        textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_field)
        if (numeric) {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applySettings() }
    }

    private fun weight(w: Float) = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, w
    )

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    /** Shorthand for [Lang.t]; the Chinese text is the lookup key. */
    private fun t(text: String): String = Lang.t(text)

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    private fun applySettings() {
        if (!::hostField.isInitialized) return
        config.host = hostField.text.toString().trim().ifEmpty { ObcConfig.DEFAULT_HOST }
        config.port = portField.text.toString().trim().toIntOrNull() ?: ObcConfig.DEFAULT_PORT
        config.gadgetPort =
            gadgetField.text.toString().trim().toIntOrNull() ?: ObcConfig.DEFAULT_GADGET_PORT
        config.save()
        refreshStatus()
    }

    private fun onStartClicked() {
        startButton.isEnabled = false
        appendLog(t("---- 启动会话 ----"))

        if (!config.enableTrainer && enabledCommands.isNotEmpty()) {
            appendLog(t("warn: 已勾选 %d 项功能，但 trainer 未启用，将不会加载 trainer.js").format(enabledCommands.size))
        }

        lifecycleScope.launch {
            val ok = client.start(launchFirst = true)
            if (ok) {
                // The gadget blocks the game until we attach, so keep this
                // process resident from here on.
                ObcService.start(this@MainActivity)
                replayEnabledCommands()
            } else {
                appendLog(t("err: 启动失败"))
            }
            refreshStatus()
            startButton.isEnabled = true
        }
    }

    private fun onStopClicked() {
        lifecycleScope.launch {
            client.stop()
            ObcService.stop(this@MainActivity)
            refreshStatus()
        }
    }

    /** Re-applies the switches that were already on when the session starts. */
    private fun replayEnabledCommands() {
        if (!config.enableTrainer) return
        if (enabledCommands.isEmpty()) return
        appendLog(t("info: 重新应用 %d 项已启用功能").format(enabledCommands.size))
        enabledCommands.forEach { client.execTrainerCommand("enable:$it") }
    }

    private fun onCommandToggled(command: String, enabled: Boolean) {
        if (enabled) enabledCommands += command else enabledCommands -= command
        saveCommandState()

        if (!config.enableTrainer) {
            appendLog(t("warn: %s 未生效 —— 请先在上方启用 trainer").format(command))
            return
        }
        if (!client.isActive) {
            appendLog(t("info: %s 已记录，将在会话启动后应用").format(command))
            return
        }
        val prefix = if (enabled) "enable:" else "disable:"
        client.execTrainerCommand("$prefix$command")
    }

    private fun saveCommandState() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(KEY_COMMANDS, enabledCommands.toSet())
            .apply()
    }

    private fun restoreCommandState() {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_COMMANDS, emptySet()) ?: emptySet()
        enabledCommands += saved
        enabledCommands.forEach { command -> commandSwitches[command]?.isChecked = true }
    }

    // ------------------------------------------------------------------
    // dump
    // ------------------------------------------------------------------

    private fun onPullDump() {
        lifecycleScope.launch {
            val access = dumpAccess ?: withContext(Dispatchers.IO) { dumpExporter.detectAccess() }
            dumpAccess = access

            if (access == DumpExporter.Access.NONE) {
                appendLog(t("err: 读不到 %s").format(dumpExporter.remoteDir))
                appendLog(t("err: Android 11+ 禁止读取其他应用的 Android/data，需要 Root 权限"))
                toast("需要 Root 权限")
                return@launch
            }

            appendLog(
                "info: 开始拉取 dump（${DumpExporter.FILE_MAPPING.size} 个文件，" +
                    if (access == DumpExporter.Access.ROOT) t("Root 读取）") else t("直接读取）")
            )

            val result = withContext(Dispatchers.IO) {
                dumpExporter.pull { done, total, name ->
                    if (done == 1 || done % 10 == 0 || done == total) {
                        appendLog("  [$done/$total] $name")
                    }
                }
            }

            appendLog(t("info: 成功 %d/%d → %s").format(result.succeeded, result.total, dumpExporter.dumpDir))

            if (result.succeeded == 0) {
                // An all-empty result means the game has not written its
                // databases yet, not that the copy failed — do not dump the
                // whole 51-name list at the user in that case.
                appendLog(t("warn: 游戏目录里没有 dump 文件 —— 先在训练器开启 dump_json，进一次战斗后再拉取"))
                toast("游戏尚未生成 dump 文件")
                return@launch
            }

            if (result.failed.isNotEmpty()) {
                appendLog(t("warn: %d 个文件缺失：%s").format(result.failed.size, result.failed.joinToString(", ")))
            }
            toast(t("已导出 %d 个文件").format(result.succeeded))
        }
    }

    private fun onClearDump() {
        lifecycleScope.launch {
            val access = dumpAccess ?: withContext(Dispatchers.IO) { dumpExporter.detectAccess() }
            dumpAccess = access

            if (access == DumpExporter.Access.NONE) {
                appendLog(t("err: 需要 Root 权限才能清理游戏端 dump"))
                return@launch
            }

            val result = withContext(Dispatchers.IO) { dumpExporter.clearRemote() }
            appendLog(t("info: 清空游戏端 dump —— 删除 %d/%d 个文件").format(result.succeeded, result.total))
            if (result.succeeded == 0) {
                appendLog(t("warn: 没有删除任何文件（文件可能不存在）"))
            }
        }
    }

    // ------------------------------------------------------------------
    // state + log rendering
    // ------------------------------------------------------------------

    private fun refreshStatus() {
        ui.post {
            val active = client.isActive

            (statusDot.background as? GradientDrawable)?.setColor(
                color(if (active) R.color.ok else R.color.text_faint)
            )
            statusText.text = if (active) t("运行中") else t("已停止")
            statusText.setTextColor(color(if (active) R.color.ok else R.color.text_dim))

            startButton.isEnabled = !active
            stopButton.isEnabled = active
            resumeButton.isEnabled = active

            statusDetail.text = buildString {
                append(t("游戏  %s").format(config.gamePackage)).append('\n')
                append(t("代理  %s").format("http://${config.host}:${config.port}")).append('\n')
                append("Gadget  127.0.0.1:${config.gadgetPort}")
            }

            renderScripts()
        }
    }

    /**
     * Appends one log line, colouring it by level.
     *
     * The whole view is kept as a SpannableStringBuilder rather than using
     * `append` so that each line can carry its own colour.
     */
    private fun appendLog(message: String) {
        val time = stamp.format(Date())
        mirrorToFile("[$time] $message")

        ui.post {
            val sb = logBuilder
            val start = sb.length
            sb.append("$time  $message\n")

            val level = classify(message)
            sb.setSpan(
                ForegroundColorSpan(color(level.colorRes)),
                start, start + 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                ForegroundColorSpan(color(R.color.log_meta)),
                start + 8, start + 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                ForegroundColorSpan(color(level.bodyRes)),
                start + 10, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            logView.text = sb

            if (stickToBottom) {
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun classify(message: String): LogLevel = when {
        message.startsWith("err") || message.contains("EXCEPTION") -> LogLevel.ERR
        message.startsWith("warn") -> LogLevel.WARN
        message.startsWith("info:") && message.contains("running", true) -> LogLevel.OK
        else -> LogLevel.INFO
    }

    private enum class LogLevel(val colorRes: Int, val bodyRes: Int) {
        INFO(R.color.log_meta, R.color.log_info),
        OK(R.color.log_ok, R.color.log_ok),
        WARN(R.color.log_warn, R.color.log_warn),
        ERR(R.color.log_err, R.color.log_err),
    }

    /**
     * Mirrors the log to a file so a session can be inspected with
     * `adb pull` — a release build refuses to emit its own logs to logcat, and
     * the on-screen panel can only be read by scrolling it into view.
     */
    private fun mirrorToFile(line: String) {
        runCatching {
            File(getExternalFilesDir(null), "session.log").appendText(line + "\n")
        }.onFailure { Log.w("OpenBachelorC", "cannot mirror log: ${it.message}") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val PREFS = "obc"
        private const val KEY_COMMANDS = "enabled_commands"
        private const val KEY_LOG_VISIBLE = "log_visible"

        /** Agent file -> what it is responsible for, shown in the modules card. */
        val SCRIPTS: List<Pair<String, String>> = listOf(
            FridaClient.SCRIPT_JAVA to "Java 层与代理",
            FridaClient.SCRIPT_NATIVE to "il2cpp 原生层",
            FridaClient.SCRIPT_EXTRA to "3 倍速 / 血量显示",
            FridaClient.SCRIPT_TRAINER to "功能模块",
        )

        /** Mirrors main.py::command_lst, with Chinese labels for the UI. */
        val TRAINER_COMMANDS: List<Pair<String, String>> = listOf(
            "zero_cost" to "零费用",
            "zero_deploy_cnt" to "零部署数",
            "deploy_everywhere" to "随处部署",
            "zero_cooldown" to "零冷却",
            "unlimited_token" to "无限费用",
            "no_sp" to "无需 SP",
            "withdraw_everything" to "全部撤退",
            "heal_everyone" to "全体治疗",
            "unlimited_ammo" to "无限弹药",
            "eat_enemy" to "吞噬敌人",
            "global_range" to "全局射程",
            "anti_air" to "对空",
            "true_aoe" to "真群体",
            "no_ban_card" to "无禁用卡",
            "cloner_assist" to "克隆辅助",
            "allow_dup_char" to "允许重复干员",
        )
    }
}
