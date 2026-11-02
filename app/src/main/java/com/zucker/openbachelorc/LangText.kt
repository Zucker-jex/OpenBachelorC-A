package com.zucker.openbachelorc

/**
 * English translations, keyed by the Chinese source text used in the UI.
 *
 * Keeping the Chinese as the key means a call site never needs an id, and a
 * missing entry simply renders the Chinese original instead of crashing or
 * showing a raw key.
 */
object LangText {

    val EN: Map<String, String> = mapOf(
        // ---- session / modules ----
        "会话" to "Session",
        "启动游戏并在同一进程中建立 Frida 会话" to
            "Launch the game and establish a Frida session in the same process",
        "启动并注入" to "Start & inject",
        "停止" to "Stop",
        "恢复游戏" to "Resume game",
        "运行中" to "Running",
        "已停止" to "Stopped",
        "---- 启动会话 ----" to "---- Starting session ----",
        "注入模块" to "Injection modules",
        "会话建立后自动加载；控制台与异常会回传到日志" to
            "Loaded automatically once the session is up; console output and errors are echoed to the log",
        "Java 层与代理" to "Java layer & proxy",
        "il2cpp 原生层" to "il2cpp native layer",
        "3 倍速 / 血量显示" to "3x speed / HP display",
        "功能模块" to "Modules",

        // ---- endpoint ----
        "服务器与端口" to "Server & ports",
        "客户端与游戏同机运行，Gadget 走本机回环，无需 adb 转发" to
            "The client runs on the same device as the game; the Gadget uses loopback, so no adb forwarding is needed",
        "服务器地址" to "Server address",
        "端口" to "Port",
        "在线" to "Online",
        "测试服" to "Test server",
        "本地" to "Local",
        "保存" to "Save",

        // ---- trainer ----
        "功能模块（trainer）" to "Modules (trainer)",
        "启用 trainer.js" to "Enable trainer.js",
        "勾选的功能会在会话建立后自动应用" to
            "Checked features apply automatically once the session is up",
        "未启用：功能开关不会生效（config.enable_trainer = false）" to
            "Disabled: the feature switches do nothing (config.enable_trainer = false)",
        "零费用" to "Zero cost",
        "零部署数" to "Zero deploy count",
        "随处部署" to "Deploy anywhere",
        "零冷却" to "Zero cooldown",
        "无限费用" to "Unlimited cost",
        "无需 SP" to "No SP cost",
        "全部撤退" to "Withdraw everything",
        "全体治疗" to "Heal everyone",
        "无限弹药" to "Unlimited ammo",
        "吞噬敌人" to "Eat enemies",
        "全局射程" to "Global range",
        "对空" to "Anti-air",
        "真群体" to "True AoE",
        "无禁用卡" to "No banned cards",
        "克隆辅助" to "Cloner assist",
        "允许重复干员" to "Allow duplicate operators",

        // ---- OBS card ----
        "OBS 服务端（Termux）" to "OBS server (Termux)",
        "在手机上跑本地服务端：先导入 OpenBachelorS 本体，再部署兼容层" to
            "Run the server on your phone: import OpenBachelorS first, then deploy the compat layer",
        "① 服务端本体" to "① Server package",
        "未安装 —— 首次使用请选择一个 OpenBachelorS 包" to
            "Not installed - pick an OpenBachelorS package to get started",
        "导入 OpenBachelorS 包" to "Import OpenBachelorS package",
        "更新 OpenBachelorS 包" to "Update OpenBachelorS package",
        "下载最新 OpenBachelorS" to "Download latest OpenBachelorS",
        "没有本体包？从 " to "No package yet? Get it from ",
        "② 兼容层" to "② Compat layer",
        "兼容层" to "Compat layer",
        "导入兼容层 ZIP" to "Import compat layer ZIP",
        "下载最新兼容层" to "Download latest compat layer",
        "部署兼容层" to "Deploy compat layer",
        "重新部署" to "Redeploy",
        "导出当前包" to "Export current package",
        "安装教程" to "Setup guide",
        "兼容层仓库" to "Compat repo",
        "恢复内置版本" to "Restore built-in",
        "授予所有文件访问权限" to "Grant All files access",
        "兼容层会随应用一起发布；也可以在兼容层仓库的 Releases 里下载新版，" to
            "The compat layer ships with the app; you can also grab a newer one from the compat repo's Releases, ",
        "再回到上面点「导入兼容层 ZIP」更新，无需重装应用。" to
            "then come back and tap \"Import compat layer ZIP\" - no need to reinstall the app.",
        "部署前需要先导入本体" to "Import the server package before deploying",
        "需要「所有文件访问权限」才能写入 Download" to
            "All files access is required to write into Download",
        "内置" to "built-in",
        "未知" to "unknown",
        "未部署" to "not deployed",
        "正在检查…" to "Checking…",
        "准备下载…" to "Preparing download…",
        "解包中…" to "Extracting…",
        "导入中…" to "Importing…",
        "查找最新版本…" to "Looking up the latest version…",

        // ---- data export ----
        "数据导出" to "Data export",
        "拉取 DUMP" to "Pull DUMP",
        "清空游戏端" to "Clear game-side",
        "正在检测访问方式…" to "Detecting access method…",
        "Root 已授权 · 可直接读取游戏数据目录" to
            "Root granted · can read the game data directory",
        "可直接读取游戏数据目录（无需 Root）" to
            "Can read the game data directory directly (no root)",
        "未检测到 Root · 无法读取其他应用的 Android/data" to
            "No root detected · cannot read another app's Android/data",
        "Android 11+ 不允许任何应用（即使拥有「所有文件访问权限」）读取其他应用的 " to
            "Android 11+ forbids every app - even with All files access - from reading another app's ",
        "Android/data，所以这里改用 Root 读取。" to
            "Android/data, so this reads it through root instead.",

        // ---- about ----
        "关于" to "About",
        "上游项目" to "Upstream",
        "OpenBachelorC 客户端与 OpenBachelorS 服务端由 " to
            "The OpenBachelorC client and OpenBachelorS server are developed by ",
        " 开发，本应用是在其基础上的安卓封装。" to
            ". This app is an Android wrapper around them.",
        "获取 OBS 服务端包" to "Get OpenBachelorS package",
        "OBS 本体包（OpenBachelorS-master.zip）就在这个仓库，下载后回到" to
            "The OpenBachelorS-master.zip package lives in this repository; after downloading, go back to ",
        "「OBS 服务端」卡片点「导入 OpenBachelorS 包」即可。" to
            "the \"OBS server\" card and tap \"Import OpenBachelorS package\".",
        "打开兼容层仓库" to "Open compat repo",
        "兼容层仓库的 Releases 页面提供独立兼容层包（obs-compat-*.zip），" to
            "The compat repository's Releases page provides standalone packages (obs-compat-*.zip); ",
        "下载后在「OBS 服务端」卡片里点「导入兼容层 ZIP」即可更新。" to
            "download one, then tap \"Import compat layer ZIP\" in the \"OBS server\" card to update.",

        // ---- log panel ----
        "日志" to "Log",
        "隐藏" to "Hide",
        "显示" to "Show",
        "清空" to "Clear",

        // ---- dialogs ----
        "Termux 安装教程" to "Termux setup guide",
        "OBS 安装教程" to "OBS setup guide",
        "关闭" to "Close",
        "复制全文" to "Copy all",
        "已复制到剪贴板" to "Copied to clipboard",

        // ---- toasts / status ----
        "下载失败" to "Download failed",
        "导入失败" to "Import failed",
        "部署失败" to "Deploy failed",
        "导出失败" to "Export failed",
        "游戏尚未生成 dump 文件" to "The game has not produced dump files yet",
        "请先授予所有文件访问权限" to "Grant All files access first",
        "请先导入本体" to "Import the server first",
        "需要 Root 权限" to "Root required",
        "请手动前往：设置 → 特殊权限 → 所有文件访问" to
            "Go to Settings → Special access → All files access",
        "已恢复内置版本" to "Restored built-in version",
        "已导出到 Download/OpenBachelorC" to "Exported to Download/OpenBachelorC",
        "已导入" to "Imported",

        // ---- log lines (static) ----
        "err: 启动失败" to "err: start failed",
        "err: 导出失败" to "err: export failed",
        "err: 需要 Root 权限才能清理游戏端 dump" to
            "err: root is required to clear the game-side dump",
        "err: 还没有导入 OpenBachelorS 本体，请先点「导入 OpenBachelorS 包」" to
            "err: OpenBachelorS is not imported yet - use \"Import OpenBachelorS package\" first",
        "err: Android 11+ 禁止读取其他应用的 Android/data，需要 Root 权限" to
            "err: Android 11+ blocks reading another app's Android/data - root is required",
        "info: trainer 已启用，将在下次启动会话时加载" to
            "info: trainer enabled; it will load on the next session start",
        "info: trainer 已停用" to "info: trainer disabled",
        "info: 下一步 → 点「部署兼容层」" to "info: next → tap \"Deploy compat layer\"",
        "info: 下一步 → 点「部署兼容层」，然后在 Termux 里运行 setup.sh" to
            "info: next → tap \"Deploy compat layer\", then run setup.sh in Termux",
        "info: 现在打开 Termux：cd ~/storage/downloads/OpenBachelorS && bash setup.sh" to
            "info: now open Termux: cd ~/storage/downloads/OpenBachelorS && bash setup.sh",
        "info: 下载完成，开始解包…" to "info: download complete, extracting…",
        "info: 开始导入 OpenBachelorS 本体（覆盖式更新，保留 sav/ 等运行数据）…" to
            "info: importing OpenBachelorS (overwrite update; sav/ and runtime data are kept)…",
        "info: 旧版本保留为 obs-compat.previous.zip" to
            "info: the previous version is kept as obs-compat.previous.zip",
        "info: 正在下载 OpenBachelorS（master.zip）…" to
            "info: downloading OpenBachelorS (master.zip)…",
        "info: 正在下载最新兼容层…" to "info: downloading the latest compat layer…",
        "info: 正在导入兼容层 ZIP…" to "info: importing compat layer ZIP…",
        "info: 正在部署兼容层…" to "info: deploying compat layer…",
        "warn: 下载本体需要「所有文件访问权限」才能写入 Download" to
            "warn: downloading the server needs All files access to write into Download",
        "warn: 导入本体需要「所有文件访问权限」" to
            "warn: importing the server needs All files access",
        "warn: 部署兼容层需要「所有文件访问权限」" to
            "warn: deploying the compat layer needs All files access",
        "warn: 导出需要「所有文件访问权限」" to "warn: exporting needs All files access",
        "warn: 未授予通知权限，前台服务通知可能不显示" to
            "warn: notification permission denied; the foreground-service notice may not show",
        "warn: 没找到 src/openbachelors，下载内容可能不是 OpenBachelorS" to
            "warn: src/openbachelors not found - the download may not be OpenBachelorS",
        "warn: 没有找到 src/openbachelors，这个 ZIP 可能不是 OpenBachelorS 包" to
            "warn: src/openbachelors not found - this ZIP may not be an OpenBachelorS package",
        "warn: 游戏目录里没有 dump 文件 —— 先在训练器开启 dump_json，进一次战斗后再拉取" to
            "warn: no dump files in the game directory - enable dump_json in the trainer, play a battle, then pull",
        "warn: 若提示 HTTP 404，说明兼容层仓库还没有发布 Release" to
            "warn: an HTTP 404 means the compat repository has no Release yet",
        "warn: 没有删除任何文件（文件可能不存在）" to
            "warn: nothing was deleted (the files may not exist)",

        // ---- misc ----
        "明日方舟 · 本机注入客户端" to "Arknights · on-device injection client",
        "无法读取所选文件" to "Cannot read the selected file",
        "下载内容为空" to "Downloaded content is empty",
        "内置兼容层损坏" to "The built-in compat layer is corrupt",
        "完成" to "done",
        "warn: trainer.js 未加载：config.enable_trainer=false 且尚未勾选任何功能" to
            "warn: trainer.js not loaded: config.enable_trainer=false and no feature is checked",
        "warn: 勾选任一功能开关后重新启动会话即可生效" to
            "warn: check any feature, then restart the session to apply it",

        // ---- language switcher ----
        "跟随系统" to "System default",
        "界面语言" to "Language",
        "语言：%s" to "Language: %s",
        "游戏  %s" to "Game  %s",
        "代理  %s" to "Proxy  %s",
        "还没有本体包？从 " to "No package yet? Get it from ",
        " 开发，本应用是在其基础上的安卓封装。" to
            ". This app is an Android wrapper around them.",
        "选择界面语言" to "Choose the interface language",
        "取消" to "Cancel",
        "启动游戏并在同一进程内建立 Frida 会话" to
            "Launch the game and establish a Frida session in the same process",
        "Root 读取）" to "read via root)",
        "直接读取）" to "read directly)",
        "已部署：" to "Deployed: ",
        "info: 已部署 %s：写入 %d，跳过 %d" to "info: deployed %s: %d written, %d skipped",
        "没有可用的浏览器：%s" to "No browser available: %s",
        "从游戏外部数据目录导出 %d 个数据库文件" to
            "Export %d database files from the game's external data directory",
        "OpenBachelorC %s\n本应用作者 %s" to "OpenBachelorC %s\nby %s",
        "兼容层 %s（%s）" to "Compat layer %s (%s)",
        "OpenBachelorC 客户端与 OpenBachelorS 服务端由 %s 开发，本应用是在其基础上的安卓封装。" to
            "The OpenBachelorC client and OpenBachelorS server are developed by %s. This app is an Android wrapper around them.",
        "info: OpenBachelorC 就绪 · 配置 %s" to "info: OpenBachelorC ready · config %s",
        "info: 会话日志 → %s/session.log" to "info: session log → %s/session.log",
        "info: 已保存预设「%s」→ %s:%d (Gadget %d)" to "info: saved preset \"%s\" → %s:%d (Gadget %d)",
        "err: 无法打开文件选择器：%s" to "err: cannot open the file picker: %s",
        "  下载 %d%% · %d MB" to "  downloading %d%% · %d MB",
        "  已解包 %d 个文件 · %d MB" to "  extracted %d files · %d MB",
        "info: 本体已就绪：%d 个文件，%d MB → %s" to "info: server package ready: %d files, %d MB → %s",
        "err: 下载/导入失败：%s" to "err: download/import failed: %s",
        "info: 兼容层地址 %s" to "info: compat layer URL %s",
        "info: 兼容层已更新：%s → %s" to "info: compat layer updated: %s → %s",
        "info: 本体导入完成：%d 个文件，%d MB → %s" to "info: server import complete: %d files, %d MB → %s",
        "info: 已备份 %s（*.obc-bak）" to "info: backed up %s (*.obc-bak)",
        "err: 导入本体失败：%s" to "err: server import failed: %s",
        "err: 导入失败：%s" to "err: import failed: %s",
        "info: 目标 %s" to "info: target %s",
        "info: 覆盖前已备份 %d 个文件（*.obc-bak）" to "info: backed up %d files before overwriting (*.obc-bak)",
        "err: 部署失败：%s" to "err: deploy failed: %s",
        "info: 当前兼容层已导出 → %s" to "info: exported the current compat layer → %s",
        "info: 已恢复内置兼容层 %s" to "info: restored the built-in compat layer %s",
        "err: 恢复失败：%s" to "err: restore failed: %s",
        "warn: su 探测结果: %s" to "warn: su probe: %s",
        "（无输出）" to "(no output)",
        "无" to "none",
        "warn: 已勾选 %d 项功能，但 trainer 未启用，将不会加载 trainer.js" to "warn: %d features are checked but the trainer is disabled; trainer.js will not load",
        "info: 重新应用 %d 项已启用功能" to "info: re-applying %d enabled features",
        "warn: %s 未生效 —— 请先在上方启用 trainer" to "warn: %s did not take effect - enable the trainer above first",
        "info: %s 已记录，将在会话启动后应用" to "info: %s recorded; it applies once the session starts",
        "err: 读不到 %s" to "err: cannot read %s",
        "info: 成功 %d/%d → %s" to "info: %d/%d succeeded → %s",
        "warn: %d 个文件缺失：%s" to "warn: %d files missing: %s",
        "info: 清空游戏端 dump —— 删除 %d/%d 个文件" to "info: cleared game-side dump - deleted %d/%d files",
        // ---- templates (String.format) ----
        "保存到「%s」" to "Save to \"%s\"",
        "已保存到「%s」" to "Saved to \"%s\"",
        "已安装 · Download/OpenBachelorS（%d 个文件）" to
            "Installed · Download/OpenBachelorS (%d files)",
        "版本 %s（%s）" to "Version %s (%s)",
        "版本 %s（%s，内置 %s）" to "Version %s (%s, built-in %s)",
        "下载 OpenBachelorS · %d%% · %d / %d MB" to
            "Downloading OpenBachelorS · %d%% · %d / %d MB",
        "下载 OpenBachelorS · %d MB" to "Downloading OpenBachelorS · %d MB",
        "解包中 · %d 个文件 · %d MB" to "Extracting · %d files · %d MB",
        "下载兼容层 · %d%% · %d KB" to "Downloading compat layer · %d%% · %d KB",
        "已导入 %d 个文件" to "Imported %d files",
        "已导入 %s" to "Imported %s",
        "已部署 %d 个文件" to "Deployed %d files",
        "info: 切换预设「%s」→ %s:%d" to "info: switched preset \"%s\" → %s:%d",
    )
}
