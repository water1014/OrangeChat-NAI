/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File

private const val TAG = "ImageGenTool"

/**
 * AI 主动生图工具。
 *
 * 复用「生图」页面同一套链路：设置里选定的生图模型 -> ProviderManager -> 落盘 + 入库，
 * 然后把图片作为一条 Image 消息部件回灌到对话里，模型与用户都能直接看到结果。
 *
 * 参数刻意做得克制（提示词 / 画幅 / 张数），模型厂商特有的步数、采样器等高级参数
 * 在本地 Provider 里未必存在，交给用户自己在面板里调，避免工具参数与厂商能力错配。
 */
fun createImageGenerationTool(
    context: android.content.Context,
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
): Tool = Tool(
    name = "generate_image",
    description = """
        Generate image(s) from a prompt using the image model configured in settings.
        Use it whenever the user asks you to draw / generate / create / design a picture,
        and also when the user only throws you a few keywords or a rough idea - in that case
        YOU are expected to turn them into a complete prompt, not to pass the keywords through.

        How to write the prompt depends on the configured style (see the system prompt):
        · TAG style (NAI): a Danbooru-style English tag string, comma separated, 30-60 tags.
        · NATURAL style (gpt-image and similar): one to three English sentences.
        Keep the user's subject and intent; add visual detail, never replace what they asked for.
        Leave width/height empty unless the user names a size ("竖图" -> 832x1216,
        "横图" -> 1216x832, "方形" -> 1024x1024).
        Put unwanted content into negative_prompt instead of "no xxx" in the prompt; it is merged
        with the panel's negative prompt (duplicates removed). Pass override_negative=true only
        when you deliberately want to replace it.

        The panel's saved settings are applied automatically on top of your prompt: the active artist
        style string is prepended, @CharacterName tokens are replaced with their saved appearance, the
        quality tags and negative prompt are appended, and the reference images in the panel are sent as
        vibe. So do NOT repeat the artist string or the negative prompt yourself - just write the scene.

        Returns the saved image(s) in the chat plus the final prompt you used, so you can show the
        user what was actually sent. Generation costs the user's API quota - only call it when the
        user clearly wants an image.
    """.trimIndent().replace("\n", " "),

    systemPrompt = { _, _ ->
        val store = me.rerere.rikkahub.data.imggen.ImgGenPresetStore(context)
        val setup = runCatching { store.describeCurrentSetup() }.getOrDefault("")
        val style = runCatching { store.effectivePromptStyle() }.getOrDefault("TAG")

        if (style == "TAG") {
            """
            你负责把用户的意图写成 Danbooru 风格的英文标签串，交给生图模型（NAI 系）。
            当用户一个关键词都没给（例如「随便画一张」「你挑一个」），不要反问，自己定一个具体场景直接生成；
            连续生成时换一个场景，不要重复上一张。

            输出规则：
            1. 只输出 tag，用英文逗号分隔。不要句子、不要 "a photo of"、不要解释。
            2. 顺序：人数与主体 → 角色外貌 → 服装 → 动作与表情 → 场景 → 构图与镜头 → 光线。
            3. 用 1girl / 1boy / 2girls / multiple girls 这类 tag 开头。
            4. 每个 tag 保持短（1–4 个词），总数控制在 30–60 个，太多会互相打架。
            5. 角色外貌只写视觉 tag：hair / eyes / skin / body / outfit。
               必须写到「服装」（shirt / dress / hoodie / coat 至少一个），不写服装模型会自由发挥。
            6. 用户提到的 @角色名 原样保留，不要展开——客户端会替换成档案里的外貌。
            7. 不要写画风、画师、媒介、质量词（masterpiece、best quality、artist:xxx、anime style、
               cinematic、oil painting 等）——配置里已经固定，重复写会让画风漂移。
            8. 不要写 "no xxx" 这类否定，不想要的内容放到 negative_prompt。
            9. 只写「这一张画面」，不要复述剧情、对话、心理活动或旁白；
               把剧情里的瞬间翻译成「此刻镜头里能看到的东西」。
            10. 构图 tag 只能选一个：portrait / upper body / cowboy shot / full body；
                不要三个一起写。不要写 single image、high resolution 这类平台无效 tag。

            示例：
            1girl, solo, long silver hair, violet eyes, white shirt, standing, looking at viewer,
            night street, neon lights, rain, wet ground, from behind, cinematic lighting, depth of field

            你只能看到配置（下面这段），不要去改它，也不要把它复述给用户：
            $setup

            调用结束后，把工具返回的 final_prompt 原样贴给用户，并说明这次用了哪套渠道/画师串/负向/vibe。
            """.trimIndent()
        } else {
            """
            你负责写一段具体、可画出来的英文画面描述，交给生图模型（OpenAI 兼容 / gpt-image 这类）。
            当用户一个关键词都没给（例如「随便画一张」），不要反问，自己定一个具体场景直接生成。

            规则：
            1. 一到三句，包含主体与外貌、动作、场景、光线、构图。
            2. 不要写画风、画师、媒介、质量词——配置里已经固定。
            3. 不想要的内容放 negative_prompt，不要写 "no xxx"。
            4. 用户提到的 @角色名 原样保留，客户端会替换成档案里的外貌。
            5. 同一角色连续生成时保持描述结构一致，只替换变化的部分。
            6. 只写这一张画面，不要复述剧情或对话。

            示例：
            A young woman with long silver hair stands on a rainy neon-lit street at night,
            seen from behind, cinematic lighting, shallow depth of field.

            当前配置（只读）：
            $setup

            调用结束后，把工具返回的 final_prompt 原样贴给用户，并说明这次用了哪套渠道/画师串/负向/vibe。
            """.trimIndent()
        }
    },
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "Visual description of the image to generate")
                }
                putJsonObject("aspect_ratio") {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("square")
                            add("portrait")
                            add("landscape")
                        }
                    )
                    put("description", "square = 1:1, portrait = 3:4, landscape = 4:3. Default square")
                }
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "How many images to generate, 1-4. Default 1")
                }
                putJsonObject("negative_prompt") {
                    put("type", "string")
                    put("description", "Extra things to avoid. Merged with the panel's negative prompt by default")
                }
                putJsonObject("override_negative") {
                    put("type", "boolean")
                    put("description", "true = replace the panel negative prompt instead of merging. Default false")
                }
                putJsonObject("width") {
                    put("type", "integer")
                    put("description", "Optional output width in px. NAI needs multiples of 64, total pixels <= 1.5MP")
                }
                putJsonObject("height") {
                    put("type", "integer")
                    put("description", "Optional output height in px (same rules as width)")
                }
            },
            required = listOf("prompt")
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "prompt is required")
                    }.toString()
                )
            )
        }
        val countOverride = obj["count"]?.jsonPrimitive?.intOrNull
        // 和生图面板共用同一套设置：画师串、@角色名替换、质量词、负向词、尺寸、NAI 参数
        val presetStore = me.rerere.rikkahub.data.imggen.ImgGenPresetStore(context)
        val composedPrompt = presetStore.composePrompt(prompt)
        val panelNegative = presetStore.negativePrompt().trim()
        val argNegative = obj["negative_prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val overrideNegative = obj["override_negative"]?.jsonPrimitive?.booleanOrNull ?: false
        // [5] 默认是「面板负向 + 工具追加」，去重合并；只有 override_negative=true 才整段替换
        val negative = when {
            overrideNegative -> argNegative
            argNegative.isBlank() -> panelNegative
            panelNegative.isBlank() -> argNegative
            else -> mergeNegative(panelNegative, argNegative)
        }
        val presetSize = presetStore.imageSize()
        val count = (countOverride ?: presetStore.defaultCount()).coerceIn(1, 4)
        val nai = presetStore.naiParams()
        // 面板「参考」页里加的图，这里作为 vibe 一起发；NAI 原型走 encode-vibe
        val referenceImages = presetStore.referenceImages()
        val referenceStrength = presetStore.referenceStrength()
        val cachedVibes = presetStore.vibeJsons().filter { it.enabled }.map {
            me.rerere.ai.provider.CachedVibe(it.encoded, it.strength, it.model)
        }
        // [6] 工具参数 → 设置默认 → 832×1216，任一为空都往下落
        val width = obj["width"]?.jsonPrimitive?.intOrNull?.let { snap(it) }
            ?: presetSize.width.takeIf { it > 0 }
            ?: 832
        val height = obj["height"]?.jsonPrimitive?.intOrNull?.let { snap(it) }
            ?: presetSize.height.takeIf { it > 0 }
            ?: 1216

        // [P0] aspect_ratio 由实际宽高推出来，不再恒为 square
        val ratio = when {
            width > height -> ImageAspectRatio.LANDSCAPE
            width < height -> ImageAspectRatio.PORTRAIT
            else -> ImageAspectRatio.SQUARE
        }
        val finalPrompt = composedPrompt

        try {
            val settings = settingsStore.settingsFlow.first()
            // 渠道优先：面板里选中的渠道，AI 调用也用同一套协议/地址/Key/模型
            val channel = presetStore.activeChannel()
            val providerSetting: me.rerere.ai.provider.ProviderSetting
            val model: me.rerere.ai.provider.Model
            if (channel != null && channel.baseUrl.isNotBlank() && channel.modelId.isNotBlank()) {
                providerSetting = me.rerere.ai.provider.ProviderSetting.OpenAI(
                    name = channel.name,
                    baseUrl = channel.baseUrl,
                    apiKey = channel.apiKey,
                    imageProtocol = if (channel.protocol == "OPENAI") {
                        me.rerere.ai.provider.ImageProtocol.OPENAI
                    } else {
                        me.rerere.ai.provider.ImageProtocol.NOVELAI
                    },
                )
                model = me.rerere.ai.provider.Model(
                    modelId = channel.modelId,
                    displayName = channel.modelId,
                    type = me.rerere.ai.provider.ModelType.IMAGE,
                )
            } else {
                model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("没有配置生图模型：在生图页新建渠道，或到设置里选图片模型")
                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("生图模型所属的 Provider 不存在")
                providerSetting = settings.providers.find { it.id == provider.id }
                    ?: throw IllegalStateException("Provider 配置缺失")
            }

            val params = ImageGenerationParams(
                model = model,
                prompt = finalPrompt,
                numOfImages = count,
                aspectRatio = ratio,
                width = width,
                height = height,
                negativePrompt = negative.ifBlank { null },
                referenceImages = referenceImages,
                referenceStrength = referenceStrength,
                cachedVibes = cachedVibes,
                naiParameters = if (nai.enabled) nai.toJsonObject() else null,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            )

            val provider = providerManager.getProviderByType(providerSetting)
            val isNaiProtocol =
                (providerSetting as? me.rerere.ai.provider.ProviderSetting.OpenAI)?.imageProtocol ==
                    me.rerere.ai.provider.ImageProtocol.NOVELAI

            // NAI：参考图作为 vibe 随文生图一起发；其它协议：参考图走图生图（编辑）
            val result = if (referenceImages.isNotEmpty() && !isNaiProtocol) {
                val editPrompt = if (negative.isBlank()) {
                    finalPrompt
                } else {
                    "$finalPrompt\n\nNegative prompt: $negative"
                }
                provider.editImage(
                    providerSetting,
                    me.rerere.ai.provider.ImageEditParams(
                        model = model,
                        prompt = editPrompt,
                        images = referenceImages,
                        numOfImages = count,
                        aspectRatio = ratio,
                        width = width,
                        height = height,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    )
                )
            } else {
                provider.generateImage(providerSetting, params)
            }

            val imagesDir = filesManager.getImagesDir()
            val timestamp = System.currentTimeMillis()
            val imageParts = mutableListOf<UIMessagePart>()
            val paths = mutableListOf<String>()

            result.items.forEachIndexed { index, item ->
                val file = File(imagesDir, "${timestamp}_${model.displayName}_$index.png")
                filesManager.createImageFileFromBase64(item.data, file.absolutePath)
                if (!file.exists() || file.length() == 0L) {
                    error("图片写入失败：${file.absolutePath}")
                }
                genMediaRepository.insertMedia(
                    GenMediaEntity(
                        path = "images/${file.name}",
                        modelId = model.displayName,
                        prompt = finalPrompt,
                        createAt = timestamp,
                        type = GenMediaEntity.TYPE_IMAGE_GENERATION,
                    )
                )
                paths.add(file.absolutePath)
                imageParts.add(UIMessagePart.Image(url = "file://${file.absolutePath}"))
            }

            if (paths.isEmpty()) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put("error", "模型没有返回任何图片")
                        }.toString()
                    )
                )
            }

            listOf<UIMessagePart>(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("count", paths.size)
                        put("model", model.displayName)
                        put("prompt", finalPrompt)
                        put("aspect_ratio", ratio.name.lowercase())
                        put("size", "${width}×${height}")
                        put("vibe_references", referenceImages.size)
                        put("seeds", buildJsonArray { result.items.forEach { item -> item.seed?.let { add(it) } } })
                        put("final_prompt", finalPrompt)
                        put("applied", buildJsonObject {
                            put("channel", presetStore.activeChannel()?.name ?: "设置里的默认图片模型")
                            put("artist", presetStore.activeArtist()?.name ?: "未使用")
                            put("artist_value", presetStore.activeArtist()?.value.orEmpty())
                            put("negative_from", when {
                                argNegative.isNotBlank() -> "工具参数"
                                presetStore.negativePrompt().isNotBlank() -> "设置里的负面预设词"
                                else -> "无"
                            })
                            put("size_from", if (obj["width"] != null || obj["height"] != null) "工具参数" else "设置里的默认尺寸")
                            put("vibe", referenceImages.size + cachedVibes.size)
                            put("vibe_images", referenceImages.size)
                            put("vibe_json", cachedVibes.size)
                            put("vibe_encoded", result.vibeEncoded)
                            put("vibe_failed", result.vibeFailed)
                            put("negative", negative)
                            put("quality_tags", nai.enabled && nai.qualityTags)
                        })
                        put("files", buildJsonArray { paths.forEach { add(it) } })
                        put("hint", "图片已保存到相册的「橘瓣生成」目录，也在「生图 → 图库」里")
                    }.toString()
                )
            ) + imageParts
        } catch (e: Exception) {
            Log.e(TAG, "generate_image failed", e)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", e.message ?: "生成失败")
                    }.toString()
                )
            )
        }
    }
)

/** NAI 只收 64 的倍数，模型给的尺寸直接对齐，省得 400 之后再试一遍 */
private fun snap(value: Int): Int {
    val clamped = value.coerceIn(64, 2048)
    return ((clamped / 64f).toInt() * 64).coerceIn(64, 2048)
}

/** 面板负向 + 工具追加，按逗号去重（忽略大小写），保留原顺序 */
private fun mergeNegative(panel: String, extra: String): String {
    val seen = LinkedHashSet<String>()
    (panel.split(",") + extra.split(",")).forEach { raw ->
        val word = raw.trim()
        if (word.isNotEmpty()) seen.add(word)
    }
    return seen.joinToString(", ")
}
