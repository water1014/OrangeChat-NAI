/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.imggen

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/**
 * NAI vibe 文件解析。
 *
 * 兼容：
 * · {identifier, information_extracted, encoded}   官方 .naiv4vibe
 * · {vibe: {encoded}} / {vibe: "base64"}
 * · {data: "base64"} / {vibe_data: ...} / {blob: ...}
 * · 顶层数组（取第一个对象）
 * · 整个文件是 base64 包裹的 JSON
 * · 没有 JSON 外壳、本身就是编码好的 vibe（纯 base64）
 */
object VibeJsonParser {

    fun parse(text: String): VibeJson? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // 有些导出会把 JSON 再 base64 一层，先试着解开
        val maybeDecoded = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            trimmed
        } else {
            runCatching {
                String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.trimStart().startsWith("{") } ?: trimmed
        }

        val root = runCatching { JSONTokener(maybeDecoded).nextValue() }.getOrNull()
        val obj = when (root) {
            is JSONObject -> root
            is JSONArray -> root.optJSONObject(0) ?: return null
            is String -> runCatching { JSONObject(root) }.getOrNull() ?: return null
            else -> null
        }

        if (obj == null) {
            // 没有 JSON 外壳：本身就是编码好的 vibe
            val looksBase64 = trimmed.length > 1000 && trimmed.matches(Regex("^[A-Za-z0-9+/=\\s]+$"))
            if (!looksBase64) return null
            return VibeJson(
                id = UUID.randomUUID().toString(),
                name = "Vibe（原始）",
                encoded = trimmed.replace("\\s".toRegex(), ""),
            )
        }

        // 官方 .naiv4vibe：encodings.<modelKey>.<hash>.encoding
        val fromEncodings = pickFromEncodings(obj)

        val encoded = fromEncodings?.second ?: pickEncoded(obj)
        if (encoded.isEmpty()) return null

        val importInfo = obj.optJSONObject("importInfo")

        val name = obj.optString("name")
            .ifBlank { obj.optString("identifier") }
            .ifBlank { obj.optString("file_name") }
            .ifBlank { "Vibe" }

        // 官方文件把「强度」放在 importInfo.strength，另有 information_extracted（提取度）
        val strength = (
            importInfo?.optDouble("strength", Double.NaN)?.takeIf { !it.isNaN() }
                ?: importInfo?.optDouble("information_extracted", 0.6)
                ?: obj.optDouble("strength", obj.optDouble("information_extracted", 0.6))
            ).toFloat().coerceIn(0.1f, 1f)

        val modelKey = fromEncodings?.first.orEmpty()
        val rawModel = importInfo?.optString("model").orEmpty()
        val model = if (rawModel.startsWith("nai-")) {
            rawModel
        } else {
            modelIdOf(rawModel.ifBlank { modelKey })
        }

        return VibeJson(
            id = UUID.randomUUID().toString(),
            name = name,
            encoded = encoded,
            strength = strength,
            model = model,
        )
    }

    /** 官方结构化文件：返回 (modelKey, encoding, params) */
    private fun pickFromEncodings(obj: JSONObject): Triple<String, String, String>? {
        val encodings = obj.optJSONObject("encodings") ?: return null
        var fallback: Triple<String, String, String>? = null

        val modelKeys = encodings.keys()
        while (modelKeys.hasNext()) {
            val modelKey = modelKeys.next()
            val modelObj = encodings.optJSONObject(modelKey) ?: continue
            val innerKeys = modelObj.keys()
            while (innerKeys.hasNext()) {
                val inner = modelObj.optJSONObject(innerKeys.next()) ?: continue
                val encoding = inner.optString("encoding", "")
                if (encoding.length <= 64) continue
                val item = Triple(modelKey, clean(encoding), inner.optString("params"))
                // 同时含多套编码时优先 4.5 full，与默认模型一致
                if (modelKey.contains("4-5full", ignoreCase = true)) return item
                if (fallback == null) fallback = item
            }
        }
        return fallback
    }

    private fun modelIdOf(modelKey: String): String = when {
        modelKey.contains("4-5full", true) -> "nai-diffusion-4-5-full"
        modelKey.contains("4-5curated", true) -> "nai-diffusion-4-5-curated"
        modelKey.contains("4-full", true) -> "nai-diffusion-4-full"
        modelKey.contains("4-curated", true) -> "nai-diffusion-4-curated"
        modelKey.contains("v3", true) -> "nai-diffusion-3"
        else -> ""
    }

    private fun pickEncoded(obj: JSONObject): String {
        // 预设组格式：{"name":"...","vibes":[{"encoded":"...","strength":0.6}]}
        listOf("vibes", "vibe_list", "items").forEach { key ->
            obj.optJSONArray(key)?.optJSONObject(0)?.let { first ->
                val value = first.optString("encoded", "")
                if (value.length > 64) return clean(value)
            }
        }

        val keys = listOf("encoded", "vibe", "vibe_data", "vibeData", "data", "blob", "state")
        keys.forEach { key ->
            val raw = obj.optString(key, "")
            if (raw.length > 64) return clean(raw)

            obj.optJSONObject(key)?.let { nested ->
                listOf("encoded", "vibe", "data").forEach { inner ->
                    val value = nested.optString(inner, "")
                    if (value.length > 64) return clean(value)
                }
            }

            obj.optJSONArray(key)?.let { arr ->
                if (arr.length() > 64) {
                    val bytes = ByteArray(arr.length()) { arr.optInt(it, 0).toByte() }
                    return Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            }
        }
        return ""
    }

    /** 去掉 data URL 前缀与换行，接口要纯 base64 */
    private fun clean(encoded: String): String {
        val stripped = encoded.substringAfter("base64,", encoded)
        return stripped.replace("\n", "").replace("\r", "").trim()
    }
}
