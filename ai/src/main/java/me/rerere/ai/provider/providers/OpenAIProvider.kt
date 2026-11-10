/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        if (providerSetting.imageProtocol == me.rerere.ai.provider.ImageProtocol.NOVELAI) {
            return@withContext generateNaiImage(providerSetting, params, key, client)
        }

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                put("size", params.resolveImageSize())
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate image: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val items = parseImageResponse(bodyStr, client)

        ImageGenerationResult(items = items)
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
            .addFormDataPart("size", params.resolveImageSize())

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to edit image: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val items = parseImageResponse(bodyStr, client)

        ImageGenerationResult(items = items)
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}

/**
 * 请求体里的 size 字段：显式宽高优先，否则按画幅给 GPT-image 的默认值。
 * NAI 这类要求 64 倍数的接口，由调用方传 width/height。
 */
private fun ImageGenerationParams.resolveImageSize(): String =
    if (width != null && height != null) "${width}x${height}"
    else when (aspectRatio) {
        ImageAspectRatio.SQUARE -> "1024x1024"
        ImageAspectRatio.LANDSCAPE -> "1536x1024"
        ImageAspectRatio.PORTRAIT -> "1024x1536"
    }

private fun ImageEditParams.resolveImageSize(): String =
    if (width != null && height != null) "${width}x${height}"
    else when (aspectRatio) {
        ImageAspectRatio.SQUARE -> "1024x1024"
        ImageAspectRatio.LANDSCAPE -> "1536x1024"
        ImageAspectRatio.PORTRAIT -> "1024x1536"
    }


/**
 * 生图中转的返回格式并不统一：
 * · OpenAI：{"data":[{"b64_json": "..."}]}
 * · 部分中转：{"data":[{"url": "https://..."}]}
 * · NAI 系中转：{"images":["<base64>"]} 或 {"output":["https://..."]}
 * 这里统一收口，拿到 base64 或 url 都能转成 ImageGenerationItem。
 */
private suspend fun parseImageResponse(raw: String, client: OkHttpClient): List<ImageGenerationItem> {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
        error("生图接口返回的不是 JSON：${raw.take(300)}")
    }

    val items = mutableListOf<ImageGenerationItem>()
    val buckets = listOf("data", "images", "output", "artifacts", "result")
    buckets.forEach { key ->
        root[key]?.let { collectImageNodes(it, client, items) }
    }

    if (items.isEmpty()) {
        error("返回里没有图片数据：${raw.take(300)}")
    }
    return items
}

private suspend fun collectImageNodes(
    element: kotlinx.serialization.json.JsonElement,
    client: OkHttpClient,
    out: MutableList<ImageGenerationItem>,
) {
    when (element) {
        is kotlinx.serialization.json.JsonArray -> element.forEach { collectImageNodes(it, client, out) }
        is kotlinx.serialization.json.JsonObject -> {
            val b64 = element["b64_json"]?.jsonPrimitive?.contentOrNull
                ?: element["base64"]?.jsonPrimitive?.contentOrNull
                ?: element["image"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length > 256 }
            val url = element["url"]?.jsonPrimitive?.contentOrNull
                ?: element["image_url"]?.jsonPrimitive?.contentOrNull
            when {
                !b64.isNullOrBlank() -> out += ImageGenerationItem(b64, "image/png")
                !url.isNullOrBlank() -> downloadImageAsItem(url, client)?.let { out += it }
            }
        }
        is JsonPrimitive -> {
            val value = element.contentOrNull ?: return
            when {
                value.startsWith("http") -> downloadImageAsItem(value, client)?.let { out += it }
                value.length > 256 -> out += ImageGenerationItem(value, "image/png")
            }
        }
        else -> Unit
    }
}

private suspend fun downloadImageAsItem(url: String, client: OkHttpClient): ImageGenerationItem? = runCatching {
    val request = Request.Builder().url(url).get().build()
    val response = client.newCall(request).await()
    if (!response.isSuccessful) return@runCatching null
    val bytes = response.body?.bytes() ?: return@runCatching null
    ImageGenerationItem(
        data = java.util.Base64.getEncoder().encodeToString(bytes),
        mimeType = response.header("Content-Type")?.substringBefore(";") ?: "image/png",
    )
}.getOrNull()


/* ------------------------------- NovelAI 原生协议 ------------------------------- */

/**
 * 柏宝绘那套做法：POST {base}/ai/generate-image，body 是
 * { input, model, action:"generate", parameters:{...} }，返回 zip（stream=msgpack），
 * 解压取第一张图。参考图在 NAI4+ 需先经 /ai/encode-vibe 编码成 cached vibe。
 */
private suspend fun OpenAIProvider.generateNaiImage(
    providerSetting: ProviderSetting.OpenAI,
    params: ImageGenerationParams,
    key: String,
    httpClient: OkHttpClient,
): ImageGenerationResult = withContext(Dispatchers.IO) {
    val base = providerSetting.baseUrl.trimEnd('/')
    val width = params.width ?: 832
    val height = params.height ?: 1216
    val negative = params.negativePrompt.orEmpty()
    val isV3 = params.model.modelId.contains("diffusion-3")

    // vibe 只编码一次，多张共用同一批缓存
    val vibeData = mutableListOf<String>()
    val vibeStrengths = mutableListOf<Float>()
    var vibeEncoded = 0
    var vibeFailed = 0
    params.referenceImages.forEach { path ->
        val encoded = encodeVibe(base, key, params.model.modelId, path, params.referenceStrength, httpClient)
        if (encoded != null) {
            vibeData.add(encoded)
            vibeStrengths.add(params.referenceStrength)
            vibeEncoded++
        } else {
            vibeFailed++
            android.util.Log.w("OpenAIProvider", "vibe 编码失败：$path")
        }
    }
    // 用户导入的 .naiv4vibe / .json：encoded 已是缓存数据，NAI4+ 直接塞进 *_cached，省一次 encode 请求
    if (!isV3) {
        params.cachedVibes.forEach { vibe ->
            if (vibe.encoded.isNotBlank()) {
                vibeData.add(vibe.encoded)
                vibeStrengths.add(vibe.strength.coerceIn(0f, 1f))
            }
        }
    }

    // 官方上限 16 个 vibe，超出直接截断，免得整单 400
    if (vibeData.size > 16) {
        val keptData = vibeData.take(16).toList()
        val keptStrength = vibeStrengths.take(16).toList()
        vibeData.clear(); vibeData.addAll(keptData)
        vibeStrengths.clear(); vibeStrengths.addAll(keptStrength)
    }

    val isNai5 = params.model.modelId.contains("nai-diffusion-5")
    val baseParameters = buildJsonObject {
        // NAI 官方对 v4 系要求 params_version=3、v5 要求 4；缺这个字段严格网关直接 400
        put("params_version", if (isNai5) 4 else 3)
        put("width", width)
        put("height", height)
        put("scale", 5f)
        put("sampler", "k_euler_ancestral")
        put("steps", 28)
        // 一次请求只出一张，多张在下面按 seed 分发，避免 n_samples>1 却只取回一张
        put("n_samples", 1)
        // 负面词由本地配方给全，这里用 None，避免和 Heavy 预设叠加
        put("ucPreset", 3)
        put("qualityToggle", true)
        put("cfg_rescale", 0f)
        put("noise_schedule", "karras")
        put("negative_prompt", negative)
        put("sm", false)
        put("sm_dyn", false)
        put("dynamic_thresholding", false)
        put("legacy", false)
        put("legacy_uc", false)
        put("legacy_v3_extend", false)
        put("add_original_image", true)
        put("controlnet_strength", 1)
        put("skip_cfg_above_sigma", JsonNull)
        // stream=msgpack 是官方前端口径，部分中转据此判定请求合法
        put("stream", "msgpack")
        put("use_coords", false)
        put("normalize_reference_strength_multiple", true)
        // 欧拉祖先采样器的官方前端标记（柏宝绘同款）：影响采样轨迹，缺失会让同参数下画质略差
        val effectiveSampler =
            params.naiParameters?.get("sampler")?.jsonPrimitive?.content ?: "k_euler_ancestral"
        if (effectiveSampler == "k_euler_ancestral") {
            put("prefer_brownian", true)
            put("deliberate_euler_ancestral_bug", false)
        }
        // 空数组也要显式给，缺字段会被当成非法请求
        putJsonArray("reference_strength_multiple") { }
        if (isV3) {
            putJsonArray("reference_image_multiple") { }
            putJsonArray("reference_information_extracted_multiple") { }
        } else {
            putJsonArray("reference_image_multiple_cached") { }
        }
        put(
            "v4_prompt",
            buildJsonObject {
                put(
                    "caption",
                    buildJsonObject {
                        put("base_caption", params.prompt)
                        put("char_captions", buildJsonArray { })
                    }
                )
                put("use_coords", false)
                put("use_order", true)
            }
        )
        put(
            "v4_negative_prompt",
            buildJsonObject {
                put(
                    "caption",
                    buildJsonObject {
                        put("base_caption", negative)
                        put("char_captions", buildJsonArray { })
                    }
                )
                put("legacy_uc", false)
            }
        )
        if (vibeData.isNotEmpty()) {
            if (isV3) {
                put("reference_image_multiple", buildJsonArray { vibeData.forEach { add(it) } })
                // NAI3 的 information_extracted 是「从参考图提取多少信息」，与强度不是一回事
                put("reference_information_extracted_multiple", buildJsonArray { vibeStrengths.forEach { add(0.9f) } })
            } else {
                // NAI4+ 的 cached 必须是 {cache_secret_key, data} 对象数组，直接塞裸字符串会被拒
                put(
                    "reference_image_multiple_cached",
                    buildJsonArray {
                        vibeData.forEach { encoded ->
                            add(
                                buildJsonObject {
                                    put("cache_secret_key", java.util.UUID.randomUUID().toString())
                                    put("data", encoded)
                                }
                            )
                        }
                    }
                )
            }
            put("reference_strength_multiple", buildJsonArray { vibeStrengths.forEach { add(it) } })
        }
    }

    // 面板里的 NAI 参数覆盖默认值
    val merged = baseParameters.toMutableMap().apply {
        params.naiParameters?.forEach { (k, v) -> put(k, v) }
    }

    val requestedSeed = params.naiParameters
        ?.get("seed")
        ?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
        ?: -1L
    val count = params.numOfImages.coerceIn(1, 4)

    val items = mutableListOf<ImageGenerationItem>()
    var lastError: String? = null

    repeat(count) { index ->
        // 固定种子时按张递增，既保证可复现，又不让 4 张完全一样
        val seed = if (requestedSeed >= 0) requestedSeed + index
        else Random.nextLong(1L, 4_294_967_295L)

        val parameters = JsonObject(
            merged.toMutableMap().apply {
                put("seed", JsonPrimitive(seed))
                put("n_samples", JsonPrimitive(1))
                put("width", JsonPrimitive(width))
                put("height", JsonPrimitive(height))
            }
        )

        runCatching {
            sendNaiRequest(
                base = base,
                key = key,
                modelId = params.model.modelId,
                prompt = params.prompt,
                parameters = parameters,
                headers = params.customHeaders,
                httpClient = httpClient,
            )
        }.onSuccess { b64 ->
            items.add(ImageGenerationItem(data = b64, mimeType = "image/png", seed = seed))
        }.onFailure {
            lastError = it.message ?: "未知错误"
            android.util.Log.w("OpenAIProvider", "NAI 第 ${index + 1} 张失败：${it.message}")
        }
    }

    if (items.isEmpty()) error(lastError ?: "NAI 返回里没有图片")
    ImageGenerationResult(
        items = items,
        vibeEncoded = vibeEncoded + params.cachedVibes.count { it.encoded.isNotBlank() },
        vibeFailed = vibeFailed,
    )
}

/** 单次 NAI 请求，返回第一张图的 base64 */
private suspend fun sendNaiRequest(
    base: String,
    key: String,
    modelId: String,
    prompt: String,
    parameters: JsonObject,
    headers: List<CustomHeader>,
    httpClient: OkHttpClient,
): String {
    // 图生图 / 局部重绘：parameters 里带 image 即 img2img，带 mask 即 infill（蒙版重绘）。
    // 由插件经 extra 注入，宿主构造请求体时按 NAI 实际口径切 action。
    val hasImage = parameters["image"] != null
    val hasMask = parameters["mask"] != null
    val action = when {
        hasMask -> "infill"
        hasImage -> "img2img"
        else -> "generate"
    }
    // infill 必须走 inpainting 模型，用户没带后缀时自动补
    val effectiveModelId = if (hasMask && !modelId.endsWith("-inpainting")) "$modelId-inpainting" else modelId

    val body = buildJsonObject {
        put("input", prompt)
        put("model", effectiveModelId)
        put("action", action)
        put("parameters", parameters)
        // 官方前端同款标记，部分中转按它判断请求来自正式客户端
        put("use_new_shared_trial", true)
    }.toString()

    val request = Request.Builder()
        .url("$base/ai/generate-image")
        .headers(headers.toHeaders())
        .addHeader("Authorization", "Bearer $key")
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "application/zip")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    val response = httpClient.newCall(request).await()
    if (!response.isSuccessful) {
        error("NAI 生图失败：${response.code} ${response.body?.string()?.take(400)}")
    }

    val bytes = response.body?.bytes() ?: error("NAI 返回空响应")
    val contentType = response.header("Content-Type").orEmpty()

    if (contentType.contains("json") || (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte())) {
        val text = bytes.toString(Charsets.UTF_8)
        val parsed = parseImageResponse(text, httpClient).map { it.data }
        if (parsed.isNotEmpty()) return parsed.first()
        error("NAI 返回里没有图片：${text.take(300)}")
    }

    return unzipFirstImage(bytes) ?: error("NAI 返回的压缩包里没有图片")
}

private suspend fun encodeVibe(
    base: String,
    key: String,
    modelId: String,
    path: String,
    strength: Float,
    httpClient: OkHttpClient,
): String? = runCatching {
    val file = File(path)
    if (!file.exists()) return@runCatching null
    val imageB64 = java.util.Base64.getEncoder().encodeToString(file.readBytes())
    val body = buildJsonObject {
        put("image", imageB64)
        put("model", modelId)
        put("information_extracted", strength)
    }.toString()

    val request = Request.Builder()
        .url("$base/ai/encode-vibe")
        .addHeader("Authorization", "Bearer $key")
        .addHeader("Content-Type", "application/json")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    val response = httpClient.newCall(request).await()
    if (!response.isSuccessful) return@runCatching null
    val bytes = response.body?.bytes() ?: return@runCatching null
    java.util.Base64.getEncoder().encodeToString(bytes)
}.getOrNull()

private fun unzipFirstImage(bytes: ByteArray): String? = runCatching {
    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name.lowercase()
            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp")) {
                return@use java.util.Base64.getEncoder().encodeToString(zip.readBytes())
            }
            entry = zip.nextEntry
        }
        null
    }
}.getOrNull()
