/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.imggen

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 出图渠道：柏宝绘那套「渠道」概念。
 * 每套自带协议、站点、Key、模型，切换渠道不影响聊天提供商，也不用来回改设置。
 */
@Serializable
data class ImgChannel(
    val id: String,
    val name: String,
    val protocol: String = "NOVELAI",   // NOVELAI / OPENAI
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val note: String = "",
)

/** 画风参考预设组：一组参考图 + 强度，按角色/画风存多套，随时切换 */
@Serializable
data class VibePreset(
    val id: String,
    val name: String,
    val paths: List<String> = emptyList(),
    val strength: Float = 0.6f,
)

/** NAI 导出的 vibe 文件（.naiv4vibe / .json）：encoded 已是缓存好的 vibe，可直接发给接口 */
@Serializable
data class VibeJson(
    val id: String,
    val name: String,
    val encoded: String,
    val strength: Float = 0.6f,
    val model: String = "",
    /** 导入后默认启用；可以临时关掉，不必删除 */
    val enabled: Boolean = true,
)

/** 生成尺寸：NAI 要求 64 的倍数，GPT-image 只认几个固定值，所以显式存宽高 */
@Serializable
data class ImageSize(val width: Int, val height: Int) {
    val ratio: Float get() = width.toFloat() / height.toFloat()
    val label: String get() = "${width}×${height}"
}

/** 提示词预设：正向 + 负向，记使用次数，方便挑出常用的那几条 */
@Serializable
data class PromptPreset(
    val id: String,
    val name: String,
    val positive: String,
    val negative: String = "",
    val uses: Int = 0,
)

/** 画师串：常用的画风/画师组合，选中后拼在提示词最前面 */
@Serializable
data class ArtistStyle(
    val id: String,
    val name: String,
    val value: String,
)

/** 角色外貌绑定：名字 + 固定外貌描述，用的时候拼进提示词 */
@Serializable
data class CharacterLook(
    val id: String,
    val name: String,
    val look: String,
)

/** NAI 专用参数：只有走 NAI 系中转时才需要，字段名按 NovelAI 官方 API */
@Serializable
data class NaiParams(
    val enabled: Boolean = false,
    val steps: Int = 28,
    val scale: Float = 5f,
    val sampler: String = "Euler Ancestral",
    val noiseSchedule: String = "Karras",
    val seed: Long = -1L,
    val ucPreset: Int = 0,
    val cfgRescale: Float = 0f,
    val ucStrength: Float = 1f,
    val smea: Boolean = false,
    val smeaDyn: Boolean = false,
    val qualityTags: Boolean = true,
    val varietyBoost: Boolean = false,
    val dynamicThresholding: Boolean = false,
    // 对齐 NovelAI 官方前端（OVONovelAI）的原生参数开关
    val useCoords: Boolean = false,
    val normalizeReferenceStrength: Boolean = true,
    val preferBrownian: Boolean = true,
) {
    /** 转成 NAI 官方 parameters 字段，面板与 AI 工具共用 */
    fun toJsonObject(): JsonObject = buildJsonObject {
        val samplerValue = when (sampler) {
            "Euler Ancestral" -> "k_euler_ancestral"
            "Euler" -> "k_euler"
            "DPM++ 2S Ancestral" -> "k_dpmpp_2s_ancestral"
            "DPM++ 2M SDE" -> "k_dpmpp_2m_sde"
            "DPM++ 2M" -> "k_dpmpp_2m"
            "DPM++ SDE" -> "k_dpmpp_sde"
            "DDIM" -> "ddim_v3"
            else -> "k_euler_ancestral"
        }
        val noiseValue = when (noiseSchedule) {
            "Native" -> "native"
            "Exponential" -> "exponential"
            "Polyexponential" -> "polyexponential"
            else -> "karras"
        }
        put("steps", steps)
        put("scale", scale)
        put("sampler", samplerValue)
        put("noise_schedule", noiseValue)
        put("seed", seed)
        put("ucPreset", ucPreset)
        put("cfg_rescale", cfgRescale)
        put("uc_strength", ucStrength)
        put("sm", smea)
        put("sm_dyn", smeaDyn)
        put("qualityToggle", qualityTags)
        // Variety+ 的官方字段是 skip_cfg_above_sigma（开=19，关=null）。
        // 旧实现误发已废弃的 variety_boost，服务端不认，导致开关点了没效果。
        if (varietyBoost) put("skip_cfg_above_sigma", 19) else put("skip_cfg_above_sigma", JsonNull)
        put("dynamic_thresholding", dynamicThresholding)
        put("use_coords", useCoords)
        put("normalize_reference_strength_multiple", normalizeReferenceStrength)
        put("prefer_brownian", preferBrownian)
    }
}
