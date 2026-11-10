/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.imggen

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.imggen.ArtistStyle
import me.rerere.rikkahub.data.imggen.CharacterLook
import me.rerere.rikkahub.data.imggen.ImageSize
import me.rerere.rikkahub.data.imggen.ImgChannel
import me.rerere.rikkahub.data.imggen.ImgGenPresetStore
import me.rerere.rikkahub.data.imggen.NaiParams
import me.rerere.rikkahub.data.imggen.PromptPreset
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File
import kotlin.uuid.Uuid
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)


private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val imagesDir = filesManager.getImagesDir()
    val fullPath = File(imagesDir, this.path.removePrefix("images/")).absolutePath

    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId
    )
}

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    /** 负向词：部分中转接口认 Negative prompt 段落，统一拼在正向后面 */
    private val presetStore = ImgGenPresetStore(context)

    private val _channels = MutableStateFlow(presetStore.channels())
    val channels: StateFlow<List<ImgChannel>> = _channels

    private val _activeChannelId = MutableStateFlow(presetStore.activeChannelId())
    val activeChannelId: StateFlow<String?> = _activeChannelId

    val activeChannel: ImgChannel?
        get() = _channels.value.firstOrNull { it.id == _activeChannelId.value }

    private val _negativePrompt = MutableStateFlow(presetStore.negativePrompt())
    val negativePrompt: StateFlow<String> = _negativePrompt

    private val prefs = context.getSharedPreferences("imggen_library", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _presets = MutableStateFlow(loadPresets())
    val presets: StateFlow<List<PromptPreset>> = _presets

    private val _characters = MutableStateFlow(loadCharacters())
    val characters: StateFlow<List<CharacterLook>> = _characters

    private val _artists = MutableStateFlow(loadArtists())
    val artists: StateFlow<List<ArtistStyle>> = _artists

    private val _naiParams = MutableStateFlow(loadNaiParams())
    val naiParams: StateFlow<NaiParams> = _naiParams

    private val _lastSeed = MutableStateFlow<Long?>(null)
    val lastSeed: StateFlow<Long?> = _lastSeed

    private val _referenceStrength = MutableStateFlow(prefs.getFloat(KEY_REF_STRENGTH, 0.6f))
    val referenceStrength: StateFlow<Float> = _referenceStrength

    fun updateReferenceStrength(value: Float) {
        _referenceStrength.value = value.coerceIn(0.1f, 1f)
        prefs.edit().putFloat(KEY_REF_STRENGTH, _referenceStrength.value).apply()
    }

    /** 当前选中的画师串，null 表示不使用 */
    private val _activeArtistId = MutableStateFlow(prefs.getString(KEY_ACTIVE_ARTIST, null))
    val activeArtistId: StateFlow<String?> = _activeArtistId

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _aspectRatio = MutableStateFlow(ImageAspectRatio.SQUARE)
    val aspectRatio: StateFlow<ImageAspectRatio> = _aspectRatio

    private val _imageSize = MutableStateFlow(presetStore.imageSize())
    val imageSize: StateFlow<ImageSize> = _imageSize

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = _currentGeneratedImages

    private val _referenceImages = MutableStateFlow(presetStore.referenceImages())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() }
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNegativePrompt(text: String) {
        _negativePrompt.value = text
        prefs.edit().putString("negative_prompt", text).apply()
    }

    /** 正向 + 负向拼成最终送给接口的提示词 */
    private fun effectivePrompt(): String {
        val artist = _artists.value
            .firstOrNull { it.id == _activeArtistId.value }
            ?.value
            ?.trim()
            .orEmpty()
        val baseText = expandCharacterTokens(_prompt.value.trim())
        // 画师串放最前，给整幅画定基调
        var base = if (artist.isEmpty()) baseText else "$artist, $baseText"
        val nai = _naiParams.value
        // NAI 的质量词由本地补，避免中转不认识 qualityToggle 时提示词里什么都没有
        if (nai.enabled && nai.qualityTags && base.isNotBlank()) {
            base = "$base, best quality, very aesthetic, absurdres"
        }
        val neg = expandCharacterTokens(_negativePrompt.value.trim())
        // 开了 NAI 参数时负向走 negative_prompt 字段，不再拼进正文，免得重复
        return if (neg.isEmpty() || nai.enabled) base else "$base\n\nNegative prompt: $neg"
    }

    // ------------------------------ 渠道 ------------------------------

    fun saveChannel(channel: ImgChannel) {
        val exists = _channels.value.any { it.id == channel.id }
        _channels.value = if (exists) {
            _channels.value.map { if (it.id == channel.id) channel else it }
        } else {
            _channels.value + channel
        }
        if (_activeChannelId.value == null) {
            _activeChannelId.value = channel.id
            prefs.edit().putString(KEY_ACTIVE_CHANNEL, channel.id).apply()
        }
        persistChannels()
    }

    fun deleteChannel(id: String) {
        _channels.value = _channels.value.filterNot { it.id == id }
        if (_activeChannelId.value == id) setActiveChannel(null)
        persistChannels()
    }

    fun setActiveChannel(id: String?) {
        _activeChannelId.value = id
        prefs.edit().putString(KEY_ACTIVE_CHANNEL, id).apply()
    }

    private fun persistChannels() {
        prefs.edit().putString(KEY_CHANNELS, json.encodeToString(_channels.value)).apply()
    }

    /**
     * 渠道优先：选了渠道就用渠道自带的协议/地址/Key/模型，
     * 没选渠道才回落到「设置 → 生图」里那个全局图片模型。
     */
    private fun resolveImageTarget(settings: me.rerere.rikkahub.data.datastore.Settings): Pair<me.rerere.ai.provider.ProviderSetting, me.rerere.ai.provider.Model> {
        val channel = activeChannel
        if (channel != null && channel.baseUrl.isNotBlank() && channel.modelId.isNotBlank()) {
            val setting = me.rerere.ai.provider.ProviderSetting.OpenAI(
                name = channel.name,
                baseUrl = channel.baseUrl,
                apiKey = channel.apiKey,
                imageProtocol = if (channel.protocol == "OPENAI") {
                    me.rerere.ai.provider.ImageProtocol.OPENAI
                } else {
                    me.rerere.ai.provider.ImageProtocol.NOVELAI
                },
            )
            val model = me.rerere.ai.provider.Model(
                modelId = channel.modelId,
                displayName = channel.modelId,
                type = me.rerere.ai.provider.ModelType.IMAGE,
            )
            return setting to model
        }
        val model = settings.findModelById(settings.imageGenerationModelId)
            ?: throw IllegalStateException("没有配置生图模型：在生图页新建一个渠道，或到设置里选图片模型")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("生图模型所属的 Provider 不存在")
        val providerSetting = settings.providers.find { it.id == provider.id }
            ?: throw IllegalStateException("Provider 配置缺失")
        return providerSetting to model
    }

    // ------------------------------ NAI 参数 ------------------------------

    /** 参考页直接导入 vibe JSON */
    fun importVibeJson(item: me.rerere.rikkahub.data.imggen.VibeJson) {
        presetStore.saveVibeJson(item)
    }

    /** 设置页导入的 vibe 文件数量，参考页用来提示 */
    fun vibeJsonCount(): Int = presetStore.vibeJsons().count { it.enabled }

    /** 用某张图的参数重来一次：正文、尺寸、种子 */
    fun reuseImageMeta(body: String, seed: Long?, width: Int, height: Int) {
        if (body.isNotBlank()) _prompt.value = body
        updateSize(width, height)
        if (seed != null && seed >= 0) {
            updateNaiParams { it.copy(enabled = true, seed = seed) }
        }
    }

    /** 锁定上一张的种子：出到满意的图后固定 seed 继续微调 */
    fun lockLastSeed(): Long? {
        val seed = _lastSeed.value ?: return null
        updateNaiParams { it.copy(enabled = true, seed = seed) }
        return seed
    }

    fun updateNaiParams(transform: (NaiParams) -> NaiParams) {
        val next = transform(_naiParams.value)
        _naiParams.value = next
        prefs.edit().putString(KEY_NAI_PARAMS, json.encodeToString(next)).apply()
    }

    private fun loadNaiParams(): NaiParams = runCatching {
        prefs.getString(KEY_NAI_PARAMS, null)
            ?.let { json.decodeFromString<NaiParams>(it) }
            ?: NaiParams()
    }.getOrDefault(NaiParams())

    /** NAI 参数（官方字段名），未启用时返回 null */
    private fun naiParametersJson(): kotlinx.serialization.json.JsonObject? {
        val nai = _naiParams.value
        if (!nai.enabled) return null
        return nai.toJsonObject()
    }

    @Suppress("unused")
    private fun legacyNaiJson(): kotlinx.serialization.json.JsonObject? {
        val nai = _naiParams.value
        if (!nai.enabled) return null
        val sampler = when (nai.sampler) {
            "Euler Ancestral" -> "k_euler_ancestral"
            "Euler" -> "k_euler"
            "DPM++ 2S Ancestral" -> "k_dpmpp_2s_ancestral"
            "DPM++ 2M SDE" -> "k_dpmpp_2m_sde"
            "DPM++ 2M" -> "k_dpmpp_2m"
            "DPM++ SDE" -> "k_dpmpp_sde"
            "DDIM" -> "ddim_v3"
            else -> "k_euler_ancestral"
        }
        val noise = when (nai.noiseSchedule) {
            "Native" -> "native"
            "Exponential" -> "exponential"
            "Polyexponential" -> "polyexponential"
            else -> "karras"
        }
        return kotlinx.serialization.json.buildJsonObject {
            put("steps", nai.steps)
            put("scale", nai.scale)
            put("sampler", sampler)
            put("noise_schedule", noise)
            put("seed", nai.seed)
            put("ucPreset", nai.ucPreset)
            put("cfg_rescale", nai.cfgRescale)
            put("uc_strength", nai.ucStrength)
            put("sm", nai.smea)
            put("sm_dyn", nai.smeaDyn)
            put("qualityToggle", nai.qualityTags)
            if (nai.varietyBoost) put("skip_cfg_above_sigma", 19) else put("skip_cfg_above_sigma", kotlinx.serialization.json.JsonNull)
            put("dynamic_thresholding", nai.dynamicThresholding)
            put("use_coords", nai.useCoords)
            put("normalize_reference_strength_multiple", nai.normalizeReferenceStrength)
            put("prefer_brownian", nai.preferBrownian)
        }
    }

    /** OpenAI 兼容协议下，把同样的参数摊成 customBody，中转认哪个用哪个 */
    private fun naiCustomBodies(): List<me.rerere.ai.provider.CustomBody> {
        val json = naiParametersJson() ?: return emptyList()
        val bodies = json.map { (key, value) -> me.rerere.ai.provider.CustomBody(key, value) }.toMutableList()
        val neg = _negativePrompt.value.trim()
        if (neg.isNotEmpty()) {
            bodies.add(me.rerere.ai.provider.CustomBody("negative_prompt", kotlinx.serialization.json.JsonPrimitive(neg)))
        }
        return bodies
    }

    // ------------------------------ 画师串 ------------------------------

    fun saveArtist(name: String, value: String) {
        if (value.isBlank()) return
        val item = ArtistStyle(
            id = Uuid.random().toString(),
            name = name.ifBlank { "预设 ${_artists.value.size + 1}" },
            value = value,
        )
        _artists.value = _artists.value + item
        persistArtists()
    }

    fun deleteArtist(id: String) {
        _artists.value = _artists.value.filterNot { it.id == id }
        if (_activeArtistId.value == id) setActiveArtist(null)
        persistArtists()
    }

    fun setActiveArtist(id: String?) {
        _activeArtistId.value = id
        prefs.edit().putString(KEY_ACTIVE_ARTIST, id).apply()
    }

    private fun persistArtists() {
        prefs.edit().putString(KEY_ARTISTS, json.encodeToString(_artists.value)).apply()
    }

    private fun loadArtists(): List<ArtistStyle> = runCatching {
        prefs.getString(KEY_ARTISTS, null)
            ?.let { json.decodeFromString<List<ArtistStyle>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * 把提示词里的 @角色名 替换成角色库里的固定外貌。
     * 参照柏宝绘的做法：正文只写名字，外貌集中维护，避免一张一个样。
     */
    private fun expandCharacterTokens(text: String): String {
        if (text.isBlank() || _characters.value.isEmpty()) return text
        var result = text
        _characters.value
            .sortedByDescending { it.name.length }
            .forEach { character ->
                if (character.look.isBlank()) return@forEach
                result = result.replace("@${character.name}", character.look, ignoreCase = true)
            }
        return result
    }

    // ------------------------------ 提示词预设 ------------------------------

    fun savePreset(name: String, positive: String, negative: String) {
        if (positive.isBlank()) return
        val preset = PromptPreset(
            id = Uuid.random().toString(),
            name = name.ifBlank { positive.take(12) },
            positive = positive,
            negative = negative,
        )
        _presets.value = _presets.value + preset
        persistPresets()
    }

    fun deletePreset(id: String) {
        _presets.value = _presets.value.filterNot { it.id == id }
        persistPresets()
    }

    /** 套用预设：正向负向都填进去，并记一次使用 */
    fun applyPreset(preset: PromptPreset) {
        _prompt.value = preset.positive
        _negativePrompt.value = preset.negative
        bumpPreset(preset.id, 1)
    }

    fun bumpPreset(id: String, delta: Int) {
        _presets.value = _presets.value.map {
            if (it.id == id) it.copy(uses = (it.uses + delta).coerceAtLeast(0)) else it
        }
        persistPresets()
    }

    private fun persistPresets() {
        prefs.edit().putString(KEY_PRESETS, json.encodeToString(_presets.value)).apply()
    }

    private fun loadPresets(): List<PromptPreset> = runCatching {
        prefs.getString(KEY_PRESETS, null)
            ?.let { json.decodeFromString<List<PromptPreset>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    // ------------------------------ 角色外貌 ------------------------------

    fun saveCharacter(name: String, look: String) {
        if (name.isBlank() || look.isBlank()) return
        val existing = _characters.value.firstOrNull { it.name == name }
        _characters.value = if (existing != null) {
            _characters.value.map { if (it.id == existing.id) it.copy(look = look) else it }
        } else {
            _characters.value + CharacterLook(
                id = Uuid.random().toString(),
                name = name,
                look = look,
            )
        }
        persistCharacters()
    }

    fun deleteCharacter(id: String) {
        _characters.value = _characters.value.filterNot { it.id == id }
        persistCharacters()
    }

    /** 把角色外貌拼到提示词后面 */
    fun applyCharacter(character: CharacterLook) {
        val current = _prompt.value.trim()
        _prompt.value = if (current.isEmpty()) character.look else "$current, ${character.look}"
    }

    private fun persistCharacters() {
        prefs.edit().putString(KEY_CHARACTERS, json.encodeToString(_characters.value)).apply()
    }

    private fun loadCharacters(): List<CharacterLook> = runCatching {
        prefs.getString(KEY_CHARACTERS, null)
            ?.let { json.decodeFromString<List<CharacterLook>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateAspectRatio(aspectRatio: ImageAspectRatio) {
        _aspectRatio.value = aspectRatio
        _imageSize.value = when (aspectRatio) {
            ImageAspectRatio.SQUARE -> ImageSize(1024, 1024)
            ImageAspectRatio.LANDSCAPE -> ImageSize(1216, 832)
            ImageAspectRatio.PORTRAIT -> ImageSize(832, 1216)
        }
    }

    /** 显式设置宽高：夹到 64 的倍数，避免 NAI 直接 400 */
    fun updateSize(width: Int, height: Int) {
        val w = snapSize(width)
        val h = snapSize(height)
        _imageSize.value = ImageSize(w, h)
        prefs.edit().putString("image_size", json.encodeToString(ImageSize(w, h))).apply()
        _aspectRatio.value = when {
            w > h -> ImageAspectRatio.LANDSCAPE
            w < h -> ImageAspectRatio.PORTRAIT
            else -> ImageAspectRatio.SQUARE
        }
    }

    private fun snapSize(value: Int): Int {
        val clamped = value.coerceIn(64, 2048)
        val snapped = (clamped / 64f).toInt() * 64
        return snapped.coerceIn(64, 2048)
    }

    fun addReferenceImages(paths: List<String>) {
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
        // 持久化：AI 工具那条路要读到同一批参考图（vibe）
        persistReferenceImages()
    }

    fun removeReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        persistReferenceImages()
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
        persistReferenceImages()
    }

    private fun persistReferenceImages() {
        prefs.edit()
            .putString("reference_images", json.encodeToString(_referenceImages.value))
            .apply()
    }

    /** 从设置页返回时重读一遍，避免面板还拿着旧配置 */
    fun reloadFromStore() {
        _channels.value = presetStore.channels()
        _activeChannelId.value = presetStore.activeChannelId()
        _characters.value = presetStore.characters()
        _artists.value = presetStore.artists()
        _activeArtistId.value = presetStore.activeArtist()?.id
        _naiParams.value = presetStore.naiParams()
        _negativePrompt.value = presetStore.negativePrompt()
        _imageSize.value = presetStore.imageSize()
        _referenceImages.value = presetStore.referenceImages()
        _referenceStrength.value = presetStore.referenceStrength()
        _numberOfImages.value = presetStore.defaultCount()
    }

    fun clearError() {
        _error.value = null
    }

    fun startNewSession() {
        cancelJob?.cancel()
        clearReferenceImages()
        _prompt.value = ""
        _currentGeneratedImages.value = emptyList()
        _error.value = null
        _isGenerating.value = false
    }

    fun generateImage() {
        if(prompt.value.isBlank()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                // 临时目录里的旧图可能已被系统清掉，先剔掉失效的，避免静默少带 vibe
                val validRefs = _referenceImages.value.filter {
                    me.rerere.rikkahub.data.imggen.VibeFiles.isValid(it)
                }
                if (validRefs.size < _referenceImages.value.size) {
                    val dropped = _referenceImages.value.size - validRefs.size
                    _referenceImages.value = validRefs
                    persistReferenceImages()
                    _error.value = "有 $dropped 张参考图文件已失效，已自动移除"
                }

                val settings = settingsStore.settingsFlow.first()
                val (providerSetting, model) = resolveImageTarget(settings)

                val params = ImageGenerationParams(
                    model = model,
                    prompt = effectivePrompt(),
                    numOfImages = _numberOfImages.value,
                    aspectRatio = _aspectRatio.value,
                    width = _imageSize.value.width,
                    height = _imageSize.value.height,
                    negativePrompt = _negativePrompt.value.trim().ifBlank { null },
                    referenceImages = _referenceImages.value,
                    referenceStrength = _referenceStrength.value,
                    cachedVibes = presetStore.vibeJsons().filter { it.enabled }.map {
                        me.rerere.ai.provider.CachedVibe(it.encoded, it.strength, it.model)
                    },
                    naiParameters = naiParametersJson(),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies + naiCustomBodies()
                )

                val result = providerManager.getProviderByType(providerSetting)
                    .generateImage(providerSetting, params)

                _lastSeed.value = result.items.firstOrNull()?.seed

                val newImages = mutableListOf<GeneratedImage>()

                result.items.forEachIndexed { index, item ->
                    val imageFile = saveImageToStorage(
                        item = item,
                        prompt = _prompt.value,
                        modelName = model.displayName,
                        index = index
                    )
                    val generatedImage = GeneratedImage(
                        id = 0, // Will be updated after database insertion
                        prompt = _prompt.value,
                        filePath = imageFile.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        model = model.displayName
                    )
                    newImages.add(generatedImage)
                }

                _currentGeneratedImages.value = newImages
            } catch (e: Exception) {
                if(e is CancellationException) return@launch
                Log.e(TAG, "Failed to generate image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun editImage() {
        if (prompt.value.isBlank() || referenceImages.value.isEmpty()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val providerSetting = settings.providers.find { it.id == provider.id }
                    ?: throw IllegalStateException("Provider setting not found")

                val sourceImages = _referenceImages.value
                val params = ImageEditParams(
                    model = model,
                    prompt = effectivePrompt(),
                    images = sourceImages,
                    numOfImages = _numberOfImages.value,
                    aspectRatio = _aspectRatio.value,
                    width = _imageSize.value.width,
                    height = _imageSize.value.height,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val result = providerManager.getProviderByType(provider)
                    .editImage(providerSetting, params)

                val newImages = mutableListOf<GeneratedImage>()

                result.items.forEachIndexed { index, item ->
                    val imageFile = saveImageToStorage(
                        item = item,
                        prompt = _prompt.value,
                        modelName = model.displayName,
                        index = index,
                        type = GenMediaEntity.TYPE_IMAGE_EDIT,
                    )
                    val generatedImage = GeneratedImage(
                        id = 0, // Will be updated after database insertion
                        prompt = _prompt.value,
                        filePath = imageFile.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        model = model.displayName
                    )
                    newImages.add(generatedImage)
                }

                _currentGeneratedImages.value = newImages
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to edit image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun saveImageToStorage(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        index: Int,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): File {
        val imagesDir = filesManager.getImagesDir()

        val timestamp = System.currentTimeMillis()
        val filename = "${timestamp}_${modelName}_$index.png"
        val imageFile = File(imagesDir, filename)

        val createdFile = filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
        if (!createdFile.exists() || createdFile.length() == 0L) {
            throw IllegalStateException("图片写入失败：${createdFile.absolutePath}")
        }

        // Save to database with relative path
        val relativePath = "images/${imageFile.name}"
        val entity = GenMediaEntity(
            path = relativePath,
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = type,
            sourcePaths = sourcePaths,
        )
        genMediaRepository.insertMedia(entity)

        // 出图参数随图存一份 sidecar，图库里可以回溯 seed / 尺寸 / 采样器并一键复现
        runCatching {
            val meta = kotlinx.serialization.json.buildJsonObject {
                put("body", _prompt.value)
                put("full_prompt", prompt)
                put("model", modelName)
                put("seed", item.seed ?: -1L)
                put("width", _imageSize.value.width)
                put("height", _imageSize.value.height)
                put("steps", _naiParams.value.steps)
                put("scale", _naiParams.value.scale)
                put("sampler", _naiParams.value.sampler)
                put("noise_schedule", _naiParams.value.noiseSchedule)
                put("artist", presetStore.activeArtist()?.name ?: "")
                put("vibe", _referenceImages.value.size)
                put("created_at", timestamp)
            }
            File(imagesDir, "${imageFile.name}.json").writeText(meta.toString())
        }

        return createdFile
    }

    fun deleteImage(image: GeneratedImage) {
        viewModelScope.launch {
            try {
                // Delete from database first
                genMediaRepository.deleteMedia(image.id)

                // Then delete the file
                val file = File(image.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image", e)
                _error.value = "Failed to delete image"
            }
        }
    }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            paths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
        private const val KEY_PRESETS = "prompt_presets"
        private const val KEY_CHARACTERS = "character_looks"
        private const val KEY_ARTISTS = "artist_styles"
        private const val KEY_ACTIVE_ARTIST = "active_artist_id"
        private const val KEY_NAI_PARAMS = "nai_params"
        private const val KEY_REF_STRENGTH = "reference_strength"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_ACTIVE_CHANNEL = "active_channel_id"
    }
}
