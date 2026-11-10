/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader
 
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.uuid.Uuid
 
/**
 * 插件加载器
 * 负责加载和管理插件生命周期
 */
class PluginLoader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val memoryBankService: MemoryBankService? = null,
    private val settingsStore: SettingsStore? = null,
    private val providerManager: me.rerere.ai.provider.ProviderManager? = null,
    private val filesManager: me.rerere.rikkahub.data.files.FilesManager? = null,
    private val genMediaRepository: me.rerere.rikkahub.data.repository.GenMediaRepository? = null,
) {
 
    companion object {
        private const val TAG = "PluginLoader"
        /**
         * 单个插件 hook 执行的协程级超时。
         *
         * 设为略大于 nativeFetch 的最长超时上限 (15s), 避免协程层先于网络层触发,
         * 误把"请求还在正常进行"报成"超时"。
         *
         * 重要限制 (如实说明): 这层 withTimeoutOrNull 只能让"等待这次 hook 调用结果"
         * 提前放弃, 无法真正打断 QuickJS 引擎内部正在执行的同步 JS 代码 (比如
         * nativeFetch 内部还在跑的 OkHttp 请求)。因为 callEvent 全程跑在单线程
         * pluginDispatcher 上, 即使外层已放弃等待, 这个单线程仍会被卡住的那次调用
         * 占用, 直到底层请求真正结束 (最长 15s)。期间排在后面的其它 hook 调用、
         * 以及 callTool (也走这个 dispatcher) 都要继续排队。这是 QuickJS 单线程
         * 模型的本质限制, 核心阻塞问题已由 ChatService 改为 fire-and-forget 解决,
         * 这一层只是给"事件处理"一个明确失败信号和日志, 不是彻底防死锁。
         */
        private const val HOOK_TIMEOUT_MS = 16_500L
    }
 
    // 单线程调度器，确保所有 QuickJS 操作在同一线程执行
    private val pluginDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-quickjs").apply { isDaemon = true }
    }.asCoroutineDispatcher()
 
    // 已加载的插件缓存
    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()
 
    /**
     * 加载插件
     */
    suspend fun loadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = withContext(pluginDispatcher) {
        try {
            if (loadedPlugins.containsKey(pluginInfo.manifest.id)) {
                doUnloadPlugin(pluginInfo.manifest.id)
            }
 
            if (!pluginInfo.isEnabled) {
                return@withContext Result.failure(IllegalStateException("Plugin is disabled"))
            }
 
            val entryFile = pluginInfo.getEntryFile()
            if (!entryFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Entry file not found: ${pluginInfo.manifest.entry}")
                )
            }
 
            // 为此插件创建独立的 PluginDataStore，并注入沙箱
            val dataStore = PluginDataStore(context, pluginInfo.manifest.id)

            val sandbox = PluginSandbox(
                context = context,
                okHttpClient = okHttpClient,
                memoryBankService = memoryBankService,
                dataStore = dataStore,
                imageBridge = { action, params -> handleImageBridge(action, params) },
            )
            sandbox.allowedHosts = pluginInfo.manifest.allowedHosts
            sandbox.initialize()
 
            val resolvedConfig = resolveModelConfig(pluginInfo)
            sandbox.injectConfig(resolvedConfig)
 
            sandbox.evaluateFile(entryFile)
 
            val loadedPlugin = LoadedPlugin(
                info = pluginInfo,
                sandbox = sandbox
            )
 
            val exportedNames = sandbox.getExportedFunctionNames()
            Log.i(TAG, "Plugin ${pluginInfo.manifest.id} exported functions: $exportedNames")
 
            pluginInfo.manifest.tools.forEach { tool ->
                if (!sandbox.hasFunction(tool.name)) {
                    Log.w(TAG, "Tool '${tool.name}' declared in manifest but not found in exports (available: $exportedNames)")
                } else {
                    Log.i(TAG, "Tool '${tool.name}' registered successfully")
                }
            }
 
            loadedPlugins[pluginInfo.manifest.id] = loadedPlugin
            Result.success(loadedPlugin)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${pluginInfo.manifest.id}", e)
            Result.failure(e)
        }
    }
 
    private fun doUnloadPlugin(pluginId: String) {
        loadedPlugins.remove(pluginId)?.let { plugin ->
            plugin.sandbox.destroy()
            Log.d(TAG, "Unloaded plugin: $pluginId")
        }
    }
 
    suspend fun unloadPlugin(pluginId: String) = withContext(pluginDispatcher) {
        doUnloadPlugin(pluginId)
    }
 
    suspend fun reloadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = loadPlugin(pluginInfo)
 
    fun getLoadedPlugin(pluginId: String): LoadedPlugin? = loadedPlugins[pluginId]
 
    fun getAllLoadedPlugins(): List<LoadedPlugin> = loadedPlugins.values.toList()
 
    fun getEnabledPlugins(): List<LoadedPlugin> = loadedPlugins.values.filter { it.info.isEnabled }
 
    /**
     * 调用插件工具
     */
    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return withContext(pluginDispatcher) {
            try {
                val plugin = loadedPlugins[pluginId]
                    ?: return@withContext Result.failure(IllegalStateException("Plugin not loaded: $pluginId"))
 
                if (!plugin.hasTool(toolName)) {
                    return@withContext Result.failure(IllegalArgumentException("Tool not found: $toolName"))
                }
 
                val result = plugin.sandbox.callFunction(toolName, params)
                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call tool=$toolName in plugin=$pluginId", e)
                Result.failure(e)
            }
        }
    }
 
    /**
     * 触发插件事件
     *
     * 对订阅了该事件的每个插件 hook, 在单线程 pluginDispatcher 上串行执行。
     * 每次调用包一层 [HOOK_TIMEOUT_MS] 超时, 超时后记录警告并跳过, 继续处理
     * 同批次其它插件的 hook, 不让单个插件拖累整批。
     */
    suspend fun callEvent(event: String, params: JsonElement) {
        withContext(pluginDispatcher) {
            for (plugin in loadedPlugins.values) {
                if (!plugin.info.isEnabled) continue
                val matchingHooks = plugin.info.manifest.hooks.filter { it.event == event }
                for (hook in matchingHooks) {
                    try {
                        if (!plugin.sandbox.hasFunction(hook.handler)) {
                            Log.w(TAG, "Hook handler '${hook.handler}' not found in plugin ${plugin.id}")
                            continue
                        }
                        val completed = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
                            plugin.sandbox.callFunction(hook.handler, params)
                        }
                        if (completed == null) {
                            Log.w(
                                TAG,
                                "Plugin hook timed out after ${HOOK_TIMEOUT_MS}ms: " +
                                    "plugin=${plugin.id}, handler='${hook.handler}', event='$event'"
                            )
                        } else {
                            Log.d(TAG, "Event '$event' handled by plugin ${plugin.id}.${hook.handler}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to handle event='$event' in plugin=${plugin.id}.${hook.handler}", e)
                    }
                }
            }
        }
    }
 
    fun getPluginsWithDailyCron(): List<Pair<LoadedPlugin, String>> {
        return loadedPlugins.values.filter { it.info.isEnabled }.flatMap { plugin ->
            plugin.info.manifest.hooks
                .filter { it.event == "daily_cron" }
                .map { hook -> plugin to hook.handler }
        }
    }
 
    private fun resolveModelConfig(pluginInfo: PluginInfo): Map<String, JsonElement> {
        val config = pluginInfo.config.toMutableMap()
        val store = settingsStore ?: return config
        val settings = store.settingsFlow.value
 
        pluginInfo.manifest.config.forEach { field ->
            if (field.type == "model") {
                val modelUuidStr = (config[field.name] as? JsonPrimitive)?.contentOrNull
                if (modelUuidStr.isNullOrBlank()) return@forEach
                try {
                    val modelUuid = Uuid.parse(modelUuidStr)
                    val model = settings.findModelById(modelUuid) ?: return@forEach
                    val provider = model.findProvider(settings.providers) ?: return@forEach
 
                    val baseUrl = when (provider) {
                        is ProviderSetting.OpenAI -> provider.baseUrl
                        is ProviderSetting.Google -> provider.baseUrl
                        is ProviderSetting.Claude -> provider.baseUrl
                    }
                    val apiKey = when (provider) {
                        is ProviderSetting.OpenAI -> provider.apiKey
                        is ProviderSetting.Google -> provider.apiKey
                        is ProviderSetting.Claude -> provider.apiKey
                    }
 
                    config[field.name] = JsonPrimitive(model.modelId)
                    config["${field.name}_base_url"] = JsonPrimitive(baseUrl)
                    config["${field.name}_api_key"] = JsonPrimitive(apiKey)
                    Log.d(TAG, "Resolved model config '${field.name}': modelId=${model.modelId}, baseUrl=$baseUrl")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve model config '${field.name}': ${e.message}")
                }
            }
        }
        return config
    }
 
    suspend fun unloadAll() = withContext(pluginDispatcher) {
        loadedPlugins.keys.toList().forEach { doUnloadPlugin(it) }
    }

    /**
     * 生图桥接：插件只传参数，宿主负责发请求、解包、落盘。
     * 这样 QuickJS 不用处理 zip 二进制，插件也能真正产出图片。
     */
    private fun handleImageBridge(action: String, paramsJson: String): String {
        if (action != "generate") {
            return """{"success":false,"error":"unknown action: $action"}"""
        }
        val pm = providerManager
        val fm = filesManager
        val repo = genMediaRepository
        if (pm == null || fm == null || repo == null) {
            return """{"success":false,"error":"image bridge not wired"}"""
        }

        return kotlinx.coroutines.runBlocking {
            try {
                val obj = org.json.JSONObject(paramsJson)
                val baseUrl = obj.optString("baseUrl").trim().trimEnd('/')
                if (baseUrl.isBlank()) {
                    return@runBlocking """{"success":false,"error":"请先在插件配置里填站点地址"}"""
                }
                val apiKey = obj.optString("apiKey")
                val protocol = obj.optString("protocol", "NOVELAI")
                val modelId = obj.optString("model", "nai-diffusion-4-5-full")
                val prompt = obj.optString("prompt")
                val negative = obj.optString("negative")
                val width = obj.optInt("width", 832).coerceIn(64, 2048)
                val height = obj.optInt("height", 1216).coerceIn(64, 2048)
                val count = obj.optInt("count", 1).coerceIn(1, 4)
                val seed = obj.optLong("seed", -1L)
                val steps = obj.optInt("steps", 28).coerceIn(1, 50)
                val scale = obj.optDouble("scale", 5.0).toFloat().coerceIn(0f, 10f)
                val sampler = obj.optString("sampler", "Euler Ancestral")
                val noiseSchedule = obj.optString("noiseSchedule", "Karras")
                val ucPreset = obj.optInt("ucPreset", 0).coerceIn(0, 3)
                val sm = obj.optBoolean("sm", false)
                val smDyn = obj.optBoolean("smDyn", false)
                val varietyBoost = obj.optBoolean("varietyBoost", false)
                val dynamicThresholding = obj.optBoolean("dynamicThresholding", false)

                // 插件可以带已编码的 vibe（NAI 的 .naiv4vibe），这里直接透传给 provider
                val cachedVibes = buildList {
                    obj.optJSONArray("vibes")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val v = arr.optJSONObject(i) ?: continue
                            val encoded = v.optString("encoded")
                            if (encoded.isNotBlank()) {
                                add(
                                    me.rerere.ai.provider.CachedVibe(
                                        encoded = encoded,
                                        strength = v.optDouble("strength", 0.6).toFloat().coerceIn(0f, 1f),
                                        model = v.optString("model"),
                                    )
                                )
                            }
                        }
                    }
                }

                // 两种协议的前缀规则不一样：NAI 原生是 {根}/ai/generate-image，
                // OpenAI 是 {根}/v1/images/generations。用户只填一个地址，这里按协议自动纠正，
                // 免得「填了 /v1 走原生变 404」「没填 /v1 走兼容变 404」两头踩坑。
                val naiBase = if (baseUrl.endsWith("/v1")) baseUrl.dropLast(3) else baseUrl
                val openAiBase = if (Regex("^https?://[^/]+$").matches(baseUrl)) "$baseUrl/v1" else baseUrl
                val isOpenAiProtocol = protocol.equals("OPENAI", true)

                val setting = me.rerere.ai.provider.ProviderSetting.OpenAI(
                    name = "Plugin",
                    baseUrl = if (isOpenAiProtocol) openAiBase else naiBase,
                    apiKey = apiKey,
                    imageProtocol = if (isOpenAiProtocol) {
                        me.rerere.ai.provider.ImageProtocol.OPENAI
                    } else {
                        me.rerere.ai.provider.ImageProtocol.NOVELAI
                    },
                )
                val model = me.rerere.ai.provider.Model(
                    modelId = modelId,
                    displayName = modelId,
                    type = me.rerere.ai.provider.ModelType.IMAGE,
                )
                val samplerKey = when (sampler) {
                    "Euler" -> "k_euler"
                    "DPM++ 2M SDE" -> "k_dpmpp_2m_sde"
                    "DPM++ SDE" -> "k_dpmpp_sde"
                    "DPM++ 2S Ancestral" -> "k_dpmpp_2s_ancestral"
                    "DPM++ 2M" -> "k_dpmpp_2m"
                    "DDIM" -> "ddim_v3"
                    else -> "k_euler_ancestral"
                }
                val noiseKey = when (noiseSchedule.lowercase()) {
                    "native" -> "native"
                    "exponential" -> "exponential"
                    "polyexponential" -> "polyexponential"
                    else -> "karras"
                }
                // org.json → kotlinx.serialization.json.JsonElement（用于透传插件 extra 的 NAI 原生参数）
                fun jsonElementFromOrg(o: Any?): JsonElement = when (o) {
                    null -> JsonNull
                    is Boolean -> JsonPrimitive(o)
                    is Number -> JsonPrimitive(o)
                    is String -> JsonPrimitive(o)
                    is org.json.JSONObject -> JsonObject(
                        o.keys().asSequence().associate { k -> k to jsonElementFromOrg(o.get(k)) }
                    )
                    is org.json.JSONArray -> JsonArray(
                        (0 until o.length()).map { jsonElementFromOrg(o.get(it)) }
                    )
                    else -> JsonNull
                }

                val naiParamsBase = kotlinx.serialization.json.buildJsonObject {
                    put("steps", steps)
                    put("scale", scale)
                    put("seed", seed)
                    put("sampler", samplerKey)
                    put("noise_schedule", noiseKey)
                    put("ucPreset", ucPreset)
                    put("sm", sm)
                    put("sm_dyn", smDyn)
                    // variety 正确映射为 NAI 实际字段 skip_cfg_above_sigma（替代此前无效的 variety_boost 键）
                    if (varietyBoost) put("skip_cfg_above_sigma", 19) else put("skip_cfg_above_sigma", JsonNull)
                    put("dynamic_thresholding", dynamicThresholding)
                    put("qualityToggle", true)
                }
                // 透传插件 extra 里的 NAI 原生参数（图生图 / 重绘 / 角色参考 / rescale 等），覆盖上面默认值
                val naiParams = runCatching {
                    val extraJson = obj.optJSONObject("extra")?.let { jsonElementFromOrg(it) } as? JsonObject
                    if (extraJson != null) {
                        JsonObject(
                            naiParamsBase.toMutableMap().apply {
                                extraJson.forEach { (k, v) -> put(k, v) }
                            }
                        )
                    } else {
                        naiParamsBase
                    }
                }.getOrElse { naiParamsBase }
                // OpenAI 兼容没有参数位。严格模式下只发 model/prompt/n/size 四个字段，
                // 多带任何未知字段都可能被网关判 400（Unrecognized request argument）。
                val strictOpenAI = obj.optBoolean("strictOpenAI", true)
                val customBodies = if (protocol.equals("OPENAI", true) && !strictOpenAI) {
                    buildList {
                        add(me.rerere.ai.provider.CustomBody("steps", kotlinx.serialization.json.JsonPrimitive(steps)))
                        add(me.rerere.ai.provider.CustomBody("cfg_scale", kotlinx.serialization.json.JsonPrimitive(scale)))
                        add(me.rerere.ai.provider.CustomBody("sampler", kotlinx.serialization.json.JsonPrimitive(sampler)))
                        add(me.rerere.ai.provider.CustomBody("seed", kotlinx.serialization.json.JsonPrimitive(seed)))
                        add(me.rerere.ai.provider.CustomBody("noise_schedule", kotlinx.serialization.json.JsonPrimitive(noiseKey)))
                        add(me.rerere.ai.provider.CustomBody("ucPreset", kotlinx.serialization.json.JsonPrimitive(ucPreset)))
                        add(me.rerere.ai.provider.CustomBody("sm", kotlinx.serialization.json.JsonPrimitive(sm)))
                        add(me.rerere.ai.provider.CustomBody("sm_dyn", kotlinx.serialization.json.JsonPrimitive(smDyn)))
                        add(me.rerere.ai.provider.CustomBody("variety_boost", kotlinx.serialization.json.JsonPrimitive(varietyBoost)))
                        add(me.rerere.ai.provider.CustomBody("dynamic_thresholding", kotlinx.serialization.json.JsonPrimitive(dynamicThresholding)))
                        if (negative.isNotBlank()) {
                            add(me.rerere.ai.provider.CustomBody("negative_prompt", kotlinx.serialization.json.JsonPrimitive(negative)))
                        }
                    }
                } else {
                    emptyList()
                }

                // 插件可传原始参考图路径，宿主经 /ai/encode-vibe 编码为 vibe（NAI 风格迁移）
                val rawRefPaths = buildList {
                    obj.optJSONArray("referenceImages")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val p = arr.optString(i)
                            if (p.isNotBlank()) add(p)
                        }
                    }
                }

                val params = me.rerere.ai.provider.ImageGenerationParams(
                    model = model,
                    prompt = prompt,
                    numOfImages = count,
                    width = width,
                    height = height,
                    negativePrompt = negative.ifBlank { null },
                    referenceImages = rawRefPaths,
                    cachedVibes = cachedVibes,
                    naiParameters = naiParams,
                    customBody = customBodies,
                )

                val result = if (protocol.equals("AUTO", true)) {
                    // AUTO：先按 NAI 原生打（柏宝绘同款路径，parameters 最完整）；
                    // 站点只做 OpenAI 兼容时会回 404/405，这时再回退，用户不用自己猜协议。
                    try {
                        pm.getProviderByType(setting).generateImage(setting, params)
                    } catch (e: Exception) {
                        val msg = (e.message ?: "").lowercase()
                        val shouldFallback = msg.contains("404") || msg.contains("405") ||
                            msg.contains("not found") || msg.contains("unsupported") ||
                            msg.contains("method not allowed")
                        if (!shouldFallback) throw e
                        val openAiSetting = me.rerere.ai.provider.ProviderSetting.OpenAI(
                            name = setting.name,
                            baseUrl = openAiBase,
                            apiKey = apiKey,
                            imageProtocol = me.rerere.ai.provider.ImageProtocol.OPENAI,
                        )
                        pm.getProviderByType(openAiSetting)
                            .generateImage(openAiSetting, params.copy(customBody = emptyList()))
                    }
                } else {
                    pm.getProviderByType(setting).generateImage(setting, params)
                }
                val imagesDir = fm.getImagesDir()
                val ts = System.currentTimeMillis()
                val paths = org.json.JSONArray()
                val seeds = org.json.JSONArray()

                result.items.forEachIndexed { index, item ->
                    val file = java.io.File(imagesDir, "${ts}_plugin_${index}.png")
                    fm.createImageFileFromBase64(item.data, file.absolutePath)
                    if (file.exists() && file.length() > 0) {
                        repo.insertMedia(
                            me.rerere.rikkahub.data.db.entity.GenMediaEntity(
                                path = "images/${file.name}",
                                modelId = modelId,
                                prompt = prompt,
                                createAt = ts,
                                type = me.rerere.rikkahub.data.db.entity.GenMediaEntity.TYPE_IMAGE_GENERATION,
                            )
                        )
                        paths.put(file.absolutePath)
                        item.seed?.let { seeds.put(it) }

                        // 出图参数 sidecar，图库可以直接回溯
                        runCatching {
                            val meta = org.json.JSONObject()
                                .put("body", prompt)
                                .put("full_prompt", prompt)
                                .put("model", modelId)
                                .put("seed", item.seed ?: seed)
                                .put("width", width)
                                .put("height", height)
                                .put("steps", steps)
                                .put("scale", scale)
                                .put("sampler", sampler)
                                .put("noise_schedule", noiseSchedule)
                                .put("vibe", cachedVibes.size)
                                .put("plugin", "com.orangechat.naigen")
                                .put("created_at", ts)
                            java.io.File(imagesDir, "${file.name}.json").writeText(meta.toString())
                        }
                    }
                }

                if (paths.length() == 0) {
                    """{"success":false,"error":"模型没有返回可用图片"}"""
                } else {
                    org.json.JSONObject()
                        .put("success", true)
                        .put("images", paths)
                        .put("seeds", seeds)
                        .toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "image bridge generate failed", e)
                org.json.JSONObject()
                    .put("success", false)
                    .put("error", e.message ?: "生成失败")
                    .toString()
            }
        }
    }
}