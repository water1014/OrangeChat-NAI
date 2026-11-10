/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.plugin.loader.LoadedPlugin
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginToolDefinition

/**
 * 插件工具提供者
 * 将插件工具转换为AI可用的Tool对象
 */
class PluginToolProvider(
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 生图统一走「设置 → 生图」的内置配置。
     *
     * naigen 插件会在 promptTemplate 里要求模型"优先调用 naigen__nai_generate"，
     * 而插件请求带的是插件自己的一套参数，会绕过内置 NaiParams / 出图渠道。
     * 这里把生图类插件的工具注册、提示词注入一并屏蔽，保证 AI 画图只能用
     * 内置的 generate_image（读同一份生图设置）。
     */
    companion object {
        private val BLOCKED_IMAGE_GEN_PLUGIN_IDS = setOf("com.orangechat.naigen")
        private val BLOCKED_IMAGE_GEN_TOOL_NAMES = setOf("nai_generate")
    }

    /** 是否是需要屏蔽的生图类插件 */
    private fun isImageGenPlugin(plugin: LoadedPlugin): Boolean {
        val manifest = plugin.info.manifest
        return plugin.id in BLOCKED_IMAGE_GEN_PLUGIN_IDS ||
            manifest.id in BLOCKED_IMAGE_GEN_PLUGIN_IDS ||
            manifest.tools.any { it.name in BLOCKED_IMAGE_GEN_TOOL_NAMES }
    }

    /** 是否是需要屏蔽的生图工具 */
    private fun isBlockedTool(name: String): Boolean =
        name in BLOCKED_IMAGE_GEN_TOOL_NAMES

    /** 已排除生图插件的插件列表 */
    private fun availablePlugins(): List<LoadedPlugin> =
        pluginLoader.getAllLoadedPlugins().filterNot { isImageGenPlugin(it) }

    /**
     * 获取所有插件提供的工具
     * 会等待插件初始化完成，确保竞态条件下不会返回空列表
     */
    suspend fun getTools(): List<Tool> {
        // 等待插件初始化完成，避免竞态条件导致工具列表为空
        pluginManager.awaitInitialization()
        // 生图只走内置设置：不注册插件的 nai_generate 等生图工具
        return availablePlugins().flatMap { plugin ->
            plugin.info.manifest.tools
                .filterNot { isBlockedTool(it.name) }
                .map { toolDef ->
                    createTool(plugin, toolDef)
                }
        }
    }

    /**
     * 获取指定插件的工具
     */
    suspend fun getPluginTools(pluginId: String): List<Tool> {
        pluginManager.awaitInitialization()
        val plugin = pluginLoader.getLoadedPlugin(pluginId) ?: return emptyList()
        if (isImageGenPlugin(plugin)) return emptyList()
        return plugin.info.manifest.tools
            .filterNot { isBlockedTool(it.name) }
            .map { toolDef ->
                createTool(plugin, toolDef)
            }
    }

    /**
     * 创建Tool对象
     */
    private fun createTool(plugin: LoadedPlugin, toolDef: PluginToolDefinition): Tool {
        return Tool(
            name = ToolNaming.buildPluginToolName(plugin.id, toolDef.name),
            description = buildDescription(plugin, toolDef),
            needsApproval = true,
            parameters = {
                InputSchema.Obj(
                    properties = buildParameters(toolDef),
                    required = toolDef.parameters.filter { it.required }.map { it.name }
                )
            },
            execute = { params ->
                executeTool(plugin, toolDef, params)
            }
        )
    }

    /**
     * 构建工具描述
     */
    private fun buildDescription(plugin: LoadedPlugin, toolDef: PluginToolDefinition): String {
        val sb = StringBuilder()
        sb.appendLine(toolDef.description)
        sb.appendLine()
        sb.appendLine("Provided by plugin: ${plugin.info.manifest.name} (${plugin.info.manifest.id})")
        return sb.toString().trim()
    }

    /**
     * 构建参数定义
     */
    private fun buildParameters(toolDef: PluginToolDefinition): JsonObject {
        return buildJsonObject {
            toolDef.parameters.forEach { param ->
                put(param.name, buildJsonObject {
                    put("type", param.type)
                    if (param.description != null) {
                        put("description", param.description)
                    }
                    // 根据类型添加额外信息
                    when (param.type) {
                        "array" -> {
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        }
                        "object" -> {
                            // 可以在这里添加properties定义
                        }
                    }
                })
            }
        }
    }

    /**
     * 执行工具
     */
    private suspend fun executeTool(
        plugin: LoadedPlugin,
        toolDef: PluginToolDefinition,
        params: JsonElement
    ): List<UIMessagePart> {
        val result = pluginLoader.callTool(
            pluginId = plugin.id,
            toolName = toolDef.name,
            params = params
        )

        return result.fold(
            onSuccess = { jsonElement ->
                val resultStr = json.encodeToString(JsonElement.serializer(), jsonElement)
                val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text(resultStr))

                // 插件生图会返回 {images:[本地路径]}，这里转成图片部件，直接展示在对话里
                val images = (jsonElement as? JsonObject)?.get("images") as? JsonArray
                images?.forEach { element ->
                    val path = (element as? JsonPrimitive)?.contentOrNull
                    if (path != null && java.io.File(path).exists()) {
                        parts.add(UIMessagePart.Image(url = "file://$path"))
                    }
                }
                parts
            },
            onFailure = { error ->
                val errorObj = buildJsonObject {
                    put("success", false)
                    put("error", error.message ?: "Unknown error")
                }
                listOf(UIMessagePart.Text(errorObj.toString()))
            }
        )
    }

    /**
     * 获取插件的提示词注入
     *
     * 第一个元素: 自动生成的"插件能力总览"(当存在声明了工具的插件时),
     *             让模型不仅"看见"插件工具, 还被明确提醒要主动使用.
     * 后续元素: 各插件 manifest.promptTemplate 中开启了 inject_as_prompt 的模板(保留原机制).
     */
    suspend fun getPluginPromptInjections(): List<String> {
        // 等待插件初始化完成，避免竞态条件
        pluginManager.awaitInitialization()

        // 生图插件的 promptTemplate 会让模型"优先调用插件生图"，必须一并排除
        val plugins = availablePlugins()
        val pluginsWithTools = plugins.filter { it.info.manifest.tools.isNotEmpty() }

        // 只保留"主动性引导"这句话 + 插件名字列表,
        // 不再逐条重复每个工具的 name/description——完整的工具定义
        // (含 name/description/参数 schema) 已独立存在于发给模型的 tools 参数里,
        // 在这里重复列一遍是纯粹的体积浪费。
        val overview = if (pluginsWithTools.isNotEmpty()) {
            val pluginNames = pluginsWithTools.joinToString("、") { it.info.manifest.name }
            "你当前装载了以下插件提供的工具（完整工具列表和参数见 tools 定义）：${pluginNames}。" +
                "不要只在用户明确点名某个工具时才使用——只要对话场景与某个工具的用途相关，就应该主动考虑调用它，而不是被动等待用户指示。" +
                "部分工具绑定的是持续性的人设/系统状态（例如经营、社交、记录类），更需要你自己记得在合适的时机调用，而不是等用户提醒。"
        } else {
            null
        }

        val manualTemplates = plugins.mapNotNull { plugin ->
            val manifest = plugin.info.manifest
            val promptTemplate = manifest.promptTemplate ?: return@mapNotNull null
            // 检查 inject_as_prompt 配置是否开启
            val injectConfig = plugin.info.config["inject_as_prompt"]
            val shouldInject = when (injectConfig) {
                is kotlinx.serialization.json.JsonPrimitive -> {
                    injectConfig.content == "true"
                }
                else -> false
            }
            if (shouldInject) promptTemplate else null
        }

        return buildList {
            if (overview != null) add(overview)
            addAll(manualTemplates)
        }
    }

    /**
     * 获取工具统计信息
     */
    fun getToolStats(): ToolStats {
        val plugins = availablePlugins()
        val totalTools = plugins.sumOf { it.info.manifest.tools.size }

        return ToolStats(
            totalPlugins = plugins.size,
            totalTools = totalTools,
            pluginDetails = plugins.map { plugin ->
                PluginToolDetail(
                    pluginId = plugin.id,
                    pluginName = plugin.info.manifest.name,
                    toolCount = plugin.info.manifest.tools.size,
                    toolNames = plugin.info.manifest.tools.map { it.name }
                )
            }
        )
    }

    /**
     * 工具统计信息
     */
    data class ToolStats(
        val totalPlugins: Int,
        val totalTools: Int,
        val pluginDetails: List<PluginToolDetail>
    )

    /**
     * 插件工具详情
     */
    data class PluginToolDetail(
        val pluginId: String,
        val pluginName: String,
        val toolCount: Int,
        val toolNames: List<String>
    )
}