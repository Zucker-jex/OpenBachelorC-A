package com.zucker.openbachelorc

/**
 * The step-by-step Termux guide shown in-app.
 *
 * Kept as plain text (not markdown) so it renders identically in the dialog and
 * can be copied out verbatim — the commands are meant to be pasted into Termux.
 */
object ObsGuide {

    fun build(): String = if (Lang.isEnglish()) EN else ZH

    private val ZH = """
如何在 Termux 里安装 OBS 服务端
════════════════════════════════════

一、安装 Termux
────────────────
1. 从 F-Droid 或 GitHub 安装 Termux：
   github.com/termux/termux-app/releases
   注意：不要用 Google Play 里的版本，那个已经停止更新。

2. 打开 Termux，先给存储权限：
   termux-setup-storage
   弹窗选「允许」。

   这一步不能跳过：项目就放在 ~/storage/downloads 下，
   没有授权的话这个路径根本不存在，后面所有命令都会失败。


二、准备 OpenBachelorS
────────────────
3. 把 OpenBachelorS 整个仓库解压到：
   内部存储/Download/OpenBachelorS

   仓库地址：github.com/pfyy/OpenBachelorS
   下载 OpenBachelorS-master.zip 即可，本应用也能直接导入这个 zip。

   目录里应当能看到 src、conf、data、res 这几个文件夹。
   放好后再回到本应用。


三、部署兼容层（本应用完成）
────────────────
4. 在本页点「部署兼容层」。

   它会把下面这些文件写进 Download/OpenBachelorS：
     setup.sh            一次性环境准备
     start.sh            启动器
     start.py            启动入口
     requirements.txt    依赖清单（可自行修改）
     obc/sitecustomize.py  运行时兼容层

   这个目录你可以随时用文件管理器打开、修改，
   以后换设备或更新依赖都用得上。


四、安装依赖
────────────────
5. 在 Termux 里依次执行：

   cd ~/storage/downloads/OpenBachelorS
   bash setup.sh

   脚本会在 ~/.obc/venv 创建虚拟环境并安装依赖，大约 1–2 分钟。
   最后会打印自检结果，每个模块一行 ok / FAIL。

   注意：虚拟环境放在 ~/.obc/venv，而不是项目目录里，
   因为 /sdcard 是共享存储，不支持 venv 需要的符号链接。


五、启动服务端
────────────────
6. 启动：

   bash start.sh

   看到 http://0.0.0.0:8443 这样的输出就成功了。
   这个窗口要保持运行，或者用第六步做自启。

7. 回到本应用，把服务器地址改成 127.0.0.1
   （点「服务器与端口」里的「本地」按钮）。


六、可选：安装时已配置自启
────────────────
8. setup.sh 最后会问「只启用 OBS（服务端）？」。
   回答 y 之后，以后每次打开 Termux 都会自动启动服务端。

   不过 start.sh 会先弹一句确认，避免每次都直接开服务：

     Enter = 启动
     n     = 这次跳过
     q     = 顺便取消开机自启

   想不要确认直接启动，用：
   OBC_YES=1 bash start.sh

   想撤销：编辑 ${'$'}PREFIX/etc/termux-login.sh 删掉那一行。


七、更新依赖
────────────────
9. Download/OpenBachelorS/requirements.txt 就是依赖清单，
   可以直接用文本编辑器改。

   改完重新执行：
   bash setup.sh

   注意：本应用只会「补」文件，不会覆盖你改过的
   requirements.txt，所以你的修改不会丢。


八、常见问题
────────────────
· 端口被占用
  换一个端口启动：
  OBC_PORT=8444 bash start.sh

· 手机在疯狂下载游戏资源
  OBS 自带的 conf/config.json 里 redirect_asset 默认是 false，
  这会让服务端自己下载并缓存资源包。
  新版 start.py 每次启动前会把它纠正为 true，无需手动改；
  如果启动时看到「已把 redirect_asset 设为 true」，就是这个原因。

· 卡在 pydantic-core（长时间无输出）
  它是 Rust 扩展，PyPI 只给源码包，在手机上编译极慢。
  新版 setup.sh 会自动从 TUR 预编译源安装它，跳过编译。
  如果仍然卡住，Ctrl-C 后重新运行 setup.sh 即可。

· 提示缺少预编译 wheel
  说明某个包只有源码包。默认不编译（避免卡死），
  确需编译时这样跑：
  OBC_ALLOW_BUILD=1 bash setup.sh

· 提示找不到 .venv
  说明还没装依赖，执行：bash setup.sh

· 报错 Permission denied: 'lib' -> '.venv/lib64'
  旧版脚本把虚拟环境建在了 /sdcard 上，共享存储不支持符号链接。
  重新导入本应用的兼容层后再跑 setup.sh 即可（新脚本改到 ~/.obc/venv）。

· 自检里 Crypto 显示 warn
  执行：pkg install python-pycryptodomex
  只影响战斗日志解码，其余功能正常。

· 想重装依赖
  删掉虚拟环境再跑一次：
  rm -rf ~/.obc/venv && bash setup.sh

· 想关掉热重载（更省电）
  OBC_RELOAD=0 bash start.sh
""".trimIndent()

    private val EN = """
Installing the OBS server in Termux
════════════════════════════════════

1. Install Termux
────────────────
1. Install Termux from F-Droid or GitHub:
   github.com/termux/termux-app/releases
   Do not use the Google Play build - it is no longer maintained.

2. Open Termux and grant storage access first:
   termux-setup-storage
   Tap "Allow" in the dialog.

   This step cannot be skipped: the project lives under
   ~/storage/downloads, and without the grant that path does not
   exist, so every later command would fail.


2. Get OpenBachelorS
────────────────
3. Extract the whole OpenBachelorS repository to:
   Internal storage/Download/OpenBachelorS

   Repository: github.com/pfyy/OpenBachelorS
   Download OpenBachelorS-master.zip; this app can import that zip directly.

   You should see src, conf, data and res in that folder.
   Then come back to this app.


3. Deploy the compat layer (done here)
────────────────
4. Tap "Deploy compat layer" on this page.

   It writes these files into Download/OpenBachelorS:
     setup.sh              one-time environment setup
     start.sh              launcher
     start.py              entry point
     requirements.txt      dependency list (edit freely)
     obc/sitecustomize.py  runtime compatibility shims

   The folder stays fully accessible from a file manager, so you can
   tweak things by hand or move them to a new device later.


4. Install dependencies
────────────────
5. In Termux run:

   cd ~/storage/downloads/OpenBachelorS
   bash setup.sh

   The script creates a virtualenv in ~/.obc/venv and installs the
   dependencies; it takes about 1-2 minutes and prints one ok / FAIL
   line per required module.

   The venv lives in ~/.obc/venv rather than in the project because
   /sdcard is shared storage and does not support the symlink that
   `python -m venv` needs.


5. Start the server
────────────────
6. Start it:

   bash start.sh

   Seeing http://0.0.0.0:8443 means it worked.
   Keep that session running, or use step 6 to auto-start it.

7. Back in this app, set the server address to 127.0.0.1
   (tap the "Local" preset under "Server & ports").


6. Optional: auto-start
────────────────
8. setup.sh ends by asking "only enable OBS (server)?".
   Answer y and Termux will start the server on every launch.

   start.sh still asks for confirmation first, so you are not forced
   into starting it every time:

     Enter = start
     n     = skip this time
     q     = also remove the auto-start entry

   To skip the confirmation entirely:
   OBC_YES=1 bash start.sh

   To undo: edit ${'$'}PREFIX/etc/termux-login.sh and delete that line.


7. Updating dependencies
────────────────
9. Download/OpenBachelorS/requirements.txt is the dependency list;
   edit it with any text editor.

   Then rerun:
   bash setup.sh

   This app only fills in files and never overwrites a requirements.txt
   you have edited, so your changes are kept.


8. Troubleshooting
────────────────
· Port already in use
  Start on another port:
  OBC_PORT=8444 bash start.sh

· The phone downloads game assets endlessly
  OpenBachelorS ships conf/config.json with redirect_asset=false,
  which makes the server download and cache the assets itself.
  The new start.py forces it back to true on every start, so no manual
  edit is needed; seeing "redirect_asset set to true" at startup is this.

· Stuck on pydantic-core (no output for a long time)
  It is a Rust extension and PyPI only ships sources, which is very slow
  to build on a phone. The new setup.sh installs a prebuilt wheel from
  TUR instead. If it still hangs, Ctrl-C and run setup.sh again.

· "no prebuilt wheel available"
  Some package is source-only. Building is off by default to avoid a
  hang; if you really need it:
  OBC_ALLOW_BUILD=1 bash setup.sh

· "virtualenv not found"
  Dependencies are not installed yet: bash setup.sh

· Permission denied: 'lib' -> '.venv/lib64'
  An older script put the venv on /sdcard, which cannot hold symlinks.
  Reimport the compat layer from this app, then run setup.sh again
  (the new script uses ~/.obc/venv).

· Crypto shows warn in the self-check
  Run: pkg install python-pycryptodomex
  Only battle-log decoding is affected; everything else works.

· Reinstall dependencies
  Delete the venv and run again:
  rm -rf ~/.obc/venv && bash setup.sh

· Disable auto-reload (saves battery)
  OBC_RELOAD=0 bash start.sh
""".trimIndent()
}
