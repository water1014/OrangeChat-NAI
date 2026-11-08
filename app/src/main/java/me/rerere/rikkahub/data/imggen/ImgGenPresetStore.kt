/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.imggen

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * 生图面板的设置仓库。
 *
 * 面板和 AI 工具都读这里，保证「页面上点的生成」和「聊天里让 AI 生成」用的是同一套
 * 画师串、角色外貌、负向词、尺寸与 NAI 参数，不再各走一条路。
 */
class ImgGenPresetStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("imggen_library", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // 老版本把参考图放在临时目录，这里迁到私有目录，避免被系统清掉
        migrateReferenceFiles()
        // 老版本用内容前 N 字当预设名，会出现 "2.5d,semi-" / "year 2025," 这种脏名字
        migrateArtistNames()
    }

    private fun migrateArtistNames() {
        runCatching {
            val list = artists()
            if (list.isEmpty()) return
            var changed = false
            val fixed = list.mapIndexed { index, artist ->
                val dirty = artist.name.contains(",") ||
                    artist.name.contains("：") ||
                    artist.name.length > 20 ||
                    artist.name.isBlank()
                if (dirty) {
                    changed = true
                    artist.copy(name = "预设 ${index + 1}")
                } else {
                    artist
                }
            }
            if (changed) setArtists(fixed)

            // 指到已删除预设上的 active id 一并清掉
            val activeId = prefs.getString(KEY_ACTIVE_ARTIST, null)
            if (activeId != null && fixed.none { it.id == activeId }) {
                prefs.edit().putString(KEY_ACTIVE_ARTIST, null).apply()
            }
        }
    }

    private fun migrateReferenceFiles() {
        runCatching {
            val raw = prefs.getString(KEY_REF_IMAGES, null) ?: return
            val paths = json.decodeFromString<List<String>>(raw)
            val migrated = paths.map { VibeFiles.ensureLocal(context, it) }
            if (migrated != paths) {
                prefs.edit().putString(KEY_REF_IMAGES, json.encodeToString(migrated)).apply()
            }
        }
    }

    fun naiParams(): NaiParams = runCatching {
        prefs.getString(KEY_NAI, null)?.let { json.decodeFromString<NaiParams>(it) } ?: NaiParams()
    }.getOrDefault(NaiParams())

    fun characters(): List<CharacterLook> = runCatching {
        prefs.getString(KEY_CHARACTERS, null)
            ?.let { json.decodeFromString<List<CharacterLook>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun artists(): List<ArtistStyle> = runCatching {
        prefs.getString(KEY_ARTISTS, null)
            ?.let { json.decodeFromString<List<ArtistStyle>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun activeArtist(): ArtistStyle? {
        val id = prefs.getString(KEY_ACTIVE_ARTIST, null) ?: return null
        return artists().firstOrNull { it.id == id }
    }

    fun channels(): List<ImgChannel> = runCatching {
        prefs.getString(KEY_CHANNELS, null)
            ?.let { json.decodeFromString<List<ImgChannel>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun activeChannelId(): String? = prefs.getString(KEY_ACTIVE_CHANNEL, null)

    fun activeChannel(): ImgChannel? {
        val id = activeChannelId() ?: return null
        return channels().firstOrNull { it.id == id }
    }

    /**
     * 负向词：第一次使用时给一份通用兜底，明显减少崩坏；
     * 用户手动清空后（key 已存在且为空）就不再回填。
     */
    fun negativePrompt(): String {
        if (!prefs.contains(KEY_NEGATIVE)) return DEFAULT_NEGATIVE
        return prefs.getString(KEY_NEGATIVE, "").orEmpty()
    }

    /** 面板里加的参考图（NAI 下作为 vibe），AI 调用时也带同一批 */
    fun referenceImages(): List<String> = runCatching {
        prefs.getString(KEY_REF_IMAGES, null)
            ?.let { json.decodeFromString<List<String>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList()).filter { VibeFiles.isValid(it) }

    fun referenceStrength(): Float = prefs.getFloat(KEY_REF_STRENGTH, 0.6f)

    fun vibePresets(): List<VibePreset> = runCatching {
        prefs.getString(KEY_VIBE_PRESETS, null)
            ?.let { json.decodeFromString<List<VibePreset>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    /** NAI 导出的 vibe 文件（encoded 已是缓存数据），与图片参考图分开管理 */
    fun vibeJsons(): List<VibeJson> = runCatching {
        prefs.getString(KEY_VIBE_JSONS, null)
            ?.let { json.decodeFromString<List<VibeJson>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun saveVibeJson(item: VibeJson) {
        prefs.edit()
            .putString(KEY_VIBE_JSONS, json.encodeToString(vibeJsons() + item))
            .apply()
    }

    fun updateVibeJson(item: VibeJson) {
        prefs.edit()
            .putString(
                KEY_VIBE_JSONS,
                json.encodeToString(vibeJsons().map { if (it.id == item.id) item else it })
            )
            .apply()
    }

    fun deleteVibeJson(id: String) {
        prefs.edit()
            .putString(KEY_VIBE_JSONS, json.encodeToString(vibeJsons().filterNot { it.id == id }))
            .apply()
    }

    fun activeVibeId(): String? = prefs.getString(KEY_ACTIVE_VIBE, null)

    /** 把当前参考图与强度存成一组 */
    fun saveVibePreset(name: String): VibePreset {
        val item = VibePreset(
            id = java.util.UUID.randomUUID().toString(),
            name = name.ifBlank { "预设 ${vibePresets().size + 1}" },
            paths = referenceImages(),
            strength = referenceStrength(),
        )
        prefs.edit()
            .putString(KEY_VIBE_PRESETS, json.encodeToString(vibePresets() + item))
            .putString(KEY_ACTIVE_VIBE, item.id)
            .apply()
        return item
    }

    /** 切到某一组：把图与强度一起写回当前配置 */
    fun applyVibePreset(id: String?) {
        if (id == null) {
            prefs.edit().putString(KEY_ACTIVE_VIBE, null).apply()
            return
        }
        val preset = vibePresets().firstOrNull { it.id == id } ?: return
        setReferenceImages(preset.paths.filter { java.io.File(it).exists() })
        setReferenceStrength(preset.strength)
        prefs.edit().putString(KEY_ACTIVE_VIBE, id).apply()
    }

    fun deleteVibePreset(id: String) {
        prefs.edit()
            .putString(KEY_VIBE_PRESETS, json.encodeToString(vibePresets().filterNot { it.id == id }))
            .putString(KEY_ACTIVE_VIBE, activeVibeId()?.takeIf { it != id })
            .apply()
    }

    /** AUTO / TAG / NATURAL */
    fun promptStyle(): String = prefs.getString(KEY_PROMPT_STYLE, "AUTO") ?: "AUTO"

    fun setPromptStyle(style: String) {
        prefs.edit().putString(KEY_PROMPT_STYLE, style).apply()
    }

    /** 实际生效的风格：AUTO 时按渠道协议决定（NAI 用标签，其它用自然语言） */
    fun effectivePromptStyle(): String {
        val raw = promptStyle()
        if (raw == "TAG" || raw == "NATURAL") return raw
        return if (isNaiChannel()) "TAG" else "NATURAL"
    }

    fun defaultCount(): Int = prefs.getInt(KEY_COUNT, 1).coerceIn(1, 4)

    // ---------------------------- 写入：设置页与面板共用同一份 ----------------------------

    fun setDefaultCount(count: Int) {
        prefs.edit().putInt(KEY_COUNT, count.coerceIn(1, 4)).apply()
    }

    fun setNegativePrompt(text: String) {
        prefs.edit().putString(KEY_NEGATIVE, text).apply()
    }

    fun setImageSize(size: ImageSize) {
        prefs.edit().putString(KEY_SIZE, json.encodeToString(size)).apply()
    }

    fun setReferenceImages(paths: List<String>) {
        prefs.edit().putString(KEY_REF_IMAGES, json.encodeToString(paths)).apply()
    }

    fun setReferenceStrength(value: Float) {
        prefs.edit().putFloat(KEY_REF_STRENGTH, value.coerceIn(0.1f, 1f)).apply()
    }

    fun setNaiParams(params: NaiParams) {
        prefs.edit().putString(KEY_NAI, json.encodeToString(params)).apply()
    }

    fun setArtists(list: List<ArtistStyle>) {
        prefs.edit().putString(KEY_ARTISTS, json.encodeToString(list)).apply()
    }

    fun setActiveArtist(id: String?) {
        prefs.edit().putString(KEY_ACTIVE_ARTIST, id).apply()
    }

    fun setCharacters(list: List<CharacterLook>) {
        prefs.edit().putString(KEY_CHARACTERS, json.encodeToString(list)).apply()
    }

    fun setChannels(list: List<ImgChannel>) {
        prefs.edit().putString(KEY_CHANNELS, json.encodeToString(list)).apply()
    }

    fun setActiveChannel(id: String?) {
        prefs.edit().putString(KEY_ACTIVE_CHANNEL, id).apply()
    }

    fun updateChannel(channel: ImgChannel) {
        val list = channels().toMutableList()
        val index = list.indexOfFirst { it.id == channel.id }
        if (index >= 0) list[index] = channel else list.add(channel)
        setChannels(list)
        if (activeChannelId() == null) setActiveChannel(channel.id)
    }

    fun deleteChannel(id: String) {
        setChannels(channels().filterNot { it.id == id })
        if (activeChannelId() == id) setActiveChannel(null)
    }

    /** 给 AI 看的当前配置快照，让它准确描述流程、不臆测 */
    fun describeCurrentSetup(): String {
        val channel = activeChannel()
        val artist = activeArtist()
        val nai = naiParams()
        val size = imageSize()
        val refs = referenceImages().size
        return buildString {
            appendLine("渠道：" + (channel?.let {
                "${it.name}（${if (it.protocol == "OPENAI") "OpenAI 兼容" else "NAI 原生"}，${it.modelId}）"
            } ?: "未选择渠道，回落到设置里的图片模型"))
            appendLine("正面预设词（画师串）：" + (artist?.let { "${it.name} = ${it.value}" } ?: "未选择"))
            appendLine("负向预设词：" + negativePrompt().ifBlank { "未设置" })
            appendLine("默认尺寸：${size.width}×${size.height}，默认张数：${defaultCount()}")
            appendLine("Vibe 参考图：${refs} 张，强度 ${referenceStrength()}")
            val looks = characters()
            appendLine(
                "角色档案：" + if (looks.isEmpty()) "暂无（需要固定外貌时用 save_character_look 建档）"
                else looks.joinToString("、") { "@${it.name}" }
            )
            appendLine("质量词：${if (nai.enabled && nai.qualityTags) "开启（best quality, very aesthetic, absurdres）" else "关闭"}")
            appendLine("NAI 参数：" + if (nai.enabled) "已启用（steps=${nai.steps}, scale=${nai.scale}, sampler=${nai.sampler}）" else "未启用")
        }.trim()
    }

    /** 渠道协议是否是 NAI 原生——只有它支持 vibe 参考图 */
    fun isNaiChannel(): Boolean = activeChannel()?.protocol?.equals("OPENAI", true) != true

    fun imageSize(): ImageSize = runCatching {
        prefs.getString(KEY_SIZE, null)?.let { json.decodeFromString<ImageSize>(it) }
            ?: ImageSize(832, 1216)
    }.getOrDefault(ImageSize(832, 1216))

    /** 把提示词里的 @角色名 换成档案里的固定外貌 */
    fun expandCharacters(text: String): String {
        if (text.isBlank()) return text
        val looks = characters()
        if (looks.isEmpty()) return text
        var result = text
        looks.sortedByDescending { it.name.length }.forEach { character ->
            if (character.look.isNotBlank()) {
                result = result.replace("@${character.name}", character.look, ignoreCase = true)
            }
        }
        return result
    }

    /**
     * 与面板完全一致的拼装顺序：画师串 → 正文（含 @角色替换）→ 质量词。
     * AI 工具拿到的提示词也走这里，保证两边的画风来源相同。
     */
    fun composePrompt(base: String): String {
        val artist = activeArtist()?.value?.trim().orEmpty()
        var text = expandCharacters(base.trim())

        // [1] 调用方可能已经自己写了画师串，先做三段检测再决定要不要补
        if (artist.isNotEmpty() && !containsArtist(text, artist)) {
            text = when {
                text.isEmpty() -> artist
                else -> "$artist, $text"
            }
        }

        val nai = naiParams()
        if (!isNaiChannel() && nai.qualityTags && text.isNotBlank() && !containsQuality(text)) {
            text = "$text, best quality, very aesthetic, absurdres"
        }
        return text
    }

    /** 正文里是否已经有画师串：完全包含、带 artist: 关键词、或用了 :: 权重语法 */
    private fun containsArtist(text: String, artist: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (lower.contains(artist.lowercase())) return true
        if (lower.contains("artist:")) return true
        // 形如 1.5::artist:xxx:: 或 1.2::wlop::
        if (lower.contains("::")) return true
        return false
    }

    /** 正文里是否已经有质量词，避免再追加一遍 */
    private fun containsQuality(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("masterpiece", "best quality", "very aesthetic", "absurdres", "highest quality")
            .any { lower.contains(it) }
    }

    companion object {
        private const val DEFAULT_NEGATIVE =
            "lowres, worst quality, bad quality, jpeg artifacts, watermark, signature, text, error, " +
                "cropped, bad anatomy, bad hands, extra fingers, fused fingers, poorly drawn face"

        private const val KEY_NAI = "nai_params"
        private const val KEY_CHARACTERS = "character_looks"
        private const val KEY_ARTISTS = "artist_styles"
        private const val KEY_ACTIVE_ARTIST = "active_artist_id"
        private const val KEY_NEGATIVE = "negative_prompt"
        private const val KEY_SIZE = "image_size"
        private const val KEY_VIBE_JSONS = "vibe_jsons"
        private const val KEY_VIBE_PRESETS = "vibe_presets"
        private const val KEY_ACTIVE_VIBE = "active_vibe_id"
        private const val KEY_PROMPT_STYLE = "prompt_style"
        private const val KEY_COUNT = "default_count"
        private const val KEY_REF_IMAGES = "reference_images"
        private const val KEY_REF_STRENGTH = "reference_strength"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_ACTIVE_CHANNEL = "active_channel_id"
    }
}
