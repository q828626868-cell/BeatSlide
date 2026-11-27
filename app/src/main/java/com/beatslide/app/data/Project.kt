package com.beatslide.app.data

import org.json.JSONArray
import org.json.JSONObject

enum class FitMode { FILL, FIT }

/** 換圖特效。transition = 只在換圖瞬間作用；Ken Burns 則是整段持續作用。 */
enum class Effect(
    val key: String,
    val label: String,
    val desc: String,
    val defaultOn: Boolean,
    val transition: Boolean = true,
) {
    FLASH_WHITE("flash_white", "閃白", "換圖瞬間白光一閃再淡出", true),
    FLASH_BLACK("flash_black", "閃黑", "換圖瞬間變暗再亮回來", false),
    ZOOM_PUNCH("zoom_punch", "縮放衝擊", "新圖從約 110% 快速回到 100%", true),
    SHAKE("shake", "畫面震動", "換圖時畫面抖動一下", false),
    RGB_SPLIT("rgb_split", "RGB 色偏", "紅綠藍分離的故障感", false),
    GLITCH("glitch", "故障切片", "橫向錯位的數位故障", false),
    CROSSFADE("crossfade", "交叉淡入淡出", "舊圖淡出、新圖淡入", false),
    KEN_BURNS("ken_burns", "緩慢推近", "兩次換圖之間圖片慢慢放大或平移（Ken Burns）", true, transition = false);

    companion object {
        fun fromKey(k: String) = entries.firstOrNull { it.key == k }
    }
}

data class EffectConfig(val enabled: Boolean, val intensity: Float)

fun defaultEffects(): Map<Effect, EffectConfig> =
    Effect.entries.associateWith { EffectConfig(it.defaultOn, 0.6f) }

data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val musicFile: String? = null,
    val musicName: String? = null,
    val musicDurationMs: Long = 0,
    val images: List<String> = emptyList(),
    val bpm: Double = 120.0,
    val offsetMs: Long = 0,
    val beatsPerSlide: Int = 1,
    val beatsPerBar: Int = 4,
    val fitMode: FitMode = FitMode.FILL,
    val effects: Map<Effect, EffectConfig> = defaultEffects(),
    val randomMode: Boolean = false,
    val accentMode: Boolean = false,
    /** 強弱拍模式下，非重拍的特效強度倍率 */
    val weakBeatStrength: Float = 0.35f,
) {
    fun effect(e: Effect): EffectConfig = effects[e] ?: EffectConfig(false, 0.6f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("musicFile", musicFile ?: JSONObject.NULL)
        put("musicName", musicName ?: JSONObject.NULL)
        put("musicDurationMs", musicDurationMs)
        put("images", JSONArray(images))
        put("bpm", bpm)
        put("offsetMs", offsetMs)
        put("beatsPerSlide", beatsPerSlide)
        put("beatsPerBar", beatsPerBar)
        put("fitMode", fitMode.name)
        put("effects", JSONObject().apply {
            effects.forEach { (e, c) ->
                put(e.key, JSONObject().put("enabled", c.enabled).put("intensity", c.intensity.toDouble()))
            }
        })
        put("randomMode", randomMode)
        put("accentMode", accentMode)
        put("weakBeatStrength", weakBeatStrength.toDouble())
    }

    companion object {
        const val MIN_BPM = 30.0
        const val MAX_BPM = 300.0

        fun fromJson(o: JSONObject): Project {
            val imgs = o.optJSONArray("images")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val eff = defaultEffects().toMutableMap()
            o.optJSONObject("effects")?.let { eo ->
                eo.keys().forEach { k ->
                    val e = Effect.fromKey(k) ?: return@forEach
                    val c = eo.getJSONObject(k)
                    eff[e] = EffectConfig(c.optBoolean("enabled"), c.optDouble("intensity", 0.6).toFloat())
                }
            }
            return Project(
                id = o.getString("id"),
                name = o.optString("name", "未命名"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                musicFile = if (o.isNull("musicFile")) null else o.optString("musicFile"),
                musicName = if (o.isNull("musicName")) null else o.optString("musicName"),
                musicDurationMs = o.optLong("musicDurationMs"),
                images = imgs,
                bpm = o.optDouble("bpm", 120.0),
                offsetMs = o.optLong("offsetMs"),
                beatsPerSlide = o.optInt("beatsPerSlide", 1),
                beatsPerBar = o.optInt("beatsPerBar", 4),
                fitMode = runCatching { FitMode.valueOf(o.optString("fitMode")) }.getOrDefault(FitMode.FILL),
                effects = eff,
                randomMode = o.optBoolean("randomMode"),
                accentMode = o.optBoolean("accentMode"),
                weakBeatStrength = o.optDouble("weakBeatStrength", 0.35).toFloat(),
            )
        }
    }
}
