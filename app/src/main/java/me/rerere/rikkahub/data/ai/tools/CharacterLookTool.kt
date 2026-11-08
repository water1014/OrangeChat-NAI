/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.imggen.CharacterLook
import me.rerere.rikkahub.data.imggen.ImgGenPresetStore
import kotlin.uuid.Uuid

private const val CHARACTER_TAG = "CharacterLookTool"

/**
 * 角色外貌档案工具（参考柏宝绘的「角色外貌集中维护」）。
 *
 * 剧情里出现一个新角色、或者角色换了造型时，让 AI 把「只与视觉有关」的描述写进档案；
 * 之后提示词里只要写 `@名字`，生图前的拼装层就会展开成这段外貌，图片不会一张一个样。
 */
fun createCharacterLookTool(context: Context): Tool = Tool(
    name = "save_character_look",
    description = """
        Save or update a character's fixed visual appearance for image generation.

        When to use:
        - the user introduces a character and describes how they look;
        - the story reveals new appearance details (new outfit, haircut, scar) that should stay consistent;
        - before generating a picture of a recurring character, to make sure the look is on file.

        How to write `look`:
        - Only visual, stable traits: hair colour/style/length, eye colour, skin tone, face shape,
          height/build, usual outfit, distinctive accessories. Keep it as a comma separated phrase
          in English, e.g. "long silver hair, violet eyes, pale skin, white shirt, thin frame".
        - No personality, backstory, relationships or transient pose/emotion — those change per scene.
        - Do NOT include the character name inside `look`.

        If a character with the same name exists, it is replaced by default (set replace=false to keep
        the old one). After saving, the user can type @Name in the prompt and the appearance will be
        expanded automatically. Returns the updated character list so you can confirm what is on file.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        """
        你可以维护角色外貌档案（save_character_look）。规则：
        · 只在用户明确描述了外貌、或剧情里出现了需要长期一致的角色时写入，不要凭剧情推测外貌；
        · look 只写视觉特征（发色/瞳色/肤色/体型/常穿服饰/标志性配饰），用英文逗号分隔；
        · 不要写性格、经历、关系、当前动作或表情；
        · 同一个角色不要重复建档，名字保持一致（用用户称呼的名字）；
        · 保存后如果接下来要画这个角色，提示词里写 @名字 即可，不要再重复描写一遍外貌。
        """.trimIndent()
    },
    needsApproval = false,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Character name used after @ in prompts")
                }
                putJsonObject("look") {
                    put("type", "string")
                    put("description", "Comma separated English visual traits (hair, eyes, skin, outfit...)")
                }
                putJsonObject("replace") {
                    put("type", "boolean")
                    put("description", "Replace existing entry with the same name. Default true")
                }
            },
            required = listOf("name", "look")
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val look = obj["look"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val replace = obj["replace"]?.jsonPrimitive?.booleanOrNull ?: true

        if (name.isBlank() || look.isBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "name 与 look 都不能为空")
                    }.toString()
                )
            )
        }

        try {
            val store = ImgGenPresetStore(context)
            val existing = store.characters()
            val sameName = existing.firstOrNull { it.name.equals(name, ignoreCase = true) }

            val updated = when {
                sameName == null -> existing + CharacterLook(
                    id = Uuid.random().toString(),
                    name = name,
                    look = look,
                )

                replace -> existing.map {
                    if (it.id == sameName.id) it.copy(name = name, look = look) else it
                }

                else -> existing
            }

            if (updated != existing) store.setCharacters(updated)

            listOf<UIMessagePart>(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("name", name)
                        put("updated", sameName != null && replace)
                        put("total", updated.size)
                        put(
                            "characters",
                            buildJsonArray { updated.forEach { add(it.name) } }
                        )
                        put(
                            "hint",
                            "提示词里写 @$name 即可自动代入外貌：$look"
                        )
                    }.toString()
                )
            )
        } catch (e: Exception) {
            Log.e(CHARACTER_TAG, "save_character_look failed", e)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", e.message ?: "保存失败")
                    }.toString()
                )
            )
        }
    }
)
