package com.zucker.openbachelorc

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * Kotlin replacement for dump.py.
 *
 * The Python version shelled out to `adb pull` because it ran on a PC. On the
 * same device the naive port would be to read the directory directly — but
 * Android 11+ refuses that for *every* app, including one holding
 * MANAGE_EXTERNAL_STORAGE: another app's `Android/data` directory is explicitly
 * carved out of that permission. So the on-device equivalent of `adb pull` is a
 * root `su -c cp`, with a direct read kept as a fast path for devices/ROMs that
 * do allow it.
 */
class DumpExporter(
    private val context: Context,
    private val gamePackage: String,
) {

    companion object {
        /**
         * Verbatim from dump.py::remote_local_filename_mapping — remote name
         * inside the game's files dir -> local name.
         */
        val FILE_MAPPING: List<Pair<String, String>> = listOf(
            "dump.cs" to "dump.cs",
            "skin_db (Torappu.SkinDB).json" to "skin_table.json",
            "charword_db (Torappu.CharWordDB).json" to "charword_table.json",
            "uni_equip_db (Torappu.UniEquipDB).json" to "uniequip_table.json",
            "CharacterDB.json" to "character_table.json",
            "StoryDB.json" to "story_table.json",
            "stage_db (Torappu.StageDB).json" to "stage_table.json",
            "handbook_info_db (Torappu.HandbookInfoDB).json" to "handbook_info_table.json",
            "retro_db (Torappu.RetroDB).json" to "retro_table.json",
            "display_meta_db (Torappu.DisplayMetaDB).json" to "display_meta_table.json",
            "medal_db (Torappu.MedalDB).json" to "medal_table.json",
            "StoryReviewDB.json" to "story_review_table.json",
            "story_review_meta_db (Torappu.StoryReviewMetaDB).json" to "story_review_meta_table.json",
            "enemy_handbook_db (Torappu.EnemyHandBookDB).json" to "enemy_handbook_table.json",
            "activity_db (Torappu.ActivityDB).json" to "activity_table.json",
            "char_patch_db (Torappu.CharPatchDB).json" to "char_patch_table.json",
            "climb_tower_db (Torappu.ClimbTowerDB).json" to "climb_tower_table.json",
            "building_db (Torappu.BuildingDB).json" to "building_data.json",
            "sandbox_perm_db (Torappu.SandboxPermDB).json" to "sandbox_perm_table.json",
            "roguelike_topic_db (Torappu.RoguelikeTopicDB).json" to "roguelike_topic_table.json",
            "SkillDB.json" to "skill_table.json",
            "BattleUniEquipDB.json" to "battle_equip_table.json",
            "gacha_db (Torappu.GachaDB).json" to "gacha_table.json",
            "item_db (Torappu.ItemDB).json" to "item_table.json",
            "campaign_db (Torappu.CampaignDB).json" to "campaign_table.json",
            "audio_db (Torappu.Audio.Middleware.Data.TorappuAudioDB).json" to "audio_data.json",
            "ChapterDB.json" to "chapter_table.json",
            "char_meta_db (Torappu.CharMetaDB).json" to "char_meta_table.json",
            "charm_db (Torappu.CharmDB).json" to "charm_table.json",
            "checkin_db (Torappu.CheckInDB).json" to "checkin_table.json",
            "clue_db (Torappu.ClueDB).json" to "clue_data.json",
            "crisis_v2_db (Torappu.CrisisV2DB).json" to "crisis_v2_table.json",
            "crisis_db (Torappu.CrisisDB).json" to "crisis_table.json",
            "favor_db (Torappu.FavorDB).json" to "favor_table.json",
            "game_data_consts_db (Torappu.GameDataConstsDB).json" to "gamedata_const.json",
            "HandbookTeamDB.json" to "handbook_team_table.json",
            "hotupdate_meta_db (Torappu.HotUpdateMetaDB).json" to "hotupdate_meta_table.json",
            "mission_db (Torappu.MissionDB).json" to "mission_table.json",
            "open_server_db (Torappu.OpenServerDB).json" to "open_server_table.json",
            "RangeDB.json" to "range_table.json",
            "ReplicateDB.json" to "replicate_table.json",
            "shop_client_db (Torappu.ShopClientDB).json" to "shop_client_table.json",
            "tip_db (Torappu.TipDB).json" to "tip_table.json",
            "TokenDB.json" to "token_table.json",
            "zone_db (Torappu.ZoneDB).json" to "zone_table.json",
            "init_text_db (Torappu.InitTextDB).json" to "init_text.json",
            "main_text_db (Torappu.MainTextDB).json" to "main_text_table.json",
            "meta_ui_db (Torappu.MetaUIDisplayDB).json" to "meta_ui_table.json",
            "CharMasterDB.json" to "char_master_table.json",
            "special_operator_db (Torappu.SpecialOperatorDB).json" to "special_operator_table.json",
            "arkvent_db (Torappu.ArkOdcDB).json" to "arkvent_table.json",
        )
    }

    /** How the game's private directory can be reached from this app. */
    enum class Access {
        /** Plain file I/O works (rare on Android 11+, possible on older ROMs). */
        DIRECT,

        /** Requires `su` (the normal case on a rooted device). */
        ROOT,

        /** Neither: the dump feature cannot work here. */
        NONE,
    }

    /** `<external storage>/Android/data/<game>/files` — where the game dumps its DBs. */
    val remoteDir: File
        get() = File(
            Environment.getExternalStorageDirectory(),
            "Android/data/$gamePackage/files"
        )

    /** Destination inside our own external files dir, mirroring dump/. */
    val dumpDir: File
        get() = File(context.getExternalFilesDir(null), "dump")

    /** Probes what it takes to read the game's directory. Blocking; call off the UI thread. */
    fun detectAccess(): Access {
        if (remoteDir.isDirectory && remoteDir.canRead()) return Access.DIRECT
        if (RootShell.available()) return Access.ROOT
        return Access.NONE
    }

    /** Equivalent of dump.py::pull_dumped_json(). */
    fun pull(onProgress: (done: Int, total: Int, name: String) -> Unit): Result {
        val access = detectAccess()
        if (access == Access.NONE) {
            return Result(0, FILE_MAPPING.size, FILE_MAPPING.map { it.first }, access)
        }

        dumpDir.mkdirs()

        val failed = mutableListOf<String>()
        var copied = 0

        FILE_MAPPING.forEachIndexed { index, (remoteName, localName) ->
            onProgress(index + 1, FILE_MAPPING.size, localName)

            val source = File(remoteDir, remoteName)
            val target = File(dumpDir, localName)

            val ok = if (access == Access.ROOT) copyWithRoot(source, target)
            else copyDirect(source, target)

            if (!ok) {
                failed += remoteName
                return@forEachIndexed
            }

            copied++
            prettyPrintJson(target, localName)
        }

        return Result(copied, FILE_MAPPING.size, failed, access)
    }

    fun clearRemote(): Result {
        val access = detectAccess()
        if (access == Access.NONE) {
            return Result(0, FILE_MAPPING.size, FILE_MAPPING.map { it.first }, access)
        }

        var removed = 0
        FILE_MAPPING.forEach { (remoteName, _) ->
            val path = File(remoteDir, remoteName).path
            val ok = when (access) {
                Access.ROOT -> {
                    val out = RootShell.run("rm -f ${RootShell.quote(path)}; [ -e ${RootShell.quote(path)} ] && echo STILL || echo GONE")
                    out?.contains("GONE") == true
                }
                else -> File(remoteDir, remoteName).delete()
            }
            if (ok) removed++
        }
        return Result(removed, FILE_MAPPING.size, emptyList(), access)
    }

    fun clearLocal(): Int {
        var removed = 0
        dumpDir.listFiles()?.forEach { if (it.isFile && it.delete()) removed++ }
        return removed
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun copyDirect(source: File, target: File): Boolean = runCatching {
        if (!source.isFile) return false
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        true
    }.getOrDefault(false)

    private fun copyWithRoot(source: File, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        target.delete()

        val out = RootShell.run(
            "cp -f ${RootShell.quote(source.path)} ${RootShell.quote(target.path)} && echo OBC_OK"
        )
        if (out?.contains("OBC_OK") != true) return false
        target.isFile && target.length() > 0
    }.getOrDefault(false)

    /** dump.py pretty-prints every JSON it pulls; keep that behaviour. */
    private fun prettyPrintJson(target: File, localName: String) {
        if (!localName.endsWith(".json")) return
        runCatching {
            val pretty = JSONObject(target.readText(Charsets.UTF_8)).toString(4)
            target.writeText(pretty, Charsets.UTF_8)
        }
    }

    data class Result(
        val succeeded: Int,
        val total: Int,
        val failed: List<String>,
        val access: Access,
    )
}
